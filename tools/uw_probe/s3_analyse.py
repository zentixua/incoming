#!/usr/bin/env python3
"""Step 3 take analysis: frame times by window (approach: burst to the front at the observer; inside young: front to +60 s;
inside after: +60 to +300 s), the passage clip (front -5..+20 s, 30 fps) and a 0-10 min time lapse (x20) cut from the
window video, and the take's gates (A1/A4 lines, E1 dust parts). Usage: s3_analyse.py <take dir>"""
import json, re, subprocess, sys, datetime
from pathlib import Path

def clean_clip(src, start, dur, out, step=1):
    """Frames of src from start for dur seconds at 30 fps (every step-th: a time lapse), all-black frames replaced by the
    frame before; H.264 at 30 fps. Returns how many frames were replaced."""
    import numpy as np
    w, h = 1600, 900
    dec = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-ss", f"{start:.2f}", "-t", f"{dur:.2f}", "-i", str(src),
                            "-vf", f"fps=30,select=not(mod(n\\,{step})),scale={w}:{h}", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                           stdout=subprocess.PIPE)
    enc = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", "30",
                            "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", str(out)], stdin=subprocess.PIPE)
    last, replaced, size = None, 0, w * h * 3
    while True:
        buf = dec.stdout.read(size)
        if len(buf) < size: break
        f = np.frombuffer(buf, np.uint8)
        if f[::97].mean() < 3 and last is not None:
            enc.stdin.write(last); replaced += 1
        else:
            enc.stdin.write(buf); last = buf
    enc.stdin.close(); enc.wait(); dec.wait()
    return replaced

d = Path(sys.argv[1])
log = (d / "client.out").read_text(errors="replace")
m = re.search(r"\[(\d\d):(\d\d):(\d\d)\][^\n]*front passed the camera's ground point \((\d+) m from the burst, ([0-9.]+) s after it, world game time (-?\d+)\)", log)
res = {"take": d.name}
if not m:
    print(json.dumps({"take": d.name, "error": "no front line"})); sys.exit(1)
hh, mm, ss, dist, after, gt = int(m[1]), int(m[2]), int(m[3]), int(m[4]), float(m[5]), int(m[6])
front, burst = gt, gt - round(20 * after)
res.update(distance_m=dist, front_after_burst_s=after)
rows = []
for line in (d / "frametimes.txt").read_text().splitlines():
    p = line.split()
    if len(p) == 2:
        try: rows.append((int(float(p[0])), float(p[1])))
        except ValueError: pass
def win(a, b):
    v = sorted(ms for t, ms in rows if a <= t < b)
    if len(v) < 20: return None
    return {"frames": len(v), "mean_fps": round(1000 / (sum(v) / len(v)), 1), "p95_frame_fps": round(1000 / v[int(0.95 * (len(v) - 1))], 1),
            "p95_ms": round(v[int(0.95 * (len(v) - 1))], 1)}
res["approach"] = win(burst, front)
res["inside_young"] = win(front, front + 1200)
res["inside_after"] = win(front + 1200, front + 6000)
# A1/A4
a4 = re.findall(r"Nuclear dust wave: primary view, eligible \[([0-9,]*)\], drawn \[([0-9,]*)\]( MISMATCH)?; wave channel ([^;]*);", log)
res["A4"] = {"lines": len(a4), "mismatch": sum(1 for x in a4 if x[2])}
covers = [float(c) for c in re.findall(r"wave channel cover ([0-9.]+) %", log)]
res["A1"] = {"lines": len(covers), "nonzero": sum(1 for c in covers if c > 0), "max_cover_pct": max(covers) if covers else 0,
             "pall_ground_dust_rows": sorted(set(re.findall(r"pall ground-dust rows (\d+)", log)))}
parts = re.findall(r"SCENARIO shot (\S+?): pall .*?wall march ([0-9.]+), pack ([0-9.]+), wall resolve ([0-9.]+)", log)
res["E1"] = {p[0]: round(float(p[1]) + float(p[2]) + float(p[3]), 2) for p in parts}
# video: segment start (epoch) and the front line's wall time (log clock: local time of the client, offset by the hour
# difference to the segment's log time)
seg = [json.loads(l) for l in (d / "take.jsonl").read_text().splitlines() if '"segment"' in l]
if seg:
    s0 = seg[0]["wall"]
    start_log = re.search(r"start: (\d+\.\d+)", (d / "take.000.log").read_text())
    start = float(start_log[1]) if start_log else s0
    ls = datetime.datetime.fromtimestamp(start)
    # the client's log clock may be in another zone: take the whole-hour offset that puts the front after the start
    t = ls.replace(hour=hh, minute=mm, second=ss, microsecond=0)
    off = round((t - ls).total_seconds() / 3600)
    ft = (t - ls).total_seconds() - 3600 * off
    res["video_front_s"] = round(ft, 1)
    v = d / "take.000.mkv"
    b = ft - after
    # x11grab leaves all-black frames (rigs.md): each is replaced by the frame before it; counted per clip
    black = {}
    black["flash_to_passage"] = clean_clip(v, max(b - 2, 0), after + 22, d / "flash_to_passage.mp4")
    black["passage"] = clean_clip(v, max(ft - 5, 0), 25, d / "passage.mp4")
    black["lapse"] = clean_clip(v, max(b - 2, 0), 602, d / "lapse.mp4", step=20)
    res["black_frames_replaced"] = black
(d / "gates.json").write_text(json.dumps(res, indent=1))
print(json.dumps(res))
