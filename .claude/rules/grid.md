---
paths:
  - "mod/src/main/java/ua/zentix/incoming/grid/**"
  - "tools/gen_grid_assets.py"
  - "mod/src/test/java/ua/zentix/incoming/grid/**"
---

# Blackout (`grid/`)

## Structure
- `grid/` — blackout: `PowerGrid` (SavedData: grid nodes, `Outage`s, cascade progress), `Districts` (districts are
  Voronoi cells on a 5×5-chunk grid, all derived from coordinates), `Outage` (cascade and light restoration as functions
  of district and time), `GridLights`/`ChunkLights` (lamp ↔ unlit twin `grid/block/Unlit*`, all state properties),
  `BlackoutWorld` (non-persistent world attachment: cascades under the blackout share of the common budget
  (`WorkScheduler.GRID_SHARE`); does not load chunks outside the world, converts them on load; a work unit is
  `UNIT_WORK` of work across several chunks (lamp in palette = 1, section scan = `SCAN_COST`); the scan stops at a lamp
  beyond the limit and resumes there (`ChunkLights.Pass`: the palette also remembers converted states, so it cannot
  indicate chunk completion). Chunks near players (`NEAR_CHUNKS`) have a separate priority queue (chunks in the common
  queue that a player approaches move there once per second too); clock: `Blackouts.newClock`, estimate decays each
  tick, otherwise one spike keeps the queue at one unit per tick; rate: `/incoming grid status`; a unit over 10 ms logs
  “Blackout (…): work unit (queue|relight|cascade)” with a breakdown by work kind and “outside kinds” time (GC, thread
  preemption are not blackout work; over half the unit = INFO, otherwise WARN)), `Blackouts` (entry points: explosion
  near a node, the nuke, `GridCommand`; events), `SubstationBlock`/`Substations` (node block, “live”; its `powered`
  is its redstone output, weak 15 from every face: `Substations.sync` writes it without neighbour updates and tells
  the neighbours through `util/SignalEdge` (`server-chunks.md`), and has them recheck every node once after a start
  (`BlackoutWorld.signalled`); its default state is dead and `onPlace` sets the grid's state
  (`Substations.works`), so nothing reads a false 15 during a placement or replacement; `RedstoneGameTests`, `RedstoneReviewGameTests`), `ChunkSaves` (no
  twins on disk: `ChunkDataEvent.Save` writes sections with lamps and `isLightOn=false`, `ChunkDataEvent.Load` turns
  off dark-district lamps in the palette before the chunk enters the world; `BlackoutWorld` removes their disk light
  via `checkBlock` when neighbors load). A twin replaces `lit=false`: shaders (Complementary) light lamps by block ID,
  DH by block luminance; twins have no item, so any twin in the world is an extinguished lamp (moved by a piston or craft
  to a lit district, it relights itself, `Unlit.placed`; Sable plots are scanned every 5 s by `BlackoutWorld.relightPlots`, because
  assembly writes blocks straight into sections, blocks do not tick in a plot, and Sable saves the plot itself; a twin in
  a plot relights on a neighbor update during assembly, `Unlit.neighborChanged`: autosave precedes the end of the server
  tick, and Sable has no plot-save event). Twins never tick (no random ticks; `randomTickSpeed 0` is common): every relight comes from
  an event or a scan (`plotTwinRelightsWithoutRandomTicks`). Remaining case: a twin written straight into a section of an
  ordinary powered chunk, without `onPlace` or a neighbour update (Sable disassembly within 5 s of assembly, a mod or
  structure placing raw sections), stays dark until that chunk saves and reloads (`ChunkSaves` writes it back as a lamp). Lamps change directly in the section palette (`ChunkLights.applyInPlace`,
  light via `checkBlock`, clients through `ChunkDelivery.lamps`: a chunk with many switched
  lamps, or more to switch in a later pass, goes whole after its light, since each lamp delta
  makes the client spread its light removal itself, `work.md`): the lamp/twin pair is one block to the world except for light
  (`GridLights.inPlace`, GameTest), whereas `setBlock` with the host's pack costs ≈10 µs per lamp — Greenfield has
  2.5 million invisible `minecraft:light` blocks (up to 7400 per chunk), so the queue would take minutes. Only Sable plots
  use `setBlock`. A chunk at the edge of the loaded world (neighbors unloaded) is converted immediately too: waiting
  for neighbors is not allowed — they never arrive on a dedicated server, leaving the chunk dark; signal-driven lamps
  lit there are checked against the signal when a neighbor loads (`BlackoutWorld.neighbourLoaded`), immediately in
  other chunks.

- Nuclear outages repair by damage (`Outage.damageLightAt`, `dead`/`damaged` = ground ranges of `Blackouts.DEAD_PSI` 5 and
  `DAMAGED_PSI` 1): never inside `dead`, days in the belt, hours outside it, on the world clock (`WorldClock`, 1000 ticks per hour of the day; `Blackouts.now`; cascade speed divides by
  `Blackouts.rate` so a vanilla day keeps its pace and a 24-hour day stretches it to real hours; a stopped day keeps
  the pace it last ran at); the
  restoration cascade starts at `Outage.restoreFrom` and lives as long as repairs (a restarted one skips districts issued
  before the restart, `Sweep.issued`). Substations the strike hit repair as their district does; a destroyed one reroutes
  its area at `Outage.rerouteAt` but stays unpowered while its zone is destroyed (`PowerGrid.destroyed`).
- Nuclear outage radius is the whole damage reach (`Detonation.radiusMax`), capped by `Blackouts.NUKE_MAX_RADIUS` 16384
  (measured cost in its javadoc), not `grid.max_radius` (substations, commands). Cascade build: `Sweep.buildRow` is
  one unit of at most `Sweep.SEGMENT` chunks of a row's circle chord, districts per row via `Districts.row` (same result as
  `Districts.of`, `DistrictsTest`), chunks stored as int offsets; chunks loaded around players when the outage begins are
  issued at once (`Sweep.early`). Due chunks are issued under the budget too (`Sweep.issuing`, `ISSUE_UNIT` a unit; a
  nuclear cascade reaches ~20 000 chunks a tick, and once built every chunk already due arrives at once); saved progress
  is the last fully issued due time (`Sweep.issuedThrough`). A built cascade waiting for its next due time is not pending
  work (`Sweep.due`, `SweepsDueTest`): a weeks-long repair cascade would otherwise keep the grid share from impacts and ruins.
  A command restore that shrinks a running spread reissues the whole uniform restoration (new times may fall before the
  old progress).
- Lamps placed in a dark district (`Blackouts.onBlockPlaced`) run on their own supply: `PowerGrid` keeps
  position → time placed (`placeOwn`, saved with the grid, not the chunk, so disk loads and LOD copies see it), and every
  darkening path skips them via `PowerGrid.keepsLit` (lamps placed after the district's latest darkening). Forgotten
  once no outage covers the chunk (`prune`).

## Pitfalls
- Outage times (`darkAt`, `lightAt`, `restoreAt`) are on the grid clock (`Blackouts.now` = `WorldClock.ticks`), never game
  time: pass `Blackouts.now(level)` to `PowerGrid.dark`/`darkSince`, in tests too. With the day stopped (GameTest
  servers) the clock advances at the last running pace (vanilla: 1 tick a tick on a fresh server) and jumps with the
  time of day, so it parts from game time by whatever ran before; `darkChunkRoundTripsThroughDisk` passed `getGameTime()` and failed in one CI shard
  order ("the block is already bright"), passed in others.
- Lamp-twin models and states come from the vanilla jar via `tools/gen_grid_assets.py` (do not edit by hand);
  substation textures: `tools/gen_textures.py`. Vanilla assigns the cutout layer to lanterns and end rods in code
  (`ItemBlockRenderTypes`), not the model — twins need it in the model (`render_type`). `ofFullCopy` does not transfer
  the loot table by block name — twins need `lootFrom(lamp)`.
- `ChunkSaves.onSave` restores lamps by renaming twin entries of each saved section's palette list; the packed block
  data stays as vanilla wrote it. Decoding and re-encoding sections on save costs more than vanilla's whole chunk
  serialization when a wave saves a dark city (276 ms per second of server thread, 1 Mt over the Silo map). The rename
  is the lossless fast path only: it reads the saved tag (a snapshot: the live chunk may have relit since), changes
  only an entry's `Name` (other keys belong to other writers), and declines without touching the tag when the lamp
  state is already listed, a twin entry does not decode or omits a property, or the packed data does not read as
  vanilla's (length, an index beyond the list). A declined section goes the full way: rebuilt only if its tag equals
  vanilla's encoding of the live section, otherwise left with its twins. Each section is handled on its own; the
  light flag drops for every converted one. GameTests `savedTwinsRestoreByPaletteRename`, `grid_save_foreign`,
  `grid_save_collision`; `grid_save_cost` logs the cost beside vanilla's serialization.
- Chunk light from disk: `ChunkSerializer.read` queues saved light arrays before `ChunkDataEvent.Load`, and
  `lightChunk(…, isLighted=false)` only adds source light — old light from a lamp removed in the palette remains.
  Remove it with `LightEngine.checkBlock` at the lamp position (the level decrease also propagates to adjacent chunks).
- Substation and district sounds follow the source law on the client (`ClientHooks.substation` from
  `SubstationBlock.animateTick`, `GridEffects`, `client/sound/Sources`); smoke from a knocked-out substation is
  transformer oil, `FireSmoke.substation`.
