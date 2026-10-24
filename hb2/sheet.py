# /// script
# dependencies = ["Pillow", "numpy"]
# ///
"""Лист сравнения: строки — прогоны (подписи), столбцы — моменты; sheet.py out.jpg 'main=dir1' 'HB2=dir2' [--scale 0.5]."""
import json, os, sys
from PIL import Image, ImageDraw

args = [a for a in sys.argv[2:] if not a.startswith('--')]
scale = 0.5
if '--scale' in sys.argv:
    scale = float(sys.argv[sys.argv.index('--scale') + 1])
    args = [a for a in args if a != sys.argv[sys.argv.index('--scale') + 1]]
rows = []
for a in args:
    label, d = a.split('=', 1)
    cap = json.load(open(os.path.join(d, 'capture.json')))
    rows.append((label, d, cap))
cols = max(len(r[2]['frames']) for r in rows)
tiles = []
for label, d, cap in rows:
    row = []
    for f in cap['frames']:
        im = Image.open(os.path.join(d, f['file'])).convert('RGB')
        im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
        dr = ImageDraw.Draw(im)
        txt = f"{label}  {f['age']:.2f} s"
        dr.rectangle([0, 0, 8 + 7 * len(txt), 16], fill=(0, 0, 0))
        dr.text((4, 2), txt, fill=(255, 255, 255))
        row.append(im)
    tiles.append(row)
w, h = tiles[0][0].size
sheet = Image.new('RGB', (w * cols, h * len(tiles)), (20, 20, 20))
for j, row in enumerate(tiles):
    for i, im in enumerate(row):
        sheet.paste(im, (i * w, j * h))
sheet.save(sys.argv[1], quality=90)
print(sys.argv[1], sheet.size)
