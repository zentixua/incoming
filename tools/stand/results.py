"""Parse a scene bench run: one client (version × pack), frames.csv and events.jsonl → measurement-window metrics.

A window is an interval from `window` events (start and end); a frame belongs if it started within the
window (first strike also includes the frame spanning the impact: the worst such frame is the measurement).
Exclude the screenshot frame and the following frame (its interval includes screenshot readback). Every
plan window is required: missing windows or too few frames invalidate a run. Standard library only:
parsing and look gate run in CI without packages (test_gate.py).

Frame metrics (ms):
- `interval`: start to next start, as seen by the player (1000 / fps); the harness row reports time since
  the previous start (`prev_interval_ms`), so a frame takes the number from the next row;
- `thread`: render-thread CPU per frame (RenderFrameEvent.Pre → Post: world, mod layers, GUI; excludes
  buffer swap, preemption by other jobs and driver sleep), the gated metric;
- `cpu`: same interval in wall-clock time (includes driver and preemption waits), report only;
- `gpu`: first to last GPU timestamp of the frame (glQueryCounter); stages are intervals between timestamps.
"""
import csv
import json
import math
from pathlib import Path

import plan


def percentile(values, q):
    """Percentile with linear interpolation (numpy default); empty gives None."""
    v = sorted(values)
    if not v:
        return None
    k = (len(v) - 1) * q / 100
    lo, hi = math.floor(k), math.ceil(k)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def stats(values):
    v = [x for x in values if x is not None]
    if not v:
        return None
    return {"n": len(v), "mean": sum(v) / len(v), "p50": percentile(v, 50), "p95": percentile(v, 95),
            "p99": percentile(v, 99), "max": max(v)}


def _num(s):
    return float(s) if s not in ("", None) else None


def read_frames(path):
    out = []
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            stages = {}
            for part in (row.get("stages") or "").split(";"):
                if "=" in part:
                    k, v = part.split("=", 1)
                    stages[k] = float(v)
            out.append({"frame": int(row["frame"]), "t": float(row["t_ms"]), "prev": _num(row["prev_interval_ms"]),
                        "cpu": _num(row["cpu_ms"]), "thread": _num(row.get("cpu_thread_ms")), "gpu": _num(row["gpu_ms"]),
                        "shot": int(row["shot"] or 0), "stages": stages})
    out.sort(key=lambda f: f["frame"])
    # Frame duration is time until next start, stored in the next row (unavailable for the last frame)
    for a, b in zip(out, out[1:] + [None]):
        a["interval"] = b["prev"] if b is not None and b["frame"] == a["frame"] + 1 else None
    return out


def read_events(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def read_host(path):
    """Machine load (client.Host, host.jsonl); absent file gives None."""
    try:
        return read_events(path)
    except FileNotFoundError:
        return None


def host_in(host, events, t0, t1):
    """Machine load in bench window [t0, t1) (ms), using host.jsonl records whose interval [epoch − dt, epoch]
intersects it: CPU samples (`kind` cpu, client.Host) and GPU cycles (`kind` gpu, client.PmonCycles); srv
event epoch_ms aligns bench and wall clocks. A GPU cycle is measured only when it has an SM number for
our client: it renders every frame, and without its number, foreign "-" does not mean idle (the driver did
not measure per process). Return {"samples", "covered" (window fraction covered by CPU samples),
"gpu_covered" (fraction covered by measured cycles), "cpu_foreign_max" (worst sample, cores),
"cpu_foreign" (overlap-weighted mean, report only), "gpu_foreign" (worst cycle, % SM; None if no cycles),
"gpu_foreign_mean" (mean over window cycles), "units" (worst by service), "load1", "profiles"},
or None if clock alignment is unavailable."""
    offs = sorted(e["epoch_ms"] - e["t_ms"] for e in events if e["type"] == "srv" and e.get("epoch_ms") is not None)
    if not host or not offs:
        return None
    off = offs[len(offs) // 2]

    def overlap(s):
        b = s["epoch"] * 1000 - off
        return min(b, t1) - max(b - s["dt"] * 1000, t0)
    got, weight, gpu_weight, cpu, cpu_max, gpu, units, load, profiles = 0, 0.0, 0.0, 0.0, 0.0, None, {}, 0.0, set()
    gpu_sum = gpu_seen = 0.0
    for s in host:
        o = overlap(s)
        if o <= 0:
            continue
        if s.get("kind") == "gpu":
            if s.get("own_sm") is not None:
                gpu_weight += o
            if s.get("foreign_sm") is not None:
                gpu = max(gpu or 0, s["foreign_sm"])
                gpu_sum += s["foreign_sm"] * o
                gpu_seen += o
            continue
        got += 1
        weight += o
        cpu += s["cpu_foreign"] * o
        cpu_max = max(cpu_max, s["cpu_foreign"])
        load = max(load, s.get("load1") or 0)
        profiles.add(s.get("profile"))
        for n, c in (s.get("units") or {}).items():
            units[n] = max(units.get(n, 0), c)
    span = max(t1 - t0, 1e-9)
    # pmon cycles are consecutive, but ends have one-second precision: neighbours can overlap; cap coverage at 1
    return {"samples": got, "covered": round(weight / span, 3), "gpu_covered": round(min(1.0, gpu_weight / span), 3),
            "cpu_foreign_max": cpu_max, "cpu_foreign": round(cpu / weight, 2) if weight else None, "gpu_foreign": gpu,
            "gpu_foreign_mean": round(gpu_sum / gpu_seen, 2) if gpu_seen else None,
            "load1": load, "units": dict(sorted(units.items(), key=lambda kv: -kv[1])[:5]), "profiles": sorted(map(str, profiles))}


def windows(events):
    """Measurement windows: name → (start, end), ms."""
    begin, out = {}, {}
    for e in events:
        if e["type"] != "window":
            continue
        if e["edge"] == "begin":
            begin[e["name"]] = e["t_ms"]
        elif e["name"] in begin:
            out[e["name"]] = (begin.pop(e["name"]), e["t_ms"])
    return out


def in_range(frames, t0, t1):
    return [f for f in frames if t0 <= f["t"] < t1 and f["shot"] == 0]


def overlapping(frames, t0, t1):
    """Frames overlapping the window at all: starts inside it plus the earlier frame spanning its start."""
    return [f for f in frames if f["shot"] == 0 and f["t"] < t1
            and (f["t"] >= t0 or (f["interval"] is not None and f["t"] + f["interval"] > t0))]


def summarize(frames, events, t0, t1):
    """Window metrics: frames, CPU, GPU, stages (p50 and mean), server tick, bottleneck, probe lines."""
    fr = in_range(frames, t0, t1)
    if not fr:
        return None
    w = {"duration_s": (t1 - t0) / 1000, "frames": len(fr), "fps": len(fr) / ((t1 - t0) / 1000),
         "interval": stats([f["interval"] for f in fr]), "cpu": stats([f["cpu"] for f in fr]),
         "thread": stats([f.get("thread") for f in fr]), "gpu": stats([f["gpu"] for f in fr])}
    labels = sorted({k for f in fr for k in f["stages"]})
    w["stages"] = {k: {"p50": percentile([f["stages"][k] for f in fr if k in f["stages"]], 50),
                       "mean": sum(f["stages"].get(k, 0) for f in fr) / len(fr)} for k in labels}
    srv = [e for e in events if e["type"] == "srv" and t0 <= e["t_ms"] < t1]
    w["mspt"] = stats([e["mspt"] for e in srv])
    w["projectiles"] = max((e["projectiles"] for e in srv), default=None)
    w["entities"] = max((e["entities"] for e in srv), default=None)
    gpu50, iv50 = (w["gpu"] or {}).get("p50"), w["interval"]["p50"] if w["interval"] else None
    w["bound"] = None if gpu50 is None or not iv50 else ("gpu" if gpu50 >= 0.85 * iv50 else "cpu")
    probes = {}
    for e in events:
        if e["type"] == "probe" and t0 <= e["t_ms"] < t1:
            probes[e["name"].split("#")[0].rsplit(".", 1)[-1] + "." + e["name"].split("#")[1]] = e["value"]
    w["probes"] = probes
    return w


def buckets(frames, t0, t1, size_s):
    """Bins of size_s seconds: mean and p95 interval, p50 GPU, p95 CPU."""
    out = []
    n = int(round((t1 - t0) / 1000 / size_s))
    for i in range(n):
        a, b = t0 + i * size_s * 1000, t0 + (i + 1) * size_s * 1000
        fr = in_range(frames, a, b)
        iv = [f["interval"] for f in fr if f["interval"] is not None]
        out.append({"t_s": i * size_s, "frames": len(fr), "interval_mean": sum(iv) / len(iv) if iv else None,
                    "interval_p95": percentile(iv, 95), "gpu_p50": percentile([f["gpu"] for f in fr if f["gpu"] is not None], 50),
                    "cpu_p95": percentile([f["cpu"] for f in fr if f["cpu"] is not None], 95)})
    return out


def growth(bks, minute_s=60):
    """Accumulation: last minute against first (mean interval), ratio and both values."""
    if not bks:
        return None
    size = bks[1]["t_s"] - bks[0]["t_s"] if len(bks) > 1 else minute_s
    k = max(1, int(round(minute_s / size)))
    if len(bks) < 2 * k:
        return None

    def mean(bs):
        v = [b["interval_mean"] for b in bs if b["interval_mean"] is not None]
        return sum(v) / len(v) if v else None
    first, last = mean(bks[:k]), mean(bks[-k:])
    return {"first_ms": first, "last_ms": last, "ratio": last / first if first and last else None}


def acc_load(events, scene, t0, t1):
    """Accumulation load in the window: wave hits in its first and last minutes and after it (`planned` per
minute); lateness of wave commands against schedule by server execution (cmd event: server began at
`ran_ms`, due at `due_ms`): a stalled server would execute them in a batch even when the client sent on time."""
    w = scene["waves"]
    hits = sorted(e["t_ms"] for e in events if e["type"] == "impact" and e["salvo"] == scene["id"])
    cmds = [e for e in events if e["type"] == "cmd" and e.get("tag") == scene["id"]]
    late = [e["ran_ms"] - e["due_ms"] for e in cmds if e.get("due_ms") is not None and e.get("ran_ms") is not None]
    minute = 60_000
    return {"planned": w["strikes"] * minute / 1000 / w["every_s"], "wave": w["strikes"],
            "first": sum(1 for t in hits if t0 <= t < t0 + minute), "last": sum(1 for t in hits if t1 - minute <= t <= t1),
            "inside": sum(1 for t in hits if t0 <= t <= t1), "after": sum(1 for t in hits if t > t1),
            "commands": len(cmds), "late_max_ms": max(late) if late else None}


def analyze(run_dir, scenes, only=None, power=None):
    """One client (version × pack): windows, first strike, accumulation, validity checks (only: scene subset,
None for all; power: initial power profile, whose changes in windows are reported)."""
    run_dir = Path(run_dir)
    frames = read_frames(run_dir / "frames.csv")
    events = read_events(run_dir / "events.jsonl")
    host = read_host(run_dir / "host.jsonl")
    wins = windows(events)
    res = {"windows": {}, "checks": [], "meta": {}}
    for name, (t0, t1) in wins.items():
        s = summarize(frames, events, t0, t1)
        if s:
            s["host"] = host_in(host, events, t0, t1)
            res["windows"][name] = s
    first = wins.get("first")
    if first:
        fr = overlapping(frames, first[0], first[1])
        iv = [f["interval"] for f in fr if f["interval"] is not None]
        gp = [f["gpu"] for f in fr if f["gpu"] is not None]
        # Load follows window boundaries, not its frame starts: a frame stalled across the whole window also measures it (worst_ms)
        res["first"] = {"worst_ms": max(iv) if iv else None, "worst_gpu_ms": max(gp) if gp else None,
                        "host": host_in(host, events, first[0], first[1])}
    acc_scene = next((s for s in scenes["scenes"] if s.get("waves")), None)
    if acc_scene and acc_scene["id"] in wins:
        t0, t1 = wins[acc_scene["id"]]
        bks = buckets(frames, t0, t1, acc_scene.get("bucket_s", 10))
        # Machine load in the minutes used to calculate growth
        res["acc"] = {"buckets": bks, "growth": growth(bks), "load": acc_load(events, acc_scene, t0, t1),
                      "host": {"first": host_in(host, events, t0, t0 + 60_000), "last": host_in(host, events, t1 - 60_000, t1)}}
    gl = [e for e in events if e["type"] == "gl"]
    if gl:
        res["meta"]["gl"] = {k: gl[0][k] for k in ("renderer", "version", "vendor", "width", "height")}
    res["meta"]["once"] = {e["name"]: e["value"] for e in events if e["type"] == "once"}
    res["meta"]["impacts"] = {}
    for e in events:
        if e["type"] == "await":
            res["meta"]["impacts"][e["salvo"]] = {"got": e["impacts"], "expect": e["expect"], "why": e["why"], "took_s": e["took_s"]}
    done = [e for e in events if e["type"] == "done"]
    res["meta"]["done"] = done[0] if done else None
    res["meta"]["frames"] = len(frames)
    res["meta"]["duration_s"] = (frames[-1]["t"] - frames[0]["t"]) / 1000 if frames else 0
    res["meta"]["shots"] = sorted(e["name"] for e in events if e["type"] == "shot")
    acc_sel = acc_scene if acc_scene and (only is None or acc_scene["id"] in only) else None
    expected, unawaited = shot_plan(run_dir, scenes, only)
    res["checks"] = checks(events, res, expected, acc_sel, unawaited)
    res["load"] = load_notes(res, acc_sel, power)
    return res


def shot_plan(run_dir, scenes, only=None):
    """Windows and salvos without completion waits come from the plan used to capture the run (stand-plan.json
alongside it), not current scenes.json; otherwise a new scene/window would invalidate all older runs.
Without that file, use scenes.json."""
    f = Path(run_dir) / "stand-plan.json"
    if not f.exists():
        return plan.windows(scenes, only), plan.launch_ids(scenes)
    steps = json.loads(f.read_text(encoding="utf-8"))["steps"]
    expected = [s["name"] for s in steps if s["do"] in ("window", "impact_window")]
    awaited = {s["salvo"] for s in steps if s["do"] == "await"}
    return expected, {s["id"] for s in steps if s["do"] == "salvo"} - awaited


def verdict_hosts(res, acc_scene=None):
    """Machine load in every verdict window: "100", first strike (by window boundaries), first and last
accumulation minutes (acc_scene: plan accumulation scene) → [(where, host_in)]. One list for all machine
checks (load, power profile), so no new verdict window bypasses either."""
    out = [(f"window {name}", w.get("host")) for name, w in res.get("windows", {}).items() if name.startswith(GATED)]
    if res.get("first"):
        out.append((f"window {FIRST}", res["first"].get("host")))
    if acc_scene and (res.get("acc") or {}).get("host"):
        out += [(f"accumulation {acc_scene['id']}, {what} minute", res["acc"]["host"][part]) for part, what in (("first", "first"), ("last", "last"))]
    return out


def checks(events, res, expected=(), acc_scene=None, unawaited=()):
    """Run validity for comparison: problem lines (empty means valid). expected: plan windows (all required),
acc_scene: plan accumulation scene (its load must fall within the window), unawaited: nearby launch salvos
(no wait for completion, plan.launch_ids). Machine load does not decide validity (load_notes): a same-session
pair protects the look gate against it (gate.py)."""
    bad = []
    if not res["meta"]["done"]:
        bad.append("run did not finish (no done event)")
    if any(e["type"] == "limit" for e in events):
        bad.append("run reached the time limit")
    for e in events:
        if e["type"] == "cmd" and not e["ok"] and e["tag"] != "setup":
            bad.append(f"command failed: {e['cmd']}")
        if e["type"] == "tp-timeout":
            why = f" (expected: {e['waiting']})" if e.get("waiting") else ""
            bad.append(f"camera did not reach {e['eye']}{why}")
        if e["type"] == "late":
            bad.append(f"frame after salvo {e['salvo']} past deadline: {e['late_s']:.1f} s beyond {e['s']:g} s after last explosion")
        if e["type"] == "since":
            # Use all run hits, not just those known to Director at the step: a late hit (salvo ended by "silence"
            # while a projectile still flew off-world) would move the frame nearer a fresh explosion
            last = max((i["t_ms"] for i in events if i["type"] == "impact" and i["salvo"] == e["salvo"]), default=None)
            after = None if last is None else (e["t_ms"] - last) / 1000
            if after is None or not e["s"] - SINCE_EARLY_S <= after <= e["s"] + SINCE_LATE_S:
                got = "no hits" if after is None else f"{after:.1f} s"
                bad.append(f"frame after salvo {e['salvo']} late: {got} after last explosion instead of {e['s']:g} s")
        if e["type"] == "impact-timeout":
            bad.append(f"window {e['name']}: no hit from salvo {e['salvo']}")
    for salvo, i in res["meta"]["impacts"].items():
        if i["why"] != "all" and not (i["why"] == "quiet" and i["expect"] - i["got"] <= i["expect"] * LOST_SHARE):
            bad.append(f"salvo {salvo}: {i['got']} hits out of {i['expect']} ({i['why']})")
    salvos = {e["id"] for e in events if e["type"] == "salvo"}
    for salvo in sorted(salvos - set(res["meta"]["impacts"]) - set(unawaited)):
        bad.append(f"salvo {salvo}: no end (no await event)")
    if not any(e["type"] == "gl" for e in events):
        bad.append("no GPU line")
    begun = {e["name"] for e in events if e["type"] == "window" and e["edge"] == "begin"}
    for name in expected:
        if name not in res.get("windows", {}) and name != FIRST:
            bad.append(f"window {name}: " + ("no frames" if name in begun else "not captured"))
    for name, w in res.get("windows", {}).items():
        if name == FIRST:
            continue
        if not w.get("gpu"):
            bad.append(f"window {name}: no GPU timestamps")
        if not w.get("thread"):
            bad.append(f"window {name}: no render-thread CPU time")
        if w["frames"] < MIN_FRAMES:
            bad.append(f"window {name}: {w['frames']} frames (less than {MIN_FRAMES})")

    # First strike: measure the worst frame overlapping the window; a client stalled for the whole window still gives a number
    if FIRST in expected and (res.get("first") or {}).get("worst_ms") is None:
        bad.append("first strike: no number (no window or no frame overlapped it)")
    if acc_scene:
        load = (res.get("acc") or {}).get("load")
        name = acc_scene["id"]
        if not load:
            bad.append(f"accumulation {name}: no window — cannot check load")
        else:
            if load["after"]:
                bad.append(f"accumulation {name}: {load['after']} hits after window — last minute did not measure the full plan")
            want = next((e["commands"] for e in events if e["type"] == "salvo" and e["id"] == name), None)
            if load["commands"] != want:
                bad.append(f"accumulation {name}: wave commands {load['commands']} out of {want}")
            if load["late_max_ms"] is None:
                bad.append(f"accumulation {name}: wave commands lack deadline or execution time (due_ms, ran_ms) — cannot check schedule")
            elif load["late_max_ms"] > LATE_MS:
                bad.append(f"accumulation {name}: wave commands off schedule (late by up to {load['late_max_ms']:.0f} ms, allowed {LATE_MS})")
            for part in ("first", "last"):
                if abs(load[part] - load["planned"]) > load["wave"]:
                    bad.append(f"accumulation {name}: in the {'first' if part == 'first' else 'last'} window minute {load[part]} hits, "
                               f"planned {load['planned']:g} ± wave ({load['wave']})")
        if not (res.get("acc") or {}).get("growth"):
            bad.append(f"accumulation {name}: no growth number")
    return bad


def foreign(h):
    """Foreign load in a window (host_in; empty means quiet): changes frame time without code changes.
Use the worst CPU sample, not the mean: a 3-core job for 5 s out of 20 averages below threshold but did
run inside the window. For GPU, use both window mean and worst cycle, above desktop background (FOREIGN_GPU)."""
    bad = []
    if h["cpu_foreign_max"] > FOREIGN_CPU:
        bad.append(f"foreign jobs — up to {h['cpu_foreign_max']} cores (allowed {FOREIGN_CPU}): {h['units']}")
    peak, mean = h["gpu_foreign"] or 0, h.get("gpu_foreign_mean") or 0
    if peak > FOREIGN_GPU_PEAK or mean > FOREIGN_GPU:
        bad.append(f"GPU used by foreign processes — {peak} % SM in worst cycle (allowed {FOREIGN_GPU_PEAK}), {mean:g} % mean "
                   f"(allowed {FOREIGN_GPU})")
    return bad


def load_checks(where, h):
    """Machine load in a window, for reporting: record exists, covers the window (with GPU measurement), no foreign load."""
    if h is None or not h["samples"]:
        return [f"{where}: no machine load record (host.jsonl)"]
    bad = []
    if h["covered"] < COVER_MIN:
        bad.append(f"{where}: machine load coverage is {h['covered']:.0%} of window")
    if h["gpu_covered"] < COVER_MIN:
        bad.append(f"{where}: foreign-process GPU coverage is {h['gpu_covered']:.0%} of window (nvidia-smi)")
    return bad + [f"{where}: {x}" for x in foreign(h)]


def load_notes(res, acc_scene=None, power=None):
    """Foreign load and power-profile changes in verdict windows (verdict_hosts): report lines, not validity.
Machine recording (CPU poll every 5 s, nvidia-smi cycles) does not prove quiet at every instant, and the
look gate already compares a same-session pair; lines explain a noisy pair or a failure that did not recur."""
    out = []
    for where, h in verdict_hosts(res, acc_scene):
        out += load_checks(where, h)
        seen = sorted(set((h or {}).get("profiles") or []) - {power}) if power is not None else []
        if seen:
            out.append(f"{where}: power profile {', '.join(seen)}, initially {power}")
    return out


FIRST = "first"
GATED = "100/"
# Foreign load in a window (report and pre-client quiet wait): limits. Idle laptop background is 0,2–0,4
# cores (Claude app, browser); one heavy job uses 1,5 (another session sandbox client) to 7 cores (build,
# review with tests), measured 25.10. GPU (1 s pmon cycles): host desktop (kwin_wayland, plasmashell, locked
# screen) uses 1–18 % SM in 15 % of cycles, up to 12 % while the bench client renders (run 25.10, 705 cycles),
# window mean over 20 s up to ~3 %; another game client holds 37–39 % continuously. Therefore window mean
# must stay at most 5 %, worst cycle at most 25 %: above background, below another client; a short spike
# below 25 % barely affects GPU p50 over 20 s.
FOREIGN_CPU, FOREIGN_GPU, FOREIGN_GPU_PEAK = 1.0, 5, 25
# The window must be covered by consecutive load samples, each starting at the previous one; a gap is
# a recording failure or a tail without a final sample
COVER_MIN = 0.95
# A window with fewer frames is not a measurement (client was stalled)
MIN_FRAMES = 20
# Wave command lateness beyond this means waves batched up (client tick 50 ms, screenshot ~0,5 s)
LATE_MS = 2000
# Camera frame after salvo (since step): no earlier than due after the last hit (minus client clock step),
# no later than deadline + 2 s (Director.LATE_S)
SINCE_EARLY_S, SINCE_LATE_S = 0.1, 2.0
# Allowed missing salvo-hit share ("quiet" end: no world projectiles, silence): a Shahed over another
# explosion dies in air, normal mod behaviour varying between runs (gate #457: 98 and 99 out of 100; #460:
# 95–96 for both accepted v3.7.0 and candidate). Losing 10 of 100 keeps load of the same order, while a
# missing salvo (broken launch) remains detectable.
LOST_SHARE = 0.10
# First/last accumulation-minute hits follow the plan (strikes × 60 / every_s) ± one wave: minutes are
# comparable only with equal load. Tolerance is a wave, not a fraction: the window begins at the first
# hit (one Shahed of wave 0), flight varies by ±3 s, and part of a wave crosses each minute boundary
# (v3.7.0: 35/25, 31/28, 32/28 for planned 30). Schedule catches batched waves (LATE_MS), `after` catches waves
# outside the window.
