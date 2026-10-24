# /// script
# dependencies = ["Pillow", "numpy"]
# ///
"""Ореол = FAST − OFF одной сессии. haloab.py off.png fast.png 'имя=x0,y0,x1,y1[,smoke|sky]' … — в рамке: средняя линейная
яркость OFF, прибавка FAST и её доля; smoke — только красноватый дым (не огонь, не небо), sky — только тёмное небо."""
import sys
import numpy as np
from PIL import Image
off, fast = [np.asarray(Image.open(f).convert('RGB')).astype(float) / 255 for f in sys.argv[1:3]]
def lum(im):
    lin = im ** 2.2
    return 0.2126 * lin[..., 0] + 0.7152 * lin[..., 1] + 0.0722 * lin[..., 2]
for a in sys.argv[3:]:
    name, spec = a.split('=', 1); p = spec.split(',')
    x0, y0, x1, y1 = [int(v) for v in p[:4]]; kind = p[4] if len(p) > 4 else 'all'
    o, f = off[y0:y1, x0:x1], fast[y0:y1, x0:x1]; lo, lf = lum(o), lum(f)
    if kind == 'smoke':
        mask = (lo < 0.12) & (lo > 0.015) & (o[..., 0] > 2 * o[..., 2])
    elif kind == 'sky':
        mask = lo < 0.015
    elif kind == 'core':
        m = (lo < 0.12) & (lo > 0.015) & (o[..., 0] > 2 * o[..., 2])
        # сердцевина дыма: и все соседи в 3 пикселях — дым (край полупрозрачен, ореол там законно проходит)
        mask = m.copy()
        for dy in range(-3, 4):
            for dx in range(-3, 4):
                mask &= np.roll(np.roll(m, dy, 0), dx, 1)
    else:
        mask = np.ones_like(lo, bool)
    add = (lf - lo)[mask].mean()
    print(f"{name}: {mask.mean() * 100:.0f} % of box, OFF {lo[mask].mean() * 100:.2f} %, halo +{add * 100:.2f} % ({add / lo[mask].mean() * 100:+.0f} %)")
