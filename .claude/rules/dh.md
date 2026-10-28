---
paths:
  - "mod/src/main/java/ua/zentix/incoming/compat/Dh*.java"
  - "mod/src/main/java/ua/zentix/incoming/client/map/**"
  - "mod/src/main/java/ua/zentix/incoming/client/render/DhDepth*.java"
  - "mod/src/main/java/ua/zentix/incoming/nuclear/world/FarLods.java"
  - "mod/src/main/java/ua/zentix/incoming/nuclear/world/FarZone.java"
  - "mod/src/main/java/ua/zentix/incoming/grid/**"
---

# Distant Horizons and LOD Server Support

## Pitfalls
- Distant terrain for the map, rays and sound (`client/map/TerrainTiles`) uses one distant layer: the first working
  source among LOD Server Support (`LssTerrain`, while `LSSApi.isServerEnabled()`), Voxy's database (`VoxyTerrain`, while
  Voxy holds a world; `voxy.md`) and DH (`DistantHorizonsTerrain`); otherwise only client chunks (`TerrainTiles.pickFar`,
  order in `TerrainTiles.stack`, test `farSourceOrderForEachPack`). LSS has no “serve to single-player” setting (only
  “Open to LAN” and a test property), so single-player distant terrain comes from Voxy's database. LSS (MIT, soft
  dependency `lss`, compileOnly `lss_version`) uses its public client API `LSSApi.registerColumnConsumer`: subscribe in
  `FMLClientSetupEvent` (its API touches its network classes); with a subscriber, the LSS client downloads even without
  Voxy. Columns arrive on its `LSS-ColumnProcessor` thread: column tops are calculated there too
  (`LssColumns.summarize`, like the client-chunk layer: `MOTION_BLOCKING`, map color, water) and stored in memory;
  let no exception escape — LSS would consider the chunk unaccepted and resend to everyone (including Voxy).
  The LSS client stores receipt marks (`.lss/cache`) and on reentry requests only changed data, so columns also live
  on disk (`incoming/lss-terrain/<server>/world-<seed hash>/<dimension>.bin`, every 2 minutes and on world exit); if the
  file is absent but LSS marks remain, distant terrain is empty until `/lss clearcache`. Detailed chunks (1 KB) are
  limited by `LssTerrain.FULL_LIMIT`; distant ones become coarse, centers of 4×4 cells (level-2 tile columns). LSS
  columns are read directly from memory (`TerrainSource.peek`): `TerrainTiles.height`, `waterDepth`, `wet` (`FarTerrain`
  rays, sound, the nuke) know everything the server sent, not just built tiles. LSS does not serve LODs to a
  single-player integrated server (except a LAN-open world via its `IntegratedServerLanHook`, and property
  `lss.test.integratedServer`, used by `tools/client_scenario.sh target-map lss`). Files are keyed by address and world
  seed hash (`BiomeManager.biomeZoomSeed`, AT), like LSS's own marks: LSS resends a different world at the same address,
  and old terrain must not remain. Total chunks are limited by `LssTerrain.TOTAL_LIMIT` (distant ones are evicted;
  LSS will not resend them itself); a JVM shutdown hook waits for writes on game exit.
- Client server-cache key: `client/ServerCacheKey`: `ServerAddress.parseString`, lowercase host (`Locale.ROOT`),
  default port 25565 omitted, others `_port`; no DNS/SRV resolution. IPv6 hosts are bracketed and reversibly encoded
  by `URLEncoder`, port outside brackets: replacing ':' with '_' merged a host with another server's port.
  `LssTerrain` chooses the folder on the file thread: new folder takes priority, otherwise legacy. Primary legacy-name
  candidates come from the parsed address; directory lookup ignores case. The last candidate is always the old
  `UNSAFE` formula over the address as typed: before parsing, IDNs were underscores on disk, not punycode. Try without
  port first, then explicit 25565 (IPv6 bracketed, also unbracketed for default port); exact names precede case variants,
  which sort alphabetically. Keep writing to the legacy folder too: saving one dimension in a new folder would hide
  the old folder's other dimensions on reentry. Directory-listing errors abort reading/writing rather than starting
  an empty cache. Do not move or delete user files; log the chosen legacy folder and read file.
- Distant Horizons terrain for the map comes only through its API (`DhApi.Delayed.terrainRepo`), which reads the DH
  database in its I/O pool, lower priority than LOD loading: while DH loads surrounding LODs (world entry, distant
  travel), reads queue (cloud: 30+ s; host's first tile 0.3–1 s), then a tile takes tens of ms. Any column reads a
  full-detail 64×64-block DH section (`getColumnDataAtBlockPos` always uses block level;
  `getAllTerrainDataAtDetailLevelAndPos` in 3.3.3 expands to the same block columns — the API gives no coarse data),
  so a level L tile costs (2^L)² sections: read terrain from DH once, build larger tiles from smaller ones; first-row
  shading uses the northern tile's last row if in memory, otherwise the slope inside this tile (do not read a neighbor
  section just for it); detailed tiles around the player preload while the map is closed (`TerrainTiles.tick`), ready
  ones update on DH events, not a timer. The map does not wait: client chunks overlay it, only background threads
  read DH, never the game thread (hangs the client); two while the map is hidden, DH's thread count while visible
  (`threadCount`: all its pools, including section reads, wait for a free thread in one queue; with two reads at once,
  a 400-tile view takes minutes), and no preloading around the player then; memory holds the map's two widest views
  plus a reserve around the player (`TerrainTiles.capacity`); map text says DH is busy. Source summary:
  `TerrainTiles.stats()` (`target-map` scenario logs it). API readiness and loaded DH worlds come from events
  (`DhApiAfterDhInitEvent`, `DhApiLevelLoadEvent`/`…Unload`); the mod targets API 7 (`DhApi.getApiMajorVersion() == 7`),
  other major versions disable the DH layer; `mods.toml` has optional dependency `distanthorizons` (`dh_version_range`).
- The same path (`DhUpdates`) also sends changes to players' Voxy LODs — `voxy.md`.
- Distant Horizons builds LODs for chunks outside the world itself (its generator reads region files with lit lamps)
  and updates on chunk load. Thus the mod supplies DH with extinguished or relit districts and nuclear ruins behind
  the wave for chunks outside the world within `FarLods.FAR_CHUNKS` of a player: a disk-chunk copy with grid-correct
  lamps and ruins from the finished plan (`nuclear/world/FarLods` → `compat/DhUpdates.offer`; world untouched, a chunk
  loaded later gets the same through its own path). Ground traces beyond the heavy zone (burned turf, plants, scorch,
  biomes) work the same: the detonation ring (`FarLods.scorch`) feeds chunks as the wave arrives, near ones first, in
  its own queue after ruins (at most `SCORCH_QUEUE` requests, `FEED_PER_TICK` ring chunks per tick); one read for copy
  and snapshot (`DiskShots.readScorch`), snapshot on the server thread (`SHOTS_PER_TICK` and `SHOT_NANOS` per tick while
  the ruin pool accepts work; a throwing snapshot means no copy, not a crashed tick), trace plan and copy in the
  background, last priority of the ruin pool (`RuinWorkers.Priority.FAR`: ruins in chunks near players go first).
  Reads are shared with ruins and lighting (8 per tick): traces use at most half the reads in flight (including
  snapshot, plan and copy), and when lighting is queued, at most 6 new reads per tick; a 15 kt ring near a city player
  is about 200 thousand chunks, ~20 min, with up to 2.5 km (burned turf) in the first ~8 min. A lighting request in the
  ring after the wave and a heavy-zone chunk that did not wait for a plan include the trace: otherwise their copies
  would erase it. Copies come from disk: a prior detonation's trace absent from disk is not repeated. A heavy-zone
  chunk without final blocks on disk (absent, partially generated with or without blocks; header read in background,
  `DiskStatus.State.PARTIAL`; also a 1.17 chunk needing further generation that did not wait for a plan) is brought
  into the world by `nuclear/world/FarZone` when players have DH: 5×5 squares with a load-only, non-ticking ticket
  (≤ 2 generating, ≤ 4 total, generation timeout 2400 ticks); ruins apply through the loading path and go to DH as
  a mark; setting `nuclear.far_zone`. Tick work does not depend on zone size: `FarLods.VISITS` requests and
  `DhUpdates.SCAN` marks per tick. Ruin requests have a separate queue ahead of lighting (lighting gets the remainder,
  at least a quarter of visits); requests for the same chunk add their kind rather than being lost: blackout enqueues
  its entire radius at once (159 thousand requests in 5 s at 8 reads per tick), and in one queue the ruins it overtook
  would wait behind it for up to 16 min. Loaded chunks changed by the mod (craters, ruins, lamps) go to DH through
  `DhUpdates.mark`, at most once per 2 s per chunk, no more than 20 per tick: an overflowing DH queue drops distant
  ones. DH calculates LOD light from block luminance (`DhLightingEngine`); block substitution in
  `DhApiChunkProcessingEvent` does not change it.
- Heavy-zone ruins (5 psi) in LODs arrive with the front, not tens of seconds later (`nuclear/world/FrontLods`, one per
  preparation): while the ICBM flies, disk plans (`NuclearPrep` phase 2) build chunk copies with ruins (`FarLods.build`)
  and lit-lamp locations in the background, after all plans, within the same 512 MB, nearest the epicenter first
  (no room: farthest evicted first), only chunks outside the world near players (`FarLods.FAR_CHUNKS`). After detonation,
  copies go to DH `DhUpdates.LEAD` (27) ticks before front arrival at the center of their DH 4×4-chunk section, with
  the entire section (`FrontSchedule`: DH writes a 64×64 area to its database one second after its last write; one
  later chunk would delay the whole area); lamps darken by grid state at front arrival (`ChunkLights.darken`). LEAD is
  DH 3.3.3's minimum API-call-to-LOD-write delay (`compat/DhQueue`: 250 ms queued, 1 s before section write, 100 ms writer
  pause); beyond that are DH queue processing, up to a second waiting for the writer, and coarse levels (up to 250 ms
  per level). Sending uses `DhUpdates.sendHeld`, bypassing `PER_TICK`: rate is bounded by a window, half DH's smallest
  queue (`DhQueue.window`: 500 per DH thread); a copy leaves the window once DH writes the chunk
  (`DhApiChunkModifiedEvent`) or after 5 s; sections without window room wait. For 2 kt, the front reaches the zone edge
  in 24 ticks (less than LEAD), so the whole zone is sent on the detonation tick. Loaded chunks lose their copy (the
  world supplies their ruins and LODs); chunks without copies (no room, front before copy, missile and bomb,
  `nuke now`, outside 5 psi) use `FarLods`. `FrontLods.claim` handles `FarLods` requests for a chunk with a copy: ruins
  belong to it; district light belongs to it while held (lamps set for front arrival), and after sending if it had no
  lamps or darkness at the front is unchanged; otherwise before the front, rerequest a copy for ruins and light;
  afterward use the lighting path. `sendHeld` skips chunks sent to DH less than `RESEND` ticks ago or pending send
  (`offer`): DH rejects a chunk already in its queue (`applyChunkUpdate`), losing the copy. Log lines:
  “heavy zone LODs held for the wave front” on the detonation tick and final “at the wave front” (send time minus front
  arrival, how many DH wrote and in how many ms); GameTest `heavyZoneLodsGoWithTheFront`.
- Distant Horizons 3.3.3 creates a generator for every server world in static map `WorldGeneratorInjector` and never
  removes it: all worlds opened in a game session would retain chunks in memory (`leak` scenario: menu
  1.3 → 2.1 → 2.4 GB, same without e4all). After server stop, `compat/DhChunks.onServerStopped` clears the map (method
  from the DH API jar, no mixin); DH still holds one previous world in `ThreadWorldGenParams` until generation in the
  next world.
- LOD depth for the effects layer (`client/render/DhDepth`, a `LodDepth` source, alongside Voxy — `voxy.md`;
  `SceneDepth`): DH 3.3 without a shaderpack, including with Iris installed but no pack, draws LODs itself with reverse
  depth (`getDepthDirection` = `REVERSE_Z` while no shaderpack is enabled; matrix `setClipPlanes(far, near, true)` is
  NDC 0..1, with default OpenGL range the texture contains 0.5..1, clear 0); no `IDhApiFramebuffer` override (Iris removes
  it each frame without a shaderpack): read the DH matrix as is; `irisPlanes` only for forward depth from the pack's
  program (`DhDepthTest`). Depth readout: “Distant Horizons depth for the effects layer” in the log (on change) and in
  `SCENARIO … depth:` (with frame share covered by LODs beyond render distance, showing whether DH built anything;
  it builds little in the cloud). NaN, infinite or behind-eye distance means sky (`fx_depth`, `pallScene`): near the
  far plane and with a foreign projection, LODs end up behind the eye or infinitely far away.

- A loaded-chunk mark in `DhUpdates` must survive network backpressure and unloading. Use `Terrain.inMemory` for
  lowered/pending-unload full chunks; otherwise keep the mark while at most four `FarLods.savedCopy` reads restore
  saved sections through `DiskShots`. Pending saves are visible to chunk storage reads. Missing/non-final saved data
  can be discarded; I/O failure retries without blocking. GameTest `voxy_saved_update` keeps an old copy at 48 MiB,
  delays the fresh mark, then verifies disk restoration replaces the waiting copy without loading world chunks.
