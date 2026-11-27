---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/replay/**"
  - "mod/src/main/java/ua/zentix/incoming/compat/FlashbackReplay.java"
  - "mod/src/main/java/ua/zentix/incoming/compat/ReplayMaterialDelivery.java"
  - "mod/src/test/java/ua/zentix/incoming/client/replay/**"
  - "mod/src/main/java/ua/zentix/incoming/net/**"
  - "mod/src/test/java/ua/zentix/incoming/net/**"
---

# Flashback replay

## Pitfalls
- Flashback replay: during seeking, the replay server applies a recording-chunk snapshot and reads packets to the
  destination in one tick, delivering mod packets to the client immediately as read, all at once; Flashback NeoForge
  Fixed also puts the last packet of each mod type into every snapshot. One-shot event packets (explosion, sound,
  flash, shake) must pass through `Replay.event` in `IncomingClient.Hooks`, otherwise they repeat on every seek.
  A replay-server end-of-tick marker decides whether an event is live (`compat/FlashbackReplay` in `ServerTickEvent.Post`:
  recording position, `doClientRendering`, and `fastForwarding` during video export): the client processes one
  connection's packets in order (Flashback wraps mod packets; Forgified Fabric API and NeoForge decode the wrapper and
  inner packet on the game thread without a second queue). Live only after three consecutive advancing markers
  (`Replay.Markers.SETTLE`): Flashback NeoForge Fixed accumulates packets from snapshot start and delivers them at replay
  server tick end (`catchUpNewViewers`), after markers for all seek reads; with one marker, seek explosions would reach
  the next tick as live. Client ticks cannot identify a seek: the client ticks during it (Flashback only suppresses
  rendering, and on pause also ticks the client from a packet); during video export the server stops between steps
  without clearing the snapshot marker. The nuclear show uses the editor's fractional replay position
  (`getPartialReplayTick`) or the export frame's exact start tick plus fractional progress. A paused editor
  supplies its held tick. Both render and tick consumers read that position on demand. Export draws directly
  between client frame events. The integer marker identifies live packet births and seek membership.
  `RecordedBursts` scans the UUID-matched archive in the background from replay open. Cold seeks display an
  estimate until the original packet outside snapshots supplies its exact birth. Shows predating the archive
  use its initial world time. World exit interrupts the reader. Check: `replay` scenario (`SCENARIO replay seek` lines); replay-server model:
  `ReplayTest`.
  Conventional effects follow the replay through the client tick (`ClientTicks`, `client.md`): Flashback paces the
  client timer by the replay speed and pauses by freezing the level's tick rate, so clouds, smoke, shake and shimmer
  hold on pause and keep the replay's pace; a seek clears them (`IncomingClient.rewound`). `ClientTicks.pre` calls that
  hooked method once more per tick; on a freeze-with-delay tick-rate keyframe the hook's first call of a freeze sets
  the client's frozen steps, so when the keyframe falls between the frame's timer call and our pre-tick, the slowdown
  starts one tick earlier (rare, cosmetic; accepted limit).
- Flashback NeoForge Fixed retains batched material checkpoints by source UUID. A viewer retiring the effects
  acknowledges that UUID. The server releases only that viewer's transfer and replaces its recorded batch with a
  one-byte tombstone. The replaceable `NukeMaterialSources` manifest contains the viewer's current sources.
  `ReplayMaterialDelivery` checks the snapshot's geometry, sync and material against this manifest before forwarding
  them. It sends the nearest source first, one part per 50 ms, including while paused. The local play-connection
  probe passes 40 recorded sources through Minecraft's encoder, NeoForge filters and compression and checks all 17
  active checkpoints at the receiver. The material format and legacy lookup live in `nuclear.md`.
- Chunk-state packets (`S2C.NukeFlashMarks`, flash traces): a Flashback snapshot contains only the last packet of a
  type, not one per chunk, so unloading chunks during seek would erase marks absent from the snapshot. Thus replay
  clients retain marks on chunk unload and show the detonation layer by their own clock (`FlashMarksClient.index`:
  layer time no later than clock + 20 ticks): seeking before detonation hides traces, forward shows them. Seeking far
  ahead to a recording section where the chunk arrived before the snapshot may fail to show them (known gap).
- Video export draws its frames without `RenderFrameEvent`: per-frame state reset only by that event stays at its
  pre-export value through the whole export (the nuclear exposure did: every exported flash white, `NukeLight.fresh`
  resamples by game time instead). Diagnostics during an export: `incoming.dev.inbox` (devtest `DevHands`: `age`,
  `light`, `dw`) works there too.
- Video export (`FlashbackReplay.exporting`: `Flashback.EXPORT_JOB` non-null): frames are not real-time, so frame-budget
  controls do not reduce quality (nuclear pall detail pinned to the setting, `PallPass.Detail.pin`). During export, the
  game frame has the video's dimensions (Flashback 0.39: `ExportJob` resizes the frame and provides it as the window);
  everything using frame size uses that: pall targets, `SceneDepth`, effects-layer shader `ScreenSize` (headless check:
  `INCOMING_EXPORT`, `client-scenario.md`).
- A Flashback (0.39.10 is the last for 1.21.1; ARR, do not redistribute) or Replay Mod recording holds only the
  recording client's server→client packets: nothing beyond its view, no server-only facts, no FOV, screens or
  aim-down-sights. A camera beyond recorded chunks shows holes with caves, so film paths stay over recorded ground;
  players'-eyes film moves the viewer on Flashback's server (`tools/replayscan` `Film.spectate`), otherwise nearby sounds vanish.

- Conventional strike state (`S2C.StrikeClouds`, server `strike/StrikeClouds`, client `client/far/ClientStrikeClouds`):
  live cloud events (`Blast`, `BunkerImpact`, `Vent`, `Collapse`, `Intercept`) are one-shots, dropped in a seek, so
  their clouds and the crater fires also come as one full-state list per player, sent on join and at most once a second
  after new events: the snapshot's last packet is the whole state. The client applies it through `Replay.event` and
  keeps the last list across the seek reset (`IncomingClient.rewound` → `ClientStrikeClouds.rewound`), so the order of
  reset and seek-read list does not matter; it builds each listed cloud at the replay position's motion age, skips
  those born later (they come live) and those seen live (keyed by the live packet). A strike in the last second before
  the seek position is missing until the next recorded list. Each cloud and its packet are framed
  (`StreamCodecs.framed`): the nested packets keep their trailing fields, an unknown kind is skipped.

## Server → client packet format
- Flashback stores each mod packet as bytes and reads it across versions with the current codec. One frame is exactly
  one packet (`ReplayReader` splits recordings by action length; connections use Netty frames; a mod packet extends to
  frame end, and vanilla checks for no remaining bytes after decoding), so a packet format shipped to players can
  only grow at the end, like protobuf. Append fields with `StreamCodecs.trailing(codec, pre-field default)`: old
  replays have no bytes, so use the default. Not at the end of a nested record followed by other packet fields
  (detonations in the `NukeSync` list): records have no length and bytes would misalign — append such fields at the
  packet's end instead (wall speed and detonation time of day in `NukeSync` are trailing lists). Removing fields or
  changing type/order requires a new packet ID; keep the old one registered on the client with its codec decoding
  into the new record. Appending a field also changes the live connection format (old clients disconnect on extra
  bytes), so bump `IncomingNetwork.PROTOCOL` as usual.
- Guard: `S2CGoldenTest`: each client packet has byte samples for every format (`src/test/resources/s2c-golden`,
  `<id>.<n>.hex`, last is current; if current file is absent, the test writes it and fails: verify and commit it).
  The current codec must decode each to the expected value and write exactly the latest. Never edit or delete a
  shipped format's sample: the test would replace a deleted one with the current codec's output and miss a break —
  a changed or deleted `.hex` in the diff is itself a format break.
- Before this rule (after 3.1.0), `wave_speed` inserted `wave` inside detonations in the `NukeSync` list; fixed before
  release: the list is again 3.1.0 detonations, `wave` a trailing list. Replays of `main` builds from #296 (16.10) carrying
  detonations in `NukeSync` (world entry, dimension change, respawn during an active detonation) cannot be read:
  unreleased-build formats are unsupported; this rule covers shipped releases.
