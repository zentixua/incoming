# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow", "scipy"]
# ///
"""Motion and repetition numbers for a look clip or frame: the measures a visual task states before a clip goes out.

The same command runs on our take and on the reference footage, so both sides are measured the same way. Frames are
read one at a time and taken to 720 rows (as a video is decoded), so a long 1080p take fits in memory.

  change  <frames|video> [--region x0,y0,x1,y1] [--lag 0.1]
          Detail change per lag: 1 − correlation of the high-pass detail (frame − Gaussian blur σ 3 px) of 32 px
          blocks between frames `lag` seconds apart, after the best whole-pixel shift within ±8 px (every shift is
          tried), median over textured blocks. 0: the detail only moves (or stands); 1: new detail every lag (boiling,
          re-randomised noise). A block whose partner is flat scores correlation 0. Also the matched blocks' apparent
          speed: median and p90 of the best shift, px/s at 720 rows (× metres per pixel of the view for m/s). The search
          reaches 8 px per lag (80 px/s at the default 0.1 s, 240 px/s at --lag 0.034): motion beyond it reads as
          change, and the line warns when many blocks sit on that ceiling.
  flicker <frames|video> [--region …] [--map out.jpg]
          Frame-to-frame pulsing: per pixel the temporal median of |second difference| of luma over 5 s chunks (smooth
          motion gives ~0; a debris piece crossing a pixel in a frame or two does not count), mean and p99 over the
          region, share of pixels above 1/255; --map writes where it is (×20) under a middle frame.
  lattice <png> [--region x0,y0,x1,y1]
          A regular dot lattice on a dust face (an interleaved or decimated sampling pattern that the history keeps): the
          strongest repeat of the face's fine detail (luma - Gaussian blur sigma 3 px at 720 rows) at lags of 6-30 px, its
          normalised 2D autocorrelation over the warm dust pixels of the region (red over blue, eroded 8 px). Billows and
          lobes stay under 0.15 (main's wall at 3.9 km: 0.08-0.13); a dot lattice the eye reads as a screen door repeats above
          it (the new wave's step-3 frames near the passage: 0.18-0.62). Exit 1 above LATTICE. Limit: a smooth face upsampled
          from a coarse lattice (1/6) repeats at its own period too (0.4-0.6 on a dot-free harness image): compare takes at
          the same divisor, and judge the frames themselves.
  period  <png> [--band y0,y1] [--x1 f] [--edge]
          Repetition along x inside a band: normalised autocorrelation of the band's high-passed column-mean luma
          (--edge: of the band's lower edge, the strongest dark-over-light step per column at sub-pixel row). Prints
          the dominant period in px (2 px and up) and its autocorrelation peak; a peak above 0.5 is a visible repeat
          (copied pieces). --edge also prints the edge slope's kurtosis: above 10 is a staircase (flat runs joined by
          steps), also when perspective shrinks the spacing so that no single period exists. The band must hold one
          dark-over-light edge across its width (check its crop): elsewhere the strongest step jumps between features
          and the numbers mean nothing.

Frames: a directory of PNGs sorted by a `_<age>s.png` suffix (sandbox takes; their fps from the ages) or by name
(--fps, default 30); a video (mp4/webm/mkv) is decoded at 720 rows by ffmpeg, --window t0,t1 in its seconds.
Regions are fractions of the frame. Exit 0; numbers on stdout, one line per command.
Run: uv run tools/clip_metrics.py change mod/run/sandbox/out/take --region 0,0.4,1,0.8
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from collections import deque
from pathlib import Path
from typing import Iterator

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter

AGE = re.compile(r"_([-+]?\d+(?:\.\d+)?)s\.png$")
VIDEO = {".mp4", ".webm", ".mkv", ".mov", ".avi"}
ROWS = 720  # every input is measured at this height, as the video decode gives
BLOCK, REACH, TEXTURED = 32, 8, 0.8
CHUNK = 150  # frames per flicker median (5 s at 30 fps): bounds memory, still robust to passing debris
LATTICE = 0.15  # repeat peak of a dust face's fine detail: main's wall at 3.9 km 0.05-0.13, the dot lattice 0.18-0.62 (step-3 frames)
STAIRCASE = 10.0  # slope kurtosis (est., from a handful of wave frames): smooth multi-scale edges 4-9, a reported staircase 34


def fractions(text: str, count: int) -> tuple[float, ...]:
    values = tuple(float(v) for v in text.split(","))
    if len(values) != count or not all(0.0 <= v <= 1.0 for v in values):
        raise argparse.ArgumentTypeError(f"{count} fractions 0..1 expected: {text}")
    return values


def luma(path: Path) -> np.ndarray:
    image = Image.open(path).convert("L")
    if image.height > ROWS:
        image = image.resize((round(image.width * ROWS / image.height), ROWS), Image.BILINEAR)
    return np.asarray(image, np.float32)


def frame_files(source: Path, fps: float, window: tuple[float, float] | None, scratch: Path) -> tuple[list[Path], float]:
    """The frames' files in order and their rate."""
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
        return files, fps
    if source.suffix.lower() not in VIDEO:
        raise SystemExit(f"not a frame directory or video: {source}")
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "stream=r_frame_rate",
                            "-of", "csv=p=0", str(source)], capture_output=True, text=True, check=True).stdout.strip()
    num, _, den = probe.partition("/")
    fps = float(num) / float(den or 1)
    cut = ["-ss", str(window[0]), "-t", str(window[1] - window[0])] if window else []
    subprocess.run(["ffmpeg", "-loglevel", "error", *cut, "-i", str(source), "-vf", f"scale=-2:{ROWS}",
                    str(scratch / "f%05d.png")], check=True)
    return sorted(scratch.glob("f*.png")), fps


def crop(frame: np.ndarray, region: tuple[float, float, float, float]) -> np.ndarray:
    h, w = frame.shape
    x0, y0, x1, y1 = region
    return frame[int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)]


def detail(frame: np.ndarray) -> np.ndarray:
    return frame - gaussian_filter(frame, 3.0)


def _blocks(a: np.ndarray, y0: int, x0: int, ny: int, nx: int) -> np.ndarray:
    return a[y0:y0 + ny * BLOCK, x0:x0 + nx * BLOCK].reshape(ny, BLOCK, nx, BLOCK).transpose(0, 2, 1, 3)


def _normalised(blocks: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    z = blocks - blocks.mean(axis=(2, 3), keepdims=True)
    norm = np.sqrt((z * z).sum(axis=(2, 3)))
    return z, norm


SHIFTS = sorted(((dy, dx) for dy in range(-REACH, REACH + 1) for dx in range(-REACH, REACH + 1)),
                key=lambda d: (d[0] ** 2 + d[1] ** 2, d))  # nearest first: a tie keeps the shortest shift


def best_shift(a: np.ndarray, b: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Per textured block of `a`: the highest correlation with `b` over every whole-pixel shift within ±REACH, the
    length of that shift (px), and whether it lies on the search edge (the real motion may be faster)."""
    h, w = a.shape
    ny, nx = (h - 2 * REACH) // BLOCK, (w - 2 * REACH) // BLOCK
    if ny < 1 or nx < 1:
        return np.empty(0), np.empty(0)
    ba = _blocks(a, REACH, REACH, ny, nx)
    za, na = _normalised(ba)
    textured = ba.std(axis=(2, 3)) >= TEXTURED
    best = np.full((ny, nx), -np.inf)
    shift = np.zeros((ny, nx))
    edge = np.zeros((ny, nx), bool)
    for dy, dx in SHIFTS:
        zb, nb = _normalised(_blocks(b, REACH + dy, REACH + dx, ny, nx))
        with np.errstate(invalid="ignore", divide="ignore"):
            c = np.where(nb > 0, (za * zb).sum(axis=(2, 3)) / (na * nb), 0.0)  # a flat partner: correlation 0
        better = c > best
        best = np.where(better, c, best)
        shift = np.where(better, np.hypot(dy, dx), shift)
        edge = np.where(better, max(abs(dy), abs(dx)) == REACH, edge)
    return best[textured], shift[textured], edge[textured]


def change(files: list[Path], fps: float, lag_s: float, region) -> tuple[float, int, int, float, float, float]:
    """(detail change, blocks, lag frames, median and p90 apparent speed px/s at ROWS rows, share of blocks whose best
    shift is on the search edge); a lag window of frames in memory. Speeds count matched blocks only (correlation > 0.5);
    the ceiling is REACH px per lag, so a high edge share means faster motion than shown (shorten --lag)."""
    lag = max(1, round(lag_s * fps))
    stride = max(1, lag // 2)
    window: deque[tuple[int, np.ndarray]] = deque()
    corr, speed, edges = [], [], []
    scale = 1.0
    for k, path in enumerate(files):
        frame = luma(path)
        scale = ROWS / frame.shape[0]  # speeds are stated at ROWS rows also for smaller frames
        window.append((k, detail(crop(frame, region))))
        while window and window[0][0] < k - lag:
            window.popleft()
        first, a = window[0]
        if first == k - lag and first % stride == 0:
            c, sh, e = best_shift(a, window[-1][1])
            corr.append(c)
            speed.append(sh[c > 0.5])
            edges.append(e[c > 0.5])
    values = np.concatenate(corr) if corr else np.empty(0)
    if not len(values):
        raise SystemExit("no textured blocks in the region (or fewer frames than the lag)")
    px_s = np.concatenate(speed) * fps / lag * scale
    on_edge = np.concatenate(edges)
    if not len(px_s):
        return 1.0 - float(np.median(values)), len(values), lag, float("nan"), float("nan"), 1.0
    return (1.0 - float(np.median(values)), len(values), lag, float(np.median(px_s)), float(np.percentile(px_s, 90)),
            float(on_edge.mean()))


def flicker(files: list[Path], region) -> tuple[np.ndarray, float, float, float]:
    """Per-pixel temporal median of |second difference| per chunk of CHUNK frames, averaged over chunks by length."""
    if len(files) < 3:
        raise SystemExit("flicker needs at least 3 frames")
    total, weight = None, 0
    for start in range(0, len(files) - 2, CHUNK):
        part = files[start:start + CHUNK + 2]
        if len(part) < 3:
            break
        stack = np.stack([crop(luma(f), region) for f in part])
        md = np.median(np.abs(stack[2:] - 2 * stack[1:-1] + stack[:-2]), axis=0) * (len(part) - 2)
        total = md if total is None else total + md
        weight += len(part) - 2
    md = total / weight
    return md, float(md.mean()), float(np.percentile(md, 99)), float((md > 1.0).mean())


def edge_rows(band: np.ndarray) -> np.ndarray:
    """Per column the row of the strongest dark-over-light step, refined to sub-pixel by a parabola through its
    neighbours (whole rows alone turn a straight gentle slope into a staircase)."""
    g = gaussian_filter(band, 1.0)
    step = g[2:] - g[:-2]  # light below dark: positive
    i = np.argmax(step, axis=0)
    cols = np.arange(step.shape[1])
    lo, hi = np.clip(i - 1, 0, step.shape[0] - 1), np.clip(i + 1, 0, step.shape[0] - 1)
    a, b, c = step[lo, cols], step[i, cols], step[hi, cols]
    den = a - 2 * b + c
    with np.errstate(invalid="ignore", divide="ignore"):
        off = np.where((den < 0) & (i > 0) & (i < step.shape[0] - 1), 0.5 * (a - c) / den, 0.0)
    return 1.0 + i + np.clip(off, -0.5, 0.5)


def slope_kurtosis(edge: np.ndarray) -> float:
    """Kurtosis of the edge's slope: flat runs joined by steps (cells, quarter-resolution rows) score high whatever their
    spacing, so a staircase shrinking with perspective, which no single period catches, still shows."""
    slope = np.diff(np.asarray(edge, np.float64))
    slope -= slope.mean()
    var = float((slope ** 2).mean())
    return float((slope ** 4).mean() / var ** 2) if var > 1e-12 else 0.0


def period(signal: np.ndarray, min_lag: int = 2) -> tuple[int, float]:
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
    lags = np.arange(min_lag, n // 3)
    peaks = [k for k in lags if ac[k] >= ac[k - 1] and ac[k] >= ac[k + 1]]
    if not peaks:
        return 0, 0.0
    best = max(peaks, key=lambda k: ac[k])
    return int(best), float(max(0.0, ac[best]))


def lattice(image: np.ndarray, region) -> tuple[float, int]:
    """The repeat peak of the dust face's fine detail (6-30 px lags) and the dust pixel count; nan below 20 000 pixels."""
    from scipy.ndimage import binary_erosion
    h, w = image.shape[:2]
    if h != ROWS:
        image = np.asarray(Image.fromarray(image.astype(np.uint8)).resize((round(w * ROWS / h), ROWS), Image.BILINEAR), np.float32)
        h, w = image.shape[:2]
    x0, y0, x1, y1 = region
    im = image[int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)] / 255.0
    lum = im @ np.array([0.2126, 0.7152, 0.0722])
    dust = binary_erosion((im[..., 0] > im[..., 2] + 0.03) & (lum > 0.15), iterations=8)
    if dust.sum() < 20000:
        return float("nan"), int(dust.sum())
    fine = (lum - gaussian_filter(lum, 3)) * dust
    shape = (2 * fine.shape[0], 2 * fine.shape[1])
    acf = np.fft.irfft2(np.abs(np.fft.rfft2(fine, s=shape)) ** 2, s=shape)
    overlap = np.fft.irfft2(np.abs(np.fft.rfft2(dust.astype(np.float64), s=shape)) ** 2, s=shape)
    norm = acf / np.maximum(overlap, 1)
    norm /= norm[0, 0]
    best = 0.0
    for dy in range(0, 31):
        for dx in range(-30, 31):
            if 36 <= dy * dy + dx * dx <= 900:
                best = max(best, float(norm[dy, dx % shape[1]]))
    return best, int(dust.sum())


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
    p = sub.add_parser("lattice")
    p.add_argument("source", type=Path)
    p.add_argument("--region", type=lambda t: fractions(t, 4), default=(0.0, 0.1, 1.0, 0.52))
    p = sub.add_parser("period")
    p.add_argument("source", type=Path)
    p.add_argument("--band", type=lambda t: fractions(t, 2), default=(0.0, 1.0))
    p.add_argument("--x1", type=float, default=1.0)
    p.add_argument("--edge", action="store_true")
    args = parser.parse_args(argv)

    if args.command == "lattice":
        image = np.asarray(Image.open(args.source).convert("RGB"), np.float32)
        peak, n = lattice(image, args.region)
        verdict = "too little dust" if peak != peak else "dot lattice" if peak > LATTICE else "no lattice"
        print(f"{args.source.name}: dust face repeat peak {peak:.3f} at 6-30 px over {n} dust pixels ({verdict})")
        return 1 if peak > LATTICE else 0

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
        files, fps = frame_files(args.source, args.fps, args.window, Path(scratch))
        if args.command == "change":
            value, blocks, lag, v50, v90, edge = change(files, fps, args.lag, args.region)
            ceiling = REACH * fps / lag
            warn = (f"; {edge * 100:.0f} % of matched blocks at the {ceiling:.0f} px/s search ceiling: real motion is "
                    "faster, measure again with a shorter --lag") if edge > 0.1 else ""
            print(f"{args.source.name}: detail change per {args.lag:g} s {value:.3f}; apparent speed median {v50:.0f}, "
                  f"p90 {v90:.0f} px/s at {ROWS} rows{warn} (blocks {blocks}, lag {lag} frames at {fps:.1f} fps)")
            return 0
        md, mean, p99, share = flicker(files, args.region)
        print(f"{args.source.name}: flicker |d2| mean {mean:.3f}/255, p99 {p99:.3f}, share > 1/255 {share * 100:.2f} % "
              f"({len(files)} frames)")
        if args.map:
            heat = Image.fromarray(np.clip(md * 20, 0, 255).astype(np.uint8)).convert("RGB")
            middle = Image.fromarray(np.clip(crop(luma(files[len(files) // 2]), args.region), 0, 255).astype(np.uint8))
            sheet = Image.new("RGB", (heat.width, heat.height * 2))
            sheet.paste(middle.convert("RGB"), (0, 0))
            sheet.paste(heat, (0, heat.height))
            sheet.save(args.map, quality=85)
    return 0


if __name__ == "__main__":
    sys.exit(main())
