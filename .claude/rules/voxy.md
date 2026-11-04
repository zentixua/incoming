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
  (`client/VoxyIngest`; LOD Server Support does the same, `compat/VoxyCompat`) on the serial `ClientHorizon` worker. Voxy is client-only:
  dedicated servers do not have it, hence a packet rather than a server call. Section light is supplied; Voxy does not
  calculate it: null means dark (black LODs); loaded chunks use the world's light engine (missing sky layer above
  terrain = 15), copies use `compat/CopyLight` from their blocks within the chunk (disk light would keep a dark district
  lit). All sections, including empty ones: air from a demolished tower erases it. Delivery uses start/chunks/finish
  batches and `C2S.LodBatchReceived`: one unacknowledged batch per connection, a single bootstrap chunk, then a
  fractional chunks/tick quota from the client's vanilla `ChunkBatchSizeCalculator` (0.01–64, like PlayerChunkSender).
  Each batch also holds at most 256 KiB before acknowledgement (one larger indivisible chunk may stand alone): a
  chunk-count window alone can leave MiB behind a fast proxy with a slow downstream. This bounds in-flight data;
  it is not a bytes/tick throttle. `fastProxyCannotAccumulateAMultiMiBBatchBeforeItsSlowClientAcknowledges` guards it.
  The three client handlers run on the same network thread. Before ack, poll Voxy's public
  `VoxyCommon.getInstance().getIngestService().getTaskCount()` on the event loop until its queue empties; never block
  the network thread. Running tasks remain bounded by its worker pool. Missing counter disables Incoming delivery.
  The receive-rate sample ends at the finish marker; capacity/deadline waiting delays acknowledgement without falsifying the measured link rate. Calculator
  state belongs to the channel; batch IDs survive dimension changes and reject old/duplicate/early acknowledgements.
  Netty still gates every chunk: active/writable, at least 16 KiB headroom, no more than 32 KiB pending even with a
  raised watermark, and at most one outstanding chunk write including event-loop dispatch. A large indivisible
  chunk can cross the watermark once. There is no fixed byte-rate throttle. The waiting queue holds at most 48 MiB;
  a fresh copy at the same coordinate/event/deadline replaces its waiting revision (different event deadlines stay separate). The server selects one immutable bounded batch; write callbacks advance it on the Netty event loop without waiting another server tick. `VoxyLods.room` gates `DhUpdates`, `FarLods` and `FrontLods`, even with DH:
  excess updates for Voxy players would otherwise be lost. Copy engine light layers (`getData` fills the live layer
  for a uniform one); a heavy-zone copy goes to DH `DhUpdates.LEAD` before the front, and Voxy receives its “not before front” deadline and detonation identity (`VoxyLods.sendChanged`). `ClientHorizon` holds encoded sections until the same client event clock used by the wave reaches that deadline. Exact before/after section comparisons include light and newly empty sections; unchanged sections carry skip flags. Unknown baselines still send full columns. Regressions: `VoxyLodsTest`, `LodBatchFlowTest`
  and GameTest batch `voxy_backpressure`.
- LOD Server Support 0.19/0.20 (server) observes `ChunkSerializer.write` at RETURN, not block-change events:
  `ChunkSaveDataHook` → `ServerReceiverGlue.onChunkSaveData` → `DirtyContentFilter.observeSave`. It hashes serialized
  block states, biomes and light. A changed observation marks the column dirty and immediately deletes its old store
  row; `DirtyColumnBroadcaster` drains marks every 200 ticks by default, invalidates timestamps/store/in-flight reads,
  and notifies clients to request fresh data. Incoming's direct section writes already set `setUnsaved(true)`;
  missing `setBlock` events alone do not bypass this path. A store miss is normal after an edit: fresh data is
  deposited by LSS's delivery path, not by the save hook. Count changed columns, not all loaded/scar-marked chunks.
  Load-time hash seeding is a baseline, not a comparison against SQLite: loading a previously stale saved column
  does not itself prove the stored row has been repaired. Do not promise that every save/store hit contains ruins.
  LSS exposes a public client API, but no public server column-invalidation API in these versions. Do not invoke its
  networking/filter/store implementation from Incoming main code or synthesize saves just to trip its mixin.
  Integration checks (`-PwithLss`, batches `lss_.*`) use the real optional LSS runtime, save hook and SQLite store;
  test-only implementation calls arrange/inspect fixtures and are excluded from the release jar.
- Speculative distant ruin copies can still be overwritten by LSS before the world chunk is settled. The client
  detects this through `client/LssVoxyWatch` (subscribes only with Voxy: without it a subscriber would make LSS
  download LODs) and reports chunks through `C2S.VoxyStale` (client 12/tick, server 256/second/player). The server
  resends a copy only to that player, and only if it differs from disk (`FarLods.refresh`: ruin plan, trace outside
  the heavy zone, district darkness). Never broadcast that repair to DH/other players: without the short-lived plan
  it could restore intact terrain over their correct ruins. `RuinSettle` now writes saved-zone ruins into vanilla
  chunks without a player visit; later clients depend on LSS's ordinary save/store invalidation. This must be tested
  separately from Incoming's immediate copy delivery. Packets are not ingested during Flashback replay (Voxy shows
  the live database there).

- MT-B3 horizon buffer: 96 MiB queued encoded columns and 24 MiB retained repair sections, plus bounded worker/codec
  scratch and bookkeeping (these are not total client heap/GPU limits). A local repair merges changed section spans,
  not individual block intentions. Same-section plans still require authoritative chronological composition upstream.
  Repair retention is session-local and evicts by bytes; it does not solve late join/reconnect or immutable stale
  replay. LSS's public void consumer cannot veto another consumer. A zero Voxy task count is not an in-progress
  worker/renderer fence. Never claim the city cannot transiently return from a successful later readback.
  `ClientHorizon.reset()` invalidates queued work and returns an asynchronous barrier behind already submitted
  Incoming worker calls; it does not cancel/fence foreign Voxy work. No lock used by a tick may span a Voxy database
  read. The dev probe's `WitnessesTest` holds a read open while producer/reset progress. Durations from nanoTime
  are elapsed times, not CPU times. The development panorama is not a real-city preparation or end-to-end 512 MiB proof.
