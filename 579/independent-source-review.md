# PR #579 independent review

Reviewed 2026-11-04 by Codex R579, fresh GPT-6.1 Sol / xhigh session.

- Checkout: `/mnt/data/projects/incoming/mod/run/wt-R579`, detached and clean.
- Candidate: `f8d6b1408b9f44cd255cb16db7253d73c7bef9b5` (`codex/impact-window-accounting`).
- Base: `eee272309e1c8306952f32b5f6b7ed4192be2e7c`.
- Scope: all four changed files; surrounding WorkClock, UnitQueue, WorkScheduler, StrikeWorld, ImpactTiming, real impact jobs and relevant unit/GameTest callers.
- Boundaries: read-only source review plus the supplied saved red log/XML. No builds, tests, CI commands, GPU, servers, PR operations or production edits. No owned jobs to await. Checkout and raw evidence preserved.
- Independence: no PR description, author transcript, diagnosis reports or inbox conclusions used. Current TEAM/context/BOARD/live and repository rules supplied workflow only.

## Verdict

**No new blocking correctness, crash or budget-accounting defect found in this diff.** The reconciliation is correct for the existing sequential production call graph. This is a source-review result, not runtime performance acceptance or a merge instruction. Passing candidate functional checks and representative full-pack evidence were not supplied for this review; the wider performance goal remains unproven.

## Accounting and caller checks

1. **One charge for an enclosing queue window.** `UnitQueue.java:107–114` captures the pre-window expenditure and clock time, then reconciles in `finally`. `WorkClock.java:240–242` takes `max(current charge, entry charge + elapsed window)`, rather than adding the elapsed window to already charged steps. In the current call graph, step intervals are disjoint subsets of the window, so the elapsed term includes readiness, dependency checks, kind lookup, collection operations, logging and synchronous releases once. Sequential queues/worlds retain their preceding expenditure through the entry offset. There is no clock reset in this call path.

2. **Early/Post sharing and lower lanes.** `WorkScheduler.java:149–160,188–223` uses one overworld impact clock for all worlds and both windows. Post subtracts its accumulated expenditure, resumes without resetting counters/estimates, and only grants the extra late start if early expenditure stayed inside its allowance and the cap has room. This patch makes those decisions and nuclear/grid remainder calculations see the queue overhead. The world-simulation gap between windows is outside both measured queue intervals and is not accidentally charged. Absolute deadlines, busy fallback, sprint, freeze and world rotation are unchanged. OWN and the mandatory-time observer also receive the enlarged expenditure; it must not be presented as the sum of ImpactCost kind timers.

3. **Counting and controlled clocks.** The new code uses the same supplied time source as begin/end, never System.nanoTime directly. With a positive-cost counting clock, only end advances fakeNow; the enclosing elapsed time already equals the recorded units, so max adds nothing. Sampler has its separate counter and remains independent. Controlled monotonic suppliers account whatever time the fixture advances; read-advancing suppliers will also see the two added reads. No unit estimate, unit-kind estimate, unit count, worked flag, decay or late-start flag is changed by accountWindow.

4. **Exceptions and resource release.** The existing step-finally still records a throwing step. RuntimeException in readiness/dependencies/step remains logged and converts the job to DONE; release remains independently guarded. Reconciliation now also runs after these paths, including their logging and release cost. Errors still propagate under the existing policy, but the enclosing finally executes. Normal completion, timeout cancellation, finish and clear preserve their existing removal/release behavior. Shutdown uses its separate effectively unlimited clock; this patch adds no retained job/world state.

5. **Callbacks and progress.** Real impact job implementations enqueue through impacts().add; the sole production queue-work call is StrikeWorld.workImpacts from WorkScheduler. Their metric record calls target BlastArea/ImpactCost, not a nested WorkClock charge. No recursive work call or clock restart inside a job was found. Callback-enqueued heads still join the original pass, with their cost inside the same enclosing window. Head priority, continuation rotation, cached availability and one-unit progress flags are unchanged. The existing callback-enqueue/counting/lifecycle fixtures remain applicable by source inspection, not newly verified passes.

The method is not a general correction for arbitrarily overlapping charge spans: max deliberately preserves a larger existing charge. Recursively executing a second queue inside a step on the same clock could overlap that step's end measurement; the existing production call graph does not do this. Such recursive execution should not be inferred to be supported.

## Supplied raw evidence

Inspected source first, then:

- `/mnt/data/projects/incoming/mod/run/incoming-sessions/MT-L2-budget-red.log`
- `/mnt/data/projects/incoming/mod/run/wt-MT-L2-BUDGET/mod/run/budget-red-evidence/TEST-ua.zentix.incoming.work.UnitQueueTest.xml`

The saved XML records 11 tests, 2 assertion failures, 0 errors, timestamp `2026-11-04T09:35:03.784Z`. The first new fixture observed **2,000,000 ns charged against 9,000,000 ns expected**. Its source allocates 3 ms to readiness, 2 ms to the step and 4 ms to release. The second observed **40,000,000 ns elapsed against a 32,000,000 ns upper bound** across its synthetic early/late windows. Both results are consistent with the base queue charging steps only.

The candidate formula accounts 9 ms for the first fixture without inflating its 2 ms step estimate. By source tracing, the second fixture's first window consumes 19 ms including the final unsuccessful readiness attempt, so its resumed deadline is 30 ms rather than the old 40 ms. These are analytical checks, not executed green results. The saved log/XML contain no source-SHA attestation; they demonstrate the fixture failures, not an independently certified run of a particular binary. They establish neither real-world prevalence nor candidate full-pack tick cost, throughput or strike completion.

## Remaining limits and optional follow-up

These are distinct from an introduced blocking finding:

- **Accounting is not preemption or a universal deadline bound.** Heads check availability before admission and have no deadline exit on SKIPPED; continuation-loop deadline checks require workedThisTick. Thus an all-waiting inspection pass can exceed the allowance without completing a unit. For example, 100 unready heads at 1 ms per readiness call still consume 100 ms; the patch records that cost but does not stop the pass. This exists in the base too. Long single readiness calls, steps and synchronous releases also remain indivisible. The new regression's 32 ms assertion is specific to its ready, short-job fixture, not a universal queue guarantee. Runtime acceptance must distinguish those cases from refunded early-window overhead.
- **Work outside the queue is outside this change.** StrikeWorld timeline execution, job construction before enqueue, endImpactTick reporting and scheduler traversal are not enclosed by accountWindow. ImpactCost and whole-tick measurements therefore have a different scope. No conclusion about eliminating their peaks follows from this patch.
- **Optional regression coverage:** add controlled-clock assertions for blocked/unready zero-unit windows and timeout/release or readiness/step/end exceptions, checking accumulated expenditure and release count. The finally path is sound by inspection, but the two added tests cover normal ready jobs. If accountWindow gains new users, document/test its same-clock, same-tick, non-overlapping-unit preconditions.

No source correction is requested by this review. The owner retains implementation, validation and acceptance responsibility.
