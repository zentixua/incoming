# TL review evidence — PR #434

Code: `d27ce5d4`. Comparison: main `e592f2ff`, whose production code is also in `3a8b6eb1` (the later change is AGENTS.md).
Only the `sam` sandbox scene and frame diagnostics were copied into the comparison tree. No trail-model change was copied.

| Sheet | Rows, top to bottom | Camera: distance / height / bearing / look height |
|---|---|---|
| [SAM day](sam-day.jpg) | 3.4.1, main, TL | sandbox: 45 / 4 / 270 / 22 |
| [SAM upwind](sam-upwind-day.jpg) | main, TL | 90 / 20 / 48 / 40 |
| [SAM downwind](sam-downwind.jpg) | main, TL | 150 / 30 / 228 / 50 |
| [SAM night](sam-night.jpg) | 3.4.1, main, TL | sandbox: 45 / 4 / 270 / 22 |
| [Grad day](grad-day.jpg) | main, TL | 120 / 10 / 90 / 30 |

All new frames: the isolated sandbox, Iris 1.8.14-beta.1, Sodium 0.8.13, Complementary Unbound r5.9.3, 1280×692.
Quiet power mode; these are visual comparisons, not performance benchmarks. The 3.4.1 row is the archived RV2
`sam-side` / `sam-night-side` reference, in a different world and with the older sky. No 3.4.1 frames at 30/60 s exist
in that reference. The first old frame is 0.3 s; the new one is 0.25 s. Sheets resize whole frames and label them;
no exposure, colour or contrast correction was applied.

At 15 s the fixed close side view is almost empty in both main and TL: wind has carried the smoke sideways and past
the observer. It is not a lifetime measurement. The downwind view keeps that smoke in front of the observer and shows
the more concentrated body in TL; the Grad view shows a surviving thin trail at 30 s. Review both camera angles.
The upwind long view is included even though the distant SAM smoke is faint. The night log has 40 live trail points
at 60 s. Those counts include the drone's booster as well as the SAM; they do not measure the visible area of smoke.

[captures.json](captures.json) records the actual frame ages and camera settings.
[TL frame log](frames-TL.log) and [main frame log](frames-main.log) record render cost, live trail points where available,
and the actual air parameters. Game time was not reset between effects: the wind evolves slowly, so atmospheric
conditions are close but not identical. In particular these windy night frames are not the class-F calm-night unit-test case.

Sound code and assets are unchanged. Native sandbox captures, aligned to the first observed interceptor motion:
[TL WAV](sam-day-TL.wav), [main WAV](sam-day-main.wav) — 12 s, 48 kHz, stereo PCM16, no mastering.
[3.4.1 archived OGG](sam-day-3.4.1.ogg) is the existing compressed RV2 comparison clip, not a lossless original.

Local validation: 1204 unit tests passed; `sam_.*` GameTest passed all 8 required tests; final build passed after adding
frame diagnostics. An earlier full CI run failed `surface_missile_nearby` (wooden-wall threshold); an isolated repeat
on the same code passed 9/12 fresh-world runs:
https://github.com/zentixua/incoming/actions/runs/40031433574 . No server blast code or tests were changed in TL.
