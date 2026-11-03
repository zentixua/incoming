#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=1.26", "scipy>=1.11", "soundfile>=0.12"]
# ///
"""Audition captured near/far strike mixes offline; never writes game assets.

uv run tools/audition_sounds.py --near near.wav --far far.wav --out mod/run/audition
Use sandbox WAVs to retain the runtime's timing, propagation and mix. Overlapping
copies are a signal stress check, NOT a simulation of the game's voice/mix budget.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import butter, resample_poly, sosfilt

RATE = 48000
MAX_SECONDS = 180


def db(value: float) -> float | None:
    """JSON null denotes silence, instead of non-standard JSON -Infinity."""
    return 20 * math.log10(value) if value > 0 else None


def read(path: Path) -> np.ndarray:
    info = sf.info(path)
    if info.channels not in (1, 2) or not 0 < info.duration <= MAX_SECONDS:
        raise ValueError(f"{path}: require mono/stereo audio, 0 < duration <= {MAX_SECONDS} s")
    x, rate = sf.read(path, dtype="float64", always_2d=True)
    if not np.isfinite(x).all():
        raise ValueError(f"{path}: non-finite samples")
    if rate != RATE:
        divisor = math.gcd(rate, RATE)
        x = resample_poly(x, RATE // divisor, rate // divisor, axis=0)
    return x


def overlap(x: np.ndarray, copies: int, spacing: float) -> np.ndarray:
    """Sum complete copies at sample-rounded start offsets, without limiting."""
    step = round(spacing * RATE)
    length = len(x) + (copies - 1) * step
    if length > MAX_SECONDS * RATE:
        raise ValueError(f"overlap exceeds {MAX_SECONDS} s; reduce copies or spacing")
    result = np.zeros((length, x.shape[1]), dtype=np.float64)
    for i in range(copies):
        result[i * step:i * step + len(x)] += x
    return result


def speaker(x: np.ndarray) -> np.ndarray:
    """Illustrative mono small speaker: causal 180 Hz HP / 4 kHz LP, both order 2.

    No harmonics, compression or makeup gain. Stereo is averaged before filtering,
    so cancellation remains audible. This is not a measured device response.
    """
    mono = x.mean(axis=1, keepdims=True)
    high = butter(2, 180, btype="highpass", fs=RATE, output="sos")
    low = butter(2, 4000, btype="lowpass", fs=RATE, output="sos")
    return sosfilt(low, sosfilt(high, mono, axis=0), axis=0)


def measure(x: np.ndarray) -> dict:
    peak = float(np.max(np.abs(x)))
    # A 4x FIR estimate, not a certified BS.1770 true-peak meter.
    interpolated = max(peak, float(np.max(np.abs(resample_poly(x, 4, 1, axis=0)))))
    return dict(sample_peak_dbfs=db(peak), oversampled_peak_4x_dbfs=db(interpolated),
                rms_dbfs=db(float(np.sqrt(np.mean(x * x)))),
                samples_over_full_scale=int(np.count_nonzero(np.abs(x) > 1)),
                sample_count=int(x.size), headroom_db=-db(interpolated) if interpolated else None)


def cases(near: np.ndarray, far: np.ndarray, counts: list[int], spacing: float):
    for name, source in (("near", near), ("far", far)):
        for count in counts:
            signal = overlap(source, count, spacing)
            # Include the filter's decay after the recording in both comparisons.
            signal = np.pad(signal, ((0, RATE // 2), (0, 0)))
            yield f"{name}-{count:03d}-full", signal
            yield f"{name}-{count:03d}-small", speaker(signal)
    for count in counts:
        distant = overlap(far, count, spacing)
        signal = np.zeros((max(len(near), len(distant)) + RATE // 2, max(near.shape[1], far.shape[1])))
        signal[:len(near)] += near
        signal[:len(distant)] += distant
        yield f"mixed-{count:03d}-full", signal
        yield f"mixed-{count:03d}-small", speaker(signal)


def run(near_path: Path, far_path: Path, out: Path, counts: list[int], spacing: float, margin: float) -> dict:
    if not counts or any(n < 1 or n > 100 for n in counts) or 1 not in counts:
        raise ValueError("copies must include 1 and contain integers from 1 to 100")
    if not math.isfinite(spacing) or spacing < 0 or not math.isfinite(margin) or not 1 <= margin <= 30:
        raise ValueError("spacing must be finite and nonnegative; margin must be 1..30 dB")
    near, far = read(near_path), read(far_path)
    counts = sorted(set(counts))
    rows = {name: measure(x) for name, x in cases(near, far, counts, spacing)}
    peak_db = max((r["oversampled_peak_4x_dbfs"] for r in rows.values()
                   if r["oversampled_peak_4x_dbfs"] is not None), default=-margin)
    gain_db = min(0.0, -margin - peak_db)
    gain = 10 ** (gain_db / 20)
    # Refuse an existing destination: neither inputs nor earlier auditions get replaced.
    out.mkdir(parents=True, exist_ok=False)
    for name, x in cases(near, far, counts, spacing):
        sf.write(out / f"{name}.wav", x * gain, RATE, subtype="PCM_24")
    for name, row in rows.items():
        perspective, count, device = name.split("-")
        reference = rows[f"{perspective}-001-{device}"]["oversampled_peak_4x_dbfs"]
        value = row["oversampled_peak_4x_dbfs"]
        row["peak_increase_vs_one_db"] = value - reference if value is not None and reference is not None else None
    report = dict(sample_rate=RATE, copies=counts, spacing_seconds=spacing,
                  spacing_samples=round(spacing * RATE), audition_gain_db=gain_db, margin_db=margin,
                  inputs={name: dict(path=str(path.resolve()), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
                          for name, path in (("near", near_path), ("far", far_path))},
                  measurement="Unscaled decoded mixes; 4x FIR peak estimate, not certified true peak. RMS is not LUFS.",
                  scope="Copies sum without the runtime voice budget, limiter or dynamic mix. Captured WAVs may already contain output limiting.",
                  mixed="One near recording plus N far copies, first copy simultaneous with near; N is the filename count.",
                  small_speaker="Mono average, second-order 180 Hz highpass then 4 kHz lowpass; no makeup gain. Illustrative, not calibrated.",
                  audition="All WAVs share one attenuation, never boosted; near/far and speaker level differences remain. Includes 0.5 s filter tail.",
                  cases=rows)
    (out / "report.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--near", type=Path, required=True)
    parser.add_argument("--far", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--copies", default="1,4,16", help="comma-separated counts, including 1; maximum 100")
    parser.add_argument("--spacing", type=float, default=0.075, help="seconds between copy starts; 0 tests coherent overlap")
    parser.add_argument("--margin", type=float, default=6, help="common audition peak margin in dB (default 6)")
    args = parser.parse_args()
    try:
        report = run(args.near, args.far, args.out, [int(s) for s in args.copies.split(",")], args.spacing, args.margin)
    except (ValueError, OSError, sf.LibsndfileError) as error:
        parser.error(str(error))
    print(f"{args.out}/report.json; audition gain {report['audition_gain_db']:.2f} dB")


if __name__ == "__main__":
    main()
