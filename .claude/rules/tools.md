---
paths:
  - "tools/**"
  - "mod/scripts/**"
  - ".github/**"
  - "mod/build.gradle"
  - "mod/gradle.properties"
---

# Where things are, in detail

The short layout is in AGENTS.md; here, what each script does.

Prism: `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/` (`tools/paths.py`: PRISM);
`instances/Open-Skies/minecraft/` is Artem's playing instance (MC; folder name only in `tools/paths.py`, read by
`build.gradle`, overridden by `MC_DIR`): `mods/` also supplies Create/Sable/Aeronautics for dev runs,
`logs/latest.log`, `incoming-backup/` for `deploy.sh` removals; `java/java-runtime-delta` is JDK 21.

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
    check_knowledge.py                   ← CI job “Знания” (every run): agent knowledge layout (CLAUDE.md = @AGENTS.md, AGENTS.md
                                           ≤ 28 KiB, every rule ≤ 64 KiB (an oversized one's ceiling only falls), has live `paths:` and is listed, plugin hooks exist, no private
                                           facts); test_check_knowledge.py also tests the plugin's hooks/knowledge.py
    clip_metrics.py change|flicker|period ← motion and repetition of a look take or of reference footage, both measured the
                                           same way (look skill): detail change per 0.1 s after the best shift and apparent speed (search 8 px per lag), frame-to-frame
                                           pulsing, a dominant period and staircase steps along an edge; test_clip_metrics.py
    nuke_survey.py <untouched> <blasted> <gz x> <gz z> ← nuclear ruins against the untouched world, block by block (region
                                           files only, safe on a live server's world): share of built blocks still in place by
                                           kind and distance ring, one-block spikes left standing, block columns at --spot=x,z
    client_scenario.sh <scenario> [shaders] [dh] [voxy] [lss] [gpu] ← client without a window (KWin virtual + Xwayland), frames and sound in WAV;
                                           vanilla (shared GPU slot or CPU) unless shaders/dh/voxy/lss/vista/gpu (run it bare: Gradle writes the launch
                                           script, then the client goes into its own laptop_job.sh --soft unit);
                                           its Gradle step and another Gradle run in the same tree (GameTest) collide on
                                           downloadAssets: start the second after the client's launch script exists;
                                           scenarios and what each captures: in the script header
    dev_client.sh start|cmd|hud|dw|age|watch|smoke|swap|reload|shot|stop|status ← the look loop: ModDevGradle runClient (the mod and its dependencies) straight
                                           into a saved world (a reflinked copy of a real map), JetBrains Runtime from the Gradle
                                           toolchain with JDWP on loopback; `swap` compiles, redefines changed classes (jdb) and presses
                                           the F3+T reload for changed resources; chat commands, reload, F2, F1 and dust-wave diagnostics (dw) as files the
                                           client reads (devtest DevHands: the nested display passes it no keyboard input); shared
                                           GPU slot or CPU (laptop_job.sh --soft); how to work: client-scenario.md, “Standard dev client”
    sandbox.sh start|fx|swap|shot|restart|stop|check|jbr ← effects sandbox (vanilla: shared GPU slot or CPU, unless shaders/sodium/dh/gpu): a client with only the mod (+ Iris with a shaderpack, Sodium without one, DH) in a
                                           flat world, an effect by the /fx command in seconds, hot code swap and
                                           resource reload without a new client, frames and WAV in one command (`shot`; `--at none`
                                           sound only, its take in a 160×90 window at 20 fps);
                                           a nuke at the camera: `nuke 15 near`; how to work: client-scenario.md,
                                           “Effects sandbox”; check without a client: test_sandbox.sh (CI); `check`: the
                                           client starts into its world and takes a frame (CI “Песочница”);
                                           no command for INCOMING_SANDBOX_IDLE s (1800; 0 never): the client stops itself
    stray_clients.sh [--host vps] [--idle S] [--max-age S] ← report only: job units whose caller is gone, sandboxes and dev
                                           clients idle past the limit, other jobs over 12 h, client JVMs whose wrapper is gone; exit 1 when any; never
                                           stops anything; test: test_stray_clients.sh (CI)
    amd_shader_check.py [--only R] [--record] [--tree D] ← every core and post program through AMD's OpenGL compiler offline
                                           (glc of RGA 2.13, gfx1032 = RX 6600 XT; vanilla stages and includes from the 1.21.1
                                           client jar; both hash-checked into mod/run/rga): VGPRs, scratch, code of both stages,
                                           compiler memory, seconds; fails past tools/fixtures/amd_shader_budget.json (+10 % code or
                                           memory per stage, new scratch, a program that does not compile, a new one over 128 KB or 1 GB). CI job
                                           “Компилятор AMD” on shader changes; --record --jobs 1 on an idle machine after a
                                           deliberate change, the reason in the PR
    see_through_check.sh [graphics] [start options] ← regression check of effects behind see-through blocks: a blast seen
                                           through a sandbox `pane` of each translucent block and stone, visible share 2 s
                                           after it by see_through.py, fails outside its bounds; run it on changes to the
                                           effects layer's depth or composites (SceneDepth, SeeThrough, fx_depth, see.glsl)
    see_through.py <dir> [--min/--max case=share] [--sheet] ← the visible share per pane case of those frames, and a sheet
    see_through_gl.py                    ← the see-through shaders on Mesa llvmpipe without a game: sampler counts (the pall
                                           composite within 16), fx_depth on synthetic captures, the half-resolution
                                           pair, the pall split with smoke in front of a far pane; exit 1 on a failed check
    nuclear_volume_warmup_gl.py           ← Mesa cold-cache nuclear volume timings for the pall and wall programs: batch (one frame), staged
                                           (one draw per loading frame, including both wall composite targets), cold (unprepared burst). dust_wall_gl.py runs staged
    dust_wall_gl.py                      ← the young nuclear dust wall's shaders on Mesa llvmpipe (see_through_gl.py's
                                           harness): thin foreground, hand and pane in its composite, history at a new
                                           occluder and on an uneven lattice, the march at the world's edge, straight down
                                           and on a valley floor; exit 1 on a failed check; run it on dust wall shader changes
    dust_wall_contract_gl.py [checkout] [case ...] ← Mesa GL contracts for depth ordering and reprojection.
                                           Use motion for camera translation/rotation, edge for slopes and convex corners, skin for the march reaching the
                                           wall's dense face cell by cell after a thin span.
                                           dust_wall_gl.py runs every case.
    wall_layer_identity.py               ← fixed-seed llvmpipe comparison with fixtures/wall_layer_7de91b6d1.glsl, run by
                                           dust_wall_gl.py. Checks light, depth and clipped transmission across 672 cases.
    pall_single_gl.py                    ← compares the general and single-burst ray in six llvmpipe scenes, including
                                           haze, soot and a populated cap with a missing-body negative control. Run by dust_wall_gl.py.
    dust_wall_split_probe.py             ← the march's two intervals against the readers' prefix on analytic media: a reader in
                                           thin haze in front of a dense wall, with and without a clear gap (the spires),
                                           with the gap-only split as control. Run by dust_wall_gl.py.
    dust_wall_ray_cost.py <checkout> [readbacks.npz] ← llvmpipe timings and optional RGBA32F readbacks for the pall ray pass
    nested_kwin.sh                       ← nested KWin for the client: no window and no host sound, its own D-Bus bus without starting services
                                           (nested_kwin_bus.conf) and XDG directories; takes the GPU lease and the client lock
                                           (gpu_lease.sh); exit 75 means whoever holds the GPU or Artem's game running (an exclusive client never starts beside it); with INCOMING_SOFT=1 a CPU session
                                           (soft_gl.sh): no lease or lock, socket wayland-incoming-soft-…; with INCOMING_GPU_SHARED=1
                                           a vanilla client on a shared GPU slot (its caller's slot lock, socket wayland-incoming-shared-…)
    soft_gl.sh                           ← vanilla clients, sourced: vanilla_display (shared GPU slot, else CPU at once), CPU rendering (Mesa llvmpipe): GL vendor/thread/heap
                                           environment, memory ceiling and admission, CPU display (nested KWin on a
                                           wayland-incoming-soft-<name> socket, else xvfb-run); details: client-scenario.md, “Vanilla rendering”
    laptop_job.sh [--gpu|--light|--soft] [--full --acceptance] <name> -- <command> ← heavy job on the host's laptop: its own temporary systemd service
                                           (not in the Claude group), the laptop does not sleep, everything of it is stopped on exit; no queue;
                                           --gpu [--wait] takes the GPU lease of its session (another session's: refusal 75; with
                                           --wait, in the lease queue) and waits until Artem's game is closed; his game started during the
                                           job (polled every second) stops it: “Artem's game started at T: job stopped”, exit 75;
                                           at the end, “laptop_job <name>: exit N”; --status: what runs and who holds the GPU;
                                           the command runs in the job's own unit: environment set on an outer systemd-run
                                           does not reach it (a script re-invoking itself must pass its mode as an argument);
                                           --soft: a vanilla client (shared GPU slot or CPU), no lease, CPUWeight=idle, MemoryMax from soft_gl.sh, no swap;
                                           every job has a memory ceiling (--ceiling <mode>): 10 GB + 4 GB swap, --full (whole pack) 20 GB,
                                           --full only with --acceptance (a final take: refused with 2 without it, each counted as a
                                           `take` row of its task in the load log),
                                           from measured unit peaks; past it the unit stops itself and the output says “memory ceiling …
                                           reached”; INCOMING_JOB_MEM/INCOMING_JOB_SWAP for one job; without a sleep inhibitor where none is allowed (a server);
                                           every job runs in agents.slice (laptop-agents: one ceiling for all agent work, rigs.md); a job
                                           killed because the slice is full says “agent work memory … is full”; TMPDIR: its own on disk, removed at stop;
                                           TERM/Ctrl+C/HUP on laptop_job.sh, or its systemd-run killed, stops its service (the client never outlives the command)
                                           task load: at stop (ExecStopPost `laptop_job.sh --account`) the unit's CPU from its cgroup
                                           goes to the load log (`cpu` row, task = DELEGATE_TASK set by `delegate`; job and task
                                           names only, never the command's arguments); laptop-agents mission-load sums it;
                                           A caller still waiting in the GPU queue dies with the agent's session cgroup at turn end
                                           (even setsid/nohup): start the wait in its own unit, `systemd-run --user
                                           --unit=incoming-wait-<name> --collect -p WorkingDirectory=$PWD --setenv=PATH="$PATH"
                                           --setenv=HOME="$HOME" --setenv=XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR"
                                           --setenv=DELEGATE_TASK="${DELEGATE_TASK:-}"
                                           -p ExecStopPost="\"$PWD/tools/laptop_job.sh\" --account" bash -c '…'` (its own CPU, such as
                                           Gradle preparation in the wrapper, then counts to the task), and
                                           name that unit in a mission's WAITING. After the job the lease stays with the launching
                                           tree for 10 min: run `tools/gpu_lease.sh release` from that tree
    gpu_lease.sh status|take [--wait]|release ← GPU lease: with a picture, one session (the owner is the delegated task,
                                           DELEGATE_TASK, else found by the tree wt-<S>…); a client on the GPU is one (the lock is held by nested_kwin.sh); another
                                           session: refusal “GPU held by …, since …”; a live nested KWin without the lock (the script before
                                           the lease) also counts as busy; running work extends it, without it expires in 10 min; Artem's game
                                           is artem_playing (java in his instance's folder or in the Prism group); --wait:
                                           an honest queue (FIFO): a free GPU goes to the first waiter; a holder past its turn (INCOMING_GPU_TURN, 30 min) with a waiter queues its new takes and yields after INCOMING_GPU_YIELD (90 s) without work; status: who holds it and
                                           who waits; at most INCOMING_GPU_QUEUE_MAX (2) other sessions wait, a newcomer beyond is refused (75);
                                           a nested KWin on a wayland-incoming-soft-… socket is a CPU session, not a GPU client;
                                           shared slots (gpu_slot_take, INCOMING_GPU_SLOTS 3, `$LEASE.slot<N>` locks plus a shared hold of the
                                           client lock, so any exclusive client, also an older script's, is refused by the kernel): vanilla
                                           clients on the GPU beside each other, never while the lease is held or queued for, never waiting
                                           (busy admission is a CPU fallback); the lease side sees them as busy;
                                           while a client holds the lock, each renewal appends a `gpu` row (utilization) billed to
                                           the client's DELEGATE_TASK to the load log (DELEGATE_LOAD_LOG, default
                                           ~/.local/state/delegate/load.tsv), only while that client still holds the lock;
                                           test: test_gpu_lease.sh
    logtime.py                           ← time of Minecraft log lines for digests of laptop tasks
    free_port.py                         ← a free port on 127.0.0.1 for check servers (stress.sh, mp_scenario.sh)
    rig_procs.sh                         ← processes of checks in their own sessions (source from stress.sh, mp_scenario.sh): the script's exit kills them with the JVM
    probes/                              ← review probes kept as regression tests (run by test_gpu_lease.sh, test_sandbox.sh, test_stray_clients.sh)
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
                                           replay — replay of a Flashback in the directory of tools/pack_dir.py of the build (zentixua/open-skies-friends) with the recording mods and quicksave: saved, opened and played back — the projectile's frames and the places where it vanished; rewinds backward and forward through strikes and the nuke — without live explosions and flashes
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
                                           for the sound audio.wav (prod_client.py --video, --size); diagnostics only (black,
                                           torn, frozen frames: rigs.md), clips come from Flashback's export
    clip_check.py <video>…               ← every frame of a delivered clip: black, torn and frozen frames, constant frame clock,
                                           whole file, size, fps, audio length and peak (exit 1 on any); test_clip_check.py (CI, rig job)
    stress.sh                            ← load bench (cloud, xvfb): a dedicated server with a director (src/devtest/.../stress) and three players
                                           without windows; salvos of 30, exit/entry, Nether, nuke, “Abort”; the summary is the STRESS lines in the log
    build_sounds.py [sections]           ← all sounds: CC0/CC BY recordings from Freesound (cache tools/.sound-cache) + synthesis
                                           synth_mod_sounds.py; writes sounds.json and SOUND-CREDITS.md (numpy, scipy, soundfile);
                                           blasts and motor loops — from the originals without loss, loudness by EBU R128 (the blast's angles
                                           are exactly `BlastMix`; loops are quieter than their own projectile's blast; evenness is checked)
    audition_sounds.py --near WAV --far WAV --out DIR ← offline near/far, small-speaker and overlap/headroom check;
                                           docs/sound-audition.md; read-only inputs, no runtime or asset changes
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
    trailer/icon_from_frames.py <frames> <folder> ← the old Shahed-against-the-sky icon from frames (superseded by the pixel icon)
  docs/DESIGN-nuke.md                    ← nuclear strike design
  docs/design/universal-integration.md   ← integration roadmap (events, permissions, claims, data, compat), status table
  docs/design/time.md                    ← time principle proposal (clocks of every consequence, pack conditions)
  docs/guide/                            ← player guide in English (pages by topic, pictures in img/),
                                           docs/guide/ru/ — the same in Russian
  docs/store/                            ← Modrinth and CurseForge page texts
  docs/media/                            ← pixel icon (icon.png 512, icon-400.png; incoming.png is the wide pixel logo), banner.jpg
                                           heading README and pages, the hero.webp loop, frames for the gallery
  .github/workflows/build.yml            ← CI: what changed → build, unit tests (4 JVMs), GameTest in parts, summary; jar in
                                           artifacts; release; an already checked tree takes that run's jar (tools/ci_reuse.py)
  .github/PULL_REQUEST_TEMPLATE.md       ← PR description template: what changes for the player, how it was checked
  .github/workflows/publish.yml          ← Modrinth and CurseForge: release runs by itself after a release build;
                                           manual draft (hidden Modrinth draft), submit, release (re-send);
                                           fields — docs/store/modrinth.json
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
  unknown paths get the full check, and a manual run (release) always runs everything. The mod's checks and changes to
  `tools/sandbox.sh`, `tools/soft_gl.sh` or `tools/gpu_lease.sh` also start the sandbox client (“Песочница”, `sandbox.sh check`). Shader changes (and
  `amd_shader_check.py`, its test and budget) also run “Компилятор AMD”. The jar is the artifact
  `incoming-jar`.
- A push to `main` and a release do not recheck a tree that already fully passed a successful run of this repository
  (a PR merged without `main` moving gives the same tree): `Что изменилось` finds the tag `tested-tree-<tree>`
  (`tools/ci_reuse.py`), and the job “Сборка из проверенного прогона” takes the jar from there. If `main` moved, the full
  check runs.
- Release (a public step: AGENTS.md): bump `mod_version`, write `docs/releases/<version>.md` (in English;
  no HTML comments, one line per paragraph, the compatibility line first), merge into `main` and run `build`
  manually on `main` with `release=true`: after all checks, the workflow releases `v<version>` with the jar (friends take it
  from there), and `publish` then uploads the same jar to Modrinth and CurseForge by itself (step `release`; a manual
  `publish release` re-sends a version and skips stores that already have the jar). If `main` has moved on from the commit checked in the game, a branch `claude/release-…` from that commit with only
  the notes, and the run on it. After the release, the open-skies-friends build takes the jar by itself (its `update` workflow
  runs there every 3 hours; to run it at once, start it manually on `main`), checks it and releases it; players with
  auto-update get it on their next launch. Create, Sable and Aeronautics are pinned in the pack: a new version of them is a PR in
  open-skies-friends together with the pins here (`gradle.properties`, `tools/fetch_runtime_mods.py`).
