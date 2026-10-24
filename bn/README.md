# BN: hot booster nozzle evidence

Latest: [review round 1 cleanup correction](review1/README.md), with final code `d968b39a`, three-way day/night comparisons and audio. The `final/` set below predates that cleanup/contact correction and remains the thermal/flight evidence.

Captured code: `141f9a6ea07d5e6c5cfed97acb04f9e88efdfc37`. Main reference: `3a8b6eb171a38bd9f03a3374e76e041c969f1974`.
The branch was subsequently rebased onto `cd310f2b`; the nozzle law, exhaust, client tick entry point, three renderers, glow quad and spent-booster entity have identical Git blobs after rebase.

All captures use the stock sandbox without a shader pack. The main reference restores `SpentBoosterRenderer`, `SpentBoosterEntity`, `IncomingClient` and `StrikeProjectileRenderer` from the reference commit. Restoring the client tick handler avoids mixing the PR's smoke with main's vanilla particles. The sandbox `spent` command is the same diagnostic spawn in both variants.

Contact sheets contain unaltered 1:1 centre crops, main above and PR below, with requested and actual frame times. `final-fall-detail.jpg` is explicitly magnified 3x with nearest-neighbour sampling. Full lossless PNGs and capture JSON are under `final/`. Original JPEG sheets at this branch's root predate the final solver and are historical only. Tumbling and smoke are not seeded, so poses can vary between variants.

| Scene | Effect | Camera: distance, height, bearing, look-at height | Times (s) | View |
|---|---|---|---|---|
| `spent-m-night` | `spent missile 20` | `7 4 0 0` | 0.5, 1.5, 3, 5.5 | night, zoom 1 |
| `spent-m-day` | `spent missile 20` | `7 4 0 0` | 0.5, 1.5, 3, 5.5 | day, zoom 1 |
| `spent-d-night` | `spent drone 20` | `5 3 0 0` | 0.5, 1.5, 3, 5.5 | night, zoom 1 |
| `fall` | `spent missile 20` | `8 18 0 17` | 0.1, 0.25, 0.4, 0.5 | night, zoom 1 |
| `tail` | `launch missile` | `12 1 0 5` | 1.6, 1.75, 1.8, 1.85 | day, `track on`, `zoom 4` |

The low cameras miss the booster at 0.5 s; the elevated fall camera shows it in the air. The last camera number is a look-at height in metres, not a pitch angle. The launch aperture is hidden by its own luminous exhaust and alumina trail; the law across separation is checked numerically.

Audio files are unprocessed sandbox captures: 4 seconds per scene, 2 seconds for `fall`, 48 kHz stereo PCM16. Sound source credits: [SOUND-CREDITS.md](https://github.com/zentixua/incoming/blob/main/SOUND-CREDITS.md). BN adds no sound sources or propagation changes.

Local validation before rebase: build and all 1,212 unit tests, including 11 nozzle tests; launcher/debris GameTest 4/4. The final rebase is checked separately in the PR. Independent Claude review is still required by the current workflow.
