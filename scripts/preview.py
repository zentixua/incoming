# preview.py <bake puff dir> <out.png> frames... : how the layer shader would light each frame (sun top/right/back/front)
import sys, numpy as np
from PIL import Image
D, out, frames = sys.argv[1], sys.argv[2], [int(x) for x in sys.argv[3:]]
rows = []
for f in frames:
    m = {s: np.load(f"{D}/{s}_{f:03d}.npy") for s in ['right', 'left', 'top', 'bottom', 'front', 'back']}
    al = m['front'][..., 3]
    light = {s: m[s][..., :3].mean(-1) / np.maximum(m[s][..., 3], 1e-4) for s in m}
    mean6 = sum(light.values()) / 6
    ref = (mean6 * al * al).sum() / (al * al).sum()
    cols = []
    for lit in [0.75 * light['top'] + 0.25 * mean6, 0.75 * light['right'] + 0.25 * mean6, 0.75 * light['back'] + 0.25 * mean6, 0.75 * light['front'] + 0.25 * mean6]:
        c = 0.08 * (lit / ref)[..., None] * np.array([1, 0.97, 0.92])
        bg = np.array([0.45, 0.6, 1.0])
        o = c * al[..., None] + bg * (1 - al[..., None])
        cols.append((np.clip(o, 0, 1) ** (1 / 2.2) * 255).astype(np.uint8))
    rows.append(np.concatenate(cols, axis=1))
    print(f, 'alpha>0.5', round(float((al > 0.5).mean()), 3), 'alpha>0.05', round(float((al > 0.05).mean()), 3))
Image.fromarray(np.concatenate(rows, axis=0)).save(out)
