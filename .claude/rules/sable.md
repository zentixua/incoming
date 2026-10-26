---
paths:
  - "mod/src/main/java/ua/zentix/incoming/compat/**"
  - "mod/src/main/java/ua/zentix/incoming/mixin/sable/**"
  - "mod/src/main/java/ua/zentix/incoming/nuclear/world/**"
  - "mod/src/main/java/ua/zentix/incoming/warhead/**"
---

# Sable (Create Aeronautics craft)

## Pitfalls
- Sable 2.0.5–2.0.6 crashes the world (“Sub-level assembly attempted inside plot of already removed sub-level”) when
  a craft splits into many pieces after an explosion: a massless piece is removed, its plot is immediately reused by
  the next one, and splitting in that plot finds the removed craft. Mixin `mixin/sable/SubLevelSplitGuardMixin`
  (`incoming.mixins.json`, `required: false`: a mixin that fails to apply only logs a warning, so everyone checks their
  own GameTest) wraps `SubLevelHeatMapManager.split` and swallows only this exception (`compat/SplitGuard`); GameTest
  `sableSplitGuardApplied` checks that it applied.
- On every block change Sable reads neighboring blocks (craft physics), and for a solid neighbor, its neighbors too,
  up to 2 blocks (`NuclearTickets.SABLE_REACH`): change blocks only where adjacent chunks are also ready
  (`NuclearTickets.neighbourhoodLoaded`, `aroundLoaded`), otherwise it loads a neighbor synchronously. Writes bypassing
  `setBlock` (ruins, flash layer) notify it of each position themselves (`SableTerrain.changed`) — the same rule: the
  flash layer at a craft is not placed without loaded neighbors (`FlashScorch`: before background work and again
  before writing — neighbors may have left while it was built; GameTests `flashLayerWaitsNeighboursAtCraft`,
  `flashLayerRechecksNeighboursBeforeWrite`); ruins, which wait for neighbors, bring it later.
