# FLAKE3 grid throughput regression evidence

Checked source: 88d4f1350a8bead28b46fe6bf9ce62d84f8fa7ac; base eee272309e1c8306952f32b5f6b7ed4192be2e7c; PR #580.

Original current failure: run41809137009/job126503694251, #578 ddd550db2. Its raw log shows earlier outages #2/#3/#4 restoring during the new city's measurement: 1767 clearing units, 589 restoration units, minimum 1.2 chunks/unit violated.

The change drains previous restoration before installing the measurement clock and taking counter baselines. Four pending city-sized outages deliberately reproduce prior-batch work even in an isolated grid_city run. Threshold, lamp count, area, counting unit and production algorithm stay unchanged. Completion cleanup releases the clock and held tickets on failure too.

Validation on final source:
- Full build: {'tests': 1783, 'failures': 0, 'errors': 0, 'skipped': 0}; 3m32s.
- All 22 grid GameTests passed; wrapper exit0.
- Deliberate old measurement boundary with the new disturbance: target assertion fails at 0.812698 chunks/unit (1260 clearing units), returning1.741497 (588 units). No threshold change. red_boundary.py records the exact negative-control source transformation.
- Finally-restored final source passes isolated grid_city at1.741497 both ways (588 units each), with678 old-work units excluded by actual completion before measurement. Source restored byte-for-byte; worktree clean.
- Fresh independent Solhigh source/raw review: first round found a compile-scope name collision; compile-only correction reviewed in second round, no remaining serious finding. No nuclear re-review.
- Final CI run41810808125: all6 GameTest shards, bothLSS runtimes, unit shards, build, clean installation and required Итог green.

checks.log retains the first compile failure. checks-fixed.log retains a separate build interruption: daemon231408 was reused from another wrapped job and terminated at that job's normal cleanup. The completed build uses repository-default daemon=false and its own lifetime; no daemon was globally stopped. These are not successful validation runs.

Real-clock values are observations, not CI performance thresholds. This test-only change makes no full-pack performance or nuclear-readiness claim. No worlds, JARs, credentials, frames, or private data are included.
