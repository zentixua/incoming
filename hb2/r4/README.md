# PR 442 (HB2), review round 4: smoke in front of the fire absorbs its halo

Sandbox, laptop RTX 4060, power profile "performance", Iris 1.8.14 + Complementary Unbound r5.9.3, 1280×720 window
(frames 1280×692). **main** = dc36fcba (`m3s_*`, same size), **r3 FAST** = e4055928 (round 3), **FAST / FULL** =
752fd451 (PR head, `r6s_*`, `r6F_*`). The FULL rows were shot after the FAST rows in the same world (ground already
stripped): compare the fire, the smoke and the glow.

- Missile at night, full size: `crop_missile_night_05s_iris.png`, `crop_missile_night_1s_iris.png` — main / r3 FAST /
  FAST / FULL. Sheets: `iris_missile_night.jpg` (0.1 / 0.5 / 1 / 2 s).
- Shahed at night from the other side, 1 s, full size: `crop_smoke_in_front_iris_1s.png`; sheet `iris_drone_back.jpg`.
- Also: `iris_drone_night.jpg`, `iris_fire_night.jpg`, `iris_bomb_night.jpg` (main / r3 FAST / FAST).
- `covered_missile_night.jpg`: a debug build that writes the covered share (what the halo is multiplied away by) to the
  frame, row 2, against the frame without glow, row 1. White = the halo is fully covered. Fire, the smoke arms and the
  ground dust cover it over the whole frame; no rectangle. The first round-4 commit (d9f837f8) still drew the glow pass
  in the emitters' scissor box: ground dust outside it did not cover the halo (a hard rectangle in this map).

## How much halo the smoke lets through

From the debug map (`coverstat.py`, covered share on smoke pixels of the frame without glow; smoke = reddish, darker
than the fire, brighter than the sky; core = smoke pixels whose neighbours within 3 px are smoke too):

| Missile at night | smoke core | all smoke | sky beside the ball |
| --- | --- | --- | --- |
| 0.5 s | 0.73 covered (10 % of pixels below 0.39) | 0.72 | 0.00 |
| 1 s | 0.97 covered (10 % of pixels below 0.92) | 0.91 | 0.00 |

At 1 s the arms in front of the ball let through 3 % of the halo at their core. At 0.5 s the young smoke at the base of
the ball is still thin and lets through about a quarter, which is what the medium model says it should.

Light added by the glow (`haloab.py`, FAST minus OFF in one session; smoke pixels; linear luma of the frame):
missile at 1 s, smoke arms +1.8 % with round 3, +0.7…2.1 % now (core), sky beside the ball +1.2 % (unchanged). Two runs of
the same setting differ by up to about 1 % on these few pixels, so the frame maps above are the better measure.

## Cost

`abq.sh`, 2560×1600, night, OFF / FAST / FULL interleaved, three runs each, whole effects layer minus its heat pass;
and the glow pass timer of the same runs:

| | missile 2 / 3 / 4 s | Shahed 1 / 2 / 3 s |
| --- | --- | --- |
| FAST − OFF, round 2 | +0.24 / +0.28 / +0.36 ms | +0.35 / +0.32 / +0.21 ms |
| FAST − OFF, now | +0.68 / +0.36 / +0.60 ms | +0.59 / +0.23 / +0.09 ms |
| FULL − OFF, now | +0.75 / +0.86 / +1.24 ms | +0.44 / +0.16 / +0.27 ms |

Between sessions the layer time with OFF itself moves by 0.2–0.3 ms, so the per-pass timer is the steadier figure: the
glow pass of the missile at 2–3 s is 0.12–0.19 ms now against 0.04–0.08 ms in round 3; the pyramid and the output are
unchanged (0.09 and 0.06 ms). `r5split.sh` swapped the depth reach off in the same session: 0.115/0.188 ms with it,
0.131/0.178 ms without — no cost of its own. The whole difference is drawing the glow pass over the whole frame
instead of the emitters' box; that is what removes the rectangle.
