"""Scene bench run plan: scenes.json → harness steps (stand-plan.json, executed by tools/stand mod).

All geometry is here: target, launch point, camera position and look point, strikes needed to reach the
count, each impact point and launch time. Impact points do not use Incoming salvo spread, which uses the
shared world RNG (`SalvoData.fire`: `level.random`; no command/world-rule seed in 3.4.1 or later). Otherwise
runs land differently: the edge camera alternates between inside and beside the dust wall, and frame time
of two same-version runs differs by 30 %. Each point therefore uses its own command with spread 0
(`salvo drone 1 0 at P from L via P`); points follow a Vogel sunflower in the spread disk (uniform area,
each salvo/wave has its own rotation), identical for every version. Shahed miss remains (σ 3 m from the
spec, `Route.error`). Launch points form a circle at `launch.dist`: strike i uses point i mod launchers,
once per `launch.gap_s` from each point (one launcher sends a Shahed every 1–2 s). Shahed flies through
the target itself (`via`); without a waypoint it loops and approaches from behind the shooter. Nearby
launch scene (`launch`): camera 30 m beside the launch site at eye height, target far to the side (impacts
out of frame), windows from command time, without waiting for salvo completion (these scenes are last,
so their impacts do not enter other windows). Harness only executes numbers, so test the plan without
the game (test_gate.py).

Minecraft axes: +X east, +Z south. Camera `toward`: unit (dx, dz) from the site; launch bearing from south
toward east (0 from south, 90 from east).
"""
import json
import math
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(path=HERE / "scenes.json"):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _site(scenes, name):
    x, z = scenes["sites"][name]
    return float(x), float(z)


def camera(scenes, spec, site, spread):
    """Camera eye and look point from {toward, dist (number or "spread"), height, look_height, [look_toward, look_dist]}."""
    if isinstance(spec, str):
        spec = scenes["cameras"][spec]
    g = scenes["ground"]
    dist = spread if spec["dist"] == "spread" else spec["dist"]
    tx, tz = spec["toward"]
    eye = [site[0] + tx * dist, g + spec["height"], site[1] + tz * dist]
    if "look_toward" in spec:
        lx, lz = spec["look_toward"]
        look = [eye[0] + lx * spec["look_dist"], g + spec["look_height"], eye[2] + lz * spec["look_dist"]]
    else:
        look = [site[0], g + spec["look_height"], site[1]]
    return [round(v, 2) for v in eye], [round(v, 2) for v in look]


def launch_point(scenes, site, k):
    """Launch point k at a site: bearings around a circle at `launch.dist`."""
    bearings = scenes["launch"]["bearings"]
    b = math.radians(bearings[k % len(bearings)])
    d = scenes["launch"]["dist"]
    return round(site[0] + d * math.sin(b), 1), round(site[1] + d * math.cos(b), 1)


GOLDEN = math.pi * (3 - math.sqrt(5))


def aim_points(site, radius, n, turn):
    """n impact points in radius around a site: Vogel sunflower (point j at radius·√((j + ½)/n), angle j·golden
angle + turn), uniform area without randomness."""
    if radius <= 0:
        return [site] * n
    out = []
    for j in range(n):
        r, a = radius * math.sqrt((j + 0.5) / n), j * GOLDEN + turn
        out.append((round(site[0] + r * math.cos(a), 1), round(site[1] + r * math.sin(a), 1)))
    return out


def strike_commands(scenes, site, points, first_point, launchers, start_s=0.0):
    """Strike command: "salvo <weapon> 1 0 at P from L via P"; strike i launches at first_point + i mod launchers
after start_s + (i // launchers) · gap_s. Return commands, offsets and next launch point."""
    gap = scenes["launch"]["gap_s"]
    cmds, delays = [], []
    for i, (px, pz) in enumerate(points):
        fx, fz = launch_point(scenes, site, first_point + i % launchers)
        cmds.append(f"incoming salvo {scenes['weapon']} 1 0 at {px:g} {pz:g} from {fx:g} {fz:g} via {px:g} {pz:g}")
        delays.append(round(start_s + (i // launchers) * gap, 3))
    return cmds, delays, first_point + launchers


def launch_steps(scenes, s, tp):
    """Nearby launch: camera at launch site, launch commands, command-relative windows (`launch_view.windows`: start `at_s`, duration `s`).
No wait for salvo completion: impacts far away after the windows."""
    lv, ln = scenes["launch_view"], s["launch"]
    site = _site(scenes, s["site"])
    tx, tz = site[0] + lv["target"][0], site[1] + lv["target"][1]
    eye, look = camera(scenes, lv["camera"], site, 0)
    at = f"at {tx:g} {tz:g} from {site[0]:g} {site[1]:g}" + (f" via {tx:g} {tz:g}" if ln.get("via") else "")
    if ln.get("one_command"):
        cmds, delays = [f"incoming salvo {ln['weapon']} {ln['count']} 0 {at}"], [0.0]
    else:
        # One launcher: sequential launches every gap_s
        gap = scenes["launch"]["gap_s"]
        cmds = [f"incoming salvo {ln['weapon']} 1 0 {at}"] * ln["count"]
        delays = [round(i * gap, 3) for i in range(ln["count"])]
    out = [tp(eye, look), {"do": "salvo", "id": s["id"], "x": tx, "z": tz, "radius": scenes["impact_margin"], "expect": ln["count"],
                           "cmds": cmds, "delays": delays}]
    t = 0.0
    for w in lv["windows"]:
        if w["at_s"] > t:
            out.append({"do": "wait", "s": w["at_s"] - t})
        out.append({"do": "window", "name": f"{s['id']}/{w['name']}", "s": w["s"]})
        # Frame at window end (after it: screenshot costs a frame and is excluded)
        out.append({"do": "shot", "name": f"{s['id']}_{w['at_s'] + w['s']:g}s"})
        out.append({"do": "wait", "s": scenes["after_shot_s"]})
        t = w["at_s"] + w["s"] + scenes["after_shot_s"]
    return out


def launch_ids(scenes):
    """Nearby launch scenes: do not wait for their salvos to finish."""
    return {s["id"] for s in scenes["scenes"] if "launch" in s}


def build(scenes, only=None):
    """Plan from scenes (only: subset scene IDs, in scenes.json order)."""
    chosen = [s for s in scenes["scenes"] if only is None or s["id"] in only]
    unknown = set(only or ()) - {s["id"] for s in scenes["scenes"]}
    if unknown:
        raise ValueError(f"no scenes: {', '.join(sorted(unknown))}")
    margin = scenes["impact_margin"]
    struck = {}     # Strikes at the site before this scene
    point = {}      # Next launch point at the site
    steps = []
    ids = {s["id"] for s in chosen}
    launched = set()
    where = [None]  # Camera eye before the step: long-distance moves wait longer for chunks and meshes

    def tp(eye, look):
        far = where[0] is None or math.dist(where[0], eye) > scenes["far_m"]
        where[0] = eye
        return {"do": "tp", "eye": eye, "look": look, "settle_s": scenes["settle_far_s"] if far else scenes["settle_s"]}

    order = {s["id"]: i for i, s in enumerate(scenes["scenes"])}
    n_bearings = len(scenes["launch"]["bearings"])

    def salvo_step(s):
        site_name = s["site"]
        site = _site(scenes, site_name)
        spread = s.get("spread", 0)
        # Pattern rotation follows scene position in scenes.json (and wave number), not the subset: subsets retain the same points
        turn = order[s["id"]] * 16 * GOLDEN
        if "waves" in s:
            w = s["waves"]
            cmds, delays = [], []
            for i in range(w["count"]):
                # A wave launches from multiple launchers together: hits span several seconds, last wave fully inside the window
                c, d, point[site_name] = strike_commands(scenes, site, aim_points(site, spread, w["strikes"], turn + i * GOLDEN),
                                                         point.get(site_name, 0), w.get("launchers", n_bearings), i * w["every_s"])
                cmds += c
                delays += d
            count = w["strikes"] * w["count"]
        else:
            count = s["strikes"] if "strikes" in s else s["total"] - struck.get(site_name, 0)
            if count <= 0:
                raise ValueError(f"scene {s['id']}: site {site_name} already has {struck.get(site_name, 0)} strikes")
            delay = s.get("launch_delay_s", 0) if s.get("launch_with") in ids else 0
            cmds, delays, point[site_name] = strike_commands(scenes, site, aim_points(site, spread, count, turn), point.get(site_name, 0),
                                                             min(count, s.get("launchers", n_bearings)), delay)
        struck[site_name] = struck.get(site_name, 0) + count
        launched.add(s["id"])
        return {"do": "salvo", "id": s["id"], "x": site[0], "z": site[1], "radius": spread + margin, "expect": count,
                "cmds": cmds, "delays": delays}

    for s in chosen:
        site = _site(scenes, s["site"])
        spread = s.get("spread", 0)
        if "launch" in s:
            steps += launch_steps(scenes, s, tp)
            continue
        if s.get("impact_window"):
            eye, look = camera(scenes, s["camera"], site, spread)
            steps.append(tp(eye, look))
            if s["id"] not in launched:
                steps.append(salvo_step(s))
            # Scenes launched alongside this one (with an offset) follow its salvo immediately
            for t in chosen:
                if t.get("launch_with") == s["id"] and t["id"] not in launched:
                    steps.append(salvo_step(t))
            steps.append({"do": "impact_window", "name": s["id"], "salvo": s["id"], "n": 1, "s": s["window_s"],
                          "shots": s.get("shots", []), "prefix": s["id"], "timeout_s": scenes["await_timeout_s"]})
            # Salvo completion is an event: run validity checks that all arrived (all waves for accumulation)
            steps.append({"do": "await", "salvo": s["id"], "timeout_s": scenes["tail_timeout_s"], "quiet_s": scenes["quiet_s"]})
            continue
        cams = s["cameras"]
        hit = s.get("hit")
        eye, look = camera(scenes, hit["camera"] if hit else cams[0], site, spread)
        # Camera over ready ground before salvo: moving with the salvo raced camera chunks against 100 off-world
        # projectiles; integrated server stalled at 50 ms/tick, camera ground stayed unrendered for 30 s (#471, 25.10)
        steps.append(tp(eye, look))
        steps.append(salvo_step(s))
        if hit:
            # Impact moment: window starts at the nth hit (ball, flash, wave, flying debris) while the salvo still lands,
            # not the first: a Shahed arriving 6 s early shifted the window to include half the strikes (smb, 25.10)
            steps.append({"do": "impact_window", "name": f"{s['id']}-hit", "salvo": s["id"], "n": hit["nth"], "s": hit["window_s"],
                          "shots": hit.get("shots", []), "prefix": f"{s['id']}-hit", "timeout_s": scenes["await_timeout_s"]})
        steps.append({"do": "await", "salvo": s["id"], "timeout_s": scenes["await_timeout_s"], "quiet_s": scenes["quiet_s"]})
        for k, cam in enumerate(cams):
            eye, look = camera(scenes, cam, site, spread)
            steps.append(tp(eye, look))
            # Frame/window at the same time after the last salvo explosion in every run: salvo end ("all" immediately,
            # "quiet" after quiet_s) and camera move vary, while smoke changes over tens of seconds (#466: edge frame
            # alternated between 3 s and 35 s after the last explosion)
            steps.append({"do": "since", "salvo": s["id"],
                          "s": scenes["since_last_s"] + k * (scenes["after_shot_s"] + s["window_s"] + scenes["tp_gap_s"])})
            steps.append({"do": "shot", "name": f"{s['id']}_{cam}"})
            steps.append({"do": "wait", "s": scenes["after_shot_s"]})
            steps.append({"do": "window", "name": f"{s['id']}/{cam}", "s": s["window_s"]})
    # Client keeps rendering after the last window: its GPU cycle (client.PmonCycles) closes with the next
    # cycle line (~1 s), reaching the bench from nvidia-smi in chunks (~1 s); immediate client exit would leave
    # the window tail without GPU measurement
    steps.append({"do": "wait", "s": scenes["end_tail_s"]})
    w = scenes["world"]
    return {"seed": w["seed"], "day_time": w["day_time"], "settle_s": w["settle_s"], "limit_s": w["limit_s"],
            "projectiles": scenes["projectiles"], "warheads": scenes["warheads"], "probes": scenes["probes"], "once": scenes["once"],
            "steps": steps}


def windows(scenes, only=None):
    """Plan measurement-window names in scene order (report and look gate)."""
    out = []
    for s in scenes["scenes"]:
        if only is not None and s["id"] not in only:
            continue
        if "launch" in s:
            out += [f"{s['id']}/{w['name']}" for w in scenes["launch_view"]["windows"]]
        elif s.get("impact_window"):
            out.append(s["id"])
        else:
            out += ([f"{s['id']}-hit"] if s.get("hit") else []) + [f"{s['id']}/{c}" for c in s["cameras"]]
    return out


if __name__ == "__main__":
    import sys
    sel = sys.argv[1].split(",") if len(sys.argv) > 1 and sys.argv[1] != "all" else None
    print(json.dumps(build(load(), sel), indent=1, ensure_ascii=False))
