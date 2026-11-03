# PERF: client impact construction and first nuclear ray draw

## Existing solutions

| Tool or approach | Fit |
| --- | --- |
| [AsyncParticles](https://github.com/Harveykang/AsyncParticles) | Moves vanilla particle work; the audit still found Incoming model construction on the render thread. It cannot remove these constructors. |
| [Driver shader caching](https://developer.nvidia.com/docs/drive/drive-os/6.0.8/public/drive-os-linux-sdk/common/topics/graphics_content/AutomaticShaderCache8.html) | Helps subsequent runs, but a clean cache/driver update still exposes first-use compilation. |
| First real pass during world entry | Uses the existing OpenGL/vanilla shader pipeline and its real resources. No dependency or shader/presentation change. |

## Baseline and scope

Base: `341c2ea6fdda1a9ed6f941dc00c5065b1353bc7e` (main at task start).
Audit: `/mnt/data/projects/incoming/mod/run/perf-audit/REPORT.ru.md`.
The audit's full client-cycle profile found 312.913 ms wall / 305.455 ms CPU with CloudModel construction in 10/12 samples, plus a later 88 ms burst. Its nuclear cold draw spent 95.37% of render-thread CPU samples inside NVIDIA gpucomp (4134 ms cycle).

A fresh cold-cache sandbox reproduced the nuclear issue with per-program draw instrumentation: `fx_pall` apply 0.077 ms, draw 5507.054 ms. The otherwise identical cached run had no PallPass draw above 10 ms. The driver-internal compiler pass is not symbolized; this report does not infer one.

A JVM construction probe (120 Shahed models; exact standard atmosphere) separately sampled the original constructors: 53 CloudModel-construction samples, including GroundDust and Thermal. Timing results are diagnostic only: other independent fronts were building on the laptop during this work. Do not interpret these microbenchmarks as game frame p99.

## Change

- Retain immutable thermal rise tables by exact physics inputs (bounded cache); calculate each cloud's wind history only to the requested age.
- Calculate shear and dust support nodes on the existing grids only as needed; preserve old nodes for backward time queries.
- Calculate annular settling once per age, instead of once per sector/calculation ring; reuse ring lookup keys without string formatting.
- Extinction/lifetime probes evaluate mass and widths without visiting future streets or calculating unused positions.
- Run one empty nuclear volume pass while entering the world, retrying until background noise textures are ready. Track the shader instance so a resource reload prepares its replacement. The GLSL and detonation remain unchanged; no preparation work repeats in steady frames. This moves compilation into world initialization rather than eliminating compilation.

## Verification

Full build: 1,743 tests in 278 suites, no failures or errors. Touched-area GameTest: 6/6 passed (three conventional blast-fire cases, blast bodies, nuclear flash char/shadow). Final shader plumbing compiled successfully. No merge or release is authorized by this task.

Cold-cache Complementary sandbox, RTX 4060 Laptop, 1280×720: the real empty pass took 1,695 ms in the ray draw before `SANDBOX ready`; the first subsequent 15 kt ground burst had no PallPass draw above the 10 ms diagnostic threshold. The baseline cold first burst spent 5,507 ms in that draw. The original audit measured a 4,134 ms whole frame. These are separate runs, not a paired timing ratio. A one-pixel dummy warmup completed in a few milliseconds but did not prevent the later driver stall; using the actual pass/resources and waiting for texture readiness was necessary in this experiment. The native internal specialization key is not identified.

Temporary per-quad instrumentation was removed. The retained startup log records total preparation time once per shader instance. The cache-off sandbox launcher wraps Java because `laptop_job.sh` forwards `INCOMING_*` but not arbitrary `__GL_*` shell variables.

## Repeated impact audit

Same 100 immediate impacts, locations, clocks, server/client settings, Iris/Sodium versions, 2560×1600 without a shader; independent process with JFR and async-profiler. All 100 impacts confirmed; no contract issues. Foreign CPU load during the total window: median 0.00 cores, max 0.16.

| Metric, 50-second effect window | Original audit | Candidate |
| --- | ---: | ---: |
| Maximum full frame, ms | 312.913 | 127.110 |
| Full-frame p99, ms | 13.281 | 13.311 |
| Frames over 50 ms | 13 | 9 |
| CPU samples through CloudModel constructor | 452 | 118 |
| CPU samples through GroundDust constructor (included above) | 312 | 112 |

This reduces construction cost; it does not make every frame smooth. The candidate's 127 ms cold frame samples terrain lookup, particle initialization, dust construction and front-dust layout. Other remaining long frames sample particle work, front litter, drawing and terrain upload. These paths are not all fixed by this PR. The original 88 ms late constructor-dominated frame has no counterpart of that magnitude attributed to construction in this repeat; a 59.5 ms batch still includes dust construction plus front litter.

These historical and new timings are diagnostic comparisons, not a tightly paired percentage guarantee. The retained audit harness was updated after the original run (different jar hashes recorded in both manifests; the new snapshot adds particle-class/own-source probes). Impact steps and mod settings match. The final A1/B/A2 scene bench uses one identical current harness for all columns.

CI Итог passed on `3d3f93875612f1c32d1bbfec8418baa8b3f55423`. The final paired scene bench passed on all three packs with one A1/B/A2 sequence each, no retries. Candidate `3d3f9387`, main `292f3153`, jar SHA-256 `445c2f92cc92d39329437b7fce6573c26cf79c8425b0b705a60279612a816b73`; the same harness (`93bda32d0c45`) across all columns. All 100 strikes confirmed in every run. [Full scene report](bench/report.md), [gate verdict](bench/verdict.json).

| 100-hit full-frame p95, ms (report-only) | Main A1 | PR | Main A2 |
| --- | ---: | ---: | ---: |
| No shader | 35.50 | 28.74 | 34.29 |
| Complementary | 45.66 | 36.71 | 47.41 |
| Photon | 99.77 | 44.80 | 59.20 |

Across the gated `100/outside`, `100/edge`, `100/inside` windows, the largest GPU increase against the worse control was +16.77% (no shader, inside), within the existing 25% tolerance. The largest render-thread CPU p95 increase was +0.42%. This is a gate pass, not a claim that every GPU sample improved. Other sessions' CPU load affected some controls/Photon windows; the unedited report lists those intervals. Do not interpret the large Photon CPU ratios as an isolated speedup from this PR.

The author inspected all three side-by-side sheets. Smoke/fire detail, envelope and dust remain visually comparable to the controls; clump and debris positions vary between actual flight runs. No new grid, disappearing layer or visible simplification was observed. The exact reference tests separately verify unchanged physical outputs.

- [No-shader frames](bench/sheet-none.png)
- [Complementary frames](bench/sheet-complementary.png)
- [Photon frames](bench/sheet-photon.png)
- [Cold first nuclear draw, baseline log](logs/cold-baseline-client.log)
- [Cold preparation and first detonation, fixed log](logs/cold-fixed-client.log)
- [Nuclear sandbox frames and WAV](frames/)

The near nuclear captures are inside the flash and saturated white; they document timing, not distant shape quality. The additional 1,200 m view shows the unchanged fireball/condensation geometry. Sound code and recordings are unchanged; WAV is retained with these shots.

All scene camera moments are visible in the three comparison sheets; the full-resolution outside view is included for PR and main A2. The rest of the original frames remain in the full local stand capture. Raw frame CSV files and collapsed CPU stacks are gzip-compressed in this evidence branch. Full JFR recordings and retained worlds stay on disk under `/mnt/data/projects/incoming/mod/run/wt-PERF/mod/run/perf-work/runtime/data/runs/inc-100-fixed`; the full stand capture is `/mnt/data/projects/incoming/mod/run/stand/out/perf-559-final`. Nothing was deployed or merged. The next step is a fresh independent review; MT-B6 remains separate.

Diagnostic reference classes copied from the base revision compare thermal outputs and dust lifetime/extinction/bounds, including backward age queries. They are evidence scripts only, not production/test sources in the PR. Permanent tests cover future-terrain reads, wind isolation/rewinding and support request order; existing physics/containment tests remain the physical correctness checks.
