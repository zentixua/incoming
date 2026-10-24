# /// script
# dependencies = ["pillow"]
# ///
# Лист кадров: sheet.py <out.jpg> <cols> <w> <папка>[:кроп x0,y0,x1,y1] ... — кадры capture.json по порядку с подписью возраста.
import json, sys
from pathlib import Path
from PIL import Image, ImageDraw
out, cols, w = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
items = []
for arg in sys.argv[4:]:
    folder, _, crop = arg.partition(':')
    sel = None
    if '@' in folder:
        folder, sel = folder.split('@'); sel = [float(x) for x in sel.split(',')]
    c = json.load(open(Path(folder) / 'capture.json'))
    frames = c['frames']
    if sel: frames = [min(frames, key=lambda f: abs(f['age'] - t)) for t in sel]
    for f in frames:
        im = Image.open(Path(folder) / f['file']).convert('RGB')
        if crop: im = im.crop(tuple(int(v) for v in crop.split(',')))
        items.append((im, f"{Path(folder).name} {f['age']:.3f}s"))
h = int(items[0][0].height * w / items[0][0].width)
rows = (len(items) + cols - 1) // cols
sheet = Image.new('RGB', (cols * w, rows * (h + 18)), '#111')
d = ImageDraw.Draw(sheet)
for i, (im, label) in enumerate(items):
    x, y = (i % cols) * w, (i // cols) * (h + 18)
    sheet.paste(im.resize((w, h), Image.Resampling.LANCZOS), (x, y + 18))
    d.text((x + 4, y + 3), label, fill='white')
sheet.save(out, quality=92)
print(out, sheet.size)
