# Registered finite-chunk measurements

Final accounted probe: `pipeline-accounted.log`; exact source/instrumentation beside it. Two passes per yield/fixture; no cold reads. Values below are observations, not a full-map deadline or statistical p95 estimate.

Earlier #560: **7.68 ms/chunk apply-only**, versus **10.10 ms/chunk median including support** (7.93–39.82 ms). Changed block counts and scene history differ; do not read this as equal-work speedup/regression.

| Yield / dense sample | Changed blocks | Snapshot + blast + collapse + finish (ms) | Apply only (ms) | Apply + support (ms/chunk) | All material continuation (ms) | Largest support step (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 15 kt / 1 | 4077 | 244.24 | 23.60 | 23.60 | 132.52 | 0.0000 |
| 100 kt / 3 | 3874 | 130.71 | 13.71 | 24.91 | 115.42 | 0.2232 |
| 1000 kt / 5 | 3772 | 65.48 | 14.31 | 21.05 | 106.86 | 0.0855 |
| 15 kt / 7 | 3730 | 58.53 | 9.49 | 15.37 | 129.38 | 0.0558 |
| 100 kt / 9 | 3653 | 57.40 | 9.25 | 16.31 | 112.61 | 0.0962 |
| 1000 kt / 11 | 3572 | 57.61 | 8.92 | 14.60 | 100.24 | 0.0522 |

Support calls are included in material continuation; do not add both columns. The sparse controls changed zero blocks and therefore do not establish support throughput. The first dense pass has no surviving support component and records zero graph steps; it is excluded from the live-support summary.

| Yield / dense sample | Immediate pending pieces / support seeds | Peak ledger estimate (MiB) | Peak observed shared reservation (MiB) | Live support graph peak (KiB) | Heap sample at completion (MiB) | Active ledger checkpoint (ms / MiB NBT estimate) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 15 kt / 1 | 2120 / 4040 | 17.87 | 18.33 | 0.00 | 854.29 | 13.26 / 8.16 |
| 100 kt / 3 | 2023 / 3874 | 17.13 | 17.60 | 17.53 | 561.44 | 6.14 / 7.81 |
| 1000 kt / 5 | 1961 / 3772 | 16.68 | 17.15 | 21.69 | 545.84 | 5.95 / 7.58 |
| 15 kt / 7 | 1940 / 3730 | 16.49 | 16.96 | 19.56 | 569.74 | 5.37 / 7.50 |
| 100 kt / 9 | 1901 / 3653 | 16.15 | 16.62 | 18.09 | 581.73 | 4.96 / 7.35 |
| 1000 kt / 11 | 1850 / 3572 | 15.79 | 16.26 | 22.16 | 551.24 | 4.04 / 7.17 |

Across the five dense observations with live support graph steps, apply + support median is **16.31 ms/chunk**, range **14.60–24.91 ms**.

The ledger graph is sampled inside each production support step; post-apply ownership is sampled before the next world tick. The shared counter includes the context attached to `NuclearWorld`. Heap samples include the entire GameTest JVM and are not peak or allocation-rate measurements. The 512 MiB limit concerns admitted nuclear working reservations, not JVM heap. Checkpoint timing measures NBT construction, **not durable disk flush**. Original raw timing logs include concurrent build/stand activity; these small samples cannot establish an isolated throughput ceiling.

The timed dense preparation stage is dominated here by fracture/blast and collapse solving; the cumulative material lifecycle is larger than plan apply alone. This does not establish what dominates a complete cold map.

**Complete acceptance-to-flash time at 15 / 100 / 1000 kt: not measured. The 1 Mt 10–15-minute target is not passed.** There is no real whole-map time figure to report from this probe. The earlier plan's 523,988-chunk workload and 10.10 ms sample would multiply to 88.2 single-thread CPU minutes, but that is only a sensitivity calculation: changed density, reuse, live-tick share, I/O and publication differ. It is deliberately not offered as a prediction of actual preparation time.
