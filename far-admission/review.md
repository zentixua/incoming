# Independent far-admission review

Fresh Astra/xhigh review of base `fa2b09d3ea9aea3b5c4d5168a5b809285d2b5fdd` to head `55a64d2e0a7fce31d24c80b7495fb14ca9b1a158`, with surrounding source and raw paired logs. No PR description or author conclusions were supplied. This technical excerpt omits personal session records.

## Verdict

**No serious correctness, crash, ownership or budget regression found in this bounded diff.** Source review complete. This is not actual runtime acceptance.

### Why the changed ordering is safe

1. `NuclearPrep.java:1211–1279` moves the existing cache trim after the far solver-admission loop and after retaining the next frontier window. It does not remove the trim or increase its requested bytes. Both `clock.canStart()` and `shared.canStart()` still gate the solver loop; every attempted solver submission retains its existing `shared.begin/end` and local-clock accounting. The trim still uses the same shared clock, with no reset/new allowance.
2. `RuinContext.java:608–628` charges a unit for every visited snapshot, including a pinned snapshot that cannot be evicted. Thus an unchanged protected 5×5 window could spend the available time before its consumer was ever attempted. Trying that consumer first removes this dependency without altering physical solver inputs/output.
3. `NuclearPrep.java:1181,1215,1262–1279` and `RuinContext.java:360–399,922–974`: assembly protection remains in force while admission refuses. Successful submission installs the Task/Grid before the subsequent cache walk; trimming protects both `tasks` and `pendingWindows`. The next centre is pinned before that walk. Single-threaded server admission leaves no intervening trim between releasing assembly and installing the Task.
4. Existing memory guards remain: reads are limited by `WINDOW_CAP`, publication transfers its read owner, and `submitPlanWorker` reserves result/fragments against resident bytes minus worker startup before solver work. Deferring a cache-policy trim cannot bypass these guards. It can retain cache data longer, but the policy threshold is not a separate total-heap cap. I found no new owner, allocation class, cancellation path or persistence format in the production diff.
5. Abandonment/retirement still use existing cancellation and claim release: `NuclearPrep.java:2068–2090`, `RuinContext.java:1132–1174`. No changed code discards a callback's outstanding ownership or counts Future cancellation as execution exit.
6. `RuinFrontierGameTests.java:47–57` saves/restores the original server work budget alongside the clock; the budget is changed only for its dedicated `nuke_frontier_material` batch. `StrikeGameTests.afterTest` runs on both success and failure. The progress limit stays 120 ticks. The test still checks complete stored physical/material plan equality, an unloaded source, bounded reader advance, sampled admission estimates and actual material movement while pending material remains. The EN/RU guide changes make the same qualified statement and retain the memory/time caveat.

## Nonblocking inherited test cleanup observation

`RuinFrontierGameTests.java:83–88,121–128,145–152`: the fixture adds 65,536 pieces to the world-owned `RuinMatter`, deliberately succeeds while material remains pending, but its cleanup only restores the artificial reservation/cache policy and cancels preparation (plus the clock/config cleanup above). It does not retire/reset this material ledger or assert that its tickets/records returned to the pre-test state. Preparation cancellation does not own that ledger (`NuclearPrep.cancel` only marks its preparation draining).

This is already present in the base and is **not a blocking regression of this patch**. It means the paired isolated runs, which then shut down their server, do not establish isolation from later batches. If this fixture is touched further, register appropriate ledger/ticket cleanup after either outcome (the repository has `RuinMatterGameTests.clearFixture`) and preserve any pre-existing fixture state as needed. No subsequent-batch failure was reproduced or attributed to it in this review.


The reviewer independently checked the base-source overlay and exact-head test/production hashes, and the old failure/new 22-tick completion with active material. Later build/CI results are author receipts, not retroactively attributed to the review. The inherited cleanup observation was addressed afterward by one test-only `afterTest(clearFixture)` line in `fd7652e864bb01a9e40f6ebaf72e8e367ecf787a`; production source is unchanged. No second review round was needed for this nonblocking cleanup.
