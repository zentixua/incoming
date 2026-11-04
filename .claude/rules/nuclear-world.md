---
paths:
  - "mod/src/main/java/ua/zentix/incoming/nuclear/world/**"
  - "mod/src/main/java/ua/zentix/incoming/mixin/net/**"
---

# Nuclear strike ruins (`nuclear/world/`)

## Structure
- `world/` — only snapshots on the detonation tick (entities and loaded-chunk coordinates in range); all work runs
  under the nuclear lane's deadline in the common budget (`work.md`) via `WorkClock` (a unit starts only if estimated
  to finish by the deadline): `PulseJob` (light and prompt radiation on entities, players and nearby first, before the
  front; players also farther out to retinal-burn range, `FlashEyes.reach`), `ScarQueue` (chunks out to
  `Detonation.ruinRadius`, where reflected pressure still breaks glass, ~0.34 psi incident; queues snapshot chunks by
  front-arrival time, one tick early, one chunk per work unit; player-visible chunks first (in their view, including
  not yet sent), others afterward, and chunks requested by a player move to visible; visible chunks and their server
  prechecks get an extra 6 ms beyond the short lane deadline when the server has no spare time
  (`ScarQueue.SEEN_BUSY_NANOS`, `WorkClock.canStartVisible`: with a full tick as the wave crossed a player's city, a
  2 ms lane left visible ruins up to 64 ticks behind the front, netcup 16.10); a chunk needing another unit (neighbor
  fracture for an on-site plan, stale plan) returns to its queue's tail, not head: several-ms window fractures would
  hold up dozens of ready visible chunks (0.2 ms replacement), 38 ticks on netcup 16.10; visible chunks are prechecked
  `ScarQueue.LOOKAHEAD` (20) ticks before the wave: plans that became stale during flight (grass grew, player placed a
  block) and chunks without plans receive a background plan now, ready by the wave (GameTest
  `staleSeenPlanReplannedBeforeWave`); a background plan stale at replacement is not applied — rebuild on the server
  thread from the chunk as it is (`staleBackgroundPlanRebuiltOnServer`); trunks falling into a neighboring chunk wait
  for its ruins; loads no neighbors: finished plans need none (except `RuinPlan.neighbourRadius`: world-path positions
  within 2 blocks of the edge or a nearby Sable craft need a radius-1 ticket; non-vanilla block entities through the
  world (Create belts remove chains up to 20 blocks) need radius 2); background plans use a 5×5 window of memory/disk
  snapshots (`RuinContext.requestWindow`); a neighbor absent as a whole chunk on disk is a solid mass in the window,
  like the world edge (GameTests `readyPlanSameWithoutNeighbours`, `windowWithoutNeighboursOnDisk`); at the view edge,
  chunks would wait up to 200 ticks for neighbors and reach players intact), `RuinPlanner` (chunk ruins as section
  deltas: two physical processes over the original 3×3-chunk world window (`RuinWindow`: for a neighbor whose ruins
  are applied, use old blocks at its plan positions), making a chunk's plan identical in advance and on-site, in any
  chunk order (detonation `RuinContext`: cached chunk fractures and which are applied; the window uses `ChunkShot`
  snapshots — section palettes, heightmaps and sky sources copied on the server thread or read from disk
  (`DiskShots`); a snapshot stays valid while chunk blocks match: section fingerprints, a lamp darkened by blackout
  remains the same lamp; fractures and plans remember their source snapshot IDs). Thus `Blast`, `Collapse` and
  `RuinPlanner.finish` (fires, light, material sources, heightmaps, section deltas) run on background `RuinWorkers`
  threads (priority queue, `RuinWorkers.Priority`: player-visible chunks in the ruin queue, then other queued ruins
  and preparation of in-memory chunks, then distant disk plans and far LODs; a priority's task limit includes higher
  priorities: distant work keeps the pool full while the wave travels; the ruin queue promotes a chunk's queued
  far-priority preparation task to its wave-chunk priority — GameTest `queuedFarTaskPromoted`; `ruin_threads`, 0 means
  all cores minus three: server, own-game rendering, world I/O; active count follows server-tick time excluding mod
  lanes (`WorkScheduler.ownNanos`): above `50 − work_ms_per_tick + 5` ms reduce by a quarter, below `… − 5` add one,
  log it; using the full tick with our 30 ms would keep one thread). They call neither world nor block code; state
  properties (`Blast.Props`: fire, flammability, top strength, heightmap opacity, light too) come from a table the server
  thread fills from snapshot palettes (`Blast.publish`); light shadow uses detonation heightmaps (`RuinContext.lit`).
  GameTest `backgroundSolversCallNoWorld` walks bytecode along the call graph from background-task roots;
  `backgroundPlanMatchesServerPlan` and `diskPlanMatchesMemoryPlan` check equal plans; a background plan running over
  600 ticks, measured from start rather than enqueue, is abandoned and planned on the server thread): `Blast` —
  pressure differences break thin elements: pressure reaches only faces beside sky-connected air (above terrain tops
  and 4 blocks into openings, then beyond breaches through all connected air in chunk columns ± 9; bunkers, sealed
  caves and basements stay untouched); blast-facing surfaces get reflected `max(p, Pr·cos θ)` (walls horizontally,
  Mach stem); thickness 1–3 (row of solid cubes; water at both ends is a mass, at one end not: aquarium glass breaks)
  fails at `P ≥ threshold·k(t)` (k = 1, 2, 3.5), face-breached elements fail through their full thickness; ground
  (terracotta, calcite, stone) only if thin with sky on both sides (ridge, wall, overhang); tall narrow elements (up to
  6 along the blast axis, ground up to 4: cliffs and mesas stand; `H ≥ 4t`, `H` measured while air is behind: a floor
  behind a wall pier ends the lever arm at that story) topple; rounds: second behind first-round breaches (only
  columns at most 6 wide topple there, ground 4: core, corner), third through all neighbors' breaches (`Blast.remnants`);
  trees fall as trunks. `Collapse` drops what lost support: 0-1 traversal from supports (all load-bearing blocks at
  window bottom and no higher than the window's lowest original surface, plus indestructible blocks, strength < 0 or
  ≥ 50, `Props.fixed`: they neither break nor fall; upward 0, sideways/down +1, up to 8); a block holds within its span
  (`Blast.Props.span`; ground in structures — unreinforced masonry — only if support did not lengthen; hanging masonry,
  not on a bearing block, previously held by a row between supports on both sides on at least one axis, now on none,
  becomes a cantilever and falls even if the nearest support is unchanged: slab beside a surviving core whose walls
  fell, otherwise an airborne ring would remain — detonation directly above the core, GameTest `towerWithPoolFalls`;
  its fall lengthens support paths for others — repair distances only where support passed through it,
  `Collapse.reroute`: rescanning the whole window for each cascade would add +40–50 % collapse cost in a ground-block
  city; row ends are only supports with a span: glass lost at 0.8 psi never supported a floor even before the strike;
  masonry cascades farther than two spans from their chunk (rare) are invisible across chunk boundaries); falls if the
  wave removed support (was within span or became at least 3 farther), falling blocks support nobody (cascade in one
  traversal); structures already without visible support before the strike (beyond 8) cascade from broken blocks,
  natural ground arches and overhangs stand; leaves without trunks fall; small water bodies (up to 4096 positions and
  16 wide) with breached bottoms drain; attached blocks (doors, torches, signs) follow their support, liquids beside a
  breach tick; removed structural material transfers to `RuinMatter`; light ignites/burns; all
  randomness comes from coordinate hashes; chunk ruins wait for ready chunks within `RuinPlanner.REACH` = 2),
  `RuinPlan` (delta only: 4 bytes per position, fires; heightmaps, lower sky sources of changed columns and light-check
  positions are calculated in the plan; on replacement, write plan positions and fire directly into sections, install
  planned heightmaps and sky sources (if a chunk's “before” value differs or blocks differ from the plan snapshot —
  `RuinPlan.sameBlocks`: section fingerprints, then positions in differing sections, allowing darkened lamps; the mod
  does not listen for chunk changes — rescan all columns with plan positions in one pass from their highest plan
  position; AT), `checkBlock` at each column's highest changed point and below its surviving top, one light-engine task
  per chunk (AT); section packets to players in the same tick (plus up to 24 removed positions with their blocks as
  client debris, `ScarQueue.thrown`, `RuinPlan.thrown`, to players already holding the chunk); Distant Horizons LODs:
  `compat/DhChunks` (`overwriteChunkDataAsync`); block entities and POIs go through the world after replacement;
  mismatching old-state hash at plan positions means replan; lamp and blackout twin hash identically), `NuclearPrep`
  (prebuilt ruins during ICBM flight: in-memory chunks in ruin radius (plus chunks loaded during flight, every 40 ticks)
  with loaded `REACH` neighbors — snapshots under `WorkScheduler.PREP_SHARE` of the common budget, plan in background;
  zone layout (chunks, outer zone without prebuilt plans, loading squares by distance, `NuclearPrep.layout`: ~33 thousand
  chunks for 15 kt, 50–65 ms) is background too; a detonation preceding completion discards the unfinished layout without waiting (`handOff`); the normal scar and durable-settlement paths cover that strike; preparation
  loads nothing into the world (city plan 13 ms/chunk: the server thread could not plan 32 thousand zone chunks during
  90 s flight); heavy zone outside memory (phase 2) reads disk in wave-arrival order: `ChunkStorage.read` (I/O thread),
  data upgrade and parsing in background (`DiskShots`, only final blocks, `DiskStatus.State.blocksFinal`: whole chunks
  and 1.17-world chunks with below-zero retrogen `below_zero_retrogen`, vanilla writes it only below zero; almost all
  Zearth and Greenfield chunks are like this; heightmaps absent on disk, almost all chunks on editor-made maps, are
  primed from blocks like vanilla loading, `DiskShots.Read.primed` (the ruin-queue window takes at most 4 such chunks at
  once: `Read.cost`); unread reasons appear in “Ruins for strike #N ready”); snapshot to server thread, plan in background
  (`RuinContext.submitDisk`), continuing after detonation (half the pool for the ruin queue); line “far rings: X of Y
  ready for the wave”; memory — plans, snapshots, reads and thread buffers — capped at 512 MB (heavy-zone LOD copies
  for the front, `FrontLods` in `dh.md`, use the remainder after plans: taken once disk plans are ready, heap stop drops
  them); one area, one preparation; a prepared detonation takes preparation's location, ground and visibility
  (`NuclearPrep.planned`: weather changes during flight would leave a trace seam in later-loaded chunks); live heap is
  old generation after full/major GC, a GC that freed it (mixed G1), or any GC leaving it over the limit (`HeapWatch`
  via collector notifications: ZGC's young pool “after GC” includes allocations during the cycle; pool sum shows
  9.3–10.1 GB for 6.4–6.8 live; JVMs without old-generation pools use post-GC pool sum); two consecutive measurements
  above 75 % stop prebuilding plans (“no more ruins are pre-built”), and the zone behind the wave loads only chunks
  with ready plans (loading nothing would waste ready plans; GameTest `heapStopZoneLoadsOnlyPlanned`); after detonation,
  the zone behind the wave loads the ≥ 5 psi heavy zone in 5×5 squares following the front — each square's chunk whose
  5×5 ruin window is whole on disk (`DiskStatus.State.WHOLE`: `chunkScanner` header; 1.17 retrogen chunks cannot load
  without generation, so disk plans wait for players or distant LODs) or in memory, via its own level-33 load-only,
  non-ticking ticket (vanilla loads it without generation, `ChunkGenerationTask.canLoadWithoutGeneration`; windows at
  square edges come from disk; `REACH` margins would mean 81 chunks per 25 ruins, thousands of ticks behind; skipping
  an entire square for one non-whole ring chunk would lose its chunks' disk plans; GameTest `zoneLoadsEveryChunkWithPlan`),
  at most 6 squares at once (a square already fully in memory uses no slot and is held only if its chunks have ready
  plans: the detonation area is released on the detonation tick, otherwise such a chunk leaves memory before the wave
  and loses its plan); the queue builds their ruins in background; release a square when its chunks' ruins are applied
  or the remainder wait only for neighbors — each then takes its own neighbor ticket (`ScarQueue.holdForTile`;
  otherwise a chunk under another ticket takes none of its own, nobody loads its neighbors in an unloaded square,
  and zone slots stall for thousands of ticks; `-Dincoming.nukeDiag=true` logs “DIAG”) (line “zone behind the wave —
  X of Y chunks done”); preparation and ruin queue live only in memory (non-persistent `NuclearWorld`): a server restart
  mid-detonation loses ready plans and ruins are built on-site — chunks without `CHUNK_SCAR` are processed on load;
  log “Ruins for strike #N ready”; detonation summary: ready-plan ruin share for chunks in memory at detonation and
  “sent to players before ruins”: a wave-reached chunk without ruins is withheld from players
  (`mixin/net/PlayerChunkSenderMixin`, `ChunkSendGate`, up to 200 ticks after the wave; no public path, reason in the
  mixin; GameTests `chunkSendGateApplied`, `chunkWithheldUntilRuins`)), `ColumnScar` (world-path block replacement, fire
  counter, shadowed light), `BlockResponse` (thresholds by `nuke_*` tags or strength), `ThermalShadow` (heightmap shadow),
  `CraterJob`, `NuclearWorld` (front on entities and craft, queues); chunk mark is attachment `CHUNK_SCAR`;
- Ground trace (`RuinPlanner.finish` after ruins, on the highest supporting block; in-memory chunks get light earlier
  via the flash layer below): blast strips turf (`STRIP_PSI`); light (`RuinContext.lit` × incidence angle `incidence`:
  blast-facing slope up to ×2.5, penumbra `LOW_SUN`; slope from ±2 columns; smooth fade to zero at
  `RuinContext.ruinRadius`, `EDGE_FADE`) burns plants above ground (`PLANT_Q`, tag `nuke_plants`, flag
  `Blast.Props.PLANT`; entire plant column above, some leave dead bushes), turf to dirt (`BURN_Q`), and inside the burned
  area scorches to podzol (`CHAR_Q`); snow melts; turf `snowy` follows snow above after all changes. Each uses a damage
  curve `model/Fragility` (lognormal: median and spread β; `Fragility.possible` rejects positions unreachable even in
  the extreme tail, without noise); spatial randomness is world noise `model/WorldNoise` (FastNoiseLite 1.1.1, MIT,
  copy in `util/noise` with double input; one layer per consequence; large patches at least 12 blocks) with position
  hash, truncated to ±2.5σ — patches rather than a threshold ring, without isolated cells far beyond the zone. Noise
  depends only on world seed and position (not detonation: preplans use location without detonation seed,
  `NuclearWarhead.geometry`; chunks loaded after preparation ruins are forgotten use the detonation; GameTest
  `scorchSameForAnySeed`): background plan = server-thread plan = disk plan = preplan.
- Trace without ruins (`RuinPlanner.scorch`: same ground pass, `land`, from one chunk snapshot, no fractures or neighbor
  window; ground is the highest supporting block, not a log, no lower than collapse bottom: 16 below the chunk's
  lowest support; water supports, no ground under deep water) serves distant DH LODs (`FarLods.scorch` from
  `NuclearWorld.onDetonation`): a chunk ring out to `RuinPlanner.scorchReach` (light with a blast-facing slope ×2.5 still
  in the plant-curve tail or lowest biome tier; about 7.5 km for 15 kt) on wave arrival, nearest first; a prepared heavy
  zone gets planned ruins, an unprepared one gets trace too. Where the wave broke nothing, trace matches ruins
  (GameTest `scorchWithoutRuinsSameAsRuins`; detonate over the platform's own corner: light from beyond its edge would
  cross neighboring batches' structures, different in each CI shard); glass and trees in LODs stand until the chunk
  loads. Distant light shadow is not calculated: the ring has its own `RuinContext` without heightmaps (rays to ground
  kilometers away pass hundreds of meters above terrain; a later-loaded chunk's plan sees only in-memory chunks).
- Flash scorch on faces (`FlashFaces`, DESIGN-nuke §3.7) is added in plan completion (`RuinPlanner.finish`, background):
  surviving material blocks (`Blast.Props.flash`: `FlashChar` material and block hash from the properties table), open
  faces toward the sphere; a face under a full original-world neighbor (`Props.full`, neighboring chunk via window)
  stays clean; shadow uses `Grid.sees` over window heights. Result: `RuinPlan.flash()`, applied with the flash layer
  (`RuinPlan.applyFlash`) and again with ruins in `RuinPlan.apply` (`FlashSync.chars`, replacing the whole detonation
  layer: an on-site plan after a preplan does not double marks; ruin scorch is the surviving part of layer scorch).
  The layer belongs to the queue's detonation passed into `apply`, not `ctx.d`: prebuilt ruins (`NuclearPrep`) have
  preparation geometry in `ctx.d` without ID/time (−1, time 0); such a layer would appear immediately on the client and
  not combine with the same detonation's shadows; chunks without a ruin plan (`RuinPlan.EMPTY`) get no scorch. Early
  exit by light at the chunk's nearest point makes the pass cost one call for distant chunks.
- Scorched biomes (`ScorchedBiomes`, `data/incoming/worldgen/biome/singed|scorched|charred`: grass, foliage, water color)
  follow average light in a 4×4 biome cell (separate noise layer), from 8 blocks below the cell's lower ground quartile
  to the top; preserve tag `nuke_keeps_biome` (oceans, rivers, caves), dry or cold climates (snow, desert), and own biomes
  of equal or higher severity. Cells are in the plan (`RuinPlan.biomes`, included in `differs`, no fingerprints: biomes
  do not change blocks); written like `/fillbiome` (`fillBiomesFromNoise` per section, `setUnsaved`), to players via
  `resendBiomesForChunks` (biome packet), to DH LODs via section copies (`RuinPlan.applyToCopy`: changed-cell count);
  a biome-only plan is still chunk ruins.
- Flash layer (`FlashScorch`; Artem's decision: everything light does to the world happens with the flash, not the
  wave; the wave only supplies pressure): one job per detonation in the nuclear lane (`NuclearWorld.scorches`, after
  the ruin queue, using what wave-reached ruins leave: prioritizing distant layers delayed front ruins by ticks,
  GameTest `ruinsPreparedDuringFlightFallWithFront`; detonation jobs advance independently; errors log and remove the
  job; separate “flash scorch” time in slow nuclear-tick logs). First unit collects in-memory chunks within
  `FlashScorch.reach` (ground trace, slope ignition ×2.5, face scorch at sphere height) and ruin-queue radius
  (`ScarQueue.inRange`, by ground, like `reach`: only ruins enable layer-fire ticks): player-visible first, by player
  distance (`SEEN` priority and `ScarQueue.SEEN_BUSY_NANOS`), others by epicenter distance; then one unit per chunk
  builds a 3×3 window from detonation ruin snapshots (`RuinContext.flashGrid`) and a background layer
  (`RuinPlanner.flash`: same `land` without pressure — no turf stripping or rubble; all light-ignited fires,
  `min(1, q/20)`, with wave-blown fires marked — position roll not below chance × `BLOWN_KEEP` at ≥ 2 psi —
  `RuinPlan.blown`; biomes; `FlashFaces`); one unit per ready layer writes like ruins (`RuinPlan.applyFlash`: directly
  into sections, section packets, LODs, `FlashSync.chars`), no earlier than light at the chunk's point nearest the
  sphere reaches ignition threshold (`Detonation.flashTicks`, emitted pulse fraction
  `FireballModel.thermalFraction`; pulse is 10·t_max, 1.24 s for 15 kt: the layer is laid during the flash itself).
  No layer for: chunks whose ruins are already applied (wave precedes layer near the epicenter); chunks loaded after
  detonation (all with ruins); chunks near Sable craft without loaded neighbors (before background work and before
  writing, `RuinPlan.waitsNeighbours`: Sable reads neighbors per position, `sable.md`; GameTest
  `flashLayerWaitsNeighboursAtCraft`, batch `nuke_flash_edge`, checks before background work;
  `flashLayerRechecksNeighboursBeforeWrite`, batch `nuke_flash_edge_write`, before writing: neighbors left memory during
  layer build); new chunks while detonation ruin snapshots plus layer plans (`RuinContext.shotBytes`) exceed
  `NuclearPrep.PLANS_CAP` (512 MB) or heap is tight (`HeapWatch.Tracker`, two consecutive measurements). Background
  layers running over `RuinContext.TASK_AGE_LIMIT` ticks are abandoned. If a layer is applied and a ruin plan already
  exists (prebuilt, deferred, background), evict the chunk snapshot from cache. Log “flash scorch — N of M chunks …
  in T ticks (pulse P ticks) …”, including chunks waiting for craft neighbors and skipped for memory.
- Layer fire has no ticks until chunk ruins (`RuinPlan.write` with `layer`): until then it neither grows, spreads nor
  dies, so the pre-flash ruin plan stays valid whenever ruins are applied (ticks scheduled by wave time would stale
  late ruins, requiring server-thread replanning). Ruin replacement schedules remaining fire (30–39 ticks, like
  `FireBlock.onPlace`). Only fire the wave will not blow out enters the detonation's fire count from the layer
  (`RuinPlan.blown`); counted fire extinguished by ruins is returned to the counter, while wave-blown fire retained by
  ruins enters it. A fire-igniting layer marks its chunk (`FLASH_LAYER`, detonation ID, saved with chunk; cleared by
  ruin replacement): ruins built without a layer plan (detonation ruins forgotten after `RUINS_IDLE`, restart) read
  chunk fire as air, in memory and on disk: disk snapshots read the mark from chunk data (`DiskShots.Read.flashLayer`),
  otherwise plans would depend on whether a neighbor is in memory (`RuinPlan.unfire` in `ChunkShot.take`/`fromDisk`,
  `RuinPlan.current`; GameTest `diskPlanMatchesMemoryPlan`: city with roof planks under a layer and one mark); they
  extinguish fire not their own (accuracy as after restart: old neighbor blocks are unknown). Such ruins count fires
  per chunk: remove as much layer fire as the detonation counter took (`ColumnScar.Budget.layer`; after restart the
  new counter took none), add remaining fire (GameTest `ruinsOverFlashWithoutShots`: after idle with layer counter,
  after restart with a new one; total detonation count is fire in ruined chunks plus counted layer fire where ruins
  are absent). If a marked chunk loads but the detonation is neither active nor past (`NuclearEvents`), no ruins will
  arrive: schedule fire ticks on world tick (`FlashScorch.forgotten`, `burnOut`) and clear the mark; if ruins already
  stand, clear immediately (both paths: GameTest `ruinsOverFlashWithoutShots`).
- Ruins over a layer use the pre-flash world, so the plan (background, on-site, prebuilt, disk) matches the layer-free
  plan: a layer-bearing snapshot reverts to the pre-flash world (`RuinPlan.unflash` in `ChunkShot.take`/`fromDisk`:
  layer fire becomes air, layer positions restore old states, palette copies `copyStates` only for changed sections;
  heightmaps and lower sky sources for layer columns are rescanned from the copy, `RuinPlan.rescanCopy`: fire raises
  world surface by one block, but layer-plan values do not know its fire; using them would give `RuinWindow` a `surface`
  one block too high and retain a flash-burning roof the wave should remove; fingerprints and `sameBlocks` compare
  the live chunk, allowing lamps only in modified sections); a snapshot valid before the layer stays valid
  (`ChunkShot.relive`); shadow heightmaps are taken before layer writing (`RuinContext.beforeFlash`); position checks
  (`RuinPlan.current`) read live chunks as pre-flash (layer fire = air, layer position = old state). `RuinPlan.apply`
  writes plan positions over the layer (columns rescanned from chunk); layer fire remains only where ruins have
  their own fire (ruin state, tick), the rest is extinguished: under a plan position with it, elsewhere by air in the
  section (world surface, light, players, Sable, LODs). Empty ruin plans extinguish layer fire too. Thus the post-wave
  world equals ruins alone, including where the wave breaks more than light (a removed roof with flash fire)
  (GameTest `flashScorchesBeforeTheWaveAndRuinsMatch`, batch `nuke_flash_scorch`: background plan through the queue and
  plan ready before flash; `ruinsOverFlashWithoutShots`, batch `nuke_flash_unflash`: field and wooden houses, no
  pre-flash snapshots — server-thread, background and disk plans match, ruins from only a layer mark). Where ruins
  change ground (rubble, collapse), the layer's trace remains underneath: light came first. Debris (`RuinPlan.thrown`)
  excludes positions the layer already changed (flash-burned blocks do not fly) and is selected before replacement
  (`ScarQueue.debris`): the replacement completing the entire 5×5 square around a chunk forgets its layer
  (`RuinContext.applied`).

## Pitfalls
- Nuclear timber members use ordinary pressure fracture; the log tag is not immunity. Rooted connected log
  components with a neighbouring crown use the tree path first, including branches and the full snapshot height.
  Below the whole-tree load, or with tree fall disabled, those logs do not leak into ordinary timber fracture.
  Breakable blocks above the legacy hardness table use the strongest nuclear proxy; conventional response is unchanged.
- `StructuralResponse` is the exact elastic SDOF response to a triangular pulse, including post-pulse motion.
  `Blast` uses physical positive-phase duration, actual collision volume, the existing `penetration` data map's
  represented thickness, and inferred span. Legacy pressure thresholds remain estimated long-pulse capacities;
  `structural_material` supplies density and estimated connection displacement/span. This is not a calibrated
  finite-element model. Reloading tags or block data maps invalidates the snapshot property table.
- `Collapse.detached` compares original contact connectivity with surviving components inside its window.
  Pre-existing detached builds remain untouched. Actual removed support faces seed `RuinSupport`: a resumable
  contact graph across ready chunks. The saved removed-contact journal is shared across all still-pending strikes,
  with the first removed state at each position retained until reconciliation drains (event IDs only attribute
  removals). Per-event reconstruction loses foundations broken by overlapping strikes. It reconstructs the original graph:
  release requires an original path to a protected block/world floor and no surviving path. A broken decoration
  does not give an End island or floating build global gravity; lower neighbours are not support-loss seeds.
  Unknown faces wait behind a separate non-ticking material ticket, never become anchors. Loader neighbour
  notifications invalidate the entire in-progress certificate, including contacts scanned in earlier slices;
  release also rechecks each cell and its external contacts. Traversal, validation and release use the same
  load-bearing face criterion: a colliding leaf is not an anchor or a new structural contact. Never bypass
  notifications when adding supports. Registered-event regressions: `nuke_review_foliage`, `nuke_review_overlap*`.
  Continuation removals AND deposition use `RuinPlan.callbackRadius`: non-vanilla block entities require a radius-2
  ready neighbourhood for `onRemove`/`onPlace`, not just the two-block Sable guard. Source expansion, search, validation and
  release run in bounded batches under the nuclear work clock; no fixed bridge length declares equilibrium.
  Original-contact journals retire when all affected support queues drain, including cold edges; later failures
  start from the reconciled world rather than retaining every completed strike's contact history.
- `RuinMatter` (`incoming_ruin_matter` SavedData) owns material after successful source removal. Capture live
  states and block-entity contents before writing; never resurrect flash-consumed blocks. Handoff IDs prevent
  repeated release of the same records. Original block state, represented solid volume/mass, trajectory/time,
  pending support sources and cold contact faces survive clean save/reload. Per-type and aggregate balances:
  released = deposited + dispersed foliage/glazing + pending. Cosmetic particles own no additional mass.
- Coarse transport integrates gravity and triangular dynamic-pressure drag relative to the air, with adaptive
  half-metre substeps. It never advances beyond elapsed game time. Settlement uses the same block type and saved
  contents; logs rotate horizontally, waterlogging follows destination water. A one-block coarse repose step is
  an estimate, not measured universal friction. Fire/plants do not trap material above an otherwise valid floor.
  Non-colliding non-replaceable decorations (rails, torches, wire) yield the destination with ordinary item drops;
  protected blocks and block entities retain ownership. Collision and placement share the occupancy rule.
  No decorative 12%/four-block cap remains. Outside-height material stays in a saved dormant reservoir without
  acquiring chunks or consuming repeated work. Unloaded destinations remain pending, not discarded.
  These are normal-save invariants, not an atomic transaction across region files and SavedData under power loss.
  Regression batches: `nuke_structure_*`, `nuke_material*`; numeric response: `StructuralResponseTest`.
- `RuinSettle` settles the entire saved ruin zone after its wave, including `NuclearEvents.past` from old saves,
  without players or LOD clients. Scan final-block disk chunks (`DiskStatus.blocksFinal`), skip absent/PARTIAL ones;
  old FINAL/retrogen chunks may need vanilla's asynchronous upgrade generation. Load through `AreaLoader` with
  non-ticking level-33 tickets, reuse `ScarQueue`, and let vanilla save. Layout is built off-thread in circular
  distance order (1/16-block distance keys, as in far LOD rings), then consumed by a durable index. Never scan the
  bounding square row by row: that leaves a north-to-south map seam and delays nearby chunks. `radial` identifies
  the saved cursor format; an older development cursor restarts at zero while retaining pending positions and
  checking saved scars (do not overwrite a pending entry/ticket when the new scan revisits it).
  When less than 64 MiB of admission headroom remains, evict cold, re-readable snapshots and their cached fractures
  under the same clock, at most 64 MiB from this detonation per pass. Snapshot access order is LRU; active worker windows and windows
  awaiting disk reads/worker admission are protected. Editor maps prime only four missing-heightmap snapshots per
  request: dropping each partial batch would livelock the window (`nuke_settle_window`). Preparation gating is
  separate from memory headroom: another launch must not trigger eviction of a healthy cache. Old changed-block deltas and pre-wave shadow heights stay: re-reading ruined disk chunks uses
  these deltas to reconstruct the original world (`nuke_settle_cache`). A growing circular frontier otherwise
  fills the snapshot cache and leaves an idle server at zero admission indefinitely. A held chunk waiting for
  neighbors takes `ScarQueue.holdForTile` within `tileHoldsLeft`, as in preparation: a job born under another scar's
  ticket has `mayHold=false`, so a radius-zero settlement ticket alone would stall until timeout. Regression
  `nuke_settle_neighbours` resumes a completed scan with only such a chunk pending, including a stale Create-block
  plan needing radius 2; it must finish without waiting for timeout. Even an empty plan needs its saved
  `CHUNK_SCAR`; no direct region edits. The loading window starts at 8, grows by a quarter per second with spare lane time and low external MSPT,
  and shrinks by a quarter under pressure. Like workers, external MSPT excludes mod lanes; the threshold is
  50 minus the common configured work budget, with a 5 ms margin. Available plan memory further limits roots,
  reserving two uncompressed chunk snapshots (at least 1 MiB) per root; already held work drains when the limit
  falls. No fixed eight-ticket throughput ceiling. At most 128 reads and 2048 unconfirmed positions; poll all bounded entries so
  completed reads do not wait behind a large queue of idle ones. Admission and polling use the common nuclear clock,
  after player ruins, preparation and fallout. Preparation must be idle; heap pressure and retained ruin data plus
  live-worker buffers at half `PLANS_CAP` limit admission (the remainder is for player windows). An idle context is evicted
  normally, allowing a long pass to resume. Count live pool threads, not configured cores: idle threads expire
  after 30 seconds; a fixed 46-thread estimate alone exceeds 256 MiB and would stop a 48-core server forever. `RuinContext.retainedBytes` counts old applied plans as well as snapshots. Empty plans must call
  `RuinContext.finished` too: otherwise unchanged streets/water leave holes in `stood`, pinning all neighboring
  old plans until the memory guard throttles a large saved-world pass (`nuke_settle_empty`).
  `RuinProgress` saves cursor AND unconfirmed positions: confirm a chunk only after an asynchronous storage read sees
  its scar (reads observe pending vanilla saves). A restart must retry those positions, even when the cursor is ahead.
  Shutdown releases settlement tickets before `StopDrain`; storage reads only return detached headers. No settings.
  Regressions: `nuke_settle_disk`, `nuke_settle_restart`, `nuke_settle_budget`, `nuke_settle_load`, `nuke_settle_many`; `RuinSettleTest` checks circular order,
  cursor restoration/migration and growth/backoff of admission.
- Bodies not in memory when the pulse or the front crossed their place (`LateExposure`): `PulseJob` and `BlastFront` see only
  loaded entities, so on `EntityJoinLevelEvent` a body loaded from disk is queued for what it missed — `NuclearWarhead.expose`
  with `late` (burns and prompt dose, sickness from the detonation via `RadiationTicker.addDose(…, at)`; no flash panic or
  clothes catching fire) and `BlastBody.late` (injury law, no throw). Entity attachment `NUKE_SEEN` (`NukeSeen`: the
  detonation numbers whose pulse and wave the body received, each by its own number — a later pulse may come before an
  earlier queued one; `born`, the last detonation when it spawned; fallout account time). The pulse (non-players) and
  the front check and set it, as does the late queue, so whichever path comes first wins (a body loaded on the tick the
  front crosses it, a quick reload queued twice). The join event only measures distances: reaches of past detonations
  (cold after a restart) are computed in `LateExposure.work`, one per work unit, then waiting bodies are sorted, then
  each body's detonations, one per unit. `MobFallout` integrates each mob's dose since its own `falloutAt` (log-grid integral for gaps
  over 2 passes). Worlds saved before the mark: detonations below `NuclearEvents.lateFrom` stay as they were (hit bodies
  carry no mark). GameTest `LateExposureGameTests`, `LateExposureOrderGameTests`, unit `MobFalloutTest`. Estimate: the late body reads the world as it
  is on load (ruins may have replaced its shelter).
- Burial: ruins bypass `setBlock`, so vanilla only detects a body inside blocks via suffocation. Burial uses a
  pre-replacement snapshot of living entities not inside blocks (`BlastBody.free`) and their bounds in blocks afterward
  (`ScarQueue.buried`); rubble injury queues through the front (`BlastFront.bury`) under the budget, like wave hits.
  Bounds at a chunk edge enter a neighbor, so check burial only with ready surrounding chunks (`Terrain.readyAround`):
  `getBlockCollisions` on a loading chunk waits on the server thread.
- Chunk ruins apply one tick before the front (`ScarQueue.EARLY`, player-visible first); body hits happen when the
  front arrives and the impulse has passed: post-ruin glass is already gone (`Blast` breaks it from 0.8 psi), so there
  would be no shards. Before `RuinPlan.apply`, bodies near the chunk (+5 blocks) remember the window ahead and wall
  behind (`BlastFront.remember` → `BlastBody.Before`). Body displacement from that position is measured at front
  arrival (`Hit` snapshots in `advance`), not hit time: the hit waits for impulse and budget, during which a
  flash-startled mob moves away. Walls along a thrown body's path use the current world (wave-demolished blocks fall
  before the body reaches them).
- Nuclear ruins write plan positions directly into sections (`RuinPlan`), bypassing `setBlock`: no `onRemove`, POI,
  Sable or block events. Sable keeps terrain colliders (section voxels in Rapier) only near craft and updates them via
  its `LevelChunk.setBlockState` mixin: near craft (`compat/SableTerrain`), after writing `RuinPlan` calls
  `SableCommonEvents.handleBlockChange` for each plan position itself (reads 6 neighbors; loaded because the plan waits
  for them). Thus anything with a block entity or POI changes through the world (`ColumnScar.replace`) after writing.
  `ColumnScar.replace` first registers POIs absent from world POI data (map cities inserted bypassing `setBlock`:
  WorldEdit, schematics); otherwise vanilla logs PoiSection “never registered” for every removed block (GameTest
  `poiInRuinsStayConsistent`). Write sections in place with `setBlockState`, as `setBlock` itself does (the light
  thread reads chunks then too); do not read into live sections (`PalettedContainer.read` reuses its array) or edit
  `PalettedContainer.copy()` (the copied palette's resize handler points to the old container: `RuinPlan.copyOf`).
  Ruin plans do not store completed sections: blackout darkens lamps directly in the palette
  (`ChunkLights.applyInPlace`) during the cascade, and a pre-cascade section copy would relight them (while whole-section
  fingerprints would become “stale” in 882 of 10881 zone chunks), so only plan positions are written.
- Slow nuclear ticks (> 50 ms) log a breakdown (ruin queue includes visits and waiting jobs per tick, prebuilt ruins
  separately; checking a waiting job is not a lane-clock unit but continues until tick deadline and its time counts
  toward the lane: `WorkClock.overdue`, `spent`); `INCOMING_JFR=1 tools/client_scenario.sh nuke` writes JFR to
  `mod/run/scenario/logs/scenario.jfr`, GC pauses to `logs/gc.log`; for the rig server,
  `INCOMING_JFR=1 tools/stress.sh` → `mod/run/stress/server/stress.jfr`.
- `ChunkMap.getPlayers(pos, false)` (`isChunkTracked`) excludes players whose chunk is still queued for sending;
  `ChunkSendGate` holds unruined chunks there, so the ruin queue checks “player sees chunk” with
  `getChunkTrackingView().contains` (`ScarQueue.watched`). Otherwise chunks awaited after teleport queue behind unseen
  ones and reach players before ruins.

- Preparation admission sums retained preparation/context estimates across all server dimensions, with worker
  buffers counted once (`NuclearPrep.memory`). Disk reads check the shared headroom before each reservation;
  near snapshots leave estimated room for their 5x5 window. Front copies include other preparations' copies while
  excluding their own bytes from the room given back to FrontLods. Settlement shares retained-memory estimates
  and pauses new admission while any dimension prepares; it still drains existing work. These are estimates,
  not a proven total heap bound: unfinished solver-result sizes, all graph/layout metadata, ordinary world chunks
  and adapter/network ownership still need full accounting before increasing yields.
- Worker lifetime (`RuinWorkers`): configuration changes resize the same executor, never drain an old pool beside
  a new one. Cancellation removes queued tasks and clears their captured callable immediately; a running task
  remains counted until its actual run exits, not Future.isDone. Promotion terminates the removed future. Queued
  shutdown futures are cancelled, and executors retained by I/O callbacks keep their original accounting lifetime.
  RuinInbox invalidates cancelled asynchronous results while keeping outstanding work charged until completion;
  it retains no world/preparation owner. Regressions: RuinWorkersTest, RuinInboxTest and nuke_lifecycle_* GameTests
  (cancel/resize/shutdown, nonblocking layout handoff, cross-strike/dimension admission).

- Preparation ownership after cancellation: `RuinInbox` read reservations remain in the owning `NuclearPrep` counter
  until actual completion even after the preparation is detached; never subtract them merely because its map entry
  was removed. `RetainedPlans` follows put/remove/clear ownership so `ScarQueue` consumption releases retained bytes
  instead of preserving historical totals. `FarLods` tags reads, snapshots and completions with a generation; clear
  invalidates publication but keeps running reads charged until harvest. New requests for their coordinates wait
  for that completion. Far scorch rings use `RadialChunks` row heads, prepared incrementally (global per-tick slice),
  not an area-sized array/sort. This does not replace `NuclearPrep`'s heavy-zone layout or certify all 1 Mt memory paths.
- Structural material retains source face scorch through `RuinScorch` (one chunk index per removal batch).
  `RuinMatter` saves the six tiers with each pending piece; log-axis rotation rotates the faces, without
  recomputing illumination at the destination. `FlashSync.movedChars` merges landed faces once per chunk/tick.
- Nuclear crater ownership is `CraterMatter`: source solid volume/mass by block state is partitioned into
  actual rim deposits, exported ejecta and thermal transformation. The rim consumes excavated ground stock;
  it cannot create material when the bowl is protected or empty. Original soil, column heights and completed
  columns survive clean restart, so a resumed chunk cannot deepen its already-dug columns. Conventional
  `CraterJob.digColumn` callers retain their existing path. Region-file/SavedData power-loss atomicity is not claimed.

- Cancelled preparations retain disk/front-copy reservations in their NuclearPrep lifetime counter until completion,
  even after the preparation is removed. Ready results release at collection or invalidation. `RetainedPlans`
  follows put/remove/clear so consumed preplans no longer contribute historical bytes. These are admission estimates,
  not a complete nuclear heap bound. FarLods tags copies, snapshots and headers by cancellation generation; late
  results release their slots but cannot publish. Regressions: `nuke_lifecycle_detached`, `nuke_lifecycle_far`.
