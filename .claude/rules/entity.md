---
paths:
  - "mod/src/main/java/ua/zentix/incoming/entity/**"
  - "mod/src/main/java/ua/zentix/incoming/guidance/**"
  - "mod/src/main/java/ua/zentix/incoming/strike/VirtualFlights.java"
  - "mod/src/main/java/ua/zentix/incoming/strike/Waypoints.java"
  - "mod/src/main/java/ua/zentix/incoming/client/cam/**"
  - "mod/src/test/java/ua/zentix/incoming/guidance/**"
---

# Projectiles and guidance (`entity/`, `guidance/`)

## Structure
- `entity/` — `StrikeProjectile` (shared flight: collisions, rail start `launchTick`, `virtualTick`/`materialize` for
  flight outside world, retargeting, range reserve; parts in `entity/flight/`: `ChunkHold` holds own/ahead chunks
  (ahead only at launch while someone can see, `FlightTickets.watched`, by distance not `seenBy`, and when target
  area already ticks: otherwise projectile would drag ticking chunks across map and leave/reenter a tick earlier or
  later at target-area edge depending on fresh chunks' entity ticking); `TargetAreaHold` target area/approach lane,
  `ProximityFuse` arming/proximity fuze; entity exposes itself to autopilot as `guidance/Craft`; `Target.Sighted` with
  `WeaponSpec.Tracking.CAMERA` follows operator camera frame, `strike/CameraLink`, see `strike.md`; synchronized
  `Pursuit` camera line: target in frame or flying to last seen point), `FlightPhase` (synced: on launcher → ignition
  → boost → climb → route → pop-up → attack; drives effects/sound), `DroneEntity`, `LoiterEntity` (Lancet: catapult,
  `LOITER` circle over target, dive by time or camera target), `CruiseMissileEntity`, `RocketEntity` (MLRS ballistics
  from rack tube), `BomberEntity` + `BunkerBusterEntity` (Young penetration: on entry, block-path `plan` with medium from
  `incoming:penetration` data map (`registry/ModDataMaps`, law `warhead/Penetration`); entity follows in time, channel
  one axial block, void sensor after 4 m solid once bomb fully entered; unready chunk ahead means in-place detonation;
  path not saved, replans after load at same speed), `IcbmEntity` (boost only: Minuteman III first-stage thrust/mass,
  spec `WeaponSpec.Ascent`, sources in `WeaponSpec.NUKE` javadoc; simplified pitch strictly axial 3 s, then 85° above
  horizon (drift ~30 blocks, farther sideways would enter chunks not yet ticking entities); exceeds world ceiling
  + 320 after ~8.5 s from y 64; ahead chunk by ground heading, not nose), `LauncherEntity` (launcher rack/slots/queue),
  `SpentBoosterEntity` spent booster, `DebrisEntity` ballistic debris, `InterceptorEntity` SAM missile visual only,
  flight via `defense/Interceptor`, downed projectile `StrikeProjectile.intercepted`, `defense.md`.
  `guidance/FlightController` turns with speed/acceleration limits (inertia; model bank by turn rate with game g 0.08);
  powered-projectile game model numbers in `WeaponSpec.Handling` (phase `Agility`: climb/cruise/dive; climb/descent
  angles, motor `accel`/`relax`), per-tick speed `Craft.throttle` → `Airframe.airspeed` (motor pulls toward desired,
  climb subtracts/descent adds `Handling.TRADE` · g · sin γ, capped `diveSpeed`); `guidance/Route` side bypass/entry
  points, approach from behind target; salvo projectile in own lane (`inLane`: lateral offset smoothly from zero at
  `exit`, back to zero over `converge` before target, boundary points added; turns use bisector; game numbers
  `WeaponSpec.Lanes`); or operator `strike/Waypoints` (target map Shift+left-click, ≤ 5 x/z points; Shahed/missile/Lancet,
  specs with `WeaponSpec.Route.reach`: path from player (order launch location, console from first point) via points
  to target within 30 km, Lancet 15; points captured within `capture`, about turn radius; map/command
  `/incoming salvo … via x z …` checks `ServerActions.routeProblem`; launcher faces first point, distant projectile
  enters before it via `Waypoints.afar`; range/ETA/siren follow waypoints; salvo stores route in `SalvoData`, retarget
  abandons via `Route.skip`; final leg determines missile pop-up after points only for operator route,
  `Route.byOperator`, not launch route; `WAYPOINTS` scenario); `guidance/Homing` interceptor pursuit (bounded turn,
  closest approach within tick); `guidance/Ballistics` unpowered gravity/quadratic drag (`WeaponSpec.Ballistic.drag`,
  game number), altitude density/world wind (`Path` by tick, 4 steps/tick), firing solution `aim` (Newton over speed/
  heading at rack angle) for MLRS with ground wind `Wind.ground`, flies through wind profile: wind drift/spec rail-exit
  spread disperse salvo; drag-free parabola remains for launch-sector check; `guidance/Orbit` loiter circle (heading
  vector field with v/r lead, tangent entry); `guidance/BombDrop` bunker-buster fall (controls steer nose, speed from
  gravity along path/drag, wind `drift` through `StrikeProjectile.drift`): same steps for bomb/B-2 simulating drop
  (no wind, controls compensate) and releasing when it will hit from current altitude; bomb drag via flight-scale
  `Airframe.drag()` (`Ballistics.Drag` overloads for other-scale tests); B-2 flight level scale `cruiseHeight()` up to
  12 km, release line same target angle (`BomberEntity.releaseLine`), bomb range straight to point × 1.5, at least spec;
  `guidance/Mission` range in blocks instead of lifetime: plan path × 1.5 + 600 cruise-speed ticks, Lancet extra
  cruise-speed circle/dive-speed dive, MLRS arc + 200 ticks; pursuit adds target movement × 1.5; lost target grants
  path to last point × 1.5 + 200 cruise ticks once; spend actual tick travel, so area waiting/stretched MLRS flight
  costs no range (wait has `AREA_WAIT_LIMIT`); old lifetime saves migrate remaining ticks × saved cruise speed.
  `guidance/Autopilot` shared guided behavior (turn circle/reapproach, waypoint heading),
  `DroneAutopilot`/`MissileAutopilot` post-launch Shahed/missile laws without Minecraft world (`AltitudeHold`, `Bearing`
  point bearing; lookahead terrain `Autopilot.reliefAhead`: 3-block-wide `Corridor` over predicted turn path out to
  spec `reliefLookahead`, all columns via `GridWalk`, distant columns subtract `Corridor.Climb`; `ReliefSensor` samples
  every 4 ticks; `StrikeProjectile.relief` over entire path inside/outside world (`Terrain.Allowed.RELIEF` /
  `VIRTUAL_RELIEF`, no Nether ceiling: ready chunk, else `util/TerrainSurvey` memory/disk heightmap, absent-on-disk
  chunk uses background generator, pending answer world bottom); projectile orders 1024-block route survey once/sec
  (`surveyAhead`); target-approach straight line `Autopilot.Sightline`, if blocked (`Craft.clearAlong`: block cells in
  ready chunks, beyond/outside world survey terrain top, `Terrain.underRelief`), do not attack; missile stays in
  pop-up, Shahed cruises without climbing (climb raises nose, preventing timely dive along same line over obstacle),
  at most `DIVE_MARGIN` nose-turn paths; no moving-target lead, Artem's decision 21.10); unit test
  `AutopilotPropertiesTest` checks 10 000 random model-terrain flight scenarios.

## Pitfalls
- Bomb ground path (`BunkerBusterEntity.plan`): remaining axial distance inside block but outside shape shorter than
  `GRAZE` (1 µm) is face float error, not void. Otherwise angled crossings between same-medium blocks “exited into
  void”, restarting Young layer: B-2 bomb at 89° penetrated 18–29 % beyond law (80° 40 %, vertical exact; GameTest
  `bomb_oblique`, found by end-to-end `surface_bomb`).
- Straight target approach (`Autopilot.Sightline`) only while nose can finish turning: projectiles do not bypass
  target-adjacent house steeper than ~60° (missile, `MissileSightlineTest`), tall building within ~45 blocks of target
  (Shahed, `DroneDiveTest`), or roof-covered target, as guide states.
- `Entity.onRemovedFromLevel` (NeoForge, from `onTrackingEnd`) also fires when chunk merely stops being served;
  already world-invisible entity unloads with chunk (`setRemoved(UNLOADED_TO_CHUNK)`) without it, unload never calls
  `remove`. Thus release tickets in both `remove`/`onRemovedFromLevel`, check step before moving at ticking edge
  (`leavesTickingChunks`): entering non-ticking chunk would leave it stuck without ticks.
- Client gets entity only within horizontal `min(clientTrackingRange, render distance)` and player-tracked chunk
  (`ChunkMap.TrackedEntity.updatePlayer`); vanilla distant viewing only spectator camera moving player itself.
  Projectile camera beyond render distance therefore shows telemetry map, not image. In cloud, weak-CPU generation
  brings nearby chunks to entity ticking slowly: projectile 90 blocks away may stay outside world, no flight bug.
- `ChunkHold` holds projectile's own chunks. World movement, including bomb drilling, only `moveAlong` (ticket follows
  chunk): bypassing via `setPos` into neighboring non-ticking chunk leaves flight endless, tickets holding self/target
  area until shutdown (bomb through air under thin slab, GameTest `virtual_risen_ground`). Take own tickets before
  world entry (`holdChunks`, on launcher/return from virtual flight). Failed entry (`addFreshEntity` refusal or
  `EntityJoinLevelEvent` handler throws, protection mods) releases only via `discard`, otherwise until shutdown.
  `VirtualFlights.tick` catches return failures (remove projectile, others continue), otherwise world tick crashes.
- Target area/lane `TargetAreaHold` belong to mission UUID: `VirtualFlights.park` creates copy, hands over via
  `handOverTargetArea`, only then removes old entity. Old form no longer owns area, its `remove`/repeated
  `onRemovedFromLevel` release only its `ChunkHold`. Reentry uses same object/hold. Save/copy errors, final discard,
  cancellation/retarget release ownership; `AreaHandoverGameTests` checks unchanged ticket objects/lane references
  across transitions for ready/unready areas. Tickets do not persist through restart. Otherwise projectile releases
  own chunks/area/lane in `remove`/`onRemovedFromLevel`.
- Outside-world projectile (`VirtualFlights`) is entity object absent from world, `isVirtual()` true; do not add
  (duplicate UUID). Return places above terrain beneath/ahead over 5 flight ticks, sampling each block: 4-block
  steps would miss wall/hit it; target-ending path (`pathEnd`) only to target (terrain beyond would raise missile onto
  cliff at target, bursting in air beside it). Leaving `park` creates new entity, tests/camera find by UUID not
  reference. No virtual collisions, but no going below surface (`groundCrossing`): ready-chunk heightmap, otherwise
  generator sea level (`ChunkGenerator.getBaseHeight` ~3 ms/point, not for tick; `Level.getSeaLevel` always 63 in
  1.21.1). No virtual terrain knowledge, flies at target altitude, target may be below sea/terrain (quarry/ravine):
  below surface but no more than 16 blocks below target (`BELOW_AIM`), keeps flying; floor may rise above projectile
  (target moved up/chunk loaded). Descending ballistics (bomb/MLRS) below floor already underground, hits overhead
  surface. Guided `climbs` below floor only when floor rose, continues descending several ticks turning upward,
  floor does not stop it. Nobody goes below world bottom. On arrival waits loading like target, hits on first world
  tick. Cannot reach cave target below heightmap: `materialize` returns above terrain, explodes on surface above it.
- Client position (`StrikeProjectile`, `InterceptorEntity`) uses `ArrivalQueue`, one position/client tick: uneven
  server packets (none then two, server catch-up, Flashback stores same) with “last packet per tick” would stop/jump
  two steps, jerking chase camera. Empty predicts on credit up to 2 ticks, queue length ≤ 2, jump > 64 blocks
  (sum over axes) immediate, no jump extrapolation (`ArrivalQueueTest`). Predict from last received position with its
  server-tick step; positions received during borrowed ticks advance it: server < 20 ticks/s supplies fewer packets
  than client ticks, debt never clears; predicting from own position using old step would drift hundreds of blocks
  off path (circling Shahed along tangent). First-frame rotation from spawn packet (`recreateFromPacket`), otherwise
  model turns from south in first frames.
- Guidance heading/pitch (`FlightController`), pitch capped ±89°: vertical residual drift follows heading, pitch cannot
  catch target behind heading. Lancet dive from 700 m circle (flight scale 1) ends vertical: `Dive.steer` turns heading
  even directly over target if behind (fractions-of-degree nose turn at vertical), otherwise miss up to 10 m
  (`loiterLosingTargetAtRealHeightStillDives`, fixed seed).
