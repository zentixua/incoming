# OL2: bounded volumetric ground-dust experiment

**Result: reject this prototype for integration. Keep PR 567 draft and the branch unmerged.** Artem retains the product decision; this report is an engineering rejection of the measured implementation, not of continuous dust as a goal. No release or deployment was performed.

Source: `codex/ol-volume-dust` at `dd0accf8b468a355c8bce43d0e0943813e3a34ab`, tree `235764457cd19644ce8690a4ceda450d28ccdd2e`. OL2 merged current main normally, inspected the historical failed patch, and made no renderer edits: ground snapshot kind 2/replay already exists in the actual WIP. The baseline jar is CI `1aca1654a`; its entire mod tree equals main `659af24a4` (the newer main change is documentation only). Jar hashes and machine settings are in each pair.json.

## Final 100-strike measurement

`ol2-quiet`: same-session A1(main), B(volume), A2(main), 2560×1600, RTX 4060 laptop, Sodium/Iris without shaderpack, performance power mode, 100/100 impacts and all 20 steps valid in every run. No further tuning or run was done after this final pair. Base below is the worse A1/A2, matching the scene gate.

| Camera | Main GPU p50 ms | Volume | Change | Main render-thread p95 ms | Volume | Change |
|---|---:|---:|---:|---:|---:|---:|
| outside | 2.433 | 3.740 | +54% | 5.430 | 6.052 | +11% |
| edge | 4.021 | 5.353 | +33% | 5.174 | 5.950 | +15% |
| inside | 4.023 | 5.434 | +35% | 4.470 | 5.725 | +28% |

The scene gate fails. Even its 25% noise allowance is exceeded, whereas the requested bar was no added cost. The clean inside window alone rejects this implementation: GPU+35%, render-thread CPU+28%. B's foreign CPU mean is 0.03–0.14 cores (maximum 0.39), no overlapping heavy job. Brief desktop GPU activity affects outside/edge (mean 0.79%/4.24%, maximum 12%/22%); inside has zero foreign GPU activity. Preserve these limits instead of attributing every fraction of a millisecond to the dust code. Whole-layer stage samples add roughly 1.7–1.8ms in the pall path; they are not a drained per-function microbenchmark.

`ol2-first` is retained only as diagnostic history: concurrent heavy builds confounded its CPU/clock comparison. Its larger percentages are not the final result.

## Fresh single views

[Five paired scenes](single-view-comparison.jpg): same cameras on current main and volume, with source PNGs, capture metadata and 6 s WAVs in `single/`. [Single-view timing and limitations](SINGLE-VIEWS.md) records the diagnostic GPU averages. It also costs more in these close views (grass at 6 s: 3.047→4.273ms; forest: 2.678→4.648ms). Those averages are not an interleaved CPU gate.

The sheet outlines disappear, but the result is smoother/greyer and lacks the desired dust structure. The fresh night 0.3 s frame has a blocking visual artifact: a bright flat orange field washes out almost the whole foreground below the horizon, unlike main. Both night ages are included in the comparison sheet; the 6 s frame alone misses the worst failure. The canopy obscures most of the forest cloud. This is not a demonstrated overall visual improvement. Nuclear density functions were not changed by OL2; no definite nuclear-quality leak was found by code review, but mixed nuclear/conventional rendering, shader failure/reload and all terrain extremes were not accepted by client tests.

## Independent review and stopping decision

[Fresh independent review](review/code-review.md), with standalone reproductions in `review/`, identifies three serious open defects:

1. The hot-ground transition multiplies mass by the shown share, producing the wrong opacity for thick dust.
2. Refitting one 64-cell axis to another elevated source broadens/moves an unchanged thin layer; preserving total optical mass does not preserve spatial continuity.
3. The new ground route bypasses the source's existing far-terrain visibility contract.

The existing prototype already uses a shared bounded grid, quarter-or-coarser rays capped at 80,000, temporal reuse and simplified lighting. Its higher cost, weak visual benefit and spatial/opacity defects make more parameter tuning a poor use of time. A future attempt needs a representation that preserves local resolution and visibility/opacity contracts within the existing budget. These findings are deliberately left open in this rejected experiment; they are not waived for a merge.

## Checks and final state

- Local full build: 1782/1782 unit tests passed; local blast_radius_brick GameTest 1/1 passed.
- tools/pr_ci.sh 567 returned green Итог on dd0accf8b (full CI including GameTest/LSS).
- Fresh independent code and raw-evidence review completed; verdict changes required.
- Both OL sandboxes, benchmark clients and local GameTest server exited. OL GPU lease released; LEAD2 owns the next GPU slot. No protected ports/worlds/settings were used. No evidence, launcher, report or measurement artifact is added to the production diff.

Orbital Laser by Stink-O (MIT, 8ae6409a) inspired the rendering-method experiment. Incoming's existing source mass/time/motion remain the intended physical input; no Orbital Laser code/assets are copied.
