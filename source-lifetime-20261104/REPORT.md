# Source-progress fixture lifetime

Baseline: ee0cd0f96c1d762f59477c5a6e3990090b36192c, PR589. Corrected fixture: 4b0aacb2ace682169f1f35f7e29f2a8e22742724. Production unchanged. Relevant context/world/input and test source is identical in integrated f8481f253. This report does not certify full runtime or whole-heap bounds.

## Original failure and controlled replay

Exact CI41859919912 GameTest3/6 crashed in RuinInputs.acquire → RuinWorkers.submit → RuinContext.submitPlanWorker → RuinSourceProgressGameTests. Original raw job is included. The direct-map fixture creates ctx.used=0 rather than registering through NuclearWorld.ruins.

`red-native-probe.patch` adds native setupTicks700, admission near a 100-tick sweep, read-only lifecycle tracing and cancellation stack logging. It does not change production control flow, the600-tick timeout, source admission pressure or original output assertions. Four CPU /2GiB heap, no-daemon laptop_job; root warmed client and another isolated CPU check were present, so timing is not a performance comparison.

At callback11/world800 all three contexts remain registered, used0, detachedfalse. The actual NuclearWorld.sweepRuins call then cancels all three. At callback12/world801 all are absent and detached. At callback14/world803 the next original solver submission crashes with the same exception. RED wrapper exits1 in25s.

`green-native-probe.patch` differs only by obtaining each context through existing world.ruins(level,d,level.getGameTime()). Used746 stays valid through world800/801, all contexts remain registered/attached. Original pressure held484156928bytes throughout; all75 real source reads complete and3nonempty full physical/material plans equal the references in23callbacks. Peak known ownership533364736 <=536870912bytes. GREEN wrapper exits0 in30s. These are known ownership estimates, not whole JVM heap figures.

The initial `red.log`/`red-probe.patch` changed gameTime inside an already-running GameTest. That advanced the test deadline and queued paced callbacks; it failed its own deadline instead of the target crash. This attempt is preserved and explicitly excluded from causal proof.

## Committed regression

4b0 uses normal world registration and forget, native late setup, admission just before a sweep, identity checks and completion strictly after the next sweep tick. It retains the original pressure, cold pool, full output equality, unloaded source and no-I/O-error assertions. No runtime guard, expiry rule or solver behavior changes.

Fresh independent Astra/high scoped review and exact corrected CI pending when this checkpoint was written. Final clean integrated fixture verification will run after the root measurement boundary. The two previous producer source review rounds are preserved separately; this review does not repeat them. Main integration/runtime acceptance remains with LEAD2.


## Final source validation checkpoint

Exact clean4b0aacb2a passed16/16 targeted GameTests in the final uninstrumented run (48s Gradle/wrapperexit0), including source progress/failure/cached failure/absence, input cancellation/clear/forget/expiry/queue, result flow/cancel, fresh-helper and window lifetime. Original600tick pressure fixture succeeds across the scheduled sweep; see final-4b0-16gt.log. No production changes, source checkout clean.

Fresh Astra/high RMT-S3B independently reviewed the exact diff/surrounding code/rawlogs without author conclusions or prior review: no serious findings. The earlier RMT-S3 report is retained with its disclosed memory exposure; RMT-S3B is the replacement blinded review for the same scoped round. Both sessions/waits completed0; neither ran tests. Both reports explicitly distinguish the diagnostic pair from the final source and note sampled ownership/actual-sweep coverage limits.

Exact CI41864060485 was still queued on GitHub after this local checkpoint; pr_ci589 remains active. No rerun was requested. Integration/main delivery was explicitly accepted by LEAD2 throughPR593, not an ancestor-only merge. Actual runtime has a separate measured far-progress issue, not resolved or certified by this fixture proof.


## Required exact-head CI completed

The required Итог check126698268974 for4b0aacb2ace682169f1f35f7e29f2a8e22742724 concluded success at2026-11-04T16:16:05Z, run41864060485. `tools/pr_ci.sh 589` completed0; both raw receipts are included. The GitHub run-level API still returned a stale queued status while the exact-head check-run API confirmed completed/success. No rerun requested. This completes the scoped fixture CI gate; integrated runtime readiness remains a separate failing investigation owned by LEAD2.
