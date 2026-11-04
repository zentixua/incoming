Recorded 2026-11-04T14:00:56.163148+00:00

# Independent worker admission review — RWORKER1

Reviewed exact range `097efc4ee419f5e4b5572f2fecc7e80ce04dd822..e70ccf06d59a349adfc16a409f0cfcf27f7c7993`, detached clean `wt-RWORKER1`. Reviewer: fresh Codex Sol xhigh session. Source, task lifetimes and raw logs only; no author PR description, report, conversation or conclusions were consulted. TEAM/context/BOARD/live, repository rules and relevant canonical memory were read. No source edits, extra agents, test jobs, desktop, GPU, server or GitHub actions.

## Result at the reviewed pin

One P2 correctness finding at the newly introduced height/stop gate. It should be corrected at the component owner before integration. No additional serious defect was established in ordinary worker birth, shared numeric room, cancellation accounting, pool resizing or shutdown-survivor accounting. Two optional hardening/coverage notes are separate below.

This is a source review, not acceptance of full heap, throughput, whole-zone progress or the combined real 100/1000 kt trials. LEAD2 retains integration and actual-runtime ownership.

## Finding F1 — P2: stop can bypass an unfunded height gate

Location: `mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinWorkers.java:235–245` (`OwnedPool.beforeExecute`).

The new gate stops waiting when `isShutdown()` becomes true, but only cancels the task if `credit.wait()` actually throws `InterruptedException`. A worker already holding a dequeued task can reach the gate after `shutdownNow()` has set STOP and interrupted it. Then `!isShutdown()` is already false, no wait executes, `interrupted` remains false, and `beforeExecute` returns to `ThreadPoolExecutor.runWorker`, which calls `Task.run`. The task enters its callable with the old-height credit and without receiving the required delta. The same hole exists if stop becomes visible while the worker is acquiring the credit monitor rather than waiting on it.

`RuinWorkers.shutdown` cannot fix this via its drained queue: the affected task has already been removed from the queue, so it is absent from `shutdownNow()`'s returned list (`RuinWorkers.java:646–651`). This is a new gate-boundary failure, distinct from cancellation of a callable already executing.

Deterministic discriminating reproducer (not run by this reviewer):

1. Start a one-thread `OwnedPool` at height 384, complete an initial task, and retain its credit/thread. Observe height 1024 without funding its delta.
2. Hold that credit's monitor in the controlling test thread. Enqueue a new `RuinWorkers.Task` whose callable increments an atomic counter. Wait until the worker has dequeued it and is blocked entering the monitor in `beforeExecute`; do not let it enter `credit.wait()` yet.
3. While still holding the monitor, call `shutdownNow()`, then release the monitor and await actual thread exit.
4. At this pin the counter can become 1 and the task completes normally rather than being cancelled, although its credit remained at height 384. A stop boundary must instead keep the counter at 0 and release the task/input charge exactly once.

Suggested correction: after leaving the credit monitor, cancel the task whenever stop/interruption or an unfunded height transition prevents entry, including stop observed before the first wait. Keep cancellation outside the credit monitor, as the current code correctly does for caught interrupts; `Task.done` visits other credit monitors.

Impact boundary: existing real ruin and flash solver roots call `RuinWorkers.checkStop()` before solver allocation (`RuinContext.java:652`, `FlashScorch.java:270`). Those guards reduce the current production impact. Captured executor callbacks for disk parsing/layout/copies do not all have that guard, and the executor's stated gate/no-unfunded-callable behavior is still false. This review does **not** claim to have reproduced oversized Collapse allocation, a whole-heap breach or player-world damage during shutdown.

The new `cancellationAndShutdownWakeHeightWaitersWithoutLendingTheirLiveBuffers` test (`RuinWorkerAdmissionTest.java:160–190`) checks already-blocked height waiters. It does not deliberately hold a worker between dequeue and its first gate wait, so a green result does not exclude this race.

## Optional notes — not additional blockers

### O1: account for a factory-created Thread that never starts

`Workspace.thread` charges global/local bytes and inserts a credit before returning a Thread (`RuinWorkers.java:168–196`). Refund occurs in the Thread wrapper's `finally`, or if construction/configuration throws inside the factory. `ThreadPoolExecutor.addWorker` can discard a factory result if shutdown wins before registration/start, or `Thread.start` can fail after the factory returns. Neither path enters that wrapper's `finally`; the scalar credit can remain permanently charged and constrain a later lifetime.

A bounded regression can pause a thread factory after charge but before returning, stop the pool, return the Thread, and verify zero resident charge after termination. Production grant servicing and normal shutdown are currently server-thread operations, so this review did not establish a normal concurrent-shutdown trigger; treat this as defensive lifetime coverage, not a reproduced default-server leak. Construction failure rollback already present inside the factory does not cover post-return rejection/start failure.

### O2: positive priority progress is only partly discriminated

The new callback unit submits a SEEN task behind FAR tasks and later cancels it, but its assertions do not prove that SEEN entered ahead of queued FAR work. Existing `RuinWorkersTest.queueGoesByPriorityThenByOrder` covers queue order, and the new pool retains that queue/comparator. A positive start-order assertion in an actual funded-birth fixture would strengthen the combined progress claim. Height-gated tasks have already left the priority queue and cannot be promoted by `remove`; this review found no ordinary startup violation because production observes all server dimension heights before pool admission. It does not promise preemption or bounded completion when no workspace fits.

## Accounting and caller-lifetime assessment

- The production pool is now `OwnedPool`; `newPool` remains a package helper used by older unit fixtures. The new units exercise the actual production factory rather than only a synthetic pool-size estimate.
- Only `service` installs `BIRTH_ROOM`. Normal `execute`, resize-triggered creation and an exiting worker's replacement attempt cannot obtain that server-thread grant. Captured I/O executors may enqueue into their captured pool but cannot recreate expired workers using stale room. `adapt` services the current pool each nuclear tick, and direct submit/promotion also service it.
- The numeric grant is computed from cross-dimension `NuclearPrep.residentMemory`, not the larger future-worker policy. Material/cold reservation producers use the same resident workspace counter. The service does not promise to preserve 64 MiB transition room after every birth; the separate future-preparation policy includes that margin.
- At height 384 one credit is 13,271,040 bytes. The new base fixture of 417,653,525 bytes fits eight credits at 523,821,845 bytes; a ninth would total 537,092,885, exceeding the 536,870,912-byte cap. These are workspace/admission **estimates**, not measured allocated or retained heap.
- Live/start credits are charged before publication and remain through task completion, cancelled futures and idle residency. Actual thread exit refunds them. Old pools surviving a bounded shutdown retain numeric ownership across new pool lifetimes; the global counter is not reset with `IN_FLIGHT`/`bufferHeight`.
- Queued Task cancellation removes queue ownership and releases `RuinInputs`; running cancellation retains actual input/task ownership until `Task.run` exits. Promotion transfers the original callable/on-exit callback and terminates the old future. The new workspace counter does not replace captured-input or inbox/read reservations.
- Height credits grow only after a resident-room grant. Normal gate waiting is outside `Task.run`, so an unstarted gated attempt does not consume its solver timeout. Still-live downsized threads remain charged until exit. The explicit stop boundary above is the exception found.
- `Workspace`, credits and the global counter contain scalar executor bookkeeping, not a newly captured ServerLevel/preparation. Existing queued callables continue to retain their real inputs under the existing Task/RuinInputs/inbox lifetimes.
- Servicing traverses retained owners on each direct submission and nuclear tick, with additional bounded credit/queue checks. Source inspection found no new chunk/world read, join or solver on the server thread. No runtime cost/latency number was measured here. Completed solver output sizes and other producers outside this diff still require the separately owned combined accounting/runtime checks.

## Evidence and limits

Read-only `git diff --check` for the exact range passed. The owner-operated `MT-WORKER-final.log` completed with `BUILD SUCCESSFUL in 3m 59s`, then all 13 required GameTests passed and the job exited 0 (`BUILD SUCCESSFUL in 23s`). The earlier supplied candidate logs show an 8-second successful unit task and 9 passing GameTests; they were explicitly not the final e70 source. No new reviewer job was needed or launched.

Raw-file SHA-256 at observation:

- `MT-WORKER-final.log`: `7d80b5629a532cde3e0175a9b35a615e9f1e67f2bcaa4f1ec9dd8d394abd6bc5`
- `MT-WORKER-unit2.log`: `07e710c30e737aee27a373f6f64ef8ccdb5667b99655f640487eebb83c150fba`
- `MT-WORKER-gt2.log`: `d9204da164954de6d69dd163a038f4b8ff57dc3d2d93ad21997868f6c5edab79`

During review the author's worktree advanced to `153e4f86a4a3dba2a4a6e3dce4b0a791e6a47827`. Its current XML had already been overwritten by a later targeted run (15 tests, including 7 admission tests), so those XML counts are **not** used as the full-build/e70 unit count. This report reviews only the requested e70 pin; it does not approve or reject the later source. No adjacent author report/MT-MEMDIAG report or actual-trial telemetry was read.

Source remains unchanged in detached `wt-RWORKER1`; no reviewer-owned jobs/resources remain. The component owner/root can use this report for correction and integration without treating it as actual-trial acceptance.
