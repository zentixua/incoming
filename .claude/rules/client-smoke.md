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
  priority uses snapshot age in seconds, including time hidden, with young clouds first. Reserve the measured
  source cost plus margin before starting its next emit. Never replay a partial snapshot or one from the future.
  On return from exclusion, discard a snapshot older than 0.25 s until the first fresh update; continuously visible clouds retain their last
  complete snapshot even when the refresh round is longer at low FPS. Preserve original source order during replay:
  puff merging is order-sensitive. Fire and nuclear sources retain their existing paths.
- Selection uses distance to the bound centre: broad empty margins must not dominate nearby clouds.
  `GroundDust.support` builds interval bounds from displayed-ring mass moments, monotone lofted fractions and
  entrainment survival; common ring settling cancels. Positive/negative wind sums cover terrain delays.
- Dust keeps the original fine `PATH` nodes and spatial sampling density: coarse hidden steps skip walls and put
  street dust on roofs. `Source.maintain` advances only hidden terrain paths, without cloud emission, in a rotating
  0.5 ms share of the same 2 ms budget (0.125 ms slices). Completed nodes survive a yield; a completed path bracket
  is reused until the next node. Terrain access stays on the client thread. A return never synchronously bypasses
  the preparation budget to catch up, and cannot publish a partial or pre-hide snapshot.
- `FarTerrain.dust` shares one weakly world-owned cell cache across strikes, before every `TerrainTiles` backend
  (LSS, Voxy, DH): loaded columns for one tick, distant columns for one second, including absent heights. Switching
  to loaded chunks or rewinding invalidates cells immediately. Cache values must not retain the weak world key.
- Frame planning runs off the client thread (`SmokePuffs.flush` with a `Planner`; the game's is one daemon thread
  "Incoming Smoke", started by its first plan, drained and stopped by `SmokePuffs.reset` on world exit and replay seek;
  `-Dincoming.smoke.inline=true` plans on the client thread). The client thread fills one `Draw` (snapshot replay, trail
  places and ribbons), seals it (last source merge, outputs to a `Recording`) and hands it over; the other `Draw` plans
  the previous frame (plan, conceal, merges, fit, clusters). Each frame draws the last completed plan: recorded quads
  and ribbons re-anchored at the current eye, so billboards face the current camera; the veil is merged and laid on the
  client thread (`VEIL`). The plan lags one frame; an unfinished plan after `WAIT_NANOS` keeps the previous one drawn.
  The planning `Draw` must read only its own arrays and immutable or frozen inputs: `FarView` holds copies of the camera
  vectors, and a source's `visible` state that ticks change is saved at `begin` (`Source.visibility`). No Level, chunk
  or BlockState access in planning; emits (terrain) stay on the client thread. `SmokePipelineTest`: the planned frame
  k + 1 draws exactly the inline frame k on the 100-strike raid, also on a real thread, and world exit mid-plan drains it.
