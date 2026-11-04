---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/render/LodDepth.java"
  - "mod/src/main/java/ua/zentix/incoming/client/render/VoxyDepth.java"
  - "mod/src/main/java/ua/zentix/incoming/compat/Voxy*.java"
  - "mod/src/main/java/ua/zentix/incoming/client/map/Voxy*.java"
  - "mod/src/test/java/me/cortex/voxy/**"
---

# Voxy (via Roxy)

## Pitfalls
- On NeoForge 1.21.1, Voxy is available only through Roxy (MIT): the player installs the official Voxy 0.2.16 jar for
  1.21.11, Roxy translates its bytecode on load; it is mod `voxy` in `ModList`, and `me.cortex.voxy.*` classes use the
  same loader as ours. Voxy has no API (ARR license: do not copy its code, only call it): everything uses reflection,
  read-only, no mixins; missing classes or fields produce one log line and disable the feature; the game runs as if
  Voxy were absent. The 1.21.1 version is frozen at 0.2.16; study that version's structure (Voxy sources on GitHub are
  already 0.2.20 and differ: check against the 0.2.16 jar). Tests use fake Voxy classes with the same names
  (`src/test/java/me/cortex/voxy`), only what the mod reads.
- Depth for the effects layer (`client/render/VoxyDepth` behind `LodDepth`, alongside DH): texture is
  `pipeline.fb.getDepthTex()` (opaque LODs), projection is `getViewport().projection` (near 8–16 blocks, far 48 km,
  forward, NDC −1..1 in 0.2.16); `frameId` indicates whether it rendered this frame. Under a shaderpack, Voxy itself
  places LOD vertices with the same projection (the pack's `vxProj`), so there are no foreign planes as with DH under
  Iris. Where Minecraft rendered, Voxy writes the **near plane** into its depth (without a shaderpack; with one it
  restores the clear value later) — a “world here” marker, not a LOD at 16 blocks: read as a LOD, it would hide all
  smoke beyond 16 blocks (`LodDepth.View.world`, `fx_depth`: `LodWorld`). Without a shaderpack (and for packs with
  `renderToVanillaDepth`), Voxy also copies LODs into Minecraft's depth, clamping distant ones to its far plane (one
  24-bit step below 1): such a pixel is a LOD, its distance comes from Voxy depth (`pinsWorldDepth`, `PINNED`).
  Complementary uses `excludeLodsFromVanillaDepth`. Read LODs by nearest texel: mixing neighbors on a hill edge gives
  a distance absent from the frame. The same frame without Voxy depth: `-Dincoming.lodDepth.voxy=false`. Readout:
  “Voxy depth for the effects layer” in the log and `SCENARIO … depth:`; client scenario with Voxy:
  `tools/client_scenario.sh <scenario> voxy` (Sodium, Roxy, Voxy and Forgified Fabric API from the mods directory).
- Distant terrain for the map, rays and sound from Voxy's LOD database (`client/map/VoxyTerrain`, behind `TerrainSource`
  between DH and LSS, `dh.md`): the same path LSS reads (`compat/VoxyTerrainProbe`, MIT) —
  `WorldIdentifier.ofEngineNullable(Level)` (on the game thread, at most once per second; null means Voxy holds no world,
  the layer stays silent), `WorldEngine.acquireRef` → `acquireIfExists(level, x, y, z)` (null: section absent, air or
  unseen) → `WorldSection.copyDataTo` → `release()` immediately → `releaseRef`. If the section is not in memory, Voxy
  reads its database on the calling thread: background threads only (map tiles and the `Incoming Voxy terrain` scan).
  Voxy levels (voxel 1–16 blocks) = tile levels: a level L tile reads level L sections. A level > 0 voxel is the most
  opaque of eight, the upper one on ties (`Mipper`); air only if all are air: column top is the cell's highest block,
  height is the voxel midpoint (`VoxyColumns.height`, ±2^L/2); water shallower than a voxel over a solid bed becomes
  the bed (more opaque) — rivers are “dry” at coarse levels. If a reread coarse summary changes, evict finer ones
  underneath from memory (`dropFiner`): `peek` takes fine data first and would give rays pre-crater terrain. World closed
  during read: `IllegalStateException` and `isLive() == false`: forget the world, find it again (do not disable);
  other `IllegalStateException`s in a live world go to the error counter and log. The `Mapper` block list grows without
  a lock: an ID not yet present is treated as air, without caching. There are no change events for us
  (`setDirtyCallback` is single and used by rendering): the scan around the player rereads level 4 out to 8 km once
  a minute; changed summaries invalidate tiles above them. Voxy's database holds only what Voxy has seen (and what LSS
  the provider delivered); in single-player, the entire save is `/voxy import current`. Readout:
  `TerrainTiles.stats()` (`Voxy (world …): section columns read …`).
- Voxy distant changes follow its normal world ingestion and LOD Server Support's saved-chunk delivery. Incoming
  does not publish into Voxy, advertise a custom terrain receiver, retain timed client columns, or repair another
  producer by re-ingesting. Near-player world changes follow the pressure wave; far LODs may arrive later after
  real source changes are saved and delivered. Measure eventual convergence separately from the near-world timing.
  The old `S2C.VoxyChunk`, `LodBatchStart` and `LodBatchFinished` codecs remain registered as no-op client handlers
  only so existing Flashback recordings decode; their shipped golden bytes must remain unchanged (`replay.md`).
- LOD Server Support 0.19/0.20 (server) observes `ChunkSerializer.write` at RETURN, not block-change events:
  `ChunkSaveDataHook` → `ServerReceiverGlue.onChunkSaveData` → `DirtyContentFilter.observeSave`. It hashes serialized
  block states, biomes and light. A changed observation marks the column dirty and immediately deletes its old store
  row; `DirtyColumnBroadcaster` drains marks every 200 ticks by default, invalidates timestamps/store/in-flight reads,
  and notifies clients to request fresh data. Incoming's direct section writes already set `setUnsaved(true)`;
  missing `setBlock` events alone do not bypass this path. A store miss is normal after an edit: fresh data is
  deposited by LSS's delivery path, not by the save hook. Count changed columns, not all loaded/scar-marked chunks.
  Load-time hash seeding is a baseline, not a comparison against SQLite: loading a previously stale saved column
  does not itself prove the stored row has been repaired. Do not promise that every save/store hit contains ruins.
  LSS exposes a declared public client API. Java-public implementation invalidation helpers are not a supported
  third-party publication contract: do not invoke its networking/filter/store implementation from Incoming main
  code or synthesize saves just to trip its mixin.
  Integration checks (`-PwithLss`, batches `lss_.*`) use the real optional LSS runtime, save hook and SQLite store;
  test-only implementation calls arrange/inspect fixtures and are excluded from the release jar.
