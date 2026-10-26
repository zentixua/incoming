---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/fx/smoke/**"
  - "mod/src/main/java/ua/zentix/incoming/client/far/**"
  - "mod/src/test/java/ua/zentix/incoming/client/fx/smoke/**"
  - "mod/src/test/java/ua/zentix/incoming/client/far/**"
---

# Blast cloud preparation

- Blast sources provide `Source.bounds` before `emit`: absolute age, growth and wind, without puffs or terrain reads.
  Include young ejecta, condensation and water. `SmokeVisibility` uses the actual layer matrix (bob and zoom), side
  planes (distant effects fold in front of the far plane), and haze at the nearest/highest bound edge.
- `SmokePreparation` selects at most 128 nearby/large sources with 20% retention hysteresis and refreshes at most
  16 within a cooperative 2 ms frame budget. A source gets at most 0.5 ms before yielding to the next one. Refresh
  priority uses snapshot age in seconds, including time hidden, with young clouds first. Never replay a partial
  snapshot, a snapshot from the future, or one older than 0.25 s. Preserve original source order during replay:
  puff merging is order-sensitive. Fire and nuclear sources retain their existing paths.
- Selection uses distance to the bound centre: broad empty margins must not dominate nearby clouds.
  `GroundDust.support` builds interval bounds from displayed-ring mass moments, monotone lofted fractions and
  entrainment survival; common ring settling cancels. Positive/negative wind sums cover terrain delays.
- Skipped dust history advances directly to the current absolute-time bracket in at most four coarse steps of two probes each.
  Wind, mass, spread, fall time and lifetime still use absolute age. Never replay all omitted path nodes on return.
- `FarTerrain.dust` shares one weakly world-owned cell cache across strikes, before every `TerrainTiles` backend
  (LSS, Voxy, DH): loaded columns for one tick, distant columns for one second, including absent heights. Switching
  to loaded chunks or rewinding invalidates cells immediately. Cache values must not retain the weak world key.
