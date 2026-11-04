# RCI-TERM independent source review

2026-11-04 16:12 UTC. Fresh gpt-6-astra / high session (confirmed in its running command). Scope: terminal diagnostic only; no core-model review.

## Pin and result

Worktree: `/mnt/data/projects/incoming/mod/run/wt-CI-ASTRA`; branch `codex/nuclear-ci-cause`.
Base: `f8481f253c9bed41c886fecc3137eeacc1617440`.
Reviewed HEAD: `9243cc6a50f4d9d0b9f3bc734797d7231a734db2`; working tree clean at final inspection.
SHA-256 of `git diff --binary BASE HEAD`: `87fa4edf0829f56277d528cd5e0c69fd7f999f377b91bdcdfed11c090950b05d`.
Two files, 87 insertions. The new `RuinPipelineDiagnostic.java` was explicitly opened in full; by review time it was committed, so the final diff includes it.

**BLOCKED: one observer-contract defect.** No Java type/access defect found by source inspection. Build/runtime validity remains untested, as requested. No claim about the cause of the earlier pipeline failure.

## Blocking finding

**P2 — The `resident` observation mutates the system being observed.**
`mod/src/devtest/java/ua/zentix/incoming/nuclear/world/RuinPipelineDiagnostic.java:39` calls `NuclearPrep.residentMemory(level)`. This is not a read-only getter at this pin:

- `NuclearPrep.java:544–552` traverses `NuclearWorld.retainedRuinBytes()` in all existing dimension attachments, then calls `RuinWorkers.residentBufferBytes(height)`.
- `NuclearWorld.java:181–189` calls `RuinSettle.retainedBytes()`. `RuinSettle.java:232` removes completed entries from `retiring` with `removeIf(CompletableFuture::isDone)`. This performs retirement/collection during the observation.
- The same path calls `RuinMatter.retainedBytes()` (`RuinMatter.java:49–56`), which updates `supportBacking` and `inventoryCapacity` accounting high-water fields.
- `RuinWorkers.java:639–641` calls `configureHeight`. At lines 610–618 that updates `bufferHeight` / workspace height and, when the configured bound differs, changes `size` and `active` and resizes the executor. The already-configured case may be idempotent, but this call chain is not an observation-only contract.

The task expressly prohibits collection/admission effects, and this callback runs before the final test outcome. Thus the current patch cannot be described as a passive terminal snapshot. This does **not** prove that these mutations caused or hid the historical failure.

Minimal correction: omit `resident` from this narrow diagnostic (retain the existing context estimate, result bytes and detached-input bytes), or read an explicitly passive numeric counter. Do not fix production accounting or worker policy in this observer patch. If workspace bytes are needed, directly read the existing atomic counter without invoking `configureHeight`.

## Checked behavior and evidence boundaries

- The original 1200-tick annotation, success predicate, assertions, their order/messages, counting-clock setup and successful cleanup are unchanged against the base. The only test-body addition registers one callback.
- Local vanilla/NeoForge source: `GameTestHelper.runAtTickTime` stores the runnable; `GameTestInfo.tickInternal` executes scheduled callbacks before sequence checks. Timeout uses `tickCount > timeoutTicks`. Consequently tick 1200 observation is reachable, runs once if the test is still active, and occurs before the final timeout at tick 1201. Earlier completed tests skip it. Reflection/formatting/logging occur inside that callback, not on ordinary earlier ticks.
- Reflection names and types match this source: queue `jobs`, job `events/event`, context `tasks/shots/absent/reading`, Task `future/since/priority`, static worker `pool`. Package placement permits the package-private APIs. Boxed Long lookups in fastutil maps are valid. No compile run was performed.
- Target state uses `getChunkNow` followed by the existing chunk's block state; scar uses `getExistingData`, so it neither loads a chunk nor creates the attachment. `ruinsIfKept` does not create or refresh a context. Plain `shots.get` does not perform the LRU-touching `getAndMoveToLast` used by production `shotAvailable`.
- `event` is the test detonation; `queuedEvent` identifies the target job's current event, which can differ. Context/task data belongs to the test detonation. Queue state is the existing coarse `diagState`, not a complete historical wait-reason trace. `pendingPlan` means a prepared plan exists, not all forms of pending work.
- `pendingReads` includes running and ready-uncollected context source ownership; `readyReads` is a boolean, not a count. `windowFailed` is context-wide. The three 25-bit masks match the current radius-2 source-window readiness predicate and expose exact relative keys; sourceReady includes live chunks, known absent chunks, and fresh disk snapshots. It does not prove that solver admission succeeded or that a loaded snapshot was captured.
- Task `since` is the existing timeout baseline (queued tasks can have it refreshed); it is not a reliable original enqueue timestamp. `started`, `futureDone`, cancellation and actual completion are distinguished correctly. `nearInFlight` includes NEAR and higher priorities; executor active/queue counts cover all priorities. They are sequential observations while workers may progress, not one atomic snapshot.
- `RuinResults.bytes`, `RuinInputs.detachedBytes`, context `retainedBytes`, `readyWindowReads`, `failed`, `windowFailed` and clock counters are passive on the inspected call paths. Context memory is an ownership estimate, not total JVM heap; result/detached bytes are components of the current resident estimate, not extra amounts to add to it.
- Reflection/runtime exceptions are caught within capture, preserving the normal assertion failure path. Fatal Errors are not caught; no source defect requiring broader Throwable handling was found.

## Optional clarifications (not blockers)

The class comment “never runs on its ordinary successful path” is too absolute: a test succeeding at tick 1200 (or 1201) can already have emitted this snapshot. Call it a deadline snapshot rather than proof of failure. Terminal logging also grants background workers wall time before the remaining sequence checks; a passing diagnostic run alone cannot prove the uninstrumented race disappeared. This is a limitation of the scheduled pre-timeout observation, not grounds to relax or replace the assertions.

## Validation and scope

Source-only review and `git diff --check` completed. No source edits, Gradle, compilation, tests, CI rerun, GPU, server, game or desktop action. No owned heavy jobs to wait for. Author report/handoff/inbox, PR description and old reviewer conclusions were not read. Integration #593 remains LEAD2-owned.

Vanilla source provenance: `/home/artem/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_0a412762a64eacb3d9f13f602653eb5df1d3774e_output.jar`.
GameTestInfo.java SHA-256: `8bbfda0f98a2265a5b7c7279a8d46f56343c0b6d37c127ebb6af44aa6624ed70`.
GameTestHelper.java SHA-256: `aebb7d5add7bc857f314e3698a3bb503d54e2b962d3e2c6f9dd1add9fb5a02de`.

В память: canonical memory unchanged, as requested; evidence is contained in this pinned review.


## Second round — 2026-11-04 16:16:53 UTC

**PASS for this narrow source review: the blocking observer finding is resolved; no serious residual defect found.** This verdict supersedes the first-round BLOCKED result only for the corrected pin below. It does not certify build/runtime success or establish the historical nuke_pipeline failure cause.

- Reviewed HEAD: `aa6e73ea1feec6db02daf7365b0a82a93fcd0d4e`; previous reviewed commit: `9243cc6a50f4d9d0b9f3bc734797d7231a734db2`.
- Worktree `/mnt/data/projects/incoming/mod/run/wt-CI-ASTRA`, branch `codex/nuclear-ci-cause`; clean at final check.
- Correction `git diff --binary PREVIOUS HEAD` SHA-256: `ea87856b8681a04b23ec071d71c33d7388d9d556ab6b8ae497089f28c18fe490`.
- Complete observer diff `git diff --binary f8481f253c9bed41c886fecc3137eeacc1617440 HEAD` SHA-256: `6591f6234b5698d04ead89f1270a58b932bbd2fed6304c0b1bf9bf713727ec82`.
- Exact correction: three added/three removed lines in `RuinPipelineDiagnostic.java`; production code, original test body/callback, rules and assertions are unchanged from round one.

The correction removes both the `resident` placeholder and the `NuclearPrep.residentMemory(level)` argument. There is no replacement call through the mutating aggregate accounting path. This eliminates the observer's access to settlement retirement removal, material accounting high-water updates and worker-height reconfiguration/executor resizing identified in round one. Remaining log placeholders and values still align.

I reopened the whole corrected observer and rechecked the remaining memory path: `ctx.retainedBytes()` → `currentBytes()` / `shotBytes()` → `RuinInputs.retained`, `RuinSnapshots.bytes`, `RetainedPlans.bytes` reads fields/container sizes only. `RuinResults.bytes()` and `RuinInputs.detachedBytes()` read atomic counters. Neither path calls the removed server-wide aggregate, configures workers, collects completions or opens reads.

The remaining worker observations are pool size/active/queue size, in-flight atomic counts, and Future/task state. `capacity()` can create a pool when called with no existing pool, but this observer guards that call with its reflected non-null pool snapshot; normal pool shutdown/replacement is on the server thread, which is executing this callback. No new-pool path was found for this fixture. Inbox pending/peek methods remain non-consuming. Chunk/context/maps are still observed without loading, creating contexts, touching LRU order, admitting/cancelling work or collecting results.

The revised class comment accurately says the snapshot is skipped for completion before tick 1200. The original deadline ordering and assertions remain untouched. The previous limitation still applies: this is a pre-timeout deadline snapshot, not proof that failure has already occurred; terminal logging gives concurrent workers some wall time. No additional blocker follows from that caveat within the requested diagnostic scope.

Validation: source inspection and `git diff --check` only. No source edits, build, GameTest, CI, heavy job, GPU, server or desktop action; quiet window respected. No owned jobs remain. Author conclusions/inbox and PR description were not read. Integration #593 remains LEAD2-owned.

В память: canonical memory unchanged per the review-only task; this append records the correction and exact source pins.
