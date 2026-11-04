# GRID2 — due-bucket scheduling

Final candidate: 5839ee91c21cf1afd6fd2dae22a2e3c653bf977b (first-reviewed candidate: e09a2e16911a57fdfbf95357081c52b767f7f5cc), base: 7bdb5b2c1. PR: https://github.com/zentixua/incoming/pull/590 . Final integration and actual runtime acceptance belong to LEAD2.

## Trigger and scope

The current real 15 kt diagnostic supplied by PERF-C2 sampled BlackoutWorld.advanceSweeps → LongArrays.quickSort → playerDistance twice during a 105.744 ms whole-server tick. Three Server-thread samples total and no overlapping GC do not attribute that whole tick to the sort. Base source independently shows full bucket copy, sort and issuance outside canStart/begin/end. This work corrects that specific unbounded path, without changing district timing, range, physics, settings or LOD implementation.

## Deterministic regression

5c906ef0c extracts the original sort/issuance into issueDue without adding a clock check or charging. Both new tests fail: all due entries are issued with zero charged units; even an exhausted shared clock still issues them. `red.log` / `red.xml` are the raw results (2 tests, 2 failures, Gradle 18 s). No deadline or wall-time tolerance was inflated.

e09a2e169 uses the same helper in production with canStart/begin/end and a persistent bucket cursor. One unit visits at most 256 deadline slots, near probes or bucket entries. Near probes use binary search in the existing build-row z/x order; no full far-list copy/sort. A sparse index set avoids double issuing overlapping-player probes when the far cursor resumes. Existing enqueue near priority handles small buckets. Issuance yields to admitted near lamps so conversion can use the next tick's guarantee.

Raw `green.xml` and `focused.log` cover six initial due-scheduling tests, including one unit per tick, an exhausted shared clock, a second sweep using that same clock, two overlapping players near the bucket tail, yielding to actual enqueue's near queue, real district deadlines and ordering, restoration, and partial-bucket progress/reconstruction. The replay test rebuilds a nonpersistent Sweep; existing GameTests cover actual world lifecycle and lamp persistence.

## Limits

These tests discriminate scheduling and coverage, not a universal tick-duration bound or full-pack runtime acceptance. Build rows and other existing blackout maintenance are outside this correction's scope. A unit's callbacks are not preempted. Player priority is the existing NEAR_CHUNKS class; distant chunks sharing a deadline retain build-row order instead of exact distance sorting. The existing FarLods callback is preserved unchanged. LEAD2 corrected the bootstrap assumption at15:27UTC: #583 removes direct Voxy publishing/refresh, while FarLods.request remains the optional DH path. Root73b032c2 and base7bdb have identical BlackoutWorld blob6a34e1c758c349e9d515dd15620fa305c2fb64f1. No callback removal or source conflict is needed for that composition. No GPU, client, Crafty, protected server, release or world action was performed here.

## Independent review correction

Fresh RGRID2 Sol xhigh found a player-visible priority regression in e09: the square traversal put a player's centre 145th, and delayed a second separated player behind the first player's square. `RGRID2-report.md` contains the independent finding. The new exact closest-order fixture fails on e09 (`near-red.log/xml`, 1 test / 1 failure, 5 s Gradle), and passes after the correction (`near-green.log`).

Final 5839ee91c enumerates distance rings across every player, beginning with all player centres, before any farther ring. Large buckets remain in the original z/x order for binary search and bounded far traversal. Small buckets of at most 256 chunks retain a bounded nearest-distance sort. `final-focused.log/xml` contains nine due tests, including production `world::enqueue` / actual near FIFO priority for both large and small buckets, plus PowerGrid's actual NBT save/load entry points with partial/finished markers and replay. The NBT round trip does not involve disk I/O or a complete server restart.

First candidate e09: full build 1793 tests / zero failures/errors/skips (4m14s), all 44 grid/work GameTests (49.35 s tests / 1m13s Gradle), required CI41855986528 green. Those results do not certify the corrected head. Final exact5839ee91c checks:

- Full build: 1796 tests, zero failures/errors/skips, 4m20s Gradle (`build.log`, `build-tests.txt`).
- All 44 required grid/work GameTests passed, 47.34 s tests / 1m6s Gradle (`gametest.log`). Wrapper exit0; no owned job/server remains.
- Fresh RGRID2B Sol xhigh scoped correction review: defect corrected, no serious findings (`RGRID2B-report.md`). Both review services ended0; no reviewer-owned jobs/resources.
- Required Итог green for5839ee91c, pr_ci.sh exit0 after10min. Workflow41857739097: https://github.com/zentixua/incoming/actions/runs/41857739097 . Raw `ci.log` and `ci-checks.json` are included. No blind rerun.
- Component handed back to LEAD2 for final integration/runtime/main merge. No main merge or release by GRID2.

The two review reports inherit an inaccurate bootstrap statement about root583 removing FarLods. That paragraph is superseded by LEAD2's15:27 correction and the exact root73b/base7bdb blob comparison recorded above: keep the existing optional-DH callback. Review findings/clearance concern the grid correction, not removal of this callback.
