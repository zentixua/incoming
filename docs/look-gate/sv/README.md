# SV — Shahed salvo, PR #429 against main

**Visual verdict: can merge.** At mid-route the salvo reads as a loose, staggered wave: rounds occupy different strips instead of following one line. The target approach brings them together; the last overhead row includes the first impacts. The side views retain readable silhouettes and banking. No additional visual regression was observed in these four views.

Captured on 24 October 2026, Europe/Warsaw. Left: main `3a8b6eb1`. Right: PR head `e904e2af`, automatically merged with that same main in the local capture worktree (`f31cd4b1`, no conflicts or manual code changes). PR #429 remained open when capture finished; SV did not merge it.

| View | Original-resolution comparison sheet |
| --- | --- |
| Mid-route, overhead | [main / PR](sv_top_mid.png) |
| Mid-route, side | [main / PR](sv_side_mid.png) |
| Camera centred about 300 blocks before the target, overhead | [main / PR](sv_top_300.png) |
| Same target approach, side | [main / PR](sv_side_300.png) |

Existing setup: `tools/sandbox.sh`, its virtual KWin client and GPU lease, Iris 1.8.14-beta.1, Sodium 0.8.13, Complementary Unbound r5.9.3. `MC_DIR` was the separate `mod/run/SV-work/mc` copy. Minecraft 1.21.1, NeoForge 21.1.264; GAME preset, flight_speed 0.0; daylight, flat Sandbox world. Quiet power mode; this is a visual comparison, with no performance verdict.

Each view launches six real mod projectiles through an operator waypoint:

```text
incoming salvo drone 6 0 at 400 -1400 from 0 1400 via 0 -200
```

The selected salvo spread is zero; weapon accuracy remains stock. Cameras and capture windows are in [capture-plan.json](capture-plan.json). Native screenshots are 3840 × 872, from a 3840 × 900 viewport with unchanged FOV. The wider view fits the group while the overhead camera stays below the shader cloud layer. Sheets preserve screenshot pixels; labels sit outside them.

These are separate salvos, with unforced random launch gaps and lane order. Rows match visible passage phases, not synchronized projectile identities. Each column labels its actual age since the order. In the overhead target view, main 78/80/82/84 s is paired with PR 64/66/68/70 s. These sheets do not measure total flight time. `/fx clear` clears flights between scenes; impact terrain persists between the two target views.

The inherited local devtest-only CMD/GO bridge issued server commands and started capture clocks; runtime mod code and launch scripts were not edited. The unused CAM fields in the original capture JSON retain their defaults because cameras were positioned with `tp`; the actual commands are in the plan. [Provenance](provenance.json), per-sheet source hashes, and the original [capture manifests](captures/) accompany the sheets. All original frame series remain in each SV worktree under `mod/run/sandbox/fx/out/sv_*`.

## По-русски

**Можно вливать.** На середине маршрута залп стал рыхлой волной в нескольких полосах вместо цепочки по одной линии. Перед целью полосы сходятся; видны первые попадания. Других визуальных ухудшений на этих четырёх ракурсах не обнаружено. Проверены шесть шахедов, днём, в песочнице под Iris; звук и остальное оружие в эту съёмку не входили.

Слева — main `3a8b6eb1`, справа — #429 поверх той же базы. В ближнем верхнем виде сопоставлены фазы пролёта с разным временем после приказа; настоящий возраст каждого кадра подписан. Код мода не правили, PR не вливали. Игра, миры и инстанс Артёма не затронуты. Песочницы SV остановлены, видеокарта освобождена.
