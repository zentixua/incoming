# /// script
# dependencies = ["pillow", "numpy"]
# ///
"""Effects behind see-through blocks: the visible share of an effect behind each wall of tools/see_through_check.sh.

For every case (a `pane` block, `none` for the open view) the frames are base_<case>_000.05s.png (the scene before the
effect) and <prefix>_<case>_<t>.png (the effect at t). The share is the mean |frame - base| over the pixels where the open
effect differs from its base, relative to the open view: 1 — the effect is seen as without the wall, 0 — hidden by it.

    uv run tools/see_through.py <frames dir> [--at 002.00s] [--min case=share …] [--max case=share …] [--sheet out.png]

Exit 1 when a case is missing or its share is outside its bound (the regression check: effects stay visible, tinted,
behind see-through blocks and hidden behind opaque ones).
"""
import argparse
import glob
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

# a pixel the open effect changes: summed RGB difference from its base above this (8-bit noise and the base's own motion
# stay below)
CHANGED = 0.08


def load(path):
    return np.asarray(Image.open(path).convert('RGB')).astype(np.float32) / 255


def shares(d, prefix, at):
    """Case → visible share at frame time `at`, and the frames read."""
    cases = sorted({os.path.basename(f)[len(prefix) + 1:].rsplit('_', 1)[0] for f in glob.glob(f'{d}/{prefix}_*_{at}.png')})
    if 'none' not in cases:
        sys.exit(f'{d}: no open view {prefix}_none_{at}.png')
    out, frames = {}, {}
    base = load(f'{d}/base_none_000.05s.png')
    open_diff = np.abs(load(f'{d}/{prefix}_none_{at}.png') - base).sum(2)
    mask = open_diff > CHANGED
    if not mask.any():
        sys.exit(f'{d}: the open effect changes no pixel at {at}')
    reference = open_diff[mask].mean()
    for c in cases:
        bp, fp = f'{d}/base_{c}_000.05s.png', f'{d}/{prefix}_{c}_{at}.png'
        if not os.path.exists(bp):
            continue
        try:
            out[c] = float(np.abs(load(fp) - load(bp)).sum(2)[mask].mean() / reference)
        except OSError as e:
            print(f'{c}: unreadable frame ({e})')
            continue
        frames[c] = (bp, fp)
    return out, frames


def bound(text):
    case, value = text.split('=')
    return case, float(value)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('dir')
    p.add_argument('--prefix', default='blast')
    p.add_argument('--at', default='002.00s')
    p.add_argument('--min', type=bound, action='append', default=[])
    p.add_argument('--max', type=bound, action='append', default=[])
    p.add_argument('--sheet')
    a = p.parse_args()
    got, frames = shares(a.dir, a.prefix, a.at)
    for c, s in sorted(got.items(), key=lambda kv: (kv[0] != 'none', kv[0])):
        print(f'{c:28s} {s:.2f}')
    bad = []
    for c, v in a.min:
        if c not in got or got[c] < v:
            bad.append(f'{c}: {got.get(c, float("nan")):.2f} < {v:.2f}')
    for c, v in a.max:
        if c not in got or got[c] > v:
            bad.append(f'{c}: {got.get(c, float("nan")):.2f} > {v:.2f}')
    if a.sheet:
        w = 480
        rows = sorted(frames, key=lambda c: (c != 'none', c))
        first = Image.open(frames[rows[0]][0])
        h = int(w * first.height / first.width)
        sheet = Image.new('RGB', (2 * w, (h + 18) * len(rows)), 'black')
        draw = ImageDraw.Draw(sheet)
        for i, c in enumerate(rows):
            for j, f in enumerate(frames[c]):
                sheet.paste(Image.open(f).convert('RGB').resize((w, h)), (j * w, i * (h + 18) + 18))
            draw.text((4, i * (h + 18) + 2), f'{c}   visible {got[c]:.2f}', fill='white')
        sheet.save(a.sheet)
    if bad:
        print('see-through check failed: ' + '; '.join(bad))
        sys.exit(1)


if __name__ == '__main__':
    main()
