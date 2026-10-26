# /// script
# dependencies = ["numpy", "scipy"]
# ///
"""Analyze probe runs: frame time, scene audio, echo comb, Shahed spectrum.

  uv run analyze.py <out-root> <variant> ...   → <out>/<variant>/wav/*.wav, <out>/analysis.json, table in stdout
"""
import json
import re
import sys
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import welch

RATE_BYTES = 4  # stereo int16
HEADER = 44


def events(log):
    ev = {}
    for line in open(log, encoding="utf-8", errors="replace"):
        m = re.search(r"SCENARIO probe (\S+) blast tick (\d+) game \d+ audio (\d+)", line)
        if m:
            ev.setdefault(m.group(1), {})["blast"] = (int(m.group(2)), int(m.group(3)))
        m = re.search(r"SCENARIO probe (\S+) launch tick (\d+) game \d+ audio (\d+)", line)
        if m:
            ev.setdefault(m.group(1), {})["blast"] = (int(m.group(2)), int(m.group(3)))
        m = re.search(r"SCENARIO probe flyby d=(\d+) tick (\d+) audio (\d+)", line)
        if m:
            ev.setdefault("flyby", {})["closest"] = (int(m.group(2)), int(m.group(3)), int(m.group(1)))
        m = re.search(r"SCENARIO probe flyby blast tick (\d+) audio (\d+)", line)
        if m:
            ev.setdefault("flyby", {})["blast"] = (int(m.group(1)), int(m.group(2)))
    return ev


def frames(path):
    out = {}
    for line in open(path):
        p = line.split()
        if len(p) == 3:
            out.setdefault(p[0], []).append((int(p[1]), float(p[2])))
    return out


def stats(ms):
    if not ms:
        return None
    a = np.array(ms)
    return {"mean": round(float(a.mean()), 2), "p99": round(float(np.percentile(a, 99)), 2), "max": round(float(a.max()), 1), "n": len(a)}


def comb(x, rate):
    """Comb: cepstrum peak (2–40 ms) against its median - for copies of the same sound with a delay τ peak at τ."""
    if len(x) < rate:
        return None
    n = 4096
    peaks = []
    for s in range(0, len(x) - n, n // 2):
        w = x[s:s + n] * np.hanning(n)
        spec = np.log(np.abs(np.fft.rfft(w)) + 1e-9)
        cep = np.abs(np.fft.irfft(spec))
        lo, hi = int(0.002 * rate), int(0.040 * rate)
        seg = cep[lo:hi]
        peaks.append(seg.max() / (np.median(seg) + 1e-12))
    return round(float(np.median(peaks)), 2)


def tone(x, rate):
    """Flight spectrum: frequency of the strongest tone 50–2000 Hz and centroid 50–8000 Hz."""
    f, p = welch(x, rate, nperseg=8192)
    band = (f > 50) & (f < 2000)
    peak = float(f[band][np.argmax(p[band])])
    band2 = (f > 50) & (f < 8000)
    centroid = float((f[band2] * p[band2]).sum() / p[band2].sum())
    return {"peak_hz": round(peak, 1), "centroid_hz": round(centroid, 1)}


def main():
    root = Path(sys.argv[1])
    result = {}
    for v in sys.argv[2:]:
        d = root / v
        log = d / "latest.log"
        if not log.exists():
            continue
        ev = events(log)
        fr = frames(d / "probe-frames.txt") if (d / "probe-frames.txt").exists() else {}
        rate, audio = wavfile.read(d / "audio.wav") if (d / "audio.wav").exists() else (22050, np.zeros((0, 2)))
        mono = audio.astype(np.float64).mean(axis=1) / 32768 if len(audio) else np.zeros(0)
        (d / "wav").mkdir(exist_ok=True)
        res = {}
        for case, e in ev.items():
            r = {}
            if case in fr:
                ticks = fr[case]
                r["frame_all"] = stats([ms for _, ms in ticks])
                if "blast" in e:
                    b = e["blast"][0]
                    r["frame_blast10s"] = stats([ms for t, ms in ticks if b <= t < b + (100 if case.startswith("launch") else 200)])
            if "blast" in e and len(mono):
                at = (e["blast"][1] - HEADER) // RATE_BYTES
                if case == "flyby" and "closest" in e:
                    c = (e["closest"][1] - HEADER) // RATE_BYTES
                    seg = slice(max(0, c - 10 * rate), c + 10 * rate)
                    r["flyby_closest_m"] = e["closest"][2]
                    r["drone_tone"] = tone(mono[seg], rate)
                else:
                    seg = slice(max(0, at - rate), at + (20 if case.startswith("grad") else 12) * rate)
                    tail = mono[at + rate: at + 5 * rate]
                    r["echo_comb"] = comb(tail, rate)
                wavfile.write(d / "wav" / f"{case}.wav", rate, audio[seg])
            res[case] = r
        result[v] = res
    (root / "analysis.json").write_text(json.dumps(result, indent=1, ensure_ascii=False))
    cases = sorted({c for r in result.values() for c in r})
    for c in cases:
        print(c)
        for v, r in result.items():
            x = r.get(c, {})
            print(f"  {v:8s} frame10s={x.get('frame_blast10s')} all={x.get('frame_all')} comb={x.get('echo_comb')} tone={x.get('drone_tone')}")


main()
