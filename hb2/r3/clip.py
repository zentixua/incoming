# /// script
# dependencies = ["Pillow", "numpy"]
# ///
"""Пересвет по кадрам: доля белых пикселей (все каналы ≥ 250) и средняя яркость (линейная, % белого) в кадре.
clip.py <scene> 'main=dir' 'r1=dir' … — строки по моментам, общим у всех прогонов (по возрасту из capture.json)."""
import json, os, sys
import numpy as np
from PIL import Image

scene = sys.argv[1]
runs = []
for a in sys.argv[2:]:
    label, d = a.split('=', 1)
    if not os.path.exists(os.path.join(d, 'capture.json')):
        continue
    cap = json.load(open(os.path.join(d, 'capture.json')))
    runs.append((label, d, {round(f['t'], 2): f['file'] for f in cap['frames']}))
ages = sorted(set.intersection(*[set(r[2]) for r in runs])) if runs else []
print(f"| {scene}, s | " + " | ".join(f"{l}: channel ≥250 %, brighter than ½ %, mean %" for l, _, _ in runs) + " |")
print("| --- " * (len(runs) + 1) + "|")
for age in ages:
    cells = []
    for label, d, frames in runs:
        im = np.asarray(Image.open(os.path.join(d, frames[age])).convert('RGB')).astype(np.float64)
        sat = np.any(im >= 250, axis=2).mean() * 100
        lin = (im / 255) ** 2.2
        luma = 0.2126 * lin[..., 0] + 0.7152 * lin[..., 1] + 0.0722 * lin[..., 2]
        half = (luma ** (1 / 2.2) > 0.5).mean() * 100
        cells.append(f"{sat:.2f}, {half:.2f}, {luma.mean() * 100:.2f}")
    print(f"| {age:g} | " + " | ".join(cells) + " |")
