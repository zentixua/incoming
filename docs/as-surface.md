# Supported surfaces, slab damage and the blast front

## Existing solutions

| Available solution | What it provides | Fit for this change |
|---|---|---|
| [Insane's Explosion Overhaul](https://www.curseforge.com/minecraft/mc-mods/insanes-explosion-overhaul) | Vanilla explosions can hit entities behind destroyed walls | Useful precedent for ordering; does not supply Incoming's charge, support or breaching laws |
| [Explosion Overhaul](https://www.curseforge.com/minecraft/mc-mods/explosion-overhaul) | Falling debris and smoke for NeoForge 1.21.1 | Presentation, rather than a law for a backed slab |
| [TNT Logic](https://modrinth.com/project/tnt-logic-realistic-explosion-physics) | Support search and collapse after an explosion | Collapse is a separate effect; does not replace the pressure–impulse or contact-breach calculation |

## Scope

Use the existing crater, pressure–impulse and FM 5-250 breach models. A load-bearing backing prevents a block from behaving like a free panel. A crater shallower than a solid block damages its surface without opening a metre-thick slab. Resolve charge-blast blocks and bodies in outward front order, so an opening nearer the charge exists before the front reaches the body behind it. Keep the vanilla collision explosion path unchanged.

The surface benchmark must check its paved and sub-block rows. Its nearby-material checks must use each block's actual pressure–impulse threshold and positions sampled by the wave rays.

Validation and sandbox evidence are recorded in the pull request.

## Physical scope and representation

[FM 5-250 (1992), chapter 3](https://www.bits.de/NRANEU/others/amd-us-archive/fm5-250%2892%29.pdf) describes deformation under a contact charge, rear-face spalling at a free boundary, and radial cracking. The existing FM breach model supplies the contact law; the panel pressure–impulse curve is used only with an unbacked rear face. The backing test is voxel-scale geometry, not a structural finite-element model.

Cohesive partial excavation retains the block and stores a damaged depth with the chunk. Vanilla crack packets show that damage, refreshed before the client's expiry. Loose soil retains unbiased whole-block sampling. There is no cumulative weakening model. Nuclear vaporization uses its existing discretization.

The conventional blast front merges block and body events in distance order, waiting for the travel time obtained by integrating dr/U(P(r)) from the existing pressure and front-speed laws. Failure within one voxel is instantaneous; the work budget may delay an event but does not reverse its causal order. The vanilla crash and Sable hull paths keep their existing behavior.

## Nearby benchmark instability

The 16³ direction grid contains no zero components. A post nine metres down a coordinate axis can fall between all 1352 rays. The position hash shifts the material threshold, hence the nearest position selected for 0.6 R; this explains coordinate-dependent survivors without a random damage threshold. The benchmark now selects sampled positions and retains its destruction thresholds and requested radius shares. A layout regression covers the reproduced origin (-10982969, 177, -15764973) and 31 translations.

## Benchmark completion

An empty `impacts()` queue can occur before the bomb timeline schedules its delayed collapse at tick 22. `StrikeWorld.idle()` includes pending timelines. The bomb rows now wait for that and for falling blocks to settle. They record falling-block start positions before the settling limiter can consume the spawn event, so a deep camouflet's surface ejection is measured separately from sand/gravel falling into the cavity. The existing maximum of three drill-hole columns stays enforced; settling is reported separately.

## Validation

Local `build` and 77 affected GameTests passed, using the downloaded CI runtime mods (`-PmcModsDir=run/ci-mods`). The set includes every surface row, conventional blasts, slab breach, body order/shielding, fractional damage/save codec, the layout regression, bomb tests, the nuclear crater and deferred blasts. Before/after sandbox frames and WAV are required in PR #437 before review.

The complete CI run caught a loaded-face error: the vector to an upper wall block is steep when the charge is near the floor, but room gas loads the vertical wall normally. Backing now follows the ray's actual entry face, and gas uses the faces adjacent to room air. The strengthened tall-room regression and the existing budget test pass without lowering its >300 selected-block requirement. The Sable timing test observes the craft explosion's own event, after the conventional front, and still requires its selected hull blocks to disappear in that event's tick.

## Reproducible loose-soil fixtures

A later full run exposed `loiterOnSand`: 21 removed blocks against the unchanged 0–18 bound (continuous expectation 8.4). Fractional loose-soil rounding consumed the world's shared random stream when deferred crater work ran, so unrelated work changed the sample. Surface craters now use the existing per-impact source seed. The benchmark sets source seeds from relative hit coordinates; it never reseeds the world's generator. `blast_crater_seed` checks that two identical seeds produce the same nonempty excavation after unrelated world RNG draws. No volume or depth bound is relaxed.
