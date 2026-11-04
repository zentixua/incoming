# RNEAR-S2 independent source review

Reviewed 2026-11-04, Codex / gpt-6.1-sol / xhigh, fresh detached worktree `/mnt/data/projects/incoming/mod/run/wt-RNEAR-S2`.

Exact range: `4d89b5b113a53b3cfe8822092e1813750516c9e9..02a9d6e048f94a8645da503a2a55df8331471124` (`codex/nuke-near-ready`). HEAD was explicitly checked out before source reads; working tree clean. Scope is the new near-first READY boundary and its immediate consumers, not a third review of the existing large core. Current TEAM/context/BOARD/live, repository rules and relevant canonical memory were consulted. No PR body, author reports/conclusions, inbox contents, STATUS or prior review reports were read. The supplied review-only task is the assignment; MT-S2 retains implementation ownership.

Three introduced defects need correction. These are source-proven reachable sequences, not claims of an executed reproduction.

## F1 — P1: unloading a near task permanently loses its result collector

**Changed location:** `mod/src/main/java/ua/zentix/incoming/nuclear/world/NuclearPrep.java:1160-1162` (also the newly enabled handle submit path at 701). Supporting lifecycle: `NuclearPrep.java:480-485, 851-870`; `NuclearWorld.java:342-348`; `RuinContext.java:655-656`.

1. An explicit handle starts a NEAR task for a loaded critical chunk C; C is added to `submitted`, and `nextTodo` advances. Choose C inside `ruinRadius` but outside the heavy-zone `order`, so the far collector cannot own it.
2. C unloads before harvest, e.g. a player leaves that part of the view. `NuclearWorld.onChunkUnload` calls `prep.forget(C)`. This removes C from `submitted`, but deliberately does not call `p.ruins.forget(C)` for handles. Before handoff that context is not in `NuclearWorld.ruins`, so its task remains alive.
3. The task completes, but harvest only visits `submitted`; nobody collects it. `required` still contains C and there is no plan.
4. Reloading does not repair it: refresh requeues C, but the new `running(C)` branch merely promotes it and advances the cursor. `running` includes completed, uncollected tasks. It never restores a collector.

The renewed unpaid request can remain PLANNING indefinitely, blocking launch after ordinary movement and retaining the task/window. Within the heavy zone the far cursor also skips an already-running near task without adding it to `farRunning`, so selecting an outer C is only a clean discriminator, not a necessary restriction.

**Focused regression:** real handle plus a gated real NEAR task for an outer critical chunk; deliver its registered unload before completion, then release the worker and renew the handle. Assert that an unloaded non-target stops blocking readiness, or that its preserved task is collected. Reload it and require READY plus successful bind without cancel/recreating the handle. Check the completed task does not remain an ownerless entry. Merely loading a new chunk, as the new test does, does not cover this sequence.

## F2 — P1: the new loaded-edge path accepts a source read failure as solid terrain

**Changed location:** `NuclearPrep.java:1175-1179`. Supporting code: `RuinContext.java:227-270` (especially 237-239 and 256), `DiskShots.java:151-152, 172`, `RuinContext.java:84-90`, `RuinWindow.java:19-20, 69-74`; contrast the existing explicit-source discriminator in `NuclearPrep.java:883-887, 950-954`.

The new handle path calls `requestWindow` for cold neighbors. That helper adds *every* non-null skip reason to `absent`, including `read error: ...` and `section N not parsed`. It then reports the window complete. A cold failed neighbor has no snapshot, so the solver sees the window's unbreakable supporting mass. No exception reaches the new catch, and no failure is propagated to the handle.

Choose a loaded critical C well outside the heavy-zone window margin, with a cold adjacent source N that exists on disk but fails reading/decoding. The heavy-zone reader never visits N, so its stricter error handling cannot rescue this case. C receives and stores an ordinary plan from the truncated window; all required keys can then pass READY and bind. The absent neighbor can change exposure/support and therefore the physical output near the player. Inside the heavy zone, early bind before the independent far read reports the error is another possible window.

This was an existing reactive-window fallback, but this diff newly uses it to certify explicit prelaunch readiness. Genuine absent/partial terrain and failed required source reads must stay distinguishable on this path too.

**Focused regression:** exercise the actual `RuinContext` window-read inbox for an outer loaded critical chunk, injecting a completed `DiskShots.Read.skipped(N, "read error: injected")` and a decode-failure reason in separate cases. Hold unrelated far progress, keep the handle alive, and require FAILED/IO (or an explicit non-ready retry), with both bind routes rejected. Companion cases for `not on disk` and `not fully generated on disk` must still progress. The existing source-failure tests inject `Prep.reads`/`Prep.archived`, not this new window route.

## F3 — P2: a completed failed near task outside the heavy order leaves a permanent PLANNING hole

**Changed location:** `NuclearPrep.java:854-870` (the added failure check at 855 only runs when `done` is false); handles newly enter this collector through 701. Supporting code: `RuinContext.java:676-684, 739-753`; `NuclearPrep.java:1039-1042`.

For a critical C outside heavy `order`, let its NEAR solver complete exceptionally. `done(C)` is true, so harvest bypasses the new failure check, removes C from `submitted`, and calls `collect`. `collect` records `failed(C)` and returns null after removing the task. The re-add condition requires `running(C)`, now false. C remains considered and required, has no plan, and is not revisited by the heavy-order failure check. The request is neither FAILED nor retried; it remains PLANNING while renewed. A timed-out task whose actual execution exits before harvest can take the same branch.

**Focused regression:** an outer critical real task whose callable throws before the next harvest; assert FAILED/IO or a bounded resubmission that ultimately completes. Also cover expired/cancelled execution already exited before harvest. Do not pre-set `p.failure`: that only tests presentation of a flag and misses this transition.

## Other inspected boundaries and limits

- Bind and bindCarrier refresh membership before checking READY. The new metadata set participates in `metadataBytes`; existing retained traversal includes preparations/contexts across dimensions. No separate introduced cross-dimension accounting defect was identified in this diff.
- `pendingWrite(key)` tests both queued and in-flight critical plan writes. Existing failed plan receipts requeue their key rather than silently opening READY; unrelated far receipts no longer block launch. This inspection does not claim fsync or atomic region/SavedData commits.
- Layout/geometry/physical equations and heavy-zone ordering are unchanged. Partial handoff transfers the same plan map/context; existing post-event far planning and durable settlement remain reachable. Eventual completion and near-wave latency under actual runtime contention were not established by this review.
- The added test has a real nonempty target plan and a useful late-load discriminator. It mutates cursors to hold far work, injects `toWrite`/`failure` flags, directly calls handOff, and only checks that far admission resumes. It does not test actual failed window I/O, unload/reload collector ownership, failed near completion, the normal event/save/settlement lifecycle, or completed distant terrain delivery. New tests are source assessed here, not execution evidence.
- No new zone-wide per-tick traversal was added. There is now a scan of required keys for status and the existing loaded-holder scan on explicit bind/refresh; their cost at large player/chunk counts is unmeasured here. No frame/tick-budget or total heap acceptance is inferred from source.
- Full 1 Mt, real city runs, actual wave timing, restart settlement, total resident heap and LSS-to-render convergence remain runtime acceptance work owned by the implementation/root sessions.

## Final state

No source modifications, tests, Gradle/build, server, GPU, CI watcher, review child, public message or publication was started. No merge/approval authority exercised. Detached HEAD remains `02a9d6e048f94a8645da503a2a55df8331471124`; source worktree clean. Report and a short sourcefinding were written only to the authorized session outputs; a generic review lesson was added to canonical memory.

**ownjob0** — zero owned heavy jobs, test/server/client processes, GPU lease or CI/review waits.
