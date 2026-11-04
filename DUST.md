# Conventional dust allocation, 4 November 2026

Creating a conventional cloud builds ground-dust models and queries their optical depth at future ages to find visibility duration. These private scalar queries allocated six 5×8 moment tables each time. Reusing per-instance work tables removes that repeated allocation. Visible emission retains its own local tables, including callback ownership. Arithmetic order, ages, physical laws, particle counts, opacity, color, terrain behavior and sound are unchanged. This is ordinary array reuse, following the rejected ready-made AsyncParticles evaluation; no replacement particle policy is introduced.

## Current-source diagnosis

The bounded `current-native` recording uses the actual main14f36 CI artifact, SHA256 `c64db8035394df8998b82e5f9f9d7703b41b1fdae510ee6a656c851cdedfc8c7`, in the copied full pack without AP. Async-profiler CPU2ms/DWARF and stock JFR attach for five seconds around the first group. The actual plan has100 direct conventional impacts in ten-at-a-time groups, not100 simultaneous projectile launches. All100 native entries,100 blast events and renderer/shader/workload contracts pass.

The instrumented early sequence is270.075 /417.053 /601.590ms wall and242.986 /326.515 /394.874ms full-cycle CPU. These are diagnostic latencies, not ordinary-game baselines. Filtering the actual Minecraft main TID309987 gives118 /157 /190 sampled records; another native OpenAL thread shares the name Render thread and is excluded. In the first clip,108 samples run through blast packet processing,46 through CloudModel construction,41 through GroundDust construction,16 through lifetime work and15 through emit (inclusive, not additive or exact duration). The following two clips instead have40 /47 ParticleEngine tick samples and substantial render/effect work. This local fix does not explain or remove every hitch.

JFR includes sampled two-dimensional and row-array allocations at emit→tau→lifeSeconds→constructor. These identify allocation sites; their sampled weights do not establish exact allocation rates. The first per-thread cumulative allocation value is not bytes allocated during this five-second capture. Foreign CPU in the capture is0.00–0.05 cores, with5% compositor GPU activity; own JVM compilation and GC are active. The4.189ms CodeCache-threshold G1 pause cannot be assigned to a particular frame using the coarse exported timestamps. Exact raw frames, host intervals and bounded native clips are retained.

## Regression, source review and checks

The external allocation regression queries128 warmed scalar optical depths, including backwards age requests, with exact rewind comparisons. Baseline451,328 allocated bytes becomes155,648:295,680 bytes or65.5% less for this fixture/runtime. That is a query-allocation result, not a measured constructor/FPS gain. Existing independent frozen CloudBuild physical samples and GroundDust behavior tests are unchanged. Fixed work storage is approximately2.7KB per populated dust instance on typical compressed-reference HotSpot layouts, retained for that model's lifetime; the two cloud dust instances are approximately5.4KB. This is a layout estimate, not a measured heap delta.

The full build passes1,785 unit tests with no skipped/failing cases, and touched blast-radius GameTest1/1 passes. Fresh independent Sol/xhigh source review finds no serious defect. Final head7c4b39c7987dcbc31e298c9b9a2dd93f3f58f2fb normally merges checked main1126ffa3 and retains the reviewed two-file delta. Required Итог41822231322 is green. No new image/audio capture is required for this ownership-only mathematical change; the independent reviewer verified unchanged emission and sound paths.

## Frozen final runtime comparison

All three clients and the wrapper complete with exit0. Each records100 native entries and100 blasts; all shader/renderer/workload contracts pass. The same copied73-JAR full pack, Aeronautics1.3.2/Sable2.0.6, Complementary Unboundr5.9.3,2560×1600, night city, seed20261025, plan and helper are used in fresh JVM/world copies. No profiler, screenshots or diagnostic readback. Essential saved update prompts are disabled only in disposable copies. A validated identical disposable Connector cache contains no fresh-install race proof.

The baseline is a main-equivalent jar: the entire GroundDust class family is compiled from pinned main1126ffa3 using the same JDK21 and placed into the candidate jar. Every other ZIP entry is byte-identical. Pair provenance records exact changed entries and SHA256 hashes. Main-equivalent SHA c78f7641ff997e1b8c52bb8b3c4f9c0bae40f49832353626a9eefdea4830fcb1; candidate SHA1ac2669c6cb09a1f53c27aaf7e65848cb9950be8ab3fac7a0b89558d8826ed51. This isolated measurement artifact is not a deployed production jar.

| Total effect window | Main A1 | Candidate B | Main A2 |
|---|---:|---:|---:|
| Frame p50 /p99 /max, ms | 16.738 /82.112 /224.174 | 16.610 /80.232 /215.086 | 17.554 /88.884 /218.810 |
| Frames >33.333 />50 />100ms | 311 /177 /9 | 310 /146 /7 | 320 /171 /13 |
| Render-bracket CPU p50 /p99, ms | 15.205 /47.208 | 13.389 /44.404 | 15.315 /45.473 |
| Full-cycle CPU p50 /p99, ms | 16.142 /78.674 | 15.487 /74.479 | 16.884 /83.429 |
| GPU timestamp span p50, ms | 14.967 | 14.396 | 15.186 |
| Active3–15s frame p50, ms | 54.018 | 50.037 | 53.103 |
| Foreign CPU mean /max, cores | 0.13 /0.35 | 0.07 /0.41 | 0.16 /0.76 |
| Foreign GPU mean /peak SM, % | 1.74 /11 | 0.00 /0 | 0.54 /7 |

Actual CPU and GPU telemetry covers100% of each effect window. Foreign work is not zero: candidate B includes MT-AUDIT up to0.38 cores and the liaison app up to0.14; A2 includes MT-AUDIT up to0.68 cores. Desktop GPU peaks are11/0/7% in A1/B/A2. A2 also has33% desktop GPU activity before the effect window, retained in raw telemetry. All runs use the performance power profile; GPU clocks and JVM warmup are uncontrolled. This is a bounded real-pack comparison, not an exclusive-machine fixed-clock experiment.

The candidate has lower total p50/p99/max and fewer >50ms frames than both controls, with no demonstrated whole-scene regression. Active3–15s render-bracket CPU medians37.895/34.249/37.624ms and full-cycle CPU medians52.439/48.727/51.634ms also favor B. This one noisy A/B/A set supports accepting the measured allocation fix; it does not isolate every latency difference or prove that reuse accounts for the full improvement. No repeated runs or version search were used to select this result.

GPU/LOD were rechecked after the CPU change. Active3–15s AFTER_LEVEL timestamp spans p50/p99 are7.425/12.146,6.968/12.382,7.474/12.034ms; Bmax14.862ms. The initial3s Bmax18.743ms is retained (controls14.649/14.202), not censored. These spans include every handler at that event and possible CPU starvation, not pure Incoming GPU-busy time. Source drawing code is identical. Ordinary world+Voxy depth remains active without coverage readback. Nuclear standard-LSS delivery remains the separately owned actual-world trial, not certified by this conventional scene.

Long frames remain. B's worst215.086ms interval contains173.942ms full-cycle CPU and75.739ms render-bracket CPU (94.602ms bracket wall); the worst full-cycle CPU is181.533ms on another frame. This patch must not be described as a complete cold-hitch fix or general release-readiness proof. Current bounded main-thread samples identify mixed tick, particles, effects, class loading and rendering; the duplicate native OpenAL thread is excluded. There is no current evidence justifying a DSP change. No vanilla/foreign-mod mechanic, visible phenomenon, event timing or physics was disabled.
