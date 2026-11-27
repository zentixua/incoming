#!/usr/bin/env python3
"""Frame-by-frame check of a delivered clip: no black, torn or frozen frames, a constant frame clock, whole file.

  tools/clip_check.py <video> [...] [--fps 60] [--size 1920x1080] [--audio] [--json frames.jsonl]

Every frame is decoded once (FFmpeg, grey, 320x180) and only its numbers are kept:
- black: a run of frames with no pixel above 24 whose mean is below a quarter of both the 5 frames before the run and
  the 5 after it (a dropout of any length, not a dark scene, a fade or the night before a flash); a run touching the
  clip's start or end has one side and counts only up to 5 frames;
- torn: at least 3 pixel rows of pure black (max ≤ 18) that are not black in both the frame before and after, in a
  frame whose mean is above 40 (a half-written window buffer; letterbox bars and dark bands stay in every frame);
- frozen: a run of unchanged frames (mean |difference| below 0.05 of 255 at 160x90) longer than one game tick of
  frames (a 20 tps game changes on every third frame of a 60 fps export), inside motion, left by a jump at least 1.5
  times the motion around it: the recorder sent the old picture again while the game moved on. A still scene
  ("still", reported but never failing) has no such jump.
The clock: packet timestamps, every step 1/fps within 2 ms (Matroska keeps milliseconds), as many as decoded frames.
A file FFmpeg reports as damaged, or whose audio is shorter or longer than its video by more than 0.5 s, fails.
Audio: its decoded peak must stay under -0.5 dBFS. Flashback renders an export's sound through a float OpenAL loopback
device without OpenAL Soft's output limiter, which live play has (integer output): a nuclear wave's arrival in a 15 kt
export reached +2.3 dBFS (443 samples over full scale) and clips in every player; deliver it through a limiter (rigs.md).
Sample peak of the first audio stream (Flashback exports one); a stream that does not decode fails.
Exit 1 when any clip fails; one JSON report line per clip. Why the checks exist: rigs.md (x11grab captures).
Needs ffmpeg/ffprobe on PATH; no Python packages.
"""
import argparse
import array
import json
import math
import operator
import statistics
import subprocess
import sys
import tempfile

W, H = 320, 180
DW, DH = 160, 90
BLACK_MAX, ROW_BLACK, TORN_MEAN, TORN_ROWS = 24, 18, 40, 3
STILL_DIFF, MOTION_DIFF, JUMP = 0.05, 0.5, 1.5
SIDE = 5
CLOCK_SLACK, AUDIO_SLACK = 0.002, 0.5
# the highest decoded audio peak, dBFS: the delivery limiter (rigs.md) holds -1.5 dBFS, AAC's overshoot left the two nuclear
# clips at -1.41 and -1.51 dBFS; 1 dB over that is a missing limiter, not codec noise
PEAK_MAX_DB = -0.5
DAMAGE = ("premature", "invalid", "corrupt", "error", "truncat", "missing")


def stats(full, small, prev_small):
    """mean, max, black-row mask and change from the previous frame: all classify needs of one frame"""
    mask = 0
    if len(full) == W * H:
        for y in range(H):
            if max(full[y * W:(y + 1) * W]) <= ROW_BLACK:
                mask |= 1 << y
    diff = None if prev_small is None else sum(map(abs, map(operator.sub, small, prev_small))) / len(small)
    return {"mean": sum(full) / len(full), "max": max(full), "rows": mask, "diff": diff}


def classify(frames):
    """frames: list of (grey W×H bytes, grey DW×DH bytes) → per-frame dicts. Tests use this; check() streams."""
    out, prev = [], None
    for full, small in frames:
        out.append(stats(full, small, prev))
        prev = small
    return judge(out)


def judge(st, fps=60.0):
    n = len(st)
    out = []
    for i, s in enumerate(st):
        near = [st[j]["rows"] for j in (i - 1, i + 1) if 0 <= j < n]
        both = near[0] & near[-1] if near else 0
        tear = bin(s["rows"] & ~both).count("1")
        still = s["diff"] is not None and s["diff"] < STILL_DIFF
        out.append({"i": i, "mean": round(s["mean"], 2), "black_rows": bin(s["rows"]).count("1"), "black": False,
                    "torn": s["mean"] > TORN_MEAN and tear >= TORN_ROWS, "still": still, "repeat": False,
                    "diff": None if s["diff"] is None else round(s["diff"], 3)})
    # a dropout: a run of dark frames (no pixel above 24) far darker than both the frames before it and after it, judged as
    # one run, so a long black stretch counts whole and the night before a flash (dark on one side) does not
    i = 0
    while i < n:
        if st[i]["max"] > BLACK_MAX:
            i += 1
            continue
        s = i
        while i < n and st[i]["max"] <= BLACK_MAX:
            i += 1
        before = [st[j]["mean"] for j in range(max(0, s - SIDE), s)]
        after = [st[j]["mean"] for j in range(i, min(n, i + SIDE))]
        sides = [statistics.median(x) for x in (before, after) if x]
        run_mean = statistics.mean(st[j]["mean"] for j in range(s, i))
        # a run at the clip's start or end has one side: only a short one (a first frame not yet drawn) is a dropout
        edge = s == 0 or i == n
        if sides and run_mean < 0.25 * min(sides) and (not edge or i - s <= SIDE):
            for j in range(s, i):
                out[j]["black"] = True
                out[j]["torn"] = False
    # a frozen run: unchanged frames s..e entered with motion and left with a jump above the motion around it, longer than
    # one game tick of frames (a 20 tps game in a 60 fps export changes on every third frame by itself)
    tick = max(1, round(fps / 20))
    i = 0
    while i < n:
        if not out[i]["still"]:
            i += 1
            continue
        s = i
        while i < n and out[i]["still"]:
            i += 1
        e = i - 1
        if e - s + 1 < tick:
            continue
        into = st[s - 1]["diff"] if s >= 1 else None
        leave = st[e + 1]["diff"] if e + 1 < n else None
        around = [st[j]["diff"] for j in range(max(1, s - 30), min(n, e + 31)) if j not in range(s, e + 2) and st[j]["diff"] is not None
                  and st[j]["diff"] >= STILL_DIFF]
        if into is not None and leave is not None and into > MOTION_DIFF and leave > MOTION_DIFF and around \
                and leave >= JUMP * statistics.median(around):
            for j in range(s, e + 1):
                out[j]["repeat"] = True
    return out


def longest(flags):
    best = run = 0
    for f in flags:
        run = run + 1 if f else 0
        best = max(best, run)
    return best


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def probe(path):
    """streams, sorted video packet times, audio span (s) or None, FFmpeg's complaint if any"""
    p = run(["ffprobe", "-v", "error", "-show_entries",
             "stream=index,codec_type,codec_name,width,height,r_frame_rate,sample_rate,channels", "-of", "json", path])
    if p.returncode:
        raise ValueError((p.stderr.strip() or "ffprobe failed").splitlines()[-1])
    streams = json.loads(p.stdout).get("streams", [])
    v = run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time", "-of", "csv=p=0", path])
    pts = sorted(float(x) for x in v.stdout.split() if x and x != "N/A")
    a = run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "packet=pts_time,duration_time", "-of", "csv=p=0", path])
    spans = [[float(x) for x in line.split(",") if x and x != "N/A"] for line in a.stdout.split()]
    spans = [s for s in spans if s]
    audio = (max(s[0] + (s[1] if len(s) > 1 else 0) for s in spans) - min(s[0] for s in spans)) if spans else None
    return streams, pts, audio, (v.stderr + a.stderr).strip()


def peak(path):
    """the first audio stream's highest decoded sample, dBFS (float decode keeps values over full scale; -inf for silence),
    or None when it does not decode"""
    p = subprocess.Popen(["ffmpeg", "-v", "quiet", "-i", path, "-map", "0:a:0", "-f", "f32le", "-"],
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    top, samples = 0.0, 0
    with p.stdout:
        while True:
            chunk = p.stdout.read(1 << 20)
            if not chunk:
                break
            a = array.array("f")
            a.frombytes(chunk[:len(chunk) // 4 * 4])
            if a:
                samples += len(a)
                top = max(top, max(a), -min(a))
    if p.wait() or not samples:
        return None
    return 20 * math.log10(top) if top > 0 else -math.inf


def decode(path):
    """per-frame stats in one streaming decode (320x180 grey; the change at every other pixel and row, 160x90)"""
    with tempfile.TemporaryFile() as errf:
        p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", path, "-vf", f"scale={W}:{H}:flags=area,format=gray",
                              "-fps_mode", "passthrough", "-f", "rawvideo", "-"], stdout=subprocess.PIPE, stderr=errf)
        out, prev = [], None
        with p.stdout:
            while True:
                full = p.stdout.read(W * H)
                if len(full) < W * H:
                    break
                small = b"".join(full[y * W:(y + 1) * W:2] for y in range(0, H, 2))
                out.append(stats(full, small, prev))
                prev = small
        code = p.wait()
        errf.seek(0)
        err = errf.read(4096).decode(errors="replace").strip()
    return out, code, err


def check(path, fps=None, size=None, audio=False):
    fails = []
    try:
        streams, pts, audio_s, perr = probe(path)
    except ValueError as e:
        return {"file": path, "fails": [f"unreadable: {e}"]}, []
    video = next((s for s in streams if s["codec_type"] == "video"), None)
    sound = next((s for s in streams if s["codec_type"] == "audio"), None)
    st, code, derr = decode(path) if video else ([], 0, "")
    measured = (len(pts) - 1) / (pts[-1] - pts[0]) if len(pts) > 1 and pts[-1] > pts[0] else None
    step = 1 / (fps or measured) if (fps or measured) else None
    steps = [b - a for a, b in zip(pts, pts[1:])]
    frames = judge(st, fps or measured or 60.0)
    report = {"file": path, "codec": video and video["codec_name"], "size": video and f"{video['width']}x{video['height']}",
              "fps": round(measured, 3) if measured else None, "frames": len(frames),
              "seconds": round(len(frames) / measured, 3) if measured else None,
              "black": sum(f["black"] for f in frames), "torn": sum(f["torn"] for f in frames),
              "repeat": sum(f["repeat"] for f in frames), "longest_repeat": longest(f["repeat"] for f in frames),
              "still": sum(f["still"] for f in frames), "max_step_s": round(max(steps), 4) if steps else None,
              "audio": sound and f"{sound['codec_name']} {sound.get('sample_rate')} Hz {sound.get('channels')} ch",
              "audio_s": round(audio_s, 3) if audio_s is not None else None}
    level = peak(path) if sound else None
    report["audio_peak_db"] = round(level, 2) if level is not None and level > -math.inf else None
    if sound and level is None:
        fails.append("audio does not decode")
    if not video or not frames:
        fails.append("no video frames decoded")
    complaint = " ".join(x for x in (perr, derr) if x)
    if code or any(w in complaint.lower() for w in DAMAGE):
        fails.append(f"damaged file: {complaint.splitlines()[0] if complaint else f'ffmpeg exit {code}'}")
    if pts and len(pts) != len(frames):
        fails.append(f"{len(pts)} packets but {len(frames)} decoded frames")
    if report["black"]:
        fails.append(f"{report['black']} black frames")
    if report["torn"]:
        fails.append(f"{report['torn']} torn frames")
    if report["repeat"]:
        fails.append(f"{report['repeat']} frozen frames (longest run {report['longest_repeat']})")
    if step and steps and any(abs(s - step) > CLOCK_SLACK for s in steps):
        bad = max(steps, key=lambda s: abs(s - step))
        fails.append(f"frame clock not constant: a step of {bad:.4f} s against {step:.4f} s")
    if fps and measured and abs(measured - fps) > 0.005 * fps:
        fails.append(f"{measured:.3f} fps, not {fps}")
    if size and report["size"] != size:
        fails.append(f"size {report['size']}, not {size}")
    if audio and not sound:
        fails.append("no audio stream")
    if sound and audio_s is not None and report["seconds"] and abs(audio_s - report["seconds"]) > AUDIO_SLACK:
        fails.append(f"audio {audio_s:.2f} s against video {report['seconds']:.2f} s")
    if level is not None and level > PEAK_MAX_DB:
        fails.append(f"audio peak {level:+.2f} dBFS, over {PEAK_MAX_DB} (clips on playback)")
    report["fails"] = fails
    return report, frames


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    ap.add_argument("video", nargs="+")
    ap.add_argument("--fps", type=float, help="required frame rate")
    ap.add_argument("--size", help="required WxH")
    ap.add_argument("--audio", action="store_true", help="require an audio stream")
    ap.add_argument("--json", help="per-frame lines (JSON) of every clip")
    a = ap.parse_args()
    bad = 0
    lines = open(a.json, "w") if a.json else None
    for v in a.video:
        report, frames = check(v, a.fps, a.size, a.audio)
        print(json.dumps(report, ensure_ascii=False), flush=True)
        bad += bool(report["fails"])
        if lines:
            for f in frames:
                lines.write(json.dumps({"file": v, **f}) + "\n")
    if lines:
        lines.close()
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
