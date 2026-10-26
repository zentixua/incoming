#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["duckdb>=1.1,<2"]
# ///
"""Plan for shooting a preview of candidates (moments.py) - for shooting replayscan (-Dreplayscan.film, README there).

  uv run tools/clips/plan.py <clips>/candidates.json [--pick rockets-0,drones-1] [--all pov,fly] [--top 1] [--out <clips>/film]
         [--size 1080x1920] [--fps 60] [--replays <copy directory>]

Takes --top the best candidates of each roller (or --pick and all candidates of rollers --all), retrieves projectile paths from the database (tracks and distant
 lines far), previous impacts (smoke) and measurement and writes a plan for each replay: <out>/<replay>/plan.json; survey output; —
<out>/<replay>/film. <out>/runs.json - what to run: replay, plan and version of the Incoming record (jar of that day).
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "replayscan"))
import replaydb  # noqa: E402
from moments import BLAST, BLAST_LOOK, flights, incoming_version  # noqa: E402

# Thresholds for checking frames by type of shot (README replayscan, “Shooting”): the proportion of frames before the event where the object was filmed; same
# for the last second; proportion of events visible in the first 12 tick; smallest fraction of frame width; the engine is audible; loud
#  behind the scenes no more than `loud` per one in the frame (by default 1; less than 0 - not checked)
NEED = {
    "launch": {"subject": 0.6, "near": 0, "keys": 1, "share": 0.004},
    "flight": {"subject": 0.5, "near": 0, "keys": 1, "share": 0.004, "engine": 0.3},
    "impact": {"subject": 0.4, "near": 0.5, "keys": 1, "share": 0.004},
    # through the eyes of the player who shoots it down: the drone is not in the frame all the time before it is shot down, there is a battle going on all around - explosions can be heard behind the scenes
    "shootdown": {"subject": 0.25, "near": 0, "keys": 1, "share": 0.004, "loud": 3},
    # chaos - many blows at once: the preview is enough for half of the visible ones
    "chaos": {"subject": 0, "near": 0, "keys": 0.5, "share": 0},
    "far": {"subject": 0, "near": 0, "keys": 1, "share": 0},
    "nuke": {"subject": 0, "near": 0, "keys": 1, "share": 0},
    # through the eyes of the player as it is: where he looked was in the frame; loud behind the scenes - his fight is around
    "pov": {"subject": 0, "near": 0, "keys": 0, "share": 0, "loud": -1},
    # flying along a path set by a person: hits in the frame are counted, but do not decide; loud behind the scenes - raid around
    "fly": {"subject": 0, "near": 0, "keys": 0, "share": 0, "loud": -1},
    # pilot passing by the impact: the impact is visible in the first tick; the projectile flies into the frame when it arrives (the pilot is waiting for it, but it is not visible from behind the
    #  camera until it flies past); loud behind the scenes - raid around
    "fpv": {"subject": 0, "near": 0, "keys": 1, "share": 0, "loud": -1, "smother": 0.03},
}


def path_of(con, uuid: str, a: int, b: int) -> list[list[float]]:
    """Projectile path in [a − 60, b + 60]: places of entity (tracks), where it is not present - distant lines (far)."""
    rows = con.execute("""
        SELECT k.t, k.x, k.y, k.z FROM tracks k JOIN entities e ON e.eid = k.eid
        WHERE e.uuid = ? AND k.t BETWEEN ? AND ? ORDER BY k.t""", [uuid, a - 60, b + 60]).fetchall()
    seen = {r[0] for r in rows}
    far = con.execute("SELECT t, x, y, z FROM far WHERE uuid = ? AND t BETWEEN ? AND ? ORDER BY t", [uuid, a - 60, b + 60]).fetchall()
    out = sorted([list(r) for r in rows] + [list(r) for r in far if r[0] not in seen])
    return [[t, round(x, 2), round(y, 2), round(z, 2)] for t, x, y, z in out]


def dim_of(con, shot: dict) -> str:
    s = shot["subject"]
    if shot["camera"].get("dim"):
        return shot["camera"]["dim"]
    if shot["camera"]["kind"] == "pov":
        # through the eyes of the player - his measurement at the beginning of the plan (the marked moment is without events)
        r = con.execute("SELECT dim FROM viewers WHERE viewer = ? AND t >= ? ORDER BY t LIMIT 1", [shot["camera"]["viewer"], shot["a"]]).fetchone()
        if r:
            return r[0]
    if s.get("uuid"):
        r = con.execute("SELECT dim FROM entities WHERE uuid = ? LIMIT 1", [s["uuid"]]).fetchone()
        if r:
            return r[0]
    for k in s.get("keys", []):
        r = con.execute("SELECT dim FROM impacts WHERE id = ?", [k.get("id")]).fetchone()
        if r:
            return r[0]
    return "minecraft:overworld"


def cameras(shot: dict) -> list[dict]:
    """Cameras of the plan: the eyes of the player (with a spare free camera for impact, shoot down, launch and chaos; for the plan “through the eyes of
     players” - without a spare one), a camera along the way (flight) or a free camera by type."""
    cam, s, kind = shot["camera"], shot["subject"], shot["kind"]
    keys = s.get("keys", [])
    if cam["kind"] == "pilot":
        # the path is paved by filming through the world of replay (Pilot replayscan)
        return [{"kind": "pilot", "fov": cam["fov"]}]
    if cam["kind"] == "path":
        # and 16 blocks higher: the path on the map goes through a high-rise building
        return [{"kind": "path", "keys": cam["keys"], "fov": cam["fov"], "rise": cam.get("rise", [0, 16])}]
    if cam["kind"] == "pov":
        out = [{"kind": "pov", "player": cam["viewer"], "fov": 70}]
        if kind in ("impact", "shootdown") and keys:
            out.append({"kind": "tripod", "around": keys[0]["pos"], "dist": [60, 90, 130], "elev": [6, 16, 28], "share": 0.3,
                        "follow": True, "size": keys[0]["size"]})
        if kind == "chaos" and keys:
            # spare - wide shot over window strikes
            xs, zs = [k["pos"][0] for k in keys], [k["pos"][2] for k in keys]
            centre = [sum(xs) / len(xs), sum(k["pos"][1] for k in keys) / len(keys), sum(zs) / len(zs)]
            spread = max(max(xs) - min(xs), max(zs) - min(zs)) / 2
            out.append({"kind": "tripod", "around": centre, "dist": [max(90, 2.5 * spread), max(140, 3.5 * spread)],
                        "elev": [12, 25, 40], "share": 0.85, "follow": False, "size": 2 * spread + 30})
        if kind == "launch" and s.get("launch"):
            out.append({"kind": "tripod", "around": s["launch"], "dist": [35, 55, 80], "elev": [3, 10], "share": 0.15,
                        "follow": True, "size": 12})
        return out
    rig = cam.get("rig")
    # long lens from the player's place: the detonation happens behind the replay chunks (the nuke in the distance), the player's place is in them
    lens = [{"kind": "fixed", "eye": cam["lens"]["eye"], "aim": cam["lens"]["aim"], "fov": cam["lens"]["fov"],
             "rise": cam["lens"].get("rise", [0])}] if cam.get("lens") else []
    if rig == "lens":
        return [{"kind": "fixed", "eye": cam["eye"], "aim": "subject", "fov": cam["fov"], "rise": cam.get("rise", [0])}]
    if rig == "wide":
        spread = s.get("spread", 60)
        return [{"kind": "tripod", "around": s["centre"], "dist": cam["dist"], "elev": cam["elev"], "share": 0.85,
                 "follow": False, "size": 2 * spread + 30}]
    around = s.get("launch") if cam.get("around") == "launch" else keys[0]["pos"] if keys else s.get("centre")
    size = keys[0]["size"] if keys else 12
    return lens + [{"kind": "tripod", "around": around, "dist": cam["dist"], "elev": cam["elev"], "share": cam.get("share", 0.3),
                    "follow": kind != "nuke", "size": size}]


SMOKE_LIFE = 4800
# bomb: deeper than this under the top the explosion does not break out (warhead.BuriedBlast: reduced depth 1 m/kg^⅓ at charge
# 2423 kg TNT - 13,4 m) - without a ball; cloud - gases from the bomb
BREACH_DEPTH = 2423 ** (1 / 3)
# explosion type in explosion package (S2C.Blast: Shahed 0, rocket 1, bomb 2, MLRS 3; same in 2.10) → smoke type Plume
PACKET_LOOK = {0: 2, 1: 3, 2: 4, 3: 1}
_HIT_WEAPONS: dict[int, tuple] = {}


def smoke_row(t: int, x: float, y: float, z: float, surface: float | None, weapon: str | None, kind: int | None = None) -> list:
    """Impact smoke line for Plume replayscan: location, diameter of the ball, type of explosion - according to the explosion package, like the mod, without the
     package - according to the projectile weapon. The smoke of the bomb is above the top (surfaceY of the package, like FarBlasts.add), not in the thickness of the charge; the charge is deeper
    BREACH_DEPTH - type 5 (cloud without a ball)."""
    look = PACKET_LOOK.get(kind, BLAST_LOOK.get(weapon, 2))
    if look == 4 and surface is not None:
        if math.floor(int(surface) - y) > BREACH_DEPTH:
            look = 5
        y = surface
    return [t, x, y, z, BLAST.get(weapon, 16), look]


def hit_weapons(con) -> dict:
    """Impact (id) → his projectile weapon; once per base. The record also holds the database itself: the address of the closed one is occupied by the following one,
    , and one `id` would have given it someone else’s weapon."""
    hit = _HIT_WEAPONS.get(id(con))
    if hit is None or hit[0] is not con:
        hit = _HIT_WEAPONS[id(con)] = (con, {f.hit: f.weapon for f in flights(con) if f.hit is not None})
    return hit[1]


def film_shot(con, cid: str, shot: dict) -> dict:
    a, b, kind, s = shot["a"], shot["b"], shot["kind"], shot["subject"]
    subject = {"size": s.get("size", 4), "keys": [{"t": k["t"], "pos": k["pos"], "size": k["size"]} for k in s.get("keys", [])]}
    if s.get("uuid"):
        subject["uuid"] = s["uuid"]
        subject["path"] = path_of(con, s["uuid"], a, b)
    if s.get("centre"):
        subject["area"] = {"pos": s["centre"], "size": 2 * s.get("spread", 60)}
    dim = dim_of(con, shot)
    # incoming's smoke column lasts up to 4 minutes (former rocket column table - 4800 tick): strikes since then; in the frame of them -
    #  only live replay strikes (with a − warm, Plan.Shot.live in replayscan), the former ones - for checking the model with the frames
    #  nuclear explosion - without a line: it has its own picture, not the column of the explosion (of the projectile in there is no detonation in the package - according to the package)
    smoke = con.execute("""
        WITH k AS (SELECT id, t, x, y, z, events FROM impact_kinds
                   WHERE place = 'ground' AND dim = ? AND t BETWEEN ? AND ? AND srcs NOT LIKE '%nuke_detonation%'),
        s AS (SELECT k.id, max(TRY_CAST(json_extract(ev.data, '$.surfaceY') AS DOUBLE)) AS surface,
                     min(TRY_CAST(json_extract(ev.data, '$.kind') AS INTEGER)) AS kind
              FROM k, unnest(k.events) AS u(e) JOIN events ev ON ev.e = u.e WHERE regexp_matches(ev.src, ':blast$') GROUP BY k.id)
        SELECT k.id, k.t, k.x, k.y, k.z, s.surface, s.kind FROM k LEFT JOIN s ON s.id = k.id ORDER BY k.t""",
                        [dim, a - SMOKE_LIFE, b]).fetchall()
    # the diameter of the fireball and the type of explosion - according to the projectile weapon (impact smoke: model Plume replayscan); ICBM - without line:
    #  A nuclear explosion has its own picture, not an explosion column
    weapons = hit_weapons(con) if smoke else {}
    far = kind in ("far", "nuke", "fly")
    return {"name": f"{cid}-{shot['name']}", "a": a, "b": b, "dim": dim, "warm": 200, "hold": 100 if far or kind == "fpv" else 20,
            "far": far, "cameras": cameras(shot), "subject": subject,
            "smoke": [smoke_row(t, x, y, z, surface, weapons.get(i), blast) for i, t, x, y, z, surface, blast in smoke
                      if weapons.get(i) != "icbm"],
            "need": NEED.get(kind, NEED["impact"]), "why": shot.get("why", ""),
            **({"time": shot["camera"]["time"]} if "time" in shot["camera"] else {})}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("candidates")
    p.add_argument("--pick", default="", help="id candidates separated by commas (default - --top best of each video)")
    p.add_argument("--top", type=int, default=1)
    p.add_argument("--all", default="", help="videos in which to film all candidates (separated by commas; “through the eyes of the players” - pov, "
                                             "spans - fly)")
    p.add_argument("--out", default=None)
    p.add_argument("--size", default="1080x1920")
    p.add_argument("--fps", type=float, default=60)
    p.add_argument("--bitrate", type=int, default=40_000_000)
    p.add_argument("--tries", type=int, default=3)
    p.add_argument("--clouds", type=float, default=None, help="bottom of shader pack clouds (default - vanilla)")
    p.add_argument("--replays", default=None, help="directory of replay copies (by default - path from parsing)")
    a = p.parse_args()

    cands = json.loads(Path(a.candidates).read_text())
    out = Path(a.out) if a.out else Path(a.candidates).parent / "film"
    pick = [x for x in a.pick.split(",") if x]
    every = [x for x in a.all.split(",") if x]
    chosen = [c for k, v in cands.items() for c in v if c["id"] in pick or k in every] if pick or every \
        else [c for v in cands.values() for c in v[:a.top]]
    width, height = (int(x) for x in a.size.split("x"))
    by_replay: dict[str, dict] = {}
    for c in chosen:
        con = replaydb.connect(Path(c["db"]), argparse.Namespace(fov=70, aspect=width / height, center=0.7, soft=2))
        meta = json.loads(con.execute("SELECT json FROM meta").fetchone()[0])
        # path - from the launch directory: the shooting client reads the plan from its game directory
        zip_path = (Path(a.replays) / Path(meta["replay"]).name if a.replays else Path(meta["replay"])).resolve()
        plan = by_replay.setdefault(c["replay"], {
            "replay": str(zip_path), "out": str((out / c["replay"] / "film").resolve()), "width": width, "height": height,
            "fps": a.fps, "bitrate": a.bitrate, "tries": a.tries, "shots": [], "incoming": incoming_version(meta),
            "previews": {}})
        if a.clouds is not None:
            plan["clouds"] = a.clouds
        plan["previews"][c["id"]] = [f"{c['id']}-{s['name']}" for s in c["shots"]]
        plan["shots"] += [film_shot(con, c["id"], s) for s in c["shots"]]
        con.close()
    runs = []
    for name, plan in by_replay.items():
        d = out / name
        d.mkdir(parents=True, exist_ok=True)
        (d / "plan.json").write_text(json.dumps(plan, ensure_ascii=False, indent=1))
        runs.append({"replay": plan["replay"], "plan": str((d / "plan.json").resolve()), "incoming": plan["incoming"],
                     "shots": len(plan["shots"]), "previews": plan["previews"]})
    (out / "runs.json").write_text(json.dumps(runs, ensure_ascii=False, indent=1))
    for r in runs:
        print(f"{r['plan']}: {r['shots']} shots, Incoming {r['incoming']}, {', '.join(r['previews'])}")


if __name__ == "__main__":
    sys.exit(main())
