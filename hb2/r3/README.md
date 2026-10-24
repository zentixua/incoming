# PR 442 (HB2), review round 3: night frames after the normalisation fix

Sandbox, laptop RTX 4060. **main** = dc36fcba (#425; main has since moved to df4ee0d6, which changes only the nuke and
the sandbox verbs, not conventional blasts), **HB2** = e4055928 (PR head). 1280×720 window (frames 1280×692), both
trees in the same session, same cameras and seeds as round 2 (`shots2.sh`). Rows: main / FAST (default) / FULL where
shown.

- `iris_*.jpg` — Iris 1.8.14 + Complementary Unbound r5.9.3; `vanilla_*.jpg` — no shader pack.
- Smoke in front of fire (Shahed at night from the other side, 1 s, full size): `crop_smoke_in_front_iris_1s.png`,
  `crop_smoke_in_front_vanilla_1s.png`. Missile at night, 1 s, full size: `crop_missile_night_1s_iris.png`.
- FULL rows were shot after the FAST rows in the same world: the ground under the missile is already stripped by
  earlier blasts. Compare the fire and the glow.
- main fire and bomb without a pack (`vanilla_fire_night.jpg`, `vanilla_bomb_night.jpg`) are `m2v_*`, the rest `m3*_`;
  all at 1280×692.

## Overexposure, measured on the frames

`overexposure.md` (`clip.py`): per frame, the share of pixels with any channel ≥ 250, the share brighter than half
white (luma on screen), and the mean linear luma of the frame.

- Saturated pixels: the same as main in every scene and moment (−0.01 … +0.07 % of the frame). The glow does not clip
  anything.
- Area brighter than half white grows by 0 … 1.15 % of the frame (largest: missile at 0.5 s, 2.38 → 3.53 %; bomb at
  1 s, 1.22 → 1.74 %).
- Mean linear luma of the frame (FAST against main): +21 … +52 % during the flash and the fireball (0.1–1 s; largest:
  bomb at 1 s, +49 % with Iris, +52 % without), +1 … +17 % from 2 s on and over the crater fire. Most of it is the
  glow over the lit ground and sky around the fire, which is dark at night.

What it looks like: a warm glow about one fireball radius around the fire, the fire's folds and soot as in main. The
strongest effect is at 0.5–1 s of the missile: the smoke arms right in front of and below the fireball get a soft veil
(`crop_missile_night_1s_iris.png`). This is the share choice, not an error: 0.12 is Complementary's default
`BLOOM_STRENGTH`, applied here to emission capped at the bake's white point (7.24). The round-1 frames you approved had
the same share; the round-2 frames were 7× weaker because of the double normalisation.
