# Background ruin retry validation

Source: `3619b73dcfb7e08f3e740c76976b27b1ae366aa5`, PR #578. Production patch reviewed at `7779f488a`; subsequent commits add the block-damage-disabled regression and documentation/fixture cleanup.

- Full build: 1,788 unit tests, zero failures/errors/skips.
- Nine affected GameTests passed in 6.635 seconds (30 seconds total Gradle execution): stale background, look-ahead, background parity, world-free background call graph, block-damage-disabled background trace, edge application, flash characters, flash preparation and flash shadow.
- The stale-plan regression holds worker admission after changing the captured glass. The chunk stays unmarked and unchanged until workers are released; the fresh plan then applies and marks it.
- The block-damage-disabled regression similarly holds worker admission, verifies deferred ground-trace completion, then verifies the glass remains intact after completion.
- Cancellation tests distinguish Future cancellation from actual worker exit, including immediate release of a queued cancellation.
- Fresh independent Sol xhigh source review found no serious defect. Its optional stale overview/comment references were corrected.

The motivating old copied-world 100 kt trace had an 81.815 ms completed tick with server-thread `ScarQueue.ruin → RuinPlanner.plan → Collapse` samples. This is old-build diagnostic evidence, not a measured speedup. The corrected integrated real-world trial remains pending.

Pressure timing, the player chunk-send deadline and solver equations are unchanged. Repeated edits can invalidate repeated snapshots; no unapplied plan receives a scar mark. CI is pending at publication time; this artifact does not certify the full 1 Mt goal or authorize a release.
