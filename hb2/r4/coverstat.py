# /// script
# dependencies = ["Pillow", "numpy"]
# ///
"""Доля «закрыто» по отладочному кадру (вывод — экраном: out = c + dst·(1 − c), c = (out − dst)/(1 − dst)) на масках OFF.
coverstat.py off.png dbg.png 'имя=x0,y0,x1,y1,kind' …"""
import sys
import numpy as np
from PIL import Image
off, dbg = [np.asarray(Image.open(f).convert('RGB')).astype(float) / 255 for f in sys.argv[1:3]]
def lum(im):
    lin = im ** 2.2
    return 0.2126 * lin[..., 0] + 0.7152 * lin[..., 1] + 0.0722 * lin[..., 2]
for a in sys.argv[3:]:
    name, spec = a.split('=', 1); p = spec.split(','); x0, y0, x1, y1 = [int(v) for v in p[:4]]; kind = p[4]
    o, d = off[y0:y1, x0:x1], dbg[y0:y1, x0:x1]; lo = lum(o)
    m = (lo < 0.12) & (lo > 0.015) & (o[..., 0] > 2 * o[..., 2])
    if kind == 'core':
        mask = m.copy()
        for dy in range(-3, 4):
            for dx in range(-3, 4):
                mask &= np.roll(np.roll(m, dy, 0), dx, 1)
    elif kind == 'sky':
        mask = lo < 0.015
    else:
        mask = m
    # синий канал: у дыма и огня он тёмный, деление на (1 − dst) устойчиво
    c = np.clip((d[..., 2] - o[..., 2]) / np.maximum(1 - o[..., 2], 1e-3), 0, 1)
    print(f"{name}: {mask.mean() * 100:.0f} % of box, covered mean {c[mask].mean():.2f}, 10th percentile {np.percentile(c[mask], 10):.2f}")
