#!/usr/bin/env python3
"""Frame-by-frame check of a delivered clip: no black or torn frames, no repeated frames, constant frame rate.

  tools/clip_check.py <video> [...] [--fps 60] [--size 1920x1080] [--audio] [--max-repeat 0] [--json report.jsonl]

Every frame is decoded (FFmpeg, grey, 320x180) and classified:
- black: a frame whose mean luma falls below a quarter of its neighbours' median (±5 frames) with no pixel above 24:
  a capture that sampled the window between buffer swaps (x11grab gave 10.7 % of the frames around a nuke);
- torn: whole pixel rows of pure black (max ≤ 18) in a frame whose mean is above 40: a half-written window buffer
  (2.1 % of the same take);
- repeat: the same picture as the frame before (mean |difference| at 160x90 below 0.05 of 255): the game drew no new
  frame in time and the recorder sent the old one again (39 % of the same take).
Timestamps must be a constant step of 1/fps. Exit 1 when any check fails; the report line per clip is JSON.
Video for players comes from Flashback's export (tools/media/README.md), which renders each frame from the replay
and never repeats or tears one; tools/x11_record.py captures are diagnostics, never clips.
Needs ffmpeg/ffprobe on PATH; no Python packages.
"""
import argparse
import json
import operator
import statistics
import subprocess
import sys

W, H = 320, 180
DW, DH = 160, 90
BLACK_MAX, ROW_BLACK, TORN_MEAN, TORN_ROWS = 24, 18, 40, 3
REPEAT_DIFF = 0.05


def classify(frames):
    """frames: list of (grey W×H bytes, grey DW×DH bytes). Returns per-frame dicts (mean, black, torn, repeat, diff)."""
    means = [sum(f) / len(f) for f, _ in frames]
    out = []
    prev = None
    for i, (full, small) in enumerate(frames):
        near = [means[j] for j in range(max(0, i - 5), min(len(frames), i + 6)) if j != i]
        ref = statistics.median(near) if near else means[i]
        black = max(full) <= BLACK_MAX and means[i] < 0.25 * ref
        rows = sum(1 for y in range(H) if max(full[y * W:(y + 1) * W]) <= ROW_BLACK) if len(full) == W * H else 0
        torn = not black and means[i] > TORN_MEAN and rows >= TORN_ROWS
        diff = None if prev is None else sum(map(abs, map(operator.sub, small, prev))) / len(small)
        out.append({"i": i, "mean": round(means[i], 2), "black_rows": rows, "black": black, "torn": torn,
                    "repeat": diff is not None and diff < REPEAT_DIFF, "diff": None if diff is None else round(diff, 3)})
        prev = small
    return out


def longest(flags):
    best = run = 0
    for f in flags:
        run = run + 1 if f else 0
        best = max(best, run)
    return best


def probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries",
                          "stream=index,codec_type,codec_name,width,height,r_frame_rate,avg_frame_rate,pix_fmt,sample_rate,channels",
                          "-of", "json", path], capture_output=True, text=True, check=True).stdout
    streams = json.loads(out)["streams"]
    pts = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time",
                          "-of", "csv=p=0", path], capture_output=True, text=True, check=True).stdout.split()
    return streams, [float(p) for p in pts if p and p != "N/A"]


def decode(path):
    """(320x180 grey, 160x90 grey) bytes per frame, in decode order."""
    def read(size):
        w, h = size
        p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", path, "-vf", f"scale={w}:{h}:flags=area,format=gray",
                              "-fps_mode", "passthrough", "-f", "rawvideo", "-"], stdout=subprocess.PIPE)
        frames = []
        while True:
            b = p.stdout.read(w * h)
            if len(b) < w * h:
                break
            frames.append(b)
        p.wait()
        return frames
    full, small = read((W, H)), read((DW, DH))
    return list(zip(full, small))


def check(path, fps=None, size=None, audio=False, max_repeat=0):
    streams, pts = probe(path)
    video = next((s for s in streams if s["codec_type"] == "video"), None)
    sound = next((s for s in streams if s["codec_type"] == "audio"), None)
    frames = classify(decode(path))
    steps = [b - a for a, b in zip(pts, pts[1:])]
    step = statistics.median(steps) if steps else 0
    report = {"file": path, "codec": video and video["codec_name"], "size": video and f"{video['width']}x{video['height']}",
              "fps": round(1 / step, 3) if step else None, "frames": len(frames), "seconds": round(len(frames) * step, 2) if step else None,
              "black": sum(f["black"] for f in frames), "torn": sum(f["torn"] for f in frames),
              "repeat": sum(f["repeat"] for f in frames), "longest_repeat": longest(f["repeat"] for f in frames),
              "max_step_s": round(max(steps), 4) if steps else None,
              "audio": sound and f"{sound['codec_name']} {sound.get('sample_rate')} Hz {sound.get('channels')} ch"}
    fails = []
    if report["black"]:
        fails.append(f"{report['black']} black frames")
    if report["torn"]:
        fails.append(f"{report['torn']} torn frames")
    if report["repeat"] > max_repeat:
        fails.append(f"{report['repeat']} repeated frames (longest run {report['longest_repeat']})")
    if steps and max(steps) > 1.5 * step:
        fails.append(f"frame clock not constant: a step of {max(steps):.3f} s against {step:.4f} s")
    if fps and report["fps"] and abs(report["fps"] - fps) > 0.01 * fps:
        fails.append(f"{report['fps']} fps, not {fps}")
    if size and report["size"] != size:
        fails.append(f"size {report['size']}, not {size}")
    if audio and not sound:
        fails.append("no audio stream")
    if not frames:
        fails.append("no frames decoded")
    report["fails"] = fails
    return report, frames


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    ap.add_argument("video", nargs="+")
    ap.add_argument("--fps", type=float, help="required frame rate")
    ap.add_argument("--size", help="required WxH")
    ap.add_argument("--audio", action="store_true", help="require an audio stream")
    ap.add_argument("--max-repeat", type=int, default=0, help="repeated frames allowed (default 0)")
    ap.add_argument("--json", help="per-frame lines (JSON) of every clip")
    a = ap.parse_args()
    bad = 0
    lines = open(a.json, "w") if a.json else None
    for v in a.video:
        report, frames = check(v, a.fps, a.size, a.audio, a.max_repeat)
        print(json.dumps(report, ensure_ascii=False))
        bad += bool(report["fails"])
        if lines:
            for f in frames:
                lines.write(json.dumps({"file": v, **f}) + "\n")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
