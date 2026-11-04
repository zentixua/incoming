# PERF-C2-CAUSE — independent read-only cold-cycle diagnosis

2026-11-04. Session: gpt-6.1-sol / high (verified in its running command). Diagnosis only; PERF-C2 retains implementation and client-readiness ownership.

## Result

The biggest reproduced remaining measured cycle is the initial conventional-strike client hitch. In the telemetry-fixed `final-post-b` arm it is **209.505 ms elapsed / 185.381 ms render-thread CPU**, frame 3612. Only **78.251 / 74.377 ms** of that is inside RenderFrame Pre/Post. The larger share of the CPU, **111.004 ms**, is outside that render bracket. This is a real unresolved end-to-end cost in the retained observer run; a small FX render number would not close it.

The older native diagnostic supports a **mixed client-tick/effect-startup/particle/render cycle**, rather than a single dominating method. It does not select a safe useful current-code correction. In particular, adding another smoke replay/merge cache would duplicate existing code. No production fix is recommended on these data alone. One bounded current native capture, specified below, is the next discriminating step. No readiness claim.

## Scope and provenance

- Clean detached worktree `/mnt/data/projects/incoming/mod/run/wt-PERF-C2-CAUSE`, HEAD `14f36e8ec965501c68224c091258eb1443e15f5d`. No source changes, commits, builds, tests, clients, GPU work, JFR re-exports, children, server or playing-instance actions.
- Read current TEAM, BOARD, context, live, AGENTS, applicable client/FX/smoke/sound/tools/rig rules and canonical memory. No current `PERF-C2-handoff.md` exists; this explicitly delegated read-only task is the scope, not an ownership transfer. PERF-C2's running service/worktree was left alone. An MT-L2 GPU timing job was running at bootstrap; all analysis used source and retained small exports (individual commands completed in under one second).
- Did not read PERF-C2's report, FINAL, PR descriptions, author inbox contents or conclusions. Existing memory contains historical measurement cautions; findings below were recomputed from the named raw inputs. No independent implementation review was needed or launched for this diagnosis-only scope.
- Raw root **R**: `/mnt/data/projects/incoming/mod/run/wt-PERF-C2/mod/run/perf-c2/runs`. Paths below are relative to R unless stated otherwise.
- All three final timing arms: `profile=none`, no screenshots, no AsyncParticles, performance power profile, 2560×1600 RTX 4060 / NVIDIA 615.78.08; retained `once` events show `ComplementaryUnbound_r5.9.3.zip` and `isPackInUseQuick=true`. Actual plan is city/night (`day_time=18000`), despite generic manifest argument labels `terrain=field`, `light=day`. All exits are code 0. This is the synthetic **native100direct** impact route, not flight/approach validation.
- `final-a1` / `final-a2`: manifest source `36c9b5bdddbad22cc4e418c7b02bb7a141f56e4b`; actual installed JAR SHA-256 `e7cde0b4fbd64bb813ee2bb6849a91daa053ac73790c268149bfdf7dd02eab3c`.
- `final-post-b`: manifest source `f69381911602154c0ce994f57150881169a4cfe9`; actual installed JAR SHA-256 `7022daa928659d13c24d41c45195b3ba4dacbd0df4682037846d943651d723be`.
- All three installed files happen to be named `mods/candidate-f69381911.jar`; filename/manifest `args.jar` alone is insufficient. Independently hashed installed JARs and compared decompressed ZIP payloads: the only A/B entry difference is `ua/zentix/incoming/client/fx/layer/SceneDepth.class`. No entry-set difference. Manifest stand SHA-256 is `c3860bdbfbfd04be2b606708b5f04ca8063dfa0351e0e1450cef64aa31bae818`, runner source `ae5aaf609`.
- Main diagnostic `main-profile`: source `3be78e33b`, installed Incoming SHA-256 `9b95ecb54937de1194649918dcf0229aa3b7e4fdfaea35d19c9bbf6a11437ed7`, `profile=async`, native CPU sampling 2 ms / DWARF plus stock JFR and work counters. Instrumented evidence, not another timing arm. No current-candidate native capture exists.

## Timing contract and independently joined raw rows

Retained source contract: `/mnt/data/projects/incoming/mod/run/wt-PERFA/tools/stand/src/main/java/ua/zentix/stand/{FrameClock,Director,ServerWatch,Probes}.java`.

`FrameClock.onPre` records the current Pre time and differences against the previous Pre. Thus frame F's complete cycle uses F's `t_ms`, **F+1's `prev_interval_ms` and `prev_cpu_cycle_ms`**. F's own `cpu_ms` / `cpu_thread_ms` are only Pre→Post. F's `gpu_ms` is a GPU timestamp span, including command gaps; F+1's `prev_gpu_period_ms` is start→start. Neither is additive to CPU. These are not presentation timestamps. CSV physical line is frame number + 1 (header); the complete-cycle fields are on the following line. All rows quoted here have `shot=0`.

`Director` emits native-begin and sends ten direct commands per scheduled batch, then waits 1.5 seconds for the next batch. `ServerWatch.blast` records ExplosionEvent.Detonate time; native direct events have source `none`, damage `incoming:strike`. There are 100 successful impact commands and 100 blast events, **no projectile `impact` records**. Do not treat this as 100 simultaneous projectile hits. Probe timestamps are the shared `now` captured before the whole probe loop, not individual method completion times.

| Arm | Peak frame | Pre t_ms → next Pre t_ms | Full cycle wall / CPU ms | Render wall / CPU ms | GPU span / next GPU period ms |
|---|---:|---|---:|---:|---:|
| final-a1 | 3668 | 96823.641 → 97085.470 | 261.829 / 190.946 | 118.314 / 71.898 | 99.340 / 248.034 |
| final-post-b | 3612 | 97121.650 → 97331.155 | 209.505 / 185.381 | 78.251 / 74.377 | 83.129 / 213.600 |
| final-a2 | 3593 | 98857.425 → 99093.222 | 235.796 / 205.729 | 82.975 / 75.683 | 78.585 / 238.165 |
| main-profile, diagnostic | 3729 | 96904.662 → 97285.882 | 381.220 / 287.715 | 99.607 / 87.589 | 94.003 / 383.571 |

Inputs: each arm's original `stand-out/frames.csv`, `stand-out/events.jsonl`, `manifest.json`, `stand-plan.json`, `exit.json`. These values establish persistence of the early peak after the observer fix; maxima alone do not quantify its causal speedup. A/B differ in one class, but GC, clocks, event delivery and other load vary.

For the corrected B arm, peak-relative chronology, using `events.jsonl` physical line numbers:

| Event | t_ms | Relative to frame 3612 Pre |
|---|---:|---:|
| Native-begin / first batch due, line 1332 | 96715.802 | −405.848 ms |
| First command completes, line 1335 | 96716.866 | −404.784 ms |
| First blast, line 1346 | 96808.362 | −313.288 ms |
| Tenth blast, line 1356 | 96837.919 | −283.731 ms |
| Frame 3610 Pre | 96744.407 | −377.243 ms |
| Frame 3611 Pre | 96924.664 | −196.986 ms |
| Probe loop timestamp | 97025.657 | −95.993 ms |
| Frame 3612 Pre / Post | 97121.650 / 97199.901 | 0 / +78.251 ms |
| Next Pre | 97331.155 | +209.505 ms |
| Next batch nominal due | 98215.802 | +1094.152 ms |

Frame 3610 is itself 180.257 ms / 161.997 CPU but only 1.941 / 1.934 render; frame 3611 is 196.986 / 164.341 CPU, render 79.368 / 64.983. Therefore the peak is a sequence spanning early effect processing and subsequent frames after the first ten blasts, before the second batch. First-batch blast ranges relative to each native-begin: A1 +127.160…321.670 ms, B +92.560…122.117 ms, A2 +107.106…127.201 ms. Do not infer identical receive timing from the shared plan.

B also retains a later 168.084 ms / 167.525 CPU cycle at frame 4069, `t_ms=117308.279→117476.363`, render 16.599 / 16.497. It is a separate unresolved inter-bracket hitch; no current stack establishes its cause.

Measured phase maxima, original window boundaries (frame assigned by its own Pre, complete cycle from the next row):

| Phase | A1 max ms | Fixed B max ms | A2 max ms |
|---|---:|---:|---:|
| Pre-strike baseline | 15.722 | 15.751 | 21.443 |
| t0–3 | 261.829 | 209.505 | 235.796 |
| t3–15 | 131.189 | 124.762 | 157.648 |
| t15–35 | 133.189 | 168.084 | 133.755 |
| t35–50 | 52.269 | 33.829 | 60.909 |

## Native/JFR evidence: what the older peak actually contains

Inspected `main-profile` first. Original clip: `native-longest-frame.{json,collapsed}`, integer epoch interval `1793781176087…1793781176468`; raw joined row interval `1793781176087.266…1793781176468.486`. Epoch origin derived from median `srv.epoch_ms−srv.t_ms`: `1793781079182.604`. Rounding at clip edges is under 1 ms.

There are **140 Render-thread CPU samples**. Partitioned each collapsed stack exactly once by its call path (tick first, then GameRenderer render, otherwise residual); percentages are sample shares, not exact milliseconds:

| Disjoint branch in this clip | Samples / 140 |
|---|---:|
| Minecraft.tick → vanilla ParticleEngine tick | 21 (15.0%) |
| Minecraft.tick → Incoming Effects timelines | 21 (15.0%) |
| Minecraft.tick → remaining IncomingClient.tick | 15 (10.7%) |
| Minecraft.tick → client entities | 13 (9.3%) |
| Minecraft.tick → ambient animate | 10 (7.1%) |
| Minecraft.tick → other | 4 (2.9%) |
| GameRenderer render → FX smoke source/replay path | 13 (9.3%) |
| GameRenderer render → other FX | 13 (9.3%) |
| GameRenderer render → other world | 13 (9.3%) |
| Other / unresolved | 17 (12.1%) |

Thus **84 tick / 39 render / 17 residual** is the defensible broad ranking in this older peak. At narrower scope the leading two branches tie; sampling uncertainty does not justify a strict winner. The 36 inclusive IncomingClient.tick samples contain the 21 timeline samples; the 26 inclusive FX samples contain 13 smoke and 13 other FX. Do not sum inclusive CloudModel/GroundDust/SmokePreparation/SmokePuffs counts into separate costs.

Nested examples, not additional cost: `BlastSounds.arrive` 14, `BlastPulse.render` 6; pairwise `SmokePuffs.merge` 10 (deduplicated recursive method occurrences); `TerrainParticle.tick` 10, own Fragments tick 4; FxPool tick 7; FarBlasts.clear/visibility rays 6. Incoming Smoke worker has 6 samples in parallel, not six more Render-thread samples. Concurrent server/LOD/JIT/GC workers consume machine capacity but cannot be charged as exclusive client duration.

Stock JFR `hitch-spans.tsv` / `hitch-events.tsv` corroborate order. Thread is named **main** there, versus Render thread in async-profiler:

- Frame 3727 has six main-thread samples: at epoch `1793781175659.579` HobBlast/range math; `…5669.704` TerrainEcho approach; `…5699.983` FrontLitter kick; `…5769.5474` FrontDust layout; `…5779.6877` GroundDust/CloudModel construction; `…5789.7983` Voxy snapshot/recalcBlockCounts. This is an early effect/receive cycle, not proof of one cold constructor consuming its full 185.218 ms.
- Frame 3728 includes four main-thread `SceneDepth.lodShare` samples at epochs `1793781175939.7385…1793781175970.228`, through `describeTiming`, plus FX and particle work. That observer path is already guarded in current `SceneDepth.java:214–216`; it is not the remaining current fix.
- Frame 3729 has 14 main-thread samples: merge at `1793781176158.8213` and `…6169.1184`, HalfPass at `…6179.3767`, then LocalWind, ambient ticks, collision, sight rays, sound arrival at `…6395.0500`, and pulse synthesis at `…6466.2078`. This spans real render and later tick work.
- Its two recorded GC pauses are 7.510747 and 0.126306 ms; the preceding frame has 5.538930 ms. Safepoint-entry durations are separate, not whole pauses. These pauses cannot explain the full 381 ms. The native clip contains class-linking/compilation paths too, but no evidence assigns the whole hitch to cold compilation.
- Whole diagnostic method export `render-cpu-methods.json` covers the longer profile window (`profile-window.json`: `1793781175561…1793781225665`), **23086 samples**, not just this peak. Examples: FX render inclusive 6947, SmokePreparation 4327, ParticleEngine render 2441 and tick 1369; these overlap and cannot replace the clipped ranking.

## Current code check and constraints on a useful fix

`git diff --quiet 3be78e33b HEAD` returned 0 for the relevant eight source files: IncomingClient, BlastEffects, FrontLitter, Effects, SmokePreparation, SmokePuffs, BlastSounds and BlastPulse. Their cited paths are under `mod/src/main/java/ua/zentix/incoming/client/` in the clean diagnostic checkout.

- `fx/Effects.java:35–54` runs age zero immediately when an effect arrives. `IncomingClient.java:230–263` ticks sound, far sources, own particles and effect timelines on the client. Moving these across frames or delaying birth is not a timing-neutral optimization.
- `fx/smoke/SmokePreparation.java:12–20,120–171,176–225` already prepares bounded complete snapshots and replays them, with a cooperative 2 ms refresh/maintenance budget. Selection/bounds and replay are outside the refresh loop; the 2 ms figure is not a hard bound on the entire smoke CPU path.
- `far/SmokePuffs.java:1427–1486` already has per-source input/output merge caching. Exact unchanged held inputs reuse results; changed snapshots are merged. A cached source merge is distinct from off-thread whole-frame planning and current-eye replay. The older merge samples do not prove a missing cache or a safe cache key to omit. No duplicate caching proposal.
- `fx/BlastEffects.java:301–355,395–410`: own Clod particles inherit TerrainParticle tick/collision, 1200 is a per-blast cap, and they enter the vanilla engine rather than FxPool. Current B probes still show 2725 Clods at native+1320.302 ms, 12260 at +2331.813 ms. Vanilla also has its own queue cap; this is not literally unbounded. At +309.855 ms B's class probe shows only LavaParticle=300 and own Fragments=484, with 2453 own sparks and no completed smoke plan. A later Clod count cannot establish the population or its exclusive CPU at the earlier peak.
- `sound/BlastSounds.java:211–267` synthesizes `BlastPulse` on arrival before attempting own-source playback; `sound/BlastPulse.java:58–87` is actual per-pressure/per-phase waveform computation. Six old peak samples establish a contributor, not priority or a demonstrated redundant identical calculation. Arbitrary quantization/reuse, changed sound onset, or removal of audible layers would require new correctness/audio evidence.

No disabling of vanilla/external particle mechanics, third-party internal patch, changed collision/arrival law, source-count cut, reduced visual detail or nuclear/LSS/server change is justified by this diagnosis. Cosmetic sampling changes to our own pieces may eventually be viable, but current evidence does not select their amount or prove preservation of the picture.

## Foreign load and other limits

Preserved actual host observations; no clean-run fiction:

- Corrected B cold peak epoch `1793788102.595…1793788102.805`: GPU host rows at 1793788102.5 and 1793788103.5 show foreign_sm=0 (own 99/16). CPU row **148**, ending 1793788104.587474 over 5.037 s, reports own=5.55 / foreign=0.03 CPU cores, no listed foreign unit. This is interval-average load, not a per-frame exclusion of preemption. The 24.124 ms elapsed-minus-CPU remainder is not assignable wholly to GPU, GC or foreign load.
- Across the 50-second strike windows with adjacent CPU coverage retained: CPU foreign ranges A1 0…0.15, B 0…0.51, A2 0…0.43 cores; B host row 166 names GOLD2 (0.46), A2 later names Claude desktop. GPU foreign maxima in the strike windows are A1=5, B=5, A2=8. Clocks/temperatures vary. Current B `gc.log` has no logged pause intersecting the early peak; A1 has a 9.194 ms remark there. This is not a perfect A/B timing isolation.
- `main-profile` cold neighborhood has low measured foreign CPU (row 160 ending 1793781176.0083048: 0.05 cores); 50-second CPU foreign range including adjacent coverage 0…0.06, GPU foreign max 4. Concurrent *own-process* workers in the native clip are still real competition.
- Supplemental `main-ap-profile` uses source3be78/AP602f8f1 and foreign co-work: peak host row154 ending1793781997.2398014 shows foreign1.07 cores, including CTL2 build0.99; later range up to3.01 includes RGOLD2P. Its frame3714 clip has381.135ms/307.862CPU,153 samples, including29 stand-probe /28 nested lodShare samples. It cannot rank the current no-AP remainder or prove a package gain.
- That AP run's existing `native-late-frame` export has88/98 samples in texture-atlas uploads through native NVIDIA code, on a195.808ms late cycle. This demonstrates why an inter-bracket CPU spike is not automatically an Effects tick. It does **not** attribute current no-AP frame4069 to that driver path. No AP correctness re-review or upstream conclusion here.
- No current image/audio comparison was supplied or created for a new optimization. Meaningful-picture preservation and ordinary-game readiness remain untested. Native/JFR instrumentation changes costs; samples show paths and approximate proportions, not exact duration or proof of a particular cold-cache mechanism.

## Exact bounded request for PERF-C2

After the present timing quiet ends, obtain **one cold-process, current-main, no-AP native100direct capture**, same copied full pack/city/night/Complementary/2560×1600, no screenshots/readbacks, no playing-instance changes. Keep original frames/events/host and installed JAR/stand/config hashes. Use the fixed ordinary telemetry path with `incoming.nukeDiag` absent. Preserve the existing schedule and all actual events; do not pre-trigger or warm up an explosion to remove its first-use work.

Bound native CPU sampling (2 ms, DWARF, threads) and stock JFR to **native-begin−1 s through +3 s** (first two batches, full neighboring Pre intervals), using the existing tools/wrappers after CPU/GPU coordination. Read actual new frame times; export only the early peak and its two preceding cycles plus a nearby ordinary cycle, outside anyone else's timing window. Four seconds is enough to discriminate the reproduced cold sequence; do not export a whole large JFR or rerun an old-version package arm for this question.

The capture must show whether the **current** >100 ms CPU outside render is predominantly (a) event/Effects initialization and timed sound/pieces, (b) repeated particle/client catch-up ticks, (c) native driver/atlas upload or other poll tasks, or (d) an actual wait/preemption/GC gap. Rank disjoint stacks across the whole sequence; do not pick one inclusive smoke/collision method. A proposed fix must name demonstrated avoidable work in our code and preserve event/birth/arrival times, trajectory/collision law, sound and visible matter. If the current native stack is diffuse again, report that result rather than inventing a cache or cutting the picture. This request is diagnostic, not authorization from this helper to change source or take ownership.

## В память

Recorded durable facts in canonical memory `client-cold-cycle-diagnosis.md`: current telemetry-fixed cold cycle remains209.505ms/185.381CPU, outside-render CPU is111.004ms; direct plan is ten-at-a-time, and existing source-merge caching precludes a duplicate fix. Retained old native peak has mixed disjoint branches and does not identify a current single-method correction.
