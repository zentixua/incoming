# Scene stand: FLASHCAM@07c0fd3 (B)

GPU NVIDIA GeForce RTX 4060 Laptop GPU, driver 620.70.06, window 2560x1600, scenes ['100'], stand d410cda45bc3, power performance, jar sha256 bf51d6ab8e80 (incoming-3.10.4.jar).

## Verdict against main@fa0ece44: PASS
Each pack is shot as a pair in one session: base A1, candidate B, base A2. The candidate fails a value when it is more than 25% above the worse of A1 and A2; a pack that does not pass is shot by another pair, and the last pair decides.
- frame time at 100 strikes within tolerance

Cells: GPU p50 / CPU p95 / frame p95 (frames per second), ms. GPU — the GPU clock from the first to the last command of the frame; CPU — CPU time of the render thread in the frame (RenderFrameEvent.Pre → Post, without the buffer swap, preemption and driver sleep); frame — from the frame start to the next frame start, as the player sees it. The gate judges GPU p50 and CPU p95 in the `100/*` windows.

## Pack: none

| window | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 | main@fa0ece44 A2 |
|---|---|---|---|
| 100-hit | 16.6 / 20.1 / 28.9 (51) | 16.8 / 20.4 / 28.7 (51) | 16.7 / 21.0 / 31.8 (51) |
| 100/outside | 2.5 / 5.4 / 7.1 (186) | 2.5 / 5.5 / 7.0 (191) | 2.5 / 5.7 / 7.3 (180) |
| 100/edge | 3.8 / 5.2 / 7.3 (194) | 3.6 / 5.1 / 7.1 (200) | 3.8 / 5.3 / 7.4 (191) |
| 100/inside | 3.0 / 4.6 / 7.1 (201) | 3.1 / 4.7 / 7.0 (204) | 3.5 / 4.8 / 7.0 (200) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 |
|---|---|---|
| after_level+ | 2.36 | 2.47 |
| after_solid_blocks | 0.07 | 0.07 |
| after_particles | 0.05 | 0.06 |
| tail | 0.04 | 0.04 |
| after_sky | 0.04 | 0.04 |
| after_entities | 0.01 | 0.01 |
| after_entities+ | 0.00 | 0.00 |
| after_weather | 0.00 | 0.00 |

Bound: cpu; server tick mean 1.3 ms; wall-clock Pre→Post p95 5.5 ms; other load on the machine 0.0 cores, GPU 6 % (nothing heavy); probes: FxLayer.describeTiming: quads 1915, 4.327 ms (longest in the second 4.916; without GC 4.916, CPU 4.870; frames with GC 0), GPU 2.090 ms (longest 2.670, 199 frames; heat pass 0.320, longest 0.338, 199 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 26.8% [r 2.5 h 21 dT 63 px 1.07] [r 2.5 h 21 dT 63 px 1.01] [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.04]; bloom FAST 0.222 ms (longest 0.671, 199 frames; glow pass 0.059, pyramid 0.100, out 0.061; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 199 frames; a pass's tail may spill into the next): models 0.00 depth 0.05 water 0.00 pall 0.00 heat 0.32 probes 0.01 quads 1.53 bloom 0.22 (sum 2.14); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1600 quads, full 799 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.38 screens (budget 16, one club per gaussian 14.38, before merging 33.30), cluster share 0.00, merge level 3, veil gaussians 1, clusters 1712, plan 3.78 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 |
|---|---|---|
| after_level+ | 3.60 | 3.49 |
| after_translucent_blocks | 0.15 | 0.15 |
| after_solid_blocks | 0.08 | 0.08 |
| tail | 0.05 | 0.05 |
| after_sky | 0.04 | 0.04 |
| after_entities | 0.01 | 0.01 |
| after_particles | 0.01 | 0.01 |
| after_weather | 0.00 | 0.01 |

Bound: cpu; server tick mean 1.4 ms; wall-clock Pre→Post p95 5.3 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1143, 4.212 ms (longest in the second 4.916; without GC 4.916, CPU 4.823; frames with GC 0), GPU 3.428 ms (longest 5.989, 198 frames; heat pass 1.429, longest 2.564, 198 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.278 ms (longest 0.579, 198 frames; glow pass 0.097, pyramid 0.111, out 0.068; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 198 frames; a pass's tail may spill into the next): models 0.00 depth 0.06 water 0.01 pall 0.00 heat 1.43 probes 0.04 quads 1.68 bloom 0.28 (sum 3.49); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 592 quads, full 678 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.95 screens (budget 16, one club per gaussian 15.95, before merging 31.84), cluster share 0.00, merge level 3, veil gaussians 11, clusters 739, plan 2.98 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 |
|---|---|---|
| after_level+ | 2.90 | 3.03 |
| after_translucent_blocks | 0.08 | 0.07 |
| after_solid_blocks | 0.06 | 0.07 |
| after_sky | 0.04 | 0.05 |
| tail | 0.04 | 0.04 |
| after_weather | 0.01 | 0.01 |
| after_entities | 0.01 | 0.01 |
| after_particles | 0.01 | 0.00 |

Bound: cpu; server tick mean 1.5 ms; wall-clock Pre→Post p95 4.9 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 530, 4.183 ms (longest in the second 5.767; without GC 5.767, CPU 4.439; frames with GC 0), GPU 2.880 ms (longest 4.852, 204 frames; heat pass 1.227, longest 2.241, 204 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.3 h 19 dT 63 px 1.35] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 204 frames; a pass's tail may spill into the next): models 0.00 depth 0.06 water 0.00 pall 0.00 heat 1.23 probes 0.03 quads 1.62 bloom 0.00 (sum 2.94); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 427 quads, full 103 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 16.00 screens (budget 16, one club per gaussian 16.00, before merging 89.02), cluster share 0.00, merge level 3, veil gaussians 12, clusters 393, plan 4.27 ms off-thread

FLASHCAM@07c0fd3 (B): frames 87534, 3.0 GPU minutes, impacts: 100 100/100 (all, 1 s)

Frames side by side: `sheet-none.png`

## Pack: complementary

| window | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 | main@fa0ece44 A2 |
|---|---|---|---|
| 100-hit | 21.4 / 28.5 / 38.9 (40) | 20.8 / 29.2 / 38.5 (40) | 21.7 / 31.1 / 41.0 (38) |
| 100/outside | 10.7 / 6.8 / 13.1 (91) | 10.6 / 6.9 / 13.1 (92) | 10.8 / 6.9 / 13.2 (90) |
| 100/edge | 12.1 / 6.3 / 14.7 (81) | 12.1 / 6.5 / 14.9 (81) | 12.5 / 6.0 / 15.1 (78) |
| 100/inside | 11.9 / 5.7 / 14.5 (82) | 11.6 / 5.6 / 14.1 (85) | 11.1 / 5.4 / 13.4 (89) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 |
|---|---|---|
| after_level | 3.77 | 3.73 |
| after_level+ | 2.86 | 2.85 |
| after_tripwire_blocks | 1.94 | 1.92 |
| after_entities | 0.89 | 0.87 |
| after_solid_blocks | 0.86 | 0.86 |
| after_sky | 0.24 | 0.23 |
| after_translucent_blocks | 0.10 | 0.10 |
| tail | 0.05 | 0.05 |

Bound: gpu; server tick mean 1.2 ms; wall-clock Pre→Post p95 6.8 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1964, 4.102 ms (longest in the second 4.680; without GC 4.680, CPU 4.649; frames with GC 0), GPU 2.561 ms (longest 3.729, 93 frames; heat pass 0.301, longest 0.601, 93 frames — heat lights 4 hot 4 front 0 jets 0, area 2 rects (2 lit) 17.9% [r 2.5 h 21 dT 63 px 1.07] [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.01] [r 2.3 h 19 dT 63 px 1.00]; bloom FAST 0.295 ms (longest 0.828, 93 frames; glow pass 0.067, pyramid 0.130, out 0.077; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 93 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 0.31 probes 0.04 quads 1.91 bloom 0.30 (sum 2.65); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1613 quads, full 814 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.05 screens (budget 16, one club per gaussian 14.05, before merging 32.25), cluster share 0.00, merge level 3, veil gaussians 0, clusters 1759, plan 3.25 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 |
|---|---|---|
| after_level+ | 3.80 | 3.90 |
| after_level | 3.80 | 3.78 |
| after_tripwire_blocks | 2.03 | 2.02 |
| after_entities | 1.03 | 1.04 |
| after_solid_blocks | 0.92 | 0.91 |
| after_sky | 0.28 | 0.27 |
| after_translucent_blocks | 0.10 | 0.10 |
| after_particles | 0.09 | 0.08 |

Bound: gpu; server tick mean 1.3 ms; wall-clock Pre→Post p95 6.4 ms; other load on the machine 0.0 cores, GPU 14 % (nothing heavy); probes: FxLayer.describeTiming: quads 1076, 4.029 ms (longest in the second 7.264; without GC 4.683, CPU 4.601; frames with GC 1), GPU 3.690 ms (longest 4.605, 82 frames; heat pass 1.407, longest 1.427, 82 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.324 ms (longest 0.621, 82 frames; glow pass 0.099, pyramid 0.125, out 0.078; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 82 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 1.41 probes 0.04 quads 1.90 bloom 0.33 (sum 3.77); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 615 quads, full 576 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.96 screens (budget 16, one club per gaussian 15.96, before merging 29.79), cluster share 0.00, merge level 3, veil gaussians 11, clusters 727, plan 2.75 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 |
|---|---|---|
| after_level+ | 3.72 | 3.49 |
| after_level | 3.63 | 3.62 |
| after_tripwire_blocks | 2.04 | 2.05 |
| after_solid_blocks | 0.91 | 0.92 |
| after_entities | 0.87 | 0.92 |
| after_sky | 0.33 | 0.33 |
| after_particles | 0.33 | 0.09 |
| after_translucent_blocks | 0.10 | 0.10 |

Bound: gpu; server tick mean 1.3 ms; wall-clock Pre→Post p95 5.8 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 526, 4.155 ms (longest in the second 5.192; without GC 5.192, CPU 5.180; frames with GC 0), GPU 3.653 ms (longest 5.210, 83 frames; heat pass 1.678, longest 1.988, 83 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 83 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 1.68 probes 0.04 quads 1.92 bloom 0.00 (sum 3.74); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 425 quads, full 101 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.87 screens (budget 16, one club per gaussian 15.87, before merging 88.81), cluster share 0.00, merge level 3, veil gaussians 39, clusters 388, plan 3.73 ms off-thread

FLASHCAM@07c0fd3 (B): frames 13076, 3.4 GPU minutes, impacts: 100 100/100 (all, 2 s)

Frames side by side: `sheet-complementary.png`

## Pack: photon

| window | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 | main@fa0ece44 A2 |
|---|---|---|---|
| 100-hit | 25.4 / 34.7 / 47.3 (33) | 25.5 / 36.6 / 48.5 (33) | 26.3 / 35.6 / 46.3 (34) |
| 100/outside | 11.3 / 7.3 / 13.7 (87) | 11.2 / 7.4 / 13.6 (87) | 11.3 / 7.3 / 13.7 (87) |
| 100/edge | 12.8 / 6.4 / 15.5 (77) | 12.6 / 6.6 / 15.3 (78) | 13.0 / 6.6 / 15.8 (76) |
| 100/inside | 12.2 / 5.7 / 14.7 (80) | 12.3 / 5.9 / 14.9 (79) | 13.0 / 5.7 / 15.4 (76) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 |
|---|---|---|
| after_tripwire_blocks | 4.26 | 4.23 |
| after_level | 3.52 | 3.51 |
| after_level+ | 2.90 | 2.88 |
| after_entities | 0.16 | 0.16 |
| after_sky | 0.15 | 0.15 |
| after_solid_blocks | 0.14 | 0.14 |
| after_translucent_blocks | 0.10 | 0.10 |
| tail | 0.05 | 0.05 |

Bound: gpu; server tick mean 1.3 ms; wall-clock Pre→Post p95 7.4 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1997, 4.763 ms (longest in the second 6.604; without GC 6.604, CPU 6.577; frames with GC 1), GPU 2.562 ms (longest 3.331, 88 frames; heat pass 0.264, longest 0.274, 88 frames — heat lights 4 hot 4 front 0 jets 0, area 2 rects (2 lit) 14.8% [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.02] [r 2.5 h 21 dT 63 px 1.00] [r 2.5 h 21 dT 63 px 1.09]; bloom FAST 0.290 ms (longest 0.301, 88 frames; glow pass 0.068, pyramid 0.126, out 0.078; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 88 frames; a pass's tail may spill into the next): models 0.00 depth 0.09 water 0.01 pall 0.00 heat 0.27 probes 0.01 quads 1.98 bloom 0.30 (sum 2.66); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1643 quads, full 844 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.69 screens (budget 16, one club per gaussian 14.69, before merging 34.29), cluster share 0.00, merge level 3, veil gaussians 1, clusters 1788, plan 3.67 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 |
|---|---|---|
| after_tripwire_blocks | 4.44 | 4.42 |
| after_level+ | 4.10 | 3.93 |
| after_level | 3.58 | 3.57 |
| after_entities | 0.19 | 0.18 |
| after_solid_blocks | 0.15 | 0.15 |
| after_sky | 0.15 | 0.15 |
| after_translucent_blocks | 0.10 | 0.10 |
| after_particles | 0.07 | 0.07 |

Bound: gpu; server tick mean 1.3 ms; wall-clock Pre→Post p95 6.4 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1057, 3.943 ms (longest in the second 4.606; without GC 4.606, CPU 4.594; frames with GC 0), GPU 3.997 ms (longest 5.284, 77 frames; heat pass 1.675, longest 1.979, 77 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.338 ms (longest 0.871, 77 frames; glow pass 0.099, pyramid 0.135, out 0.079; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 77 frames; a pass's tail may spill into the next): models 0.00 depth 0.10 water 0.01 pall 0.00 heat 1.68 probes 0.06 quads 1.91 bloom 0.35 (sum 4.10); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 587 quads, full 568 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.98 screens (budget 16, one club per gaussian 15.98, before merging 31.35), cluster share 0.00, merge level 3, veil gaussians 10, clusters 743, plan 2.80 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | FLASHCAM@07c0fd3 (B) | main@fa0ece44 A1 |
|---|---|---|
| after_tripwire_blocks | 4.68 | 4.69 |
| after_level | 3.60 | 3.62 |
| after_level+ | 3.35 | 3.42 |
| after_solid_blocks | 0.16 | 0.16 |
| after_entities | 0.15 | 0.15 |
| after_sky | 0.14 | 0.14 |
| after_translucent_blocks | 0.10 | 0.10 |
| tail | 0.05 | 0.05 |

Bound: gpu; server tick mean 1.3 ms; wall-clock Pre→Post p95 5.8 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 530, 3.604 ms (longest in the second 4.182; without GC 4.182, CPU 4.174; frames with GC 0), GPU 3.210 ms (longest 3.757, 80 frames; heat pass 1.285, longest 1.587, 80 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 80 frames; a pass's tail may spill into the next): models 0.00 depth 0.09 water 0.01 pall 0.00 heat 1.29 probes 0.02 quads 1.89 bloom 0.00 (sum 3.30); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 426 quads, full 104 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.99 screens (budget 16, one club per gaussian 15.99, before merging 90.52), cluster share 0.00, merge level 3, veil gaussians 12, clusters 399, plan 3.95 ms off-thread

FLASHCAM@07c0fd3 (B): frames 12324, 3.3 GPU minutes, impacts: 100 100/100 (all, 2 s)

Frames side by side: `sheet-photon.png`
