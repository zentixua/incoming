# Supported surfaces, slab damage and the blast front

## Existing solutions

| Available solution | What it provides | Fit for this change |
|---|---|---|
| [Insane's Explosion Overhaul](https://www.curseforge.com/minecraft/mc-mods/insanes-explosion-overhaul) | Vanilla explosions can hit entities behind destroyed walls | Useful precedent for ordering; does not supply Incoming's charge, support or breaching laws |
| [Explosion Overhaul](https://www.curseforge.com/minecraft/mc-mods/explosion-overhaul) | Falling debris and smoke for NeoForge 1.21.1 | Presentation, rather than a law for a backed slab |
| [TNT Logic](https://modrinth.com/project/tnt-logic-realistic-explosion-physics) | Support search and collapse after an explosion | Collapse is a separate effect; does not replace the pressure–impulse or contact-breach calculation |

## Scope

Use the existing crater, pressure–impulse and FM 5-250 breach models. A load-bearing backing prevents a block from behaving like a free panel. A crater shallower than a solid block damages its surface without opening a metre-thick slab. Resolve charge-blast blocks and bodies in outward front order, so an opening nearer the charge exists before the front reaches the body behind it. Keep the vanilla collision explosion path unchanged.

The surface benchmark must check its paved and sub-block rows. Its nearby-material checks must use each block's actual pressure–impulse threshold and positions with unobstructed rays.

Validation and sandbox evidence are recorded in the pull request.
