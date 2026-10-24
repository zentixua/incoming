# /// script
# dependencies = ["Pillow", "numpy", "scipy"]
# ///
"""Предпросмотр свечения на процессоре: кадр огня из атласа на фоне, та же пирамида, что в FxBloom (на numpy)."""
import sys, numpy as np
from PIL import Image
from scipy.ndimage import map_coordinates

def sample(img, x, y):
    # линейная выборка, край по краю; x, y — в пикселях (середина i — i+0.5)
    h, w = img.shape[:2]
    out = np.empty(x.shape + (3,), np.float32)
    for c in range(3):
        out[..., c] = map_coordinates(img[..., c], [np.clip(y - 0.5, 0, h - 1), np.clip(x - 0.5, 0, w - 1)], order=1, mode='nearest')
    return out

def down(s):
    h, w = s.shape[:2]
    th, tw = (h + 1) // 2, (w + 1) // 2
    yy, xx = np.mgrid[0:th, 0:tw].astype(np.float32)
    cx, cy = 2 * xx + 1, 2 * yy + 1
    S = lambda dx, dy: sample(s, cx + dx, cy + dy)
    return (S(0, 0) * 0.125 + (S(-2, 2) + S(2, 2) + S(-2, -2) + S(2, -2)) * 0.03125
            + (S(0, 2) + S(-2, 0) + S(2, 0) + S(0, -2)) * 0.0625 + (S(-1, 1) + S(1, 1) + S(-1, -1) + S(1, -1)) * 0.125)

def tent(s, w, h):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy = (xx + 0.5) / 2, (yy + 0.5) / 2
    S = lambda dx, dy: sample(s, cx + dx, cy + dy)
    return ((S(-1, -1) + S(1, -1) + S(-1, 1) + S(1, 1)) + 2 * (S(0, -1) + S(-1, 0) + S(1, 0) + S(0, 1)) + 4 * S(0, 0)) / 16

def halo(e, first, last):
    lv = [e]
    for k in range(1, last + 1): lv.append(down(lv[-1]))
    up = lv[last]
    for k in range(last - 1, first - 1, -1): up = tent(up, lv[k].shape[1], lv[k].shape[0]) + lv[k]
    for k in range(first - 1, 0, -1): up = tent(up, lv[k].shape[1], lv[k].shape[0])
    h, w = e.shape[:2]
    return tent(up, w, h) * 0 + sample(up, (np.mgrid[0:h, 0:w][1] + 0.5) / 2, (np.mgrid[0:h, 0:w][0] + 0.5) / 2) / (last - first + 1)

def film_inv(y):
    t = np.clip(y, 0, 1); a = 2.51 - 2.43 * t; b = 0.03 - 0.59 * t; c = -0.14 * t
    return (-b + np.sqrt(b * b - 4 * a * c)) / (2 * a)

atlas, out = sys.argv[1], sys.argv[2]
W, H = 1280, 800
fire = np.asarray(Image.open(atlas).convert('RGBA'), np.float32) / 255
size = int(sys.argv[3]) if len(sys.argv) > 3 else 360
fire = np.asarray(Image.fromarray((fire * 255).astype(np.uint8)).resize((size, size), Image.LANCZOS), np.float32) / 255
rows = []
for bg in [(0.02, 0.025, 0.04), (0.55, 0.68, 0.85)]:
    frame = np.ones((H, W, 3), np.float32) * np.array(bg, np.float32)
    glow = np.zeros((H, W, 3), np.float32)
    y0, x0 = H // 2 - size // 2, W // 2 - size // 2
    a = fire[..., 3:4]
    rgb = fire[..., :3]  # прямая альфа, значения экрана
    reg = frame[y0:y0 + size, x0:x0 + size]
    frame[y0:y0 + size, x0:x0 + size] = rgb * a + reg * (1 - a)
    gain = float(sys.argv[4]) if len(sys.argv) > 4 else 0
    if gain > 0:
        L = rgb ** 2.2 * gain
        top = np.maximum(L.max(-1, keepdims=True), 1e-6)
        glow[y0:y0 + size, x0:x0 + size] = L * np.minimum(1, 7.2417 / top) * a
    else:
        glow[y0:y0 + size, x0:x0 + size] = film_inv(rgb ** 2.2) * a
    b = halo(glow, 2, 8) * 0.12
    t = 1 - np.exp(-b)
    s = t ** (1 / 2.2)
    with_bloom = s + frame - s * frame
    rows.append(np.concatenate([frame, with_bloom], 1))
img = np.clip(np.concatenate(rows, 0), 0, 1)
Image.fromarray((img * 255).astype(np.uint8)).save(out, quality=90)
print(out)
