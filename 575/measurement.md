# PR 575: rejected empty-section fragment optimization

The three-arm full-pack measurement does not establish a server-compute benefit. Do not merge the candidate. Retain the branch and evidence; main remains unchanged by this experiment.

Each fresh JVM/world ran the same 100 native drone impacts spread through a night city with Complementary and the same 73-mod snapshot. Both jars were built locally with the same JDK/Gradle. Exact ZIP payload differences are only FragmentTrace and Visitor line metadata. A1/A2 use 36c9b5bdd; B uses 0006b0ccc. Native CPU sampling (2 ms), JFR and per-tick work counters are enabled identically. This is an instrumented comparison, not an uninstrumented gameplay maximum. All three contracts passed: 100 native entries and 100 blast events per arm, no command/contract issues.

| Metric | A1 main | B candidate | A2 main |
|---|---:|---:|---:|
| Fragment stage total, ms | 1495.378 | 1564.802 | 1453.471 |
| Recorded work-unit series total, ms | 3350.5 | 3504.0 | 3357.2 |
| ImpactCost tick p99 / max, ms | 31.584 / 48.553 | 31.914 / 47.155 | 31.381 / 45.610 |
| Whole server tick p99 / max, ms | 49.478 / 56.842 | 47.647 / 57.286 | 49.730 / 54.914 |
| Recorded longest work unit, ms | 20.0 | 21.4 | 21.1 |
| Frame p99 / max, ms | 88.391 / 302.827 | 86.587 / 275.385 | 87.970 / 243.157 |

Candidate fragment time is 4.6% / 7.7% higher than both baselines; the measured target did not improve. This single A/B/A does not prove an intrinsic slowdown of that exact magnitude. Faster frame medians are not accepted as evidence for a server getter improvement: GPU clocks and background GPU load vary, and whole-scene evolving-world scheduling is not proven identical. No further GPU iteration was performed. ImpactCost has zero ticks above 50 ms in all arms; whole server ticks do exceed 50 ms, so this is no blanket release/performance acceptance. Stage sums, outer tick brackets and longest units are different scopes and must not be added or substituted.

The only structural change from the retained C2 plan is omission of FxLayer#describeTiming in every arm. That probe performed a full texture readback through SceneDepth.lodShare. Other original probes and all fixture commands/timing remain unchanged; FarRenderer.describeSmoke only reads stored scalar counters. Missing layer/depth labels are intentional. The old readback-instrumented frame maxima are not comparable as equal instrumentation. Plan hashes and exact jar hashes are in provenance.json.

Foreign CPU mean/max cores (A1/B/A2): 0.129/0.24, 0.014/0.08, 0.093/0.18. Foreign GPU mean/max percent: 0.28/1.8, 0/0, 1.48/7.8. Mean GPU clocks: 2281.5 / 2290.5 / 2194.5 MHz. Reported profile was performance throughout; it was not changed. Full window statistics and GC rows are preserved. Numeric host evidence omits unrelated process identities.

At the measured candidate: 1787 unit tests and six conventional GameTests passed, and a fresh independent Sol xhigh static review found no blocking issue. Differential fixtures compare per-ray traversal encounters, not whole-scene mutable-world equality. CI 41802215854 failed two nuclear checks; their investigation belongs to the separate current lifecycle/fixture correction. It is not waived, green or needed to merge this rejected candidate. No production PR merge or release occurred.

Raw timing events and frames are compressed without data changes; quality/summary/work summaries, native entries and series logs are preserved here. Original native/JFR files remain in each retained ignored p2-pair-local/runs directory with source hashes in raw-source-hashes.json; they are not needed to derive the quoted stopwatch totals. No user world was copied or published. Screenshot capture was disabled identically; no visual-change claim is made.
