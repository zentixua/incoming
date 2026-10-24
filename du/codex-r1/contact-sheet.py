# /// script
# dependencies = ["pillow"]
# ///
"""Build labelled QA sheets from untouched sandbox PNGs; retain the originals."""
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parent
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 20)
small = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 16)
for scene in ('paved-roof', 'paved-hit', 'forest-hit', 'forest-close', 'forest-downwind'):
    sheet = Image.new('RGB', (2560, 816), '#151515')
    draw = ImageDraw.Draw(sheet)
    for row, (variant, label) in enumerate((('main', 'main 4498ecfc'), ('du2', 'DU2 1bd1cae5'))):
        folder = root / variant / scene
        meta = json.loads((folder / 'capture.json').read_text())
        y = row * 408
        draw.text((10, y + 2), label, font=font, fill='white')
        for col, frame in enumerate(meta['frames']):
            with Image.open(folder / frame['file']) as image:
                sheet.paste(image.convert('RGB').resize((640, 360), Image.Resampling.LANCZOS), (640 * col, y + 27))
            draw.text((640 * col + 10, y + 388), f"t = {frame['t']:g} s (captured {frame['age']:.3f} s)", font=small, fill='white')
    sheet.save(root / f'{scene}-compare.jpg', quality=94, subsampling=0)
    # Fixed central crop of both original 10 s frames, at native scale, for the roof/ground detail.
    detail = Image.new('RGB', (1600, 450), '#151515')
    dd = ImageDraw.Draw(detail)
    for col, (variant, label) in enumerate((('main', 'main 4498ecfc - 10 s'), ('du2', 'DU2 1bd1cae5 - 10 s'))):
        folder = root / variant / scene
        meta = json.loads((folder / 'capture.json').read_text())
        frame = next(f for f in meta['frames'] if f['t'] == 10)
        with Image.open(folder / frame['file']) as image:
            w, h = image.size
            assert w >= 800 and h >= 420
            crop = image.convert('RGB').crop(((w-800)//2, (h-420)//2, (w+800)//2, (h+420)//2))
            detail.paste(crop, (800 * col, 30))
        dd.text((800 * col + 10, 3), label + ' (central crop)', font=font, fill='white')
    detail.save(root / f'{scene}-10s-detail.jpg', quality=94, subsampling=0)
    print(scene)
