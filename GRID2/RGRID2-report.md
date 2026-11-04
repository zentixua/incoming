# RGRID2 independent source review

Reviewed at 2026-11-04T15:22:51.736834+00:00. Fresh `gpt-6.1-sol` / `xhigh` session, detached clean worktree `/mnt/data/projects/incoming/mod/run/wt-RGRID2`.

Base: `7bdb5b2c1`. Candidate: `e09a2e16911a57fdfbf95357081c52b767f7f5cc`. Old-algorithm extraction: `5c906ef0c` (the diff preserves the original issuance loop and only exposes it for tests).

## Verdict

**Changes requested: one P2 behavior regression.** No additional crash, chunk-loading regression, lost/duplicated issuance, or new budget bypass was established by source review. This is not runtime acceptance or an approval of the final integration.

## Finding

### P2 — Preserve nearest-player ordering within each due bucket

Location: `mod/src/main/java/ua/zentix/incoming/grid/BlackoutWorld.java:158-170`, especially the square coordinates at **160-161**; amplified by the yield at **742**.

The base sorted same-deadline chunks by their minimum distance from *any* player before enqueueing. The candidate walks the first player's 17×17 square in z/x order and only then visits the next player. `near` is FIFO (`enqueue`, 351-355; `take`, 397-399), and an unfinished chunk returns to its head (`handle`, 842-846). Therefore it no longer converts the closest lamps first.

Deterministic source example: use the existing large instant bucket, with player chunk (0,0) and a second separated player at (50,0), both well inside the radius-1200 outage. The same large-bucket shape is reachable during restoration with `grid.restore_spread_seconds = 0`, which is an allowed configuration value; it does not require infinite-speed production flight. With those squares loaded, the first probe is (-8,-8), while (0,0) is probe/enqueue **145**, behind 144 farther chunks. The first issuance unit enqueues the first 256 probes of player one, then `near` becomes nonempty and stops further issuance. Player two receives no entry until those first-player chunks finish conversion. Even with ample remaining tick budget, the next call to `issueDue` is blocked by that nonempty queue. Dense light chunks require many conversion units each, so player-local lights can wait behind an entire remote edge of the first player's view. The old minimum-distance sort put both player chunks ahead of all positive-distance chunks.

This is a functional regression in the player-visible priority that was deliberately present in the old loop, not a request to restore its unbounded sort. Enumerate a bounded near set in increasing distance across players (or another bounded mechanism preserving the same priority), then retain bounded far traversal. Cover a small bucket too: the `chunks.size() <= CHUNKS_PER_UNIT` branch also drops the old distance ordering.

**Missing discriminator:** the new overlapping-player test only verifies that all chunks within NEAR_CHUNKS precede chunks outside that square. It does not assert that either player's own chunk precedes farther chunks, and its callback adds directly to a set, so it never exercises production's near-queue drain gate. Add separated players, real `world::enqueue`, and check that one player's distant boundary lamps do not hold up either player's closer lamps. A narrow deterministic fixture is sufficient; no GPU run is needed to prove the ordering defect.

## Source checks and test evidence

- Issuance is now enclosed by the actual shared `WorkClock.begin/end` (740-748). The 256-iteration bound includes absent deadlines, near probes, duplicates and far entries; one running unit remains nonpreemptive. `clock.start` is not restarted inside the sweep or dimension loop.
- `buildRow` appends every due list in signed z/x order. Binary search uses that same order. `issuedNear` suppresses overlap and later raw-list replay; cursor advances only after every raw-list index has been visited. The examined paths do not lose or duplicate a chunk.
- The new persisted marker is the last fully issued deadline. `PowerGrid.save/load`, `prune`, `scheduleRestoreSweeps` and `restore` were inspected. On attachment restart the code rebuilds incomplete sweeps from their outage, including already-issued portions; actual conversion uses current `grid.dark` and is idempotent. No new disk format or migration is introduced.
- Supplied raw `red.xml`: 2 tests / 2 failures, specifically zero charged units for the large bucket and issuance despite an exhausted shared clock. `red.log` agrees. The extraction diff establishes that this red control still uses the old algorithm.
- Supplied raw `green.xml`: 6 tests / 0 failures/errors/skips; `green.log` and `focused.log` report successful compilation/test tasks. The six fixtures cover shared clock admission, bounded complete issuance, deduplication for overlapping players, near-queue yielding, ordered physical deadlines, restoration and replay of a partially issued bucket.
- `grid_restart` resets the attachment immediately after scheduling its outage. The new unit replay fixture constructs another Sweep directly; it does not serialize/reload PowerGrid after a partially issued bucket. These are useful source/attachment checks, not a complete mid-bucket SavedData round-trip proof.
- Existing grid GameTests cover actual lamps, dense conversions, signal/light cleanup, loaded edges, restoration and attachment restart. They contain no player-bearing issuance-priority fixture. They cannot catch the finding above as written.

## Limits and integration

At 2026-11-04T15:22:51.736834+00:00 the owner's build log reported `BUILD SUCCESSFUL in 4m 14s`. Full test counts, touched GameTest completion and required CI remain GRID2's owned validation; this review did not start or duplicate those jobs. The supplied focused results are not proof of a measured full-pack tick/FPS improvement.

The patch does not bound row construction, all-player snapshot allocation, per-callback cost, maintenance scans, or all grid time; it bounds issuance iterations and charges their work. It must not be described as a universal wall-time cap. A callback, one row, lighting/world work, GC or preemption can still exceed the allowance as an indivisible unit.

The candidate still calls `FarLods.request` for non-memory chunks. That inherited call is inside the newly clocked callback and introduces no new synchronous world load here. Root's separate #583 removal of custom FarLods must remain intact when composing this change; preserve the resumable/budgeted loop and resolve the old callback against the final root code. Final merged compilation, standard LSS delivery and real runtime acceptance remain LEAD2's responsibility. No root edits were made.

PR description, author conversation/inbox/report and conclusions were not read. No production edits, commits, merges, clients, GPU, servers, Crafty, worlds, or new heavy jobs. Reviewer-owned external jobs/resources: **0**.

## В память

Recorded the durable test boundary in canonical `grid-gametest-isolation.md`: near/far membership and overlapping-player deduplication do not prove nearest-player conversion priority; include the FIFO conversion gate and separated players. A direct Sweep replay is not a SavedData round trip.
