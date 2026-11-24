# /// script
# dependencies = ["numpy", "pillow", "scipy"]
# ///
"""T6 measure (unified-wave 2c-i): the dust's top line across a frame, per column the row of the strongest dark-over-light
step (dust below, sky or haze above) inside a band, smoothed over 3 px; prints its std and range in px (at 720 rows) and
the share of columns whose line stands 8+ px above its 200 px running median (tufts). Usage:
  topline.py <png> [--band y0,y1] [--cols x0,x1] (fractions of the frame)"""
import sys
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter1d, median_filter

f = sys.argv[1]
band = [float(v) for v in sys.argv[sys.argv.index("--band") + 1].split(",")] if "--band" in sys.argv else [0.25, 0.75]
cols = [float(v) for v in sys.argv[sys.argv.index("--cols") + 1].split(",")] if "--cols" in sys.argv else [0.0, 1.0]
im = Image.open(f).convert("L")
im = im.resize((round(im.width * 720 / im.height), 720))
a = np.asarray(im, np.float64) / 255
h, w = a.shape
y0, y1 = int(band[0] * h), int(band[1] * h)
x0, x1 = int(cols[0] * w), int(cols[1] * w)
s = gaussian_filter1d(a[y0:y1, x0:x1], 2.0, axis=0)
step = s[:-4] - s[4:]          # brighter above than below: sky over dust
line = y0 + 2 + np.argmax(step, axis=0).astype(np.float64)
line = np.convolve(line, np.ones(3) / 3, "same")
base = median_filter(line, size=201, mode="nearest")
tufts = float(((base - line) >= 8).mean())
print(f"{f.rsplit('/', 1)[-1]}: top line std {line[2:-2].std():.1f} px, range {np.ptp(line[2:-2]):.0f} px, tufts (8+ px over the "
      f"running median) {100 * tufts:.1f} % of columns")
