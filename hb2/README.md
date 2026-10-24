# PR 442 (HB2): bloom of fire, fireball, flashes and rocket flames

Sandbox (`tools/sandbox.sh`), laptop RTX 4060, power profile "performance". Rows in every sheet: **main** = 3d45467f
(origin/main at branch point), **HB2** = b5752a9f. Same client-side blasts (seed 1, crater fire seed 2), same cameras:
Shahed `50 6 90 8`, missile `60 8 90 10`, bomb `real bunker` `100 8 90 12` (fresh site each run, server seeds differ),
launch `launch missile` `45 4 90 15`, crater fire at night `30 4 90 4`.

- `iris_*.jpg` — Iris 1.8.14 + Complementary Unbound r5.9.3, 1280×800 (bloom levels 2–8, glow pass at half frame).
- `vanilla_*.jpg` — no shader pack, 1280×800.
- `hires_night.jpg` — HB2 only, 2560×1600 (levels 3–9, glow pass at quarter frame): missile and Shahed at night.
- `crop_missile_night_1s.png` — missile at night, 1 s, full size: main / first round (halo laid over the fire too: folds
  washed out) / HB2 (halo around the fire, folds as in main).
- `cpu_preview_shown.jpg`, `cpu_preview_model.jpg` — CPU preview of the same pyramid on a baked fire frame, night and
  day sky: emission from the shown frame only (too weak) vs with the model's overexposure (chosen).

The launch scene differs between runs in both branches (pad cloud and framing), so the launch rows are not like for like.

## Cost (2560×1600, Iris + Complementary, night)

Whole effects layer GPU time minus its heat pass, bloom on and off by hot swap in one session, three runs each,
interleaved (`ab3.sh`); means in ms:

| Scene, time | off | on | bloom |
| --- | --- | --- | --- |
| missile, 2 s | 1.279 | 1.696 | +0.42 |
| missile, 3 s | 2.643 | 3.242 | +0.60 |
| missile, 4 s | 4.094 | 4.682 | +0.59 |
| Shahed, 1 s | 1.085 | 1.282 | +0.20 |
| Shahed, 2 s | 0.661 | 1.075 | +0.41 |
| Shahed, 3 s | 1.126 | 1.576 | +0.45 |

Per stage with `glFinish` around it (diagnostic build, not in the PR): glow pass 0.10–0.31 ms, pyramid + output
0.36–0.41 ms. The earlier variant with emission as a second render target of the layer cost +0.6–0.85 ms for the
missile.
