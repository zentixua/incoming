# R579: first runtime evidence review

2026-11-04. Codex, Sol xhigh. Earlier production-source and observer-artifact reviews remain unchanged.

## Finding and evidence sufficiency

**The raw A1/B/A2 evidence demonstrates the queue-window accounting correction in this workload.** The candidate accounts the previously omitted non-step window time, with valid runtime hooks, clock carry and reconciliation bounds. No measurement-contract invalidity or new serious queue-accounting fault was found in these recordings.

This finding is narrower than a hard deadline guarantee or general performance acceptance. The candidate still has initial indivisible-unit overruns, a separate cold entry/outer cost, later whole-tick peaks without queue work, and large client-frame outliers. This report gives no merge verdict.

Primary directory: `/mnt/data/projects/incoming/mod/run/wt-MT-L2-BUDGET/mod/run/p2-window-pair`. Read all three raw `stand-out/events.jsonl` and `frames.csv`, plans/manifests/mod bytes, exit files, selected native/series/error console lines, quality.json and window-observer-validation.json. Independently recomputed the numbers below, rather than using window-comparison.json, window-summary.json, summary.json or an author verdict. Also inspected the supplied raw b/a2 postqueue clips; attribution.json was not used. No author inboxes, PR body, transcripts or diagnosis reports read.

All work was lightweight read-only JSON/CSV/hash/zip inspection. No new builds, tests, profiles, exports, client/GPU/server runs, CI/PR operations or source edits. No owned jobs remain. Detached wt-R579 stays clean at f8d6b1408.

## Identity and workload completion

The runtime baseline is eee272309e1c8306952f32b5f6b7ed4192be2e7c, jar SHA-256 `de011b8a93a5b54ea7454c4aff8b329f987b8180f989d6f943fda4740ea69ab9`. Runtime B is f8d6b1408b9f44cd255cb16db7253d73c7bef9b5, jar `ba536ba317bbe8b956ab29b10dd5ebd2c105c9164dacc14b7ebde016bcc208b3`. Their jar payloads differ only in WorkClock.class and UnitQueue.class.

Every arm has 73 top-level mod files, all matching its manifest. A1/A2 mod bytes are identical; B changes only Incoming. The installed helper in every arm is the previously reviewed frozen `5c8f4f7963fbe1b3371e081bfc3b1dafe18f30857a8f5aea29eefae312fb7f37`; its nine source hashes still match. No AsyncParticles filename is present. All plan bytes share SHA-256 `929c558e36cf590dfd3f24a1e8ffb60b50809c9dffef2fe30feca2c9b76495dc`.

The actual plan builds a 49-house artificial city at night, then directly invokes 100 `perfa impact incoming-drone` strikes: ten at a time, at delays 0, 1.5, …, 13.5 seconds, with fixed spread positions. This tests the detonation/work path, not projectile flight or an instantaneous 100-impact burst. Raw command sequences match the plan in every arm; all 355 command events have ok=true. Native log positions match those commands and all 100 raw blast positions. Both Iris probes report ComplementaryUnbound_r5.9.3.zip in use; GL reports the same RTX 4060, driver and 2560×1600 dimensions.

| Completion evidence | A1 | B | A2 |
| --- | ---: | ---: | ---: |
| Process exit code | 0 | 0 | 0 |
| Native entries / raw blasts / completed series strikes | 100 / 100 / 100 | 100 / 100 / 100 | 100 / 100 / 100 |
| Executed plan steps | 261 | 261 | 261 |
| Recorded work units; matches series units | 768,015 | 756,158 | 693,216 |
| Last queue-window end after native-begin mark, seconds | 14.086 | 14.132 | 14.048 |

The native series logs report all 100 strike completions, and no queue windows remain in either 15–35 s or 35–50 s measurement window. No queue/release/observer exception was found in the selected console checks. quality.json reports no contract issues or screenlocker mentions; the raw journals also contain no screenlocker mention.

The different unit counts and block-write totals show that equal launch geometry is not identical dynamic work: fire/world evolution and timed batching vary. These runs support completion and accounting, not a precise CPU-efficiency percentage or block-for-block outcome equivalence. Some inherited manifest labels still say field/day or retain the original stand_jar_sha256; the actual plan, commands and installed helper bytes above establish this run's identities.

## Runtime hook and linkage checks

Recomputed from every window row:

| Observation | A1 | B | A2 |
| --- | ---: | ---: | ---: |
| Queue windows | 260 | 261 | 263 |
| Early / late windows | 134 / 126 | 134 / 127 | 134 / 129 |
| Active server ticks | 134 | 134 | 134 |
| Windows with no record | 35 | 33 | 33 |
| accountWindow observed | 0 | 261 | 0 |
| Link, charge, carry or reconciliation violations | 0 | 0 | 0 |

All rows use one non-counting clock per arm and common budget 30,000,000 ns. Every n resolves to the corresponding raw tick and work-link with matching server_tick; work-phase links resolve too. Initial expenditure is zero each active tick, and the next same-tick window inherits the preceding used_after exactly. No world-simulation gap is included by summing the separate window brackets.

In A1/A2, charged_delta equals record_charged for every window. In B, account_used_argument equals window used_before, pre-reconciliation charge minus window entry equals the record sum, and account_used_after equals window used_after. Every one of B's 261 actual reconciliations falls inside the lower/upper bounds derived from its captured account_enter/account_exit timestamps and account_began. Last recorded unit timestamps, where present, lie within their window bracket. This validates the actual optional candidate hook, beyond the baseline gate.

First-record counts are 134 in every arm; late-record counts are 116/107/119. These flags are record-time observations, not a complete canStart/estimate/spare trace. All active ticks record a unit; this is not a dedicated test of a completely waiting queue's execution bound.

## Queue wall time versus real charge

Method: group work-window rows by stand n. Queue wall is the sum of elapsed_wall_ns; real charge is the sum of charged_delta_ns. Units below are milliseconds. Percentiles use linear interpolation at (N−1)p. Active means at least one observed queue window, rather than including hundreds of idle ticks in the denominator.

| All 134 active ticks | A1 | B | A2 |
| --- | ---: | ---: | ---: |
| Queue wall total | 3,757.866 | 3,726.348 | 3,801.115 |
| Real charge total | 3,557.434 | 3,724.719 | 3,614.566 |
| Record-only total | 3,557.434 | 3,534.772 | 3,614.566 |
| Wall minus charge total | **200.432** | **1.629** | **186.549** |
| Wall minus charge, fraction of queue wall | 5.3337% | 0.0437% | 4.9078% |
| Queue wall median / p99 / max | 30.007 / 33.622 / 44.199 | 29.932 / 30.687 / 33.087 | 30.023 / 32.241 / 40.071 |
| Wall-minus-charge per-tick p99 / max | 7.315 / 8.670 | 0.048 / 0.223 | 6.390 / 8.186 |

B adds **189.947 ms** beyond its recorded steps through reconciliation. The small remaining bracket difference is between observer and production sampling boundaries; it must not be presented as missing production charge. Baseline early-window gaps alone total 46.976/38.851 ms, versus 1.020 ms in B; those omitted early costs are exactly the expenditure the resumed window otherwise cannot see.

For initial versus later activity, use the predeclared plan windows, not an outlier-selected warmup cutoff. “Later” is t3–15 and does not claim every pack class is warm:

| Later active ticks within t3–15 | A1 | B | A2 |
| --- | ---: | ---: | ---: |
| Active tick count | 94 | 92 | 93 |
| Queue wall p99 / max | 32.608 / 34.179 | **30.117 / 30.134** | 30.978 / 31.525 |
| Real charge p99 / max | 31.042 / 33.930 | 30.101 / 30.127 | 30.013 / 30.617 |
| Queue ticks above 30.25 ms | 16 | **0** | 18 |

B is not a strict zero-overshoot result: 33/134 active queue ticks exceed 30.000 ms; 19 have real charge above 30 ms. In the later interval 26/92 queue ticks exceed exactly 30 ms, with maximum 30.134 ms. Initial t0–3 queue maxima are 44.199/33.087/40.071 ms. Their long final units and sampling/inspection/release costs must remain visible, rather than being called a universal deadline proof. The source review's non-preemption and waiting-scan limits still apply.

## Cold entry, outer impact cost and whole tick

These are different scopes; the series log's “ticks over 50 ms: 0” does not mean every server tick stayed below 50 ms.

| First active server tick | A1 | B | A2 |
| --- | ---: | ---: | ---: |
| First native entry | 20.775 | 21.429 | 21.530 |
| Queue wall | 19.420 | 18.666 | 19.522 |
| ImpactCost total | 47.073 | 48.158 | 48.861 |
| Whole tick wall | 52.339 | 53.306 | 54.032 |

The first native entry ends 2.893/3.466/2.709 ms before the first observer queue bracket begins. It is a distinct cold cost outside that bracket. The broad outer ImpactCost and whole tick cannot be replaced by the lane charge or the sum of its internal kind timers. This patch has not removed that cold/outer cost.

Across active ticks, whole-tick p99/max is 54.634/56.148 ms in A1, 50.104/53.306 in B, and 53.286/54.148 in A2. Startup maxima across all tick rows are larger: 81.205/124.283/86.606 ms, before the workload. Do not mix those populations.

After queue completion, the B n=1681 peak is **78.044 ms wall / 62.155 ms CPU**, at +41.209 s; A2 n=1481 is **69.845 / 58.384 ms**, at +36.356 s. Neither tick has a work-window/link row; each has ImpactCost total 0.001 ms and air-cost calls zero. The selected postqueue clips' input hashes match the raw profiles/events/quality/manifests. Their native CPU stacks have 29/27 server-thread sample weights; 16 in each contain Burnt collapse/spawn together with class loading/verification/Mixin, and none contains UnitQueue. Other samples include FuelFires and ordinary server paths. These are limited samples, not exact duration allocation; stock events crossing clip boundaries must not be charged at their whole duration. The evidence separates these peaks from queue-accounting expenditure without claiming all remaining tick cost has been diagnosed or fixed.

## Frame evidence and limits

Read 6,127/6,132/6,338 completed CSV rows: ordered frame IDs/timestamps and finite nonnegative populated timing values. All five declared windows are covered. Done reports 2–3 more rendered frames than completed CSV rows; the small terminal gap is after the tabulated windows, so this is not a claim of every-frame coverage.

Raw previous-frame interval maxima in t0–3 are **229.201/222.816/284.145 ms** (A1/B/A2). In t3–15 the maxima are 114.008/108.861/145.720 ms. Even t15–35, with no queue rows, has interval maxima 164.991/132.222/157.315 ms. CPU-cycle and GPU columns are distinct measurements; none of these is a clean-FPS acceptance result.

The same observer/profilers run in all arms, but their overhead is not identical per executed path: candidate-only accounting hooks and variable unit counts matter. Window bookkeeping consumes time; serialization after tick capture is absent from that tick metric while still perturbing real execution. One short A1/B/A2 workload with dynamic fires does not isolate every admission, establish all scenarios or demonstrate uninstrumented gameplay latency.

## Final-target transfer

Independently verified final-target-identity.json against git and jar contents. Final candidate **04931ca4dfe3778709d4f9309d0bf0354ebd4d1b** has parents f8d6b1408 and accepted main **14f36e8ec965501c68224c091258eb1443e15f5d**. The complete git diff from 14f36 to final is byte-identical to eee272309→f8d6, SHA-256 `6664a6eaa65f8ee5df159861de83b9d2aff6ab309bc839d41986401c2b94e5af`.

The actual final jar SHA-256 is `ac66ef56c61ee9cf82458394d1dc3091e1375a697ed77ab304c03c80145e7bd1`. Runtime-to-final payload comparison finds only SceneDepth.class changed by the unrelated integration. WorkClock, UnitQueue, WorkScheduler and StrikeWorld bytes are identical. In particular:

- WorkClock.class: `9d4911b447333397e55394c657a9ce3eef398aa084ca0e773203bb9e27aff091`.
- UnitQueue.class: `553b4f816f0aabc01ffe9d4e640087a56afc01d9816db41231325e5d24faba3e`.

The server accounting evidence therefore applies to the final target classes. The observed client-frame results are from runtime f8, not a validation of the final SceneDepth integration. The supplied evidence is sufficient for the narrow accounting/completion conclusion above; wider performance acceptance remains outside this review.
