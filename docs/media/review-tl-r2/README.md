# TL review, round 2 — PR #434

Code: `ec7ad14d`. Control: main `4498ecfc`. The comparison worktree copies only the SAM sandbox scene and diagnostics, without the trail model or SAM replication fix.

## What to inspect

- [Day: whole frames](sustainer-day.jpg) and [same native-pixel detail](sustainer-day-detail.png), main above TL, 15 / 30 / 60 s. The sustainer leg extends left from the broad boost cloud. It is visible in TL, becoming faint at 60 s; main never creates this leg.
- [Night: whole frames](sustainer-night.jpg) and [same native-pixel detail](sustainer-night-detail.png). The displaced, wavy leg remains visible at 60 s.
- [Close side, day](sam-day-side.jpg) and [close side, night](sam-night-side.jpg), with archived 3.4.1 / main / TL rows. These retained views show the nearby boost/start-cloud skirts. The sustainer axes are outside the close side view's cone at 15/30/60 s; this is not a valid view for measuring their lifetime. Narrower puff spread can leave less of those skirts inside the close crop. Compare it with the upwind view that frames the actual leg.
- [All upwind day moments](sam-upwind-day.jpg), [all upwind night moments](sam-upwind-night.jpg).

| Scene | Camera: distance / height / bearing / look height |
|---|---|
| Upwind, day and night | 90 / 20 / 48 / 40 |
| Close side, day and night | 45 / 4 / 270 / 22 |

New frames are from the isolated sandbox with Iris + Sodium + Complementary Unbound r5.9.3, at 1280×692. Quiet power mode. Each SAM run resets game time to 6000; day/night is explicit. Wind and stability are in [TL frame log](frames-TL.log) and [main frame log](frames-main.log). Day and night have different stability. Interception positions vary between runs; this is an identical scene setup and weather, not a bitwise image-difference experiment. Each round-2 scene was captured once. No contrast/colour corrections. Detail sheets use the same fixed native-pixel rectangle `(40, 350)–(800, 500)` from both branches; whole frames remain available below.

The 3.4.1 row is the archived RV2 `sam-side` / `sam-night-side` reference in a different world/sky. Its first frame is 0.3 s versus 0.25 s here, and it has no 30/60 s reference. It is not an identical-weather control. The previous round's wind-only explanation was incomplete; missing SAM samples were the reason no sustainer geometry existed.

## Native frames


sam-upwind-day:

- main: [15 s](sam-upwind-day-main-15s.png), [30 s](sam-upwind-day-main-30s.png), [60 s](sam-upwind-day-main-60s.png)
- TL: [15 s](sam-upwind-day-TL-15s.png), [30 s](sam-upwind-day-TL-30s.png), [60 s](sam-upwind-day-TL-60s.png)

sam-upwind-night:

- main: [15 s](sam-upwind-night-main-15s.png), [30 s](sam-upwind-night-main-30s.png), [60 s](sam-upwind-night-main-60s.png)
- TL: [15 s](sam-upwind-night-TL-15s.png), [30 s](sam-upwind-night-TL-30s.png), [60 s](sam-upwind-night-TL-60s.png)

sam-day-side:

- main: [15 s](sam-day-side-main-15s.png), [30 s](sam-day-side-main-30s.png), [60 s](sam-day-side-main-60s.png)
- TL: [15 s](sam-day-side-TL-15s.png), [30 s](sam-day-side-TL-30s.png), [60 s](sam-day-side-TL-60s.png)

sam-night-side:

- main: [15 s](sam-night-side-main-15s.png), [30 s](sam-night-side-main-30s.png), [60 s](sam-night-side-main-60s.png)
- TL: [15 s](sam-night-side-TL-15s.png), [30 s](sam-night-side-TL-30s.png), [60 s](sam-night-side-TL-60s.png)

## Sound and metadata

Sound source code and assets are unchanged. [TL day WAV](sam-upwind-day-TL.wav), [main day WAV](sam-upwind-day-main.wav), [TL night WAV](sam-upwind-night-TL.wav), [main night WAV](sam-upwind-night-main.wav): native 12 s / 48 kHz / stereo PCM16, no mastering, aligned to first observed interceptor motion. [Archived 3.4.1 OGG](sam-day-3.4.1.ogg) is the compressed RV2 reference, not a lossless original.

[captures.json](captures.json) records frame ages and camera settings. Phase/cone counters below are geometry diagnostics, not pixel-area measurements. The CPU test checks actual quads and texture alpha; these Iris frames check the resulting light and contrast. The screen regression starts its 15/30/60 s clock after the final flight sample; the native captures are timed from first interceptor motion.

## Sustainer diagnostics

| View | Age | main sustainer points | TL sustainer points | TL axes in cone | TL peak point opacity |
|---|---:|---:|---:|---:|---:|
| day upwind | 15 s | 0 | 46 | 46 | 0.275 |
| day upwind | 30 s | 0 | 46 | 46 | 0.166 |
| day upwind | 60 s | 0 | 46 | 46 | 0.087 |
| night upwind | 15 s | 0 | 46 | 46 | 0.358 |
| night upwind | 30 s | 0 | 46 | 46 | 0.218 |
| night upwind | 60 s | 0 | 46 | 46 | 0.136 |

Validation: 1224 unit tests passed after rebase; all 8 required local SAM GameTests passed; final CI passed all six GameTest shards, Итог and Записка. The accepted Richardson/Briggs split, fill budget and thresholds are unchanged. No guidance, damage, dependency or pack change. No running servers or releases were changed.
