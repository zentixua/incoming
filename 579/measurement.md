# PR 579: account the complete shared impact queue window

The candidate charges readiness, dependencies, queue bookkeeping and synchronous release once, together with the existing recorded steps. Early work previously left that overhead out of WorkClock, allowing Post to reuse it. The controlled red fixtures report 2 ms instead of 9 ms and permit 40 ms for a 30 ms setting with a final 2 ms job; both regressions pass with the candidate. Estimates, unit counts, progress guarantees and physics are unchanged.

## Matched runtime evidence

Each arm uses a fresh JVM and disposable synthetic city, the same 73-mod full pack, Complementary shader, native 100-drone spread plan, and the identical private copied stand helper SHA256 `5c8f4f7963fbe1b3371e081bfc3b1dafe18f30857a8f5aea29eefae312fb7f37`. A1/A2 use eee272309; B uses f8d6b1408. Exact locally built production ZIP differences are only WorkClock.class and UnitQueue.class. The same plan/native CPU2ms/DWARF/JFR/count instrumentation is enabled in all arms. The known full-texture readback probe FxLayer#describeTiming is omitted identically; the scalar smoke probe remains. No strike or frame is used for priming.

All three clients and the owned wrapper completed with exit0. Each has 100 native entries and 100 blast events; commands, quality contracts, shader and workload checks pass. The first baseline runtime gate passed before B; candidate-only account hooks are separately present and satisfy captured time-sample bounds. There are 260/261/263 queue-window rows, both early and late, each joined by explicit server_tick to stand n; all arms have 134 active queue ticks. Raw compressed events and frame timings permit independent recomputation.

| Metric, milliseconds | A1 baseline | B candidate | A2 baseline |
|---|---:|---:|---:|
| Active queue wall p50 |30.0068|29.9308|30.0213|
| Active queue wall p99 |34.1789|30.7506|32.5870|
| Active queue wall maximum |44.1986|33.0873|40.0709|
| Observer queue wall minus real clock charge, mean per active tick |1.4958|0.0122|1.3922|
| Observer queue wall minus real clock charge, sum |200.4323|1.6290|186.5493|
| Steady t3–15 active queue wall p99 / maximum |34.1789 /34.1789|30.1344 /30.1344|31.5254 /31.5254|
| Outer ImpactCost p99 / maximum |31.3384 /47.0731|30.8630 /48.1581|31.5205 /48.8606|
| Whole server tick p99 / maximum |49.9314 /56.1479|49.2121 /78.0436|49.8550 /69.8445|
| Frame p99 / maximum |90.118 /229.201|88.128 /222.816|98.562 /284.145|

The table uses nearest-rank percentiles (`ceil(N*p)`); the independent runtime report instead uses linear interpolation and labels its values accordingly. The target accounting discrepancy is removed within the observer bracket difference, and this set's queue tail is narrower. It is not a claim of an intrinsic speedup of a stated percentage or clean gameplay FPS. Native/functional completion is measured separately from the queue records. Functional tests additionally establish eventual job completion and release; no universal equality of evolving worlds across differently scheduled scenes is claimed.

## Scope and exceptions

This is a shared queue accounting correction, not a hard preemption mechanism. A readiness scan with no ready unit can still exceed its allowance; steps, readiness operations and release are indivisible. Predicted admission cannot bound unexpectedly expensive units. The largest B queue tick n862 has early20.244665 ms and late12.842631 ms; its last recorded steps are16.118880 and7.926604 ms. Recorded first/late flags and a phase-entry hasTime snapshot do not prove every admission decision. Constructors, timelines, entity/fuel ticks, other mods and world simulation are outside this queue's charge.

The largest whole B tick n1681 (78.0436 wall /62.1545 CPU) and A2 tick n1481 (69.8445 /58.3839) occur 27.077/22.308 seconds after their last queue window. Both have outer ImpactCost0.001 ms and no queue rows. Exact selected native CPU stacks plus one stock JFR sample per interval independently identify scheduled Burnt smoldering-wood Stage3 → RandomCollapse → FallingSlabEntity construction with class loading/Mixin transformation in both arms. This is not a demonstrated Incoming redundant operation or a regression introduced by579. FuelFires appears in one sample per arm; zero sampled Debris/LSS frames do not prove universal absence. Wall-minus-CPU15.889/11.461 ms remains unexplained. Inclusive sample counts are not exclusive milliseconds. See postqueue-diagnosis.md and exact primary clips; no other-mod change or contact follows.

Instrumentation has overhead. Same helper prevents version drift but not differing executed work, candidate-only account-hook overhead, allocation or pacing effects. Window records serialize after the existing server tick wall/CPU sample, consuming time not included in that row. The candidate's real time sample is bounded by observer entry/exit; observer elapsed and real charge need not be numerically identical. No full hard30ms, whole-tick or render acceptance follows.

Foreign CPU mean/max cores A1/B/A2:0.101/0.24,0.033/0.19,0.065/0.18. Foreign GPU mean/max percent:0.72/4.4,0.58/4.4,0/0. Power profile was performance throughout and was not changed. Recorded maximum GC pauses are9.825/9.936/10.075 ms; these do not coincide with the selected postqueue peaks. Full quality rows remain in each arm; host evidence excludes unrelated process identities.

## Source, checks and final integration

Fresh independent Sol xhigh source and measurement-artifact reviews are retained separately. At f8d6b1408, the full1785-unit build and23 touched work/fragment GameTests pass; the first fragment-only selection is not counted as23. Required Итог41809193595 is green. The original red XML/log and candidate targeted XML are retained.

Validated integration head04931ca4 normally merges accepted main14f36e8ec. Its four-file diff against main is byte-identical to the reviewed/runtime diff, SHA256 `6664a6eaa65f8ee5df159861de83b9d2aff6ab309bc839d41986401c2b94e5af`. Jar and35 targeted units pass10s; required Итог41818510130 is green. Final head2cacd4702 then only corrects an inherited false universal deadline-plus-one-unit claim in the internal work rule, documenting the already identified waiting-scan/non-preemption limits. Its entire mod production/test/build tree is identical to04931ca4. Both target class bytes equal the measured f8 jar. The only runtime-to-integrated jar payload difference is SceneDepth.class from independently merged576; this integrated whole jar was not used for the three-arm measurement. final-target-identity.json records source-tree, diff, jar and target hashes. The first independent runtime review (R579, Sol xhigh) recomputed the raw workload, hooks, carry, clock bounds, completion and timings without author summaries: no measurement-contract invalidity or new serious accounting fault. It confirms the narrow accounting/completion conclusion, with the stated wider limits. That review inspected04931; the later2cac change is internal rule text only with identical full mod tree. Actual-final-head2cacd4702 required Итог41819885881 is green; its tools/pr_ci waiter completed0. All owned checks, profiles, reviews and CI waiters ended before publication.

Raw events/frame timings are compressed without alteration. Complete original JFR/native recordings and logs remain privately retained with hashes; only selected diagnostic clips are published. No saved world, environment dump, production binary, personal launcher or screenshot is published. This server-only change has no visual design change or before/after screenshot claim. This evidence does not make B3 horizon ready, incorporate the rejected575 fragment cache, authorize a release, or establish full-mod performance readiness.
