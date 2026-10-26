# Incoming — NeoForge mod for Minecraft 1.21.1 (Open Skies pack)

ID/namespace `incoming`, command `/incoming`, packages `ua.zentix.incoming`, jar `incoming-<version>.jar`, configs
`incoming-server.toml`/`incoming-client.toml`. Old `airstrike:*` worlds open through `legacy/`: `OldNamespace` aliases
registries and `data/airstrike_*.dat`; `LegacyMigration` migrates the old datapack. Open Skies is Artem and friends'
private pack; do not mention it in the mod's public text.

Cinematic, realistic strikes: Shahed-136, Lancet, Grad, cruise missile, B-2 bunker-buster, salvos, nuclear ICBM; remote
with binoculars, aiming at anything the player looks at (blocks, mobs, players, Create Aeronautics craft), custom
Doppler 3D sound. Public repository: https://github.com/zentixua/incoming. Everything is in-game: projectiles,
launchers, air defense, targets, cities and “players” are Minecraft blocks, entities and characters. Artem and friends
play single-player, a host's world open to friends, and a whitelisted dedicated server; only the host is operator.
Player guide: docs/guide (README.md covers overview/install). Player-visible changes update the guide in the same PR,
both languages: docs/guide English, docs/guide/ru Russian. Frames: `tools/client_scenario.sh guide`,
`tools/guide_images.sh`. Nuclear design/deviations: docs/DESIGN-nuke.md. Neighboring repositories have their own `CLAUDE.md`:
Open Skies (zentixua/open-skies), Almighty AI game-master bridge (zentixua/almighty).

Read this file in full before work. Keep it well under 32 KiB (Codex reads at most 32 KiB).
It contains only rules needed every session. Package structure and pitfalls live
in `.claude/rules/` (list below). **Before reading or editing files, open every area rule whose `paths:` header covers
them; a path often belongs to several areas.** Do not assume automatic loading: Codex has no path-scoped rules.
When planning before opening files, read the area rules by their descriptions below. This applies to every tool,
including shell reads. New area rules/pitfalls go in their area file; only session-wide rules belong here.

## People and communication
- Author/host: **Artem**, in-game **ZentixUA**. Friends: **ENOTzRPG**, **WallyFillmark**.
- Chat in Russian, briefly and directly.
- Public text is English: README.md, guide, mod description, store pages (docs/store), release notes, en_us keys;
  update Russian translations (README.ru.md, docs/guide/ru, ru_ru) in the same PR. Write plainly and precisely, without
  advertising, repetition or filler; check every claim against code.
- **Languages (Artem, 26.10).** Everything in the repository is in English: code, comments, log messages, CLAUDE.md,
  `.claude/rules`, AGENTS.md, internal docs, `tools/`, CI, commits and PRs. English plus Russian only for what players
  need: the public docs (README, `docs/guide`, release notes, store pages) and the mod's interface (`en_us`/`ru_ru`).
  No mixing inside one file. Chat with Artem stays in Russian.
- **Do not control the user's computer** (no computer-use or game clicks). Prepare everything so Artem only starts
  the game: the mod arrives through a release and an open-skies pack PR (auto-update).
- **Minimize “check in-game” requests**; they interrupt play. Verify ourselves: unit tests, GameTest, headless client
  (`tools/client_scenario.sh`), post-game `tools/logscan.py`.
- Never delete user worlds/saves/files. Move surplus aside (`deploy.sh` uses `incoming-backup/`).
- **Finish without questions:** once built/tested (including GameTest), merge your PR after green Итог and review
  (workflow below), deliver to the game: `tools/deploy.sh` for instances without pack auto-update, release/pack PR
  for those with it. Artem should find a playable mod, not a checklist. Ask only about goal changes or irreversible
  choices.
- Public steps (GitHub release, Modrinth, YouTube) require Artem's explicit instruction.

## Locations
Project layout and every `tools/` script: `.claude/rules/tools.md`. `mod/` uses ModDevGradle 2, NeoForge 21.1.264,
Parchment, Java 21: `src/main` mod, `src/devtest` GameTest/scenarios/bench (excluded from jar), `src/test` unit tests,
`run/` launch folders (.gitignore). Prism: `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/`
(`tools/paths.py`: PRISM); `instances/Open Skies/minecraft/` is Artem's playing instance (MC; folder name only in
`tools/paths.py`, read by `build.gradle`, overridden by `MC_DIR`): `mods/` also supplies Create/Sable/Aeronautics for
dev runs, `logs/latest.log`, `incoming-backup/` for `deploy.sh` removals; `java/java-runtime-delta` is JDK 21.

## Working commands
```sh
export JAVA_HOME=~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
cd mod && ./gradlew --daemon build        # compile (-Xlint:all, no warnings), unit tests; warm daemon: edit → compile 16 → 4 s
                                         # --daemon only for build/test/compile, not game run* or scripts
                                         # never call `gradlew --stop`: it kills every session's daemons
cd mod && ./gradlew runGameTestServer     # headless server: Create 6.0.10, Sable 2.0.6, Aeronautics 1.3.2
cd mod && ./gradlew runScenarioSweep      # flight scenarios, all seeds; -PscenarioSeeds=N, default 16
                                         # -PscenarioOnly=<regex> selects; -PscenarioRealChunks uses real loading
cd mod && ./gradlew runScenarioRecord     # rerecord trajectory baseline; explain shifts in the scenario PR
tools/client_scenario.sh nuke            # headless: mod/run/scenario/screenshots/*.png, audio.wav, logs
tools/sandbox.sh shot <name> '<effect>'   # effect frames/WAV ~10 s after edit, same client (start once)
                                         # view frames during work, put frames in PR; client-scenario.md
tools/stand/run.sh <name> ci:<branch> --packs none --scenes 100  # sampled scene bench, ~3 GPU min
                                         # --against ci:main pairs runs; stand.py pr <number> for PR/main; its README.md
tools/deploy.sh                          # jar → dist/ for friends and mods/ without pack auto-update
python3 tools/logscan.py [--since 18:30]   # after play: mod errors, strikes, detonations, Sable
```
Scripts using numpy/scipy/soundfile/Pillow declare dependencies in `# /// script` (PEP 723); run
`uv run tools/<script>.py`, install nothing into system Python. Without an instance (cloud/CI):
`python3 tools/fetch_runtime_mods.py` → `./gradlew runGameTestServer -PmcModsDir=run/ci-mods`.
CI checks each commit once, on `main` push and PR; branches without PRs get no CI, so open a draft immediately.
Checks follow changed paths (only `tools/`, `docs/`, `*.md` skip mod build; exceptions in `tools.md`); releases run all.
Итог decides merge, failing if any needed check fails or is canceled. Release: `mod_version`,
`docs/releases/<version>.md`, manual `build` with `release=true`, pack PR (order in `tools.md`).
A PR changing packet/entity-data formats bumps `IncomingNetwork.PROTOCOL`. A release with behavior/lang keys different
from the last shipped jar bumps it if not already bumped during that release: old jars must not join new hosts
(NeoForge reports different mod versions).

## Architecture (`ua.zentix.incoming` packages)
Area packages: `strike/` orders/launch, `entity/`/`guidance/` flight, `warhead/` explosion, `nuclear/`, `defense/` air
defense, `launcher/`, `grid/` blackout, `sound/`, `client/`, `work/` tick budget. Details in area rules. Shared rules:
- **Weapon spec** `WeaponSpec` (`WeaponType.spec()`): weapon-specific numbers belong in the spec.
- `net/ClientHooks`: client-implemented interface; server never loads client classes.
- `compat/SubLevels`: all Sable interaction. Mixins in `mixin/<mod or area>/`.
- `util/Terrain`: chunk readiness/ground location; all ground heights through it, no direct `getBaseHeight`/
  `getSeaLevel` elsewhere (`server-chunks.md`).
- Unsaved server state uses non-persistent NeoForge world/player attachments, not static maps: statics survive
  single-player world changes.
- `sound/`: Minecraft-free projectile sound shared by server/client (source, ISO 9613 propagation, recording spectra
  from `tools/build_sounds.py`); audible reach: `strike/Hearing`.
- New `/incoming` commands/syntax (`command/IncomingCommand`) also update Almighty's AI notes
  `data/incoming/almighty/notes/strikes.md`. Munition recipes use Create with vanilla fallback via `neoforge:mod_loaded`.

## Principles: physics and settings
The mod must be the most physically accurate in its niche and spectacular like good cinema. Applies to every edit:
- **The minimum that achieves the effect** (Artem, 25.10, “toy car”: real outside, small motor and a few wires inside),
  for look, sound, flight, destruction, code and tools:
  1. Goal: what players see, hear and feel; reference is reality (video, photos, measurements) beside game frames/audio.
  2. First understand real causes, order and quantities.
  3. Implement the minimum giving the same picture/behavior in every game case (day/night, field/city, wind, one strike
     or a hundred). Visible causes, place, time, order and quantities must also be correct in data: nothing appears
     finished; it originates where/when its cause acts, then follows shared world laws (wind, time, gravity). Do not
     simulate the unseen/inaudible.
  4. Simplest working solution: community tools, then simple rule, then own model. More complexity only when simpler
     fails, demonstrated by a frame or number.
  5. One error is “childish”: finished objects appear at wrong place/time, eyeballed numbers. The other is “excess”:
     complexity/hardware cost invisible in-game.
- **Game launch, physical effects at the target.** Craft, press launch; target charge, blast, fragments, destruction
  and damage follow real specs/laws. Each number from `WeaponSpec` or law with nearby source; missing-data values
  marked `(est.)`, approximate laws identified.
- **Look: physics sets events, presentation shows them** (Artem, 23.10). Spectacular, cinematic, mesmerizing, with no
  impossible real-world phenomena.
  - Physics sets order, sizes, time, speed, trajectory, destruction. Never alter for beauty: no impossible flight,
    200 m craters or “space” effects.
  - Presentation sets light, color, contrast, billows, detail, movement, sound, camera. Strengthen like good cinema in
    Minecraft style; mark boosts `presentation:` in code, without changing facts.
  - Measure is “wow”: frames/movement like good real-strike footage. Player's eye beside previous version decides
    (author compares sandbox frame with same `main` frame during work); numbers inform, never decide. Performance
    balance (Artem, 25.10): each effect has a frame-cost limit, held at any strike count. 3.7.0 is no hard floor:
    simplify/redesign effects that cannot perform well without contortions/hacks; discard costly, barely visible
    detail. Physics still sets events, rendering changes. Leave the nuke alone. Author judges “wow”; before/after
    frames go to Artem.
  - Prefer community particle/effect libraries, post-effects, shaderpacks; custom only where none exists.
- **Flight/air defense use our own game model** (Artem, 21.10): plausible/serious, not 1:1 real flight specs, guidance,
  radar or damage models. Shared class laws (inertia/bank, altitude-speed trade, gravity/drag/wind for unpowered,
  `guidance/`); game numbers in `WeaponSpec.Handling`, `Ballistic`, marked “not physical”.
- **Fix causes, not outcomes.** Correct the producing model, not its output; one law per class; weapon-specific
  numbers in spec, not constants or weapon `switch`.
- **Configure causes, not consequences.** Player sets conditions, world computes consequences. Four setting kinds:
  world rules (phenomena, permissions, salvos, flight time, real-product choices: nuclear yield 1–15 kt, burst height);
  map time/distance scale (`GAME` default, `REAL`, `CUSTOM`); performance (one work budget/tick, quality); convenience.
  Laws compute crater size, destruction, kill probability, penetration, audibility, smoke amount/lifetime. Excess
  setting signals: named after a consequence (“blast power ×”, “kill probability”, “radar range”) or a multiplier
  without physical meaning.
- **Minecraft scale.** Block = meter, so dimensions/everything at target (charge, fragments, crater, penetration,
  damage) stay real under every setting. Short visibility/play range (hundreds of blocks) lets real-speed fast
  weapons cross rendering in fractions of a second. Approach speed is game property, not product: two spec numbers
  per weapon (game `WeaponSpec.Airframe`, real sourced `Article.Envelope`), one world `scale.flight_speed`
  (`strike/FlightScale`: 0 game, 1 real, between v_game·(v_real/v_game)^x per weapon; cruise/dive, B-2 route/drop height,
  interceptor speed, Grad launch rail exit/drag; speed-derived range, leg, waypoint capture, attack geometry, approach
  lane grow with cruise, `Airframe.stretch`; maneuver remains game). Never derive game speed from render distance:
  each player's differs, server flight is shared. Default distant nuclear wave also game speed, 0.20 sound speed.
  `scale_preset`: `GAME` default flight 0/wave 0.20, `REAL` real life, `CUSTOM` uses `flight_speed`,
  `nuclear.wave_speed`, `nuclear.effects_scale` as set.
- **Physics deviations** only preset scale or Artem's explicit decision; mark every one “not physical” in code and
  guide deviation list (game flight speeds/heights, wave 0.20 let players see approach/wave; real values in weapon
  table). No silent deviations. Presentation boosts change no facts, so are not deviations.
- **Complaints never repeat:** each fix gets a test/check that would catch recurrence.

## Mod boundaries and compatibility
Artem, 26.10: standard Minecraft mod practice, no extra custom principles.
- **Change only our own:** our weapons/mechanics (strikes/aftermath, blackout, mod wind, nuclear mob panic). General
  game fire, smoke over arbitrary fire, vanilla explosions/world sounds belong to vanilla/overhaul mods (Explosion
  Overhaul, Subtle Effects), as in weapon mods Superb Warfare/Create Big Cannons. Block fire, including strike-ignited,
  is ordinary game fire; custom smoke/heat only at our sources (blast cloud, crater fuel, substation oil, the nuke).
- **Compatibility through loader channels:** NeoForge events, tags, data maps, configs, vanilla identity (explosion
  with source entity, own damage type); expose ours through tags/data/events too. No specific-mod code in main code.
  Truly needed integrations are optional `compat/` modules using public APIs, loaded only with that mod. Never adopt
  or disable other mods' mechanics/look (smoke/fire/sound).
- **A mod conflicts with ours:** first its settings or pack choice (open-skies); its bug gets an issue to author;
  needed integration goes in optional API `compat/` as above (Sable/DH/Voxy); crash without API gets narrow guard with
  code reason, removed when fixed upstream; fundamental incompatibility declared in `mods.toml`.

## Units and scale
- Block = meter. Projectile speeds blocks/tick, game at flight scale 0 (`FlightScale`: Shahed 2.1, loitering 1.6/dive
  4, missile 4/dive 5, B-2 12, ICBM up to 25, SAM interceptor up to 14; MLRS/bomb ballistic with drag,
  g = 0.0245 blocks/tick²). Sound 17.15 blocks/tick. Shared world air for flight/sound/smoke (wind, stability class,
  humidity): `util/Air`.
- `WeaponType.id` enum ordinal, client `values()[id]`: append new weapons only; remote/binocular order `WeaponType.menu()`.
- Nuclear model meters/seconds/kilotons; `Detonation` converts via `effects_scale`, game front `FrontProfile`
  (`nuclear.md`). Fallout game clock: 1000 ticks = 1 hour.

## Workflow
Agent workflow: task → branch → code → build/tests → PR → CI → one review round → merge. Extra checks only for
visuals/sound/FPS, since tests cannot judge beauty or lag. First principles throughout: derive from how the thing
actually works, fix the real cause.
- **Ready-made first** (Artem, 22.10): search mods/addons/libraries/tools before custom code (Modrinth, CurseForge,
  GitHub; Fabric via Roxy/Sinytra Connector). Custom only if no ready-made solution, reason in one PR line. Our ban
  on vanilla/other-mod mixins concerns our code, not an argument against other mods.
- **Plan only large changes:** before coding, a few PR-description lines of what players see/hear and the law
  (existing mod law, new laws sourced). Small edits directly.
- **Before PR:** build and touched-area GameTest (`./gradlew build`; GameTest through `tools/laptop_job.sh … --
  ./gradlew runGameTestServer -PgametestOnly=…`). Numeric claims get unit tests/GameTest; comment/guide promises held
  by tests. Open draft immediately (CI only on PRs); split large work.
- **Visual/audio:** edit → sandbox frame/WAV (`tools/sandbox.sh shot`) beside same `main` frame; before/after frames/
  WAV in PR. Effect changes require scene bench: 100-strike frame cost versus `main`, before/during review on latest
  PR head; never merge above frame-cost limit. No full-pack Iris recording per PR.
- **Performance starts with measurement:** GPU time by layer parts (mod markers, bench line, F3), RenderDoc frame
  (`mod/run/renderdoc/`, Python analysis); edit the numerically identified cause, not a presumed model.
- **One visual session at a time:** one GPU (`tools/gpu_lease.sh`: other sessions' clients/`--gpu` jobs refuse, naming
  holder); finish with `tools/sandbox.sh stop`, then `tools/gpu_lease.sh release`.
- **One review round**, model that did not write code: Claude PRs → Codex GPT-6 Astra (effort max), Codex PRs → Claude.
  Reviewer reads diff/surrounding code, not PR description or author's conversation/conclusions; checks PR frames,
  plus WAV for player-visible/audible changes. Only serious errors block: wrong behavior, crash, tick-budget or
  frame-cost breach; other findings optional, author fixes minor issues without another round. Second round only
  for serious first-round fixes, no third. Parallel tasks/measurements: Codex GPT-6.1 Sol (effort max).
- **Author merges:** green Итог (sole required check), review without serious errors. No repeated verification by
  someone else; CI-missed bugs get tests, not extra manual steps.
- End commits with your own agent's name/address in `Co-Authored-By:`, never another agent's.
- Community standards versus our tools/replacements: `docs/dev-pipeline.md`.

## Shared code and check rules
- **Never read unloaded chunks from tick:** `getBlockState`, `clip`, vanilla `explode`, even NeoForge
  `TicketController.forceChunk` synchronously load/generate, stalling server 5–6 s. `hasChunk`/`isLoaded` include still-
  loading chunks whose `getHeight`/`getBlockState` wait in `managedBlock` (up to 400 ms); check `getChunkNow` readiness
  through `util/Terrain`. Nuclear loads use background vanilla `TicketType` (`NuclearTickets`).
- `Locale.ROOT` for command numbers: Artem's Russian locale makes `String.format("%.1f")` use comma.
- Heavy laptop jobs through `tools/laptop_job.sh`, own systemd service outside agent app cgroup (shared group can kill
  app too); clients/Blender `--gpu` lease own session's GPU, wait for Artem's game to close. No job queue: GPU session
  runs full-pack jobs (≈12 GB) singly; GPU-free jobs may overlap if total fits 32 GB. Fix excess use in code, not memory
  limits. `/tmp` is RAM tmpfs; large clones/instance copies/frames on disk, e.g. ignored `mod/run/`.
- Checks must never reach Artem's screen/speakers. Clients only via `tools/nested_kwin.sh` (`--virtual`, no desktop
  window/host OpenAL audio, scenario audio to file); without KWin (cloud), `xvfb-run` with same muted host audio (`rigs.md`).

## `.claude/rules/` files
- `tools.md`: layout, every `tools/` script, CI.
- `strike.md` (`strike/`, `target/`: orders/spec/launch/virtual flight/areas/hit outcomes); `entity.md` (`entity/`,
  `guidance/`: flight/autopilots/virtual projectile/client entity tracking); `work.md` (tick budget, bulk damage in
  units); `warhead.md` (staged blast/glass/crater falls/vanilla-blast particles); `defense.md` (air defense, allowed/
  forbidden targets); `launcher.md` (fixed launcher, redstone launch).
- `nuclear.md` (nuke except ruins: model/detonation/radiation/client); `nuclear-world.md` (ruins, zone behind wave,
  player chunk delivery); `grid.md` (blackout/lamps/chunk light).
- `server-chunks.md` (chunks/tickets/world exit/entities/ground); `sable.md`; `dh.md` (Distant Horizons/LOD Server
  Support API terrain for map/rays/sound, distant LODs, world leaks); `voxy.md` (Voxy via Roxy, depth/distant changes/LSS).
- `client.md` (client structure/heightmaps/Iris/`AFTER_LEVEL`); `client-fx.md` (particles/effects layer/trail/alpha
  discard); `client-models.md` (OBJ); `client-sound.md` (OpenAL/volume/loops); `replay.md` (Flashback).
- `gametest.md` (GameTest/flight scenarios, no wall clocks); `client-scenario.md` (headless client); `rigs.md`
  (bench/multiplayer/nested KWin/full-pack client); `trailer.md`.

## Environment
- Key mods: Create 6.0.10, **Create Aeronautics 1.3.2 + Sable 2.0.6** (craft are sub-levels, blocks in distant “plot”;
  vanilla explosion breaks them via Sable mixin), Sodium, **host Iris with shaders**, Distant Horizons, Flashback,
  Essential. Open Skies 53 mods; neighboring All of Create Aeronautics instance ~217; dedicated server uses pack's
  `main`. Both server and clients require mod jar.

## Unfinished / ideas
- Loitering weapon finds its own target on circle (currently operator selects through camera).
- Shahed/missile lack moving-target lead (`Dive.intercept` only Lancet dive); Artem, 21.10: own game flight model,
  no lead.
