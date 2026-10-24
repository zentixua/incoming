# /// script
# dependencies = ["Pillow", "numpy"]
# ///
"""Вырезка в полный размер вокруг огня: crop.py out.png w h 'подпись=файл' … — центр по самому яркому месту первого."""
import sys
import numpy as np
from PIL import Image, ImageDraw

out, w, h = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
items = [a.split('=', 1) for a in sys.argv[4:]]
first = np.asarray(Image.open(items[0][1]).convert('L')).astype(float)
ys, xs = np.nonzero(first >= np.percentile(first, 99.7))
cx, cy = int(np.median(xs)), int(np.median(ys))
tiles = []
for label, f in items:
    im = Image.open(f).convert('RGB')
    x0 = max(0, min(im.width - w, cx - w // 2)); y0 = max(0, min(im.height - h, cy - h // 2))
    t = im.crop((x0, y0, x0 + w, y0 + h))
    d = ImageDraw.Draw(t); d.rectangle([0, 0, 8 + 7 * len(label), 16], fill=(0, 0, 0)); d.text((4, 2), label, fill=(255, 255, 255))
    tiles.append(t)
sheet = Image.new('RGB', (w * len(tiles), h))
for i, t in enumerate(tiles):
    sheet.paste(t, (i * w, 0))
sheet.save(out)
print(out, sheet.size, (cx, cy))
