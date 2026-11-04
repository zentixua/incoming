# Actual 1 Mt: consumer progress failure

**FAIL.** Source `41c718adcc211076e28e4909343fad7e03cdeff6`, test-only JAR SHA-256 `fd2a79c9b171c75f5cf6bb4247dbe58cae2cab4f3f6b17da5a675d2a45410d3f`, protocol 55. Its production runtime is `476355384f944d094856348c31ea0dd6cab31aa9`; production `452f1f78af23779200baf4aef804e8e92d6dca40` adds only devtest changes. This is a fresh preserved 1000 kt city-domain copy, 8 GiB server heap, 12 active processors, 30 ms work budget, GAME wave scale 0.25, effects scale 1. Neither a release nor player installation follows.

The ordinary command was issued at **20:56:36.165 UTC, 4 November 2026**. Native launch was 20:56:52.505, native detonation #2 was **20:58:22.496**: 16.340 s preparation and 89.991 s flight, 106.331 s request to flash. The fixed clean window ends at **21:06:22.496**, exactly 480 s after the native flash; polling receipt timestamps are not used as the explosion clock. The [driver](raw/driver.jsonl.gz) includes the exact native lines and later normal save receipt.

## Failure and ownership

During the clean window all 480 event-observed pending samples were explicitly incomplete (`cutoff=time`); none is a full-view success. Positive observations nevertheless prove failure: up to **401 overdue loaded watched chunks**, maximum observed pending wave lag **8,955 ticks**, and successful watched applications up to **8,938 ticks** late. The far-plan count stayed at **12,174** in all 480 samples, an observed unchanged span of 478.960 s. Fourteen demand reads and 58,720,256 asynchronous bytes remained at the last clean sample. These are measured delays, not a permanent-deadlock claim. [Full-lifetime raw progress](raw/progress.log.gz), [phase-scoped summary](progress-clean-summary.json).

A single separately timed ownership probe at **21:07:29.902**, before preparation expiry, inspected **all 2,965 queued jobs** and found **84 currently loaded watched overdue chunks**, maximum **10,264 ticks**. The probe took **73.749011 ms** and is outside the clean performance window. It observes current membership, not continuous player waiting or rendered geometry. Native getters may update internal caches/profiler state; no world loads, admission or gameplay mutations were requested. [Full probe output](raw/ownership-post-clean.txt.gz).

That snapshot found nine resident workers, zero active/queued, and 32,474 completed tasks. Material had 118,766 moving records, 11,417 support contacts and 44,361 original contacts, but no queued support sources. The scalar `supportBacking` remained **190,669,824 B**: source inspection shows it retains a historical estimate mixing future credits and backing. This is **not** a measured live heap value or proof that all of it is reclaimable. The disjoint recorded ownership subtotal was 505,804,091 B of the 536,870,912 B limit, leaving at most 31,066,821 B. Two sampled completed plans required at least 82,043,520 / 51,008,704 B for material admission before additional pages/cargo. This reconstruction is not an atomic replay of the reserve call.

The four recorded material counts balance: 719,835 released = 465,903 deposited + 135,166 dispersed + 118,766 pending; stranded count zero. This single live snapshot is not save/rejoin acceptance. Completed demand ownership and a completed unseen result also require independent collection and consumer handoff; their exact temporal contribution cannot be recovered from this one snapshot. Subsequent corrections need discriminating tests and another real run.

## Clean phase measurements

| Phase | Server tick p99 / max, ms | Frame interval p99 / max, ms | Frames over 100 ms |
|---|---:|---:|---:|
| Baseline, 60 s | 16.432 / 58.205 | 37.093 / 45.351 | 0 |
| Preparation | 55.560 / 82.625 | 37.537 / 38.924 | 0 |
| Flight | 38.552 / 85.225 | 38.114 / 235.911 | 2 |
| Flash, 0–30 s | 66.247 / 121.037 | 57.152 / 270.707 | 1 |
| Wave, 30–120 s | 75.153 / 436.107 | 81.924 / 144.805 | 6 |
| Settlement, 120–480 s | 69.750 / 369.605 | 95.020 / 158.255 | 43 |

[Phase metrics and scope](phase-summary.json), [server ticks](raw/ticks.csv.gz), [client frame slice](raw/frames.csv.gz), [ledger observations](raw/ledger.csv.gz). The frame slice preserves one preceding and one following row around the measured interval; the complete original file remains preserved privately. Tick work excludes between-tick work/sleep; render CPU excludes client tick/task work; GPU elapsed includes submission gaps. Heap-used peak 6,750,275,472 B is not a live-set measurement. TCP observations cover attributed Minecraft sockets, not only this mod.

Tick footer is complete with zero drops; event samples have no >20-tick gaps. Client pairs are consecutive, GPU ring waits zero, TCP sample errors zero. The client later reached its configured one-hour wrapper timeout and exited 143, so there is no normal client stop footer. This happened after the clean interval. Clock brackets: before −28.942 ±29.128 ms, after −45.732 ±26.173 ms (server minus client); the intervals overlap without assuming symmetric transport latency.

## Validity and next gate

- [Preflight image](preflight.png) is a verified capture of the owned nested client window. An earlier unattended test-player death was repaired before opening the gate; invalid preflight attempts and their logs remain preserved. No earlier strike occurred. The accepted camera/player identity, healthy spectator state and loaded city were checked together.
- No profiler/ownership attach, screenshot readback or receipt hashing occurred inside the clean window. The preflight is not a post-destruction frame or a renderer-completion fence.
- Server stop was requested 21:14:31.398, completed 21:14:45.666; the preserved cut completed 21:14:57.895. The extended diagnostic tail must not be merged into the 480 s clean window. The test server and client are stopped and the GPU lease released.
- No continuous VPS host-memory sampler was started for this run. Startup headroom alone cannot prove the required reserve throughout the trial. The next trial must record it. Laptop power was not directly sampled during this interval; a later change to performance mode prevents an unqualified timing comparison.
- Every partial census is a lower bound; repeated prefixes do not establish complete coverage. A reviewed native-view traversal refinement retains the same 1 ms allowance and still needs real-scene coverage/cost verification.
- The failed JAR's runtime-admission pin has been revoked. Dense composed RED/GREEN regressions, independent review, exact CI, corrected real 1000/100 kt, normal save/rejoin, ordinary LSS delivery and actual visible geometry remain required. No budget increase, omitted pending chunks or diagnostic player JAR can substitute for them.

[Raw file checksums](raw-manifest.json) identify compressed and original bytes. Worlds, credentials, machine launchers and full private client/server logs are not published here. Evidence lives on an orphan branch, outside the production diff.
