# Incoming — NeoForge mod for Minecraft 1.21.1 (Open Skies pack)

ID/namespace `incoming`, command `/incoming`, packages `ua.zentix.incoming`, jar `incoming-<version>.jar`, configs
`incoming-server.toml`/`incoming-client.toml`. Old `airstrike:*` worlds open through `legacy/`: `OldNamespace` aliases
registries and `data/airstrike_*.dat`; `LegacyMigration` migrates the old datapack. Open Skies is Artem and friends'
private pack; do not mention it in the mod's public text.

Cinematic, realistic strikes: Shahed-136, Lancet, Grad, cruise missile, B-2 bunker-buster, salvos, nuclear ICBM; remote
with binoculars, aiming at anything the player looks at (blocks, mobs, players, Create Aeronautics craft), custom
Doppler 3D sound. Public repository, public product with a large audience: no sloppy work. Everything is in-game
(Minecraft blocks, entities, characters). Played single-player, as an opened host world and on a whitelisted server; only the host is operator.
Player-visible changes update the player guide in the same PR, both languages: docs/guide English, docs/guide/ru
Russian (frames: `tools/client_scenario.sh guide`, `tools/guide_images.sh`). Nuclear design/deviations:
docs/DESIGN-nuke.md. Neighbors: Open Skies pack (zentixua/open-skies), Almighty AI game-master bridge (zentixua/almighty).

Read this file in full before work. **Before reading or editing files, open every area rule whose `paths:` header
covers them; a path often belongs to several areas.** When planning before opening files, read the area rules by
their descriptions below. This applies to every tool, including shell reads; Codex and project threads never load
path rules by themselves.

**Where knowledge lives (one home per fact; no copies, no history, dates only as origin).** This file: session-wide
rules, concepts and Artem's permanent decisions about the mod and the process. `.claude/rules/<area>.md`: one code
area's structure and pitfalls. Plugin skills: procedures. Team and operations (roles, machines, servers, what needs
Artem's word): the private `open-skies` repository's `.claude/rules/team.md`, loaded by project threads and, through
the laptop plugin, by laptop sessions. Live status: the lead's project memory. Claude Code auto memory is no home:
other agents never see it. A changed fact is rewritten in its home in the same PR, and a rule that must always hold
gets a test or check (`tools/check_knowledge.py` guards this layout). A checkout behind `origin/main` has outdated
instructions: work in a worktree from `origin/main`.

## People and communication
- Author/host: **Artem**, in-game **ZentixUA**.
- Chat in Russian, briefly and directly.
- **Languages (Artem, 26.10).** Everything in the repository is in English: code, comments, log messages, CLAUDE.md,
  `.claude/rules`, AGENTS.md, internal docs, `tools/`, CI, commits and PRs. English plus Russian, in the same PR, only
  for what players need: README(.ru).md, `docs/guide`(`/ru`), release notes, store pages (docs/store) and the mod's
  interface (`en_us`/`ru_ru`), English first. No mixing inside one file. Public text: written for players (no
  developer internals), plain and precise, no emoji, advertising, repetition or filler, every claim checked against code. Weapons go by their real names everywhere (Shahed-136, Lancet, Grad;
  «Шахед-136», «Ланцет», «Град»), never "Shahed-style" (Artem, 14.10).
- **No chronicles** (Artem, 12.10): every text (README, guide, store pages, AGENTS.md, rules, comments) states only the
  current state: no "before/now", version history or dated incident stories; a rule's reason is a present-tense
  fact. Exceptions: `docs/releases/`, and `legacy/` code saying what it does.
- **Fix the reported symptom as reported:** a measurement that does not reproduce it missed it; find the reproducing
  case or restate the exact symptom before coding. Never change look or behavior nobody asked to change (Artem, 10.11).
- **Never control Artem's computer** (no computer-use, no game clicks) and **don't ask him to check in game**:
  verify ourselves (unit tests, GameTest, headless client `tools/client_scenario.sh`, `tools/logscan.py` after play).
- Never delete what your task did not create (worlds, saves, files, worktrees) without Artem's word; move surplus
  aside (`deploy.sh` uses `incoming-backup/`).
- **Act as the engineer, finish without questions** (Artem, 04.11): Artem sets goals as a player; we decide how and
  take obvious engineering steps without waiting. Once the applicable checks pass, merge your PR after green Итог and
  review (workflow below). Ask only about goal changes or irreversible choices.
- **Plans are targets, not decisions** (Artem, 03.11, 20.11): a clearly better way found mid-work, even a fundamental
  rewrite of an existing feature, is taken without asking and recorded in the PR. Limits stay: the concept below, real
  size/shape and public steps by Artem's word.
- **Public steps** (GitHub release, version bump for a release, Modrinth, CurseForge, YouTube, replies to players)
  require Artem's explicit instruction. A release candidate reaches him only after we tested it in game ourselves and
  found nothing to fault; he approves, he does not test for us.
- Store pages and the README first screen follow the usual popular-mod layout (`docs/store/listing.md`): banner and
  one line, 2–3 sentence intro, Features with GIFs/screenshots, video, requirements, FAQ, links, license small; no
  step-by-step install, other screenshots in the Gallery.

## Locations
Project layout, every `tools/` script and the Prism instance paths: `.claude/rules/tools.md`. `mod/` uses ModDevGradle
2, NeoForge 21.1.264, Parchment, Java 21: `src/main` mod, `src/devtest` GameTest/scenarios/bench (excluded from jar),
`src/test` unit tests, `run/` launch folders (.gitignore). Artem's playing instance (`tools/paths.py`, `MC_DIR`) also
supplies Create/Sable/Aeronautics for dev runs; never change it, only copies.

## Working commands
```sh
export JAVA_HOME=~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
cd mod && ./gradlew --daemon build        # compile (-Xlint:all, no warnings), unit tests; --daemon only for
                                         # build/test/compile; never `gradlew --stop`: it kills every session's daemons
cd mod && ./gradlew runGameTestServer     # headless server: Create 6.0.10, Sable 2.0.6, Aeronautics 1.3.2
cd mod && ./gradlew runScenarioSweep      # flight scenarios, all seeds; -PscenarioSeeds=N, default 16
                                         # -PscenarioOnly=<regex> selects; -PscenarioRealChunks uses real loading
cd mod && ./gradlew runScenarioRecord     # rerecord trajectory baseline; explain shifts in the scenario PR
tools/client_scenario.sh nuke            # headless: mod/run/scenario/screenshots/*.png, audio.wav, logs
tools/sandbox.sh shot <name> '<effect>'   # effect frames/WAV, CPU-rendered, no GPU queue (client-scenario.md)
tools/stand/run.sh <name> ci:<branch> --packs none --scenes 100  # scene bench, ~3 GPU min; tools/stand/README.md
tools/deploy.sh                          # jar → dist/ for friends and mods/ without pack auto-update
python3 tools/logscan.py [--since 18:30]   # after play: mod errors, strikes, detonations, Sable
```
Scripts using numpy/scipy/soundfile/Pillow declare dependencies in `# /// script` (PEP 723); run
`uv run tools/<script>.py`, install nothing into system Python. Without an instance (cloud/CI):
`python3 tools/fetch_runtime_mods.py` → `./gradlew runGameTestServer -PmcModsDir=run/ci-mods`.
CI checks each commit once, on `main` push and PR; branches without PRs get no CI, so open a draft immediately.
Checks follow changed paths (`tools.md`); Итог decides merge, failing if any needed check fails or is canceled.
Release steps: `tools.md`.
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

## Principles: picture, physics and settings
**Concept** (Artem, 19.11, permanent; governs every edit from now on):
1. **The picture is the judge.** The goal is what players see and hear, beside the best real footage of the
   phenomenon (real test film, then high-end film VFX that stays physically plausible; nuclear blast wave and the
   haze behind it: Silo S3E09, 01:00:26–01:00:37). Spectacle and cinema are the main measure: mesmerizing, never an
   invented Hollywood fire show.
2. **Physics is the frame, not the goal.** It sets what keeps the world believable and consistent everywhere: cause,
   order, place, time, scale, what burns and what breaks, the same in every chunk and every case (day/night,
   field/city, wind, one strike or a hundred). Nothing appears finished: it originates where/when its cause acts, then
   follows shared world laws (wind, time, gravity).
3. **Model no deeper than the screen shows.** If a simple rule gives the same picture, take it and simplify the deep
   model; precision that never reaches the screen is not a goal. Order: community tools, then a simple rule, then our
   own model; more only when the simpler one fails, shown by a frame or number. Two errors: "childish" (finished
   objects at the wrong place/time, eyeballed numbers) and "excess" (complexity or cost invisible in game).
4. **Presentation is free within plausibility.** Light, colour, contrast, billows, detail, movement, sound and camera
   are strengthened toward the reference footage, marked `presentation:` in code, without changing facts. A real size
   or shape changes only by Artem's word, as a marked deviation. Never add what reality lacks (fire on every block,
   smoke from nowhere, impossible colour, impossible flight, 200 m craters, "space" effects).
- **Vanilla first** (Artem, 20.11): the main visual target is the picture in vanilla rendering without shader packs;
  shader packs (Iris) add on top and must not break it. Tune and judge without shaders; under a pack, only check it
  still works.
- **Not done by design** (Artem, 19.11): consequence sliders (damage ×), Hollywood craters, pixel smoke, a small
  "game-size" nuke default, smoke or fire shorter than its law.
- **Every weapon, the nuke included,** has a realistic and cinematic launch, flight and impact (Artem, 07.11).
- **Game launch, physical effects at the target.** Craft, press launch; target charge, blast, fragments, destruction
  and damage follow real specs/laws. Each number from `WeaponSpec` or law with nearby source; missing-data values
  marked `(est.)`, approximate laws identified.
- **Judging the look:** sandbox frame beside the same `main` frame and the reference footage; the player's eye
  decides, numbers inform. Before/after frames go to Artem. Prefer community particle/effect libraries,
  post-effects, shaderpacks; custom only where none exists.
- **Picture first, then performance** (Artem, 25.10, 04.11, 08.11): optimization never visibly hurts the picture;
  quality presets let players fit their PC. Each effect has a frame-cost limit held at any strike count; redesign
  effects that cannot perform well without hacks, discard costly, barely visible detail. Optimize what is noticeably
  slow (tick, preparation, memory, saves, frame, network), by measurement, without asking; an update is not ready
  while such a cost remains. The highest quality setting looks best.
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
  (`strike/FlightScale`, details in `strike.md`). Never derive game speed from render distance: each player's
  differs, server flight is shared. Default distant nuclear wave also game speed, 0.35 sound speed.
  `scale_preset`: `GAME` default flight 0/wave 0.35, `REAL` real life, `CUSTOM` uses `flight_speed`,
  `nuclear.wave_speed`, `nuclear.effects_scale` as set.
- **Time** (Artem, 10.11): no modes. What players watch move (flight, flash, wave, mushroom, the first 20 minutes of
  fires) runs in real seconds; slow consequences (fires burning out, black rain, fallout decay, sickness, burns, hearing,
  power repairs) follow the world's day through `util/WorldClock` (time of day, never slower than real, never backward:
  vanilla 72×, day-length mods 1×, stopped day real, sleep/`/time add` jump forward). Each detonation runs
  `nuclear/NukeClock`: real for the 20-minute show, then joins the world clock over a minute (`nuclear.md`). An
  effect never precedes its cause (`NukeClockTest`). No new time settings; durations stay in world hours.
- **Physics deviations** only preset scale or Artem's explicit decision; mark every one “not physical” in code and
  guide deviation list (game flight speeds/heights, wave 0.35 let players see approach/wave; real values in weapon
  table). No silent deviations. Presentation boosts change no facts, so are not deviations.
- **Complaints never repeat:** each fix gets a test/check that would catch recurrence.

## Mod boundaries and compatibility
Artem, 26.10: standard Minecraft mod practice, no extra custom principles.
- **Change only our own:** our weapons/mechanics (strikes/aftermath, blackout, mod wind, nuclear mob panic). General
  game fire, smoke over arbitrary fire, vanilla explosions/world sounds belong to vanilla/overhaul mods (Explosion
  Overhaul, Subtle Effects). Block fire, including strike-ignited,
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
  (`nuclear.md`). World clock (`WorldClock`): 1000 ticks = 1 hour of the day.

## Workflow
The repository contains only shared contributor resources. Personal agent launchers, session records and
machine-specific job notes belong outside version control. Agent skills and knowledge hooks live in the `incoming`
plugin (`.claude/plugins/incoming/`, listed by `.claude-plugin/marketplace.json`; claude.ai syncs it from `main`;
Project threads do not read `.claude/skills/` or `.claude/settings.json`).

Agent workflow: task → branch → code → build/tests → PR → CI → one review round → merge. Extra checks only for
visuals/sound/FPS, since tests cannot judge beauty or lag. First principles throughout: derive from how the thing
actually works, fix the real cause.
- **One owner per task:** the owner implements, validates, handles review and merges; overlapping edits are
  coordinated; unfinished work is handed over with branch/PR, checked commit, measurements, risks and next action.
  The owner chooses the implementation and records a substantial decision (context, alternatives, choice,
  consequences) in the PR or a design document; disagreements are settled with code, tests or measurements. A
  concrete correctness, data-loss or budget defect blocks; preference alone does not.
- **Ready-made first** (Artem, 22.10): search mods/addons/libraries/tools before custom code (Modrinth, CurseForge,
  GitHub; Fabric via Roxy/Sinytra Connector) and the mod's own systems (a new renderer only if a frame shows the one
  drawing that phenomenon fails). Custom only if no ready-made solution, reason in one PR line. Our ban
  on vanilla/other-mod mixins concerns our code, not an argument against other mods. A new mod or library that
  clearly helps is welcome: try briefly, tell Artem what it gives and costs; a new required pack mod needs his word.
- **License** (Artem, 22.10): Incoming is GPL-3.0-only; copy code only under compatible licenses (GPL-3.0, MIT, CC0),
  from All-Rights-Reserved or closed mods take ideas only (the license is in the jar's `mods.toml`).
- **Plan only large changes:** before coding, a few PR-description lines of what players see/hear and the law
  (existing mod law, new laws sourced). Small edits directly. A brief gives goal, decisions, references, done numbers,
  not the implementation; that target stays fixed for the task: new views or criteria go to a follow-up PR.
- **Validation:** code changes need build and touched-area GameTest (`./gradlew build`; GameTest through `tools/laptop_job.sh … --
  ./gradlew runGameTestServer -PgametestOnly=…`). Numeric claims get unit tests/GameTest; comment/guide promises held
  by tests. Documentation-only changes use the applicable CI checks, without a game run. Open draft immediately
  (CI only on PRs); split large work.
- **Visual/audio:** edit → sandbox frame/WAV (`tools/sandbox.sh shot`) beside same `main` frame; before/after frames/
  WAV in PR. Vanilla frames render on the CPU (Mesa llvmpipe, `tools/soft_gl.sh`): no GPU lease, sessions in parallel,
  laptop or netcup. The GPU only for what needs it: shader-pack/Voxy/DH frames, frame time, the scene bench. Scene bench (100-strike frame cost versus `main`) only for changes that can change frame cost: render/FX
  code, shaders, particle or draw counts (Artem, 30.10); once, on the final PR head after review. Colors, textures,
  sounds, text, server and tool changes skip it. Never merge above frame-cost limit. No full-pack Iris recording per PR.
- **Performance starts with measurement:** GPU time by layer parts (mod markers, bench line, F3), RenderDoc frame
  (`mod/run/renderdoc/`, Python analysis); edit the numerically identified cause, not a presumed model.
- **One run answers everything** (Artem, 18.11): before any run that observes the system (test, measurement,
  reproduction), decide every question it must answer and record everything that could bear on them, so one run is
  enough to name the cause by number. A guess is not a result; a rerun only to add a forgotten probe is waste. Every
  run records by default (Artem, 20.11): JFR ring and GC log (`build.gradle`, all runs), spark in hand-run clients,
  servers and scenarios, mod timing markers, full logs; read them before any rerun.
  Tooling for the mod: the `deep-debug` skill. **Study before coding** (Artem, 20.11): for a look or performance
  defect, one such run on our frame and on the reference footage, then the design from those numbers. **No guess-tuning; stuck work stops for a
  root-cause analysis** (Artem, 20.11): every attempt has a timebox stated at its start in the plan or PR. Three rounds of fixes (tweaks,
  frame-judge or review rounds) or the timebox without a measured shift toward the goal → stop, find the root cause from
  the recorded data (deep-debug skill), write it with its number in the PR or the executor's report, then act on it: fix that cause,
  change the approach, or correct the test or reference. A new approach is one possible outcome, not the automatic one.
- **One GPU session at a time** (`tools/gpu_lease.sh`: other sessions' GPU clients/`--gpu` jobs refuse, naming holder;
  at most two sessions queue, a third is refused); finish with `tools/sandbox.sh stop`, then `tools/gpu_lease.sh
  release`. CPU clients need no lease, limited only by memory (`soft_gl.sh` admission).
- **Independent review** by the other vendor (if unavailable, a fresh session of the same vendor), effort high (Artem,
  20.11). The reviewer reads the diff and surrounding code, not the PR description or the author's conclusions, and
  leaves its reproducing probe as a file, which becomes the regression test. Each agent only where it is strong
  (Artem, 19.11): Codex reviews code (correctness, tests, budgets, concurrency) and plans before large or physics code,
  never frames, WAV, logos or design; a Claude session judges those. Serious = player-visible wrong behavior, crash,
  data loss (worlds, inventories, configs), tick/frame budget breach, each with a scenario and a number or probe; rare
  configurations are minor unless common setups hit them. Reviewers also report hacks, unsourced constants,
  duplication and stray edits; the author fixes minor findings without another round. At most two rounds (the second
  checks the first round's fixes and new serious defects in them); a defect class surviving both stops the work for the
  root-cause analysis above, not a third round. Merge only without an open serious finding: fixed, or recorded in the PR as an explicit
  limit. Before each release, one Codex audit of the whole `main` diff since the last release.
- **Effort** (Artem, 19.11), for every agent, task and review: the working range is low/medium/high.
  **medium** is the default. **high** for work that needs deep reasoning: diagnosis of an unknown cause, physics
  laws, rendering, performance, concurrency, and reviews of such changes. **low** for mechanical work: renames,
  moving or copying text, routine checks, collecting files or logs. Above high only when high demonstrably failed on
  that task (a missed serious error or a wrong result), stated in the PR or report.
- **Author or explicitly accepted work owner merges** (who owns a task in the team: open-skies `team.md`): green Итог (sole required check), review without serious errors. No repeated verification by
  someone else; CI-missed bugs get tests, not extra manual steps.
- End commits with your own agent's name/address in `Co-Authored-By:`, never another agent's.
- Community standards versus our tools/replacements: `docs/dev-pipeline.md`.

## Shared code and check rules
- **Never read unloaded chunks from tick:** `getBlockState`, `clip`, vanilla `explode`, even NeoForge
  `TicketController.forceChunk` synchronously load/generate, stalling server 5–6 s. `hasChunk`/`isLoaded` include still-
  loading chunks whose `getHeight`/`getBlockState` wait in `managedBlock` (up to 400 ms); check `getChunkNow` readiness
  through `util/Terrain`. Nuclear loads use background vanilla `TicketType` (`NuclearTickets`).
- `Locale.ROOT` for command numbers: Artem's Russian locale makes `String.format("%.1f")` use comma.
- Heavy laptop jobs through `tools/laptop_job.sh` (own systemd service; `--gpu` waits for Artem's game to close and
  leases the GPU; `--soft` CPU clients run at idle CPU priority under a memory ceiling, also while he plays); full-pack jobs (≈12 GB) run singly, GPU-free jobs may overlap within 32 GB; fix excess use in code.
  `/tmp` is RAM tmpfs: large clones/instance copies/frames go to disk, e.g. ignored `mod/run/`.
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
- `client-smoke.md` (blast cloud bounds, bounded preparation, shared dust terrain cache).
- `client.md` (client structure/heightmaps/Iris/`AFTER_LEVEL`); `client-fx.md` (particles/effects layer/trail/alpha
  discard); `client-models.md` (OBJ); `client-sound.md` (OpenAL/volume/loops); `replay.md` (Flashback).
- `gametest.md` (GameTest/flight scenarios, no wall clocks); `client-scenario.md` (headless client); `rigs.md`
  (bench/multiplayer/nested KWin/full-pack client); `trailer.md`.

## Environment
- Key mods: Create 6.0.10, **Create Aeronautics 1.3.2 + Sable 2.0.6** (craft are sub-levels, blocks in distant “plot”;
  vanilla explosion breaks them via Sable mixin), Sodium, **host Iris with shaders**, Voxy (via Roxy) with LOD Server
  Support for distance (Distant Horizons is not in the pack; its support stays an optional `compat/` path, `dh.md`),
  Flashback, Essential; the dedicated server uses the pack's `main`. Both server and clients require the mod jar.
- Shahed/missile have no moving-target lead (`Dive.intercept` only for the Lancet dive): own game flight model
  (Artem, 21.10).
