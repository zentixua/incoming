# RCI6 independent source review

Date: 2026-11-04T19:52:49Z. Reviewer: fresh Codex session, actual process `109748`, `gpt-6.1-sol`, effort `xhigh`.

## Scope and verdict

Exact reviewed diff: `f9d9f209c7c3f996ffc460d1ddac6e42d399a2f3..3978c8e3a827fd33de83940bf03ebb56b81e3bb1`, only `mod/src/devtest/java/ua/zentix/incoming/nuclear/world/RuinWorkerAdmissionGameTests.java` (11 additions, 3 deletions). Detached review worktree `/mnt/data/projects/incoming/mod/run/wt-RCI6`, clean at the exact head. Production code is unchanged by this diff.

**One P2 fixture-correctness finding remains.** Clearing unrelated contexts addresses a source-demonstrated changing background, but the new material reset can itself leave stale accounting until the first admitting memory traversal. Correct that ordering before treating the fixture as isolated. No production admission defect or player/runtime regression is established by this review.

This is the first independent CI6 review, not a request for a third routine round. CI6 retains fixture implementation; MT-S5 retains production; LEAD4 retains integration/runtime. No builds, tests, servers, GPU work, source edits or external posting were performed. No owned jobs/resources exist.

## Finding F1 — refresh every world's ledger reference after replacement, before calibrating pressure (P2)

Changed location: `RuinWorkerAdmissionGameTests.java:29-32`, specifically the new `clearFixture(previous)` at line 31; affected calibration is lines 54-58.

**Trigger:** another completed fixture leaves a populated material ledger in the overworld or End, with an old-versus-empty retained-estimate difference of at least **221,973 bytes**. The new loop replaces that SavedData, but does not refresh the corresponding `NuclearWorld.matter` reference. Only the Nether is refreshed by `NuclearWorld.get(other)` at line 37. This is a concrete source-level order dependency; the supplied raw runs do not execute this explicit populated-ledger discriminator.

Source chain at reviewed head:

1. `NuclearStrikes.clear` (`NuclearStrikes.java:171-175`) calls `NuclearWorld.get(level)` before clearing. `NuclearWorld.get` (`NuclearWorld.java:115-122`) attaches the currently cached material ledger to the world owner.
2. `NuclearWorld.clear` (`NuclearWorld.java:400-420`) unloads the ledger, but does not replace or null the `matter` reference. `RuinMatter.unload` (`RuinMatter.java:231-235`) releases its ticket/search; retained pieces, balances, backing storage and palette remain.
3. `RuinMatterGameTests.clearFixture` (`RuinMatterGameTests.java:25-27`) unloads that current ledger and replaces the SavedData entry with a new `RuinMatter`, without rebinding the world owner.
4. `NuclearPrep.residentMemory` (`NuclearPrep.java:548-556`) traverses the already-attached worlds directly; `NuclearWorld.retainedRuinBytes` (`NuclearWorld.java:184-192`) reads their `matter` fields. Therefore calibration at line 54 and its immediate equality assertion can both count the displaced old ledger.
5. The first submission's service path likewise uses `residentMemory` (`RuinWorkers.java:259-264,380-386`). Then the existing `NuclearPrep.memory(level)` assertion at line 58 calls `NuclearWorld.get` for **every** dimension (`NuclearPrep.java:565-580`), replacing stale references with the new empty ledgers. The calibrated non-worker total drops within this same setup step, before the remaining nine submissions.

At height 384 each worker is `48*48*384*15 = 13,271,040` bytes (`RuinWorkers.java:610-612`). With cap 536,870,912 and base 417,653,525, the ninth worker is denied by only 221,973 bytes. A stale ledger difference at least that large now legitimately funds a ninth worker; the unchanged exact-eight assertion fails even with correct admission and no global-cap breach. No bad worker-accounting implementation is needed for this failure.

**Correction:** refresh `NuclearWorld.get(previous)` after each SavedData replacement (or equivalently refresh all dimensions after the reset loop), before obtaining/calibrating the baseline. Keep the exact-eight, global-cap, cancellation and captured-callback assertions unchanged.

**Missing discriminator:** seed a nonempty overworld or End material ledger with a retained delta above the ninth-worker threshold, execute the reset, and verify the world owner refers to the new cached ledger before charging pressure. Verify the non-worker baseline survives both `residentMemory` and the subsequent `memory(level)` traversal, then exercise the existing admission stages. This distinguishes stale ledger ownership from worker-birth over-admission. No production getter/guard relaxation is necessary.

## Behavior and lifecycle assessment

The new global reset is confined to this one-test GameTest batch. Clearing all server dimensions is appropriate to a server-wide cap; it does not remove the deliberate Nether reservation used to test cross-dimension admission, because that reservation is created afterward. The loop uses normal cancellation/clear and the established devtest ledger/ticket helper. Global configuration mutation, latch release, worker shutdown and reservation restoration still have `afterTest` cleanup for success/failure/timeout. No extra production dependency or public behavior is added.

The core regression checks remain: ten submitted tasks, eight held workers, the global resident cap, refusal under future-worker policy, a cancelled running Future retaining actual workspace, genuine idle thread exit, an off-thread callback delivered through the original captured executor at a full material charge, one-workspace release enabling callback rebirth, executor identity and maximum pool size. The diagnostic changes reuse one already-required per-tick resident observation and preserve assertion predicates/deadlines. The entry count measures fixture callable execution, not result solving or total nuclear throughput.

Clearing also invalidates earlier window/preparation work. In production, invalidated asynchronous reads/receipts and shutdown survivors can stay charged until actual completion (`RuinContext.java:1152-1171`, `NuclearPrep.java:2055-2078`, `RuinSettle.java:264-275`). The reset is not a universal completion fence. This is an additional **precondition/coverage limit**, not a second demonstrated failing path in the supplied run. A fixed-pressure test must not interpret a lawful release of such prior ownership as a production cap violation.

## Raw evidence and its limits

Read directly: `wt-CI6/mod/run/ci6/original-ci-f9-gt1.log`, `prefix.log`, full `diagnostic-prefix.patch`, `original-prefix.json`, `ci-metadata.json`; relevant source/cancellation/accounting code; `wt-CI6-OLD/mod/run/ci6-old/worker.log`; available `build-worker.log`. No PR body, CI6 inbox, author report or author conversation was used. TEAM/context/live, BOARD owner records and canonical memory were bootstrap inputs; BOARD diagnosis prose was not used as review evidence. Source claims above were checked against this checkout.

- Original CI run 41896220491, GT1 job 126806420364: the merge checkout `1568376b4998ce5fb4dd4767bd258d8ab08feb3e` and base `f9d9f209...` independently resolve to the same tree `dc894d4727ca9ce984ec148ada3776d3628d31aa`. Original raw lines 1017-1027 show `nuke_worker_admission` failing the **exact-eight** assertion. It contains no terminal resident/entry/pool discriminator. The exact original cause remains unproven; do not claim the log proves nine workers or a production breach.
- The local instrumented original prefix preserves the first 36 actual grouped batches / 57 tests, ending at this target. It fails **later**, on `material charge lost before callback drain` (`prefix.log:778`), rather than reproducing the original count assertion. The patch adds buffered field/atomic observations and the batch-tail cutoff, not cleanup or changed assertion semantics. Measurements are sampled, not an atomic all-fields snapshot or an overhead-free production run.
- Its held-worker observations (`prefix.log:779-786`) show non-worker ownership changing: charged 417,653,525; stage 1 417,366,645; stage 2 415,874,625. The eight workers own 106,168,320 bytes while held. A prior context remains present; its snapshots drop 80→64→48→32 and shot/fracture bytes decline. At callback stage, resident is 536,349,020 (521,892 below cap), workspace 0, progress 10, queue/in-flight 1. These rows demonstrate a changing unrelated background and a different failing invariant; they do not establish the exact original CI scheduling interleaving.
- The old-production control is base `b5610b3c91a2c1e2e2d09d28c0fd503464a43982` with the exact same corrected devtest file (SHA-256 `396c97f5561cbd162824fc046062de057c80df9391aea71c9d8ab0a7211ad196`, independently matched). `worker.log:198-226` reports 1/1 PASS, build success, wrapper exit 0. This establishes compatibility for that isolated control, not a negative control of the original contaminated prefix or a validation of new production behavior.
- At final evidence check, exact-head `build-worker.log` was still inside `:test` with no terminal result, and `corrected-prefix.log` did not exist. Their outcomes are **not reviewed/passed here**. CI6 owns those running jobs; this read-only review need not wait for them. No final CI or runtime readiness claim is made.

Raw SHA-256 at inspection: original CI log `b653d5c16f438e58eceabb824ff0874a0cb81d9a749dc6a6a30a59912b38ade6`; instrumented prefix `a43161f5f70d0a1c601ae9a357da6c42093e83be269646f1a82a8ff629aa6503`; diagnostic patch `461eb4678ac66f285fd4c5970d413e31c414009246548f4c14e0c8722b511e9e`.

## Memory

Added the source-pinned SavedData/world-owner refresh boundary to canonical `nuclear-fixture-lifetime.md`. It records the conditional trigger and explicitly does not claim a reproduced original CI cause.
