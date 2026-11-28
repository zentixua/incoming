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
  opaque of eight (leaves count as fully opaque), on ties the +x child, then +y, then +z (`Mipper`); air only if all are air: column top is the cell's highest block,
  height is the voxel midpoint (`VoxyColumns.height`, ±2^L/2); under leaves the scan goes on to the ground (`GROUND`, the canopy, read
  past the colour across sections, at most 63 blocks); above level 0 only an air voxel between leaves and ground proves it
  (a level-4 forest read 10-21 m under its floor), else unknown; water shallower than a voxel over a solid bed becomes
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
  lit). All sections, including empty ones: air from a demolished tower erases it; a copy made from disk
  (`LodSections.DiskCopy`, built only by `FarLods.build`, which takes each disk section's fingerprint before the plan
  writes) starts one section below its lowest section that differs from disk, plus every sky-lit section beneath
  (`LodSections.from`): Voxy keeps the dark untouched sections below, normally the disk state (its own view or LSS);
  a copy equal to disk (a restore) and a loaded chunk go whole. Limits, all out of sight from above ground: a chunk
  Voxy never held (no LSS, never seen) gets a crust whose hollow underside shows only at a cliff at its edge; lamps an
  earlier outage copy darkened in a dark basement below the cut stay dark there; two detonations whose far zones overlap
  before `RuinSettle` keep the first one's ruins below the second one's cut. A chunk with the bytes last queued for that
  player in this world is not queued again (`Viewer.offer`, at most `C2S.VoxyStale.REMEMBERED` chunks, what the client
  remembers to report): whatever else writes it into the client's Voxy forgets it first, the server sending the
  player the world chunk (`VoxyLods.onChunkSent`, `ChunkWatchEvent.Sent`) and a `C2S.VoxyStale` report. On the wire the sections
  are deflated (`S2C.VoxyChunk` `incoming:voxy_chunk_z`, `StreamCodecs.deflated`); every size below counts the sections
  as they are, before deflating. Delivery is an acknowledged byte
  window per connection (`net/LodBatchFlow`): the server thread keeps up to `STOCK_BYTES` (2 MiB) of its queue ready on
  the connection's network event loop, topped up each tick, the only rate ceiling (40 MiB/s of sections at 20 TPS);
  the event loop writes chunks while the window and Netty allow, at most `PUMP_BYTES` per task, marks a batch
  (`S2C.LodBatchFinished`) every 64 KiB, and the client acknowledges marks cumulatively (`C2S.LodBatchReceived`,
  handled on the server's network thread, so a freed window sends at once, not on the next tick). The window is the
  line's bandwidth-delay product plus `TARGET_NANOS` (100 ms, LEDBAT's largest target) of own queue, estimated as BBR
  does: the highest delivery rate of the last 1–2 minutes times the lowest mark-to-ack time of the last 9–10, a 1.25/0.75
  probing round trip in eight, within one batch of what is in flight (RFC 6817 max_allowed_cwnd), between `WINDOW_MIN`
  (128 KiB) and `WINDOW_MAX` (1.5 MiB). Never size it by mark-to-ack delay itself: on the same connection LSS's queue
  and LTE jitter raise it, and a delay-based window gave ruins 6 % of the line beside a 100 ms foreign queue and 16 %
  with the jitter alone (`VoxyLodsSharedLineTest`). Our own
  queue and another sender's look the same from here: the target is how much of a foreign queue the window holds its
  share against and how much own queue a slow line carries (88–222 ms from 4.5 to 0.6 MB/s on a default kernel,
  `slowerLinesKeepTheLineFullWithAShortQueue`). With `tcp_notsent_lowat` on the socket (netcup's Crafty container) the
  socket holds at most 47 KB unsent on the rig (2.5 MB without), so the window's bytes beyond TCP's own window wait in
  the stock; what then queues in the line is TCP's window, shared with LSS, whose traffic sets the game-packet wait
  while it streams (rig: LSS at 0.54 or 1.66 MB/s in the same phase, p95 wait 12 or 260 ms). Sizes count section bytes before the connection's compression, which
  shrinks ruin chunks 5.7× (rig, 26.11.2026); `WINDOW_MAX` is ~280 KB on the wire, just over the 240 KB a 4.5 MB/s, 54 ms LTE link
  holds in flight. One acknowledged batch per round trip, as vanilla's chunk sender does at its client-measured rate,
  is round-trip bound: on that link it carried 0.07 MB/s and the 48 MiB queue overflowed. The window bounds what can
  wait ahead of game packets anywhere between server and client, a buffering proxy included
  (`fastProxyHoldsAtMostOneWindowBeforeItsSlowClientAcknowledges`); `acknowledgementsKeepAnLteLineFullWithoutAStandingQueue`
  and `slowerLinesKeepTheLineFullWithAShortQueue` simulate lines from 0.6 to 4.5 MB/s. A mark not above the client's
  last one starts a new server session on the same client channel (a proxy's backend switch): the client acknowledges
  it. The client handlers run on the same network thread. Before acknowledging, poll Voxy's public
  `VoxyCommon.getInstance().getIngestService().getTaskCount()` (sections queued, not started) on the event loop until
  it is at most LSS's own halt mark, 6144 (`VoxyIngest.INGEST_HALT_SECTIONS`); never block the network thread. Not
  "until empty": LSS keeps that queue in the thousands while it fills its radius, and an empty-queue wait cut ruin
  LODs to ~100 chunks a minute with half dropped (Zearth 07.11.2026). Running tasks remain bounded by its worker pool.
  Missing counter disables Incoming delivery. Batch IDs survive dimension changes (a dimension change drops the queue
  and the stock, not the window) and reject old/duplicate/early acknowledgements. Netty still gates every chunk:
  active/writable, at least 16 KiB headroom, no more than 32 KiB pending even with a raised watermark, and at most one
  outstanding chunk write, whose completion sends the next. A large indivisible chunk can cross the watermark once, and
  one larger than the window goes alone. The waiting queue holds at most 48 MiB;
  a fresh copy replaces a waiting copy (not one already handed to the event loop). `VoxyLods.room` gates `DhUpdates`,
  `FarLods` and `FrontLods`, even with DH: excess updates for Voxy players would otherwise be lost. Copy engine light
  layers (`getData` fills the live layer for a uniform one); a heavy-zone copy goes to DH `DhUpdates.LEAD` before the
  front, but Voxy ingests immediately — give it a “not before front” deadline (`VoxyLods.send(…, notBefore)`).
  Regressions: `VoxyLodsTest`, `LodBatchFlowTest`, `ClientLodBatchesTest` and GameTest batch `voxy_backpressure`.
  Per-batch timing: `-Dincoming.lodDiag=true` (`LODDIAG` lines on server and client, wall-clock milliseconds).
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

- The `lod-buffer` development scenario is excluded from the release jar. Its database witnesses run outside the
  producer/reset monitor (`WitnessesTest` holds a read while both progress). Values carry their epoch; stale read
  confirmation cannot consume a new witness. `TimedLodBuffer.current` is an ownership snapshot, never a fence
  against foreign ingestion already crossing stop. Submission timings are elapsed nanoseconds, not CPU time.

- Voxy recovery retains only 65,536 recent chunk coordinates and 4,096 pending hints, not every changed coordinate
  in the session. Requests are deduplicated and drained at the existing server rate. Overflow evicts old hints, so
  recovery remains best effort, not a durable freshness record. A later Incoming submission cannot cancel a
  crossing foreign-delivery hint. Logout/reset clears the previous world and both collections immediately.
- Client crash `IllegalStateException: Geometry OOM` in Voxy `AsyncNodeManager.addWork` is Voxy's LOD geometry
  buffer filling up (sized from free VRAM at join, ~1.8–2.1 GB on an 8 GB card), not Incoming; the player's remedy is
  a lower Voxy render distance.

