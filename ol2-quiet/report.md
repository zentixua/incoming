# Scene stand: 3.10.4 (B)

GPU NVIDIA GeForce RTX 4060 Laptop GPU, driver 615.78.08, window 2560x1600, scenes ['100'], stand 93bda32d0c45, power performance, jar sha256 cec5517a996d (incoming-3.10.4.jar).

## Verdict against 1aca1654a@1aca1654: FAIL
Each pack is shot as a pair in one session: base A1, candidate B, base A2. The candidate fails a value when it is more than 25% above the worse of A1 and A2; a pack that does not pass is shot by another pair, and the last pair decides.
- none 100/edge gpu.p50: 5.35 ms versus 4.02 for worst accepted (+33%, tolerance 25%)
- none 100/inside gpu.p50: 5.43 ms versus 4.02 for worst accepted (+35%, tolerance 25%)
- none 100/inside thread.p95: 5.72 ms versus 4.47 for worst accepted (+28%, tolerance 25%)
- none 100/outside gpu.p50: 3.74 ms versus 2.43 for worst accepted (+54%, tolerance 25%)

Look differs noticeably (for the reviewer, not a failure):
- none 100-hit_10s: smoky pixels 26% versus 15% / 14%

Cells: GPU p50 / CPU p95 / frame p95 (frames per second), ms. GPU — the GPU clock from the first to the last command of the frame; CPU — CPU time of the render thread in the frame (RenderFrameEvent.Pre → Post, without the buffer swap, preemption and driver sleep); frame — from the frame start to the next frame start, as the player sees it. The gate judges GPU p50 and CPU p95 in the `100/*` windows.

## Pack: none

| window | 3.10.4 (B) | 1aca1654a@1aca1654 A1 | 1aca1654a@1aca1654 A2 |
|---|---|---|---|
| 100-hit | 18.0 / 21.7 / 30.9 (48) | 14.6 / 17.2 / 25.1 (60) | 17.1 / 21.0 / 30.1 (49) |
| 100/outside | 3.7 / 6.1 / 7.5 (173) | 2.3 / 5.1 / 6.6 (201) | 2.4 / 5.4 / 7.1 (188) |
| 100/edge | 5.4 / 5.9 / 8.2 (169) | 4.0 / 4.8 / 6.8 (205) | 3.6 / 5.2 / 7.1 (200) |
| 100/inside | 5.4 / 5.7 / 8.3 (172) | 3.7 / 4.4 / 6.6 (215) | 4.0 / 4.5 / 6.8 (210) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | 3.10.4 (B) | 1aca1654a@1aca1654 A1 |
|---|---|---|
| after_level+ | 3.66 | 2.20 |
| after_solid_blocks | 0.07 | 0.07 |
| tail | 0.04 | 0.04 |
| after_sky | 0.03 | 0.03 |
| after_particles | 0.01 | 0.06 |
| after_entities | 0.01 | 0.01 |
| after_weather | 0.00 | 0.00 |
| after_sky+ | 0.00 | 0.00 |

Bound: cpu; server tick mean 1.3 ms; wall-clock Pre→Post p95 6.1 ms; other load on the machine 0.1 cores, GPU 12 % (nothing heavy); probes: FxLayer.describeTiming: quads 1818, 4.780 ms (longest in the second 9.450; without GC 5.571, CPU 5.564; frames with GC 1), GPU 3.503 ms (longest 5.751, 181 frames; heat pass 0.383, longest 1.022, 181 frames — heat lights 4 hot 4 front 0 jets 0, area 2 rects (2 lit) 28.6% [r 2.5 h 21 dT 63 px 1.06] [r 2.5 h 21 dT 63 px 1.02] [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.03]; bloom FAST 0.223 ms (longest 0.517, 181 frames; glow pass 0.054, pyramid 0.104, out 0.063; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 181 frames; a pass's tail may spill into the next): models 0.00 depth 0.05 water 0.00 pall 1.56 heat 0.38 probes 0.01 quads 1.32 bloom 0.22 (sum 3.56); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1473 quads, full 731 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 12.53 screens (budget 16, one club per gaussian 12.53, before merging 40.50), cluster share 0.00, merge level 3, veil gaussians 0, clusters 1598, plan 3.41 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | 3.10.4 (B) | 1aca1654a@1aca1654 A1 |
|---|---|---|
| after_level+ | 5.32 | 3.98 |
| after_solid_blocks | 0.08 | 0.09 |
| tail | 0.05 | 0.05 |
| after_sky | 0.03 | 0.03 |
| after_entities | 0.01 | 0.01 |
| after_translucent_blocks | 0.01 | 0.07 |
| after_weather | 0.01 | 0.01 |
| after_particles | 0.00 | 0.01 |

Bound: gpu; server tick mean 1.4 ms; wall-clock Pre→Post p95 6.0 ms; other load on the machine 0.0 cores, GPU 22 % (nothing heavy); probes: FxLayer.describeTiming: quads 1140, 4.911 ms (longest in the second 5.832; without GC 5.832, CPU 5.806; frames with GC 0), GPU 5.591 ms (longest 9.264, 162 frames; heat pass 1.501, longest 2.537, 161 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.3 h 19 dT 63 px 1.35] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.321 ms (longest 0.658, 162 frames; glow pass 0.106, pyramid 0.124, out 0.076; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 162 frames; a pass's tail may spill into the next): models 0.00 depth 0.07 water 0.02 pall 1.83 heat 1.51 probes 0.06 quads 1.87 bloom 0.33 (sum 5.67); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 622 quads, full 605 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.99 screens (budget 16, one club per gaussian 15.99, before merging 30.62), cluster share 0.00, merge level 3, veil gaussians 38, clusters 758, plan 2.50 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | 3.10.4 (B) | 1aca1654a@1aca1654 A1 |
|---|---|---|
| after_level+ | 5.38 | 3.70 |
| after_solid_blocks | 0.07 | 0.07 |
| tail | 0.05 | 0.05 |
| after_sky | 0.04 | 0.04 |
| after_weather | 0.01 | 0.01 |
| after_entities | 0.01 | 0.01 |
| after_translucent_blocks | 0.01 | 0.03 |
| after_particles | 0.00 | 0.00 |

Bound: gpu; server tick mean 1.5 ms; wall-clock Pre→Post p95 5.8 ms; other load on the machine 0.1 cores, GPU 0 % ({'arch-update.service': 0.18}); probes: FxLayer.describeTiming: quads 518, 4.608 ms (longest in the second 5.739; without GC 5.739, CPU 5.724; frames with GC 0), GPU 5.244 ms (longest 7.737, 174 frames; heat pass 1.669, longest 3.165, 174 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 174 frames; a pass's tail may spill into the next): models 0.00 depth 0.06 water 0.01 pall 1.76 heat 1.68 probes 0.05 quads 1.75 bloom 0.00 (sum 5.31); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 422 quads, full 96 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.96 screens (budget 16, one club per gaussian 15.96, before merging 78.92), cluster share 0.00, merge level 3, veil gaussians 38, clusters 390, plan 3.53 ms off-thread

3.10.4 (B): frames 93022, 2.9 GPU minutes, impacts: 100 100/100 (all, 2 s)

Frames side by side: `sheet-none.png`
