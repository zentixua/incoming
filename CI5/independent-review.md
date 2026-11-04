Review completed: 2026-11-04 19:12 UTC

# RCI5 — independent source and diagnostic review

Verdict: **no blocking correctness, crash, lifetime or resource defect found in this one-file diagnostic change.** This is approval of diagnostic instrumentation, not a finding that the original CI failure is explained or fixed. MT-S5 production remediation and delivery readiness are outside this review.

Reviewed `f53ecbe1c3cfec751c1aa978362897a07adc2e7d..d51e559589df40ce6d8055b63cf45200453c34f6`, branch `codex/ci-fragment-progress`, worktree `/mnt/data/projects/incoming/mod/run/wt-CI5`. Only `mod/src/devtest/java/ua/zentix/incoming/nuclear/world/RuinFragmentCacheGameTests.java` changes (+38/-2). Read the committed pin with `git show`, not the temporary control working copy. Separate fresh Astra/xhigh review; source, current rules and raw evidence only. No author conversation, PR description, handoff, inbox contents or authored reports used. No build, GameTest, GPU/server action, production edit or publication performed.

## Source and lifecycle findings

- Lines 140–198 retain the 400-tick deadline, original fixed-pressure calculation/reservation, non-null admission/cache-refusal check, actual `RuinWorkers.finished` wait, `takeWorker`, nonempty physical output assertion, result discard, and unchanged held-pressure/cache assertions. Added nanosecond values are observations only, not deadlines or assertions. There is no sleep, retry, synchronous solver fallback, early pressure refund or extra queue service.
- The failed-test callback (158–178) reads atomics, Future flags, direct ownership counters and a single captured thread's state/stack. `RuinWorkers.started`, `finished`, `inFlight` and `summary` do not create/service a pool. `fragmentCacheBytes` reaches the cache's synchronized numeric accessor; `RuinResults.bytes` and `RuinInputs.detachedBytes` are atomic reads. The new terminal probe does not call `residentMemory`, `takeWorker`, collection or admission. Initial/submission calls to `residentMemory` already existed. Logging/stack collection costs time and may observe subsequent worker progress, but does not drive nuclear ownership transitions.
- Cleanup still runs on success and failure, now under `finally` around the diagnostic block. `StrikeGameTests.afterTest` (3713–3732) registers both completion callbacks. The local NeoForge 21.1.264 source artifact confirms `GameTestInfo.hasFailed()` reads the error field, set before failure listeners, and the timeout sequence retains the last failed assertion. Thus failure-only logging is supported by the actual API. `ctx.cancelTasks()` still closes cache/abandons results and transfers running input ownership before cancellation; `held.close()` is idempotent. Logging failure cannot skip this cleanup. The change adds no wait for worker completion and does not turn cancellation into an early refund of the worker's own permits.

Diagnostic meanings must remain distinct:

| Observation | Meaning and limit |
| --- | --- |
| `admitted` | A Future was returned. Does not prove thread startup or available workspace. |
| `started` | `Task.run` was entered. `entered_ns` additionally establishes entry into the instrumented solver callable. |
| `exited_ns` | Inner solver callable's `finally`, before `RuinResults.producing` unwinds, `owner.markResult`, Future publication and `Task.run` exit-accounting. Not an actual Task-exit timestamp. |
| `worker_error` | Throwable from `Collapse.solve`/`RuinPlanner.finish` only. Null does not certify success of later result publication or exit callbacks. |
| `done` / `cancelled` | Future state. A cancelled running task can still own inputs/results. |
| `finished` | `isDone && !Task.counted`: actual-run-finally/removal accounting has completed; distinct from solver exit and from consumer collection. The callback-error path logs separately. |
| result collection | Performed later by unchanged `takeWorker`, followed by `discardResult`. Terminal logging does not collect. |
| pressure/pool counters | `held` is this fixture's current reservation; `initial_room`, `result_bound`, `startup` are initial values/estimates. `results`, `detached`, `inflight` are shared counters, not a complete per-task memory/admission state. `summary` reports configured/adapted capacity, not live pool size. |

Fields are separately sampled, not one atomic snapshot. A captured pool thread may already be idle or executing another task when its stack is read. Optional clarity improvement: label `exited_ns` as `solver_exited_ns`; not a merge blocker.

## What the raw evidence demonstrates

Evidence directory: `wt-CI5/mod/run/ci5/`.

1. `ci-result.json`: run 41889061857 failed in GameTest 6/6 and Итог. `ci-failed.log:95,122,125` proves checkout of merge `2178866fcbd5168e035f6f6e2d1a2b97df70cbce`. Direct tree comparison shows it and base `f53ecbe...` have the identical full tree `d0b1cc1241f3e4f81df7ea49332be4589e8a023a`; equality is verified, not inferred from the reported PR head. Proposed head tree is `c253895c72c860523ccd4fc383cfa1ef9752f0f6`.
2. `ci-failed.log:1186` records “Uncached solver has not progressed with pressure still held”. Reaching this assertion establishes that submission returned a Future and the initial cache-refusal assertion passed. It establishes `finished == false` at the deadline check; it does not distinguish never-started/queued, running solver, Future-publication or exit-accounting delay. There are no task-state/worker-stack samples at that original failure. Batch-to-error wall timestamps are not the callable's elapsed time. No causal attribution to scheduler starvation, memory admission, deadlock, context expiry or solver failure is justified.
3. All 38 actual batch names/order through the target match among `ci-failed.log`, `original-prefix.txt`, and `prefix.log`. `diagnostic.patch` is a temporary shard-prefix/probe change, not the proposal. `prefix.log:935,940` shows entry at 0.694115 ms, inner exit at 19.925071 ms, terminal sample at 20.397009 ms after probe construction; 77 callbacks, `done=true`, `cancelled=false`, `finished=true`, cache zero and held reservation 511646352 bytes unchanged. Shared result bytes equal the held reservation after the successful consumer discarded its plan. All 76 required prefix tests passed. This establishes one observed completion under held pressure, not reproduction or explanation of the original failure; the prefix also has extra instrumentation and a different runtime environment.
4. `final-check.log` records build/test tasks and one passing targeted GameTest (exit 0). It is local validation evidence, not final-head full CI or original-shard failure reproduction; the log alone contains no compiled-source SHA manifest.
5. `failure-control.patch` adds only an unconditional intentional failure immediately after admission/cache validation. `failure-control.log:114–116` exercises the real terminal callback while the solver is in `Blast.Solver.aroundBreaks`: admitted/started true, done/cancelled/finished false, inner exit absent, held=511777552, cache=0, inflight=1. It then reports the expected failed test and clean worker/server shutdown, exit 1. This validates the failure logging path with a live worker; it does not reproduce the original timeout or measure final ownership counters after cleanup.

No new jobs are needed for this scoped review. Required author CI and broader production/runtime acceptance remain separate. No owned jobs/resources remain; review session can release its slot for MT-S5's second review.
