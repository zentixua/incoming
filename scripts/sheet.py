# /// script
# dependencies = ["Pillow"]
# ///
"""sheet.py out.jpg width "row label" img1 img2 ... -- "row label" img... : contact sheet, rows of frames with labels."""
import sys
from PIL import Image, ImageDraw, ImageFont
out, w = sys.argv[1], int(sys.argv[2])
rows, cur = [], None
for a in sys.argv[3:]:
    if a == '--': cur = None; continue
    if cur is None: cur = [a, []]; rows.append(cur)
    else: cur[1].append(a)
cols = max(len(r[1]) for r in rows)
ims = [[Image.open(p).convert('RGB') for p in r[1]] for r in rows]
ar = ims[0][0].height / ims[0][0].width
h = int(w * ar); lab = 22
sheet = Image.new('RGB', (cols * w, len(rows) * (h + lab)), (20, 20, 20))
d = ImageDraw.Draw(sheet)
try: f = ImageFont.truetype('/usr/share/fonts/TTF/DejaVuSans.ttf', 16)
except Exception: f = ImageFont.load_default()
for i, (r, row) in enumerate(zip(rows, ims)):
    for j, (im, p) in enumerate(zip(row, r[1])):
        x, y = j * w, i * (h + lab)
        sheet.paste(im.resize((w, h), Image.LANCZOS), (x, y + lab))
        name = p.rsplit('_', 1)[-1].replace('.png', '')
        d.text((x + 6, y + 3), f"{r[0]} | {name}", fill=(255, 255, 255), font=f)
sheet.save(out, quality=90)
print(out, sheet.size)
