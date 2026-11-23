# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow", "scipy"]
# ///
"""Motion and repetition numbers for a look clip or frame: the measures a visual task states before a clip goes out.

The same command runs on our take and on the reference footage, so both sides are measured the same way.

  change  <frames|video> [--region x0,y0,x1,y1] [--lag 0.1]
          Detail change per lag: 1 − correlation of the high-pass detail (frame − Gaussian blur σ 3 px at 720 p) of
          32 px blocks between frames `lag` seconds apart, after the best whole-pixel shift within ±8 px, median over textured
          blocks. 0: the detail only moves (or stands); 1: new detail every lag (boiling, re-randomised noise).
  flicker <frames|video> [--region …] [--map out.jpg]
          Frame-to-frame pulsing: per pixel the temporal median of |second difference| of luma (smooth motion gives
          ~0; a debris piece crossing a pixel in a frame or two does not count), mean and p99 over the region, share of
          pixels above 1/255; --map writes where it is (×20) under the middle frame.
  period  <png> [--band y0,y1] [--x1 f] [--edge]
          Repetition along x inside a band: normalised autocorrelation of the band's high-passed column-mean luma
          (--edge: of the strongest dark-over-light step's row per column, a band's lower edge). Prints the dominant
          period in px and its autocorrelation peak; a peak above 0.5 is a visible repeat (copied pieces). --edge also
          prints the edge slope's kurtosis: above 10 is a staircase (flat runs joined by steps), also when perspective
          shrinks the spacing so that no single period exists. The band must hold one dark-over-light edge across its
          width (check its crop): elsewhere the strongest step jumps between features and the numbers mean nothing.

Frames: a directory of PNGs sorted by a `_<age>s.png` suffix (sandbox takes; their fps from the ages) or by name
(--fps, default 30); a video (mp4/webm/mkv) is decoded at 720 p by ffmpeg, --window t0,t1 in its seconds.
Regions are fractions of the frame. Exit 0; numbers on stdout, one line per command.
Run: uv run tools/clip_metrics.py change mod/run/sandbox/out/take --region 0,0.4,1,0.8
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter

AGE = re.compile(r"_([-+]?\d+(?:\.\d+)?)s\.png$")
VIDEO = {".mp4", ".webm", ".mkv", ".mov", ".avi"}


def fractions(text: str, count: int) -> tuple[float, ...]:
    values = tuple(float(v) for v in text.split(","))
    if len(values) != count or not all(0.0 <= v <= 1.0 for v in values):
        raise argparse.ArgumentTypeError(f"{count} fractions 0..1 expected: {text}")
    return values


def load(source: Path, fps: float, window: tuple[float, float] | None, scratch: Path) -> tuple[list[np.ndarray], float]:
    """Luma frames (float32) and their rate."""
    if source.is_dir():
        files = sorted(source.glob("*.png"))
        ages = [AGE.search(f.name) for f in files]
        if files and all(ages):
            pairs = sorted(zip((float(a.group(1)) for a in ages), files))
            if window:
                pairs = [p for p in pairs if window[0] <= p[0] <= window[1]]
            files = [f for _, f in pairs]
            steps = np.diff([a for a, _ in pairs])
            if len(steps) and np.median(steps) > 0:
                fps = 1.0 / float(np.median(steps))
        return [np.asarray(Image.open(f).convert("L"), np.float32) for f in files], fps
    if source.suffix.lower() not in VIDEO:
        raise SystemExit(f"not a frame directory or video: {source}")
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "stream=r_frame_rate",
                            "-of", "csv=p=0", str(source)], capture_output=True, text=True, check=True).stdout.strip()
    num, _, den = probe.partition("/")
    fps = float(num) / float(den or 1)
    cut = ["-ss", str(window[0]), "-t", str(window[1] - window[0])] if window else []
    subprocess.run(["ffmpeg", "-loglevel", "error", *cut, "-i", str(source), "-vf", "scale=-2:720",
                    str(scratch / "f%05d.png")], check=True)
    return [np.asarray(Image.open(f).convert("L"), np.float32) for f in sorted(scratch.glob("f*.png"))], fps


def crop(frame: np.ndarray, region: tuple[float, float, float, float]) -> np.ndarray:
    h, w = frame.shape
    x0, y0, x1, y1 = region
    return frame[int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)]


def detail(frame: np.ndarray) -> np.ndarray:
    sigma = 3.0 * frame.shape[0] / 720.0 if frame.shape[0] > 720 else 3.0
    return frame - gaussian_filter(frame, sigma)


def best_shift_correlation(a: np.ndarray, b: np.ndarray, block: int = 32, reach: int = 8, step: int = 2,
                           textured: float = 0.8) -> list[float]:
    """Per textured block of `a`, the highest correlation with `b` over shifts within ±reach."""
    h, w = a.shape
    out = []
    for by in range(reach, h - block - reach + 1, block):
        for bx in range(reach, w - block - reach + 1, block):
            block_a = a[by:by + block, bx:bx + block]
            if block_a.std() < textured:
                continue
            za = (block_a - block_a.mean()).ravel()
            za /= np.linalg.norm(za)
            def score(dy: int, dx: int) -> float:
                block_b = b[by + dy:by + dy + block, bx + dx:bx + dx + block]
                zb = (block_b - block_b.mean()).ravel()
                norm = np.linalg.norm(zb)
                return float(za @ zb) / norm if norm > 0 else -1.0

            coarse = max(((score(dy, dx), dy, dx) for dy in range(-reach, reach + 1, step)
                          for dx in range(-reach, reach + 1, step)))
            _, cy, cx = coarse  # then every shift next to the best coarse one
            fine = (score(dy, dx) for dy in range(max(-reach, cy - step + 1), min(reach, cy + step - 1) + 1)
                    for dx in range(max(-reach, cx - step + 1), min(reach, cx + step - 1) + 1))
            out.append(max(coarse[0], *fine))
    return out


def change(frames: list[np.ndarray], fps: float, lag_s: float, region) -> tuple[float, int, int]:
    lag = max(1, round(lag_s * fps))
    hp = [detail(crop(f, region)) for f in frames]
    values = []
    for k in range(0, len(hp) - lag, max(1, lag // 2)):
        values += best_shift_correlation(hp[k], hp[k + lag])
    if not values:
        raise SystemExit("no textured blocks in the region (or fewer frames than the lag)")
    return 1.0 - float(np.median(values)), len(values), lag


def flicker(frames: list[np.ndarray], region) -> tuple[np.ndarray, float, float, float]:
    stack = np.stack([crop(f, region) for f in frames])
    if len(stack) < 3:
        raise SystemExit("flicker needs at least 3 frames")
    d2 = np.abs(stack[2:] - 2 * stack[1:-1] + stack[:-2])
    md = np.median(d2, axis=0)
    return md, float(md.mean()), float(np.percentile(md, 99)), float((md > 1.0).mean())


def edge_rows(band: np.ndarray) -> np.ndarray:
    g = gaussian_filter(band, 1.0)
    step = g[2:] - g[:-2]  # light below dark: positive
    return 1.0 + np.argmax(step, axis=0).astype(np.float64)


STAIRCASE = 10.0  # slope kurtosis (est., from a handful of wave frames): smooth multi-scale edges 4-9, a reported staircase 34


def slope_kurtosis(edge: np.ndarray) -> float:
    """Kurtosis of the edge's slope: flat runs joined by steps (cells, quarter-resolution rows) score high whatever their
    spacing, so a staircase shrinking with perspective, which no single period catches, still shows."""
    slope = np.diff(np.asarray(edge, np.float64))
    slope -= slope.mean()
    var = float((slope ** 2).mean())
    return float((slope ** 4).mean() / var ** 2) if var > 0 else 0.0


def period(signal: np.ndarray, min_lag: int = 8) -> tuple[int, float]:
    """Dominant period (px) of a 1-D profile and its normalised autocorrelation peak (0 when none)."""
    s = np.asarray(signal, np.float64)
    s = s - gaussian_filter(s, max(4.0, len(s) / 16.0))  # keep detail, drop the slow trend (perspective, light)
    s -= s.mean()
    n = len(s)
    if n < 4 * min_lag or not s.any():
        return 0, 0.0
    spectrum = np.fft.rfft(s, 2 * n)
    ac = np.fft.irfft(spectrum * np.conj(spectrum))[:n]
    ac = ac / ac[0]  # biased: falls with the lag, so the fundamental beats its multiples
    top = n // 3
    lags = np.arange(min_lag, top)
    peaks = [k for k in lags if ac[k] >= ac[k - 1] and ac[k] >= ac[k + 1]]
    if not peaks:
        return 0, 0.0
    best = max(peaks, key=lambda k: ac[k])
    return int(best), float(max(0.0, ac[best]))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("change", "flicker"):
        p = sub.add_parser(name)
        p.add_argument("source", type=Path)
        p.add_argument("--region", type=lambda t: fractions(t, 4), default=(0.0, 0.0, 1.0, 1.0))
        p.add_argument("--fps", type=float, default=30.0)
        p.add_argument("--window", type=lambda t: tuple(float(v) for v in t.split(",")))
        if name == "change":
            p.add_argument("--lag", type=float, default=0.1)
        else:
            p.add_argument("--map", type=Path)
    p = sub.add_parser("period")
    p.add_argument("source", type=Path)
    p.add_argument("--band", type=lambda t: fractions(t, 2), default=(0.0, 1.0))
    p.add_argument("--x1", type=float, default=1.0)
    p.add_argument("--edge", action="store_true")
    args = parser.parse_args(argv)

    if args.command == "period":
        image = np.asarray(Image.open(args.source).convert("L"), np.float32)
        band = crop(image, (0.0, args.band[0], args.x1, args.band[1]))
        profile = edge_rows(band) if args.edge else band.mean(axis=0)
        px, peak = period(profile)
        kind = "lower edge" if args.edge else "column luma"
        verdict = "visible repeat" if peak > 0.5 else "no dominant period"
        line = f"{args.source.name}: {kind} period {px} px, autocorrelation {peak:.2f} ({verdict})"
        if args.edge:
            k = slope_kurtosis(profile)
            line += f"; slope kurtosis {k:.1f} ({'staircase' if k > STAIRCASE else 'no steps'})"
        print(line)
        return 0

    with tempfile.TemporaryDirectory(prefix="clip-metrics-") as scratch:
        frames, fps = load(args.source, args.fps, args.window, Path(scratch))
    if args.command == "change":
        value, blocks, lag = change(frames, fps, args.lag, args.region)
        print(f"{args.source.name}: detail change per {args.lag:g} s {value:.3f} "
              f"(blocks {blocks}, lag {lag} frames at {fps:.1f} fps)")
        return 0
    md, mean, p99, share = flicker(frames, args.region)
    print(f"{args.source.name}: flicker |d2| mean {mean:.3f}/255, p99 {p99:.3f}, share > 1/255 {share * 100:.2f} % "
          f"({len(frames)} frames)")
    if args.map:
        heat = Image.fromarray(np.clip(md * 20, 0, 255).astype(np.uint8)).convert("RGB")
        middle = Image.fromarray(np.clip(crop(frames[len(frames) // 2], args.region), 0, 255).astype(np.uint8))
        sheet = Image.new("RGB", (heat.width, heat.height * 2))
        sheet.paste(middle.convert("RGB"), (0, 0))
        sheet.paste(heat, (0, heat.height))
        sheet.save(args.map, quality=85)
    return 0


if __name__ == "__main__":
    sys.exit(main())
