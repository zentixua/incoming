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
  near a node, the nuke, `GridCommand`; events), `SubstationBlock`/`Substations` (node block, “live”), `ChunkSaves` (no
  twins on disk: `ChunkDataEvent.Save` writes sections with lamps and `isLightOn=false`, `ChunkDataEvent.Load` turns
  off dark-district lamps in the palette before the chunk enters the world; `BlackoutWorld` removes their disk light
  via `checkBlock` when neighbors load). A twin replaces `lit=false`: shaders (Complementary) light lamps by block ID,
  DH by block luminance; twins have no item, so any twin in the world is an extinguished lamp (moved by a piston or craft
  to a lit district, it relights itself, `Unlit.placed`; Sable plots are scanned every 5 s by `BlackoutWorld.relightPlots`:
  assembly writes blocks straight into sections, blocks do not tick in a plot, Sable saves the plot itself; a twin in
  a plot relights on a neighbor update during assembly, `Unlit.neighborChanged`: autosave precedes the end of the server
  tick, and Sable has no plot-save event). Lamps change directly in the section palette (`ChunkLights.applyInPlace`,
  light via `checkBlock`, clients via `blockChanged`): the lamp/twin pair is one block to the world except for light
  (`GridLights.inPlace`, GameTest), whereas `setBlock` with the host's pack costs ≈10 µs per lamp — Greenfield has
  2.5 million invisible `minecraft:light` blocks (up to 7400 per chunk), so the queue would take minutes. Only Sable plots
  use `setBlock`. A chunk at the edge of the loaded world (neighbors unloaded) is converted immediately too: waiting
  for neighbors is not allowed — they never arrive on a dedicated server, leaving the chunk dark; signal-driven lamps
  lit there are checked against the signal when a neighbor loads (`BlackoutWorld.neighbourLoaded`), immediately in
  other chunks.

## Pitfalls
- Lamp-twin models and states come from the vanilla jar via `tools/gen_grid_assets.py` (do not edit by hand);
  substation textures: `tools/gen_textures.py`. Vanilla assigns the cutout layer to lanterns and end rods in code
  (`ItemBlockRenderTypes`), not the model — twins need it in the model (`render_type`). `ofFullCopy` does not transfer
  the loot table by block name — twins need `lootFrom(lamp)`.
- Chunk light from disk: `ChunkSerializer.read` queues saved light arrays before `ChunkDataEvent.Load`, and
  `lightChunk(…, isLighted=false)` only adds source light — old light from a lamp removed in the palette remains.
  Remove it with `LightEngine.checkBlock` at the lamp position (the level decrease also propagates to adjacent chunks).
- Substation and district sounds follow the source law on the client (`ClientHooks.substation` from
  `SubstationBlock.animateTick`, `GridEffects`, `client/sound/Sources`); smoke from a knocked-out substation is
  transformer oil, `FireSmoke.substation`.
