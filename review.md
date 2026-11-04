# RFLAKE3 independent source review

Checked head: `84e845c563258f0e73e6f909fe34c1742cd431e4`.
Base: `eee272309e1c8306952f32b5f6b7ed4192be2e7c`.
Worktree: `/mnt/data/projects/incoming/mod/run/wt-RFLAKE3` (detached, clean).
Reviewer: fresh Codex Sol high session, independent of the author's conclusions.

## Serious blocker: P1 — the moved center local prevents devtest compilation

`mod/src/devtest/java/ua/zentix/incoming/gametest/GridGameTests.java:475` now declares `Vec3 at` before the lamp-population loop. That same method already declares `BlockPos at` in its nested loop at line 499. The scopes overlap; Java does not permit a local variable to redeclare an enclosing local, even when their types differ. Previously the `Vec3 at` declaration came after this loop, so there was no overlap.

The checked candidate cannot compile `GridGameTests` / `compileDevtestJava`, preventing its GameTests and required CI from running. Rename either local and its references. This is a direct source finding; no compiler or build was launched in this review, as requested.

No other serious source defect found in the reviewed change and surrounding paths. This verdict is for the exact checked head, not a later correction or merge approval.

## Independent evidence and behavior checks

- The diff changes only `GridGameTests.java` (20 insertions, 7 deletions). No production source, configuration, protocol, work budget, or shipped behavior changes.
- Raw evidence reviewed: `/mnt/data/projects/incoming/mod/run/wt-FLAKE3/mod/run/flake3/ci-shard2.log`, supplied run `41809137009`, job `126503694251`, #578 at `ddd550db2`. Lines 754–756 report 1767 clearing units / 589 worked ticks (0.5795 chunks/unit), versus 589 restoration units / 197 ticks (1.7385 chunks/unit); the sole required failure listed at lines 1722–1723 is `cityqueuerunsmanychunkspertick`. This is evidence about that failing revision, not successful validation of this candidate.
- `quiet(level)` calls immediate-time `Blackouts.restore(..., spread=0)` and removes nodes; restoration scheduling is synchronous, completion is not. `BlackoutWorld.onRestore` retains/replaces sweeps, `advanceSweeps` charges a counting-clock unit for every built row, and the shared clock also counts chunk-conversion work. A denominator starting before prior sweeps drain can therefore include unrelated work. This follows from source, not wall-time inference from the CI log.
- The added fixture creates four city-radius outages before `quiet`. Both their dark sweeps and newly scheduled restoration sweeps are real work; a distinct counting clock drains them before taking the lifetime-counter baseline and installing the benchmark clock. `idle()` includes sweeps, relight queue and chunk queues. No fixed delay or host-speed threshold is introduced. The positive cleanup-unit assertion prevents an empty disturbance fixture from passing silently, though it alone does not prove the four-sweep margin.
- The same 1024 held chunks, eight lamps per chunk, radius 480, 1 ms counting unit, and `off >= 1.2 && on >= 1.2` limits remain. Extinguished-lamp checks, restored-lamp checks, lifetime work-counter checks and real-clock diagnostic passes remain. No retry, threshold relaxation or benchmark-work subtraction is added. Actual red/green regression strength still requires the owner's executable evidence after the compile fix.
- The completion listener is registered before any city ticket allocation. `StrikeGameTests.afterTest` invokes its callback on both pass and failure (including timeout), resetting the per-server grid clock and removing each held ticket with the same type, radius 1 and key used on acquisition. Successful-path restoration and lamp assertions remain. This addresses the previous success-only release and covers early setup/assertion failure after allocation.

## Limits and ownership

Source/raw-log review only: no source edits, builds, GameTests, game/client/server/GPU operations, public actions, CI waiter or nested agents. No jobs or leases acquired. No PR description or author report was read. Parent FLAKE3 retains implementation, validation, CI and merge ownership. The raw failing log does not establish full candidate isolation across all dimensions: the override is server-wide while the drain predicate is level-local; no concrete cross-dimension interference was established in this scope.

## Memory

Recorded the durable source contract for grid fixture isolation in canonical memory: zero restoration spread schedules sweeps rather than draining them, and counting-clock totals include cascade-row units as well as conversion units. Candidate status is explicitly kept separate from those production facts.

## Second round — final source at 88d4f1350

Checked head: `88d4f1350a8bead28b46fe6bf9ce62d84f8fa7ac`, detached clean worktree `/mnt/data/projects/incoming/mod/run/wt-RFLAKE3`.
Checked correction: `84e845c56..88d4f1350`; checked final diff: `eee272309..88d4f1350`.

**Final source verdict: no remaining serious blockers found. The first-round P1 is resolved in this head.** This supersedes the earlier blocker verdict for final source; the original finding remains valid for its pinned older head.

The correction only renames the method-level center from `at` to `cityCenter` and updates its three blackout call sites (the disturbance loop and both measured blackout passes). The inner `BlockPos at` remains confined to lamp placement. There is no overlapping declaration of `cityCenter`, the captured center is effectively final, and all three calls still receive the same center value. The Java local-scope conflict identified at lines475/499 is therefore removed directly in source.

The final base-to-head diff still changes only `GridGameTests.java`, with no production source or behavior changes. The four-sweep fixture, separate cleanup/counting clocks, drain-before-baseline ordering, pass/fail cleanup callback and all lamp/counter checks are unchanged from the first-round source review. The 1024-chunk/eight-lamp fixture, radius480, 1ms counting units and both minimum1.2 chunks/unit assertions remain unchanged. No additional lifecycle, isolation, threshold or cleanup regression is introduced by this correction.

Coverage is the compile correction and its final surrounding method/base diff, as requested. No nuclear re-review, builds, compiler invocations, GameTests or new raw runtime evidence in this round. This source verdict is not a claim that compilation, deliberate-red controls or CI have passed: parent FLAKE3 owns those running checks and merge.

Actual reviewer resource state at completion: no owned laptop jobs, CI waiters, nested agents, client, server or GPU lease acquired or left running. The parent FLAKE3 process and its `incoming-job-flake3-checks-owned-241542` wrapper were observed active and were not altered. No source files changed; worktree remains clean. Only review/coordination records and the canonical memory follow-up were updated.
