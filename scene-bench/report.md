# Scene stand: MT-B5-dba2d153d (B)

GPU NVIDIA GeForce RTX 4060 Laptop GPU, driver 615.78.08, window 2560x1600, scenes ['100'], stand 93bda32d0c45, power performance, jar sha256 2f5d06077afb (incoming-3.10.4.jar).

## Verdict against ab2741fa11d5@ab2741fa: FAIL
Each pack is shot as a pair in one session: base A1, candidate B, base A2. The candidate fails a value when it is more than 25% above the worse of A1 and A2; a pack that does not pass is shot by another pair, and the last pair decides.
- complementary 100/inside thread.p95: 9.66 ms versus 5.89 for worst accepted (+64%, tolerance 25%)

Cells: GPU p50 / CPU p95 / frame p95 (frames per second), ms. GPU — the GPU clock from the first to the last command of the frame; CPU — CPU time of the render thread in the frame (RenderFrameEvent.Pre → Post, without the buffer swap, preemption and driver sleep); frame — from the frame start to the next frame start, as the player sees it. The gate judges GPU p50 and CPU p95 in the `100/*` windows.

## Pack: none

| window | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 | ab2741fa11d5@ab2741fa A2 |
|---|---|---|---|
| 100-hit | 16.3 / 19.6 / 28.1 (51) | 15.0 / 17.3 / 25.4 (58) | 16.9 / 21.7 / 32.8 (48) |
| 100/outside | 2.3 / 5.9 / 7.7 (170) | 2.3 / 5.7 / 7.1 (179) | 2.3 / 6.3 / 8.2 (159) |
| 100/edge | 3.4 / 5.2 / 7.3 (190) | 3.5 / 5.2 / 7.1 (193) | 3.1 / 8.9 / 11.0 (131) |
| 100/inside | 3.2 / 4.9 / 6.9 (205) | 3.8 / 6.2 / 7.3 (191) | 3.1 / 5.8 / 7.8 (178) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 |
|---|---|---|
| after_level+ | 2.17 | 2.18 |
| after_particles | 0.16 | 0.03 |
| after_solid_blocks | 0.07 | 0.07 |
| tail | 0.04 | 0.04 |
| after_sky | 0.04 | 0.04 |
| after_entities | 0.01 | 0.01 |
| after_weather | 0.00 | 0.00 |
| after_entities+ | 0.00 | 0.00 |

Bound: cpu; server tick mean 6.8 ms; wall-clock Pre→Post p95 6.0 ms; other load on the machine 0.0 cores, GPU 7 % ({'app-com.anthropic.Claude-183212.scope': 0.11}); probes: FxLayer.describeTiming: quads 2003, 4.733 ms (longest in the second 5.698; without GC 5.698, CPU 5.681; frames with GC 0), GPU 2.011 ms (longest 2.807, 179 frames; heat pass 0.287, longest 0.314, 179 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 25.6% [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.08] [r 2.5 h 21 dT 63 px 1.04] [r 2.5 h 21 dT 63 px 1.01]; bloom FAST 0.217 ms (longest 0.878, 179 frames; glow pass 0.056, pyramid 0.099, out 0.060; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 179 frames; a pass's tail may spill into the next): models 0.00 depth 0.05 water 0.00 pall 0.00 heat 0.29 probes 0.01 quads 1.49 bloom 0.22 (sum 2.06); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1547 quads, full 934 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.48 screens (budget 16, one club per gaussian 14.48, before merging 33.39), cluster share 0.00, merge level 3, veil gaussians 0, clusters 1706, plan 3.56 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 |
|---|---|---|
| after_level+ | 3.36 | 3.55 |
| after_translucent_blocks | 0.21 | 0.14 |
| after_solid_blocks | 0.08 | 0.08 |
| tail | 0.04 | 0.05 |
| after_sky | 0.04 | 0.04 |
| after_entities | 0.01 | 0.01 |
| after_particles | 0.01 | 0.00 |
| after_weather | 0.00 | 0.01 |

Bound: cpu; server tick mean 6.9 ms; wall-clock Pre→Post p95 5.3 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 922, 4.158 ms (longest in the second 4.900; without GC 4.900, CPU 4.575; frames with GC 0), GPU 3.380 ms (longest 6.038, 195 frames; heat pass 1.424, longest 2.622, 196 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.3 h 19 dT 63 px 1.35] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.267 ms (longest 0.548, 196 frames; glow pass 0.087, pyramid 0.109, out 0.068; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 195 frames; a pass's tail may spill into the next): models 0.00 depth 0.06 water 0.01 pall 0.00 heat 1.42 probes 0.04 quads 1.64 bloom 0.27 (sum 3.45); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 548 quads, full 463 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 16.00 screens (budget 16, one club per gaussian 16.00, before merging 33.01), cluster share 0.00, merge level 3, veil gaussians 12, clusters 648, plan 2.78 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 |
|---|---|---|
| after_level+ | 3.19 | 3.73 |
| after_solid_blocks | 0.07 | 0.07 |
| after_translucent_blocks | 0.06 | 0.05 |
| after_sky | 0.04 | 0.05 |
| tail | 0.04 | 0.05 |
| after_weather | 0.01 | 0.01 |
| after_entities | 0.01 | 0.01 |
| after_particles | 0.01 | 0.01 |

Bound: cpu; server tick mean 6.9 ms; wall-clock Pre→Post p95 4.9 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 473, 3.973 ms (longest in the second 4.654; without GC 4.654, CPU 4.657; frames with GC 0), GPU 2.949 ms (longest 5.300, 212 frames; heat pass 1.345, longest 2.577, 212 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 212 frames; a pass's tail may spill into the next): models 0.00 depth 0.06 water 0.00 pall 0.00 heat 1.35 probes 0.03 quads 1.57 bloom 0.00 (sum 3.01); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 376 quads, full 97 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.99 screens (budget 16, one club per gaussian 15.99, before merging 91.19), cluster share 0.00, merge level 3, veil gaussians 11, clusters 347, plan 4.04 ms off-thread

ab2741fa11d5@ab2741fa A1: machine load in the verdict windows: window 100/inside: foreign jobs — up to 5.06 cores (allowed 1.0): {'incoming-job-mt-b7-observer-build-795092.service': 5.2}

ab2741fa11d5@ab2741fa A2: machine load in the verdict windows: window 100/outside: foreign jobs — up to 1.28 cores (allowed 1.0): {'incoming-job-mt-b5-cache-fix-800533.service': 1.12, 'incoming-codex-MT-B7.service': 0.28, 'app-com.anthropic.Claude-183212.scope': 0.26}; window 100/edge: foreign jobs — up to 7.64 cores (allowed 1.0): {'incoming-job-mt-b5-pipeline-accounted-802745.service': 6.18, 'incoming-job-mt-b5-cache-fix-800533.service': 1.06, 'incoming-codex-MT-B7.service': 0.26, 'app-com.anthropic.Claude-183212.scope': 0.24}; window 100/inside: foreign jobs — up to 3.13 cores (allowed 1.0): {'incoming-job-mt-b5-pipeline-accounted-802745.service': 2.15, 'incoming-job-mt-b5-cache-fix-800533.service': 1.04, 'app-com.anthropic.Claude-183212.scope': 0.13}

MT-B5-dba2d153d (B): frames 73609, 2.9 GPU minutes, impacts: 100 100/100 (all, 1 s)

Frames side by side: `sheet-none.png`

## Pack: complementary

| window | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 | ab2741fa11d5@ab2741fa A2 |
|---|---|---|---|
| 100-hit | 20.2 / 28.6 / 38.3 (42) | 19.6 / 27.1 / 36.2 (43) | 20.6 / 30.4 / 41.3 (40) |
| 100/outside | 10.1 / 7.1 / 13.1 (95) | 10.0 / 6.9 / 14.0 (94) | 10.2 / 7.1 / 12.6 (95) |
| 100/edge | 11.7 / 6.5 / 14.3 (84) | 11.2 / 6.2 / 14.4 (86) | 11.6 / 6.5 / 14.2 (85) |
| 100/inside | 11.1 / 9.7 / 14.2 (89) | 10.4 / 5.9 / 12.9 (94) | 10.8 / 5.8 / 13.3 (90) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 |
|---|---|---|
| after_level | 3.63 | 3.66 |
| after_level+ | 2.74 | 2.85 |
| after_tripwire_blocks | 1.87 | 1.87 |
| after_entities | 0.85 | 0.85 |
| after_solid_blocks | 0.82 | 0.84 |
| after_sky | 0.23 | 0.23 |
| after_translucent_blocks | 0.10 | 0.09 |
| tail | 0.05 | 0.05 |

Bound: gpu; server tick mean 6.5 ms; wall-clock Pre→Post p95 7.1 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 2119, 4.890 ms (longest in the second 8.420; without GC 5.750, CPU 5.728; frames with GC 1), GPU 2.440 ms (longest 3.183, 97 frames; heat pass 0.284, longest 0.553, 97 frames — heat lights 4 hot 4 front 0 jets 0, area 2 rects (2 lit) 17.8% [r 2.5 h 21 dT 63 px 1.01] [r 2.5 h 21 dT 63 px 1.06] [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.02]; bloom FAST 0.277 ms (longest 0.588, 97 frames; glow pass 0.066, pyramid 0.120, out 0.073; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 97 frames; a pass's tail may spill into the next): models 0.00 depth 0.07 water 0.01 pall 0.00 heat 0.29 probes 0.02 quads 1.83 bloom 0.29 (sum 2.52); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1632 quads, full 965 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.32 screens (budget 16, one club per gaussian 14.32, before merging 32.84), cluster share 0.00, merge level 3, veil gaussians 0, clusters 1819, plan 3.31 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 |
|---|---|---|
| after_level+ | 3.80 | 3.59 |
| after_level | 3.66 | 3.59 |
| after_tripwire_blocks | 1.94 | 1.90 |
| after_entities | 0.99 | 0.99 |
| after_solid_blocks | 0.88 | 0.86 |
| after_sky | 0.27 | 0.26 |
| after_translucent_blocks | 0.10 | 0.09 |
| after_particles | 0.07 | 0.09 |

Bound: gpu; server tick mean 6.6 ms; wall-clock Pre→Post p95 6.5 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1078, 4.390 ms (longest in the second 5.525; without GC 5.525, CPU 5.526; frames with GC 0), GPU 3.750 ms (longest 4.632, 83 frames; heat pass 1.543, longest 1.865, 83 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.320 ms (longest 0.608, 83 frames; glow pass 0.094, pyramid 0.127, out 0.075; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 83 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 1.55 probes 0.05 quads 1.82 bloom 0.33 (sum 3.83); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 560 quads, full 609 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.96 screens (budget 16, one club per gaussian 15.96, before merging 31.17), cluster share 0.00, merge level 3, veil gaussians 12, clusters 708, plan 2.63 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 |
|---|---|---|
| after_level | 3.48 | 3.40 |
| after_level+ | 3.42 | 3.03 |
| after_tripwire_blocks | 1.98 | 1.91 |
| after_solid_blocks | 0.88 | 0.86 |
| after_entities | 0.82 | 0.78 |
| after_sky | 0.31 | 0.31 |
| after_translucent_blocks | 0.10 | 0.09 |
| after_particles | 0.06 | 0.06 |

Bound: gpu; server tick mean 7.3 ms; wall-clock Pre→Post p95 9.7 ms; other load on the machine 3.2 cores, GPU 0 % ({'incoming-job-mt-b5-merge-memory-821839.service': 7.13, 'app-com.anthropic.Claude-183212.scope': 0.11}); probes: FxLayer.describeTiming: quads 558, 5.463 ms (longest in the second 7.830; without GC 7.830, CPU 7.835; frames with GC 0), GPU 3.369 ms (longest 4.404, 88 frames; heat pass 1.477, longest 1.791, 89 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.3 h 19 dT 63 px 1.35] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 88 frames; a pass's tail may spill into the next): models 0.00 depth 0.07 water 0.01 pall 0.00 heat 1.48 probes 0.03 quads 1.85 bloom 0.00 (sum 3.45); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 450 quads, full 108 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.98 screens (budget 16, one club per gaussian 15.98, before merging 89.41), cluster share 0.00, merge level 3, veil gaussians 10, clusters 437, plan 4.82 ms off-thread

MT-B5-dba2d153d (B): machine load in the verdict windows: window 100/inside: foreign jobs — up to 7.1 cores (allowed 1.0): {'incoming-job-mt-b5-merge-memory-821839.service': 7.13, 'app-com.anthropic.Claude-183212.scope': 0.11}

MT-B5-dba2d153d (B): frames 13353, 3.3 GPU minutes, impacts: 100 100/100 (all, 2 s)

Frames side by side: `sheet-complementary.png`

## Pack: photon

| window | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 | ab2741fa11d5@ab2741fa A2 |
|---|---|---|---|
| 100-hit | 22.4 / 33.7 / 44.0 (37) | 22.1 / 32.5 / 43.9 (38) | 23.5 / 34.6 / 46.3 (37) |
| 100/outside | 11.0 / 7.3 / 13.4 (89) | 10.8 / 7.1 / 13.2 (91) | 11.0 / 7.2 / 13.4 (89) |
| 100/edge | 12.2 / 6.7 / 15.0 (80) | 12.2 / 7.2 / 15.1 (80) | 12.2 / 6.8 / 15.0 (81) |
| 100/inside | 14.1 / 6.2 / 16.8 (70) | 13.2 / 6.2 / 15.9 (75) | 13.2 / 6.1 / 16.0 (74) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 |
|---|---|---|
| after_tripwire_blocks | 4.12 | 4.06 |
| after_level | 3.41 | 3.37 |
| after_level+ | 2.86 | 2.82 |
| after_entities | 0.16 | 0.16 |
| after_solid_blocks | 0.14 | 0.14 |
| after_sky | 0.14 | 0.14 |
| after_translucent_blocks | 0.10 | 0.09 |
| tail | 0.05 | 0.05 |

Bound: gpu; server tick mean 6.6 ms; wall-clock Pre→Post p95 7.3 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1972, 5.033 ms (longest in the second 6.093; without GC 6.093, CPU 6.094; frames with GC 0), GPU 2.649 ms (longest 2.942, 90 frames; heat pass 0.295, longest 0.299, 90 frames — heat lights 4 hot 4 front 0 jets 0, area 2 rects (2 lit) 18.0% [r 2.5 h 21 dT 63 px 1.08] [r 2.5 h 21 dT 63 px 1.02] [r 2.5 h 21 dT 63 px 1.02] [r 2.5 h 21 dT 63 px 1.03]; bloom FAST 0.288 ms (longest 0.582, 90 frames; glow pass 0.070, pyramid 0.121, out 0.075; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 90 frames; a pass's tail may spill into the next): models 0.00 depth 0.09 water 0.01 pall 0.00 heat 0.30 probes 0.02 quads 2.04 bloom 0.29 (sum 2.75); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1651 quads, full 807 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.68 screens (budget 16, one club per gaussian 14.68, before merging 33.69), cluster share 0.00, merge level 3, veil gaussians 0, clusters 1771, plan 3.45 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 |
|---|---|---|
| after_tripwire_blocks | 4.31 | 4.26 |
| after_level+ | 3.79 | 3.87 |
| after_level | 3.47 | 3.45 |
| after_entities | 0.17 | 0.17 |
| after_solid_blocks | 0.15 | 0.15 |
| after_sky | 0.14 | 0.14 |
| after_translucent_blocks | 0.10 | 0.10 |
| after_particles | 0.06 | 0.05 |

Bound: gpu; server tick mean 6.6 ms; wall-clock Pre→Post p95 6.7 ms; other load on the machine 0.0 cores, GPU 0 % ({'app-com.anthropic.Claude-183212.scope': 0.1}); probes: FxLayer.describeTiming: quads 1245, 4.356 ms (longest in the second 5.382; without GC 5.382, CPU 5.383; frames with GC 0), GPU 3.719 ms (longest 4.926, 80 frames; heat pass 1.469, longest 1.751, 80 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.3 h 19 dT 63 px 1.35] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.324 ms (longest 0.638, 80 frames; glow pass 0.099, pyramid 0.127, out 0.076; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 80 frames; a pass's tail may spill into the next): models 0.00 depth 0.10 water 0.01 pall 0.00 heat 1.49 probes 0.03 quads 1.87 bloom 0.33 (sum 3.82); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 647 quads, full 697 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.98 screens (budget 16, one club per gaussian 15.98, before merging 30.18), cluster share 0.00, merge level 3, veil gaussians 39, clusters 843, plan 2.80 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-dba2d153d (B) | ab2741fa11d5@ab2741fa A1 |
|---|---|---|
| after_level+ | 5.43 | 4.56 |
| after_tripwire_blocks | 4.57 | 4.53 |
| after_level | 3.50 | 3.49 |
| after_solid_blocks | 0.15 | 0.15 |
| after_entities | 0.15 | 0.16 |
| after_sky | 0.14 | 0.14 |
| after_translucent_blocks | 0.10 | 0.10 |
| after_particles | 0.06 | 0.04 |

Bound: gpu; server tick mean 6.5 ms; wall-clock Pre→Post p95 6.2 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 535, 4.048 ms (longest in the second 4.748; without GC 4.748, CPU 4.749; frames with GC 0), GPU 5.343 ms (longest 6.315, 69 frames; heat pass 3.407, longest 3.442, 69 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.3 h 19 dT 63 px 1.35]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 69 frames; a pass's tail may spill into the next): models 0.00 depth 0.10 water 0.01 pall 0.00 heat 3.41 probes 0.09 quads 1.84 bloom 0.00 (sum 5.44); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 434 quads, full 101 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 16.00 screens (budget 16, one club per gaussian 16.00, before merging 91.59), cluster share 0.00, merge level 3, veil gaussians 13, clusters 407, plan 3.83 ms off-thread

MT-B5-dba2d153d (B): frames 12294, 3.1 GPU minutes, impacts: 100 100/100 (all, 2 s)

Frames side by side: `sheet-photon.png`
