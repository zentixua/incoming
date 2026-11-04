# PERF-C2 AsyncParticles package — independent review

Reviewer: Codex, fresh `gpt-6.1-sol` / `xhigh` session PERF-C2-AP-RV, 2026-11-04.

Reviewed Open Skies `68e77a75c1d4172c5a86e0d239ec3a09cc22a0f0` → `ba165bf90197dcb618652848f361cc1ec91270f9` (PR #50, branch `codex/asyncparticles-readiness`). Read TEAM/BOARD/current live/context and repository rules. The author's PR description, reports, inboxes, conversation, `initial-pair.json` and `PERF-C2-report.md` were not read. No source changes, public comments, merges, clients or heavy/GPU jobs were performed.

Evidence abbreviations:

- **E** = `/mnt/data/projects/incoming/mod/run/wt-PERF-C2/mod/run/perf-c2`.
- **S** = `E/asyncparticles-source`, checked-out commit `602f8f18b89e9585506611604f1163512f049ccf`.
- **H** = `/mnt/data/projects/incoming/mod/run/wt-PERFA/tools/stand`.
- **A** = `/mnt/data/projects/incoming/mod/run/perf-audit/data/scripts`.

## Verdict and blocking finding

**Request changes for this exact package artifact.** The performance result supports continuing preparation, but I found a deterministic Sable light-scan defect in the published jar. The supplied night capture does not validate the affected case. No crash was established by this review.

### B1 — The published Sable scan limiter stops after one iteration

The new dependency in `pack/mods/asyncparticles.pw.toml:6` selects AsyncParticles 21.1.4.10. Its `MixinMixinParticle_LightScan.limitHeightScan` reads `@Share("steps")`, writes `steps - 1`, then returns `steps >= 0 && steps < maxScanSteps`. Nothing initializes `steps`; only the separate `maxScanSteps` reference is initialized. A shared int starts at zero ([MixinExtras contract](https://github.com/LlamaLad7/MixinExtras/wiki/Share)). Therefore successive originally-true loop conditions become **true, false**, regardless of a normal positive world height.

This is not an inference from the current upstream branch. I disassembled the exact SHA-512-verified proposed jar: `neoforge.fun.qu_an.minecraft.asyncparticles.client.mixin.compat.sable.MixinMixinParticle_LightScan` contains the same `get`, `iconst_1`, `isub`, `set`, and negative-value rejection. It is listed in `neoforge-asyncparticles-common.mixins.json`; `AsyncParticlesMixinPlugin.shouldApplyMixin` enables the Sable package when Sable is loaded. Source: `S/common/src/main/java/fun/qu_an/minecraft/asyncparticles/client/mixin/compat/sable/MixinMixinParticle_LightScan.java:24–52`, plugin line 87.

The exact installed Sable 2.0.6 `ParticleMixin.sable$checkSubLevelLightColor` has two corresponding downward scans, for tracked and nearby sublevels. They start one cell above the local particle position, seek a non-air block, and apply sublevel skylight only if one is found (bytecode offsets 224–304 and 629–707). A solid block two or more inspected cells away is now missed. For example, a world-space particle inside a nearby dark craft, still untracked and several cells above its floor, can retain world skylight instead of sublevel skylight.

The default light cache does not prove this path irrelevant: GPU-compatible particles deliberately leave the ordinary light-cache switch disabled (`ParticleHelper.doFirstRefresh`, lines 51–77); `MixinTextureSheetParticle.asyncparticles$getGpuLightCoords`, lines 72–82, subsequently calls `getLightColor`, and the OpenGL renderer calls that getter (line 282). The Sable wrapper returns the original light unchanged for a particle with neither a tracking sublevel nor a previous tracked sublevel (`compat/sable/MixinParticle.java:75–84`). Thus the original nearby-sublevel scan remains relevant. This is a static correctness finding; the magnitude of the visible regression was not measured in this review.

**Required resolution:** use an artifact with a correct bounded scan, or provide a focused runtime check that resolves this exact reachable case before accepting this package. The check should cover daylight outside versus a dark opaque Sable interior, particles initially created in world coordinates, and the default GPU route. Merely turning off `sable.fixParticleLights` does not disable this independent scan mixin. No external author contact or third-party source modification is authorized here.

Disassembly retained locally in `/mnt/data/projects/incoming/mod/run/wt-PERF-C2-AP-RV/mod/run/review/{ap-lightscan,sable-particle}.javap.txt`. These are review evidence, not production changes.

## Package identity and CI

- The exact local jar matches the pack SHA-512 `0b07cdbf6cf88c75e8c5fdedcf5d754579ec37e59294f22a45437cfa02b47e5af621827b3e001135b1f45765c80fb40582944788d63a9a06d2097baf23c9fafe`. Independently fetched [Modrinth version metadata](https://api.modrinth.com/v2/version/qLpoOaqY) agrees on filename, hash, client-only environment, beta status, Minecraft 1.21.1 and NeoForge support. The [source commit](https://github.com/Harveykang/AsyncParticles/commit/602f8f18b89e9585506611604f1163512f049ccf) agrees with that version's changes; the relevant defect was additionally checked in bytecode.
- Embedded NeoForge metadata accepts NeoForge `[21.1,)` and Minecraft `[1.21,1.21.1]`, and rejects Create `<=6.0.9`; this pack has NeoForge 21.1.264 / Create 6.0.10. No missing required dependency was found. `side = "client"` is appropriate; no new registry-bearing content requiring server installation was identified.
- Version 0.1.55, the Russian README row and English release note are present. The pack index hash and every indexed file hash match. There is no shipped AsyncParticles config override. Generated runtime config matches source defaults: SEQUENTIAL async ticks, light cache, GPU rendering on this supported GPU, LEVEL_RENDERING compute stage, weather ticks, 16384 particle limit, RAISE_CRASH failure behavior.
- [CI run 41801977828](https://github.com/zentixua/open-skies/actions/runs/41801977828) is completed successfully for the reviewed head: both `Сборка модов` and `Запуск с нуля` passed. Merge/release jobs were skipped on this PR. That covers fresh installation, loader/startup and the existing server/Flashback boot smoke; it does not exercise the scan case above.

## Independently recalculated performance

I read raw CSV/JSONL, runtime configs/logs and the retained original harness/audit scripts. Recalculation used consecutive raw rows and nearest-rank percentiles, without rewriting the supplied evidence. The following values are milliseconds, except the last row (seconds).

| Metric | main-a1-clean | main-ap-b1 | main-a2 |
|---|---:|---:|---:|
| Baseline frame p50 | 8.261 | 8.409 | 8.236 |
| Active 3–15 s frame p50 | 51.007 | 33.584 | 58.274 |
| Active 3–15 s full-cycle CPU p50 | 49.538 | 32.415 | 55.576 |
| Active 3–15 s GPU timestamp span p50 | 44.328 | 32.540 | 49.768 |
| Entire 0–50 s frame p50 | 16.223 | 16.491 | 16.882 |
| Entire 0–50 s frame p99 | 87.620 | 53.860 | 89.917 |
| Entire 0–50 s worst frame | 208.154 | 193.205 | 192.676 |
| Entire 0–50 s time in frames >50 ms | 11.157 | 3.067 | 13.797 |

This supports a substantial whole-package gain during the particle-heavy phase: active frame median falls approximately 34–42%, whole-window p99 approximately 39–40%. It does **not** establish removal of the worst hitch, a comparable overall-median improvement, or the contribution of any one AP feature.

Workload/identity checks:

- All three plans' `steps` have SHA-256 `7c9dafc243840190f22516743d6a4d238cd9270f148d5f7d7ee6e356d2cd9637`: the same constructed night city, camera, 100 direct native impact commands, ten batches spaced 1.5 s, and 50 s aftermath. These test impacts, not projectile flight/launch equivalence. Actual command entries, native log entries and blast events each total 100. Maximum scheduled-command lateness: 50.877 / 27.029 / 47.020 ms. No failed commands or timeout events were found.
- Every actual manifest-listed jar was rehashed. Incoming is the same `9b95ecb54937de1194649918dcf0229aa3b7e4fdfaea35d19c9bbf6a11437ed7` build attributed to 3be78e33b; stand jar is the same `c3860bdbfbfd04be2b606708b5f04ca8063dfa0351e0e1450cef64aa31bae818`. B adds only AP (`860c7754…` SHA-256). A2's mod map equals A1's. The timing plans differ only in optional probe declarations.
- All raw rows have `shot=0`, with no frame-number gaps. These are uncensored timing runs. Resolution is 2560×1600 on RTX 4060 Laptop / driver 615.78.08, vsync off, cap 260, render distance 12, simulation distance 8, all particles. Iris reports ComplementaryUnbound r5.9.3 **and active=true**. Sodium and effective Iris settings match; Iris file differences are save timestamps.

Clock semantics: `H/.../FrameClock.java` records the previous Pre→Pre wall interval and previous whole-cycle render-thread CPU in the **next row**. Both were shifted accordingly. The summary's `cpu` is only Pre→Post render CPU; it excludes client ticks/packets outside that bracket. `gpu_ms` is a first-to-last GPU-clock timestamp span, including command starvation gaps, **not pure GPU busy time**. Stage timestamps are not independent hardware costs. No causal CPU/GPU mechanism is proved by their movement together.

Host noise: I used `H/results.py:host_in`'s interval-overlap join, not just `quality.json`'s sample-end filter. CPU and GPU coverage are 100% in the measured windows. Foreign CPU peaks are 0.31 / 0.29 / 0.12 cores. A1 has foreign GPU mean 9.12% in 15–35 s (23% peak), exceeding the harness's 5% mean note threshold; its whole-window mean is 5.13%. This weakens A1's late GPU comparison. A2 has zero measured foreign GPU activity and still shows the active-phase regression relative to B. All runs remain on the performance power profile, but observed GPU clocks vary about 1650–2565 MHz and temperatures reach 87°C. These are laptop outcome measurements, not fixed-clock GPU benchmarks or statistically replicated speedup estimates.

## Visual, audio and compatibility scope

Inspected all 16 actual PNGs as paired sheets, and inspected AP's one-second particle and three-second impact originals at larger size. The glass cage is present after assembly, particles/flames appear and decay, rain renders, and the impact destroys the glass with debris and cloud visible afterward. There is no missing entire effect apparent in these sampled views. Random cloud shape, debris and fire locations differ; these snapshots do not establish a regression or exact equivalence by themselves.

The scene is a modest compatibility smoke, with important limits:

- Assembly commands returned success in both runs. The craft is already resting near the ground when particles are shown; particles are spawned at fixed world coordinates above it. There are no controlled trajectories, collision/contact counters, particle identifiers, poses or light values. The scene does not validate particles carried by a translating/rotating craft, entry/exit from its plot, or the dark-interior lighting case in B1.
- A 12 s rain window and one exterior screenshot verify visible rain without a crash, not shelter occlusion or rainfall/particles on moving contraptions. `weather clear` immediately precedes the impact; residual rain remains visible, consistently with weather transition.
- AP compatibility log line 1719 contains `Received a sub-level movement packet for a non-existent sub-level`; the baseline compatibility log does not. It occurs around assembly. No crash follows, and attribution to AP versus the asynchronous assembly/network ordering is not established. Treat it as an observation to watch, not a proven new crash.
- Renderer coverage is Complementary/Sodium/Iris/Colorwheel/Voxy on this NVIDIA machine. The included Sable/Create and ParticleRain compat source paths are real, but the scene does not exercise every Create particle, custom mod particle, shaderpack or driver fallback.
- The runs enter one world and exit the client successfully. Source has particle-clear/reset/wait hooks (`core/particle/MixinParticleEngine.java:47–55`, `AsyncTickBehavior.reset`), but no repeated disconnect/rejoin, dimension change, resource reload or pause/resume stress is demonstrated. No lifecycle bug is inferred from that absence.

Audio was not played on host speakers. Both complete WAVs decode and contain nonzero audio with no full-scale clipping: durations 111.020 / 116.360 s, peaks −6.245 / −5.397 dBFS. Whole-record RMS differs and the recordings contain different silence/startup duration and randomized game events; it is not a sound-equivalence measurement. **Perceptual audio review is unavailable in this session.**

## Reflection/native diagnostics

The first B timing run's four unrelocated `fun.qu_an...Diagnostic` calls each resolve to **`n/a`**, not false. The Forgix NeoForge jar actually uses `neoforge.fun.qu_an...`. Corrected names in `main-ap-compat` resolve successfully: all four flags are false in each of 49 observations.

I waited for/verified the later native run's completed `exit.json`: code 0, end epoch 1793782051.482 (08:47:31 UTC). However, the actual retained `main-ap-profile/stand-plan.json` and `events.jsonl` contain **no Diagnostic probes at all**, corrected or otherwise. Therefore that run cannot be cited as direct flag-false evidence for the 100-impact workload. Its raw native collapsed stacks do contain `AsyncParticleTickWorker-1` through `-6`, unlike the baseline; this confirms worker activity, not all features' uninterrupted activation or CPU savings. Both native recordings are separate instrumented diagnostics, not substitutes for the clean A/B/A numbers. The stock-JFR CPU collapsed exports are empty; they cannot establish absent work.

## Permission and delivery boundary

Preparation and review of this isolated proposal are within the performance task. **Do not merge/deploy this head now.** In addition to B1, current live instructions hold releases for Artem's explicit word. Open Skies `pack/` on main updates player instances on their next launch, and `.github/workflows/build.yml` automatically publishes a new pack version after merge. Thus this composition merge has a live-delivery/release effect. Open Skies AGENTS also reserves adding a required mod for Artem; this metafile has no optional selection. I found authorization to investigate AsyncParticles and prepare release readiness, not explicit approval to install this required package or publish 0.1.55. This is a delivery-permission constraint, separate from the technical finding, and not a request to the reviewer to perform those actions.

Owner's next step: resolve B1 against the exact selected artifact and attach focused evidence; keep the package staged until the existing delivery permission is satisfied. The remaining coverage limits above should accompany any compatibility claim, without expanding into excluded old look complaints.
