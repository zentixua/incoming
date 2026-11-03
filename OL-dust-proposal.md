# OL stage 2 — conventional dust experiment

**Decision: retain main; no rendering PR.** The lighting experiment did not establish a useful improvement in head/residue readability. The requested rapid clearing already occurs for Incoming's coarse torn-cover detail. Making the persistent fine suspension clear like OL would require a different extinction/removal model; that exceeds stage 2's presentation-only authorization. No physics proposal is implemented.

Baseline: Incoming `341c2ea6fdda1a9ed6f941dc00c5065b1353bc7e`. Study reference: Stink-O's MIT-licensed Orbital Laser `8ae6409af90335e461ba473c92a3e56798f30d38`. No upstream code was copied into Incoming.

## What was tested

The experiment used the already-fetched front lighting map for shallow relief on flat puff tops, limited to a 0.8–1.2 linear sky-light multiplier. Vertical billow lighting, per-pixel fire light, sprite alpha, puff positions/sizes, mass, source times, transport and draw budgets were unchanged. It added no texture sample or pass and moved the existing three six-way lighting powers into the non-flat branch. The trial patch is preserved as evidence, not as a candidate to merge.

Sandbox A/B/A captures used the same weapon, seed, camera and daylight commands:

| Scene | Camera (distance, height, bearing, look height), m/degrees | Frames after blast |
|---|---|---|
| Shahed, grass | 40, 3, 0, 2 | 0.1, 0.3, 0.7, 2, 6, 12 s |
| Shahed, forest | 60, 5, 0, 2 | same |
| Missile, raised view | 70, 20, 0, 2 | same, day and night |

The low grass view already reveals the ground through residual dust at 2 s, with the near veil largely gone at 6 s. Forest trunks remain readable through the residue. The lighting trial changes local shading slightly, most visibly from above, but does not establish a stronger moving head or earlier terrain recovery. It also affects flat puffs generally, so that small benefit would incur a broader visual change. The return-to-main captures support keeping the current appearance. These are qualitative judgments, not pixel-identical comparisons: actual frame ages, shake, particles and moving vanilla clouds vary slightly; `capture.json` preserves measured ages.

One capture, `ol-trial-grass`, is **invalid**: it was the first ordinary blast after a distant nuke scene. `Sandbox.startEffect` changes the site to HOME and immediately emits BLAST, before the camera/terrain has returned. Missing local litter and fallback ground data produce a different effect. `ol-trial-grass-ready` repeats it on loaded terrain; compare that against `ol-main-grass` and `ol-main-repeat-grass`. Do not attribute the invalid capture's opaque layer to the shader.

## Why not add an OL-style fade

`client/fx/FrontDust.java:186` already separates coarse torn-cover detail from the fine `GroundDust` inventory: its post-phase suspension decays with the 120 µm settling speed, 0.745 m/s, over the 1.3 m presentation layer. Its e-folding time is about 1.74 s. `client/fx/smoke/GroundDust.java:33` and `nuclear/model/DustModel.java:84` retain fine dust according to size-dependent settling, dilution and transport; fine grains dominate extinction after coarse grains settle. Changing lighting cannot make an optically thick path transmit more scenery.

OL's `mod/shaders/payload.wgsl:379` and `nuke.wgsl:272` explicitly prescribe head decay, 2.5/6-second haze decay and a 3-second low-residue fade. The nuke marcher also removes near-eye dust over 2–30 m (`nuke.wgsl:1489`). Those are changes to density/visibility, not merely light contrast. Copying them would defeat the authorized mass/height/transport invariants.

The code-derived `decay-diagnostic.json` illustrates the distinction, without claiming a full GroundDust trajectory: in a fixed one-metre layer after the positive phase, Incoming's five-bin mixture retains about 92% of its extinction after 6 s, whereas OL's low-residue multiplier retains about 14%. Actual GroundDust height, advection and stem removal vary; this simplified calculation is explanatory only.

## Outcome and evidence

The trial shader was restored byte-for-byte; `git diff --exit-code` passed. There is no retained runtime change, no render PR and no new frame-cost claim. A final-head scene bench is therefore not applicable. No nuclear file was edited. Branch `codex/ol-dust-residue` remains at its original main commit.

Evidence is under `OL-stage2-evidence/`: `incoming/` captures, baseline/trial/restore logs, `rejected-lighting-trial.patch`, command scripts and `invalid-captures.txt`. The publishable evidence is kept exclusively on the orphan `look-gate/ol-stage2-20261103` branch; the tools PR links it. The nuke comparison and any future owner decision are separate in `OL-nuke-proposal.md`.
