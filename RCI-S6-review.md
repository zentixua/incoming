Completed 2026-11-04T22:54:34+00:00

# RCI-S6 independent source review

Verdict: PASS for the exact fixture/rule delta; no serious blocker found. No production integration, runtime readiness, merge or delivery acceptance.

- Reviewer: fresh Codex session RCI-S6, explicitly launched gpt-6.1-sol / high (verified live process).
- Base: b57c36061db305408c25e07d9e563fc37bd64dde.
- Head: 0798edf0859b8dfa3ce8f8879de9b33d0e66a35e.
- Worktree: /mnt/data/projects/incoming/mod/run/wt-RCI-S6, detached at head, clean before and after inspection.
- Scope: three files, 20 additions / 3 deletions; production source unchanged. Read diff and relevant owners/callers directly. No new runtime/heavy jobs, source edits, GPU/server/UI/remote actions.

## Serious findings

None in the reviewed delta.

## Cleanup and regression discrimination

RuinBackgroundGameTests.java:1342–1351 now retires the synthetic detonation context inside the existing finally, after clearing the standalone queue and resetting the visibility override. This runs on assertion/error exits from the queue exercise too. Existing visible scar timing, stale-plan count, unseen scar absence and unseen glass assertions remain intact (1337–1341).

Ownership agrees with production code: ScarQueue.clear (ScarQueue.java:700) releases queue jobs, parked results and tickets, but does not own the shared RuinContext. NuclearWorld.forgetRuinsForTest (NuclearWorld.java:141) removes only this synthetic detonation ID and invokes cancelTasks. RuinContext.cancelTasks (1163 onward) preserves actual worker input/result lifetimes while cancelling tasks, closing cache/storage and retiring window reads; retireWindowReads/releaseAssembly (1150–1161) release the shared assembly claim. This matches NuclearWorld.clear's separate queue/context cleanup (400 onward), rather than weakening production admission or reclaiming live workers early.

The new await fence (1349) is followed by planFresh(..., true), not a synchronous fallback. RuinPlanner.planFresh (142–151) creates an independent unregistered context and cancels it in finally even when submission/await fails. RuinContext.submit (922–943) checks worker admission and the shared assembly token before launching the real solver. A stale NEAR assembly owner prevents this independent NEAR consumer despite zero in-flight workers. Thus changedBlocks > 0 is a meaningful nonempty-output check after a real independent admission, not just a queue-size or cache-residency assertion. It is not a full physical-output equality assertion, and does not need to replace the earlier ordering assertions.

Raw correspondence, relative to /mnt/data/projects/incoming/mod/run/wt-CI-S6/mod/run/ci-s6:

- native-six.log:391–416: background submission fails with tasks=0/inFlight=0 and an assembly owner different from the requesting context; 31-test prefix fails only ruinsOverFlashWithoutShots.
- pair-red.log:147–175: preceding synthetic detonation1000001 retains pending assembly; following detonation1000002 fails despite idle workers. pair-green.log:155–156 passes both tests after retirement.
- retirement-mutant.log:149 and 176: omission of context retirement while retaining the new checks fails the look-ahead test itself with background-submission refusal, then fails the flash test. This is a direct discriminator for the added post-cleanup consumer.
- final-prefix-six.log:372–373 passes31/31, with the final fixture and original-prefix helper, without the earlier diagnostic rows.

## Craft geometry and unchanged behavioral assertions

WorkGameTests.java:487 raises the charge one block from CENTER to CENTER.above. Compensating body offset at500 preserves the body's exact world position. The old prerequisite sight-line point c_old+(0,1+epsilon,0) equals the new c_new+(0,epsilon,0): this preserves the tested line while bringing the actual detonation source to it. Lines530–535 additionally require an empty collision shape at the actual source and complete initial shielding.

The test still requires a STRIKE damage callback, a nonempty selected craft plot, every selected plot block already air inside that callback, and no survivors at the following scheduler observation (493–495, 504–517, 540–544). No replacement with eventual hull removal or unconditional damage was introduced. StagedExplosion.front (894–919) merges craft and body events by range; wave.wind (813–815) uses the actual airOrigin, so a source trapped in the platform can legitimately yield no body wound even after hull removal.

Evidence: craft-position.log:147–171 and craft-trace.log:147–172 reproduce the old source at Y=-49, selected45 hull blocks, no body damage, and seenBase/seenUp=0 at the actual body-processing point. craft-wave.log:149 records breach=null, placement=NONE, wind=false/reaches=false/wound=null. craft-green.log:147–153 raises only the source to Y=-48, retains the body position, initially shields it, then records wind=true and STRIKE damage with hullGone=true, plot45/left0. The final source retains all the decisive assertions. final-prefix-four.log:389–390 passes50/50.

## Optional findings

None requiring a change in this delta. The conservative onCraft query remains unchanged: craft-green.log:147 records inPlots=false but nearOne=1; Warheads.java:557–567 consequently chooses placement NONE. This explains why the platform source cannot be assumed to receive a surface-breach offset. This review neither accepts nor changes that broader production policy.

## Contract and evidence limits

- No weapon charge, pressure law, material behavior, worker memory cap, production deadline or budget changed. Counting-clock settings, original GameTest timeouts and prior timing assertions remain unchanged. The extra 60-second await is an existing test-helper timeout, not a runtime deadline relaxation; planFresh already has its own bounded await.
- original.log passes the two tests in isolation; native-four.log passes the old craft fixture50/50. The pinned-position craft controls expose a geometry-dependent failure, not a universal old-test failure. The supplied local logs do not independently authenticate the exact historical CI craft interleaving.
- Saved *.red.java files are NOT byte-identical to base: I compared both and found added diagnostic probes. The supplied diagnostic-and-controls.patch records temporary GameTestShards selection/order/seed/origin controls, probes and the retirement omission. These are instrumented controls, not exact unmodified-source runs. The probe calls NuclearPrep.residentMemory, which can perform accounting/reclamation; it is not a passive total-heap observation. Timing and resident figures from those rows are not performance acceptance.
- Final prefix logs show the stated passing test totals and actual batch orders. They retain temporary prefix infrastructure; no immutable source/log manifest proves byte-for-byte execution provenance for every earlier log. Full build, remote CI and integrated runtime acceptance stay with CI-S6 / MT-S6 / LEAD4. I launched or owned no such jobs and did not inspect PR description, CI-S6 inbox/handoff or author conversation.
- Mandatory BOARD inspection exposed its short CI-S6 checkpoint (including author claims). No verdict relies on those claims; source, assertions, owner transitions and raw controls above were independently traced. Canonical memory supplied general lifecycle/clock cautions, not proof of this head's behavior.
- git diff --check on the exact range passed. No local build/test execution was requested or performed by this review.

## Handback and resources

EXPLICIT HANDBACK to CI-S6 for component implementation/PR, MT-S6 for production integration, LEAD4 for runtime/merge/delivery. No author work or runtime ownership taken. Owned heavy jobs, child review sessions, CI waiters, GPU leases, clients and servers: 0. No resources to stop or release. Review worktree remains detached/clean at0798edf0859b8dfa3ce8f8879de9b33d0e66a35e.

В память: no update; lifecycle and evidence-boundary facts are already recorded in canonical memory.

END
