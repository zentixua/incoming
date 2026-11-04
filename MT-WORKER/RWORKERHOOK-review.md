# RWORKERHOOK independent review

Date: 2026-11-04 14:24:50 UTC. Reviewer: Codex, fresh `gpt-6.1-sol` session with `xhigh` effort (confirmed from the live launcher process).

## Scope and result

Exact additive range: `b89f5a10d68d738156dee18382173959b9f069cd..44c08410e58ab81bbb9a6a525bd6107b310066e7`. Clean detached checkout: `/mnt/data/projects/incoming/mod/run/wt-RWORKERHOOK`. Changes reviewed: the four-argument `RuinWorkers.submit`, its composite input/exit callback, `Task.uncount` exception cleanup, and the added unit/GameTest coverage.

**Result: one P2 correctness finding in callback-failure cleanup. The new API should resolve F1 before component acceptance.** Normal completion/cancellation/promotion semantics have no additional established serious finding in this scope.

The earlier worker workspace component was not reopened or reviewed again. Existing Task, RuinInputs, executor, promotion, shutdown and immediate RuinContext callers were read only as surrounding code needed to assess the new seam. No author PR description, report or conversation was used. Current TEAM/context/BOARD/live, repository rules and relevant canonical memory supplied the required bootstrap. This is not a review of MT-S2 result publication, complete memory accounting, real-map progress, integration or release readiness.

## F1 — P2: isolate exit-callback exceptions from cancellation and shutdown cleanup

Changed locations: `mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinWorkers.java:389–394` and `:526–534`. Relevant surrounding consumers: `Task.done` at `:517–523`, `shutdown` at `:669–675`.

The composite callback lets an exception from `onActualExit.run()` escape. The new `uncount` finally correctly decrements this Task's count and marks it finished, but does not protect its enclosing cleanup protocol. The added unit deliberately permits an `IllegalStateException` from the observer, so this is a supported failure case being tested, not a claim that the intended numeric token normally throws.

There are two concrete consequences of that same exception escape:

1. **Queued cancellation retains the captured Callable after declaring it released.** If the Task is still queued, `cancel(false)` calls `Task.done`; `owner.remove(this)` succeeds, and `uncount` runs the throwing observer. `RuinInputs.release()` has already run and `counted` is false, but `done()` then throws back into `FutureTask.finishCompletion()`. In the installed JDK 21 bytecode, `finishCompletion` calls `done()` at offset 62 and clears its private `callable` field only at offset 67, without a finally. Consequently that private field is not cleared. Clearing the separate shadow field `Task.callable` at line 518 does not clear the superclass field. A still-referenced cancelled future can therefore retain its original Grid/context payload while `RuinWorkers.finished(future)` reports true and its detached input estimate has been released. The remaining credit notifications in `done()` are also skipped.
2. **Shutdown abandons the rest of its drained queue without releasing their owners.** `shutdownNow()` first returns all queued tasks. If the first drained Task's observer throws from `t.uncount()`, the loop stops before later Tasks are cancelled/uncounted. They have already left the executor queue and will never run. Their futures, input owners and additional callbacks remain unfinished; the normal accounting-lifetime reset and termination wait below the loop are skipped. `pool` was already set to null, so calling shutdown again cannot recover that drained list.

Please keep ordinary observer failures from escaping the cancellation completion hook, and ensure one observer failure cannot prevent the rest of a drained queue from releasing. Recording/logging an observer failure is compatible with once-only invocation; retrying the callback is not required. Retain the existing finally that always terminates this Task's count.

Discriminating regression cases: (a) cancel a queued Task with a throwing observer and a captured payload, checking actual superclass-callable release rather than only the shadow field/count; (b) drain at least two queued Tasks where the first observer throws and verify every future is terminal and every later input/exit owner is released. The new `failedExitCallbackStillTerminatesCountExactlyOnce` runs a Task with `owner == null`; its observer runs from the outer `run` finally, after normal FutureTask cleanup. It cannot exercise either failure above. The added GameTest uses only nonthrowing AtomicInteger observers.

Evidence level: source-proven control flow plus inspection of the installed JDK implementation with `javap -c -p java.util.concurrent.FutureTask`; no new fault-injection job or source/test edit was made.

## Other reviewed paths

- The three-argument overload delegates with a null extra observer. The composite callback releases RuinInputs first and attempts the extra callback even if input release fails.
- Successful/exceptional Callable completion reaches Task.run's finally. Running cancellation alone does not uncount; the observer waits for actual run exit. Queued removal completes synchronously on the cancelling thread.
- Promotion moves the same callback without acquiring inputs again. It clears the old Task's observer before cancelling/uncounting the old future. The promoted Task owns the eventual exit notification. The concurrent-cancel and rejected-promotion branches were traced.
- Rejection cancels and uncounts the rejected Task; normal nonthrowing ownership releases there. Rejection-specific extra-observer execution is not directly covered by the new test additions.
- The Task monitor serializes ordinary concurrent uncount calls, clears the observer before invoking it and publishes `counted=false` after callback completion, including failure. Queue/credit locks are not held when normal exit observers run. Callback invocation may occur on a worker or the server/cancelling thread; a numeric, thread-safe, bounded callback with no world/preparation capture fits this seam. No external result-owner implementation is present at this pin.

## Observed validation and resource state

I read the supplied raw evidence, not the author's conclusions:

- `MT-WORKER-hook.log`: targeted unit task succeeded; all **10 required GameTests passed**, including the new `nuke_worker_exit` batch; wrapper exit 0.
- `MT-WORKER-hook-build.log`: full build completed successfully during review, wrapper exit 0. Current full-build XML contains **1786 tests, 0 failures/errors/skips**. Relevant suites: admission 8, workers 4, inputs 3, all green. This does not cover F1's queued/bulk failure routes.
- `MT-WORKER-fixture.log`: prior 4 required fixture GameTests passed. This is prior fixture compatibility evidence, not additional new-hook coverage.
- Reviewed pin and the author's current worktree both remained at `44c08410e58ab81bbb9a6a525bd6107b310066e7` when checked. `git diff --check` passed. No source/test changes, agents, heavy jobs, GPU/client/server/desktop/Crafty operations, deletion or GitHub actions were performed by this review. CI and author-owned jobs remain the author's wait responsibility; no independent CI verdict is claimed here.

Raw-log identities:
- `MT-WORKER-hook.log`: SHA-256 `a70d5310c6dfefc6bf853524cfcd8c6a306fd98237fc9ca866cb330ebde8a8c4`.
- `MT-WORKER-hook-build.log`: SHA-256 `461bc73039eb42bef6305807ad57987c2b4b3388a0fb28310156be64843956c5`.
- `MT-WORKER-fixture.log`: SHA-256 `1aa5d59d0d696a6bd74fae7d2deb586ff2e295f6f0ba09c316f6c4dbd50c5c84`.

Report returned to MT-WORKER and MT-S2 through their inboxes. Final integration/runtime/merge ownership remains LEAD2; result integration remains MT-S2. End of assigned review.
