# Scene stand: f67045b66@f67045b6 (B)

GPU NVIDIA GeForce RTX 4060 Laptop GPU, driver 620.70.06, window 2560x1600, scenes ['100'], stand d410cda45bc3, power low-power, jar sha256 5c80d6bc385d (incoming-3.13.0.jar).

## Verdict against main@e2a35026: PASS
Each pack is shot as a pair in one session: base A1, candidate B, base A2. The candidate fails a value when it is more than 25% above the worse of A1 and A2; a pack that does not pass is shot by another pair, and the last pair decides.
- frame time at 100 strikes within tolerance

Cells: GPU p50 / CPU p95 / frame p95 (frames per second), ms. GPU — the GPU clock from the first to the last command of the frame; CPU — CPU time of the render thread in the frame (RenderFrameEvent.Pre → Post, without the buffer swap, preemption and driver sleep); frame — from the frame start to the next frame start, as the player sees it. The gate judges GPU p50 and CPU p95 in the `100/*` windows.

## Pack: none

| window | f67045b66@f67045b6 (B) | main@e2a35026 A1 | main@e2a35026 A2 |
|---|---|---|---|
| 100-hit | 19.6 / 23.6 / 35.8 (43) | 18.0 / 22.0 / 32.9 (47) | 20.0 / 23.8 / 35.6 (42) |
| 100/outside | 3.8 / 6.0 / 7.5 (174) | 3.7 / 5.8 / 7.6 (177) | 3.8 / 6.2 / 7.9 (166) |
| 100/edge | 4.7 / 5.4 / 7.4 (187) | 4.7 / 5.5 / 7.7 (184) | 4.7 / 5.9 / 7.7 (180) |
| 100/inside | 5.1 / 5.3 / 7.6 (184) | 4.0 / 5.0 / 7.3 (191) | 4.3 / 5.2 / 7.5 (189) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | f67045b66@f67045b6 (B) | main@e2a35026 A1 |
|---|---|---|
| after_level+ | 3.51 | 3.45 |
| after_solid_blocks | 0.10 | 0.10 |
| tail | 0.06 | 0.06 |
| after_sky | 0.05 | 0.05 |
| after_particles | 0.03 | 0.12 |
| after_entities | 0.01 | 0.01 |
| after_weather | 0.00 | 0.00 |
| after_translucent_blocks | 0.00 | 0.00 |

Bound: cpu; server tick mean 1.4 ms; wall-clock Pre→Post p95 6.0 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1847, 4.707 ms (longest in the second 5.492; without GC 5.492, CPU 5.475; frames with GC 0), GPU 3.269 ms (longest 3.678, 183 frames; heat pass 0.517, longest 0.522, 183 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 29.2% [r 2.5 h 21 dT 63 px 1.08] [r 2.5 h 21 dT 63 px 1.05] [r 2.5 h 21 dT 63 px 1.00] [r 2.5 h 21 dT 63 px 1.03]; bloom FAST 0.330 ms (longest 0.681, 183 frames; glow pass 0.088, pyramid 0.147, out 0.091; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 183 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.00 pall 0.00 heat 0.52 probes 0.01 quads 2.41 bloom 0.33 (sum 3.35); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1519 quads, full 779 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 14.26 screens (budget 16, one club per gaussian 14.26, before merging 33.05), cluster share 0.00, merge level 3, veil gaussians 0, clusters 1653, plan 3.63 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | f67045b66@f67045b6 (B) | main@e2a35026 A1 |
|---|---|---|
| after_level+ | 4.54 | 4.54 |
| after_solid_blocks | 0.10 | 0.10 |
| tail | 0.06 | 0.06 |
| after_translucent_blocks | 0.05 | 0.07 |
| after_sky | 0.05 | 0.05 |
| after_entities | 0.01 | 0.01 |
| after_weather | 0.01 | 0.01 |
| after_particles | 0.00 | 0.01 |

Bound: gpu; server tick mean 1.5 ms; wall-clock Pre→Post p95 5.5 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 1040, 4.344 ms (longest in the second 5.266; without GC 5.266, CPU 5.240; frames with GC 0), GPU 4.453 ms (longest 4.718, 191 frames; heat pass 1.800, longest 2.133, 192 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.371 ms (longest 0.707, 191 frames; glow pass 0.115, pyramid 0.148, out 0.093; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 192 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 1.80 probes 0.04 quads 2.22 bloom 0.38 (sum 4.54); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 595 quads, full 550 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.95 screens (budget 16, one club per gaussian 15.95, before merging 31.70), cluster share 0.00, merge level 3, veil gaussians 11, clusters 700, plan 3.09 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | f67045b66@f67045b6 (B) | main@e2a35026 A1 |
|---|---|---|
| after_level+ | 4.91 | 3.84 |
| after_solid_blocks | 0.09 | 0.08 |
| tail | 0.06 | 0.06 |
| after_sky | 0.05 | 0.06 |
| after_translucent_blocks | 0.02 | 0.04 |
| after_weather | 0.01 | 0.01 |
| after_entities | 0.01 | 0.01 |
| after_particles | 0.01 | 0.01 |

Bound: gpu; server tick mean 1.5 ms; wall-clock Pre→Post p95 5.4 ms; other load on the machine 0.0 cores, GPU 0 % (nothing heavy); probes: FxLayer.describeTiming: quads 522, 4.821 ms (longest in the second 6.271; without GC 6.271, CPU 4.734; frames with GC 1), GPU 4.806 ms (longest 5.068, 177 frames; heat pass 2.566, longest 2.576, 178 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.3 h 19 dT 63 px 1.35] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 177 frames; a pass's tail may spill into the next): models 0.00 depth 0.08 water 0.01 pall 0.00 heat 2.57 probes 0.06 quads 2.18 bloom 0.00 (sum 4.89); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 420 quads, full 102 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.99 screens (budget 16, one club per gaussian 15.99, before merging 91.46), cluster share 0.00, merge level 3, veil gaussians 37, clusters 382, plan 4.40 ms off-thread

main@e2a35026 A1: machine load in the verdict windows: window 100/edge: foreign jobs — up to 1.13 cores (allowed 1.0): {}

f67045b66@f67045b6 (B): frames 73283, 3.1 GPU minutes, impacts: 100 100/100 (all, 1 s)

Frames side by side: `sheet-none.png`
