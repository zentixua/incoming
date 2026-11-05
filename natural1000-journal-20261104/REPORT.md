# Actual 1 Mt: dense original-state transition and delayed progress

**FAIL.** The corrected source still misses timely player-visible destruction. Production `3efac357cc368045f6fe821ac02c561ab99af4c8` and diagnostic `d553892fd4839841af89045f03d6f12d73cebd0c` passed exact CI41930347079/41930381376. Diagnostic JAR SHA-256: `1aa74fe7edd8bffe5544b72abcdeb1be49cf8ccbf0e2739ec0f2038068da36c9`,23,531,857 B, protocol 55. Local combined 1848 unit/7 targeted GameTests and the independently reviewed source components are component evidence, not runtime readiness. This JAR is TEST ONLY; its runtime-admission pin is now revoked.

Fresh preserved 1000 kt city-domain copy,8 GiB heap/12 active processors, GAME wave 0.25 / effects 1, fixed native camera(-96.5,150,-816.5), view centre(-7,-52)/distance 10. Pre-login spectator protection avoided the preceding trial's recovery. Healthy player, matching connection/player/camera, loaded city and actual IrisVoxyRenderPipeline were verified before the gate. The client started from the preserved mod/Connector-cache base with no world/LOD cache. That and the later performance power profile prevent an unqualified timing comparison with the long-warmed previous trial.

## Clocks and complete current-view evidence

All dates below are UTC on 4 November 2026. Command23:23:54.637, native launch23:24:10.745, native detonation#2 at**23:25:40.705**:16.108 s preparation,89.960 s flight,106.068 s request-to-flash. The clean window ends**23:33:40.705**,480 s from the native flash, not the approximately one-second-later polling receipt. Pre/post SSH clock brackets overlap: server-minus-client+14.415±25.978 ms and+0.203±26.810 ms.

The unchanged cooperative1 ms allowance now produces **434 complete current-view samples out of 480** ; 46 explicitly report time cutoff. All use native views, at most 529 square candidates/453 loaded watched chunks. No log drops, malformed rows or >20-tick sample gaps. These are sampled current cohorts, not continuous membership or renderer fences. The longest measured census is3.146 ms; cooperative limits are not hard wall-time preemption or zero overhead.

- At+41.555 s a complete cohort has 81 scarred and 372 pending, all 372 overdue, maximum 190 ticks. At+153.556 s it has 325 scarred/128 overdue, maximum 2430 ticks.
- At+353.572 s the complete cohort reaches 430 scarred/23 pending. It remains 430/23 through the last complete clean sample at+479.555 s, maximum**8933 ticks** pending lag.
- There are 552actual scar commits in the sampled unchanged lifetime:334 prepared+218 fresh,430 watched and122 unwatched at commit. Successful watched latency reaches 6354 ticks; it omits the 23still waiting. Early pending-without-job observations include not-yet-eligible work and are not automatically lost jobs.
- Far preparation is**3290** in all 480 post-flash rows,478.999 s observed unchanged span. Its last earlier count is 3274 at−0.479 s. This does not mean absolutely no work: during the late 120 s, planner completions and stale attempts keep increasing without another scar commit. Invocation counts are not useful material advances or deposits.

## One separately timed ownership snapshot

After the clean window and normal save receipt, the single snapshot at**23:34:31.910** scans all 3100 queued jobs and finds 23 watched/FULL/overdue, maximum 9789 ticks. Cost **53.344521 ms**, entirely outside clean performance. It precedes preparation expiry23:35:40.705. Native FULL lookups can update vanilla lookup caches/profiler counters; no plan-future payload collection, admission, eviction, world load or gameplay mutation was requested. Independent RPROBE5 review explicitly qualifies this scope.

The worker pool is 1 resident/0 active/0 queued. Demand-plan reads are 0. Deferred support sources are 0. Actual support tables contain 75,979 contacts and**1,570,626original-state records**. The original table has 2,097,152 slots and maximum fill 1,572,864, leaving 2238 entries before its next expansion. This is different from the previous historical source-credit/read-retention failure.

Independent source/raw reconstruction finds that adding at least 2239new originals raises the current projected table charge by 64 MiB. The watched 22144-material-cell head requires 117,662,848 B of capture/support/FIFO admission before cargo; a strict partial resident lower bound is 445,924,545 B. Their sum 563,587,393 B exceeds the536,870,912 B ceiling. This explains that refusal under the implemented accounting; these numbers are **ownership estimates, not measured total live heap**. Real table arrays were observed, but not every conservatively charged byte is a live object.

The material counts balance: 1,606,151 released =628,586 deposited +284,759 dispersed +692,806 pending; stranded 0. Remaining material/support can still change ownership, so no permanent deadlock is proved. A completed task was only three ticks old at the snapshot; it is not evidence that the repaired completion collector starves again.

The late expansion does not explain the entire early failure. Before the wave reached the player, resident/admission estimates already approached or exceeded the ceiling, and later stale/replan activity continued. The next correction must cover the actual representation and full transition, service order, original-state validity and compatible streamed reload. Merely lowering the late peak does not establish front-time readiness. The original saved material is preserved for a real-input reproduction; it is not replaced by artificial equal counters.

## Clean performance

| Phase | Server tick p99 / max,ms | Frame interval p99 / max,ms | Frames >100 ms |
|---|---:|---:|---:|
| Baseline 60 s |17.450 /20.223|35.005 /83.466|0|
| Preparation |47.071 /150.686|33.167 /36.198|0|
| Flight |42.281 /212.289|33.619 /82.961|0|
| Flash 0–30 s |75.596 /111.726|59.440 /111.383|2|
| Wave 30–120 s |72.109 /420.580|78.704 /335.898|8|
| Settlement 120–480 s |67.898 /123.577|82.063 /151.630|25|

Heap-used peak 7,200,226,864 B is not a live-set measurement. Tick work excludes between-tick work/sleep; frame interval includes client work outside rendering. The normal server tick footer is complete, no drops; frame pairs consecutive, GPU ring waits 0, TCP sample errors 0. TCP covers attributed Minecraft traffic, not Incoming-only bytes. The client was deliberately terminated through its owned wrapper after the post-clean capture, exit 143/signals[15]; no native stand-stop footer is claimed.

VPS monitoring spans 1993 samples, maximum gap 550 ms, minimum sampled MemAvailable 19,548,545,024 B (18.21 GiB), with no reserve-stop event. No continuous every-instant heap/headroom guarantee follows from sampling. No profiler, receipt hashing or screenshot readback occurred inside the clean interval.

## End state and next gate

Stop requested23:35:13.324, server stopped23:35:28.641, immutable cut completed23:35:38.970. Client/compositor stopped, GPU released. The post-clean native image is heavily obscured by dust; it is retained as observed, not used to certify every ruined section. Worlds, material/cargo files, original logs and failed artifacts are preserved privately. Public evidence contains selected raw telemetry/checksums/images and technical review scope, not personal launchers or full worlds.

No 100 kt acceptance run, player installation, main merge or public release follows this failure. The next source owner must demonstrate bounded dense progress and compatible real-ledger reload, then independent review/exact CI, corrected actual 1000/100 kt, traversal/save/rejoin/ordinary LSS/native geometry and performance. Existing accepted solver/persistence components are reused unless causal evidence requires changing them. No discarded authoritative material, larger cap, weaker pending cohort or longer timeout substitutes for the player goal.


Raw evidence: [progress](raw/progress.log.gz), [ticks](raw/ticks.csv.gz), [ledger observations](raw/ledger.csv.gz), [single ownership snapshot](raw/ownership-post-clean.txt.gz), [driver](raw/driver.jsonl.gz), [VPS samples](raw/host-monitor.jsonl.gz), [frame interval slice](raw/frames.csv.gz), [checksums](raw-manifest.json). The slice includes one preceding and one following frame; the full original remains private. [Complete phase metrics](phase-summary.json), [current-cohort summary](progress-clean-summary.json), [host coverage](host-coverage.json).

[Preflight](preflight.png) and [post-clean native image](post-clean.png) are unedited captures of the owned nested window. [Independent chain assessment](independent-chain-review.md) separates early and late causes. [Observer review](observer-review.md), [scope clarification](observer-scope.md), [source](OwnershipAgent5.java) and [class hashes](reviewed-class-hashes.json) preserve its provenance: the comment-only clarification changes no compiled class bytes. World/material/cargo reproduction inputs are deliberately excluded from this public branch.
