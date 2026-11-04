Recorded 2026-11-04T14:08:32.745534+00:00

# Independent final worker admission review — RWORKER2

Reviewer: fresh Codex Sol xhigh session. Exact detached, clean pin: `153e4f86a4a3dba2a4a6e3dce4b0a791e6a47827`, worktree `/mnt/data/projects/incoming/mod/run/wt-RWORKER2`.

Reviewed the entire `097efc4ee419f5e4b5572f2fecc7e80ce04dd822..153e4f86a4a3dba2a4a6e3dce4b0a791e6a47827` component diff, with particular attention to `e70ccf06d..153e4f86a`. Read RuinWorkers, both new worker test files, relevant existing worker tests, and surrounding NuclearPrep, RuinContext, RuinMemory, RuinInputs, RuinInbox and NuclearWorld lifetime/admission paths. Current TEAM/context/BOARD/live, repository rules and relevant canonical memory were checked. The assigned review brief and RWORKER1 independent findings defined the scope; no author PR description, report or conversation was consulted.

## Verdict

**F1/P2 is addressed at the exact final pin. No remaining serious correctness, progress or workspace-lifetime defect was established in this component or its corrective delta.** The source review is clear for the component owner's integration process.

This is the explicitly requested second/final review of the new worker component, not a third review of its predecessor/core. LEAD2 retains integration, CI disposition and combined actual 100/1000 kt acceptance. Neither full JVM heap containment nor real-map throughput/whole-zone readiness is certified here.

## F1: stop before the first height wait

At RuinWorkers.java:238–255, leaving the credit monitor now cancels the Future when either the wait was interrupted **or the pool is shut down**. A worker whose task was already dequeued and whose first monitor acquisition happens after STOP therefore cannot enter that Task's callable. It does not depend on reaching credit.wait or on appearing in shutdownNow's drained queue.

Cancellation remains outside the credit monitor. This matters because Task.done (lines 507–514) wakes other credits; cancelling under one credit lock would create a lock-order hazard between workers. Task.run still reaches its finally/uncount path even when FutureTask.run does no callable work, preserving the completion callback and count release once. Existing cancellation of an already executing callable retains its original actual-exit ownership.

The new stopBetweenDequeueAndHeightGateCannotEnterAnUnfundedCallable unit (lines 212–246) deliberately holds that monitor until dequeue and shutdown have happened. It asserts cancellation, RuinWorkers.finished and zero final workspace. The supplied pre-correction log fails its cancellation assertion at line 243; the supplied final targeted run and full build pass. This discriminates the original hole rather than merely testing an already-waiting worker.

The post-monitor check does not promise to preempt a callable that already passed a funded gate when shutdown arrives. Existing solver interruption checks still handle that boundary.

## Factory reservation correction / former optional O1

Workspace.release (lines 198–205) centralizes the refund under the credit monitor and is idempotent through credit.closed. The same operation covers construction failure, the worker wrapper's final exit, and a factory-created Thread that never starts.

BirthRoom now records only grant-local cleanup callbacks. After the synchronous prestart calls return or throw, service's finally removes BIRTH_ROOM and checks those Threads (lines 267–290). A still-NEW result is refunded; a successfully started thread retains ownership until its wrapper exits. The grant list does not become a persistent Thread/world registry.

The new factoryResultDiscardedByShutdownReleasesItsUnstartedWorkspace test (lines 194–208) makes shutdown win after factory reservation and before Thread.start. Its pre-correction run fails with workspace still resident; the final run passes. This closes the concrete never-started path from O1. Failure of Thread.start itself is covered by the same finally/NEW logic in source, but no separate native-start-failure injection was supplied.

## Full component and surrounding lifetime checks

- Worker birth uses current resident headroom across dimensions, not the larger future-worker admission policy. Only server-thread servicing installs the ThreadLocal birth grant. Captured I/O executors preserve their pool/count lifetime and can enqueue after idle expiry without allocating an unfunded replacement worker.
- adapt services each eligible nuclear tick, before the slower CPU adaptation interval. Direct submissions and promotions also service admission. The new code introduces no solver, world/chunk read or join in this service.
- Numeric credits are charged before worker publication. Task cancellation/completion, CPU downsizing and idle residency do not lend live workspace. A shutdown survivor remains in the global counter across a later pool lifetime; shutdown does not reset that counter.
- Height growth is funded as a delta before the next callable. Existing charged buffers remain resident while a gate waits; cancellation and stop wake/terminate that task path. Server dimension heights are observed before production pool admission.
- Queued Task cancellation removes the callable and releases its input callback once. Running cancellation retains its slot/input owner until Task.run exits. Promotion transfers that callback to the replacement. RuinInputs detachment and RuinInbox/read reservations remain separate owners; the workspace change does not replace them.
- Workspace/Credit/global accounting contain executor/scalar bookkeeping, not a newly retained ServerLevel or preparation. Ordinary queued callables still retain their real inputs under the existing lifetime rules.

At height 384, one workspace estimate is 13,271,040 bytes. The test's 417,653,525-byte non-worker base plus eight credits is 523,821,845 bytes; a ninth would give 537,092,885 bytes against the 536,870,912-byte cap. These are admission estimates. Actual arrays, GC retention, completed result sizes and the rest of the JVM remain separate from this scalar guarantee.

## Optional note, not a blocker

RWORKER1 O2 remains a coverage improvement: the mixed SEEN/FAR unit does not positively assert actual SEEN start order. Existing queue-order tests and the unchanged priority comparator support the ordering implementation. No new ordinary-production priority failure was established; this does not warrant another review round.

## Raw validation evidence and limits

No reviewer test/heavy job, nested session, GPU/client/server/desktop action, source edit, GitHub comment or merge was performed. Read-only git diff --check passed; HEAD and clean detached status were rechecked before writing.

Supplied final-pin raw logs:
- MT-WORKER-final2.log: full build succeeds in 3m 51s; all 13 required GameTests pass; GameTest task succeeds in 20s; wrapper exit 0.
- MT-WORKER-stop-unit.log: targeted worker units succeed in 7s; wrapper exit 0.
- MT-WORKER-red.log: base097 with the new GameTest fails “workspace producer exceeded global cap”; wrapper exit 1.
- MT-WORKER-stop-red.log: the two corrective units each fail on the supplied e70 control, with expected-red startup=1 and stop=1. Its overall wrapper exit 0 denotes a successful negative-control script, not passing tests.

Observed SHA-256:
- final2: bd99e9ef1175d8e92f465139ca2aa909334b90dc1c9eb6cc2590853c491f74a8
- stop-unit: 519bc2b2a7418a60ceba3e68a0fefd957e9e8b564871eccdf1e0c074c0bd1838
- red: 6eb24f56c1c8a16a691cf39cccc252d7d761906bb25b6448eed25c009612e024
- stop-red: 5a78b49cd708012b1e6bbd6af220ac60c1d03510c900104ed5b4296df81b5d63

No unit total is inferred from overwritten XML. RWORKER2-inbox.md was absent at the final scope check. No reviewer-owned jobs/resources remain.
