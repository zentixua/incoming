# Page-media content checks

Capture real gameplay using a normal multiplayer client and Flashback. Use Flashback's camera keyframes and
native exporter for motion, and ordinary F2 for screenshots. The checks in this directory inspect capture data;
they do not create or alter game effects, operate a server, or replace Flashback's renderer.

Before recording, inspect the complete view from the planned camera, including the sky and foreground. Record
the unmodified site, a normal launch, and the whole event. For a nuclear sequence, record several minutes after
detonation. Keep the source replay and full continuous native export privately. World/replay files are not
redistributable evidence; only permitted rendered media and aggregate measurements belong on the evidence branch.

Acceptance uses both game data and visible content:

1. Bind every exported frame to its replay tick, and the tick to the recorded launch/impact/detonation event.
   Save native exporter settings and frame logs. A screenshot filename or time since receiving an SSH log is
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

Keep task-specific manifests, camera plans, frame reports and before/after evidence on an orphan `look-gate/`
branch or in ignored run directories. Keep reusable checks and their regression tests here.
