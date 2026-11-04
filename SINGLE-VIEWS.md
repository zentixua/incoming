# Fresh single-strike views

Captured 4 November 2026 in isolated repository sandboxes, same seed/effect/camera/light, current main 659af24a4 versus experimental tree dd0accf8b. The main mod tree equals 1aca1654a (the performance baseline jar). Five cases: Shahed grass, forest, paved stone-brick target, raised missile view, night Shahed. PNGs at 0.3/2/6 seconds and 6-second WAVs are in `single/`; capture JSON includes actual frame age and camera. Both clients were stopped after capture.

The volume removes the sprite-sheet boundaries but looks smoother, greyer and less structured. The forest camera is beneath a dense canopy, obscuring much of the main cloud; treat it as a local occlusion view, not proof of attractive forest dust. The night 0.3 s volume frame has a blocking bright orange wash over most foreground; the original main frame retains ground/litter detail. The comparison sheet includes both night 6 s and 0.3 s. No final nuclear/mixed-effect look claim is made.

Last-second effects-layer GPU averages at the 6-second frame (1280×692 output, milliseconds):

| Scene | Main sprites | Volume |
|---|---:|---:|
| Grass | 3.047 | 4.273 |
| Raised missile | 4.344 | 4.916 |
| Night | 4.926 | 5.474 |
| Forest | 2.678 | 4.648 |
| Paved target | 3.768 | 5.199 |

These screenshot-line averages are diagnostic single-view measurements, not an interleaved statistical gate. Source lines are in `single-main-timing.txt` and `single-volume-timing.txt`. Do not infer render-thread CPU cost from the ~16 ms sandbox wall time: its frame cap contaminates that value. The separate 100-strike A1/B/A2 bench measures actual render-thread CPU.
