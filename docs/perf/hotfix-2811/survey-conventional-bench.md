# Survey: scene bench 3.14.0 vs 3.13.0 (100 strikes = the raid scene, grad, missile, shahed; packs none and Photon)

From the survey mission (measurement only). Nuclear takes follow when the survey finishes.

# Scene stand: v3.14.0 (B)

GPU NVIDIA GeForce RTX 4060 Laptop GPU, driver 620.73.04, window 2560x1600, scenes ['100', 'grad', 'missile', 'shahed'], stand bc9543a8c131, power performance, jar sha256 99fb1ee1b032 (incoming-3.14.0.jar).

## Verdict against v3.13.0: PASS
Each pack is shot as a pair in one session: base A1, candidate B, base A2. The candidate fails a value when it is more than 25% above the worse of A1 and A2; a pack that does not pass is shot by another pair, and the last pair decides.
- frame time at 100 strikes within tolerance

Look differs noticeably (for the reviewer, not a failure):
- none 100_edge: smoky pixels 20% versus 32% / 31%

Cells: GPU p50 / CPU p95 / frame p95 (frames per second), ms. GPU — the GPU clock from the first to the last command of the frame; CPU — CPU time of the render thread in the frame (RenderFrameEvent.Pre → Post, without the buffer swap, preemption and driver sleep); frame — from the frame start to the next frame start, as the player sees it. The gate judges GPU p50 and CPU p95 in the `100/*` windows.

## Pack: none

| window | v3.14.0 (B) | v3.13.0 A1 | v3.13.0 A2 |
|---|---|---|---|
| 100-hit | 18.1 / 22.2 / 34.5 (44) | 19.6 / 24.1 / 36.0 (42) | 20.6 / 25.1 / 39.0 (40) |
| 100/outside | 2.8 / 7.4 / 9.3 (145) | 2.6 / 6.2 / 7.9 (167) | 2.5 / 6.7 / 8.3 (159) |
| 100/edge | 4.1 / 6.9 / 8.8 (151) | 3.9 / 5.9 / 7.9 (174) | 3.7 / 5.9 / 8.2 (173) |
| 100/inside | 4.8 / 6.1 / 8.7 (153) | 4.9 / 5.5 / 7.8 (178) | 3.3 / 6.0 / 8.2 (173) |
| grad/start | 2.5 / 3.8 / 5.0 (280) | 2.2 / 4.2 / 5.2 (280) | 1.9 / 3.7 / 4.8 (292) |
| grad/hang | 1.4 / 3.2 / 3.5 (363) | 1.2 / 2.9 / 3.3 (381) | 1.2 / 2.9 / 3.4 (378) |
| missile/start | 0.9 / 2.2 / 2.9 (454) | 0.6 / 2.2 / 2.8 (457) | 0.6 / 2.2 / 2.7 (464) |
| missile/hang | 0.6 / 1.9 / 2.3 (576) | 0.4 / 1.7 / 2.0 (628) | 0.4 / 1.7 / 2.0 (626) |
| shahed/start | 2.1 / 2.6 / 4.5 (363) | 1.7 / 2.4 / 4.0 (407) | 1.6 / 2.3 / 3.7 (419) |
| shahed/hang | 0.5 / 1.6 / 2.1 (608) | 0.4 / 1.7 / 2.0 (631) | 0.4 / 1.6 / 2.0 (634) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | v3.14.0 (B) | v3.13.0 A1 |
|---|---|---|
| after_level+ | 2.68 | 2.46 |
| after_particles | 0.19 | 0.05 |
| after_solid_blocks | 0.07 | 0.07 |
| after_sky | 0.04 | 0.04 |
| tail | 0.04 | 0.04 |
| after_block_entities+ | 0.04 | 0.00 |
| after_entities | 0.01 | 0.01 |
| after_weather | 0.00 | 0.00 |

Bound: cpu; server tick mean 1.7 ms; wall-clock Pre→Post p95 7.6 ms; other load on the machine 1.4 cores, GPU 36 % (nothing heavy); probes: FxLayer.describeTiming: quads 2161, 5.307 ms (longest in the second 6.593; without GC 6.593, CPU 5.288; frames with GC 0), GPU 2.261 ms (longest 3.953, 158 frames; dust wall 0.000, longest 0.000, 0 frames; heat pass 0.438, longest 0.637, 158 frames — heat lights 4 hot 4 front 0 jets 0, area 2 rects (2 lit) 34.2% [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.08] [r 2.5 h 21 dT 63 px 1.04] [r 2.5 h 21 dT 63 px 1.02]; bloom FAST 0.243 ms (longest 1.516, 158 frames; glow pass 0.060, pyramid 0.117, out 0.064; 2560x1600, glow level 3, levels 3-9)), depth: world, see-through off; GPU by pass (ms, mean of 158 frames; a pass's tail may spill into the next): models 0.00 depth 0.16 water 0.00 pall 0.00 heat 0.44 probes 0.01 quads 1.56 bloom 0.24 (sum 2.42); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1623 quads, full 1098 (a quad over both kinds of tiles counts in each), lays 1, uploads 0.063 ms a draw (longest 0.155, all views); FarRenderer.describeSmoke: fill 11.45 screens (budget 16, one club per gaussian 11.45, before merging 25.50), cluster share 0.00, merge level 3, veil gaussians 0, clusters 1935, plan 4.49 ms off-thread, veil set 0

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | v3.14.0 (B) | v3.13.0 A1 |
|---|---|---|
| after_level+ | 3.83 | 3.75 |
| after_block_entities+ | 0.22 | 0.00 |
| after_translucent_blocks | 0.10 | 0.22 |
| after_solid_blocks | 0.08 | 0.08 |
| tail | 0.05 | 0.05 |
| after_sky | 0.04 | 0.04 |
| after_translucent_blocks+ | 0.01 | 0.00 |
| after_entities | 0.01 | 0.01 |

Bound: cpu; server tick mean 1.6 ms; wall-clock Pre→Post p95 7.0 ms; other load on the machine 0.3 cores, GPU 8 % (nothing heavy); probes: FxLayer.describeTiming: quads 1219, 5.785 ms (longest in the second 10.045; without GC 7.565, CPU 7.053; frames with GC 2), GPU 3.513 ms (longest 5.169, 143 frames; dust wall 0.000, longest 0.000, 0 frames; heat pass 1.357, longest 2.472, 143 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.3 h 19 dT 63 px 1.35]; bloom FAST 0.295 ms (longest 0.541, 143 frames; glow pass 0.110, pyramid 0.113, out 0.070; 2560x1600, glow level 3, levels 3-9)), depth: world, see-through off; GPU by pass (ms, mean of 143 frames; a pass's tail may spill into the next): models 0.00 depth 0.18 water 0.00 pall 0.00 heat 1.36 probes 0.04 quads 1.82 bloom 0.30 (sum 3.69); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 699 quads, full 721 (a quad over both kinds of tiles counts in each), lays 1, uploads 0.039 ms a draw (longest 0.083, all views); FarRenderer.describeSmoke: fill 15.97 screens (budget 16, one club per gaussian 15.97, before merging 37.27), cluster share 0.00, merge level 3, veil gaussians 42, clusters 835, plan 3.91 ms off-thread, veil set 42 [57.20 -5.91 143.09 43.64 8.65 244.0 0.851 0.290 0.290 0.290] [-41.42 -8.31 187.37 28.72 5.52 51.48 0.358 0.290 0.290 0.290] [-20.49 -5.92 121.13 14.25 1.18 3.357 0.978 0.290 0.290 0.290] [-10.59 -6.74 113.60 0.97 0.76 0.4796 0.980 0.290 0.290 0.290] [42.18 -5.81 108.40 1.88 1.21 1.343 0.980 0.290 0.290 0.290] [43.51 48.22 116.98 37.32 28.64 4591 0.944 0.290 0.290 0.290] [137.17 59.99 52.17 49.84 48.65 6080 0.952 0.290 0.290 0.290] [90.56 10.00 112.74 38.57 11.06 266.2 0.848 0.290 0.290 0.290] [-38.66 36.61 112.36 33.20 21.43 3138 0.925 0.290 0.290 0.290] [4.38 32.55 35.43 17.24 17.24 115.8 0.992 0.290 0.290 0.290] [49.42 83.43 25.74 38.56 36.31 1709 0.703 0.290 0.290 0.290] [-76.96 -9.57 110.82 41.11 5.41 62.71 0.660 0.290 0.290 0.290] [16.76 18.00 49.21 12.17 12.30 155.8 0.811 0.290 0.290 0.290] [111.68 102.87 -21.07 45.21 44.81 909.5 0.974 0.290 0.290 0.290] [96.39 10.00 76.77 22.49 11.36 223.5 0.964 0.290 0.290 0.290] [41.42 40.69 4.71 18.62 18.62 126.0 0.990 0.290 0.290 0.290] [16.39 51.37 8.15 22.14 23.26 366.2 0.991 0.290 0.290 0.290] [41.27 20.18 18.46 14.91 11.38 172.1 0.815 0.290 0.290 0.290] [2.46 26.01 18.37 13.53 13.53 10.24 0.994 0.290 0.290 0.290] [39.64 37.69 -10.79 16.70 16.70 73.06 0.990 0.290 0.290 0.290] [-16.27 23.57 38.64 11.62 11.50 51.80 0.618 0.290 0.290 0.290] [7.96 44.91 -11.59 20.34 20.28 277.4 0.992 0.290 0.290 0.290] [-12.07 48.45 27.13 20.88 20.29 263.7 0.619 0.290 0.290 0.290] [33.06 84.93 -28.98 37.73 37.06 882.0 0.984 0.290 0.290 0.290] [10.12 -8.96 24.80 8.52 5.50 17.97 0.900 0.290 0.290 0.290] [-67.74 25.44 31.39 24.55 12.97 282.2 0.927 0.290 0.290 0.290] [-38.69 28.57 14.73 14.88 14.98 248.7 0.875 0.290 0.290 0.290] [-24.39 47.30 -13.72 21.47 20.49 433.3 0.985 0.290 0.290 0.290] [-0.04 -6.01 29.12 5.40 5.40 3.112 0.866 0.290 0.290 0.290] [35.14 22.21 -5.35 11.66 11.39 90.50 0.781 0.290 0.290 0.290] [-84.06 27.52 58.25 17.37 16.42 427.4 0.917 0.290 0.290 0.290] [-37.55 60.68 31.57 27.16 25.10 553.3 0.986 0.290 0.290 0.290] [-84.41 -8.98 55.07 19.78 5.60 42.24 0.419 0.290 0.290 0.290] [9.36 33.66 -17.15 15.54 15.18 110.4 0.993 0.290 0.290 0.290] [135.73 -2.51 107.89 35.53 11.44 181.6 0.876 0.290 0.290 0.290] [45.83 -8.48 20.15 12.09 6.15 22.54 0.659 0.290 0.290 0.290] [-27.33 -6.61 9.27 8.08 5.43 6.176 0.971 0.290 0.290 0.290] [-16.00 61.34 -18.75 26.48 24.71 245.1 0.988 0.290 0.290 0.290] [-14.84 21.79 -14.39 13.46 12.68 105.3 0.995 0.290 0.290 0.290] [-51.46 33.52 -1.90 15.09 15.09 49.22 0.989 0.290 0.290 0.290] [-43.14 41.52 -5.76 17.51 17.51 59.42 0.990 0.290 0.290 0.290] [342.11 -28.26 204.43 22.13 18.76 701.5 0.862 0.847 0.847 0.847]

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | v3.14.0 (B) | v3.13.0 A1 |
|---|---|---|
| after_level+ | 4.39 | 4.68 |
| after_block_entities+ | 0.22 | 0.00 |
| after_solid_blocks | 0.08 | 0.08 |
| tail | 0.05 | 0.05 |
| after_sky | 0.05 | 0.05 |
| after_translucent_blocks | 0.04 | 0.04 |
| after_particles | 0.01 | 0.00 |
| after_translucent_blocks+ | 0.01 | 0.00 |

Bound: cpu; server tick mean 1.6 ms; wall-clock Pre→Post p95 6.5 ms; other load on the machine 0.2 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 729, 5.527 ms (longest in the second 6.488; without GC 6.488, CPU 5.294; frames with GC 0), GPU 4.236 ms (longest 5.570, 156 frames; dust wall 0.000, longest 0.000, 0 frames; heat pass 2.161, longest 2.238, 156 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world, see-through off; GPU by pass (ms, mean of 156 frames; a pass's tail may spill into the next): models 0.00 depth 0.19 water 0.00 pall 0.00 heat 2.16 probes 0.05 quads 2.02 bloom 0.00 (sum 4.43); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 618 quads, full 111 (a quad over both kinds of tiles counts in each), lays 1, uploads 0.022 ms a draw (longest 0.050, all views); FarRenderer.describeSmoke: fill 16.00 screens (budget 16, one club per gaussian 16.00, before merging 78.69), cluster share 0.00, merge level 3, veil gaussians 35, clusters 606, plan 4.97 ms off-thread, veil set 35 [591.31 84.14 -296.06 160.48 71.45 9.286e+04 0.854 0.693 0.685 0.675] [346.93 127.64 -407.04 132.57 70.18 2.538e+04 0.911 0.336 0.327 0.317] [157.42 55.55 -78.59 47.95 47.30 1.141e+04 0.885 0.290 0.290 0.290] [274.13 127.11 -185.43 103.69 52.70 8557 0.896 0.428 0.428 0.428] [9.44 30.13 -22.19 11.44 11.44 36.71 0.381 0.290 0.290 0.290] [81.89 76.16 -146.41 50.76 40.34 1.111e+04 0.933 0.290 0.290 0.290] [27.38 20.38 -36.02 14.39 9.77 156.2 0.618 0.290 0.290 0.290] [255.75 94.00 -352.23 121.16 47.50 1.378e+04 0.899 0.574 0.574 0.574] [41.21 23.96 7.14 13.15 11.30 106.0 0.303 0.290 0.290 0.290] [123.72 13.62 15.39 29.55 15.06 323.7 0.872 0.290 0.290 0.290] [-3.33 27.39 -19.37 11.41 11.41 36.32 0.994 0.290 0.290 0.290] [19.69 -4.60 -24.48 5.18 5.18 16.95 0.284 0.290 0.290 0.290] [-4.93 29.41 4.65 11.40 11.40 36.17 0.361 0.290 0.290 0.290] [18.38 47.71 -17.71 16.98 16.98 54.27 0.991 0.290 0.290 0.290] [40.55 76.05 -31.03 35.02 29.83 1471 0.984 0.290 0.290 0.290] [25.47 25.87 45.19 14.82 11.67 158.2 0.721 0.290 0.290 0.290] [68.71 20.27 -124.45 25.96 16.31 299.5 0.679 0.290 0.290 0.290] [17.83 28.52 25.11 11.36 11.36 35.74 0.360 0.290 0.290 0.290] [51.84 61.14 24.49 26.35 24.62 574.2 0.985 0.290 0.290 0.290] [-13.53 47.64 -34.04 16.83 16.83 53.12 0.990 0.290 0.290 0.290] [-15.48 69.74 -49.97 28.18 25.93 570.2 0.985 0.290 0.290 0.290] [85.10 74.42 4.85 27.71 26.83 318.3 0.981 0.290 0.290 0.290] [10.97 56.85 7.78 21.83 21.24 320.7 0.407 0.290 0.290 0.290] [-1.94 71.26 -109.91 26.15 26.15 280.9 0.978 0.290 0.290 0.290] [-9.85 35.91 40.94 13.62 13.62 92.03 0.990 0.290 0.290 0.290] [20.77 27.60 65.77 11.19 11.19 34.19 0.381 0.290 0.290 0.290] [127.78 8.18 -67.36 29.32 7.98 88.73 0.544 0.290 0.290 0.290] [11.01 43.91 44.27 16.51 16.51 51.18 0.989 0.290 0.290 0.290] [30.36 50.58 68.51 19.27 18.87 182.8 0.984 0.290 0.290 0.290] [240.59 6.44 -115.95 15.39 15.39 60.84 0.954 0.290 0.290 0.290] [1003.76 52.54 -344.70 65.10 30.91 5431 0.820 0.847 0.847 0.847] [541.19 -34.18 -269.47 89.16 32.15 7428 0.859 0.847 0.847 0.847] [605.09 -42.18 -91.82 67.49 36.11 1881 0.822 0.847 0.847 0.847] [1094.52 -44.83 -453.36 27.78 30.35 782.8 0.808 0.847 0.847 0.847] [555.68 -43.95 21.33 22.38 21.81 355.4 0.905 0.847 0.847 0.847]

v3.14.0 (B): machine load in the verdict windows: window 100/outside: foreign jobs — up to 3.41 cores (allowed 1.0): {}; window 100/outside: GPU used by foreign processes — 36 % SM in worst cycle (allowed 25), 6.66 % mean (allowed 5)

v3.13.0 A2: machine load in the verdict windows: window 100/edge: GPU used by foreign processes — 13 % SM in worst cycle (allowed 25), 5.92 % mean (allowed 5); window 100/inside: foreign jobs — up to 1.05 cores (allowed 1.0): {}

v3.14.0 (B): frames 149371, 5.9 GPU minutes, impacts: 100 100/100 (all, 1 s)

Frames side by side: `sheet-none.png`

## Pack: photon

| window | v3.14.0 (B) | v3.13.0 A1 | v3.13.0 A2 |
|---|---|---|---|
| 100-hit | 26.5 / 31.5 / 47.4 (33) | 24.0 / 38.0 / 52.3 (32) | 29.3 / 41.1 / 59.5 (28) |
| 100/outside | 12.0 / 8.5 / 15.0 (81) | 11.8 / 8.0 / 14.6 (83) | 12.1 / 8.6 / 15.1 (81) |
| 100/edge | 13.9 / 8.3 / 17.4 (70) | 13.4 / 7.7 / 16.5 (74) | 13.6 / 8.3 / 16.8 (72) |
| 100/inside | 13.4 / 8.0 / 16.6 (73) | 13.0 / 6.8 / 15.9 (76) | 13.6 / 7.5 / 16.9 (72) |
| grad/start | 11.6 / 6.7 / 15.0 (84) | 11.4 / 6.3 / 14.6 (86) | 11.5 / 6.7 / 14.9 (85) |
| grad/hang | 10.0 / 5.7 / 12.0 (95) | 10.3 / 5.6 / 12.1 (96) | 10.2 / 5.9 / 12.1 (95) |
| missile/start | 10.2 / 4.4 / 13.3 (90) | 10.1 / 5.6 / 12.8 (91) | 10.1 / 4.5 / 13.0 (91) |
| missile/hang | 9.7 / 3.5 / 11.2 (100) | 9.5 / 3.4 / 11.0 (102) | 9.7 / 4.0 / 11.2 (101) |
| shahed/start | 11.9 / 5.4 / 15.3 (82) | 11.6 / 5.5 / 15.1 (84) | 11.9 / 5.3 / 14.9 (83) |
| shahed/hang | 10.1 / 3.1 / 11.3 (97) | 10.0 / 3.7 / 11.2 (98) | 10.1 / 3.2 / 11.4 (97) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | v3.14.0 (B) | v3.13.0 A1 |
|---|---|---|
| after_tripwire_blocks | 4.50 | 4.43 |
| after_level | 3.71 | 3.65 |
| after_level+ | 3.18 | 3.09 |
| after_entities | 0.17 | 0.17 |
| after_sky | 0.16 | 0.16 |
| after_solid_blocks | 0.15 | 0.15 |
| after_translucent_blocks | 0.11 | 0.11 |
| tail | 0.05 | 0.05 |

Bound: gpu; server tick mean 1.3 ms; wall-clock Pre→Post p95 8.6 ms; other load on the machine 0.5 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1990, 4.801 ms (longest in the second 5.557; without GC 5.557, CPU 5.538; frames with GC 0), GPU 2.673 ms (longest 3.395, 83 frames; dust wall 0.000, longest 0.000, 0 frames; heat pass 0.333, longest 0.352, 83 frames — heat lights 4 hot 4 front 0 jets 0, area 2 rects (2 lit) 18.1% [r 2.5 h 21 dT 63 px 1.02] [r 2.5 h 21 dT 63 px 1.00] [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.06]; bloom FAST 0.306 ms (longest 0.665, 83 frames; glow pass 0.062, pyramid 0.134, out 0.083; 2560x1600, glow level 3, levels 3-9)), depth: world, see-through off; GPU by pass (ms, mean of 83 frames; a pass's tail may spill into the next): models 0.00 depth 0.22 water 0.01 pall 0.00 heat 0.34 probes 0.02 quads 1.99 bloom 0.32 (sum 2.89); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1684 quads, full 821 (a quad over both kinds of tiles counts in each), lays 1, uploads 0.049 ms a draw (longest 0.068, all views); FarRenderer.describeSmoke: fill 12.31 screens (budget 16, one club per gaussian 12.31, before merging 28.16), cluster share 0.00, merge level 3, veil gaussians 0, clusters 1842, plan 3.79 ms off-thread, veil set 0

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | v3.14.0 (B) | v3.13.0 A1 |
|---|---|---|
| after_tripwire_blocks | 4.81 | 4.70 |
| after_level+ | 4.60 | 4.18 |
| after_level | 3.86 | 3.74 |
| after_entities | 0.20 | 0.19 |
| after_solid_blocks | 0.17 | 0.16 |
| after_sky | 0.16 | 0.15 |
| after_translucent_blocks | 0.13 | 0.11 |
| tail | 0.06 | 0.06 |

Bound: gpu; server tick mean 1.4 ms; wall-clock Pre→Post p95 8.4 ms; other load on the machine 0.2 cores, GPU 12 % (nothing heavy); probes: FxLayer.describeTiming: quads 971, 5.084 ms (longest in the second 6.319; without GC 6.319, CPU 6.213; frames with GC 0), GPU 4.334 ms (longest 6.186, 71 frames; dust wall 0.000, longest 0.000, 0 frames; heat pass 1.726, longest 2.791, 71 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.359 ms (longest 0.368, 71 frames; glow pass 0.118, pyramid 0.137, out 0.085; 2560x1600, glow level 3, levels 3-9)), depth: world, see-through off; GPU by pass (ms, mean of 71 frames; a pass's tail may spill into the next): models 0.00 depth 0.22 water 0.01 pall 0.00 heat 1.73 probes 0.06 quads 2.16 bloom 0.38 (sum 4.57); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 612 quads, full 478 (a quad over both kinds of tiles counts in each), lays 1, uploads 0.029 ms a draw (longest 0.050, all views); FarRenderer.describeSmoke: fill 15.98 screens (budget 16, one club per gaussian 15.98, before merging 38.90), cluster share 0.00, merge level 3, veil gaussians 44, clusters 670, plan 4.13 ms off-thread, veil set 44 [-11.95 -6.07 123.32 1.97 1.13 2.014 0.978 0.290 0.290 0.290] [-14.79 -6.82 125.88 0.93 0.73 0.4796 0.978 0.290 0.290 0.290] [70.59 -7.93 114.38 29.59 6.68 245.8 0.932 0.290 0.290 0.290] [43.94 -9.23 170.40 26.72 6.20 148.0 0.795 0.290 0.290 0.290] [55.20 -6.17 127.66 1.81 1.11 1.727 0.976 0.290 0.290 0.290] [52.94 47.79 123.92 41.97 28.70 7138 0.927 0.290 0.290 0.290] [144.28 77.36 52.94 49.51 51.12 6294 0.960 0.290 0.290 0.290] [-32.69 42.94 112.88 35.25 22.52 2108 0.909 0.290 0.290 0.290] [52.00 84.53 23.39 46.22 41.92 2659 0.954 0.290 0.290 0.290] [-44.82 -9.17 99.56 36.58 6.23 57.47 0.975 0.290 0.290 0.290] [18.28 42.18 22.79 23.25 19.54 391.5 0.991 0.290 0.290 0.290] [122.61 102.55 -18.26 49.98 49.99 276.1 0.973 0.290 0.290 0.290] [63.57 18.23 86.60 23.90 14.96 161.6 0.820 0.290 0.290 0.290] [9.97 23.55 46.31 13.17 12.44 92.21 0.991 0.290 0.290 0.290] [0.75 38.08 39.56 17.97 17.97 123.6 0.991 0.290 0.290 0.290] [-16.21 -9.43 202.39 19.55 6.15 70.51 0.914 0.290 0.290 0.290] [58.06 92.88 -24.64 44.10 43.61 2213 0.973 0.290 0.290 0.290] [-0.10 17.25 50.77 10.41 10.41 24.12 0.991 0.290 0.290 0.290] [115.67 18.46 53.07 18.14 14.11 179.3 0.934 0.290 0.290 0.290] [-29.40 65.58 28.79 29.00 27.23 786.6 0.987 0.290 0.290 0.290] [84.19 -9.45 70.61 16.10 6.09 43.33 0.787 0.290 0.290 0.290] [-50.73 28.93 108.63 23.03 13.91 107.9 0.850 0.290 0.290 0.290] [-21.49 43.80 20.00 21.35 18.54 197.2 0.991 0.290 0.290 0.290] [-68.79 38.39 50.80 23.38 20.18 579.2 0.935 0.290 0.290 0.290] [-2.40 -9.72 33.20 6.55 6.03 19.62 0.994 0.290 0.290 0.290] [16.72 -8.44 33.99 9.48 6.33 19.32 0.909 0.290 0.290 0.290] [18.75 21.30 9.80 11.84 11.96 70.16 0.869 0.290 0.290 0.290] [36.54 23.47 13.89 14.13 12.27 80.38 0.992 0.290 0.290 0.290] [36.15 36.21 -2.49 16.10 16.10 12.97 0.991 0.290 0.290 0.290] [43.24 41.86 -3.75 17.04 17.04 52.65 0.990 0.290 0.290 0.290] [-17.88 27.61 14.08 14.26 13.34 11.40 0.994 0.290 0.290 0.290] [-65.98 26.37 26.61 20.07 13.66 145.0 0.704 0.290 0.290 0.290] [-98.62 -9.22 77.06 16.34 6.26 43.46 0.842 0.290 0.290 0.290] [27.58 23.97 -14.39 12.49 12.09 50.22 0.993 0.290 0.290 0.290] [-12.61 29.95 -11.54 15.51 14.17 128.0 0.698 0.290 0.290 0.290] [-50.24 41.74 -1.24 17.63 17.63 249.7 0.989 0.290 0.290 0.290] [-17.02 68.83 -17.48 28.34 30.16 396.5 0.987 0.290 0.290 0.290] [3.86 -4.23 3.64 6.25 6.25 22.90 0.999 0.290 0.290 0.290] [7.40 2.45 -0.32 7.26 7.26 19.17 0.999 0.290 0.290 0.290] [-35.30 -7.35 9.67 16.39 6.03 6.676 0.993 0.290 0.290 0.290] [-39.98 16.86 3.68 10.03 10.03 23.69 0.992 0.290 0.290 0.290] [-33.30 23.39 -2.94 12.01 11.98 43.25 0.993 0.290 0.290 0.290] [-14.67 37.84 -20.46 16.45 16.45 50.60 0.992 0.290 0.290 0.290] [238.21 124.00 40.62 46.41 46.41 70.37 0.956 0.290 0.290 0.290]

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | v3.14.0 (B) | v3.13.0 A1 |
|---|---|---|
| after_tripwire_blocks | 5.08 | 4.98 |
| after_level | 3.88 | 3.82 |
| after_level+ | 3.85 | 3.51 |
| after_entities | 0.18 | 0.18 |
| after_solid_blocks | 0.17 | 0.17 |
| after_sky | 0.16 | 0.16 |
| after_translucent_blocks | 0.11 | 0.11 |
| tail | 0.05 | 0.05 |

Bound: gpu; server tick mean 1.5 ms; wall-clock Pre→Post p95 8.1 ms; other load on the machine 0.3 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 788, 4.770 ms (longest in the second 5.667; without GC 5.667, CPU 5.643; frames with GC 0), GPU 3.699 ms (longest 5.065, 71 frames; dust wall 0.000, longest 0.000, 0 frames; heat pass 1.320, longest 1.692, 71 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.3 h 19 dT 63 px 1.35] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world, see-through off; GPU by pass (ms, mean of 71 frames; a pass's tail may spill into the next): models 0.00 depth 0.22 water 0.01 pall 0.00 heat 1.33 probes 0.10 quads 2.27 bloom 0.00 (sum 3.93); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 692 quads, full 96 (a quad over both kinds of tiles counts in each), lays 1, uploads 0.024 ms a draw (longest 0.045, all views); FarRenderer.describeSmoke: fill 15.96 screens (budget 16, one club per gaussian 15.96, before merging 79.49), cluster share 0.00, merge level 3, veil gaussians 27, clusters 679, plan 4.68 ms off-thread, veil set 27 [602.42 111.73 -355.60 167.12 86.46 7.728e+04 0.835 0.625 0.613 0.598] [456.54 186.21 -523.81 165.46 111.20 1.144e+04 0.888 0.381 0.364 0.344] [152.46 75.80 -73.56 44.20 46.22 1.033e+04 0.707 0.290 0.290 0.290] [57.76 88.87 -55.26 39.57 37.83 1352 0.980 0.290 0.290 0.290] [81.86 85.15 -155.19 46.59 48.62 7502 0.900 0.290 0.290 0.290] [44.01 31.32 6.86 14.23 12.36 80.01 0.854 0.290 0.290 0.290] [19.95 30.21 -46.24 13.87 12.40 126.7 0.648 0.290 0.290 0.290] [220.88 108.06 -304.41 105.08 53.94 1.409e+04 0.921 0.470 0.470 0.470] [103.26 56.09 23.43 31.88 27.03 1059 0.955 0.290 0.290 0.290] [52.68 31.18 -114.17 24.79 14.88 239.8 0.917 0.290 0.290 0.290] [122.16 27.61 20.66 17.91 13.24 420.8 0.826 0.290 0.290 0.290] [344.79 90.58 -208.88 88.82 56.46 1.534e+04 0.916 0.380 0.380 0.380] [47.20 68.64 19.50 31.23 27.12 1430 0.985 0.290 0.290 0.290] [3.73 28.92 42.16 12.26 12.26 40.34 0.658 0.290 0.290 0.290] [55.32 -4.04 -20.64 5.80 5.80 14.43 0.742 0.290 0.290 0.290] [111.22 24.67 -43.90 28.37 13.87 638.2 0.924 0.290 0.290 0.290] [-14.57 59.65 -39.87 21.60 23.14 107.0 0.987 0.290 0.290 0.290] [19.20 25.71 56.55 12.04 11.57 83.26 0.968 0.290 0.290 0.290] [25.45 48.33 57.27 21.01 19.27 566.3 0.737 0.290 0.290 0.290] [59.79 -5.04 -33.21 5.80 5.80 14.43 0.754 0.290 0.290 0.290] [95.92 -5.46 28.82 5.80 5.80 14.43 0.982 0.290 0.290 0.290] [-13.68 78.69 -82.88 28.86 28.87 269.7 0.981 0.290 0.290 0.290] [977.88 52.15 -413.50 43.93 36.74 3720 0.832 0.847 0.847 0.847] [661.98 -44.21 -199.75 89.79 33.48 3017 0.852 0.847 0.847 0.847] [1033.63 -43.49 -423.02 38.83 31.12 1053 0.818 0.847 0.847 0.847] [510.44 -15.65 -313.73 41.36 25.20 1210 0.824 0.847 0.847 0.847] [1021.25 40.71 -210.94 26.16 72.04 452.2 0.834 0.847 0.847 0.847]

v3.14.0 (B): machine load in the verdict windows: window 100/inside: foreign jobs — up to 1.74 cores (allowed 1.0): {}

v3.14.0 (B): frames 26871, 6.6 GPU minutes, impacts: 100 100/100 (all, 1 s)

Frames side by side: `sheet-photon.png`
