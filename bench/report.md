# Scene stand: PR559 3d3f9387 (B)

GPU NVIDIA GeForce RTX 4060 Laptop GPU, driver 615.78.08, window 2560x1600, scenes ['100'], stand 93bda32d0c45, power performance, jar sha256 445c2f92cc92 (incoming-3.10.4.jar).

## Verdict against main 292f3153: PASS
Each pack is shot as a pair in one session: base A1, candidate B, base A2. The candidate fails a value when it is more than 25% above the worse of A1 and A2; a pack that does not pass is shot by another pair, and the last pair decides.
- frame time at 100 strikes within tolerance

Cells: GPU p50 / CPU p95 / frame p95 (frames per second), ms. GPU — the GPU clock from the first to the last command of the frame; CPU — CPU time of the render thread in the frame (RenderFrameEvent.Pre → Post, without the buffer swap, preemption and driver sleep); frame — from the frame start to the next frame start, as the player sees it. The gate judges GPU p50 and CPU p95 in the `100/*` windows.

## Pack: none

| window | PR559 3d3f9387 (B) | main 292f3153 A1 | main 292f3153 A2 |
|---|---|---|---|
| 100-hit | 16.0 / 19.3 / 28.7 (53) | 15.8 / 19.1 / 35.5 (50) | 16.1 / 19.1 / 34.3 (48) |
| 100/outside | 2.3 / 5.7 / 7.4 (175) | 2.3 / 5.9 / 7.5 (171) | 2.4 / 5.9 / 7.6 (169) |
| 100/edge | 3.6 / 5.3 / 7.3 (192) | 3.6 / 5.7 / 7.4 (179) | 3.3 / 5.7 / 7.8 (175) |
| 100/inside | 4.0 / 4.9 / 7.0 (201) | 3.4 / 5.2 / 7.1 (192) | 2.8 / 5.2 / 7.2 (190) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | PR559 3d3f9387 (B) | main 292f3153 A1 |
|---|---|---|
| after_level+ | 2.21 | 2.18 |
| after_particles | 0.09 | 0.06 |
| after_solid_blocks | 0.07 | 0.07 |
| tail | 0.04 | 0.04 |
| after_sky | 0.04 | 0.04 |
| after_entities | 0.01 | 0.01 |
| after_entities+ | 0.00 | 0.00 |
| after_weather | 0.00 | 0.00 |

Bound: cpu; server tick mean 6.6 ms; wall-clock Pre→Post p95 5.8 ms; other load on the machine 0.0 cores, GPU 0 % ({'app-com.anthropic.Claude-183212.scope': 0.1}); probes: FxLayer.describeTiming: quads 1891, 4.651 ms (longest in the second 5.751; without GC 5.751, CPU 5.754; frames with GC 0), GPU 1.908 ms (longest 2.500, 182 frames; heat pass 0.293, longest 0.321, 182 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 26.5% [r 2.5 h 21 dT 63 px 1.02] [r 2.5 h 21 dT 63 px 1.02] [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.05]; bloom FAST 0.203 ms (longest 0.464, 182 frames; glow pass 0.048, pyramid 0.093, out 0.060; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 182 frames; a pass's tail may spill into the next): models 0.00 depth 0.05 water 0.00 pall 0.00 heat 0.29 probes 0.01 quads 1.40 bloom 0.20 (sum 1.96); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1575 quads, full 788 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.22 screens (budget 16, one club per gaussian 14.22, before merging 32.66), cluster share 0.00, merge level 3, veil gaussians 1, clusters 1696, plan 3.39 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | PR559 3d3f9387 (B) | main 292f3153 A1 |
|---|---|---|
| after_level+ | 3.45 | 3.51 |
| after_translucent_blocks | 0.20 | 0.24 |
| after_solid_blocks | 0.08 | 0.08 |
| tail | 0.04 | 0.05 |
| after_sky | 0.04 | 0.04 |
| after_entities | 0.01 | 0.01 |
| after_particles | 0.01 | 0.01 |
| after_weather | 0.00 | 0.01 |

Bound: cpu; server tick mean 6.8 ms; wall-clock Pre→Post p95 5.3 ms; other load on the machine 0.0 cores, GPU 0 % ({'app-com.anthropic.Claude-183212.scope': 0.11}); probes: FxLayer.describeTiming: quads 1004, 4.180 ms (longest in the second 4.942; without GC 4.942, CPU 4.943; frames with GC 0), GPU 3.417 ms (longest 6.484, 197 frames; heat pass 1.407, longest 2.618, 197 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.277 ms (longest 0.568, 197 frames; glow pass 0.093, pyramid 0.113, out 0.068; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 197 frames; a pass's tail may spill into the next): models 0.00 depth 0.06 water 0.00 pall 0.00 heat 1.41 probes 0.04 quads 1.69 bloom 0.28 (sum 3.48); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 581 quads, full 513 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.97 screens (budget 16, one club per gaussian 15.97, before merging 30.37), cluster share 0.00, merge level 3, veil gaussians 13, clusters 683, plan 2.71 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | PR559 3d3f9387 (B) | main 292f3153 A1 |
|---|---|---|
| after_level+ | 4.01 | 3.51 |
| after_solid_blocks | 0.07 | 0.07 |
| tail | 0.05 | 0.04 |
| after_sky | 0.05 | 0.04 |
| after_translucent_blocks | 0.04 | 0.05 |
| after_weather | 0.01 | 0.01 |
| after_entities | 0.01 | 0.01 |
| after_particles | 0.00 | 0.01 |

Bound: gpu; server tick mean 6.8 ms; wall-clock Pre→Post p95 5.0 ms; other load on the machine 0.0 cores, GPU 0 % ({'app-com.anthropic.Claude-183212.scope': 0.1}); probes: FxLayer.describeTiming: quads 529, 4.346 ms (longest in the second 5.444; without GC 5.444, CPU 4.507; frames with GC 0), GPU 3.695 ms (longest 5.747, 195 frames; heat pass 1.984, longest 2.738, 195 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 195 frames; a pass's tail may spill into the next): models 0.00 depth 0.06 water 0.00 pall 0.00 heat 1.99 probes 0.05 quads 1.65 bloom 0.00 (sum 3.75); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 427 quads, full 102 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.98 screens (budget 16, one club per gaussian 15.98, before merging 88.56), cluster share 0.00, merge level 3, veil gaussians 38, clusters 408, plan 4.00 ms off-thread

main 292f3153 A1: machine load in the verdict windows: window 100/inside: GPU used by foreign processes — 17 % SM in worst cycle (allowed 25), 6.01 % mean (allowed 5)

PR559 3d3f9387 (B): frames 73670, 2.9 GPU minutes, impacts: 100 100/100 (all, 1 s)

Frames side by side: `sheet-none.png`

## Pack: complementary

| window | PR559 3d3f9387 (B) | main 292f3153 A1 | main 292f3153 A2 |
|---|---|---|---|
| 100-hit | 20.1 / 27.2 / 36.7 (42) | 19.7 / 27.7 / 45.7 (39) | 19.9 / 28.0 / 47.4 (39) |
| 100/outside | 10.4 / 7.2 / 12.9 (94) | 10.1 / 7.2 / 12.8 (96) | 10.2 / 7.1 / 12.7 (96) |
| 100/edge | 11.8 / 6.4 / 14.4 (84) | 11.6 / 6.7 / 14.2 (84) | 11.8 / 6.7 / 14.5 (83) |
| 100/inside | 11.5 / 5.9 / 14.1 (85) | 12.4 / 6.1 / 14.8 (79) | 11.2 / 8.8 / 13.9 (88) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | PR559 3d3f9387 (B) | main 292f3153 A1 |
|---|---|---|
| after_level | 3.62 | 3.59 |
| after_level+ | 2.84 | 2.70 |
| after_tripwire_blocks | 1.87 | 1.85 |
| after_entities | 0.84 | 0.84 |
| after_solid_blocks | 0.83 | 0.82 |
| after_sky | 0.23 | 0.23 |
| after_translucent_blocks | 0.10 | 0.09 |
| tail | 0.05 | 0.05 |

Bound: gpu; server tick mean 6.5 ms; wall-clock Pre→Post p95 7.2 ms; other load on the machine 0.0 cores, GPU 0 % ({'app-com.anthropic.Claude-183212.scope': 0.11}); probes: FxLayer.describeTiming: quads 2130, 4.978 ms (longest in the second 5.796; without GC 5.796, CPU 5.798; frames with GC 0), GPU 2.657 ms (longest 3.809, 95 frames; heat pass 0.311, longest 0.599, 95 frames — heat lights 4 hot 4 front 0 jets 0, area 2 rects (2 lit) 19.3% [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.08] [r 2.5 h 21 dT 63 px 1.01]; bloom FAST 0.285 ms (longest 0.583, 95 frames; glow pass 0.070, pyramid 0.120, out 0.074; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 95 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 0.32 probes 0.03 quads 2.02 bloom 0.29 (sum 2.74); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1656 quads, full 949 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.86 screens (budget 16, one club per gaussian 14.86, before merging 33.97), cluster share 0.00, merge level 3, veil gaussians 10, clusters 1834, plan 3.66 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | PR559 3d3f9387 (B) | main 292f3153 A1 |
|---|---|---|
| after_level+ | 3.72 | 3.77 |
| after_level | 3.67 | 3.61 |
| after_tripwire_blocks | 1.96 | 1.94 |
| after_entities | 1.03 | 0.97 |
| after_solid_blocks | 0.88 | 0.87 |
| after_sky | 0.27 | 0.27 |
| after_translucent_blocks | 0.10 | 0.10 |
| after_particles | 0.09 | 0.07 |

Bound: gpu; server tick mean 6.5 ms; wall-clock Pre→Post p95 6.4 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1003, 4.314 ms (longest in the second 5.348; without GC 5.348, CPU 5.350; frames with GC 0), GPU 3.622 ms (longest 4.479, 83 frames; heat pass 1.420, longest 1.492, 83 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.309 ms (longest 0.331, 83 frames; glow pass 0.096, pyramid 0.121, out 0.075; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 83 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 1.43 probes 0.05 quads 1.82 bloom 0.32 (sum 3.71); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 561 quads, full 551 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.99 screens (budget 16, one club per gaussian 15.99, before merging 30.88), cluster share 0.00, merge level 3, veil gaussians 15, clusters 670, plan 2.79 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | PR559 3d3f9387 (B) | main 292f3153 A1 |
|---|---|---|
| after_level+ | 3.71 | 4.63 |
| after_level | 3.49 | 3.48 |
| after_tripwire_blocks | 1.99 | 2.02 |
| after_entities | 0.94 | 0.89 |
| after_solid_blocks | 0.88 | 0.88 |
| after_sky | 0.32 | 0.32 |
| after_translucent_blocks | 0.10 | 0.09 |
| tail | 0.05 | 0.05 |

Bound: gpu; server tick mean 6.6 ms; wall-clock Pre→Post p95 5.9 ms; other load on the machine 0.0 cores, GPU 0 % ({'app-com.anthropic.Claude-183212.scope': 0.12}); probes: FxLayer.describeTiming: quads 515, 3.972 ms (longest in the second 5.209; without GC 5.209, CPU 5.211; frames with GC 0), GPU 3.671 ms (longest 5.500, 85 frames; heat pass 1.743, longest 2.005, 85 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 85 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 1.75 probes 0.06 quads 1.86 bloom 0.00 (sum 3.75); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 417 quads, full 98 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.99 screens (budget 16, one club per gaussian 15.99, before merging 90.85), cluster share 0.00, merge level 3, veil gaussians 11, clusters 389, plan 3.76 ms off-thread

main 292f3153 A2: machine load in the verdict windows: window 100/inside: foreign jobs — up to 3.61 cores (allowed 1.0): {'incoming-job-mt-b2-material-compile2-462685.service': 3.79, 'app-com.anthropic.Claude-183212.scope': 0.1}

PR559 3d3f9387 (B): frames 13176, 3.3 GPU minutes, impacts: 100 100/100 (all, 2 s)

Frames side by side: `sheet-complementary.png`

## Pack: photon

| window | PR559 3d3f9387 (B) | main 292f3153 A1 | main 292f3153 A2 |
|---|---|---|---|
| 100-hit | 22.7 / 33.8 / 44.8 (36) | 31.5 / 53.5 / 99.8 (20) | 23.8 / 35.9 / 59.2 (33) |
| 100/outside | 11.0 / 11.8 / 15.1 (87) | 10.6 / 7.9 / 13.2 (92) | 11.0 / 11.9 / 14.9 (87) |
| 100/edge | 12.4 / 6.8 / 15.3 (79) | 12.2 / 9.6 / 15.7 (80) | 11.9 / 8.2 / 14.8 (83) |
| 100/inside | 12.1 / 6.0 / 14.7 (81) | 11.9 / 11.1 / 16.0 (82) | 12.2 / 6.6 / 14.6 (81) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | PR559 3d3f9387 (B) | main 292f3153 A1 |
|---|---|---|
| after_tripwire_blocks | 4.12 | 4.04 |
| after_level | 3.43 | 3.36 |
| after_level+ | 2.80 | 2.71 |
| after_entities | 0.16 | 0.16 |
| after_solid_blocks | 0.14 | 0.14 |
| after_sky | 0.14 | 0.14 |
| after_translucent_blocks | 0.10 | 0.09 |
| after_particles | 0.05 | 0.03 |

Bound: gpu; server tick mean 7.8 ms; wall-clock Pre→Post p95 11.9 ms; other load on the machine 4.6 cores, GPU 0 % ({'incoming-job-mt-b2-material5-471682.service': 6.32, 'app-com.anthropic.Claude-183212.scope': 0.12}); probes: FxLayer.describeTiming: quads 1998, 6.178 ms (longest in the second 8.980; without GC 8.980, CPU 8.984; frames with GC 0), GPU 2.560 ms (longest 3.604, 89 frames; heat pass 0.278, longest 0.288, 89 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 16.9% [r 2.5 h 21 dT 63 px 1.04] [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.01] [r 2.5 h 21 dT 63 px 1.08]; bloom FAST 0.287 ms (longest 0.582, 89 frames; glow pass 0.067, pyramid 0.125, out 0.075; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 89 frames; a pass's tail may spill into the next): models 0.00 depth 0.09 water 0.01 pall 0.00 heat 0.28 probes 0.02 quads 1.96 bloom 0.30 (sum 2.66); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1608 quads, full 1139 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.22 screens (budget 16, one club per gaussian 14.22, before merging 32.47), cluster share 0.00, merge level 3, veil gaussians 0, clusters 1771, plan 3.61 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | PR559 3d3f9387 (B) | main 292f3153 A1 |
|---|---|---|
| after_tripwire_blocks | 4.32 | 4.25 |
| after_level+ | 3.96 | 3.86 |
| after_level | 3.49 | 3.47 |
| after_entities | 0.18 | 0.17 |
| after_solid_blocks | 0.15 | 0.15 |
| after_sky | 0.15 | 0.14 |
| after_translucent_blocks | 0.10 | 0.10 |
| after_particles | 0.05 | 0.06 |

Bound: gpu; server tick mean 6.6 ms; wall-clock Pre→Post p95 6.8 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1274, 4.480 ms (longest in the second 5.319; without GC 5.319, CPU 5.321; frames with GC 0), GPU 3.883 ms (longest 5.410, 79 frames; heat pass 1.620, longest 1.645, 79 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.315 ms (longest 0.338, 79 frames; glow pass 0.098, pyramid 0.123, out 0.076; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 79 frames; a pass's tail may spill into the next): models 0.00 depth 0.09 water 0.01 pall 0.00 heat 1.63 probes 0.06 quads 1.88 bloom 0.32 (sum 3.98); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 604 quads, full 766 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.92 screens (budget 16, one club per gaussian 15.92, before merging 32.26), cluster share 0.00, merge level 3, veil gaussians 13, clusters 774, plan 2.83 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | PR559 3d3f9387 (B) | main 292f3153 A1 |
|---|---|---|
| after_tripwire_blocks | 4.54 | 4.51 |
| after_level | 3.53 | 3.50 |
| after_level+ | 3.42 | 3.28 |
| after_solid_blocks | 0.16 | 0.16 |
| after_entities | 0.15 | 0.14 |
| after_sky | 0.14 | 0.14 |
| after_translucent_blocks | 0.10 | 0.10 |
| after_particles | 0.05 | 0.03 |

Bound: gpu; server tick mean 6.6 ms; wall-clock Pre→Post p95 6.0 ms; other load on the machine 0.0 cores, GPU 0 % ({'app-com.anthropic.Claude-183212.scope': 0.1}); probes: FxLayer.describeTiming: quads 558, 4.057 ms (longest in the second 4.787; without GC 4.787, CPU 4.787; frames with GC 0), GPU 3.372 ms (longest 4.444, 80 frames; heat pass 1.453, longest 1.507, 80 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.3 h 19 dT 63 px 1.35]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 80 frames; a pass's tail may spill into the next): models 0.00 depth 0.09 water 0.01 pall 0.00 heat 1.46 probes 0.06 quads 1.85 bloom 0.00 (sum 3.47); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 451 quads, full 107 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.96 screens (budget 16, one club per gaussian 15.96, before merging 89.96), cluster share 0.00, merge level 3, veil gaussians 11, clusters 422, plan 3.95 ms off-thread

PR559 3d3f9387 (B): machine load in the verdict windows: window 100/outside: foreign jobs — up to 6.28 cores (allowed 1.0): {'incoming-job-mt-b2-material5-471682.service': 6.32, 'app-com.anthropic.Claude-183212.scope': 0.12}

main 292f3153 A1: machine load in the verdict windows: window 100/outside: foreign jobs — up to 4.42 cores (allowed 1.0): {'incoming-job-mt-b2-material-pile2-466710.service': 4.52, 'app-com.anthropic.Claude-183212.scope': 0.11}; window 100/edge: foreign jobs — up to 3.59 cores (allowed 1.0): {'incoming-job-mt-b2-material-pile2-466710.service': 3.59, 'app-com.anthropic.Claude-183212.scope': 0.26}; window 100/inside: foreign jobs — up to 6.13 cores (allowed 1.0): {'incoming-job-mt-b2-material3-467908.service': 6.17, 'app-com.anthropic.Claude-183212.scope': 0.15}

main 292f3153 A2: machine load in the verdict windows: window 100/outside: foreign jobs — up to 6.61 cores (allowed 1.0): {'incoming-job-mt-b2-material-regressions-475982.service': 6.65, 'app-com.anthropic.Claude-183212.scope': 0.12}; window 100/edge: foreign jobs — up to 2.75 cores (allowed 1.0): {'incoming-job-mt-b2-material-regressions-475982.service': 2.8}

PR559 3d3f9387 (B): frames 12851, 3.2 GPU minutes, impacts: 100 100/100 (all, 2 s)

Frames side by side: `sheet-photon.png`
