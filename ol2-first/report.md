# Scene stand: 3.10.4 (B)

GPU NVIDIA GeForce RTX 4060 Laptop GPU, driver 615.78.08, window 2560x1600, scenes ['100'], stand 93bda32d0c45, power performance, jar sha256 cec5517a996d (incoming-3.10.4.jar).

## Verdict against 1aca1654a@1aca1654: FAIL
Each pack is shot as a pair in one session: base A1, candidate B, base A2. The candidate fails a value when it is more than 25% above the worse of A1 and A2; a pack that does not pass is shot by another pair, and the last pair decides.
- none 100/edge gpu.p50: 5.61 ms versus 3.34 for worst accepted (+68%, tolerance 25%)
- none 100/inside gpu.p50: 5.32 ms versus 3.25 for worst accepted (+64%, tolerance 25%)
- none 100/inside thread.p95: 9.74 ms versus 7.79 for worst accepted (+25%, tolerance 25%)
- none 100/outside gpu.p50: 4.06 ms versus 2.33 for worst accepted (+74%, tolerance 25%)
- none 100/outside thread.p95: 10.68 ms versus 6.36 for worst accepted (+68%, tolerance 25%)

Cells: GPU p50 / CPU p95 / frame p95 (frames per second), ms. GPU — the GPU clock from the first to the last command of the frame; CPU — CPU time of the render thread in the frame (RenderFrameEvent.Pre → Post, without the buffer swap, preemption and driver sleep); frame — from the frame start to the next frame start, as the player sees it. The gate judges GPU p50 and CPU p95 in the `100/*` windows.

## Pack: none

| window | 3.10.4 (B) | 1aca1654a@1aca1654 A1 | 1aca1654a@1aca1654 A2 |
|---|---|---|---|
| 100-hit | 18.7 / 23.0 / 34.3 (45) | 18.9 / 25.9 / 39.9 (42) | 18.2 / 23.3 / 35.4 (45) |
| 100/outside | 4.1 / 10.7 / 12.6 (110) | 2.3 / 5.8 / 7.3 (182) | 2.3 / 6.4 / 8.1 (165) |
| 100/edge | 5.6 / 11.0 / 31.7 (93) | 3.0 / 9.0 / 12.2 (130) | 3.3 / 5.7 / 8.0 (182) |
| 100/inside | 5.3 / 9.7 / 11.4 (130) | 3.2 / 7.8 / 9.0 (165) | 2.7 / 5.1 / 7.5 (192) |

GPU by stage, 100/outside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | 3.10.4 (B) | 1aca1654a@1aca1654 A1 |
|---|---|---|
| after_level+ | 3.96 | 2.28 |
| after_solid_blocks | 0.07 | 0.07 |
| tail | 0.04 | 0.04 |
| after_sky | 0.02 | 0.02 |
| after_particles | 0.01 | 0.04 |
| after_entities | 0.01 | 0.01 |
| after_weather | 0.00 | 0.00 |
| after_sky+ | 0.00 | 0.00 |

Bound: cpu; server tick mean 2.0 ms; wall-clock Pre→Post p95 10.8 ms; other load on the machine 6.7 cores, GPU 0 % ({'incoming-job-mt-l2-hints-build-27727.service': 6.15, 'incoming-job-mt-l2-recovery-build-19137.service': 2.55, 'incoming-codex-MT-L2.service': 0.17}); probes: FxLayer.describeTiming: quads 2056, 7.118 ms (longest in the second 9.779; without GC 9.779, CPU 9.736; frames with GC 0), GPU 3.687 ms (longest 4.492, 117 frames; heat pass 0.341, longest 0.818, 117 frames — heat lights 4 hot 4 front 0 jets 0, area 2 rects (2 lit) 28.8% [r 2.5 h 21 dT 63 px 1.07] [r 2.5 h 21 dT 63 px 1.00] [r 2.5 h 21 dT 63 px 1.03] [r 2.5 h 21 dT 63 px 1.02]; bloom FAST 0.206 ms (longest 0.243, 117 frames; glow pass 0.056, pyramid 0.092, out 0.056; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 117 frames; a pass's tail may spill into the next): models 0.00 depth 0.05 water 0.00 pall 1.85 heat 0.34 probes 0.01 quads 1.27 bloom 0.21 (sum 3.74); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 1576 quads, full 892 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 13.57 screens (budget 16, one club per gaussian 13.57, before merging 42.26), cluster share 0.00, merge level 3, veil gaussians 0, clusters 1736, plan 5.85 ms off-thread

GPU by stage, 100/edge (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | 3.10.4 (B) | 1aca1654a@1aca1654 A1 |
|---|---|---|
| after_level+ | 6.11 | 2.97 |
| after_translucent_blocks | 0.10 | 0.49 |
| after_solid_blocks | 0.09 | 0.07 |
| tail | 0.05 | 0.04 |
| after_sky | 0.03 | 0.03 |
| after_entities | 0.01 | 0.01 |
| after_weather | 0.01 | 0.00 |
| after_particles | 0.00 | 0.00 |

Bound: cpu; server tick mean 4.1 ms; wall-clock Pre→Post p95 22.2 ms; other load on the machine 8.6 cores, GPU 6 % ({'incoming-job-mt-l2-recovery-build-19137.service': 13.28, 'incoming-job-mt-l2-hints-build-27727.service': 1.29, 'app-com.anthropic.Claude-3533.scope': 0.45}); probes: FxLayer.describeTiming: quads 1129, 5.744 ms (longest in the second 6.744; without GC 6.744, CPU 6.717; frames with GC 0), GPU 5.722 ms (longest 8.514, 143 frames; heat pass 1.543, longest 2.912, 143 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.294 ms (longest 0.739, 142 frames; glow pass 0.094, pyramid 0.120, out 0.072; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 143 frames; a pass's tail may spill into the next): models 0.00 depth 0.07 water 0.01 pall 2.09 heat 1.54 probes 0.05 quads 1.75 bloom 0.30 (sum 5.80); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 605 quads, full 613 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.97 screens (budget 16, one club per gaussian 15.97, before merging 31.83), cluster share 0.00, merge level 3, veil gaussians 36, clusters 765, plan 2.92 ms off-thread

GPU by stage, 100/inside (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, `tail` = after the last stage: GUI, post):

| stage | 3.10.4 (B) | 1aca1654a@1aca1654 A1 |
|---|---|---|
| after_level+ | 5.29 | 3.26 |
| after_solid_blocks | 0.07 | 0.07 |
| tail | 0.04 | 0.04 |
| after_sky | 0.03 | 0.03 |
| after_weather | 0.01 | 0.01 |
| after_entities | 0.01 | 0.01 |
| after_translucent_blocks | 0.01 | 0.10 |
| after_particles | 0.00 | 0.02 |

Bound: cpu; server tick mean 1.9 ms; wall-clock Pre→Post p95 9.9 ms; other load on the machine 5.2 cores, GPU 0 % ({'incoming-job-mt-l2-gametest-29912.service': 6.68, 'incoming-job-mt-l2-hints-build-27727.service': 1.02, 'app-com.anthropic.Claude-3533.scope': 0.24}); probes: FxLayer.describeTiming: quads 519, 7.000 ms (longest in the second 10.770; without GC 10.770, CPU 10.726; frames with GC 0), GPU 4.963 ms (longest 7.732, 116 frames; heat pass 1.330, longest 2.597, 115 frames — heat lights 4 hot 4 front 0 jets 0, area 1 rects (1 lit) 100.0% [r 2.5 h 21 dT 63 px 1.37] [r 2.5 h 21 dT 63 px 1.37] [r 2.3 h 19 dT 63 px 1.35] [r 2.5 h 21 dT 63 px 1.37]; bloom FAST 0.000 ms (longest 0.000, 0 frames; glow pass 0.000, pyramid 0.000, out 0.000; 2560x1600, glow level 3, levels 3-9)), depth: world; GPU by pass (ms, mean of 116 frames; a pass's tail may spill into the next): models 0.00 depth 0.05 water 0.01 pall 2.06 heat 1.34 probes 0.04 quads 1.52 bloom 0.00 (sum 5.02); drained frame: none (F3 or -Dincoming.fx.drain=true); half-resolution smoke 422 quads, full 97 (a quad over both kinds of tiles counts in each), lays 1; FarRenderer.describeSmoke: fill 15.98 screens (budget 16, one club per gaussian 15.98, before merging 80.91), cluster share 0.00, merge level 3, veil gaussians 13, clusters 403, plan 4.63 ms off-thread

3.10.4 (B): machine load in the verdict windows: window 100/outside: foreign jobs — up to 8.64 cores (allowed 1.0): {'incoming-job-mt-l2-hints-build-27727.service': 6.15, 'incoming-job-mt-l2-recovery-build-19137.service': 2.55, 'incoming-codex-MT-L2.service': 0.17}; window 100/edge: foreign jobs — up to 14.91 cores (allowed 1.0): {'incoming-job-mt-l2-recovery-build-19137.service': 13.28, 'incoming-job-mt-l2-hints-build-27727.service': 1.29, 'app-com.anthropic.Claude-3533.scope': 0.45}; window 100/inside: foreign jobs — up to 7.87 cores (allowed 1.0): {'incoming-job-mt-l2-gametest-29912.service': 6.68, 'incoming-job-mt-l2-hints-build-27727.service': 1.02, 'app-com.anthropic.Claude-3533.scope': 0.24}

1aca1654a@1aca1654 A1: machine load in the verdict windows: window 100/outside: foreign jobs — up to 2.24 cores (allowed 1.0): {'incoming-job-lead2-jfr-stages-19293.service': 1.24, 'incoming-job-mt-l2-recovery-build-19137.service': 1.03}; window 100/edge: foreign jobs — up to 7.5 cores (allowed 1.0): {'incoming-job-mt-s2-demand-23423.service': 6.45, 'incoming-job-mt-l2-recovery-build-19137.service': 1.04, 'app-com.anthropic.Claude-3533.scope': 0.37}; window 100/inside: foreign jobs — up to 7.15 cores (allowed 1.0): {'incoming-job-mt-l2-recovery-build-19137.service': 1.79, 'app-com.anthropic.Claude-3533.scope': 0.37}

1aca1654a@1aca1654 A2: machine load in the verdict windows: window 100/outside: foreign jobs — up to 1.85 cores (allowed 1.0): {'incoming-job-mt-l2-hints-build-27727.service': 1.73}; window 100/edge: foreign jobs — up to 1.73 cores (allowed 1.0): {'incoming-job-mt-l2-hints-build-27727.service': 1.61}; window 100/inside: foreign jobs — up to 1.4 cores (allowed 1.0): {'incoming-job-mt-l2-hints-build-27727.service': 1.15, 'incoming-codex-OL2-REV.service': 0.15}

3.10.4 (B): frames 82705, 3.0 GPU minutes, impacts: 100 100/100 (all, 1 s)

Frames side by side: `sheet-none.png`
