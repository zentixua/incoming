#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["duckdb>=1.1,<2"]
# ///
"""Replay analysis database (README.md): replayscan mod tables in DuckDB, with views for choosing shots on top.

  uv run tools/replayscan/replaydb.py build <analysis directory> [--db file]   tables and views (replay.duckdb alongside)
  uv run tools/replayscan/replaydb.py sql <db> "<query>"                       any query, output as a table
  uv run tools/replayscan/replaydb.py report <db> [--out directory]            candidate shots: launches, flybys, impacts,
                                                                            chaos, distant impacts with silence before the rumble

The frame is defined by the vertical field of view and aspect ratio (--fov 70 --aspect 0.5625 is vertical 9:16): a point
is in frame if its |cx| ≤ aspect·tan(fov/2) and |cy| ≤ tan(fov/2) (cx, cy are offsets in the frame plane per unit depth,
sight.csv); "in the center of the frame" uses the same test with the --center fraction. A replay tick is 1/20 s;
report times are mm:ss from the start of the replay.
"""
import argparse
import json
import math
import re
import sys
from pathlib import Path

import duckdb

SOUND = 17.15  # blocks per tick: speed of sound at Incoming (BlastSounds)

TABLES = {
    "events": "e BIGINT, t INTEGER, dim VARCHAR, kind VARCHAR, src VARCHAR, x DOUBLE, y DOUBLE, z DOUBLE, size DOUBLE, "
              "uuid VARCHAR, clear INTEGER, solid DOUBLE, pre INTEGER, data VARCHAR",
    "blocks": "t INTEGER, dim VARCHAR, x INTEGER, y INTEGER, z INTEGER, state VARCHAR",
    "far": "t INTEGER, dim VARCHAR, src VARCHAR, uuid VARCHAR, x DOUBLE, y DOUBLE, z DOUBLE, data VARCHAR",
    "tracks": "t INTEGER, eid INTEGER, x DOUBLE, y DOUBLE, z DOUBLE, yaw DOUBLE, pitch DOUBLE, vx DOUBLE, vy DOUBLE, vz DOUBLE, ground INTEGER",
    "entities": "eid INTEGER, id INTEGER, uuid VARCHAR, type VARCHAR, name VARCHAR, subject INTEGER, player INTEGER, dim VARCHAR, "
                "first INTEGER, last INTEGER, x0 DOUBLE, y0 DOUBLE, z0 DOUBLE, x1 DOUBLE, y1 DOUBLE, z1 DOUBLE",
    "states": "t INTEGER, eid INTEGER, data VARCHAR",
    "sight": "t INTEGER, viewer VARCHAR, kind VARCHAR, ref VARCHAR, dist DOUBLE, ahead DOUBLE, cx DOUBLE, cy DOUBLE, "
             "hard DOUBLE, soft INTEGER, unknown DOUBLE",
    "viewers": "t INTEGER, viewer VARCHAR, dim VARCHAR, x DOUBLE, y DOUBLE, z DOUBLE, yaw DOUBLE, pitch DOUBLE",
    "time": "t INTEGER, game_time BIGINT, day_time BIGINT",
}

# Types: all that plans need is queries on tables; frame numbers - variables (fov, aspect, center, every)
VIEWS = r"""
CREATE OR REPLACE MACRO tanv() AS tan(radians(getvariable('fov')) / 2);
CREATE OR REPLACE MACRO in_frame(cx, cy, k) AS cx IS NOT NULL AND cy IS NOT NULL
    AND abs(cy) <= k * tanv() AND abs(cx) <= k * tanv() * getvariable('aspect');
-- the beam to the target is clear: no opaque block, no unknown chunk, transparent with shape (leaf, glass, water) - not
-- more --soft blocks
CREATE OR REPLACE MACRO clear_ray(hard, unknown, soft) AS coalesce(hard, -1) < 0 AND coalesce(unknown, -1) < 0
    AND coalesce(soft, 0) <= getvariable('soft');
CREATE OR REPLACE MACRO mmss(t) AS printf('%02d:%04.1f', CAST(floor(t / 1200) AS INTEGER), (t % 1200) / 20.0);

-- viewer camera axes: look f, right r, up u = r × f (as Scan.axes)
CREATE OR REPLACE VIEW eyes AS
WITH a AS (
  SELECT *, -sin(radians(yaw)) * cos(radians(pitch)) AS fx, -sin(radians(pitch)) AS fy, cos(radians(yaw)) * cos(radians(pitch)) AS fz,
         -cos(radians(yaw)) AS rx, -sin(radians(yaw)) AS rz
  FROM viewers)
SELECT *, -rz * fy AS ux, rz * fx - rx * fz AS uy, rx * fy AS uz FROM a;

-- explosions, hits and interceptions: game explosion packs and mod packs with "blast", "intercept", "detonation", "impact" in id
CREATE OR REPLACE VIEW blasts AS
SELECT * FROM events
WHERE x IS NOT NULL AND (src = 'ClientboundExplodePacket'
   OR (kind = 'payload' AND regexp_matches(src, ':(blast|intercept|nuke_detonation|bunker_impact)$')));

-- shells and other things that were watched (not by players)
CREATE OR REPLACE VIEW subjects AS SELECT * FROM entities WHERE subject = 1;
"""


def connect(db: Path, a) -> duckdb.DuckDBPyConnection:
    con = duckdb.connect(str(db))
    con.execute(f"SET VARIABLE fov = {float(a.fov)}")
    con.execute(f"SET VARIABLE aspect = {float(a.aspect)}")
    con.execute(f"SET VARIABLE center = {float(a.center)}")
    con.execute(f"SET VARIABLE soft = {int(a.soft)}")
    return con


def build(a) -> Path:
    scan = Path(a.dir)
    # without meta.json (the analysis was interrupted without finishing it) - the tables are as is, the analysis is not complete
    meta_file = scan / "meta.json"
    meta = json.loads(meta_file.read_text()) if meta_file.exists() else {"complete": False}
    db = Path(a.db) if a.db else scan / "replay.duckdb"
    if db.exists():
        db.unlink()
    con = connect(db, a)
    for name, columns in TABLES.items():
        cols = "{" + ", ".join(f"'{c.split()[0]}': '{' '.join(c.split()[1:])}'" for c in columns.split(", ")) + "}"
        con.execute(f"CREATE TABLE {name} AS SELECT * FROM read_csv('{scan / (name + '.csv.gz')}', header=true, columns={cols}, "
                    f"quote='\"', escape='\"', max_line_size=10000000, ignore_errors=true)")
    con.execute("CREATE TABLE meta AS SELECT ? AS json", [json.dumps(meta)])
    con.execute(f"SET VARIABLE every = {int(meta.get('every', 2))}")
    con.execute(VIEWS)
    impacts(con)
    con.close()
    print(f"{db}: {', '.join(f'{n} {c}' for n, c in meta.get('rows', {}).items())}; complete={meta.get('complete')}")
    return db


# secondary impact explosions: game explosions (without a mod package) no further and no later than this from the mod package - its stages.
#  For the Incoming cruise missile they are up to 10,6 block from the point of impact, at 3–9 tick of the timeline, and the game package goes away, when
# stage is removed - under the budget tick: on a busy server (volley) after the ninth tick
STAGE_REACH, STAGE_TICKS = 12, 20
# soil at a point on the server: Incoming puts “material” in the explosion package - from which the block is at the point of impact or under it
#  (water - too: impact at the surface); 8 - gravel, the server is also responsible for air, for blocks without their own soil and for
#  unfinished chunk (the gravel itself is stone)
MATERIAL_SRC, MATERIAL_UNSURE = r"^(airstrike|incoming):(blast|bunker_impact)$", 8


def material(data: str) -> int | None:
    try:
        m = json.loads(data).get("material")
    except (ValueError, AttributeError):
        return None
    return m if isinstance(m, int) else None


def impacts(con):
    """Impacts: explosions of one impact (mod package and game explosion packages, explosion stages) - in one line, with the type of place."""
    # in tick mod packs - before game explosions: steps find their blow
    rows = con.execute("SELECT e, t, dim, src, x, y, z, coalesce(size, 0), uuid, clear, solid, pre, data FROM blasts "
                       "ORDER BY t, src = 'ClientboundExplodePacket', e").fetchall()
    groups: list[dict] = []
    for e, t, dim, src, x, y, z, size, uuid, clear, solid, pre, data in rows:
        g = None
        if src == "ClientboundExplodePacket":
            # hit stage: the nearest mod package in STAGE_REACH blocks and STAGE_TICKS tick after it
            near = [(d2, g) for g in groups if g["anchor"] and g["dim"] == dim and 0 <= t - g["anchor_t"] <= STAGE_TICKS
                    and (d2 := (x - g["x"]) ** 2 + (y - g["y"]) ** 2 + (z - g["z"]) ** 2) <= STAGE_REACH ** 2]
            g = min(near, key=lambda n: n[0])[1] if near else None
        if g is None:
            # explosion projectile (uuid mod package): different salvo projectiles nearby - different impacts
            g = next((g for g in reversed(groups) if g["dim"] == dim and t - g["t1"] <= 3
                      and (x - g["x"]) ** 2 + (y - g["y"]) ** 2 + (z - g["z"]) ** 2 <= 36
                      and (uuid is None or g["uuid"] is None or uuid == g["uuid"])), None)
        if g is None:
            g = {"id": len(groups), "t": t, "t1": t, "dim": dim, "x": x, "y": y, "z": z, "events": [], "srcs": set(),
                 "power": 0.0, "uuid": None, "clear": None, "solid": None, "pre": 0, "anchor": False, "anchor_t": None,
                 "material": None}
            groups.append(g)
        if g["material"] is None and data and re.match(MATERIAL_SRC, src):
            g["material"] = material(data)
        g["t1"] = max(g["t1"], t)
        g["events"].append(e)
        g["srcs"].add(src)
        g["power"] = max(g["power"], size)
        # impact location - according to the mod package (point of impact), otherwise according to the first explosion
        if src != "ClientboundExplodePacket" and not g["anchor"]:
            g["x"], g["y"], g["z"], g["anchor"], g["anchor_t"] = x, y, z, True, t
        g["uuid"] = g["uuid"] or uuid
        if clear is not None and clear >= 0 and (g["clear"] is None or pre > g["pre"] or (pre == g["pre"] and clear < g["clear"])):
            g["clear"], g["pre"] = clear, max(g["pre"], pre)
        if solid is not None and solid >= 0 and (g["solid"] is None or solid < g["solid"]):
            g["solid"] = solid
    con.execute("CREATE TABLE impacts (id INTEGER, t INTEGER, t1 INTEGER, dim VARCHAR, x DOUBLE, y DOUBLE, z DOUBLE, power DOUBLE, "
                "uuid VARCHAR, clear INTEGER, solid DOUBLE, pre INTEGER, material INTEGER, intercept BOOLEAN, srcs VARCHAR, events BIGINT[])")
    if groups:
        con.executemany("INSERT INTO impacts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        [(g["id"], g["t"], g["t1"], g["dim"], g["x"], g["y"], g["z"], g["power"], g["uuid"], g["clear"], g["solid"], g["pre"],
                          g["material"], any(s.endswith(":intercept") for s in g["srcs"]), ",".join(sorted(g["srcs"])), g["events"])
                         for g in groups])
    # crater: blocks that became air in 6 blocks from the location behind 20 tick after being hit
    con.execute("""
        CREATE TABLE craters AS
        SELECT i.id, count(*) AS broken FROM impacts i JOIN blocks b
          ON b.dim = i.dim AND b.t BETWEEN i.t AND i.t1 + 20 AND b.state LIKE '%air'
         AND (b.x + 0.5 - i.x) ^ 2 + (b.y + 0.5 - i.y) ^ 2 + (b.z + 0.5 - i.z) ^ 2 <= 36
        GROUP BY i.id""")
    con.execute("""
        CREATE OR REPLACE VIEW impact_kinds AS
        SELECT i.*, coalesce(c.broken, 0) AS broken,
          CASE WHEN i.intercept THEN 'intercept'
               WHEN (i.solid IS NOT NULL AND i.solid <= 2.5) OR (i.clear IS NOT NULL AND i.clear <= 2) OR coalesce(c.broken, 0) > 0
                 OR coalesce(i.material, {UNSURE}) <> {UNSURE} THEN 'ground'
               WHEN i.solid IS NOT NULL AND i.clear IS NOT NULL THEN 'air'
               ELSE 'unknown' END AS place
        FROM impacts i LEFT JOIN craters c ON c.id = i.id""".replace("{UNSURE}", str(MATERIAL_UNSURE)))
    # hit with the viewer's eyes: geometry of the eye tick of the hit, ray - the best of the rays of the hit events
    con.execute("""
        CREATE OR REPLACE VIEW impact_views AS
        WITH g AS (
          SELECT k.*, v.viewer, v.x AS ex, v.y AS ey, v.z AS ez,
                 (k.x - v.x) * v.fx + (k.y - v.y) * v.fy + (k.z - v.z) * v.fz AS ahead,
                 (k.x - v.x) * v.rx + (k.z - v.z) * v.rz AS side,
                 (k.x - v.x) * v.ux + (k.y - v.y) * v.uy + (k.z - v.z) * v.uz AS upv,
                 sqrt((k.x - v.x) ^ 2 + (k.y - v.y) ^ 2 + (k.z - v.z) ^ 2) AS dist
          FROM impact_kinds k JOIN eyes v ON v.t = k.t AND v.dim = k.dim),
        rays AS (
          SELECT i.id, s.viewer, bool_or(clear_ray(s.hard, s.unknown, s.soft)) AS clear, bool_or(s.unknown >= 0) AS unknown,
                 min(s.soft) AS soft
          FROM impacts i, unnest(i.events) AS u(e) JOIN sight s ON s.kind = 'p' AND s.ref = CAST(u.e AS VARCHAR)
          GROUP BY i.id, s.viewer)
        SELECT g.id, g.t, g.dim, g.x, g.y, g.z, g.place, g.broken, g.power, g.uuid, g.srcs, g.viewer, g.dist,
               CASE WHEN g.ahead > 0 THEN g.side / g.ahead END AS cx, CASE WHEN g.ahead > 0 THEN g.upv / g.ahead END AS cy,
               r.clear, r.unknown, r.soft, g.t + g.dist / {SOUND} AS heard
        FROM g LEFT JOIN rays r ON r.id = g.id AND r.viewer = g.viewer""".replace("{SOUND}", str(SOUND)))
    # launch: first appearance of a projectile (according to UUID: leaving the range and returning - a new entity of the same UUID)
    # almost without speed (on the launcher) or next to the launcher; distance and visibility - through the eyes of each viewer:
    # samples - tick measurements (once in every) of the first 3 s, while the projectile is there, seen - of them in the middle of the frame and not closed
    #  (tick without a visibility line - outside the frame or further range: not visible)
    con.execute("""
        CREATE OR REPLACE VIEW launches AS
        WITH firsts AS (
          SELECT s.*, row_number() OVER (PARTITION BY s.uuid ORDER BY s.first) AS k FROM subjects s
          WHERE s.type NOT LIKE '%launcher%' AND s.type NOT LIKE '%booster%'),
        f AS (
          SELECT s.eid, s.uuid, s.type, s.dim, s.first, s.last, s.x0, s.y0, s.z0,
                 (SELECT max(sqrt(tr.vx ^ 2 + tr.vy ^ 2 + tr.vz ^ 2)) FROM tracks tr WHERE tr.eid = s.eid AND tr.t <= s.first + 3) AS v0,
                 (SELECT max(sqrt(tr.vx ^ 2 + tr.vy ^ 2 + tr.vz ^ 2)) FROM tracks tr WHERE tr.eid = s.eid AND tr.t BETWEEN s.first + 20 AND s.first + 60) AS v3,
                 (SELECT min(sqrt((l.x0 - s.x0) ^ 2 + (l.y0 - s.y0) ^ 2 + (l.z0 - s.z0) ^ 2)) FROM entities l
                   WHERE l.type LIKE '%launcher%' AND l.dim = s.dim AND l.first <= s.first AND l.last >= s.first) AS launcher
          FROM firsts s WHERE k = 1)
        SELECT f.*, v.viewer, sqrt((f.x0 - v.x) ^ 2 + (f.y0 - v.y) ^ 2 + (f.z0 - v.z) ^ 2) AS dist0,
          (SELECT count(*) FROM viewers w WHERE w.viewer = v.viewer AND w.dim = f.dim
             AND w.t BETWEEN f.first AND least(f.first + 60, f.last) AND w.t % getvariable('every') = 0) AS samples,
          (SELECT count(*) FROM sight s WHERE s.kind = 'e' AND s.ref = CAST(f.eid AS VARCHAR) AND s.viewer = v.viewer
             AND s.t BETWEEN f.first AND f.first + 60 AND in_frame(s.cx, s.cy, getvariable('center')) AND clear_ray(s.hard, s.unknown, s.soft)) AS seen
        FROM f JOIN eyes v ON v.t = f.first AND v.dim = f.dim
        WHERE coalesce(f.v0, 0) < 0.5 OR f.launcher <= 8""")
    # spans: how many ticks the projectile is in 10–60 blocks, in the frame and not closed
    con.execute("""
        CREATE OR REPLACE VIEW flybys AS
        SELECT s.viewer, e.eid, e.type, e.first, e.last, min(s.dist) AS closest, arg_min(s.t, s.dist) AS t_closest,
               count(*) FILTER (WHERE s.dist BETWEEN 10 AND 60 AND in_frame(s.cx, s.cy, 1) AND clear_ray(s.hard, s.unknown, s.soft)) * getvariable('every') AS seen_ticks,
               min(s.t) FILTER (WHERE s.dist BETWEEN 10 AND 60 AND in_frame(s.cx, s.cy, 1)) AS t_in
        FROM sight s JOIN subjects e ON s.kind = 'e' AND s.ref = CAST(e.eid AS VARCHAR)
        WHERE e.type NOT LIKE '%launcher%' AND e.type NOT LIKE '%booster%'
        GROUP BY s.viewer, e.eid, e.type, e.first, e.last""")
    # long-range projectiles (mod packages): geometry according to the eye of the same tick
    con.execute("""
        CREATE OR REPLACE VIEW far_views AS
        WITH g AS (
          SELECT f.*, v.viewer, (f.x - v.x) * v.fx + (f.y - v.y) * v.fy + (f.z - v.z) * v.fz AS ahead,
                 (f.x - v.x) * v.rx + (f.z - v.z) * v.rz AS side,
                 (f.x - v.x) * v.ux + (f.y - v.y) * v.uy + (f.z - v.z) * v.uz AS upv,
                 sqrt((f.x - v.x) ^ 2 + (f.y - v.y) ^ 2 + (f.z - v.z) ^ 2) AS dist
          FROM far f JOIN eyes v ON v.t = f.t AND v.dim = f.dim)
        SELECT t, viewer, src, uuid, x, y, z, dist, CASE WHEN ahead > 0 THEN side / ahead END AS cx,
               CASE WHEN ahead > 0 THEN upv / ahead END AS cy, data FROM g""")


def table(con, query: str, params=None, limit: int = 0):
    cur = con.execute(query, params or [])
    cols = [d[0] for d in cur.description]
    rows = cur.fetchall()
    if limit:
        rows = rows[:limit]
    widths = [max(len(c), *(len(fmt(r[i])) for r in rows)) if rows else len(c) for i, c in enumerate(cols)]
    print("  ".join(c.ljust(w) for c, w in zip(cols, widths)))
    for r in rows:
        print("  ".join(fmt(v).ljust(w) for v, w in zip(r, widths)))
    return cols, rows


def fmt(v) -> str:
    if v is None:
        return ""
    if isinstance(v, float):
        return f"{v:.2f}" if math.isfinite(v) else str(v)
    return str(v)


def sql(a):
    con = connect(Path(a.db), a)
    con.execute(f"SET VARIABLE every = {int(json.loads(con.execute('SELECT json FROM meta').fetchone()[0]).get('every', 2))}")
    table(con, a.query)


REPORT = {
    "launches": """
        SELECT mmss(first) AS at, first AS t, viewer, type, round(dist0) AS dist, launcher, round(v0, 2) AS v0, round(v3, 2) AS v3,
               seen, samples, eid, uuid
        FROM launches
        WHERE dist0 BETWEEN 30 AND 200 AND samples > 0 AND seen >= 0.8 * samples
        ORDER BY seen DESC, first""",
    "flybys": """
        SELECT mmss(t_in) AS at, t_in AS t, viewer, type, round(closest) AS closest, mmss(t_closest) AS closest_at, seen_ticks, eid
        FROM flybys WHERE seen_ticks >= 30
        ORDER BY seen_ticks DESC""",
    "ground_impacts": """
        SELECT mmss(t) AS at, t, viewer, round(dist) AS dist, round(cx, 2) AS cx, round(cy, 2) AS cy, broken, srcs, id
        FROM impact_views
        WHERE place = 'ground' AND dist BETWEEN 40 AND 300 AND in_frame(cx, cy, 1) AND clear
        ORDER BY t""",
    "chaos": """
        WITH hits AS (SELECT viewer, t FROM impact_views WHERE place = 'ground' AND in_frame(cx, cy, 1) AND coalesce(clear, false)),
             loud AS (SELECT viewer, heard, in_frame(cx, cy, 1) AS shown FROM impact_views),
             w AS (SELECT DISTINCT viewer, (t // 20) * 20 AS start FROM hits)
        SELECT mmss(w.start) AS at, w.start AS t, w.viewer,
               (SELECT count(*) FROM hits h WHERE h.viewer = w.viewer AND h.t BETWEEN w.start AND w.start + 199) AS ground_in_frame,
               (SELECT count(*) FROM loud l WHERE l.viewer = w.viewer AND l.heard BETWEEN w.start AND w.start + 199 AND l.shown) AS loud_on,
               (SELECT count(*) FROM loud l WHERE l.viewer = w.viewer AND l.heard BETWEEN w.start AND w.start + 199 AND NOT l.shown) AS loud_off
        FROM w ORDER BY ground_in_frame DESC, loud_off LIMIT 40""",
    "far_quiet": """
        SELECT mmss(i.t) AS at, i.t, i.viewer, i.place, round(i.dist) AS dist, round(i.heard - i.t) AS boom_after,
               round(i.cx, 2) AS cx, round(i.cy, 2) AS cy, i.id,
               (SELECT count(*) FROM impact_views o WHERE o.viewer = i.viewer AND o.id <> i.id
                  AND o.heard BETWEEN i.t - 40 AND i.heard + 40 AND o.dist < 2 * i.dist) AS other_loud
        FROM impact_views i
        WHERE i.dist BETWEEN 300 AND 1500 AND in_frame(i.cx, i.cy, 1) AND i.place <> 'intercept'
        ORDER BY other_loud, i.t""",
}


def report(a):
    db = Path(a.db)
    con = connect(db, a)
    con.execute(f"SET VARIABLE every = {int(json.loads(con.execute('SELECT json FROM meta').fetchone()[0]).get('every', 2))}")
    out = Path(a.out) if a.out else db.parent / "report"
    out.mkdir(parents=True, exist_ok=True)
    for name, query in REPORT.items():
        print(f"\n== {name}")
        table(con, query, limit=a.limit)
        con.execute(f"COPY ({query}) TO '{out / (name + '.csv')}' (HEADER)")
    print(f"\n{out}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--fov", type=float, default=70, help="vertical viewing angle, Grad")
    p.add_argument("--aspect", type=float, default=16 / 9, help="frame width to height (9:16 - 0.5625)")
    p.add_argument("--center", type=float, default=0.7, help="frame share for \"in the middle\"")
    p.add_argument("--soft", type=int, default=2, help="the ray is clear if there are no more than that many blocks of transparent shape in the path")
    sub = p.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("dir")
    b.add_argument("--db")
    s = sub.add_parser("sql")
    s.add_argument("db")
    s.add_argument("query")
    r = sub.add_parser("report")
    r.add_argument("db")
    r.add_argument("--out")
    r.add_argument("--limit", type=int, default=25)
    a = p.parse_args()
    {"build": build, "sql": sql, "report": report}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
