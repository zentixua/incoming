# Scene stand: MT-B5-490a2aa84 (B)

GPU NVIDIA GeForce RTX 4060 Laptop GPU, driver 615.78.08, window 2560x1600, scenes ['100'], stand 93bda32d0c45, power performance, jar sha256 45f3df524107 (incoming-3.10.4.jar).

## Verdict against 1aca1654a71a@1aca1654: PASS
Each pack is shot as a pair in one session: base A1, candidate B, base A2. The candidate fails a value when it is more than 25% above the worse of A1 and A2; a pack that does not pass is shot by another pair, and the last pair decides.
- frame time at 100 strikes within tolerance

Cells: GPU p50 / CPU p95 / frame p95 (frames per second), ms. GPU — the GPU clock from the first to the last command of the frame; CPU — CPU time of the render thread in the frame (RenderFrameEvent.Pre → Post, without the buffer swap, preemption and driver sleep); frame — from the frame start to the next frame start, as the player sees it. The gate judges GPU p50 and CPU p95 in the `100/*` windows.

## Pack: complementary

| window | MT-B5-490a2aa84 (B) | 1aca1654a71a@1aca1654 A1 | 1aca1654a71a@1aca1654 A2 |
|---|---|---|---|
| 100-hit | 20.4 / 30.0 / 38.5 (40) | 20.4 / 28.9 / 37.6 (41) | 20.3 / 27.9 / 38.4 (42) |
| 100/outside | 10.3 / 7.2 / 12.6 (95) | 10.3 / 7.1 / 12.8 (95) | 10.3 / 7.0 / 12.8 (94) |
| 100/edge | 11.8 / 6.6 / 14.4 (83) | 11.8 / 6.4 / 14.5 (83) | 11.7 / 6.5 / 14.4 (84) |
| 100/inside | 11.0 / 6.7 / 13.8 (89) | 11.3 / 5.9 / 13.7 (87) | 11.0 / 5.7 / 13.3 (89) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-490a2aa84 (B) | 1aca1654a71a@1aca1654 A1 |
|---|---|---|
| after_level | 3.64 | 3.63 |
| after_level+ | 2.72 | 2.76 |
| after_tripwire_blocks | 1.87 | 1.87 |
| after_entities | 0.85 | 0.84 |
| after_solid_blocks | 0.83 | 0.83 |
| after_sky | 0.23 | 0.23 |
| after_translucent_blocks | 0.09 | 0.09 |
| after_particles | 0.05 | 0.04 |

Bound: gpu; server tick mean 6.5 ms; wall-clock Pre→Post p95 7.2 ms; other load on the machine 0.0 cores, GPU 0 % ({'app-com.anthropic.Claude-183212.scope': 0.27, 'incoming-codex-MT-B7.service': 0.12}); probes: FxLayer.describeTiming: quads 1959, 4.753 ms (longest in the second 5.617; without GC 5.617, CPU 5.608; frames with GC 0), GPU 2.424 ms (longest 3.466, 96 frames; heat pass 0.266, longest 0.282, 96 frames — heat lights 4 hot 4 front 0 jets 0, area 2 rects (2 lit) 15.9% [r 2.5 h 21 dT 63 px 1.04] [r 2.5 h 21 dT 63 px 1.09] [r 2.5 h 21 dT 63 px 0.99] [r 2.5 h 21 dT 63 px 1.03]; bloom FAST 0.280 ms (longest 0.573, 96 frames; glow pass 0.063, pyramid 0.121, out 0.075; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 96 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 0.27 probes 0.02 quads 1.85 bloom 0.29 (sum 2.51); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1591 quads, full 823 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.22 screens (budget 16, one club per gaussian 14.22, before merging 32.88), cluster share 0.00, merge level 3, veil gaussians 1, clusters 1719, plan 3.53 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-490a2aa84 (B) | 1aca1654a71a@1aca1654 A1 |
|---|---|---|
| after_level+ | 3.71 | 3.84 |
| after_level | 3.69 | 3.67 |
| after_tripwire_blocks | 1.97 | 1.96 |
| after_entities | 1.02 | 1.01 |
| after_solid_blocks | 0.89 | 0.89 |
| after_sky | 0.27 | 0.27 |
| after_translucent_blocks | 0.10 | 0.10 |
| after_particles | 0.08 | 0.07 |

Bound: gpu; server tick mean 6.6 ms; wall-clock Pre→Post p95 6.6 ms; other load on the machine 0.0 cores, GPU 12 % ({'incoming-codex-MT-B7.service': 0.12}); probes: FxLayer.describeTiming: quads 1124, 4.635 ms (longest in the second 5.395; without GC 5.395, CPU 5.397; frames with GC 0), GPU 3.630 ms (longest 5.149, 83 frames; heat pass 1.414, longest 1.733, 83 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.3 h 19 dT 63 px 1.35]; bloom FAST 0.316 ms (longest 0.611, 83 frames; glow pass 0.097, pyramid 0.122, out 0.075; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 83 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 1.42 probes 0.05 quads 1.82 bloom 0.33 (sum 3.71); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 616 quads, full 610 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.99 screens (budget 16, one club per gaussian 15.99, before merging 30.06), cluster share 0.00, merge level 3, veil gaussians 37, clusters 763, plan 2.61 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | MT-B5-490a2aa84 (B) | 1aca1654a71a@1aca1654 A1 |
|---|---|---|
| after_level | 3.54 | 3.52 |
| after_level+ | 3.20 | 3.48 |
| after_tripwire_blocks | 1.99 | 2.00 |
| after_solid_blocks | 0.89 | 0.89 |
| after_entities | 0.87 | 0.87 |
| after_sky | 0.32 | 0.32 |
| after_translucent_blocks | 0.10 | 0.10 |
| after_particles | 0.08 | 0.05 |

Bound: gpu; server tick mean 7.0 ms; wall-clock Pre→Post p95 6.7 ms; other load on the machine 0.9 cores, GPU 19 % ({'incoming-job-mt-b7-archive-893787.service': 0.99}); probes: FxLayer.describeTiming: quads 504, 4.851 ms (longest in the second 5.661; without GC 5.661, CPU 5.662; frames with GC 0), GPU 3.093 ms (longest 3.829, 89 frames; heat pass 1.218, longest 1.529, 89 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 89 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 1.23 probes 0.05 quads 1.82 bloom 0.00 (sum 3.18); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 404 quads, full 100 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 16.00 screens (budget 16, one club per gaussian 16.00, before merging 89.12), cluster share 0.00, merge level 3, veil gaussians 40, clusters 388, plan 4.65 ms off-thread

MT-B5-490a2aa84 (B): machine load in the verdict windows: window 100/inside: foreign jobs — up to 1.06 cores (allowed 1.0): {'incoming-job-mt-b7-archive-893787.service': 0.99}; window 100/inside: GPU used by foreign processes — 19 % SM in worst cycle (allowed 25), 5.69 % mean (allowed 5)

MT-B5-490a2aa84 (B): frames 13348, 3.3 GPU minutes, impacts: 100 100/100 (all, 2 s)

Frames side by side: `sheet-complementary.png`
