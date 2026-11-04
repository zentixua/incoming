# RWORKERHOOK2 independent second/final review

Date: 2026-11-04 14:41:31 UTC. Reviewer: Codex, fresh `gpt-6.1-sol` session with `xhigh` effort, confirmed from the live launcher process.

## Scope and verdict

Exact reviewed pin: `87d412c15a874c0910999af4128603e2108f031b`, clean detached worktree `/mnt/data/projects/incoming/mod/run/wt-RWORKERHOOK2`. Full additive callback range: `b89f5a10d68d738156dee18382173959b9f069cd..87d412c15a874c0910999af4128603e2108f031b`. Corrective range: `17228fdb497aa2bab26c22b565f4b39c6c4fdda3..87d412c15a874c0910999af4128603e2108f031b`.

**No serious blocker established. RWORKERHOOK F1 is corrected on its queued-cancellation and bulk-shutdown paths.** The corrective delta introduces no established serious defect. One optional defensive observation is recorded below; it does not require another review round.

This is the second/final callback review. The earlier `153e4f86a` workspace model and the two fixture-only commits are excluded; surrounding executor/credit code was read only to trace callback ownership. The supplied independent `RWORKERHOOK-review.md` was used as raw F1 evidence. The review used diff, surrounding code, installed JDK bytecode and raw test logs, without reading the author's PR description or report. Current TEAM/context/BOARD/live, AGENTS, applicable area rules and relevant canonical memory were read.

## F1 resolution

`RuinWorkers.java:526–538` now takes and clears `onExit` before invocation, catches `RuntimeException | Error`, logs the failure, and always decrements the Task count and publishes `counted=false` in `finally`. Normal observer failure therefore cannot escape from `Task.done` through `uncount`.

The two original consequences are addressed:

- **Queued captured Callable:** `done` at lines 517–523 returns after the caught observer failure. The installed JDK 21 `FutureTask.finishCompletion` invokes `done` at bytecode offset 62 and clears its private `callable` at offset 67. With normal return restored, it clears the actual superclass payload, in addition to the subclass shadow. The new unit at `RuinWorkerAdmissionTest.java:266–302` reads the superclass field, first proves that it contains the captured Callable, then cancels the still-queued Task and proves that the field is null, the observer did not escape, the Task is finished, and its callback/count completed once. This discriminates the original retention bug.
- **Bulk queue drain:** `shutdown` at lines 673–679 cancels and uncounts each drained Task. A failed observer is contained by `uncount`, so processing continues to later drained Tasks and the accounting reset/termination wait remain reachable. `failedObserverCannotAbandonTheRestOfShutdownQueue` in `RuinWorkerAdmissionGameTests.java:169–218` holds a real running worker, queues the throwing observer before a second queued owner, detaches all three inputs, and checks every future's terminal/actual-finished state, all three once-only callbacks, full input refund, zero task ownership and repeat shutdown. It reaches the actual production shutdown path.

The corrected owner-null unit also verifies that observer failure no longer escapes `Task.run`, and repeating run cannot decrement or invoke twice.

## Surrounding ownership paths

The four-argument submit at lines 385–396 acquires `RuinInputs` once. Its composite releases input ownership first and attempts the extra observer in `finally`, including an input-release failure. The existing three-argument overload delegates with no extra observer.

Normal or exceptional Callable completion clears FutureTask state before the run-finally callback. Running cancellation alone retains Task ownership until actual run exit. Queued cancellation releases through successful queue removal. Promotion transfers the same composite callback to the replacement Task without reacquiring inputs; the old Task has its callback cleared before cancellation/uncount. Rejected submission or rejected promoted work cancels/uncounts its Task through the guarded callback path. Ordinary Task completion still publishes finished only after the callback terminates. These checks establish the seam's lifecycle, not the correctness of a future caller's numeric ledger or result publication.

## Optional defensive note

`RuinWorkers.java:443–445` still directly invokes the detached `onExit` in the `callable == null` promotion fallback. If another thread cancels after `p.remove(t)` but before the synchronized capture, a throwing observer there could escape `promote`. Current production promotion and context cancellation are server-thread operations; this race requires a cancellation caller outside that contract. Both the old FutureTask cleanup and the Task count are already terminal before this direct callback, so neither F1 payload retention nor F1 bulk-drain abandonment follows from this branch. No normal-game failure was established. If off-thread cancellation becomes a supported producer path, use the same failure isolation there and add a discriminating promotion-race test. This is nonblocking defensive coverage, not a new serious finding.

## Completed raw validation

All supplied local receipts finished before this report:

- `MT-WORKER-hook-fault-final.log`: exact final worktree remained at the reviewed pin. Full build succeeded in 6m24s; full-build XML totals were **1787 tests, 0 failures, 0 errors, 0 skipped**. Relevant suites: admission 9, workers 4, inputs 3. Both fault units passed. The following union GameTest run reported **all 15 required tests passed**, including `nuke_worker_exit` and `nuke_worker_exit_failure`; Gradle succeeded and `laptop_job mt-worker-hook-fault-final: exit 0` completed.
- `MT-WORKER-hook-fault-red.log`: the same new fault unit failed specifically at the actual superclass-Callable assertion (line 286), and the same shutdown GameTest failed with `shutdown abandoned a drained future`. Both underlying Gradle runs failed as expected; the wrapper explicitly reported `Expected-red queued_callable=1 shutdown_drain=1`, then exit 0. The negative-control worker source blob was verified identical to `17228fdb4` (`da2433537835e2312708644ade72a7d95f698676`); both new fault fixture/helper regions were byte-for-byte identical to the reviewed final source.
- `MT-WORKER-hook-fault.log`: earlier focused candidate succeeded, wrapper exit 0. It is supporting evidence only; the exact final receipt above supersedes it.
- `git diff --check b89f5a10d..87d412c15` passed. Reviewer and author worktree heads both remained at the exact final pin; reviewer worktree was clean.

SHA-256 identities:

- final log: `e65da7a2c9b5e1d88b185b4af115b4db7140af393f2396a282d2d64de1db7360`
- negative-control log: `400a0b77dbacced14825528a48feebda048190a2d08f96a902a65087a6162d20`
- earlier focused log: `0ed81c7a168a30de4b1d9ea6162595b4f6e424bca384a9a7ab921bdda64c2839`

CI is not certified by this review: the author's existing `MT-WORKER-ci3.log` waiter was still awaiting checks for the new head when last observed. The author retains that wait. No new heavy job, agent, source/test edit, GPU/client/server/desktop/Crafty action, deletion or GitHub action was performed by the reviewer.

MT-S2's caller numeric-token/result-publication implementation is absent from this pin and remains outside review. No full memory/runtime, real-map progress, integration, main-merge or release certification is implied. LEAD2 retains integration/main/runtime ownership; MT-S2 retains producer integration. This report ends the assigned callback review.
