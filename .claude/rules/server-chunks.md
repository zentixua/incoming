---
paths:
  - "mod/src/main/java/ua/zentix/incoming/{strike,entity,guidance,target,work,warhead,nuclear,grid,util,compat,mixin,command}/**"
  - "mod/src/main/java/ua/zentix/incoming/Incoming.java"
  - "mod/src/main/resources/META-INF/accesstransformer.cfg"
  - "mod/src/devtest/java/ua/zentix/incoming/**"
---

# Chunks, tickets and server tick

## Pitfalls
- Ground location only via `util/Terrain.estimate`: height/source `CHUNK`/`CEILING`/`DISK`/`CLIENT_MAP`/`GENERATOR`/`SEA`/
  `UNKNOWN`, caller chooses allowed sources: `Allowed.CHUNK` in world, `FLIGHT` outside, `ORDER` for orders, `RELIEF`
  for entire flight path, `VIRTUAL_RELIEF` outside world/straight target line beyond ready chunks (no Nether ceiling);
  no direct `getBaseHeight`/`getSeaLevel` outside `Terrain`/`TerrainSurvey`.
  For a flight corridor, `Terrain.relief` returns a `util.Relief.Survey` for one synchronous measurement:
  readiness and source selection run once per unique chunk, each column height is read once, and every geometric
  visit and climb correction stays in `Corridor.highest`. Never retain views or heights between measurements,
  even within a tick: blocks and readiness can change. `Terrain.choose` is shared by single-column estimates
  and chunk views. `Craft.reliefSurvey` is created during the sensor refresh; its four-tick period is unchanged.
  `RELIEF` uses `DISK`/`GENERATOR` only from `util/TerrainSurvey` (non-persistent world attachment): in-memory unready
  chunk (`Terrain.inMemory`) gives its heightmap immediately; otherwise first query requests chunk-file `Heightmaps`
  on chunk I/O thread (`chunkScanner`, like `DiskStatus.scan`; also returns unsaved data, unloaded-chunk writes queue
  before any later reads), returns null. Same queue loads player chunks: at most `MAX_READS` (64) in flight, others
  in own queue (sensor queries first, lookahead preorders after), same generator priority; repeat query promotes
  waiting preorder. Total live orders including generator ≤ 8192, preorders at most half. Within limit, no dropping:
  otherwise neighboring flights (scenario batch shares world) and disk speed would decide admission. Background
  answers publish only at world-tick end (`publish`): mid-tick answers would be visible to one corridor column and
  not its neighbor, making flight disk-speed-dependent (`missile_far.mob.*` differed between runs). Absent/partial
  disk chunk uses generator on one background thread (shared Minecraft pool, leaving other threads for worldgen,
  next order after previous completion), nodes every 8 blocks; final blocks but unreadable maps (pre-1.18 format or
  length mismatching world height) mean unknown terrain, not generator: world not built by it. Below-zero retrogen
  (`below_zero_retrogen`) alone does not invalidate map: 1.18 `ChunkHeightAndBiomeFix.getFixedHeightmap` already shifted
  heights to new world bottom; `TerrainSurvey.decode` reads if blocks final/packing matches world height. Disk read
  opens region file, creating empty 0-byte file if absent, like vanilla old-chunk lookup beside fresh generation.
  In-memory chunks answer themselves; on leaving (`ChunkEvent.Unload`, before save) leave survey same heightmap sent
  to disk: vanilla unloads in spare tick time, otherwise chunk unknown for a tick or two at varying times across
  runs. Always retain `MOTION_BLOCKING`, even before first query; each kind ≤ 8192 maps, least recently read evicted
  immediately (LRU), so new unload cannot evict active flight's repeatedly read map. Cancel all chunk orders on load/
  unload regardless of map presence; late answers cannot replace new data. Retain only full `LevelChunk`/primed map
  (`hasPrimedHeightmap`): partial chunk map lacks structures/is empty, `getHeight` would prime and write it. Generator/
  read failure means `UNKNOWN` for 200 ticks before retry (queries never extend), not WARN/new job each tick; I/O
  error does not mean absent chunk. Flight scenarios settle orders at tick end (`TerrainSurvey.settle` from
  `InstantChunks`). `Terrain.height` gives `CHUNK` without selection to callers already knowing readiness:
  `LaunchSite`, `Warheads` explosions, `nuclear/world`, ICBM site behind player in `NuclearStrikes`.
- World exit may hang on “Saving worlds” (vanilla unload loop `ChunkMap.processUnloads`): shutdown calls
  `ServerChunkCache.tick(() -> true, …)`, drains unload queue without limit; unload task for a revived holder retained
  by generation (`getGenerationRefCount() > 0`, generation retains whole window, canceled tasks until layers finish)
  immediately reenqueues itself, while generation layers complete only via server-thread tasks
  (`ServerChunkCache.pollTask`) unreachable until this call returns. Pre-exit generation started by mod tickets
  (areas, zone, ruin prep, approach lanes, player arrival), less often player tickets. Do not change vanilla loop
  (Artem's decision): `util/StopDrain` in `ServerStoppingEvent` (`LOWEST`, after mod releases tickets/work, all areas via
  `StrikeWorld.releaseAreas`, otherwise wait applies them and awaits whole-salvo generation) executes each world's
  chunk tasks until no holder (`chunkMap.getChunks()` after `runDistanceManagerUpdates`) is generating, ≤ 30 s
  (INFO every 5 s, WARN at limit: exit likely hangs; `stopReleasesAreasBeforeDrain`). GameTest
  `stopDrainLetsVanillaUnloadFinish`: unload-queued holder revived by load-ticket generation has no generation after
  wait, unchanged vanilla shutdown completes; `ServerStoppingEvent` also removes all ruin work/tickets, stops
  `RuinWorkers` (`stopMidZoneLeavesNoWork`). Still hanging: capture `jstack`.
- Chunks not yet ticked (fresh generation at render edge) hold block entities as deferred data (`pendingBlockEntities`:
  generated “DUMMY”, loaded `keepPacked`). `onRemove`/`removeBlockEntity` miss them; replacing block leaves data in air,
  vanilla logs “Tried to load a DUMMY block entity”. World block changes via `ColumnScar.replace`, first materializing
  through `level.getBlockEntity`.
- Never kill entities while iterating live `level.getAllEntities()` map (loot added directly); snapshot
  `getEntitiesOfClass`.
- Region ticket `addRegionTicket` immediately enables ticking by level, not neighbor readiness: block entities tick
  if chunk in ticking count (`Level.tickBlockEntities` → `shouldTickBlocksAt`, `DistanceManager.tickingTicketsTracker`)
  and load level ≤ 32 (`LevelChunk.isTicking`); edge beehive/trial spawner reads generating neighbor synchronously
  (`ServerChunkCache.getChunk`: `UNKNOWN` ticket level 33, `managedBlock` wait) behind generation queue, stalls 2–12 s
  (rig “stall:” line). Thus mod areas use `AreaLoader`: immediately place load-only ticket
  (`DistanceManager.addTicket`, loading tracker only) at same `33 − d` (unique per area, value area itself, identical
  vanilla tickets merge); grow region ticket by ready squares. `targetAreasTickOnlyNextToReadyChunks` catches ticking
  beside unready chunks (hundreds of ticks with vanilla region ticket). Generation queue by ticket level, lower first
  (`ChunkTaskPriorityQueue`); levels > 33 do not initiate generation. Tested on rig, unsuitable: cap loading-chunk
  count, wait whole area before ticket, grow rings with level-33 tickets (areas miss projectile deadlines); lower
  priority too (level-32 nuclear site not ready in 90 s ICBM flight while salvos hold level-29 areas). Sync loads share
  queue (`UNKNOWN` 33): mid-salvo joiner would wait 0.55–0.8 s for place on first tick. `ArrivalTickets` only runs while
  the destination dimension has active non-arrival `AreaLoader` requests. Arrival tickets and queued releases do not
  count as competing work. Releasing or expiring the last own area removes both live and queued arrival tickets;
  idle player events allocate no tracking (`ArrivalScopeGameTests`). Check the respawn destination, not the dimension
  where the player died. While needed, `ArrivalTickets` uses load level
  26, below any mod-area center (Lancet center 27, disk load waits radius-1 neighbors, level 28 would wait entire Lancet
  salvo). Non-ticking, removed once any players' `PLAYER` tickets hold radius-5 square (`DistanceManager.tickets`, AT,
  read-only), ≤ 200 ticks; `arrivalLoadsBefore{Salvo,Loiter}Areas`, `arrivalReleasedWhenPlayersHoldPlace`. Respawn
  (`PlayerList.respawn`) reads bed/anchor synchronously before own events: same ticket on death (`LivingDeathEvent`,
  `LOWEST`) at respawn place, 100-tick lease renewed by dead-player tick (also acquired if work starts after death),
  removed on respawn/logout/live tick/expiry
  (player no longer ticks); slot transfers in `PlayerEvent.Clone` (`respawnTicketWaitsForRespawn`,
  `respawnTicketLeaseExpires`, `respawnEventsMoveTicket`, handlers with NeoForge `FakePlayer`). Sable-craft bed is plot-
  grid point (Sable respawns at own world point): no load tickets there (`SubLevels.inPlotGrid`,
  `respawnOnCraftNoTicket`). While held, bed without player runs `checkDespawn`. Same-dimension NeoForge teleport loads
  synchronously (`Entity.setPosRaw → getChunk`, `UNKNOWN` removed next tick), new player tickets queue; synchronous
  ring (≤ 11 chunks) dropped below loading/revived while area queue generated neighbors. Vanilla unload task
  (`ChunkMap.scheduleUnload`: `isReadyForSaving` false while `getGenerationRefCount() > 0`) reenqueues in `unloadQueue`,
  `processUnloads` spins until tick deadline, exactly 50 ms/tick, no chunk-task/generation time (rig 100 Shaheds,
  camera teleported with salvo, 50 ms/tick up to minute). Player jump (`ArrivalTickets.onLevelTick`: chunk moved ≥ `JUMP` chunks at world-
  tick end, no player tickets holding place) installs arrival ticket before second `purgeStaleTickets` (`teleportRingStaysLoaded`,
  control `teleportRingDropsWithoutArrival`). Portal search loads before mod learns location, uncovered. Same vanilla
  gap: fresh-location player simulation chunks (teleport, blocks tick to simulation radius + 1), so rig teleports
  player into ready simulation-radius + 2 square. Mod load ticket never enables ticking itself, but if it grants
  level ≤ 32 under another ticking count, block entities tick without ready neighbors, vanilla rule like player
  ticket edges; worse than vanilla only if simulation distance exceeds server view distance. Never change block-
  entity ticking rule (Artem: mod does not change game/other mods globally): mixin on
  `LevelChunk$BoundTickingBlockEntity.tick` (tick only with ready `ChunkHolder.getTickingChunk`) would delay all pack
  block entities; Lithium replaces same call. Rig stall ≤ 13 s (trial spawner by target area, server view 8/simulation
  6) also vanilla: player steps beyond pre-ready square, neighbor still generating. Lithium “sleeping” block entities
  use dummy ticker, `getPos()` null; code iterating `Level.blockEntityTickers` checks null. Mixin `@Shadow` only on
  members declared by target class itself (no refMap, game Mojang names); nested outer reference `this$0`, like Lithium.
- Vanilla unload (`ChunkMap.processUnloads`) uses spare tick time; without it, 200 `toDrop` holders/tick; above 2000 in `toDrop`
  it moves excess to unloading, saves/unloads ready excess above 2000 in same tick: hundreds of simultaneously
  released areas (salvo Cancel) cost 6.8 s/tick, rig 5 s/~9 thousand chunks. `AreaLoader.release` immediately removes
  area tickets (ticking stops), holds ready area with same-level non-ticking release ticket; world-tick-end queue
  releases area by area while unloading (`toDrop` + `pendingUnloads`, AT/read-only, excluding holders retained by
  neighbor generation `getGenerationRefCount() > 0`, vanilla skips them) plus area's holder square ≤ 2000 (vanilla
  drains in spare time); beyond that vanilla busy rate 200 holders/tick (large areas borrow following ticks), while
  unloading < 2200 and `toDrop` < 2000; `runDistanceManagerUpdates` after each to see release count. Waiting only for
  room fails: busy server (tick > 50 ms) keeps 2000 unloading, drains excess only, never generation holders, releasing
  one area/100 ticks (~60 salvos leaves 12.5 thousand release tickets/~25 thousand loaded chunks, TPS 13–15). Whole
  unload cap needed too: first spare tick moves all `toDrop` to unloading, saves excess above 2000 even after time
  ends, so `toDrop` cap alone after busy ticks gives ~2000 simultaneous saves. Release ticket no timeout: vanilla
  `purgeStaleTickets` expires all together, none during `/tick freeze`; only queue removes. Still-loading areas and
  all at shutdown (`!isRunning`, `releaseAll`) release immediately: otherwise release ticket completes generation,
  `StopDrain` waits. `releasedAreasUnloadInTurn` (without queue 3645 unloading); no spare time (`/tick sprint`, vanilla
  `hasTimeLeft = () -> false`): `releasedAreasUnloadWithoutFreeTime`, `salvoLeavesNoTicketsOnBusyServer` (room-only queue
  leaves 7/8 areas and 17 salvo areas past deadline).
- All loaded chunks: `chunkMap.getChunks()` (AT).
- `PalettedContainer.maybeHas` exact only for small palettes: global (> 256 states/section, normal detailed city)
  always `true`, small retains former states. If deciding section scan on each chunk load/save, use
  `util/Palettes.contains` (palette then `PalettedContainer.count`; Lithium replaces `count`, reports all palette
  entries including zero-block ones, count only `n > 0`).
- “Can't keep up … N ms behind” means cumulative lag since previous line (at most once/15 game seconds,
  `MinecraftServer.runServer`), not one pause: 12.9 s after 28 s means ~11 ticks/s throughout. Disk chunk parsing
  (`ChunkSerializer.read`)/FULL promotion on server thread between ticks (`ChunkMap.scheduleChunkLoad`,
  `mainThreadExecutor`); dense-city teleport takes seconds without mod too.
- `ChunkEvent.Load` once per in-memory lifetime: view-edge chunk drops below full (`getChunkNow` null), returns without
  unload/new event. Holding queues remove only on `ChunkEvent.Unload`; in-memory check `NuclearTickets.inMemory`:
  `ChunkMap.getVisibleChunkIfPresent` or `pendingUnloads` (AT/read-only) holder + `getChunkIfPresentUnchecked(FULL)`.
  Vanilla immediately removes ticketless holder from visible map, unloads/sends `Unload` when savable; returned ticket
  restores same chunk without `ChunkEvent.Load` (`chunkRevivedFromPendingUnloadStillScarred`). Otherwise untouched
  nuclear stripes (`ScarQueue`), zone square holding planned chunk without work. `ChunkEvent.Unload` only for full
  `LevelChunk`; disk-read `ChunkDataEvent.Load` chunk remaining edge-ring `ImposterProtoChunk` leaves without event:
  clean by `getVisibleChunkIfPresent(c) == null` (`BlackoutWorld`, once/sec).
- Long server `Level.clip` (aim to 1024 blocks, projectile nose, nuclear thermal shadow) only to first unready chunk:
  `Terrain.readyUntil`/`readyAlong`, grid traversal of chunk columns.
- `AreaLoader` applies tickets immediately (`apply` → `DistanceManager.runAllUpdates`, take/release/grow within tick,
  not `runDistanceManagerUpdates`, which copies whole chunk map for new holders, areas taken dozens/tick). Vanilla
  recalculates when called: tick start, sync load, another area's release. Otherwise same-tick versus next-tick area
  ticking depended on others requesting recalculation, hence other strikes.
