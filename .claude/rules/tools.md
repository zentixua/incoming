---
paths:
  - "tools/**"
  - "mod/scripts/**"
  - ".github/**"
  - "mod/build.gradle"
  - "mod/gradle.properties"
---

# Where things are, in detail

The short layout is in the root CLAUDE.md; here, what each script does.

```
/mnt/data/projects/incoming/             ← this project (git)
  mod/                                   ← mod sources (ModDevGradle 2, NeoForge 21.1.264, Parchment, Java 21)
    src/main/java/ua/zentix/incoming/   ← code (packages: in the root CLAUDE.md)
    src/main/resources/                  ← assets (sounds, textures, lang ru/en), data (tags, damage types), AT
    src/devtest/                         ← GameTest, test-pad templates, client scenario (not in the jar)
      …/gametest/scenario/               ← flight scenarios: weapon × target × disturbance × seed, flight properties,
                                           trajectory baseline src/devtest/resources/scenario-baseline.json
    src/test/                            ← JUnit unit tests (sound, nuclear blast model)
    scripts/gen_test_structures.py       ← GameTest structures (pad, range, runway)
    run/<client|server|gametest|scenario>/  ← launch folders (in .gitignore)
  tools/
    paths.py                             ← all game paths (the only place)
    fetch_runtime_mods.py                ← Create/Sable/Aeronautics/Lithium from Modrinth (sha512): for CI and the cloud without an instance
    deploy.sh                            ← build → mods/ of an instance and dist/ (--test, --dry; --jar F: a ready jar from CI/release);
                                           does not touch an instance with pack auto-update: packwiz-installer would bring back the pack's jar
    logscan.py                           ← digest of logs/latest.log
    client_scenario.sh <scenario> [shaders] [dh] [voxy] [lss] ← client without a window (KWin virtual + Xwayland), frames and sound in WAV;
                                           scenarios and what each captures: in the script header
    sandbox.sh start|fx|swap|shot|restart|stop|jbr ← effects sandbox: a client with only the mod (+ Iris with a shaderpack, DH) in a
                                           flat world, an effect by the /fx command in seconds, hot code swap and
                                           resource reload without a new client, frames and WAV in one command (`shot`);
                                           a nuke at the camera: `nuke 15 near`; how to work: client-scenario.md,
                                           “Effects sandbox”; check without a client: test_sandbox.sh (CI)
    nested_kwin.sh                       ← nested KWin for the client: no window and no host sound, its own D-Bus bus without starting services
                                           (nested_kwin_bus.conf) and XDG directories; takes the GPU lease and the client lock
                                           (gpu_lease.sh); exit 75 means whoever holds the GPU
    laptop_job.sh [--gpu|--light] <name> -- <command> ← heavy job on the host's laptop: its own temporary systemd service
                                           (not in the Claude group), the laptop does not sleep, everything of it is stopped on exit; no queue;
                                           --gpu [--wait] takes the GPU lease of its session (another session's: refusal 75; with
                                           --wait, in the lease queue) and waits until Artem's game is closed; at the end, “laptop_job <name>:
                                           exit N”; --status: what runs and who holds the GPU
    gpu_lease.sh status|take [--wait]|release ← GPU lease: with a picture, one session (the owner is found by the tree
                                           wt-<S>…); a client on the GPU is one (the lock is held by nested_kwin.sh); another
                                           session: refusal “GPU held by …, since …”; a live nested KWin without the lock (the script before
                                           the lease) also counts as busy; running work extends it, without it expires in 10 min; Artem's game
                                           is artem_playing (java in his instance's folder or in the Prism group); --wait:
                                           an honest queue (FIFO): a free GPU goes to the first waiter; status: who holds it and
                                           who waits; test: test_gpu_lease.sh
    sessions/go.sh <S> [id message]      ← background claude2 session in its own systemd service (incoming-claude-<S>): task
                                           <S>.txt and brief context.md from mod/run/incoming-sessions; the result of a turn is a line in
                                           sessions.log; when the turn is done while its laptop jobs (from the tree wt-<S>, wt-<S>-*)
                                           still run, it continues by itself at their end; --wait <S>, --status
    laptop-jobs/<name>.md                ← tasks for the laptop: the coordinator sends the file by the commit's SHA, the laptop checks sha256;
                                           done ones are deleted (history is in git)
    logtime.py                           ← time of Minecraft log lines for digests of laptop tasks
    free_port.py                         ← a free port on 127.0.0.1 for check servers (stress.sh, mp_scenario.sh)
    rig_procs.sh                         ← processes of checks in their own sessions (source from stress.sh, mp_scenario.sh): the script's exit kills them with the JVM
    test_rig_procs.sh                    ← check of rig_procs.sh (CI): Ctrl+C, TERM, end of script, a foreign session, fd, RIG_GRACE timeout: no orphans
    look_gate/run.sh <name> <tree> [scenes] ← picture-and-sound gate: ProbeScenario scenes under Iris with the whole build (README.md
                                           there): only a one-off comparison with 3.4.1, then to be removed; hearing always without deafening, sampling in the order of the reference and in the air of its game time;
                                           a run's validity: check.py on the log (code 1); test: test_check.py (CI `Шлагбаум`)
    stand/                               ← scene bench (README.md there): a mod wrapper without dependency on Incoming (its own project
                                           MDG) plays scenes.json in a flat world: real strikes of 1, 16, 40, 100 Shaheds,
                                           accumulation, launches nearby; CPU and GPU frame time by stage, server tick,
                                           frames; run.sh <name> <jar> [--against <jar>] — runs by pack (Sodium,
                                           Iris; without a pack, Complementary, Photon by the build's sha512); with --against, a pair
                                           A1, B, A2 in one session; stand.py pr <PR> [--post] — PR against main: the pair's verdict,
                                           the `Стенд сцен` status and a comment; report.py — tables, frame sheets; gate
                                           gate.py, test test_gate.py (CI)
    pr_ci.sh <PR>                        ← wait for a PR's `Итог` check in one command (in the background); a task that hangs as “running” is judged by its
                                           completedAt: 0 is green, 1 is failed (the tasks and the log tail), 3 is a conflict with the base
                                           (GitHub then runs no checks at all; `gh pr checks --watch` would wait forever)
    gametest_durations.py <log>...       ← GameTest batch times from logs (latest.log or a CI task log) → a table by which
                                           CI splits GameTest into parts (src/devtest/resources/gametest-durations.json)
    mp_scenario.sh                       ← multiplayer without windows: a server and two clients (Alpha strikes, Bravo is the target), exits and entries in the middle of a strike
    prod_client.py <scenario> [--world …] ← client with the whole host build, as the player has it (a copy of the instance, without Prism; in the copy
                                           the simple Dynamic FPS is off — idle.timeout 0: a laptop with a charge limit counts it
                                           “on battery”, and after 300 s without input the scene ran at exactly 15 fps, 25.10): a scenario from
                                           ./gradlew scenarioJar (build/scenario-libs, not in the release); nuke-profile — a measurement of a detonation;
                                           strike-profile — server ticks around strikes and teleports (--prop incoming.profile.steps=…);
                                           leak — who holds the world after leaving to the menu: entries into the world with commands (incoming.leak.*),
                                           in the menu — a class histogram and a JFR sample with paths to roots (needs -XX:VMThreadStackSize=8192:
                                           with the default stack the path search crashes the JVM), into the log — lines `SCENARIO leak … holder`;
                                           replay — replay of a Flashback in the directory of tools/pack_dir.py of the build (zentixua/open-skies) with the recording mods and quicksave: saved, opened and played back — the projectile's frames and the places where it vanished; rewinds backward and forward through strikes and the nuke — without live explosions and flashes
    replayscan/                          ← analysis of a Flashback replay by the game itself into tables: a client mod without a window (events, paths,
                                           visibility through the player's eyes or set cameras, ground measurements at explosions) and replaydb.py —
                                           a DuckDB database with queries for video plans (launches, flybys, ground strikes); filming plans of
                                           Flashback video with a check of every frame (-Dreplayscan.film); README.md there
    clips/                               ← short clips from replay data: moments.py (candidates), plan.py (filming plans),
                                           edit.py (editing, captions, flash check by frames); README.md there
    guide_images.sh [frames]             ← guide pictures docs/guide/img from the frames of `client_scenario.sh guide`: recipes
                                           by the workbench window's place, the rest are animations from series: GUI screens are GIF (the designator
                                           screen is cropped to the window), the world is an animated WebP (three to four times lighter than GIF);
                                           the first frame and the length of each are numbers in the script; no series: the animation is untouched
    x11_record.py                        ← the client's window in the nested KWin → a video in real time (ffmpeg x11grab) with time marks
                                           for the sound audio.wav (prod_client.py --video, --size)
    stress.sh                            ← load bench (cloud, xvfb): a dedicated server with a director (src/devtest/.../stress) and three players
                                           without windows; salvos of 30, exit/entry, Nether, nuke, “Abort”; the summary is the STRESS lines in the log
    build_sounds.py [sections]           ← all sounds: CC0/CC BY recordings from Freesound (cache tools/.sound-cache) + synthesis
                                           synth_mod_sounds.py; writes sounds.json and SOUND-CREDITS.md (numpy, scipy, soundfile);
                                           blasts and motor loops — from the originals without loss, loudness by EBU R128 (the blast's angles
                                           are exactly `BlastMix`; loops are quieter than their own projectile's blast; evenness is checked)
    freesound.py login|fetch             ← Freesound originals (OAuth2): login outside the repository (~/.config/incoming); files go to
                                           tools/.sound-cache/orig; downloaded ones need no login
    gen_textures.py                      ← textures (Pillow), fixed seed; each group has its own random generator,
                                           a new one goes at the end (existing files do not change)
    gen_particles.py                     ← effect particle textures (textures/fx/particle), the torch and distant sprites (numpy + Pillow)
    gen_models.py                        ← projectile models: OBJ meshes + textures (numpy + Pillow) and simplified copies
                                           of parts `_lod1` for distant models; do not edit the OBJ by hand
    gen_grid_assets.py                   ← blackout lamp twin models and states from the vanilla jar; do not edit by hand
    trailer/record.sh [shaders]          ← trailer: the client scenario trailer shoots shots frame by frame at 60 fps
                                           (INCOMING_SIZE=1920x1080, frames and the sound log go to mod/run/scenario/trailer/)
    trailer/edit.py [--lang en|ru] [--draft] [--rec …] ← editing to music (Kevin MacLeod, CC BY), titles, sound from the log →
                                           dist/incoming-trailer.mp4, -lite.mp4 and -credits.txt (lines for the video description)
    trailer/icon_from_frames.py <frames> <folder> ← the mod icon from the “icon” plan (a Shahed against the sky): 512 and a small variant
  docs/DESIGN-nuke.md                    ← nuclear strike design
  docs/guide/                            ← player guide in English (pages by topic, pictures in img/),
                                           docs/guide/ru/ — the same in Russian
  docs/store/                            ← Modrinth and CurseForge page texts
  docs/media/                            ← icon (also the mod logo incoming.png), the hero.webp loop for README and pages,
                                           frames for the gallery
  .github/workflows/build.yml            ← CI: what changed → build, unit tests (4 JVMs), GameTest in parts, summary; jar in
                                           artifacts; release; an already checked tree takes that run's jar (tools/ci_reuse.py)
  .github/PULL_REQUEST_TEMPLATE.md       ← PR description template: what changes for the player, how it was checked
  .github/workflows/publish.yml          ← Modrinth and CurseForge pages, manual runs only: draft (hidden
                                           Modrinth draft), submit, release; fields — docs/store/modrinth.json
  docs/releases/<version>.md             ← release notes
```

## Runs and CI
- Mods for runs (`run/*/mods`) are copied from the instance by the `copyRuntimeMods_*` tasks; the path is `MC_DIR` or the
  default. Without an instance (cloud, CI): `tools/fetch_runtime_mods.py` (Java 21 is in the cloud, and the network to
  NeoForge/Mojang/Parchment/Modrinth is open).
- `Что изменилось` in `build.yml`: a change only in `tools/` (except `fetch_runtime_mods.py`, `paths.py`), `docs/`, `*.md`
  does not build the mod or run GameTest (`tools/replayscan` has its own build and unit tests, `tools/look_gate` the run
  validity test, `tools/stand` the wrapper build, its unit tests and test_gate.py); store pages (`docs/store`,
  `docs/media`, `docs/guide`, README, `.github/scripts`, `publish.yml`) only their check `test_store*.py`; `mod/`, CI and
  unknown paths get the full check, and a manual run (release) always runs everything. The jar is the artifact
  `incoming-jar`.
- A push to `main` and a release do not recheck a tree that already fully passed a successful run of this repository
  (a PR merged without `main` moving gives the same tree): `Что изменилось` finds the tag `tested-tree-<tree>`
  (`tools/ci_reuse.py`), and the job “Сборка из проверенного прогона” takes the jar from there. If `main` moved, the full
  check runs.
- Release: bump `mod_version`, write `docs/releases/<version>.md` (in English), merge into `main` and run `build`
  manually on `main` with `release=true`: after all checks, the workflow releases `v<version>` with the jar (friends take it
  from there). If `main` has moved on from the commit checked in the game, a branch `claude/release-…` from that commit with only
  the notes, and the run on it. After the release, the open-skies build takes the jar by itself (its `update` workflow
  runs there every 3 hours; to run it at once, start it manually on `main`), checks it and releases it; players with
  auto-update get it on their next launch. Create, Sable and Aeronautics are pinned in the pack: a new version of them is a PR in
  open-skies together with the pins here (`gradle.properties`, `tools/fetch_runtime_mods.py`).
