Current post-reboot evidence: [post-reboot-20261104/EVIDENCE.md](post-reboot-20261104/EVIDENCE.md). The historical checkpoint below is preserved with its original limits.

# MT-OPT working evidence — stopped, not acceptance

4 November 2026. Draft PR [#568](https://github.com/zentixua/incoming/pull/568).
Work stopped for the owner's clean team restart. No full-zone, server-heap, network-delivery or
1 Mt deadline acceptance is claimed. Code is WIP; no merge/release.

## Existing facilities considered

| Facility | Use / decision |
|---|---|
| Minecraft IOWorker / region storage | Existing asynchronous, indexed chunk pages; reused for disposable prepared plans/snapshots. |
| [SQLite WAL](https://sqlite.org/wal.html), [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | Durable indexed alternative, but would add a native JDBC dependency when region pages already supply this cache's indexing. |
| JDK JFR | Existing profiler used for CPU samples; no extra profiler dependency. |
| NeoForge IOUtilities | Existing ordered atomic SavedData writing; reused without another whole-ledger deep copy. |

Custom code encodes Incoming's exact plan/material fields and bridges their bounded lifetime to
these facilities; no physics library or new mandatory mod was added.

## Material save measurement

`OptBench.java`, `mt_opt_matter`, 100,000 stone pieces plus original/support-source journals.
Same deterministic input; medians of samples 2–7. CPU-only local GameTest, not netcup.

| Stage | Original | Compact but rebuilt at save | Immutable record pages |
|---|---:|---:|---:|
| Construct save NBT | 45.886 ms | 9.146 ms | 0.131 ms |
| Extra NBT deep copy in benchmark | 152.517 ms | 1.643 ms | 1.879 ms |
| NBT `sizeInBytes()` estimate | 308.70 MB | 18.11 MB | 18.11 MB |
| Thread allocation, save + copy | 597.27 MB | 36.26 MB | 18.26 MB |

The final production override does **not** do that extra deep copy: complete FIFO pages are immutable,
partial ends and inventories are copied. Compression/disk completion is not included in these timings.
Heap values in raw logs are JVM samples including garbage, not live-set or peak-heap measurements.
`matter-pages.log` also records 18 passing material/review/measurement GameTests. `RuinRecordsTest`
passed FIFO/snapshot immutability tests in the same job. Later restart wiring is not covered by that run.

## Planning diagnostic, not a paired speedup verdict

`fixture-before.log` and `fixture-after.log`: median samples 8–31; fresh context each iteration,
all nine window fractures computed cold, then Collapse, finish, plan codec. The two GameTest launches
placed fixtures at different absolute coordinates and produced slightly different plans. Therefore
these rows are exploratory, **not a controlled before/after acceptance comparison**. A fresh run
must time frozen ReferenceCollapse and current Collapse against the very same immutable grid.

| Stage | Dense before → after, ms | Natural before → after, ms | Memory observation |
|---|---:|---:|---|
| Read / snapshot | Not measured | Not measured | Needs isolated timing and allocation |
| Plan: nine cold fractures | 78.860 → 76.898 | 33.463 → 31.106 | Includes neighbour work, not amortized contiguous prep |
| Collapse | 18.377 → 15.469 | 8.057 → 5.276 | Total fixture allocation: 27.73 → 21.34 MB dense; 11.54 → 6.93 MB natural |
| Finish | 1.414 → 1.461 | 1.155 → 0.946 | Resident plan ~61 KB dense / 8 KB natural |
| Write / support | Not measured here | Not measured here | #565's 16.31 ms is a different fixture/workload |
| Matter continuation | Not measured | Not measured | Compact queues tested for behavior, not throughput yet |
| Save | See fixed 100k-record table | See fixed 100k-record table | Not normalized per chunk |
| Server LOD / LSS | Not measured | Not measured | Client queue untouched |

`exact.log`: six passing tests, including complete output-array comparisons to frozen main Collapse
on dense/natural samples at 1, 6, 20 and 50 psi, plus exact plan/snapshot round trips and file reopen.
The fast vertical-support branch still needs a test proving it is exercised, plus partial-block,
floating/border/foliage corpus coverage. Neither per-sample full-path timing nor 1 Mt projection is complete.

Four JFR event exports preserve before/after CPU/allocation/GC samples. Raw recordings remain local; environment/system-property events are excluded from the exports. A separate B7 1 Mt JFR was inspected earlier: 15,478
Collapse-inclusive samples; detached 7,182, support 5,738, distances 4,622, property lookup/palette
access recurring heavily. These overlap and cannot be summed into elapsed time. Its large JSON
export remains local (`mod/run/mt-opt/baseline-jfr.json`, about 4.3 GB), not committed here.

## Verification boundaries

- `storage3.log`: 8 passing storage/lifecycle tests before later archive-restart changes.
- `exact.log`: 6 passing codec/comparison tests before later archive-restart changes.
- `matter-pages.log`: 18 passing tests and RuinRecords unit tests before later archive-restart changes.
- `build.log`: latest Java/devtest/test compilation and jar assembly completed; build daemon terminated
  during unit tests, with a shutdown/interrupt log. Full build is **not passed**. No rerun after stop.
- No GPU/client job, Crafty test server, protected world, server heap or player setting changed here.
- No independent review, full final CI acceptance, bounded whole-zone memory proof, actual restart
  integration test, netcup before/after throughput or full-stage end-to-end 1 Mt result yet.

For continuation use the local MT-OPT-handoff.md and current TEAM/BOARD/live rules, not this historical
measurement package as an authorization source. OptBench is deliberately excluded from the production PR.
