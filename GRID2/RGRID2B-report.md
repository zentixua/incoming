# RGRID2B scoped independent correction review

Reviewed 2026-11-04T15:30:55+00:00, fresh `gpt-6.1-sol` / `xhigh`, detached clean worktree `/mnt/data/projects/incoming/mod/run/wt-RGRID2B`.

First-reviewed pin: `e09a2e16911a57fdfbf95357081c52b767f7f5cc`.
Correction candidate: `5839ee91c21cf1afd6fd2dae22a2e3c653bf977b`.
Original base: `7bdb5b2c1`.

## Verdict

**The first review's nearest-player ordering defect is corrected. No serious findings in the scoped correction.** This closes the source-review correction round; it is not final CI, composed-source, full-pack performance, or release/runtime acceptance. No implementation or third review round was started.

Read canonical TEAM/context/live/BOARD, repository AGENTS and applicable grid, work, GameTest, server-chunk and nuclear rules, canonical memory and the first independent `RGRID2-report.md`. Then independently inspected the exact correction diff, actual test assertions and surrounding queue, shared WorkClock and persistence paths. The process command confirms explicit Sol/xhigh and this worktree. GRID2 retains component ownership; LEAD2 retains integration/runtime/main merge. No prior RGRID2B handoff or reviewer-owned job needed resumption.

## Why the correction holds

- **Large-bucket ordering:** `BlackoutWorld.java:162-184` visits radius zero for every snapshotted player, then each successive Chebyshev-distance ring for every player, through radius eight. Each nonzero ring has four nonoverlapping sides of `2*r` cells, including every corner exactly once. Thus a chunk's first successful probe occurs at its minimum distance from any snapshotted player. `issuedNear` suppresses overlap by the bucket's stable index. The far traversal starts only after all near rings finish, skips already-issued indices and resumes at `at` (`186-192`). No lost or duplicated entry was identified.
- **Small-bucket ordering:** only lists of at most `CHUNKS_PER_UNIT` (256) entries are sorted by the same nearest-player maximum-axis metric (`155-160`, `389-395`). Sorting cannot invalidate binary search: this branch sets an empty priority-player array, so it never runs `find` on the reordered list. Large buckets remain in signed z/x order from `buildRow` and therefore retain valid binary search.
- **Actual FIFO interaction:** `enqueue` (`364-368`) still appends to near/ready, `take` (`414-417`) takes near first, and an unfinished conversion returns to its original head (`859-863`). With both centres produced before farther cells, dense lamps at player one's view boundary no longer precede player two's centre due to the old square walk. `issueDue` (`757-765`) still yields after an issuance unit populates near; it does not consume the next tick's conversion guarantee while near remains nonempty. Existing older queued work and later player movement are separate inherited ordering boundaries.
- **Bounded admission/shared clock:** the new ring counters advance once per probe under the existing 256-iteration loop, including misses and overlap duplicates (`148-194`). Per-bucket counters, including the new radius, reset together only after the bucket's raw-list traversal completes. Initialization, the small sort, lookups and callbacks remain inside the existing `clock.begin/end` try/finally. No clock start/reset was added. `Blackouts.work:197-211` passes one clock through dimensions; `WorkScheduler:219-223` starts the grid lane once. `WorkClock.fits:156-160` and `end` preserve shared expenditure and its single progress guarantee. Build-row, relight and conversion work continue to share that clock.
- **Restoration and persistence:** no Outage/PowerGrid format, physical due time, restoration scheduling or pruning code changes. Both sweep directions use the same ordering code. `issuedThrough` (`212-218`) still advances past only fully issued deadlines; partially probed or traversed buckets retain `cursor-1`. PowerGrid saves/loads that marker (`143-172`), and attachment restore restarts unfinished dark sweeps (`699-707`); rebuilding an incomplete bucket intentionally replays idempotent current-grid alignment. Ring state itself is not persisted and cannot cause a partly issued bucket to be marked complete. Restoration scheduling/pruning (`712-718`, `PowerGrid:131-140`) retain their existing completion conditions.

## Raw evidence checked

Evidence directory: `/mnt/data/projects/incoming/mod/run/wt-GRID2/mod/run/grid2`.

- `near-red.xml` and `near-red.log`: one test, one failure at the nearest-distance assertion in `closestChunksOfEveryPlayerLeadEachDistanceRing`; stack points to the first pin's old `Sweep.issueBatch` square walk. `near-green.log`: successful compilation and test run after the correction.
- `final-focused.xml`: **9 tests, zero failures/errors/skips**, timestamp `2026-11-04T15:25:37.869Z`; `final-focused.log` reports successful compilation/test tasks. Names match the nine assertions inspected in candidate source. The XML/logs do not themselves embed a commit hash; the owner is responsible for final exact-head validation.
- The new stationary separated-player fixture (`BlackoutSweepTest:78-101`) checks both centres lead and every near emission has nondecreasing minimum player distance across the complete large sweep. The new production-FIFO fixture (`124-153`) uses actual `world::enqueue`, covers both >256 and <=256 bucket branches, dequeues both centres first and checks the remainder's distance order. The existing gate fixture (`105-120`) separately verifies issuance yields after one unit and leaves the next tick's conversion guarantee intact. These are queue/source fixtures, not lamp conversion or multiplayer runtime measurements.
- Existing focused fixtures retain exhausted-clock admission, one-unit bounded complete issuance, overlapping-player deduplication, physical deadline ordering and zero-spread restoration with a second sweep sharing the exhausted clock.
- The new persistence fixture (`157-181`) calls actual `PowerGrid.save(CompoundTag, Provider)` and private codec load, verifies partial marker 99 survives, rebuilds/replays the complete bucket without duplicates, then verifies the terminal marker survives a second round trip. This closes the previous report's direct-Sweep-only evidence gap at the **in-memory SavedData codec** boundary. It uses darkening and no players; it does not exercise a filesystem save wrapper, a live restart during near probing, or real lamp conversion. Those are evidence limits, not an established regression in this correction.
- Historical `red.xml`: 2 tests / 2 failures (missing charge and exhausted-clock bypass). Historical `green.xml`: 6 / 0. First pin `build-e09.log` is successful; `build-tests-e09.txt` reports **1793 / 0 / 0 / 0**; `gametest-e09.log` reports **44 required tests passed**; `ci-e09.log` reports green Итог for **e09a2e16**. These belong to the first pin and must not be presented as the correction pin's full checks.
- Final observation at `2026-11-04T15:31:14Z`: correction `build.log` reports **BUILD SUCCESSFUL in 4m 20s**, and newly written `build-tests.txt` reports **1796 / 0 / 0 / 0**. The owner's final `gametest.log` is now actively writing; the required CI log is still empty. Earlier at `15:29:21Z` the build was at `:test` and the GT/count files still held first-pin results; those older results were not mistaken for final checks. Final GameTest/CI completion remains owner-owned. No duplicate jobs or CI waiter were started by this reviewer.
- `git diff --check e09a2e169..5839ee91c` is clean; the review checkout has no production modifications.

## Limits and integration

The 256 bound is a bound on issuance iterations, not all operations or wall time. The small sort is bounded in bucket size but compares against all players; player snapshot allocation, lookup cost, callbacks, one build row, maintenance scans, GC and preemption remain nonpreemptive. There is no independently measured full-pack tick/FPS improvement or universal deadline cap in this review.

The inherited `FarLods.request` callback remains at `BlackoutWorld:744` in this isolated pin. LEAD2's separate LSS583 removal must remain intact when composing the resumable issuance loop; its final compilation, tests and real standard-LSS delivery are integration-owner gates. No root/production edits were made here.

PR description, author's session/inbox/report/conclusions were not read. No tests, heavy jobs, GPU/client/game/Crafty/server/world operations, commits, merge, deploy or release actions were started. Reviewer-owned external jobs/resources: **0**; none remain to await or stop.

## В память

Updated canonical `grid-gametest-isolation.md`: exact ring/FIFO and small-bucket-sort review invariants; distinguish the new real SavedData codec round trip from a filesystem/live-world restart and final integrated/runtime acceptance.
