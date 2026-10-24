# NK — nuke fireball and flash, frames (PR #441)

Evidence for zentixua/incoming#441. Not code; this branch is never merged.

Conditions: dev sandbox (`tools/sandbox.sh shot`), Iris 1.8.14 + Complementary Unbound (host's pack), 1280×692,
flat grass world, render distance 12, quiet power profile. 15 kt air burst at 592 m.

- 1.38 km: `nuke 15 air near 1320` with `--cam '60 42 0 55'` — camera 1.38 km from ground zero, 42 m up, pitched
  ~12° up (as the 3.4.0 Newisle reference). Near mode fixes the game time and detonation number, so wind and dust are
  the same in main and NK. Frame times are seconds after the burst (near mode counts from the front at the houses,
  2.85 s; files are renamed).
- 5 km: `nuke 15 air`, `--cam '5000 2 0 600'`.
- main = 3d45467f (origin/main at branch time), same sandbox harness (the sandbox waits for the chunks around the
  camera, part of the PR, applied locally to main only for these shots).
- 3.4.0 = the accepted video `nuke-newisle-340` (Newisle, evening, other terrain and camera height), frames at
  burst + t.

Sheets: `nuke1380-day.jpg`, `nuke1380-night.jpg`, `nuke5000-day.jpg`, `nuke5000-night.jpg`, `flash-1380.jpg`
(0.25/0.5/1 s), `late-1380.jpg` (5/7/10 s — mushroom and haze), `ball-closeup.jpg`. Single frames: `frames/`.

`ball-spread.txt`: display luminance inside the ball (disc of 0.6 radii around the ball centre computed from the
model and camera), 10th–90th percentile spread. main 1 s and 3 s: 0.000–0.005 (flat disc). NK day: 1 s 0.043,
3 s 0.301; night: 1 s 0.081, 3 s 0.278.
