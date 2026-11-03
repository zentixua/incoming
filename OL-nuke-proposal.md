# OL stage 2 — nuclear dust-front proposal

**Recommendation: keep the current nuclear wall.** OL's readable head and quickly clearing low residue are attractive, but the matched-distance captures do not show a way to obtain that same clearance by changing only light, without changing Incoming's height, extinction or transported matter. A small experiment in the lighting of the existing head could improve its silhouette while it approaches an outside observer; that is a separate, unproven proposal for Artem, not an implemented change. It cannot make terrain visible through the optically thick post-front pall at 300/700 m.

No nuclear source, shader or MT file was edited. The conventional lighting experiment was rejected and restored; see `OL-dust-proposal.md`. The useful implemented result is the offline sound check in [PR #562](https://github.com/zentixua/incoming/pull/562), left as a draft for the arranged fresh-session review.

## Capture contract and evidence

- Incoming baseline: `341c2ea6fdda1a9ed6f941dc00c5065b1353bc7e`, before any trial shader edit. The tools PR is based on later main `292f3153`; its code does not alter the captures or runtime. These pictures are not a claim about the concurrent MT branches.
- OL: Stink-O, MIT, `8ae6409af90335e461ba473c92a3e56798f30d38`, both **Nuke and Payload**. Its existing standalone preview executes the actual composed WGSL with Rust-generated strike tables, **FULL** quality. It uses synthetic flat land and trees; it is not Horizon gameplay and does not execute Horizon's voxel destruction, audio or full renderer/half-resolution join.
- Both: horizontal ground range **300 or 700 m**, eye **2 m above ground**, nominal vertical FOV **70°**, looking horizontally toward the burst, **day/noon**. Native images are **1280×692**: the sandbox's requested 1280×720 outer window produces that framebuffer, so the OL preview uses the measured framebuffer dimensions. No image recoloring, opacity adjustment or crop was used.
- Incoming: **15 kt ground burst, effects scale 1**, block = metre. `nuke 15 ground near 290/690`, camera `10 2 0 2`, places the eye 300/700 m from the epicentre, with houses and oaks beside the listener. OL has its own authored scale and no calibrated equivalent yield; it is not a 15 kt physical comparison. Synthetic OL trees and Incoming's destructible houses are different foregrounds. Matching range, view and time reference does not make the engines, lighting, geometry or source energy identical.
- Primary Incoming set uses the **GAME-equivalent wave value 0.25** with CUSTOM and effects scale 1, set by reloading only the isolated sandbox config. Logs confirm `wave 0.25`. The standard sandbox starts at wave 1.0 via `gametest/TestScale`; that first control set is also retained and clearly named `ol-main-nuke-*`. Nothing in Artem's playing instance was changed.
- Time zero is the front's arrival at the scene point **10 m ahead of the eye**, the existing `nuke … near` clock. Incoming's physical/game `FrontProfile` and OL's constant 343 m/s differ. OL is sampled at `PL_HIT + (distance − 10)/343 + measured Incoming frame age`. This aligns the same local event reference, not elapsed time since detonation or an identical shock speed. The eye itself is reached slightly later in each model.

| Requested time, s | Actual Incoming GAME frame age at 300 m | Actual age at 700 m |
|---|---:|---:|
| −0.2 | −0.099 | −0.200 |
| +0.1 | +0.232 | +0.104 |
| +0.5 | +0.598 | +0.514 |
| +2 | +2.000 | +2.015 |
| +6 | +6.014 | +6.015 |
| +12 | +12.005 | +12.005 |

At 300 m the front arrives so soon that the first requests cannot all be met on time; filenames retain requested times, while `capture.json` records actual ages. **OL uses the actual ages above.** These are sparse snapshots of the event, not a continuous motion or equal-FPS benchmark.

Local evidence: `OL-stage2-evidence/incoming/ol-game-nuke-300`, `ol-game-nuke-700`, and `nuke-300`, `nuke-700`, `payload-300`, `payload-700`. Reproduction commands, actual camera/time manifests and logs are preserved. Published galleries and evidence are on the orphan [look-gate/ol-stage2-20261103](https://github.com/zentixua/incoming/tree/look-gate/ol-stage2-20261103) branch, outside every PR diff.

## What the frames show

At **300 m**, Incoming's early view is dominated by the flash. At +2 s the view is inside a dark, dense pall with a warm glow; suspended material continues to obscure it at +12 s. OL's low residue permits the ground and tree silhouettes to remain visible shortly after the nominal front, with the compact column behind them. Payload is already mostly gray where Nuke remains luminous. That difference also includes their different fireball/timeline choices; it is not a light-only comparison of identical dust.

At **700 m**, Incoming's GAME pre-arrival frame still shows the local structures strongly illuminated and burning, with visible debris. The post-front view becomes dark and opaque. OL's approaching rim is visible over/among the synthetic trees; after arrival it leaves a light low veil, and by +6/+12 s the scene is substantially clear. Its screen dirt can remain after volumetric clearance. The 1.0-wave control does not reverse the conclusion: it changes when the strong flash and front coincide, not the post-front inventory law.

These captures support the visual observation that OL makes the head/residue distinction easy to read. They **do not** support treating its clear foreground as the same physical dust with nicer lighting, or reducing our nuke's scale to keep its mushroom in view. They also do not establish that Incoming's current numerical model is exact reality; its sourced fits and estimates remain those documented in the original report.

## The cause in code

[OL Nuke `pl_dust`, lines 272–311](https://github.com/Stink-O/orbital-laser/blob/8ae6409af90335e461ba473c92a3e56798f30d38/mod/shaders/nuke.wgsl#L272) builds separate density terms: a narrow exponential head, haze decaying with 2.5/6-second constants, and a low patchy term decaying over 3 seconds. Its height is authored from radius, noise and distance behind the front. [Payload, lines 379–416](https://github.com/Stink-O/orbital-laser/blob/8ae6409af90335e461ba473c92a3e56798f30d38/mod/shaders/payload.wgsl#L379) uses a lower head and similar residue logic. Neither is a conserved dust-size distribution. [Nuke line 1489](https://github.com/Stink-O/orbital-laser/blob/8ae6409af90335e461ba473c92a3e56798f30d38/mod/shaders/nuke.wgsl#L1489) additionally suppresses ray-marched density within 2–30 m of the eye. It deliberately protects foreground visibility.

Incoming's [DustModel size-bin settling/extinction](https://github.com/zentixua/incoming/blob/341c2ea6fdda1a9ed6f941dc00c5065b1353bc7e/mod/src/main/java/ua/zentix/incoming/nuclear/model/DustModel.java#L34), [BlastWall source coordinates and transport](https://github.com/zentixua/incoming/blob/341c2ea6fdda1a9ed6f941dc00c5065b1353bc7e/mod/src/main/java/ua/zentix/incoming/client/nuclear/BlastWall.java#L208), and [PallField handoff](https://github.com/zentixua/incoming/blob/341c2ea6fdda1a9ed6f941dc00c5065b1353bc7e/mod/src/main/java/ua/zentix/incoming/client/nuclear/PallField.java#L524) keep the suspension tied to local birth, height, phase and transport. Fine grains can retain strong extinction after much of the coarse mass settles. The head becoming pall does not mean the material disappears. Preserving mass alone is insufficient if a presentation trick secretly changes where extinction lies or deletes it near the camera.

## Decision options for Artem

| Idea | Proposal and cost |
|---|---|
| Subtle light contrast on the existing approaching head | **Conditional adapt, not implemented.** Reuse current samples in `fx_pall.glsl`/existing `BlastWall` field; keep all model values, column extinction and birth/transport fixed. No new pass or particles. Expected incremental cost near zero only if replacing existing work; must prove a visible benefit from outside the wall and pass the final 100-strike bench. The conventional prototype did not establish such a benefit, so it is not evidence that the nuclear version will work. |
| Sparse interior/head-biased sampling | **Skip for now.** No sampling defect was isolated here. Only revisit if a frozen-state profile shows the head is missed despite the correct density field; then redistribute the existing step budget and verify inside/outside and multiple fronts. |
| OL's fast density decay, low-height prescription or eye hole | **Skip under the present rules.** Changes visibility, height or material removal. Not a presentation-only route to rapid recovery. |
| An additional thin haze layer | **Skip.** Incoming already carries the residual material into pall; another layer risks double-counting extinction and frame cost. |

**Preferred decision is to leave the nuke unchanged.** If Artem later wants a light-only head experiment, define its goal as a clearer approaching silhouette, not seeing terrain sooner after entering a dense pall. It should stop if it requires altering the physical field or fails the visible-benefit/frame-cost gates.

The supplied `MT-final-plan.md` remains untouched. This proposal does not alter B1 preparation, B2/B3 material continuity, B4 controls, B5 yield/budget gates or B6 arrival. In particular, it supplies no excuse for smaller radii, lost mass or a false ready state at 1 Mt. B7's disposable-world comparison can later include the same observer-range/time metadata; no server work is performed here.

## Validation boundary

The sandbox resource reloads reported no effect-shader compilation failure; the startup narrator-library error is unrelated to rendering. The conventional trial was removed before the retained tools commit. The GPU work used the queued lease, then the sandbox was stopped and the lease released. No final runtime change exists, so there is no render-head scene bench or new frame-cost claim. The preview's per-frame timings and sandbox FPS are diagnostic capture metadata, not a comparable end-to-end performance measurement.
