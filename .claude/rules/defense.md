---
paths:
  - "mod/src/main/java/ua/zentix/incoming/defense/**"
  - "mod/src/main/java/ua/zentix/incoming/entity/InterceptorEntity.java"
  - "mod/src/main/java/ua/zentix/incoming/guidance/Homing.java"
  - "mod/src/main/java/ua/zentix/incoming/client/hud/RadarScope.java"
  - "mod/src/main/java/ua/zentix/incoming/client/render/SamRenderer.java"
  - "mod/src/test/java/ua/zentix/incoming/defense/**"
  - "mod/src/devtest/java/ua/zentix/incoming/gametest/SamGameTests.java"
---

# Air defense (`defense/`)

## Structure
- `SamBlock` + `SamBlockEntity` — SAM: the block is a 1×1×1 radar cabin (block model); above it,
  `client/render/SamRenderer` draws a rack of four vertical tubes at real scale (`WeaponModels.Mesh.SAM_PACK` from
  `tools/gen_models.py`, sam: tube 0.62 × 4.3 m, top ~5.4 m; rack numbers match `SamBlockEntity`, render bounds are
  `PACK_HALF_WIDTH`, `PACK_TOP`); the rack is visual only, no collisions; item icon is a miniature
  (`models/item/sam.json`). The missile sits in a tube (`SamBlockEntity.rail`: center of the rail's tube) and leaves
  vertically (`InterceptorSpec.SAM.elevation` 90, like the 9M96). Owner is the placer (`setPlacedBy`), side is
  `target/Sides`; stock is `util/Magazine` (same as the fixed launcher), 2 slots of 16 interceptors each; external access
  (`Capabilities.ItemHandler.BLOCK`: hopper, Create funnel) inserts only, no extraction; rails (`rails`) fill from stock one at
  a time per `reload_seconds`; sky sweeps every `SWEEP` ticks, staggered by block position; no firing under a roof
  (its ready chunk's heightmap is above the block); broken or exploded, it drops stock and rail missiles (`onRemove`),
  the block itself drops by loot table (`survives_explosion`); comparator reads stock.
- Air defense uses its own game model (Artem's decision, 21.10): ranges, missile flight and fuze are game numbers in
  `InterceptorSpec`, not data or laws of a real system (guidance, radar equation and kill probability are not modeled).
- Separate parts, reusable by a portable SAM: `Radar` (only world projectiles in `StrikeWorld.projectiles`, one snapshot
  per tick in `DefenseWorld`; visible within the SAM spec's radar range (`InterceptorSpec.radarRange`) ×
  `WeaponSpec.Signature.visibility`, and only in line of sight — `util/LineOfSight` over ready-chunk heightmaps
  (`MOTION_BLOCKING_NO_LEAVES`, unready chunks do not occlude); hostile means not `Sides.friendly` by owner UUID;
  ownerless projectiles are hostile to everyone; engage if hostile, `Signature.intercept`, unridden, within
  `InterceptorSpec.engageRange`), `FireControl` (nearest target not pursued by anyone's missile: one missile per target
  across the world, a miss releases it), `AirAlert` (alerts the side to a new hostile target: direction and distance
  line to all friendlies in the world, signal at most once per 5 s; a target unseen by the side's radars for 10 s is
  forgotten), `Interceptor` (flight is an object without a world: leaves the rail toward the target at the spec's angle
  (vertically for SAM), speed follows the S2 powered-flight law (`WeaponSpec.Handling.speed` via `InterceptorSpec.speed`:
  motor toward maximum speed, boost increment `boostAccel`, lower in cruise by the spec's thrust ratio; exchange altitude
  for speed); then heads toward the target's current position each tick, with `guidance/Homing` turn limit from lateral
  acceleration, `InterceptorSpec.turnLimit`: tighter on boost; fuze at closest approach (closest point of the missile's
  and target's tick segments within the tick, not its end: if still closing, wait a tick) within `fuse` + target
  half-length: detonation means target down, no dice roll; if it passes farther away, it turns after the target while
  fueled; target gone, ridden, or fuel exhausted means self-destruct; ground contact means detonation; kill shares by
  target class: `InterceptShareTest`, numbers in `docs/guide/defense.md`), `InterceptorSpec` (flight numbers are for the
  game, not physical; flight scale brings maximum speed to real `maxSpeed()`, acceleration takes the same ticks
  `boostAccel()`, no flight above `ceiling()`, the 9M96 zone's altitude ceiling; real 9M96E2 specs in `passport`: mass,
  dimensions, speed, g-load, zone, warhead, fuze, motor — its mass flow also feeds the client exhaust flame),
  `DefenseWorld` (non-persistent world attachment: flying missiles, pursuit assignments, side alerts; ticks in
  `LevelTickEvent.Post` after `StrikeWorld` — targets have already moved this tick).
- Shot down: `StrikeProjectile.intercepted` — warhead does not fire; `DebrisSpawner.wreck` inherits target velocity
  (only in ticking chunks); a projectile outside the world is simply removed; log:
  “Projectiles: … shot down by a SAM at …, SAM x, y, z” (`FlightLog.Event.INTERCEPTED`). Nearby clients get
  `S2C.Intercept` (burst cloud is the warhead-charge cloud model `FarBlasts.airburst`: flash, sphere, effects-layer
  billow cloud, distant roll; nearby sparks and pop `BlastEffects.Airburst`; the downed track ends); friendlies within
  48 blocks get `S2C.RadarScope` (`client/hud/RadarScope` screen); hostile radar targets go on the side's map
  (`Sightings.spot`, `Source.RADAR`; friendlies are not marked — the map draws marked targets as hostile).
- Look and sound: `entity/InterceptorEntity` exists only over ready, ticking chunks, is placed at the flight position
  each tick, is not saved; model `interceptor_body` (`tools/gen_models.py`, far away `FarFlightView`), plume:
  `PlumeRenderer` (`InterceptorRenderer`), ignition flash and nozzle fire: `Exhaust.interceptor`. Client track:
  `FlightTrack` without a weapon (`FlightTracks.interceptors`, separate from projectile tracks: it has no weapon), from
  the entity or distant `S2C.FarFlights` (`weapon = FarFlight.INTERCEPTOR`, flight UUID stays constant across all its
  entities). Distant tracks every 4 ticks continue the cruise trail beyond entity tracking range; without them the SAM
  missile left tracking range during boost, so the model's cruise smoke was absent on screen. Terminal sample
  `FarFlight.ended` closes the track at the burst; a late sample cannot revive it. Transmission loads no chunks. Along
  the track: launch cloud (`LaunchCloud`, while the jet hits ground) and ribbon trail (`FarFlightView`, appearance:
  `ClientWeaponSpec.INTERCEPTOR`) by the same law as projectiles; motor is `EngineSound.Layer.SAM` in the shared
  `ClientSounds` engine system (law: `client/sound/InterceptorSound`, solid rocket source `Noise.Rocket` from spec thrust
  and exhaust, path `Propagation`, volume `Loudness`; booster loop, pitch 1.2). The air-defense track
  (`FlightTracks.interceptors`) starts sound, not the entity tick: a distant missile driven only by server packets
  sounds the same; channels share `VoiceBudget`, restarting after F3+T, device and slider changes like projectile
  motors (engine channel, `ClientSounds.playing`); a visual returning to the client with the same UUID gets a new track
  and its own sound. Ignition hit occurs when the motor is first heard from rail exit itself (`InterceptorSound.heard`:
  `launch.booster` from boost motor power, like projectile boosters); the server sends no launch sound. Look-gate
  scenes: `sam-*` in `ProbeScenario` (SAM on a platform above the forest: otherwise terrain occludes the Shahed
  differently in the new version and 3.4.1).

## Pitfalls
- Targets are only mod projectiles (Artem's decision: air defense against mod strikes). SAM does not target players,
  mobs, ridden projectiles or Sable/Create Aeronautics craft; targets are found by UUID among mod projectiles
  (`DefenseWorld.find`).
- Load no chunks: radar uses projectile lists and ready-chunk heightmaps; missile visuals only in ready, ticking
  chunks; block ray stops at the first unready chunk (`Terrain.readyUntil`); beyond it, only ready-chunk heightmaps
  count as ground: sea level is a guess, and targets can be below it (GameTest `loadsNoChunks`).
- Turn limit is lateral acceleration, not a single angular speed: `InterceptorSpec.lateralG` 40 g (open sources give
  “over 20 g” for the 9M96 at altitudes up to 35 km), ω = a / v. After rail exit this is ~18°/tick, an arc from vertical
  to horizontal in ~0.3 s (`InterceptorFlightTest.pitchOverIsAnArc`), at full speed 4°/tick. It cannot be lower: a missile
  leaving the rail toward a Shahed passing directly over the SAM would turn behind it and follow the diving target
  into the ground (`catchesTargetPassingOverhead`, `catchesDivingTargetOverhead`; at 30 g it misses if the Shahed is
  18 blocks short of the SAM at launch or already overhead, as with the second missile in GameTest
  `shootsDownHostileDrones`); a single angular speed has the same problem.
- Interceptor flight is not saved: after a server restart mid-intercept, the missile is gone, the target keeps flying.
- A nuclear warhead on a cruise missile or B-2 is intercepted like a conventional one (signature belongs to the carrier);
  an ICBM is not.
- GameTest servers have no profile cache: `Sides` recognizes the owner's team by UUID only if the player is “online” —
  `SamGameTests.listener` puts a `FakePlayer` into `PlayerList.playersByUUID` via reflection. The SAM would shoot down
  other tests' missiles: each SAM test has its own batch, and its SAM is removed afterward (`SamGameTests.sam`); old
  test structures remain, and a loaded SAM ticking beside the next structure would shoot at its projectiles too.
- GameTest surrounds the structure with a barrier wall as tall as the template: it hides low targets behind it from
  a SAM radar on the platform (for “runway”, the wall top is 3.5 blocks above the antenna, 16 blocks toward ±X). Distant
  test targets must be above the wall or toward +Z (wall 156 blocks away); −Z and −X contain previous batches' structures.
