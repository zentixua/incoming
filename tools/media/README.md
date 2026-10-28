# Page-media content checks

Capture real gameplay using a normal multiplayer client and the player's usual pack/settings in a separate copy.
Prefer Flashback's own camera keyframes and Export UI. Stills may come from the exported clip at the correct
frame, or ordinary F2. If the isolated desktop cannot open the native save dialog, the existing ReplayFilm tool
may set native Flashback keyframes and invoke the same exporter; record that limitation and any task-specific
authorization. It must not replace gameplay recording, render effects itself, or choose stills by wall-clock
timers. The offline checks in this directory inspect capture data; they do not create or alter game effects,
operate a server, or replace Flashback's renderer.

Before recording, inspect the complete view from the planned camera, including the sky and foreground. Record
the unmodified site, a normal launch, and the whole event. For a nuclear sequence, record several minutes after
detonation. Keep the source replay and full continuous native export privately. World/replay files are not
redistributable evidence; only permitted rendered media and aggregate measurements belong on the evidence branch.

Acceptance uses both game data and visible content:

1. Bind every exported frame to its replay tick, and the tick to the recorded launch/impact/detonation event.
   Save native exporter settings and the inspection frame log. A screenshot filename or time since receiving an SSH log is
   not an exact game tick. Unknown source timing fails a phase-specific still.
2. Check the final encoded interval, not just the untrimmed source. Require the flash at detonation, the rising
   cap and wave arrival. For a factory strike, measure camera/projectile direction, projected body size and
   consecutive visible frames before impact. Unknown world coverage or visibility fails rather than counts as air.
3. Inspect the actual decoded frames at page resolution. Geometric bounds prove where an object should be,
   not whether its gray body is distinguishable against a gray building or hidden by smoke. Brightness and
   contrast checks are necessary diagnostics, not substitutes for visual review.
4. Give an independent reviewer [the shot checklist](review.md), the frames, full native export and underlying
   game measurements. Include rejected before frames beside replacements. Recheck content after every trim.

Incoming 3.10.0 registers its full-frame nuclear flash below the vanilla crosshair; F1 hides that entire GUI
group. Hiding HUD widgets individually in Flashback's native export settings preserves the effect. Verify both
the detonation timestamp and the rendered flash; a bright fireball alone is insufficient.

Run `uv run tools/media/check.py capture.json --out content.json` with Java 21 (`JAVA_HOME`) and FFmpeg available.
The manifest has an `assets` array. Paths are relative to the manifest. Each entry names `file` (the delivered
encoding), `qa` (ReplayFilm inspection `qa.jsonl`), `plan`, `shot`, `events` (replayscan `events.csv.gz`), `first_frame`,
`frame_count`, `fps` and `width` (the actual page display width, never greater than the file width).

- `kind: "factory-approach"` also supplies `scan` (the scan directory) and `uuid`. The plan must track that same
  projectile. The check uses its recorded positions/orientation, actual rendered visibility and final pixels.
  It requires at least one continuous second with an axis projection of 18 px and body diameter of 3 px, moving
  along the camera’s viewing direction and toward the target after passing behind the camera. A following camera
  may hold the missile at constant depth: the check measures its world displacement and closing target distance.
  Body contrast is sampled along the projected axis against neighboring pixels, excluding most background pixels
  that would dilute a thin missile in a square patch. These are editorial readability
  minimums at page size, not changes to the weapon or claims of physical accuracy.
- `kind: "nuclear-sequence"` also supplies `event_tick`, `jar` (the shipped mod) and `jar_sha256`. `PhaseProbe.java`
  reads the shipped jar's cloud/flash/front model; it does not implement a second cloud law. The full sequence
  must include at least 0.5 s before detonation and 180 s after it. The checker requires the recorded 5 kt/night/
  1.6–1.7 km conditions, actual GUI state, a frame-wide flash during the pulse, visible cap rise and a visible
  developed cap. The source ground must remain in view throughout the cloud sequence, with foreground below it,
  not only at wave arrival. These bounds and contrast measurements do not prove the mushroom's appearance.
  The approximate front-arrival calculation is a diagnostic; review the actual dust wave and complete cloud.
  A shorter page excerpt may name `full_sequence` (the `file` of an earlier entry). That full sequence must pass
  for the same event; the excerpt still has to contain the flash, visible rise and wave. Label excerpts accurately.
- Optional `stills` entries name `file`, `export_frame` (the original export's zero-based frame index) and `phase`
  (`flash`, `rising-cap` or `wave` for a nuclear shot). Extract the full 1920×1080 frame from that export. The
  checker derives its tick from the frame map and compares its pixels; no wall-clock estimate is needed.
  For an ordinary F2 instead, supply `source_f2`, exact `tick` and `tick_proof`. Both the original F2 and delivered
  still must match that native frame; wall-clock timings are insufficient. Retain full 1920×1080 originals.
  `tick_proof` is JSON with `paused: true`, measured `tick`, `f2_sha256`, and `state_source` pointing to the raw
  paused-state log or screenshot. The reviewer must inspect that source, not just the transcribed fields.

The JSON report identifies failed conditions and records measurements per final frame. Missing evidence fails.
It cannot establish visual recognizability from contrast alone: the independent review remains mandatory.

Keep task-specific manifests, camera plans, frame reports and before/after evidence on an orphan `look-gate/`
branch or in ignored run directories. Keep reusable checks and their regression tests here.
