# Handoff: conventional weapons look and sound by physics (cloud sessions, 20.10.2026)

This folder lives only on the branch `claude/project-thread-izso6n` of zentixua/incoming. It is reference material, not
code: never merge it into `main` and never copy it into your own branch.

Read it without switching your branch:

```sh
git fetch origin claude/project-thread-izso6n
git worktree add /tmp/handoff origin/claude/project-thread-izso6n   # files are in /tmp/handoff/handoff/
```

## The task in one paragraph

Artem (owner) wants Incoming to be the most physically correct, and therefore the most beautiful, mod in its niche. The
nuke is done that way (Incoming 3.4.1). Now the other weapons (Shahed-136, Lancet-3, Tomahawk, Grad, B-2 with its
bomb, the ICBM launch) get the same treatment **for picture and sound only**: engine flames and launch smoke, smoke
trails, the explosion (flash, fireball, smoke and dust as a volume), its light, the jolt it gives the body, and every
sound from engine to echo. All of it is built **around the current flight logic**: speeds, trajectories, guidance,
targets, warhead charges and damage stay exactly as they are. Those are a separate package for Artem's local sessions
(`local/`), not yours.

## Hard boundary (cloud sessions)

- **Do not change** flight, guidance, speeds, trajectories, launch timing, targeting, air defence, interceptors,
  warhead charge values, block damage, craters, fragments, fire ignition on the server, or anything in `entity/`,
  `guidance/`, `strike/` (except reading `WeaponSpec`), `defense/`, `warhead/` (except reading). Do not read `local/`.
- The picture and the sound **read** what the flight and the passport give now (real velocity of the entity, the
  `WeaponSpec.charge` in kg TNT, the weapon type). When the local package later makes speeds and charges real, the
  picture and sound must follow by themselves, with no change on your side. So never hard-code "Shahed flies 51 m/s":
  take the speed from the entity; never hard-code a fireball size: derive it from the charge and fuel.
- Laws must hold for any speed and charge. Supersonic sound, for example, is needed even if no weapon is supersonic
  today (the Grad will be): test it with synthetic speeds in unit tests.

## Files

| File | What it is |
|---|---|
| `cloud/1-flames.md` … `cloud/5-smoke-sources.md` | one spec per session: goal, what the nuke already built to reuse, what to do, checks |
| `cloud/contract.md` | the one shared change two sessions both need (blast packet carries the weapon); read it if your spec says so |
| `ref/feel.md` | what each weapon should look and sound like from launch to aftermath, by physics (the target picture). It describes the end state with real speeds and charges; today's flight is slower, so check your laws with today's numbers and with the real ones in unit tests |
| `ref/passport.md` | real data of each weapon with source and confidence (engines, fuel, charges, sizes) |
| `ref/formulas.md` | laws with units and sources; §5 smoke, light, flames; §6 sound; marks: ✓ verified, ⚠ check against the source before coding |
| `ref/sources.md` | open primary sources |
| `ref/code-map/*.md` | code maps with line numbers from 18.10 (before the nuke's last 20 PRs): find code by name, not line |
| `nuke/*.md` | how the nuke does it: the volume pass (`mushroom-volume.md`), physics audit with what is labelled non-physics, verified numbers, dust laws |

## What the nuke already built (reuse it, do not rebuild)

| Need | Already in `main` |
|---|---|
| Smoke, dust, fire as a lit volume | `client/fx/layer/PallPass` (raymarch at reduced resolution, blue-noise jitter, temporal resolve, depth-aware composite without block-edge rims since #344, GPU timer and quality ladder at 2 ms), shaders `pall.glsl`, `mushroom.glsl`, `fx_pall*.fsh`; `VolumeNoise` (FastNoiseLite); `OpenAir` (no smoke underground: sky-light window around the eye) |
| Light of hot gas | `BlackBody` row in the volume table (`MushroomField.blackBodyRow`), `FxLights` (fireballs light particles and ribbons), fireball light on the world (`MushroomField.balls`) |
| Smoke columns over fires | `client/nuclear/FireStorm` and its fire map in `PallPass` (smoke columns, glow from below) |
| Blast pressure | `nuclear/model/BlastModel` (Kinney–Graham, `surfaceOverpressureKpa(range, tntKg)`, positive phase, wind behind the front), `HobBlast` (height of burst) |
| Body jolt | `nuclear/model/BodySway` + `CameraShake.sway(...)` (lean from the pressure–time history at the body) |
| Shock sound at the ear | `client/sound/BlastPulse` (shock synthesised from the model, rise time from the jump), crack in `NukeSounds` |
| Hearing damage | `nuclear/model/HearingModel` + `client/nuclear/Deafness` (OpenAL EFX low-pass, ringing, recovery on game hours) |
| Propagation | `client/sound/Outdoor` (ISO 9613-2, two bands, refraction day/night), `SoundFilters`, `OwnSources`, `TerrainEcho`, `NukeEchoes` |
| One flame near and far | `ClientWeaponSpec.Flame` and `Stage` (#346): one spec for `PlumeRenderer` near and `FarFlightView` far; numbers in it are still hand-picked |

## Sessions

| # | Session | Runs | Main code |
|---|---|---|---|
| 1 | Engine flames and trails by propellant physics | now, in parallel | `ClientWeaponSpec` (Flame, Trail), `client/fx/Exhaust`, `client/render/PlumeRenderer`, `client/far/FarFlightView`, `TrailPoints`, `FxLights` |
| 2 | The explosion as a volume: smoke grid, fireball, flash | now, in parallel | `PallPass` and its shaders, new `client/fx/smoke/` (grid and cloud model), `BlastEffects`, `Explosions`, `FarBlasts`, `Flash` |
| 3 | Flight sound by engine and propagation | now, in parallel | `client/sound/EngineSound`, `Acoustics`, `Emission`, `Outdoor`, `strike/Hearing` (derived ranges), `tools/build_sounds.py` |
| 4 | Blast sound, hearing and body jolt by pressure | now, in parallel | `client/sound/BlastMix`, `BlastSounds`, `BlastPulse`, `EchoMix`, `TerrainEcho`, `client/nuclear/Deafness`, `client/fx/CameraShake` |
| 5 | Launch clouds, near trails and fire smoke in the volume | **after session 2 is merged** | the smoke grid API from session 2, `Exhaust` launch cloud, fire smoke |

Sessions 1–4 touch neighbouring files. Whoever merges later merges `main` into their branch and resolves the conflict
(never rebase or force-push someone else's branch). Sessions 2 and 4 both need the blast packet to carry the weapon:
`cloud/contract.md` says exactly what that change is, so both make the same change.

## Artem's working principles (they apply to every line you write)

- **First principles.** Every decision comes from how the thing really works: real laws of physics, real measured
  data, the real cause of a problem. Do not tune by eye and do not copy a solution without understanding why it works.
  Where a law is known only approximately, say so plainly.
- **Fix the cause, never hide the symptom.** Prefer the architectural fix that closes the whole class of problems over
  a patch for one case. No workarounds, no special cases for one weapon, no magic numbers without a source.
- **One law per class.** One flame law for all motors, one smoke model for all clouds, one source law for all engine
  sounds; a number that makes a weapon different goes into its passport (`WeaponSpec` / `ClientWeaponSpec`), not into a
  constant or a `switch` by weapon.
- **Picture and sound come out of the model.** If a frame looks wrong, fix the model, never tune the frame.
- **Deviations from physics only as a setting or Artem's explicit decision, labelled as not real.** No quiet
  deviations. Already labelled ones are listed in `nuke/physics-audit.md`.
- **Real data with its source next to it** (comment in code or the passport). Missing data: a marked estimate.
- **Features full and permanent**, not demos. Reuse what exists (table above) before building anything new.
- **His complaints must never come back**: each fix gets a test or check that would have caught it.
- **Effort by risk, small steps, fail fast**: a short scenario or one seed before a long run.
- **Honest**: report measured numbers, say what you could not check (his Iris shaders, his GPU), hide nothing.
- Code and comments in Russian, like the rest of the repo; public texts (guide, release notes) English first, Russian
  second, plain and exact, current state only, no "was/now" history.

## Rules for every session

1. Read `CLAUDE.md` and the `.claude/rules/` files of your area first (`client.md`, `client-fx.md`, `client-sound.md`,
   `client-models.md`, `nuclear.md`, `client-scenario.md`, `gametest.md` as your spec lists); they win over this folder
   on code conventions. Line numbers here are old: find code by name.
2. Formulas marked ⚠ in `ref/formulas.md`: check against the named source before coding; if it differs, use the
   source and say so in the PR.
3. The nuke must not change: it is a special case of the same laws. Where you generalise a nuke class (`BlastPulse`,
   `HearingModel`, `BodySway`, the volume pass), the nuke's existing tests stay green unchanged, and its frames in the
   scenarios `nuke*` do not move.
4. No mixins into vanilla or other mods. GPL-3.0-only. The GPU budget of all effects together, nuke included, is 2 ms
   on Artem's RTX 4060: measure GPU time with the pass's own timer in headless scenarios, report it, and say that the
   real number on his GPU is unchecked.
5. Reproduce before changing and prove after: headless client scenarios (`tools/client_scenario.sh`, cloud path
   through `xvfb-run`, see `.claude/rules/client-scenario.md`), unit tests for pure models, GameTest for server logic.
   Before/after frames (and for sound, the scenario's sound log) in the PR. Shader output with Artem's Iris shaders
   cannot be checked in the cloud: say so and name the one thing he should look at in game.
6. One PR per session into `main`, opened as a draft right away (CI runs only on PRs). Player-visible change → update
   `docs/guide` (English) and `docs/guide/ru` (Russian) in the same PR. Packet format change → bump
   `IncomingNetwork.PROTOCOL` (once per PR).
7. Done = CI gate «Итог» green, an independent review of the diff is clean (a fresh subagent or `/code-review high`,
   findings fixed), then merge the PR yourself. No release, Modrinth, CurseForge or any other public step: Artem cuts
   the release himself.
8. Credits are limited: read only the files you need, let CI run the full GameTest (six shards), do not poll in loops.
