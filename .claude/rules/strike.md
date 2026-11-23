---
paths:
  - "mod/src/main/java/ua/zentix/incoming/strike/**"
  - "mod/src/main/java/ua/zentix/incoming/target/**"
  - "mod/src/main/java/ua/zentix/incoming/net/**"
  - "mod/src/main/java/ua/zentix/incoming/progress/**"
  - "mod/src/test/java/ua/zentix/incoming/strike/**"
---

# Orders, launch, targets (`strike/`, `target/`)

## Structure
- `strike/` handles orders: `ServerActions` (remote packets, permissions, cancel;
  `strike(player, …, rules)`: `rules` means an order under player rules, every remote including operators', and
  non-operator commands), `Munitions` (inventory ammunition: non-creative `rules` orders are paid in full when issued,
  one spec `WeaponSpec.munition` item per projectile; Grad pack uses one round from a pack (opened pack component
  `incoming:rounds`); nuclear warhead on carrier costs an additional `nuclear_warhead`; insufficient stock refuses
  with “need N, have M”, never truncates order; failed launch refunds payment, canceled salvo refunds unfired/failed
  launches to online owner, full inventory drops at feet, dead owner before respawn at death location (inventory
  without `keepInventory` does not carry across); offline owner gets only log line; throwing launch counts as failed),
  `NuclearKeys` (nuclear `rules` order from non-operator/non-host uses `trusted`, when `ops_only = false`: paid order
  waits for second key, `C2S.NuclearKey` button from another player within `second_key_distance` of launcher player
  during `second_key_window` s, not own key; expiry refunds; alone online needs no key; ICBM flies at least
  `MIN_WARNING` (90 s, `StrikeService.launchIcbm(…, flightTicks)`), carrier launches 90 s after target alarm
  (siren/line in nuclear alarm radius); successful launch tells everyone “Launch … detected from area X, Z” by
  256-block cell, plus siren; nuclear cancel/nuclear-projectile retarget only `trusted`; orders in `StrikeWorld`, not
  saved; their projectiles count active for `max_active_per_player` (`StrikeWorld.active`); nuclear cancel, server
  stop or launcher-player exit before second key removes/refunds them), `StrikeService` (one projectile launch, siren,
  log line; `launchBy` launches for player without looking up player list), `SalvoData` (salvos, SavedData; removal
  only by `cancel(world, owner, online players)` and completion/tick error, every path refunds through one `refund`;
  online players passed as parameter, GameTest `FakePlayer` absent from player list; salvo `seed` (saved) assigns
  each projectile a route lane, `WeaponSpec.Lanes.offset` by launch index, `StrikeService.launch(…, lane)`,
  `Route.inLane`: salvo flies as wave rather than thread; single strike/one-shot salvo follow route itself),
  `StrikeWorld` (explosion timelines plus salvos at world-tick end; non-persistent world attachment),
  `Loadout`/`WeaponType`/`TargetMode` (`Loadout.Nuke.onCarrier`: nuclear warhead on missile/B-2), **weapon spec**
  `WeaponSpec` (`WeaponType.spec()`: all weapon differences, siren, salvo, warhead, yield, ground-explosion charge
  `charge()` (glass, visuals/sound, from warhead of article that hits), launch method, route, launcher rack
  `entity/LauncherRack`, explosion look; `Airframe` for flying entity: speeds, turn limits, heights, nose, durability,
  range reserve, fuze margin, launch, approach lane, attack geometry; sound spec `noise` (`sound/Noise`: engines,
  booster, body, launch one-shots, sourced article numbers); SAM signature `Signature` (radar-range share/worth
  intercepting, `defense.md`); real article `article` (`strike/Article`: masses, dimensions, CdA/f(M) curve, propeller,
  turbofan/solid rocket motors, real speed/height, impact conditions, warhead TNT/HE/casing/fuel at impact; each number
  sourced in spec Javadoc; `Airframe` speeds/heights are game values, not physical; world flight scale
  `scale.flight_speed` (`FlightScale.x()`, by preset, 0 before settings load) takes them toward real values:
  `Airframe.cruiseSpeed()/diveSpeed()/cruiseHeight()` blend via `FlightScale.blend` (at 0 exactly game values, scenarios
  unchanged); speed-derived lengths (`range`, `visibleLeg`, `attack`, spec `finalLeg`/`capture`/`standoff`) multiply by
  `Airframe.stretch`; Grad standoff follows trajectory range at rack angle with scaled launch rail exit speed,
  `WeaponSpec.standoff`; unpowered drag `Airframe.drag()` (`Ballistics.Drag`: game without Mach curve → ρ₀·CdA/2m
  with f(M)); game numbers are `game*` components, read directly only when game scale is needed (B-2 release angle,
  `BomberEntity.releaseLine`); shared `WeaponSpec.*_PISTON/_TURBOFAN/_BOOSTER/_MOTOR` constants feed server,
  `*_NOISE` sound and `ClientWeaponSpec` plumes, `WeaponSpecTest` compares law-derived values with article specs);
  B-2 additionally has bomb `payload`; client spec `client/ClientWeaponSpec`: sound layers, booster voice,
  nozzles/plumes, dropped booster). New weapon-specific numbers go in spec, not entity constants or weapon `switch`
  (exception: command-name parsing); `WeaponSpecTest` compares chosen/derived numbers. `ChunkTickets` (own
  `TicketType`: projectile holds own/ahead chunk, only ready ones; ahead chunk `ChunkHold` only while someone can
  see, `FlightTickets.watched`). Launch/flight: `LaunchSite` (launcher near player: level, solid, sky, ready chunks
  only; Shahed/missile launch sector before fuze arming must be clear of blocks from every rack slot, acceleration
  under spec law `LaunchProfile.boost`, shared with projectile, `clearAhead`; otherwise other bypass side/rack turn:
  projectile collides during boost, crashes without detonation before arming, log “Projectiles: … crashed before
  fuze arming”; after-arming climb: terrain along heading over `reliefLookahead` blocks below autopilot climb law,
  `Autopilot.climbOver`, otherwise location between towers invalid; one launcher choice checks at most
  `LaunchSite.Budget` cells (≈8–16 ms), otherwise distant approach; backward/sideways heading sector checks raise site
  above neighboring GameTest grid platforms, `ABOVE_GRID`; firing position `LaunchSite.Post`: near player or ordered
  launch location, `/incoming salvo … from x z` (operator permission only), or remote map (`C2S.Fire.from`: operator
  and host, `NuclearKeys.trusted`, within `map_range`, `ServerActions.launchSiteProblem`; ruled/paid order; absent on
  remote with linked launchers); salvo stores it in `SalvoData`: try exact position, then nearest valid nearby
  (`LaunchSite.PLACE_AREA`, 96 blocks, step-2 positions nearest first, `LaunchSite.nearest`; blocked-sector position
  blocks neighbors within 9 blocks; each site check counts one `Budget` cell). Map point may be water/forest/slope:
  crew moves to clearing/road/shore (before #397, 12-point ring 13–38 blocks around position missed valid sites 7
  blocks away by shore and 50–72 by Newisle lake/forest, launching outside world without launcher); launcher with
  `ordered` flag (NBT) faces target, reused by mark (`LauncherEntity.mark` in NBT; mark moved beyond 9 blocks gets
  new launcher; without mark, within `PLACE_REUSE`), does not count toward `MAX_PER_OWNER`, remains until Cancel
  (owner's own, operator for ownerless console launcher); unready site (`LaunchSite.PLACE_REACH` around it) sends
  order, even one projectile, through a salvo that loads site area (`LaunchPlaces`: `AreaLoader`, held while salvo
  runs), waiting at most `LaunchPlaces.WAIT`: launch visible from afar (`FarFlights`); without site area projectile
  would start outside world already in cruise (missile at cruise altitude, MLRS descending), nobody sees launch;
  timeout/no site launches directly from ordered location without launcher (outside world `VirtualFlights`); B-2
  takes only approach heading from it (nothing to wait for, no rack, `LaunchPlaces.fromPad`); ICBM boosts at the selected ground site, with the same area wait;
  command `via x z …` uses same `Waypoints` as map, both checked by `ServerActions.routeProblem`; launch origin
  `LaunchOrigin`: ordered place/fixed launcher, `launcher.md`), `VirtualFlights` (SavedData: projectiles outside
  loaded world are live entity objects absent from world; entering ticking chunks returns them with same UUID),
  `FlightTickets` (own `TicketType`: target area loads early in background, ticks only for last `AREA_TICK_LEAD`
  approach ticks (`etaTicks`; Lancet entire circle); missile additionally has `approach` lane, last 256 route blocks
  entity-ticking, visible to target-area player even when simulation distance below render distance; only with a
  player at target; salvo shares lane areas, `holdApproach`, at most `APPROACH_LIMIT` per world), `AreaLoader` (areas
  mod preloads: projectile targets, map hints; loads immediately, enables ticking only over ready neighbors,
  releases/unloads gradually, `server-chunks.md`; nuclear detonation location from launch, no ticking),
  `ArrivalTickets` (world entry, dimension change, player jumps within world, teleport/respawn: level-26 loading
  ticket on player/respawn chunk to load before salvo areas, `server-chunks.md`), `FlightStatus` (every 5 ticks sends
  owner's projectile phase/ETA), `ImpactCost` (server-thread impact time per tick by type: explosion rays, explosion
  `Detonate`/damage, blocks, glass, debris, fire/ground; tick over 50 ms logs “Impacts (…): N ms in a tick” with
  explosion steps in parentheses; queue waiting over 20 ticks, “N jobs queued”; each impact releasing `BlastArea`
  logs “Strike summary (…)”: work units, explosions, removed blocks, ticks, longest unit, explosion steps, ray-step
  counts (air, cache, craft queries, vanilla explosions), blocks over 10 ms; after 100 impact-free ticks, “Impact
  series summary (…)”: impacts, work units, longest unit/tick, ticks over 50 ms), `FarFlights` (each player receives
  tracks of projectiles absent as entities (beyond entity range/outside world): audible every 2 ticks
  (`Hearing.audible`: spec sound noise at real speed versus octave background after open-air path,
  `sound/Propagation`; same calculation gives client-layer volume, obstructions client-only; none beyond
  `Hearing.RANGE`, 60 s of sound); only-visible every 4 ticks to `visibility.far_range` (8000 blocks horizontally,
  including B-2 overhead at real 12 km level), at most 64 nearest; packet `S2C.FarFlights`; during launch (burning
  booster) also server ready-chunk ground below projectile, `FarFlight.ground`, distant client launch cloud rests on
  it, `FlightTrack.launchGround`); explosion packets `S2C.Blast` reach `far_range` or quiet-night charge audibility,
  `HearingModel.audibleRangeM`, capped by `IncomingConfig.FAR_RANGE_MAX`, whichever reaches farther).
- `target/`: `Target` (point; map location `Ground`, server finds height, canopy-inclusive top from ready chunk,
  otherwise estimated client map top `C2S.AimHint.mapSurface`, without it generator `getBaseHeight` no lower than its
  sea level (`Terrain.estimate`; below roofs in saved cities, can be world bottom in 1.17 worlds upgraded to 1.21);
  MLRS fixes impact point to surface when chunk ready, B-2 by release; `Ground` also command `at x z` target,
  `ServerActions.groundAim` (`at x y z` exact point), and salvo round around stationary ground center (`SalvoData`
  `around`: point at center height for distant target with unready launch-time chunk would hang above lowland;
  first projectile decides center airborne status, `aloft`, unready chunk means ground, retained until salvo end,
  otherwise salvo would split when area loads or early impacts open air beneath center); entity, Sable craft; codec),
  `TargetPicker` (crosshair: craft → craft block → entity → block), `TargetTracker`; `Target.Sighted`: spotted target,
  actual target (`quarry`, `subject()`) and last seen place (`seen`, `resolve` returns only it, so salvo does not
  follow target); `TargetTracker` moves location with target only while visibility predicate accepts (`inSight`),
  otherwise location stays and target is not lost. `Sight`: server line of sight without chunk loading,
  `sees(player, entity)` (not invisible, sent to client, horizontal range no farther than vanilla sends entity,
  `range`/`viewRange`, clear eyes-to-eyes/body-center), `within(player, point)`, `clear(world, from, to)`
  (`ClipContext.Block.VISUAL`: glass transparent; unready path chunk means unseen); `Sides`: friendlies by `/team`
  (same player/team; UUID also supports offline owner via profile cache). `Sightings`: side reconnaissance
  (`Sides.side`: team/player, non-persistent world attachment): every 5 ticks each player marks hostile players and
  Sable craft unobstructed in view (60° cone), through own eyes and own projectile-camera frame (same `CameraLink`
  view as guidance); radar/other sensors use `Sightings.spot`; retains `sight_memory` (30 s). Map (`mapPlayers`):
  friendlies always, hostiles/other spotted subjects at last seen place with age; without `sight_rules`, all players.
  `compat/SubLevels`: all Sable interaction (sable-companion, embedded jar-in-jar; Sable itself compileOnly).
- Target rules always apply to remote/binoculars/map/projectile camera; commands only without operator permission
  (`ServerActions.strike(…, rules)`, `IncomingCommand.rules`: `!hasPermission(2)`); console, command blocks, game
  master (`dispatch`), scene bench bypass them. Under rules target within `map_range`, `ServerActions.sighted`
  permits hostile player (and ridden entity)/craft only if spotted (`sight_rules`): shooter currently sees it
  (order `Sightings.sees`/`seesCraft`, camera retarget `CameraLink.holds`) or side saw it just now
  (`Sightings.CURRENT`), target itself; seen earlier, last seen place (`Target.Point`); otherwise refuse
  `incoming.target.unseen`. Entity/craft becomes `Target.Sighted`. Tracking comes from `WeaponSpec.Tracking`:
  `CAMERA` (Shahed/Lancet) follows target only while operator holds it in this projectile's camera frame, otherwise
  flies to last seen place; `POINT` flies to order location. Frame `CameraLink`: onboard client sends
  `C2S.CameraView` (camera rotation) every tick, fresh for 10 ticks, 25° cone, out to `REACH` (1024), `Sight.within`,
  clear `Sight.clear` ray, craft uses `SubLevels.visibleFrom` (ray can hit craft itself); player has one view,
  one guided projectile. Without rules target is `OfEntity`/`OfSubLevel`, followed without camera.

## Order lifecycle events (`ua.zentix.incoming.api`)
- Every order entry (designator, player command, `dispatch`, fixed launcher, `nuke now`) is admitted through
  `strike/Orders.admit` after Incoming's own checks and before payment: `Request` → one `Rejected`/`Accepted`
  (`Order.accept`/`reject`, once), all three posted at once (the next order must see the last `Accepted`). Each entry's
  outermost call starts with `Orders.elsewhere` (off the server thread, or inside a `Request` listener: the whole entry
  reruns later with all its checks and a live player object); a new entry does the same.
- Round, detonation and grid events never post inside Incoming's loops: `Orders.queue` puts them in one server-wide
  queue (overworld `StrikeWorld`), delivered at world- and server-tick end and last in `onServerStopping`. A
  synchronous post there would let listeners recall or clear inside salvo, key-wait and flight loops (double endings,
  `ConcurrentModificationException`) and reorder Released/Ended; per-world queues would reorder rounds that change
  dimension.
- Each admitted round ends exactly once. Rounds are `RoundRef` (order id + index, name-based UUID), saved on whatever
  carries them: `StrikeProjectile` NBT `round`, `ScheduledStrike.round`, `SalvoData` `order`/`failed_rounds`. A form
  change hands the round on (`VirtualFlights.park` detaches it from the old entity, B-2 release moves it to the bomb);
  a terminal path calls `endRound` before `discard` (first ending wins); any other destroying removal ends it as
  failed in `StrikeProjectile.remove`. Unreleased rounds end where their carrier settles (`SalvoData.settle`,
  `NuclearKeys.end`, the single-launch failure in `ServerActions.launch`). Regressions: `OrderEventGameTests`.
- Command results and `status` (`strike/OrderStatus`, `command/StatusCommand`): a strike command (and `launcher …
  fire`) returns `Order.admitted`; the answered order reaches it through the `made` callback of `strike`/`dispatch`/
  `fromLauncher`, also after a deferral (`Orders.deferred`: the command returns 0 and names the order when admitted).
  `status` reads rounds where they live, never a parallel registry that could drift: orders inside `Orders.admit`
  whose rounds get a carrier only after `accept` (`Orders.carrierPending`: single launch, key wait, `nuke now`; a salvo
  is recorded before `accept`, so a clear from an `Accepted` listener shows at once), `SalvoData.rounds`
  (unlaunched and failed, queued), `NuclearKeys.rounds` (queued), projectiles carrying a round in world and off-world
  (`VirtualFlights.inFlight`: disk records instantiated, flights entering the world this moment included),
  `ScheduledStrike`s (a `nuke now` waiting for ground, launch time = burst time, is queued), and impact work:
  conventional `ImpactWork` (the round's `BlastArea` holders; a new impact job must retain the area or the round
  leaves the count early; a blast's setup releases its holder if it throws), nuclear `NuclearEvents.Impact` (saved;
  kept while `NuclearWorld.frontBusy` or `craterDigging` (a live job; a dropped one resumes only after a restart),
  dropped the first tick neither holds, so ruins on later chunk loads never reopen it; deferred craft blasts are
  `ImpactWork` of the same round). Unfinished = Accepted
  until Ended, plus impact work. A tag is world-wide, saved with `RoundRef`; `clear tag` follows `clear`'s owner rules
  (`ServerActions.recall(…, tag, …)`, `clearTag`), leaves launchers (`LauncherEntity.serves` matches by owner and
  weapon only), removes the ICBM showing a cancelled strike (`IcbmEntity.strike`), and tells clients only what is gone
  (`S2C.Cleared` with `Util.NIL_UUID`, `S2C.NukeCancelled`: a full `NukeSync` would restart live detonations), plus an
  owner notice ending the salvo progress line when no salvo of that owner still launches.
  Regressions: `OrderStatusTest`, `OrderStatusGameTests`, `OrderStatusRegressionGameTests`, `ImpactRegistrationTest`,
  `TaggedClearAlertsTest`, `TaggedSalvoHudTest`, `CraterImpactTest`, `FlashAftermathClockTest`.

## Advancements and statistics (`progress/`)
- `Progress` credits only from the api events as delivered (`Released`, `Ended`, `NuclearDetonationEvent`,
  `GridRestoreEvent`), never from Incoming's own loops: one place decides, and credit follows the round's owner UUID
  across off-world flight and restarts. Credited = in the player list at delivery and not `isFakePlayer()`; nothing
  is queued for an offline owner. Online players of a level come from the player list filtered by level, not
  `level.players()`. Interceptions skip rounds friendly to the interceptor (`Sides.friendly`, the SAM radar's rule).
- Camera credit is `StrikeProjectile.cameraGuided` (saved as `camera_guided`, set by `retarget` and a pursuit HELD
  tick), carried to `RoundEnd.cameraGuided`/`Ended.isCameraGuided` by `endRound` and `detonateNuclear`.
- Nuclear survival: watched from 0.3 psi (`Detonation.HIT_KPA`) unless creative/spectator, due at front arrival +
  positive phase + `LANDING_TICKS` (`survivalDue`); the watch is the unsaved player attachment `SURVIVAL`, cleared on
  death; the advancement's 5 psi is in its JSON (`Blackouts.DEAD_PSI`, `ProgressTest`). Witnessed = horizontal range
  within `Detonation.radiusMax`.
- Stats (`ModStats`) are created at common setup: vanilla lists in the statistics screen and accepts as scoreboard
  criteria only created stat objects. A new weapon adds `rounds_launched.<id>` and its lang key automatically fails
  `ProgressTest` until translated. GameTest players that must earn advancements or stats are real `ServerPlayer`s
  (`ProgressGameTests.Credited`): `FakePlayer` drops stats and advancements; connection before `moveTo`.
  Regressions: `ProgressGameTests`, `ProgressTest`.

## Unpaid nuclear review
- `NuclearReview` is separate player state, not a `NuclearKeys` order. Global nuclear abort invalidates it on the
  server before sending cancellation; actual death invalidates it through `LivingDeathEvent`, with dead/removed
  guards on requests and actions. Respawn attachment loss is not death-time invalidation. Regressions:
  `NuclearReviewSafetyGameTests` (`nuclear_review_abort`, `nuclear_review_death`).
- A read-only review may disclose coordinates without inventory/payment. Launch permission (`ruled`/`sighted`)
  alone is insufficient: map exclusions apply even for teammates and with `sight_rules=false`. Unseen hostile
  players use the remembered position, including during `Sightings.CURRENT`'s grace period, never unobserved live
  movement. Keep ordinary launch resolution separate from review disclosure. Packet-level regressions:
  `nuclear_review_disclosure`, `nuclear_review_last_seen`.

- Authorization (`strike/Authority`, roadmap B1): one policy for every entry (designator packets, player commands,
  deferred reruns, a key wait as its key turns, fixed launcher owner, linking, second key): `check(Action, Actor)` →
  null or `Denial` (gate, line). Rights are NeoForge `PermissionNode`s (`incoming.launch`, `incoming.launch.<weapon
  serialized name>`, `incoming.nuclear`, `incoming.launcher.link`) whose default resolvers are the switches
  (`designator_for_everyone`, `nuclear.ops_only`, operator by `hasPermissions(2)` online or the cached profile offline,
  `NuclearKeys.trusted`), so the default handler answers exactly as before; its refusal lines stay the switches' own,
  a foreign handler's say only what is refused (`Denial.byDefault`). Gates: designator, weapon, nuclear flag (hard),
  `incoming.nuclear`. Trust is never a node: second key, warning, nuclear abort/retarget, map launch site stay
  `NuclearKeys.trusted`. The actor is who gave the order: a command's `CommandSourceStack.source` player (also through
  `/execute as`; `ServerActions.strike(asker, player, …)`, re-read live after a deferral); console, command blocks and
  ownerless launchers are automation and ask nothing, except that a flying nuclear order always asks the player it is
  made for (`nuclearRefusal`, as the switches did); `nuke now` asks only a typing player. A key wait re-asks its owner's
  rights (not carrier/yield validity) when the key turns, after finding the order (no-order line unchanged); an armed
  order rechecks the nuclear flag before release (a paid order whose owner is offline waits for them: refunds reach only
  online inventories). A non-player executor of a player's command (`/execute as` an armor stand) asks that player
  (`dispatch(asker, …)`); a deferred `nuke now` re-reads its player by UUID (`ServerActions.live`) and needs them still
  operator. An offline owner never calls `PermissionAPI.getOfflinePermission` (NeoForge promises nothing about storage
  there) and nothing is kept from their last visit (its context, e.g. dimension, would be wrong): the node resolver
  answers, as the default handler does. A handler that lacks a node falls back to its resolver.
  Regressions: `AuthorityTest`, `PermissionGameTests`, `PermissionRegressionGameTests`, `PermissionRoundTwoGameTests`
  (devtest handlers swapped into `PermissionAPI`, one batch per probe: the handler and switches are server-wide).
- Nuclear permission (Artem): `nuclear.ops_only` means operators and the world host (`NuclearKeys.trusted`: the
  single-player or LAN host, cheats on or off). Review and launch take the refusal from one function,
  `ServerActions.nuclearRefusal` (`Authority`: designator, weapon, enabled, `incoming.nuclear`; then carrier, yield cap,
  in that order; `NuclearRefusalTest`). Every refused order speaks through `ServerActions.refuse` (red action bar,
  `RefusalChannelTest`); every key named in code exists in both lang files (`LangTest`).

- `world.early_warning` controls automatic sirens and nuclear launch/target notices (default true). `ServerConfigMigration`
  decorates the registered server `IConfigSpec`: copy legacy `world.siren` before spec correction removes it; an explicit
  new key wins. `IncomingConfigScreen` passes the underlying `ModConfigSpec` to NeoForge section widgets. Warning
  records are owner-only with alarm false while disabled, including resync; `NukeApproach` and sensor tracks remain.
