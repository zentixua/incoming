# RMT-S2-OPT independent review

Reviewed head: `daf620c741aa1bf650b83e7771509e5a6609f1db`.
Base: `0d7eb539a` (the requested origin/main checkpoint).
Reviewer: fresh Codex RMT-S2-OPT session, read-only production scope.

Verdict: changes requested. Four source-level correctness findings below block the affected implementation. The latest integrated GameTest log also ends with two failures; those failures are recorded separately rather than assumed to prove either memory defect.

## Scope and method

The worktree `/mnt/data/projects/incoming/mod/run/wt-RMT-S2-OPT` was clean and detached at the exact requested head at the beginning and end of source inspection. Read TEAM/context/BOARD, current live instructions, repository AGENTS and nuclear/nuclear-world/server-chunks/work/GameTest rules, the canonical memory index and relevant cold-ledger lessons. Did not read author handoffs, PR descriptions, owner discussion or inbox contents. Source and raw logs were the basis of findings. No delegation, production edits, builds, test launches, GPU/game/desktop/server/settings changes, deletion, merge or public actions.

Reviewed all 32 changed paths, including the supporting tests and frozen ReferenceCollapse. Surrounding code inspected includes RuinPlanner, ColumnScar, NuclearWarhead, Terrain, LedgerData/Loads/Reader, RuinMemory and the affected ScarQueue/worker/context routes. Public facade methods were followed through NuclearPrep and NuclearWorld. CTL2's UI/order/packet and NuclearWarhead/NuclearStrikes forwarding changes are outside this diff and were not reviewed or accepted here. Missing caller integration in this pinned tree is not reported as a backend defect.

Line references below are relative to this pinned worktree. Static reproducers describe deterministic source paths; no new Minecraft reproducer was executed in this read-only session.

## Blocking findings

### F1 — P1: spilled plans lose structural material ownership and container cargo

Locations: `mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinPlan.java:405` (detach), `:378` (store), `:414` (restore), `:817` (capture/apply), `:865` (default material list). Producer: `RuinPlanner.java:328`. Production spill path: `RetainedPlans.java:66` and `:82`.

`RuinPlanner.finish` assigns the structural candidate positions with `plan.material(c.changes.keySet().toLongArray())`. The new detached copy does not copy `material`; the new page encoder does not write it, and restore does not read it. Every restored plan consequently has the default empty `long[] material`, even when its block delta removes an entire building.

This is reached during ordinary large preparations, without a restart: RetainedPlans writes `plan.detached().store()`, evicts successfully written plans above the 16 MiB resident target, and later restores them on demand. The restored plan still passes `current`, writes the same removal cells, and marks the chunk ruined. However, its material capture loop is empty. `RuinMatter.release` and `support` receive no removed pieces. Structural blocks disappear rather than entering transport or support reconciliation. For a removed barrel/chest, ColumnScar.replace explicitly clears the inventory before replacing the block (`ColumnScar.java:79`); there is now no captured cargo owner. This is irreversible normal-play data loss, not only a cosmetic or accounting difference.

Minimal regression: create a real structural plan which removes at least one masonry block and a populated container; record its material candidates. Compare original, `detached()`, direct `restore(store(), ctx)`, and actual RetainedPlans spill/read. Apply each against identical fixture worlds. Assert the same released/per-type mass, pending support and cargo before saving, then save/reload the resulting material ledger. Also check the ordinary spill route with a low resident target so this does not depend on a whole-zone run.

Why current tests miss it: RuinStorageGameTests.roundTrip compares encoded tags and `differs`; both representations/comparators omit material. Equality of the same incomplete serialized schema cannot establish complete plan equivalence. A small static probe confirmed that detach, store and restore contain no material handling. Preserve this field at every boundary and invalidate/rebuild old incomplete cache pages rather than interpreting their omission as zero structural material.

### F2 — P2: the new worker charge can permanently prevent preparation on supported thread counts

Locations: `mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinWorkers.java:387`; `NuclearPrep.java:489`, `:566`, `:674`, `:913`. The existing setting permits 0–256 threads (`IncomingConfig.java:261`).

The per-thread estimate increases from 7 to 15 bytes per 48×48×world-height cell, but preparation still reserves `max(configured size, live pool size)` for every pool thread, plus the 64 MiB transition reserve, before admitting even a single window. There is no memory-based adjustment of configured/admitted solver parallelism. Idle timeout and external-MSPT backoff do not reduce this charge because it uses `size`, not `active` or only the live pool.

At ordinary height 384, 46 threads contribute 610,467,840 bytes = 582.1875 MiB before any plans/reads. Adding transition room gives 646.1875 MiB against a 512 MiB total cap / 480 MiB preparation window. The old 7-byte estimate plus transition was 335.6875 MiB. Even 33 threads now consume 481.65625 MiB with transition alone, already above WINDOW_CAP.

Reproducer: a 48-logical-core dedicated server with default automatic threads (48 minus two = 46), or any server with the valid `ruin_threads=46`, height 384, and a saved target. Once the pool has been initialized, an unlaunched handle cannot enter geometry/surface resolution or fill its first snapshot window; status remains PAUSED/MEMORY. Letting the workers expire does not recover admission. If the pool is initially absent, the first disk read can initialize it and close subsequent admission before a full 5×5 window exists. Existing scheduled preparation is likewise unable to fill its disk windows.

Regression should exercise the preparation admission path at a high configured/automatic thread count, both before and after pool initialization/idle expiry. Fit actual permitted concurrency and its buffers into the window while retaining honest accounting; simply reverting the byte charge would conceal the allocation that was added.

### F3 — P2: prelaunch surface resolution treats a ceiling dimension's roof as terrain

Locations: `mod/src/main/java/ua/zentix/incoming/nuclear/world/NuclearPrep.java:669` and `:612`. Existing geometry contract: `mod/src/main/java/ua/zentix/incoming/nuclear/NuclearWarhead.java:115` and `:122`; Terrain source selection: `util/Terrain.java:315`.

For a ready Nether/ceiling-dimension chunk, Terrain returns `Source.CEILING`, deliberately distinct from CHUNK. NuclearWarhead.ground rejects CEILING and therefore leaves the requested in-cavern target height unchanged. The new resolver accepts only live CHUNK, falls through to DiskShots, primes/reads the same roof heightmap, then returns it as `Source.DISK` without checking the dimension's ceiling semantics. `surface=true` converts that height into resolvedTarget and preparation ground.

Reproducer: use a saved, ready Nether chunk with its normal bedrock roof, request `prepare(... target at y=64, surface=true, airBurst=false)`. NuclearWarhead.surfaceAt(level, target) remains at the requested y because the heightmap describes the ceiling. The new facade resolves to the roof's first-air height (normally about 128) or can fail on a not-yet-saved live target instead of using the valid live target geometry. An accepted order using resolvedTarget is moved to the roof; an order retaining the original target cannot bind. The public backend has no Overworld-only restriction.

Share the same source/dimension rule with ordinary geometry for live and disk inputs. Extend the surface-resolution regression to a ceiling dimension and assert equality with the ordinary geometry/surface contract, without loading a cold target synchronously.

### F4 — P2: far preparation restores cached snapshots without source validation

Locations: `mod/src/main/java/ua/zentix/incoming/nuclear/world/NuclearPrep.java:847` and `:924`. Compare the properly validated window route at `RuinContext.java:250` / `:263`.

When far() receives a cached snapshot, it checks only its encoded coordinate and calls `restoreShot`; the current world chunk is read only if the cache is missing or throws. It never calls restoreArchived or compares the snapshot against current source blocks/scar. In contrast, requestWindow reads both inputs and rejects stale unscarred snapshots through restoreArchived.

A concrete affected sequence is a legacy scheduled preparation resumed after clean restart: its numeric strike/geometry/content path is reopened, so a stored snapshot may predate a later player edit and save. A still-unplanned adjacent center uses this old snapshot to solve its blast/support window. Its own old delta cells need not have changed, so RuinPlan.current can accept the resulting wrong plan. A similar read-back occurs after cold snapshot eviction during a flight. The later source-cell validation at application only checks that center's removal cells; it cannot detect changed shelter/support in the neighboring cached chunk.

Regression: use the stale/edited unscarred snapshot fixture from RuinStorageGameTests.reopenedPagesValidateLaterSourceEdits, but deliver it through the real NuclearPrep.far archived completion/read-ahead path. The currently stored source must win for an unscarred edit; separately retain the pre-wave original for an already scarred neighbor. Then compare the affected center's complete plan with a fresh-source solve. The existing test invokes restoreArchived directly and therefore bypasses the defective production entry point.

## Validation evidence and limits

Raw evidence read, without relying on report verdicts:

- `/mnt/data/projects/incoming/mod/run/wt-MT-OPT/mod/run/mt-s2-corpus.log`: 24 selected tests, all passed; fixture batches include optimization, storage, lifecycle, flash, prep and zone paths.
- `mt-s2-paired.log` and `mt-s2-paired-final.log`: each has 768 alternating pairs (24 grids × 32 samples), of which 576 are after the first eight warm samples per grid. Both runs finish with 2 required tests passed. Inspected `/mnt/data/projects/incoming/mod/run/wt-MT-OPT/mod/run/mt-s2/PairedCollapseBench-final.java`: old/new order alternates on the same grid; equality assertions execute for every pair. ReferenceCollapse matches base Collapse after class-name replacement, apart from its added provenance comment.
- Final paired post-warm median per-pair new/old ratios, recomputed directly from raw rows: dense 0.832, natural 0.793, columns 0.233, partial 0.861, floating 1.171, border 1.109. Thus the benefit is input-dependent; these are solver-fixture timings, not full preparation or real-map acceptance. The fast path's vertical contact/anchor condition was also traced against the full support/detached predicates; no independent fast-path correctness fault was found here.
- `/mnt/data/projects/incoming/mod/run/wt-MT-B2/mod/run/mt-s2-b5-final.log`: 8 ledger/craft tests passed. Inspected actual LedgerStorageGameTests: the delayed real SavedData file overload, packed/legacy/streamed cargo equality, cold admission/drain and aggregate migration tests. LedgerData's frozen owner and new FIFO decode preserve cargo/key order in the inspected paths; no separate FIFO/cold-ledger data-loss finding beyond F1.
- `/mnt/data/projects/incoming/mod/run/wt-MT-P2/mod/run/mt-p2/carrier-final.log`: build successful and 16 selected backend/lifecycle/storage tests passed. Earlier final/last raw logs were also inspected. These are a backend checkpoint, not acceptance of CTL2's caller chain.
- `/mnt/data/projects/incoming/mod/run/wt-MT-OPT/mod/run/mt-s2-integrated-compile.log`: compileJava, compileDevtestJava, compileTestJava and test finish successfully.
- The newer `/mnt/data/projects/incoming/mod/run/wt-MT-OPT/mod/run/mt-s2-integrated-gt.log` was available by the end of review: 74 selected tests; line 397 fails removedPreparationKeepsUnfinishedReadsCharged, line 441 fails preparationAdmissionCountsOtherStrikesAndDimensions, line 801 reports two required failures, line 843 is BUILD FAILED. These exact-equality assertions can observe ambient ownership changes/cleanup, so static review does not equate these log failures with proof that detached I/O reservations are actually lost. They still need resolution/isolation and a passing required run; this report does not grant a green-check exception.

The FIFO/page tests, READY receipt gating, owner/lease/bind identity and backend cancellation routes were read. Required plan writes are tracked through both queued keys and receipts; failed receipts do not certify READY. Running/cancelled callback charges remain attached to the preparation lifetime counter; per-handle cache UUIDs avoid replacement writers reopening the same archive. The page queues have bounded active I/O counts. These observations do not establish a complete JVM heap bound or acceptance of 1 Mt, 10–15 minute preparation, client delivery, physical look or real-world gameplay. No release, yield-ceiling or merge acceptance is implied.

## Optional comments

- The comparator/serialization completeness problem in F1 should be prevented with a durable-output inventory or an assertion that covers every authoritative plan field. Encoded-tag equality alone is insufficient even when called a complete-plan comparison.
- Failed plan writes are retained/retried safely, but status can remain STORING with Reason.NONE after repeated I/O failures. Surfacing the failed receipt as an I/O reason would make the caller's state more useful; no false READY was found from this condition.

## End state

Production worktree remains unchanged at the reviewed commit. No owned jobs, CI watcher, review child, server, client or lease was started. Findings are returned to MT-S2 for implementation; ownership was not transferred to the reviewer.
