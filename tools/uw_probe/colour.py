# /// script
# dependencies = ["numpy", "pillow"]
# ///
"""Probe colour numbers (unified-wave 1b): per frame of a take, the dust region below the horizon (rows 45-100 %): median
luma, hue of the mean colour, black share (luma < 0.05), luma std (structure). Usage: colour.py <takes dir> <take>... --t 4,10,..."""
import colorsys
import sys
from pathlib import Path
import numpy as np
from PIL import Image

root = Path(sys.argv[1])
names = [a for a in sys.argv[2:] if not a.startswith("--") and "," not in a]
ts = [float(x) for x in sys.argv[sys.argv.index("--t") + 1].split(",")]
for t in ts:
    cells = []
    for n in names:
        f = root / n / f"{n}_{t:06.2f}s.png"
        if not f.exists():
            cells.append(f"{n}: -")
            continue
        a = np.asarray(Image.open(f).convert("RGB"), np.float32) / 255
        r = a[int(a.shape[0] * 0.45):]
        l = r @ np.array([0.2126, 0.7152, 0.0722])
        m = r.reshape(-1, 3).mean(0)
        h = colorsys.rgb_to_hsv(*m)[0] * 360
        cells.append(f"{n}: luma {np.median(l):.3f} hue {h:4.0f} black {(l < 0.05).mean():.2f} std {l.std():.3f}")
    print(f"{t:6.0f}s | " + " | ".join(cells))
