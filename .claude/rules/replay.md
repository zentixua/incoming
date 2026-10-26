---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/replay/**"
  - "mod/src/main/java/ua/zentix/incoming/compat/FlashbackReplay.java"
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
  without clearing the snapshot marker. The replay client clock follows the recording's time packet once per second
  (`ClientNuclear.TIME_SYNC_TICKS`). Check: `replay` scenario (`SCENARIO replay seek` lines); replay-server model:
  `ReplayTest`.
- Chunk-state packets (`S2C.NukeFlashMarks`, flash traces): a Flashback snapshot contains only the last packet of a
  type, not one per chunk, so unloading chunks during seek would erase marks absent from the snapshot. Thus replay
  clients retain marks on chunk unload and show the detonation layer by their own clock (`FlashMarksClient.index`:
  layer time no later than clock + 20 ticks): seeking before detonation hides traces, forward shows them. Seeking far
  ahead to a recording section where the chunk arrived before the snapshot may fail to show them (known gap).
- Video export (`FlashbackReplay.exporting`: `Flashback.EXPORT_JOB` non-null): frames are not real-time, so frame-budget
  controls do not reduce quality (nuclear pall detail pinned to the setting, `PallPass.Detail.pin`). During export, the
  game frame has the video's dimensions (Flashback 0.39: `ExportJob` resizes the frame and provides it as the window);
  everything using frame size uses that: pall targets, `SceneDepth`, effects-layer shader `ScreenSize` (headless check:
  `INCOMING_EXPORT`, `client-scenario.md`).

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
