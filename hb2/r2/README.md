# PR 442 (HB2), review round 2

Sandbox, laptop RTX 4060, power profile "performance". **main** = dc36fcba (with #425), **HB2** = eb85a90d (same code as the
PR head 0a96e4b6, which only changes the setting's text). Rows: main / FAST (default) / FULL where shown. 1280×800.

- `iris_*.jpg` — Iris 1.8.14 + Complementary Unbound r5.9.3; `vanilla_*.jpg` — no shader pack.
- Smoke in front of fire: `iris_drone_back.jpg`, `vanilla_drone_back.jpg` (Shahed at night from the other side, its smoke
  arms cross the fire), full-size crops at 1 s: `crop_smoke_in_front_iris_1s.png`, `crop_smoke_in_front_vanilla_1s.png`
  (main left, HB2 right).
- Also: missile, Shahed, crater fire and bomb at night, Shahed by day. The FULL rows were shot later in the same session;
  their ground differs (torn grass from earlier blasts), the fire and glow are what to compare.

## Small moving emitter: energy of the glow pass per frame

Missile launch at night seen from 900 m (`launch missile`, camera `900 3 90 3`), FAST, 2560×1600. A diagnostic build
read the deepest pyramid level every frame (its sum is the whole emission energy of the glow pass); spots on (this PR)
and off (threshold 0, as before the review). Raw series: `e_spots.txt`, `e_nospots.txt` (ms, energy).

| | frames | mean energy | frame-to-frame change, median | 90th percentile |
| --- | --- | --- | --- | --- |
| spots (PR) | 128 | 515 | 0.5 % | 5 % (tick steps of the launch) |
| no spots | 108 | 202 | 6.8 % | 43 % |

Point sampling in the reduced pass lost ~60 % of the light of the small exhaust and flickered.

## Cost (2560×1600, Iris, night)

Whole effects layer GPU time minus its heat pass; OFF / FAST / FULL switched through `incoming-client.toml` in one
session, interleaved, three runs each (`abq.sh`); means, ms:

| Scene, time | OFF | FAST | FULL |
| --- | --- | --- | --- |
| missile, 2 s | 2.696 | 2.940 (+0.24) | 3.040 (+0.34) |
| missile, 3 s | 3.871 | 4.147 (+0.28) | 4.461 (+0.59) |
| missile, 4 s | 4.839 | 5.203 (+0.36) | 5.293 (+0.45) |
| Shahed, 1 s | 1.633 | 1.982 (+0.35) | 1.983 (+0.35) |
| Shahed, 2 s | 2.344 | 2.663 (+0.32) | 2.794 (+0.45) |
| Shahed, 3 s | 2.338 | 2.545 (+0.21) | 2.622 (+0.28) |

Run-to-run noise is about ±0.1 ms.
