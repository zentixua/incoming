# Picture and sound look gate

Only a one-time comparison with 3.4.1: frames and sound from full modpack under Iris
 on a laptop from the same points, in one go, until the scenes reach the level of 3.4.1; then the look gate is removed. It does not serve as a condition for
 to join PR: in PR - frames and WAV dev client “before and after”.
For ongoing PR requirements, see [visual and audio checks](../../docs/dev-pipeline.md#4-visual-and-audio-checks).

Scenes - `ProbeScenario` (client scenario `probe`, `mod/src/devtest`): Shahed in 50 and 300 blocks day and night,
 "Grad" salvo day and night, rocket launch from behind (above jet of the
 accelerator: on the axis of the nozzle, the camera stands in the jet itself, and the frame is entirely its smoke) and from the side, the flight of Shahed; all versions have the same
grain, places and times. Time - and the time of the game: the wind of the world and its stability class depend only on it, and at
 the boundaries of the classes D and E at night the wind near the ground is 1 or 5 m/s (the fire trail is either a pillar or a strip), so each scene
 begins at its own time of the game reference run of all scenes (`ProbeScenario.START`; the sample of scenes catches up with it
`/tick sprint`; time goes only forward, so the sample goes in the order of the reference; a scene started later (the previous one took
 longer) is valid as long as its air - the wind near the ground, its direction, stability class - is the same as in the reference time,
otherwise stops the run) - sampling frames are comparable to the full run and to compare-final. hearing without
 stunning - always (`nuke_tinnitus = false` in the copy; the test writes what the game sees and stops if hearing is enabled). For a release - the same class in its tree (git worktree release + copy of the file and line in
`ClientScenario`).

- `run.sh <name> <worktree> [scenes] [mods]` — run (via `tools/laptop_job.sh --gpu`; waits until Artem's game closes),
  output in `mod/run/look-gate/out/<name>/`; ends with `check.py`: a run with hearing enabled or a scene at the wrong
  game time exits with code 1.
- `check.py <latest.log>` — is the run valid for comparison (test: `test_check.py`, in CI).
- `sheet.py <out> <scene> <sheet.png> <name>[=caption]…` — frame sheet: version rows, moment columns.
- `analyze.py <out> <name>…` — frame time, scene audio in WAV, echo comb measure, flyby pitch.
