#!/usr/bin/env python3
"""One acceptance scene of the nuclear dust wave, one recorded run, every gate's evidence at once.

    python3 tools/uw_probe/scene_run.py <scene.json> --out DIR [--preset P] [--camera NAME] [--renderer R] [--gpu]
                                        [--world-copy DIR] [--dry] [--analyse-only]

Scene files (acceptance.md B): `kind` sandbox (the flat sandbox, `effect` a sandbox command), single (a world copy in
a full-pack single-player client, `prod_client.py commands`), server (a dedicated server with clients: not run by this
driver, which prints the plan). Every run records, in DIR:
  frames/      PNG at `times_after_front_s` (s after the front passes the camera's ground point; sandbox `near`:
               the front at the scene's houses) and the clip's 30 fps frames over `clip`
  clip.mp4     the passage clip from those frames
  log.txt      the client log (nukeDiag on: A4 eligible/drawn per second, A1 wave channel and pall dust rows, clock CSV)
  gates.json   A1, A4 (from the log), E1 (dust wave interval per shot: march + pack + resolve, ms), C0 (clip_metrics
               change, flicker --map, period --edge on the clip), C2 (the dust top line per frame, topline.py),
               C3 (radial luma/hue profile steps below the horizon, masked to dust pixels)
Heavy runs go through tools/laptop_job.sh; GPU runs (--gpu) through the lease. Never on live worlds or Artem's instance.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

TREE = Path(__file__).resolve().parents[2]
JAVA_HOME = Path.home() / ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta"


def arg(name, default=None):
    a = sys.argv
    return a[a.index(name) + 1] if name in a else default


def sh(cmd, env=None, check=True, log=None):
    print("$", cmd if isinstance(cmd, str) else " ".join(cmd), flush=True)
    e = dict(os.environ, JAVA_HOME=str(JAVA_HOME), **(env or {}))
    with open(log, "a") if log else open(os.devnull, "w") as out:
        r = subprocess.run(cmd, shell=isinstance(cmd, str), cwd=TREE, env=e, stdout=out if log else None,
                           stderr=subprocess.STDOUT if log else None)
    if check and r.returncode != 0:
        raise SystemExit(f"failed ({r.returncode}): {cmd}")
    return r.returncode


def clip_times(scene):
    c = scene.get("clip") or {}
    if not c: return []
    a, b, fps = c["from_s"], c["to_s"], c.get("fps", 30)
    n = int(round((b - a) * fps))
    return [round(a + i / fps, 4) for i in range(n + 1)]


# ---------------------------------------------------------------------------------------------------------------- sandbox

def run_sandbox(scene, out, preset, camera, gpu):
    """S0-like scenes: the flat sandbox, the scene's effect, frames and clip in one shot."""
    S = TREE / "mod/run/sandbox"
    cam = scene["cameras"][camera]
    times = sorted(set(scene["times_after_front_s"]) | set(clip_times(scene)))
    at = ",".join(f"{t:g}" for t in times)
    start = ["tools/sandbox.sh", "start"] + (["gpu", "--wait"] if gpu else []) + ["--size", arg("--size", "2560x1600")]
    env = {"INCOMING_NUKE_DIAG": "1"}
    sh("tools/sandbox.sh stop", check=False)
    (S / "fx").mkdir(parents=True, exist_ok=True)
    (S / "fx/frames_per_tick").write_text("2\n")
    sh(start, env=env)
    opt = S / "options.txt"
    t = opt.read_text()
    t = re.sub(r"(?m)^renderDistance:.*$", "renderDistance:32", t)
    t = re.sub(r'(?m)^renderClouds:.*$', 'renderClouds:"false"', t) if "renderClouds" in t else t + 'renderClouds:"false"\n'
    opt.write_text(t)
    srv = S / "config/incoming-server.toml"
    srv.write_text(re.sub(r"(?m)^\tmax_yield = .*$", "\tmax_yield = 1000", srv.read_text()))
    cl = S / "config/incoming-client.toml"
    cl.write_text(re.sub(r'(?m)^nuke_dust_quality = .*$', f'nuke_dust_quality = "{preset}"', cl.read_text()))
    sh(["tools/sandbox.sh", "restart"] + start[2:], env=env)
    if cam.get("zoom"): sh(["tools/sandbox.sh", "fx", f"zoom {cam['zoom']}"])
    name = f"{scene['id'].lower()}_{camera}_{preset.lower()}"
    rc = sh(["tools/sandbox.sh", "shot", name, scene["effect"], "--at", at, "--cam", cam["cam"], "--wav", "0",
             "--time", str(cam.get("daytime", 6000))], env=env, check=False)
    d = S / "fx/out" / name
    (out / "frames").mkdir(parents=True, exist_ok=True)
    for f in d.glob("*.png"): shutil.copy2(f, out / "frames" / f.name)
    shutil.copy2(S / "logs/latest.log", out / "log.txt")
    sh("tools/sandbox.sh stop", check=False)
    if gpu: sh("tools/gpu_lease.sh release", check=False)
    return rc


# ---------------------------------------------------------------------------------------------------------------- single

def commands(scene, camera, preset):
    """The commands-scenario plan: warm-up at the camera, the bursts, then shots at fixed times after the front."""
    cam = scene["cameras"][camera]
    gz = json.load(open(Path(arg("--scenes", Path(sys.argv[1]).parent)) / "sites.json"))[scene["site"]]["gz"]
    steps = ["gamemode spectator", "time set " + ("18000" if scene.get("time") == "night" else "6000"),
             "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
             "weather " + ("rain" if scene.get("weather") == "rain" else "clear"),
             f"tp @s {cam['x']} {cam['y']} {cam['z']} {cam['yaw']} {cam['pitch']}",
             "wait:3600", "hud:off", "maxyield:1000", f"wave:{scene.get('wave', 0.35)}", f"pall:{preset}"]
    first = scene["bursts"][0]
    steps.append(f"shot:{scene['id'].lower()}_before")
    steps.append(f"incoming nuke now at {gz[0]} {gz[1]} {gz[2]} {first['kt']} {first['burst']}")
    steps.append("wait:front")
    t = 0.0  # seconds after the front; each shot takes 20 ticks of the plan
    for s in sorted(set(scene["times_after_front_s"])):
        w = int(round((s - t) * 20))
        if w > 0: steps.append(f"wait:{w}")
        steps.append(f"shot:{scene['id'].lower()}_{s:g}s")
        t = s + 1.0 if w >= 0 else t + 1.0
    return ";".join(steps)


def run_single(scene, out, preset, camera, gpu):
    world_copy = arg("--world-copy")
    if not world_copy: raise SystemExit("single scenes need --world-copy DIR: a prod_client directory with saves/world (a fresh golden copy)")
    plan = commands(scene, camera, preset)
    (out / "commands.txt").write_text(plan + "\n")
    job = ["tools/laptop_job.sh"] + (["--gpu", "--wait"] if gpu else ["--soft"]) + ["--full", f"uw-{scene['id'].lower()}", "--",
           "python3", "tools/prod_client.py", "commands", "--no-copy", "--dir", world_copy, "--world", "world",
           "--size", arg("--size", "1600x900"), "--prop", f"incoming.commands={plan}", "--prop", "incoming.nukeDiag=true"]
    if arg("--video"): job += ["--video", arg("--video")]
    rc = sh(job, check=False, log=out / "client.out")
    (out / "frames").mkdir(parents=True, exist_ok=True)
    for f in Path(world_copy, "screenshots").glob(f"{scene['id'].lower()}_*.png"): shutil.copy2(f, out / "frames" / f.name)
    lat = Path(world_copy, "logs/latest.log")
    if lat.exists(): shutil.copy2(lat, out / "log.txt")
    if gpu: sh("tools/gpu_lease.sh release", check=False)
    return rc


# ---------------------------------------------------------------------------------------------------------------- gates

def gate_a(log):
    """A1 and A4 from the nukeDiag lines."""
    lines = [l for l in log if "Nuclear dust wave:" in l and "eligible" in l]
    mism = [l for l in lines if "MISMATCH" in l]
    covers = [float(m.group(1)) for l in lines for m in [re.search(r"wave channel cover ([0-9.]+) %", l)] if m]
    rows = sorted({m.group(1) for l in lines for m in [re.search(r"pall ground-dust rows (\d+)", l)] if m})
    warn = [l.strip()[-200:] for l in log if "undrawn" in l.lower() or "goes undrawn" in l]
    return {"A4": {"summary_lines": len(lines), "mismatch_lines": len(mism), "first_mismatch": mism[0][-160:] if mism else None},
            "A1": {"wave_channel_cover_pct": {"n": len(covers), "max": max(covers) if covers else 0,
                                              "nonzero": sum(1 for c in covers if c > 0)},
                   "pall_ground_dust_rows": rows, "undrawn_warn": warn[:3]}}


def gate_e1(log):
    """E1 per frame line: the dust wave's interval and its parts (mean over the last second at that shot)."""
    rows = []
    for l in log:
        m = re.search(r"(?:SANDBOX|SCENARIO) shot (\S+?)(?:\.png)? .*?", l)
        if not m: continue
        a = re.search(r"age ([0-9.\-]+) s", l)
        p = re.search(r"march ([0-9.]+) pack ([0-9.]+) resolve ([0-9.]+)", l) or \
            re.search(r"wall march ([0-9.]+), pack ([0-9.]+), wall resolve ([0-9.]+)", l)
        g = re.search(r"preset 1/(\d+)", l) or re.search(r"detail (1/\d+ × \d+ steps)", l)
        if p: rows.append({"shot": m.group(1), "age": float(a.group(1)) if a else None, "dust_ms": round(sum(map(float, p.groups())), 3),
                           "march": float(p.group(1)), "rung": g.group(1) if g else None})
    v = sorted(r["dust_ms"] for r in rows)
    p95 = v[min(len(v) - 1, int(0.95 * len(v)))] if v else None
    return {"frames": len(rows), "p95_ms": p95, "max_ms": v[-1] if v else None, "rows": rows}


def py(script, *a):
    r = subprocess.run(["uv", "run", "-q", str(TREE / script), *map(str, a)], cwd=TREE, capture_output=True, text=True)
    return (r.stdout + r.stderr).strip()


def gate_frames(out, scene):
    frames = sorted((out / "frames").glob("*.png"))
    res = {"frames": len(frames)}
    age = lambda f: (lambda m: float(m.group(1)) if m else None)(re.search(r"_(-?\d+(?:\.\d+)?)s(?:_\d+)?\.png$", f.name))
    clip = sorted((f for f in frames if age(f) is not None), key=age)
    if len(clip) >= 30:
        lst = out / "clip.txt"
        lst.write_text("".join(f"file '{f}'\n" for f in clip))
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-r", "30", "-f", "concat", "-safe", "0", "-i", str(lst),
                        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16", str(out / "clip.mp4")])
        res["C0"] = {k: py("tools/clip_metrics.py", k, *(["--map", out / "flicker.png"] if k == "flicker" else []), out / "clip.mp4")
                     for k in ("change", "flicker")}
    fixed = [f for f in clip if any(abs(age(f) - t) < 1e-6 for t in scene["times_after_front_s"])]
    res["C2_topline"] = {f.name: py("tools/uw_probe/topline.py", f) for f in fixed}
    res["C3"] = {f.name: radial(f) for f in fixed}
    # period --edge on the fixed frames (the dust top over the sky in the band where the top line stands)
    res.setdefault("C0", {})["period_edge"] = {f.name: py("tools/clip_metrics.py", "period", f, "--band", "0.3,0.7", "--edge")
                                               for f in fixed}
    return res


def radial(f):
    """C3: luma and hue per row below the horizon (rows are distance bands), masked to dust-coloured pixels; the largest
    step between neighbouring 8-row bands."""
    code = r"""
import sys, colorsys, numpy as np
from PIL import Image
a = np.asarray(Image.open(sys.argv[1]).convert('RGB').resize((1280, 720)), np.float64) / 255
rows = a[360:700]
l = rows @ np.array([0.2126, 0.7152, 0.0722])
mask = (l > 0.03) & (rows.max(2) - rows.min(2) < 0.35)
bands = []
for y in range(0, rows.shape[0] - 8, 8):
    m = mask[y:y + 8]
    if m.sum() < 200: bands.append(None); continue
    c = rows[y:y + 8][m].mean(0)
    bands.append((l[y:y + 8][m].mean(), colorsys.rgb_to_hsv(*c)[0]))
d = [abs(b[0] - a[0]) for a, b in zip(bands, bands[1:]) if a and b]
h = [min(abs(b[1] - a[1]), 1 - abs(b[1] - a[1])) for a, b in zip(bands, bands[1:]) if a and b]
print('luma step max %.4f hue step max %.4f bands %d' % (max(d) if d else 0, max(h) if h else 0, sum(1 for b in bands if b)))
"""
    r = subprocess.run(["uv", "run", "-q", "--with", "numpy", "--with", "pillow", "python", "-c", code, str(f)],
                       capture_output=True, text=True)
    return (r.stdout + r.stderr).strip()


def main():
    scene = json.load(open(sys.argv[1]))
    out = Path(arg("--out", f"scene-{scene['id']}")).resolve()
    out.mkdir(parents=True, exist_ok=True)
    preset = arg("--preset", scene["presets"][0])
    camera = arg("--camera", next(iter(scene["cameras"])))
    gpu = "--gpu" in sys.argv
    meta = {"scene": scene["id"], "preset": preset, "camera": camera, "gpu": gpu, "tree": str(TREE),
            "head": subprocess.run(["git", "rev-parse", "HEAD"], cwd=TREE, capture_output=True, text=True).stdout.strip(),
            "start": time.strftime("%Y-%m-%d %H:%M:%S")}
    if scene["kind"] == "server":
        print(json.dumps({"plan": "dedicated server + clients (step0b/scripts/start_run.sh, client_pair.sh): not run by this driver",
                          "scene": scene}, indent=1))
        return 2
    if scene["kind"] == "single" and "--dry" in sys.argv:
        print(commands(scene, camera, preset)); return 0
    t0 = time.time()
    if "--analyse-only" not in sys.argv:
        rc = (run_sandbox if scene["kind"] == "sandbox" else run_single)(scene, out, preset, camera, gpu)
        meta["exit"] = rc
    meta["wall_s"] = round(time.time() - t0)
    log = open(out / "log.txt", errors="replace").read().splitlines() if (out / "log.txt").exists() else []
    gates = {"meta": meta, **gate_a(log), "E1": gate_e1(log), **gate_frames(out, scene)}
    (out / "gates.json").write_text(json.dumps(gates, indent=1, ensure_ascii=False))
    print(json.dumps({k: v for k, v in gates.items() if k not in ("C2_topline", "C3")}, indent=1, ensure_ascii=False)[:4000])
    return 0


if __name__ == "__main__":
    sys.exit(main())
