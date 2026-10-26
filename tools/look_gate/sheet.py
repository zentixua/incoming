# /// script
# dependencies = ["Pillow"]
# ///
"""Probe frame sheet: variant rows, scene-moment columns.

  uv run sheet.py <out-root> <scene> <file.png> <variant>[=caption] ... [--w 480]
Frames: <out-root>/<variant>/screenshots/probe_<scene>_<moment>.png; moments from the first variant, in time order.
"""
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

args = sys.argv[1:]
w = 480
if "--w" in args:
    i = args.index("--w"); w = int(args[i + 1]); del args[i:i + 2]
root, scene, out, *variants = args
root = Path(root)
labels = []
for v in variants:
    name, _, label = v.partition("=")
    labels.append((name, label or name))
times = set()
for name, _ in labels:
    for p in (root / name / "screenshots").glob(f"probe_{scene}_*.png"):
        times.add(p.stem[len(f"probe_{scene}_"):])
def sec(t):
    m = re.match(r"([\d.]+)s", t)
    return float(m.group(1)) if m else 1e9
times = sorted(times, key=sec)
h = w * 9 // 16
pad, top, left = 4, 28, 150
sheet = Image.new("RGB", (left + len(times) * (w + pad), top + len(labels) * (h + pad)), (24, 24, 24))
d = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype("/usr/share/fonts/TTF/DejaVuSans.ttf", 18)
except OSError:
    font = ImageFont.load_default()
d.text((8, 4), scene, fill=(255, 255, 255), font=font)
for j, t in enumerate(times):
    d.text((left + j * (w + pad) + 6, 4), t, fill=(255, 255, 255), font=font)
for i, (name, label) in enumerate(labels):
    y = top + i * (h + pad)
    d.text((8, y + h // 2 - 10), label, fill=(255, 255, 255), font=font)
    for j, t in enumerate(times):
        p = root / name / "screenshots" / f"probe_{scene}_{t}.png"
        if p.exists():
            sheet.paste(Image.open(p).convert("RGB").resize((w, h), Image.LANCZOS), (left + j * (w + pad), y))
        else:
            d.text((left + j * (w + pad) + 20, y + h // 2), "no frame", fill=(200, 80, 80), font=font)
sheet.save(out, quality=88)
print(out, sheet.size)
