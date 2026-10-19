# Handoff: Artem's complaints about the nuke and rocket flames (cloud sessions, 19.10.2026)

This folder lives only on the branch `claude/project-thread-d5dzbt` of zentixua/incoming. It is reference material, not
code: never merge it into `main` and never copy it into your own branch.

Read it without switching your branch:

```sh
git fetch origin claude/project-thread-d5dzbt
git worktree add /tmp/handoff origin/claude/project-thread-d5dzbt   # files are in /tmp/handoff/handoff/
```

## Files

| File | What it is |
|---|---|
| `mod-complaints.md` | Artem's complaints after testing Incoming 3.4.0 (15 kt nuke on Newisle, wave_speed 0.15–0.20), in his words |
| `nuke-film/kak-vyglyadit-15kt.md` | second by second, what a 15 kt burst should look like by physics (the reference for any "is this right?" check) |
| `nuke-film/physics-audit.md` | audit of the nuke against physics, approved list, what is labelled non-physics |
| `nuke-film/dust-model-research.md` | dust as one process: laws for lift-off behind the front, roll, spray height (§8) |
| `nuke-film/sources-check.md` | verified numbers with sources (Glasstone–Dolan, NUKEMAP, tests) |
| `nuke-film/mushroom-volume.md` | how the raymarched volume (mushroom and pall) is built: grid, steps, upsampling, budget |

Line numbers in these notes are older than `main` (3.4.0, #343): find code by name.

## Sessions

All four are independent and can run at the same time. They touch neighbouring files; whoever merges later merges
`main` into their branch and resolves the conflict (never rebase or force-push someone else's branch).

| # | Session | Complaints | Main code |
|---|---|---|---|
| 1 | Pall: rim and underground | bright rim along block edges with shaders; pall visible in mines | `client/fx/layer/PallPass`, `SceneDepth`, pall shaders in `assets/incoming/shaders/` |
| 2 | Dust from the ground, one blanket | dust spreads from GZ instead of being ripped off the ground behind the front and rolled; far pall looks different by player height (golden texture), not one blanket | `client/nuclear/PallField`, `PallGround`, `NukePall`, `BlastWall`, pall shaders |
| 3 | Wave jolt and flash contrast | wave arrival is a smooth lean instead of one sharp jolt; at 3–5 km from 15 kt the first seconds have extreme contrast and hard shadows across the map | `client/fx/CameraShake`, nuke flash light (`FxLights`, `ClientNuclear`), shadows from the fireball |
| 4 | Rocket flames at render distance | flames are visible from far away but seem to vanish once the rocket enters render distance | `client/far/FarFlightView`, `FarRenderer`, `client/fx/Exhaust`, `client/render/PlumeRenderer` |

## Rules for every session

1. Read `CLAUDE.md` and the `.claude/rules/` files of your area first (`client.md`, `client-fx.md`, `nuclear.md`,
   `client-scenario.md`, `gametest.md`); they win over this folder.
2. First principles: find the real cause and the real physics. Fix the cause, never tune the frame to hide it; prefer
   the fix that closes the whole class of problems. Where the law is only approximate, say so in the PR.
3. Complaints phrased as "check whether it should be like this": derive what physics says first (use
   `kak-vyglyadit-15kt.md` and `sources-check.md`). If the model is right, change nothing and explain in the PR
   description why it looks like that; if the model is wrong, fix the model.
4. No mixins into vanilla or other mods. Root fixes, no workarounds. GPL-3.0-only. The visual budget of all effects
   together, nuke included, stays 2 ms on Artem's RTX 4060.
5. Reproduce before fixing and prove after: headless client scenarios (`tools/client_scenario.sh`, cloud path through
   `xvfb-run`, see `.claude/rules/client-scenario.md`), unit tests for pure logic, GameTest for server logic. Put
   before/after frames in the PR. Shader output with Artem's Iris shaders cannot be checked in the cloud: say so in the
   PR and name the one thing he should look at in game.
6. One PR per session into `main`, opened as a draft right away (CI runs only on PRs). Player-visible change → update
   `docs/guide` (English) and `docs/guide/ru` (Russian) in the same PR. Packet or entity data format change → bump
   `IncomingNetwork.PROTOCOL`.
7. Done = CI gate «Итог» green, an independent review of the diff is clean (a fresh subagent or `/code-review high`,
   findings fixed), then merge the PR yourself. No release, Modrinth, CurseForge or any other public step: Artem cuts
   the release himself.
8. Credits are limited: read only the files you need, let CI run the full GameTest (six shards), do not poll in loops.
