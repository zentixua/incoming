---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/VoxyIngest.java"
  - "mod/src/main/java/ua/zentix/incoming/client/LssVoxyWatch.java"
  - "mod/src/main/java/ua/zentix/incoming/compat/LodSections.java"
  - "mod/src/main/java/ua/zentix/incoming/compat/CopyLight.java"
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
  and our `S2C.VoxyChunk` sent); in single-player, the entire save is `/voxy import current`. Readout:
  `TerrainTiles.stats()` (`Voxy (world …): section columns read …`).
- Distant world changes (craters, ruins and nuclear traces, blackout lamps) use the same path as DH (`compat/DhUpdates`:
  marks, `FarLods` copies, heavy-zone `FrontLods` copies), alongside DH: `compat/VoxyLods` sends chunks to Voxy players
  (`C2S.LodMods` on entry) via `S2C.VoxyChunk`, except players viewing that chunk (their Voxy rebuilds it itself), no
  farther than `FarLods.FAR_CHUNKS`; the client submits sections with static
  `VoxelIngestService.rawIngest(WorldIdentifier, section, x, y, z, blockLight, skyLight)` via `MethodHandle`
  (`client/VoxyIngest`; LOD Server Support does the same, `compat/VoxyCompat`) on the network thread. Voxy is client-only:
  dedicated servers do not have it, hence a packet rather than a server call. Section light is supplied; Voxy does not
  calculate it: null means dark (black LODs); loaded chunks use the world's light engine (missing sky layer above
  terrain = 15), copies use `compat/CopyLight` from their blocks within the chunk (disk light would keep a dark district
  lit). All sections, including empty ones: air from a demolished tower erases it. Rate: 128 KiB per tick per player
  (a host opening the world to friends sends it over their own connection), queue up to 48 MiB (`VoxyLods.room` gates
  `DhUpdates`, `FarLods` and `FrontLods`, even with DH: otherwise excess updates for Voxy players would be lost); copy
  engine light layers (`getData` fills the live layer for a uniform one); a heavy-zone copy goes to DH `DhUpdates.LEAD`
  before the front, but Voxy ingests immediately — give it a “not before front” deadline
  (`VoxyLods.send(…, notBefore)`).
- LOD Server Support (server) broadcasts chunks on save (hash, every 200 ticks), live or from disk, thus already with
  ruins, and on client entry replies “unchanged” by timestamp: it leaves the mod's copies alone. Only the client's
  first check (or after clearing its storage) overwrites them with disk chunks lacking distant ruins: the client
  detects this through its LSS subscriber (`client/LssVoxyWatch`, subscribes only with Voxy: a subscriber without Voxy
  would make LSS download LODs) and names the chunks to the server (`C2S.VoxyStale`: client 12 per tick, server accepts
  up to 256 per second per player); the server resends a copy **only to that player**, and only if it differs from disk
  (`FarLods.refresh`: ruin plan, trace outside the heavy zone, district darkness). Not to everyone or to DH: their LODs
  are correct, and the ruin plan is short-lived (`ZONE_HOLD`, restart) — broadcasting a copy without a plan would
  restore the intact city over ruins in DH's database (review #391). A player absent from the world during the strike
  will not see distant ruins in Voxy until approaching (DH stores them in the server's DH database). Packets are not
  ingested during Flashback replay (Voxy shows the live database there).
