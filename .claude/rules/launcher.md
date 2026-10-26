---
paths:
  - "mod/src/main/java/ua/zentix/incoming/launcher/**"
  - "mod/src/main/java/ua/zentix/incoming/entity/Launcher.java"
  - "mod/src/main/java/ua/zentix/incoming/entity/LaunchQueue.java"
  - "mod/src/main/java/ua/zentix/incoming/entity/LauncherMount.java"
  - "mod/src/main/java/ua/zentix/incoming/strike/LaunchOrigin.java"
  - "mod/src/main/java/ua/zentix/incoming/util/Magazine.java"
  - "mod/src/main/java/ua/zentix/incoming/client/render/FixedLauncherRenderer.java"
  - "mod/src/test/java/ua/zentix/incoming/entity/LaunchQueueTest.java"
  - "mod/src/devtest/java/ua/zentix/incoming/gametest/FixedLauncherGameTests.java"
---

# Fixed launcher (`launcher/`)

## Structure
- Launchers share trailer and block logic: `entity/Launcher` (`LauncherRack` on a `LauncherMount`: trailer `TRAILER`
  pivot 1.6 above ground and 2.2 behind center; block `PAD` pivot 1.25 above the block bottom, over its center; heading,
  rack elevation, `LaunchQueue`, turning only a silent rack and only by more than 15°). `LauncherEntity` (trailer) and
  `FixedLauncherBlockEntity` (block) implement it; slots, launch sector (`LaunchSite.clearAhead`/`pickOn` with the mount)
  and rack visuals (`LauncherRenderer.renderRack`) are shared.
- `FixedLauncherBlock` + `FixedLauncherBlockEntity`: owner is the placer (`setPlacedBy`; a Create mechanism places on
  behalf of its owner — its `FakePlayer` has the same UUID); stock is `util/Magazine` (same as SAM), 9 exposed slots for
  ammunition the launcher accepts (`Mission.accepts`: weapons with a rack in the spec — Shahed, Lancet, missile, Grad;
  not B-2 or ICBM); external access inserts only, from any side and for `null` (`Capabilities.ItemHandler.BLOCK`:
  hopper, Create funnel and chute; mechanical arm and belt via a funnel); breaking drops stock; comparator reads
  exposed slots. One extra reserve slot is hidden externally: it holds the remainder of an opened Grad pack and
  returns (`Magazine.stow`) when a hopper keeps exposed slots full — otherwise each rocket would open a new pack,
  dropping the remainder on the ground (`Munitions.take` takes opened packs first, then single whole packs, opening
  a stack last). Mission (`Mission`): weapon, count, spread, location (`Target.Point`/`Ground`; entity, craft or spotted
  target means its location when assigned), and waypoints. Only rack weapon, heading and elevation tick go to the
  client (`getUpdateTag`; mission and stock stay server-side).
- Redstone works like a dispenser: rising edge (`hasNeighborSignal` at its position or above) sets `TRIGGERED` and
  schedules a tick 4 ticks later; that tick issues one order (`LauncherOrders.fire`); failure means click
  `levelEvent(1001)` and reason in the status line (empty-hand right click; the launch's own reason, e.g. “sector
  blocked”, is in `report`, more specific than a generic refusal).
- Remote: right-click the block with the remote (`useItemOn`, no Shift) to link/unlink (`LauncherLinks`, component
  `incoming:launchers`: `GlobalPos` list, up to 16). While the list is nonempty, the remote's “Fire”
  (`ServerActions.fire`) assigns missions to linked launchers (`LauncherOrders.assign`): target follows player rules
  (`ServerActions.ruled`); each launcher must be in this world, in a ready chunk (do not load for a mission), owned by
  the player or their team, idle; target within `map_range` of it, route within weapon range from it (`routeProblem`
  with launch origin `LaunchOrigin.Fixed`). Missing launcher (chunk ready, block absent) is unlinked
  (`LauncherLinks.forget`): otherwise the remote would remain in mission mode forever.
- Launch follows the player-order path (`ServerActions.fromLauncher`): single via `StrikeService.launch`, salvo via
  `SalvoData`; origin is `strike/LaunchOrigin` (`Place`: launch location `/incoming salvo … from x z`, `Fixed`: launcher;
  the salvo stores it, location under `from`, launcher under `from_launcher`). `Fixed` launches only from its rack
  (`StrikeService.Stand`): Shahed/missile heading via `aimFixed` (a silent rack turns onto the route or via
  `LaunchSite.pickOn` rotation on its mount; a busy one launches on its heading), MLRS/Lancet via `aimedFixed`; blocked
  sector, unready chunk, missing block mean failed launch (no distant approach), reason in `report`. Projectiles act
  for the owner (friendly by `/team`, their “Cancel”, their active-projectile limit); no salvo lines or bar to the
  owner (`Salvo.reporter`). Owner rules apply (`designator_for_everyone` or operator by profile, owner may be offline;
  `max_active_per_player`; target within `map_range` of the launcher); ownerless (placed by command) has no rules.
  Stock must cover the entire order (`Munitions.shortage`), but is charged per projectile when it enters the rail
  (`Munitions.pay(Store, Bill)` in `StrikeService`): canceled salvos and failed launches stay in stock, so nothing
  needs returning to a possibly unloaded chunk. The launcher is busy while its salvo runs (`SalvoData.busy`) or its
  rack is not silent (`LaunchQueue.silent`) — `LauncherOrders.busy`: neither orders nor new missions (a mission changes
  the rack, whereas salvo projectiles start from their weapon's rack; `StrikeService` launches only the rack's weapon
  on a round).
- For the game master and scripts: `/incoming launcher <x y z>` (operator, `IncomingCommand.launcher`): status, `fire`,
  `mission` (no player rules; target `x z` is a ground location, `x y z` a point; route via `routeProblem` from the
  launcher), `clear`, `load N`, `owner`; position must be in a ready chunk; `mission` and `clear` refuse a busy launcher.

## Pitfalls
- The rack is larger than a block: `FixedLauncherRenderer` has its own `getRenderBoundingBox` (otherwise it is culled
  by the block cube) and `getViewDistance` 160; the block is opaque, so rack light is sampled above it
  (`LevelRenderer.getLightColor(pos.above())`).
- A launcher salvo waits while its chunk is unready (`Terrain.ready`), loads it in the background
  (`strike/LaunchPlaces`: `AreaLoader` area for the duration of the salvo), and ends if the block is absent: a broken
  launcher has already dropped its stock.
- Launchers do not fire in Sable's plot grid (`SubLevels.inPlotGrid`): a craft's block sits in a distant plot, and the
  projectile would leave from there.
- `getUpdateTag` writes only what the client sees; `loadAdditional` reads owner, stock and mission only when present
  in the tag: update packets do not erase them.
