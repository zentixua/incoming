# RCI6B second scoped independent review

Date: 2026-11-04. Reviewer: fresh Codex process 117758, `gpt-6.1-sol`, effort `xhigh`.

## Exact scope and verdict

Review worktree `/mnt/data/projects/incoming/mod/run/wt-RCI6B`, detached and clean at **8ca93cda2fcf74ed8a04cc7db81177c827e4bfbc**. Cumulative diff **f9d9f209c7c3f996ffc460d1ddac6e42d399a2f3..8ca93cda2fcf74ed8a04cc7db81177c827e4bfbc**, one file: `mod/src/devtest/java/ua/zentix/incoming/nuclear/world/RuinWorkerAdmissionGameTests.java`, 47 insertions / 5 deletions. Follow-up inspected separately from predecessor head **3978c8e3a827fd33de83940bf03ebb56b81e3bb1**. Final fixture SHA-256: `9e912fe7f7f1b186a8826289816d9ff96471c18c3c0f140e9250285282642d2d`.

**One remaining P2 fixture-isolation blocker:** the new pre-pressure fence omits outstanding settlement-header retirement. The predecessor's stale material-owner finding is corrected, but a legal asynchronous release can still change the supposedly fixed background after this fence passes. This is a source-demonstrated conditional path, not a reproduced failure in the supplied final runs or a production admission defect.

This is the requested second scoped correction review, not a third core review. CI6 retains fixture ownership; MT-S5 retains production; LEAD4 retains integration/runtime/delivery. No source/settings/world edits, nested agents, tests, clients, servers, GPU work or GitHub actions were performed. Own jobs/resources: **0**.

## F1 — include settlement retirement in the pre-pressure fence (P2)

Changed location: **RuinWorkerAdmissionGameTests.java:50-58**. Affected assertions: lines 109-110 and 128.

**Trigger:** clearing a world that still has one or more outstanding settlement chunk-header reads. The new fence can pass while those reads remain charged, because its checks cover ledger imports, preparation reads, material reservations, detached inputs/results and worker buffers, but not the independent settlement owner.

Concrete source chain at the exact reviewed head:

1. `NuclearWorld.clear` calls `settle.clear(level)` (`NuclearWorld.java:403`). `RuinSettle.clear` explicitly moves unfinished `Entry.read` futures into **retiring** before clearing the entries/order (`RuinSettle.java:264-275`). This is intentional ownership retention, not a synchronous completion fence.
2. Each such future owns **96 KiB** (`RuinSettle.java:46`). `RuinSettle.retainedBytes` keeps charging it until a later observation finds `isDone`, then removes it (`:231-234`). These are vanilla chunk-storage futures (`:247-254`), independent of `RuinWorkers.shutdown`.
3. This charge is included by `NuclearWorld.retainedRuinBytes` (`:190`) and therefore by `NuclearPrep.residentMemory` (`NuclearPrep.java:548-556`). It is **not** part of `world.prep().retainedBytes()` (`:531-539`), `materialReservation`, `ledgers.pending()`, `RuinInputs`, `RuinResults` or worker-buffer bytes. All new checks at fixture lines 50-58 can consequently succeed with this charge still live.
4. Calibration at fixture line 86 compensates for whatever retiring reads are currently charged. If three of them finish afterward, **294,912 B** legally disappears; this exceeds the unchanged ninth-worker denial margin **221,973 B** (cap 536,870,912; base 417,653,525; H384 worker 13,271,040). Correct admission may then fund nine workers and fail the exact-eight assertion.
5. Independently, if even one read survives until the full-cap charge at line 121 and completes before line 128, resident ownership becomes cap minus 98,304 B. The callback can remain correctly queued, yet the strict equality fails with “material charge lost before callback drain”. No production bug is required.

**Correction:** before calibrating pressure, also drain/observe settlement retirement for every dimension under the existing test deadline. Preserve actual ownership until completion; do not zero counters or weaken the exact-eight/global-cap/callback assertions. A discriminating fixture can hold a settlement-header future through clear, verify that the pre-pressure phase stays pending, then complete it and run the existing admission stages. This can remain devtest-only using access to the existing owner, without transferring production ownership from MT-S5.

The available original diagnostic rows have `settle=entries:0,retiring:0` and do not exercise this trigger. The finding is based on the explicit source paths above; I did not run a reproducer under this review's no-test restriction. It does not explain the original CI interleaving.

## Corrected stale-owner boundary and preserved coverage

The predecessor RCI6 finding is resolved by the `NuclearWorld.get(previous)` traversal at fixture line 51, after every SavedData replacement and before baseline calibration. That getter refreshes all three ledger references (`NuclearWorld.java:115-122`); `LedgerLoads.get` polls an existing import and rejects publication when its placeholder has been replaced (`LedgerLoads.java:17-31,69-80`). The sequence waits for outstanding imports and preparation ownership instead of assuming clear completed I/O. Successful worker shutdown is now asserted. Seeded 2,048 actual stone pieces are created and discarded in the same server invocation; the retained delta must exceed 262,144 B before cleanup. The post-`memory(level)` assertion at lines 91-92 detects a stale baseline disappearing during a later all-world refresh.

The core pressure test remains meaningful: ten tasks, one-worker control, future-worker admission refusal, exactly eight simultaneous held workers, shared cross-dimension resident cap, running `cancel(false)` retaining execution/workspace, all task exits plus genuine idle worker exit, original captured off-thread callback blocked at a fully charged cap, one-workspace release allowing rebirth, same executor identity and largest-pool-size bound. The 300-tick timeout is unchanged; setup now consumes part of that same deadline. No threshold, worker count, callback predicate or producer guard is relaxed. The other two GameTests in the file are unchanged.

Cleanup is registered before configuration, pool and synthetic reservation changes in `checkPressure` (lines 74-79). `StrikeGameTests.afterTest` invokes it for pass/failure/timeout. The new pre-phase itself holds no own live work or changed configuration after the reset; it clears prior fixture state. The initial global clear is appropriate for a server-wide test cap and precedes the intentional Nether reservation.

## Direct raw evidence and limits

Read: TEAM/context/current live; BOARD ownership records; repository AGENTS/applicable rules and canonical memory; predecessor independent `RCI6-review.md`; cumulative/follow-up diffs and surrounding production; the supplied original CI metadata/prefix JSON, original logs and diagnostic patch; final build log, stale-owner control patch/source/log, old-production final fixture patch/log, and final corrected-prefix instrumentation patch/source. No PR body, author inbox/report or author conversation was read. Canonical memory included predecessor facts; source claims were checked against this checkout.

- Original CI run **41896220491**, GT1 job **126806420364**, fails the exact-eight assertion (`original-ci-f9-gt1.log:1024`), without an actual worker count. The checkout merge `1568376b4998ce5fb4dd4767bd258d8ab08feb3e` and f9 source independently resolve to the same tree **dc894d4727ca9ce984ec148ada3776d3628d31aa**. The original cause remains unproven.
- The instrumented original prefix preserves the exact ordered **36 batches / 57 tests** through the target, independently compared with raw original rows and `original-prefix.json`. It instead fails later at `prefix.log:778`, on the full-cap callback equality. Raw rows show an unrelated context losing snapshots 80→64→48→32 and non-worker charge falling from 417,653,525 to 417,366,645 to 415,874,625 B. This proves a changing background in that run, not nine workers in the original CI run.
- Preserved `stale-owner-control.patch` changes only this test file: it retains the material seed and new baseline-refresh assertion, omits the final sequence's wait/refresh phase, and adds a diagnostic row. At inspection it contains **no separate helper-file alteration**. The raw control at `stale-owner-red.log:114-115` reports resident 430,602,229 versus expected 430,924,565 (**322,336 B lost**) and fails the intended refresh assertion; wrapper exit 1. This supports the stale-owner discriminator. It is not a negative control of worker over-admission or settlement retirement.
- Exact final `final-build-worker.log` finishes **BUILD SUCCESSFUL / wrapper exit 0**, with **3/3 focused worker GameTests passed** (`:159,184-186`). Preliminary `build-worker.log` was not substituted for this result.
- Supplied old-production **b5610b3c91a2c1e2e2d09d28c0fd503464a43982** control `OLD/mod/run/ci6-old/final-worker.log` records **1/1 PASS / BUILD SUCCESSFUL / wrapper exit 0**. Its preserved `old-production-final-fixture.patch` is byte-identical to this exact final cumulative diff (SHA-256 `5632a655581dc660dc9c359e33e85f5d080fb9937ece555efcdef10ad41cfaaa`). OLD's live source had already been restored at inspection; the patch/log, rather than that subsequently restored live file, provide the supplied provenance. This isolated compatibility control does not establish the original CI cause.
- Final `corrected-prefix.patch` was inspected against the pinned final fixture. It preserves the seed/wait and predicates, adds the same buffered field/atomic probe and prefix cutoff, and reintroduces a second `residentMemory` call for the stage-3 equality. This is instrumented evidence, not byte-identical final execution or a transactional ownership snapshot. `corrected-prefix.log` was still running at inspection (13/36 matching batches, target not reached). Its terminal outcome and final CI are **not certified by this review**; those are CI6/LEAD4-owned checks.

Stable raw SHA-256: original CI log `b653d5c16f438e58eceabb824ff0874a0cb81d9a749dc6a6a30a59912b38ade6`; original prefix `a43161f5f70d0a1c601ae9a357da6c42093e83be269646f1a82a8ff629aa6503`; original diagnostic patch `461eb4678ac66f285fd4c5970d413e31c414009246548f4c14e0c8722b511e9e`; stale-owner control patch `94841dcfc3e1f14dd825141824daab110ffe288da3725ad37385f5010872fb2d`; stale-owner raw `ad2e83086c39c887c0b0b2bc0f952c6068b350691e191cdb5e08beec2e706341`; old-production final raw `29902d751934e56f7d6ef5b3c227ee1d76d31f8aafd4905783a2952feef445ec`; final build raw `e3cffbc2da61a8150f5061be812418e1cee872285c018740cad2b8c44d361f47`.

No whole-pipeline, real-runtime, complete-heap, merge or release-readiness claim follows.

## Memory

Updated canonical `nuclear-fixture-lifetime.md` with the independent settlement-retirement boundary, exact pin and source-only evidence limits.
