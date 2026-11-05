# Actual 1 Mt after section-journal and FAR-service corrections

**Runtime acceptance failed.** In the last complete sample of the fixed 480-second post-flash window, 451 of 453 currently loaded watched chunks have their ruin mark; two remain overdue by up to **8,890 ticks**. Successful applications alone reach 6,394 ticks of lag and exclude those pending chunks. No player installation or release follows this result.

## Exact inputs and measurement

Production source `56f735f2b6ba7c5c30fec8d148359c75c0373372`, successful [CI 41961080047](https://github.com/zentixua/incoming/actions/runs/41961080047), tree `4358cac2099d4c680101373b16fde3c28dc4d8aa`. Diagnostic composition `a0babee626ad07cacc1d7d41dbe71cccfeadcc7b`, successful [CI 41961138222](https://github.com/zentixua/incoming/actions/runs/41961138222), tree `2349edb03f917f37981791208ebfbbdd3a0a978b`. These full CI results are source checks, not runtime approval.

The measured JAR is TEST ONLY: 23,544,074 bytes, SHA256 `ef18d01008fe610758309f35a381de775d18595790aaee995a643e65e17dc2e5`, version 3.11.0/protocol 55. It must never be installed for players. The pristine source level SHA256 is `f8563e2c4470bc17f10adf4f68d4eeb45ec486747b162f9d22cb0d54bfb57073`; old trials and the final stopped world remain preserved privately.

Native dense-city test: 1,000 kt ground burst at 3104.5/63/560.5, eight-GiB heap, 12 processors, unchanged 30-ms common work setting, GAME wave 0.25/effects scale 1. Camera body −96.5/150/−816.5; eye Y151.62; yaw −66.72369/pitch 1.4302; native tracking view centre −7/−52, distance 10. Preflight independently checked health 20, spectator, body/listener/camera identity, the actual native city image and an active `IrisVoxyRenderPipeline`. Resolution 2560×1600, normal full pack/shaders. No desktop interaction.

Native UTC times on 5 November: baseline 03:38:15.944, request 03:39:15.944, launch 03:39:32.263, flash 03:41:02.224. Request→launch 16.319 s, flight 89.961 s, request→flash 106.280 s. The post-flash interval ends at 03:49:02.224. Driver receipt is later than the native log and is not substituted for it. Baseline/preparation/flight/flash/wave/settling remain separate. No screenshots, hash walk, profiler or reflective probe occurred inside these clean phases.

## Current-cohort and world progress

All 480 one-second samples are retained: 435 complete, 45 time-cut partial, zero progress drops; cadence 20 game ticks. A partial census is a lower bound, never inferred complete by combining prefixes. Event history is established before phase cropping.

- Last complete sample at +479.167 s: 453 affected loaded watched chunks, 451 scarred, two pending/overdue, maximum pending age 8,890 ticks.
- Successful scar commits in the observed lifetime: 1,447 = 1,296 prepared + 151 fresh; 451 watched at commit and 996 not watched at commit. This is actual successful plan application, including plans that may be unchanged, not a count of planner-method calls or arbitrary block destruction.
- FAR plans reach 7,241, then remain unchanged for an observed 452.012 s. World application continues during that interval, so this is not evidence that every subsystem is deadlocked.
- No prepared-owner drops or clear epoch occur during the fixed interval. Late unload counters and pending cohorts remain in the raw data; no cohort was narrowed to obtain a pass.

[Chronology](chronology.json) retains the original complete/partial rows near selected ages. [Complete summary](progress-clean-summary.json) retains missing-data and lifetime qualifications. The complete raw progress history is available below.

## One separate post-clean snapshot

Normal `save-all flush` completed natively at 03:49:18.252 UTC; driver complete was recorded at 03:49:19.059. The guarded one-shot probe ran at 03:49:20.312, before the preparation's ten-minute expiry, and cost 68.284 ms **outside** the performance interval. Exact JAR/PID/start/cwd and observer JAR SHA256 `5d92d0940b24709e4f6d931c460125ae0d9aa94cec9773ba2c4c1aa887408439` were guarded. This is the first successful native probe6 attach.

All 3,310 pending queue jobs were visited. Two watched jobs were FULL and overdue, maximum age 8,964 ticks. One resident worker was active with one queued task. The FAR frontier was at chunk233/62 with none of its 25 snapshots stored in that context. The material ledger was neither loading, writing nor failed at the snapshot. These facts do not identify the exact first stale cell or prove a single cause for all earlier delays.

The compact original-state journal held 2,295,804 original entries in 4,316 outer section pages; its maintained `pageBytes` was 55,117,320. The outer map capacity was 8,192. Outer pages and global entries are different quantities; this probe does not enumerate per-page tables or measure total retained JVM heap. Native FULL getters can warm cache/profiler bookkeeping; no absolute zero-side-effects claim is made. [Reviewed observer source/scope](observer-review.md), [exact source](OwnershipAgent6.java).

## Phase performance and resource coverage

Milliseconds, p99 / maximum. Frame intervals include adjacent consecutive rows; render-bracket CPU is not whole-cycle CPU. Tick work excludes between-tick tasks. These results describe this failed run, not a performance acceptance.

| Phase | Server tick work | Frame interval | Frames >100 ms |
|---|---:|---:|---:|
| baseline | 17.916 / 22.814 | 35.654 / 65.679 | 0 |
| prepare | 59.466 / 89.739 | 31.740 / 39.951 | 0 |
| flight | 46.951 / 139.496 | 32.712 / 42.430 | 0 |
| flash | 70.854 / 101.192 | 56.027 / 129.839 | 1 |
| wave | 82.882 / 558.937 | 95.849 / 163.911 | 24 |
| settle | 70.387 / 156.049 | 81.466 / 180.588 | 17 |

VPS monitor started before server startup, sampled at 0.5 s, used no class histogram and retained a normal-stop guard. The clean interval has 1,279 samples, minimum MemAvailable 18,821,775,360 bytes (17.53 GiB), maximum sample gap 564 ms, no reserve stop or API error. [Host coverage](host-coverage.json). Client process occupancy and the verified performance power profile are in [client occupancy](client-occupancy.json). No competing heavy job/client ran during the clean interval; source-reading agents remained separate. The sampler still records foreign GPU activity (peak19% SM); this is not a zero-contention GPU benchmark.

Clock brackets: server minus client +0.150±26.404 ms before, +3.731±26.281 ms after. They overlap; analysis conservatively encloses both. Client log wall time has a different timezone and is not used directly to align frames; passive stand epoch anchors are used. Tick footer is complete with zero drops; consecutive frame pairs are complete and GPU ring waits zero; TCP sampler errors zero. TCP counts all attributed Minecraft stream traffic, not only this mod. The owned client ended through its normal wrapper TERM cleanup (143); the passive stand `observe-stop` callback is absent, so no complete JVM shutdown-tail claim is made.

## Preservation and remaining work

Normal server stop requested 03:50:17.158, stopped 03:50:38.583; immutable cut A completed 03:50:53.358. Client and GPU were then released. Runtime admission was revoked on both hosts while preserving the admitted pin and all earlier failed cases. No wider timeout, raised cap, altered world epoch, discarded material, new renderer or reduced watched cohort was used.

A private bounded corpus retains the original/stopped 30 chunks around the two remaining watched jobs plus all 30 archived snapshot and 30 plan records. All 120 compressed-record/decompressed-NBT hashes and three geometry/progress metadata files were verified. No private world, block-entity cargo, material file or machine launcher is included in this evidence branch. Ongoing diagnosis must distinguish early working-set/service refusals from late source-validity retries. A later corrected integrated run, normal 100 kt, traversal/save/rejoin/native geometry and final production-JAR checks are still required.

[Native preflight](preflight.png) shows the city; [post-clean native image](post-clean.png) is heavily dust-obscured and does not certify all rendered geometry. All screenshots are exact owned nested-window captures.

Raw evidence: [progress](raw/progress.log.gz), [tick times](raw/ticks.csv.gz), [ledger save phases](raw/ledger.csv.gz), [one ownership snapshot](raw/ownership-post-clean.txt.gz), [driver](raw/driver.jsonl.gz), [VPS monitor](raw/vps-host.jsonl.gz), [frame slice](raw/frames.csv.gz), [TCP counters](raw/tcp.jsonl.gz), [client host sampler](raw/client-host.jsonl.gz), [checksums](raw-manifest.json). The frame slice includes its preceding/following pair rows; complete originals remain privately preserved. [Phase metrics](phase-summary.json) retain methodological limitations.
