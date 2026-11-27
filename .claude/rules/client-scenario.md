---
paths:
  - "mod/src/devtest/java/ua/zentix/incoming/scenario/**"
  - "tools/client_scenario.sh"
  - "tools/prod_client.py"
  - "tools/x11_record.py"
  - "tools/sandbox.sh"
  - "mod/src/sandboxAgent/**"
---

# Headless client scenarios

## Page media
- Use a fixed camera for each page clip: no keyframe motion, pan or zoom unless Artem explicitly requests it.
  The mod's own effects, including impact camera shake, stay on; page media shows how the mod normally works.
  Record with the normal full pack and shaderpack, then use Flashback's native keyframe and export;
  derive stills from that same exported take. Do not disable mod effects in the filming copy.
- Factory shots are near ground level, looking slightly up, with about half sky and half target. Launch several
  missiles roughly 1.5 km behind the camera, through a waypoint above it, with a few dozen blocks of target spread.
  The clip must show missiles against the sky over the camera, their approach and impacts at different points.
  Before recording, check sight lines against the actual world blocks across the impact area: foreground houses,
  trees or walls must not hide the impacts. Inspect the real client view as well as the rays.
- Each nuclear clip uses one fixed eye-height camera on low natural ground, including the foot of a rise when
  requested, never a hilltop or roof. Use Artem's requested target, yield and distance; verify the real view before
  launching. Choose framing once for the flash, fireball, rising cloud and visibly approaching wave; record through
  the wave reaching and covering the camera.
- Store gallery publishing reconciles the configured set, never appends duplicates. Keep each image and shot once,
  with a caption describing what is actually visible; a repeated draft run must leave the same gallery.
- Before merge, a fresh visual session checks the actual stills and clip frames against Artem's shot list, including
  the camera, approach, impacts, nuclear phases and gallery. A successful export alone does not establish this.

## Scenario lines
Client scenarios log `SCENARIO …` lines (sounds, fps, flash; `SCENARIO far`: distant visuals and last distant roll;
`SCENARIO nuke-sound`: nuclear echo, rays, reflection delay/level, tracks, start anchor `by sound|tick`; burning city,
own OpenAL sources with obstruction, per-tick `FireWatch` cost). Use these and frames to check look/sound.
`commands` scenario (`incoming.commands`, semicolon-separated steps, `ScenarioCommands`/`CommandPlan`): commands,
`wait:N`, `shot:<name>`, `hud:off|on`, `wait:nuke` (wait for detonation packet), `wait:front` (once: later steps wait until the newest detonation's
in-game front has passed the ground under the camera; after `incoming nuke now` use it alone, since `wait:nuke` after
the command's own gap waits for a second detonation), `wait:blast` (wait for next server
explosion, at most 3600 ticks), `maxyield:<kt>` (server yield ceiling), `burnable` (to the most flammable land
within 4 km, 200 blocks up), `skip:<s>` (time skip to that many seconds after the last detonation, as scenario
`firestorm`), `storm:on|off`, `frames:<name>:<count>:<yaw>:<blocks>` (consecutive frames while turning/moving, each
with its `SCENARIO frame` pall line: trails and shimmer), `site:<biomes>` (fresh ground zero whose 2 km ring and 16 km southern
camera line are mostly these biome ids/`#tags`, from the generator without loading, ≥ 40 km from earlier sites; teleports 150
above it facing north), `wave:<x>` (CUSTOM preset, `nuclear.wave_speed`), `pall:<OFF|LOW|MEDIUM|HIGH>` (client dust quality),
`pair:<name>` (frame, then `pall_<name>_off` 3 ticks later with the pall off: what the pall pass draws). `pause:on|off` (the
single-player pause without its menu, vanilla's "Game Paused" title in the frame; client ticks still count; `SCENARIO
pause` logs whether vanilla paused), `sleep` (the player lies down through vanilla's check in a bed placed at its feet,
which stays: night and a non-spectator player; the day must run for vanilla to skip the night, logged as `SCENARIO sleep
finished`), `leave` (leaves the world as "Save and Quit"; the run ends once Flashback has written the replay it recorded,
`SCENARIO left: replay …`: a filming take sets `automaticallyStart` and `quicksave` in the copy's Flashback config). Every shot with a
detonation also logs `SCENARIO shot … nuke:` (`NukePall.describe`) and `… wall:` (devtest `WallProbe`: model time, dust edge −
shock radius, wedge/top, fire zone and flame, frame sky/exposure, camera biome, and the head colour `NukePall.dustLight` gives on
the camera line with its soot share, sand share and fire term). With the shaderpack and no DH, ground beyond render distance is
fog/clouds: frames of a wall kilometres away show it against sky, not against ground. Detailed city for client-fire measurements: `INCOMING_CITY=N@tick` (`FireCity`:
surface section plus N − 1 below per client chunk, with global palette, client-only).

## Standard dev client: the look loop
The loop of the look skill (section 4; docs/dev-pipeline.md, section 2): ModDevGradle's `runClient` (the mod with
Create, Sable and Aeronautics, no pack) kept up in a saved world, `tools/dev_client.sh` as the agent's hands.
- `start <save> --from <world dir>`: the world is copied once into `mod/run/client/saves/<save>` (`cp --reflink`: a
  4.7 GB Silo copy in 4 s, no extra disk) and kept; later starts reuse it. `-PquickPlay` opens it, `-PhotSwap` runs
  JetBrains Runtime (Gradle toolchain, foojay) with `-XX:+AllowEnhancedClassRedefinition` and JDWP on a free loopback
  port. The display is `soft_gl.sh` `vanilla_display` (shared GPU slot or CPU, `laptop_job.sh --soft`); its unit
  ceiling is the heap plus 6 GB: with a 4 GB heap a real map with a 100 kt burst peaked at 8.85 GB, and heap + 2 GB
  (the sandbox's) was killed while loading the world. A map made in the pack carries experimental datapack settings:
  vanilla asks once, `start` confirms it (`press`).
- Keys: XTEST events (xdotool) never reach the game's Xwayland window inside a nested KWin `--virtual`, so the devtest
  `DevHands` reads one-line files from `mod/run/client/dev/inbox` (`incoming.dev.inbox`, set by `-PhotSwap`): `cmd`,
  `reload` (answered when the reload has finished), `shot`, `hud`, `press <button>`, `dw <key>=<value>` (the dust wave's
  diagnostics, as the scenario's `dw:` steps; `rung=N` pins the pall's rung here too), `age` (the newest detonation's shown
  age, front radius and eye distance: a hold at a front distance, since `/tick step` paces with the frame rate), `skip <s>`
  (the newest detonation that old: the server's game time moves, the client rebuilds as on a seek; a skip across the
  show's end reads the span at vanilla pace, since the server dates a late end that way, while a skip after an ended show
  leaves the world's clock where it was, a 24-hour day's aftermath: skip to 1150 s, wait for the end, then skip on) and
  `watch on|off` (a one-frame dropout logged as `DEV pop`; it stalls the GPU every frame); it logs every opened screen
  (`DEV screen`). The bare `reload` reads `build/resources`: a shader edit in `src` needs `swap`.
- The scene: `cmd 'tp @s …'`, `time set`, the burst once, `tick freeze` at the moment, `tick step <ticks>` to move it.
  A server burst leaves its ruins in the copy (the client saves it on `stop`): an untouched map is a new `<save>` name
  with `--from` again.
  The freeze holds the nuclear show's age (`ClientNuclear`: eye-following state still updates), particles and the
  conventional strike's clouds, smoke, shake and heat shimmer (`ClientTicks`, `client.md`). On a CPU client (5 fps) a
  frozen frame can still change once: the frame-budgeted smoke preparation finishes the held age's snapshot late; judge a
  hold by the per-second `SCENARIO far blasts` line (`hot [r h dT]` and puffs stay fixed), not by one frame pair.
  Config values (`mod/run/client/config/incoming-server.toml` and `-client.toml`) are reread when the file changes;
  `max_yield` is a config value there, not an `/incoming rule`.
- Measured 25.11 (laptop, shared GPU slot, Silo copy at 3514 73 −2298, a held 100 kt front): start 44.6 s to the
  player in the world; a shader edit to its frame 37.2 s cold, 16.6 s warm (the F3+T reload 27.8 / 13.8 s of it);
  a Java method edit hot-swapped and running in 3.0 s; two held frames 3 s apart differ by 0.19 of 255 on average.
  A whole Silo take per change took 14 min 31 s per spot.

## Effects sandbox: frames during work; full pack under Iris only for one comparison with 3.4.1
Rule ([visual/audio checks](../../docs/dev-pipeline.md#4-visual-and-audio-checks)): the look loop is the dev client
above; the sandbox gives frames/WAV at exact effect seconds in a flat world, before/after against `main` for the reviewer.
Sandbox is an MDG dev client with `devtest` (like JEI's `clientGameTest`); custom parts are only frame-to-file at a
specified tick and chatless commands (Claude cannot see screen; NeoForge has no screenshot client tests,
NeoForge #3624). Full-pack Iris client (`look_gate`, `prod_client.py`: ~3 min to first frame, 10–25 min per scene set,
the nuke 5–6 min per scene) records only the one-off 3.4.1 comparison, in one session until scenes reach its level;
not per PR. Vanilla frames on a shared GPU slot or the CPU (below, “Vanilla rendering”), any number of sessions; one exclusive GPU session at a time. Tool-improvement options (own replacement agent → HotswapAgent
on JBR; scenarios as sandbox command lists): [evaluating tool changes](../../docs/dev-pipeline.md#7-evaluating-tool-changes).
Scene bench (`tools/stand`, Artem's decision 24.10) differs from sandbox: sandbox gives work-in-progress frames in
this tree's dev client; bench runs identical real strikes with any Incoming 3.4.1+ jar (release/CI artifact) beside
accepted release, measuring frame time and frames; merge condition for a PR that can change frame cost (render/FX
code, shaders, particle or draw counts; Artem, 30.10) is frame time at 100 strikes (status “Стенд сцен”), run once on
the final head; other visual changes (colors, textures, sounds, text) skip the bench. Scenes compared with release belong in bench `scenes.json`, not sandbox. During work, sample bench
with `--packs none --scenes 100`.
Laptop measurement 22.10: sandbox start 19–24 s (build 5–8 s, JVM-to-world client 13–15 s); with Iris/shaderpack 39 s
(client 30 s); method-body/shader edit → frame at effect second 3 takes 10 s (Gradle/replacement 5–7 s), also 10 s
with Iris.
- `tools/sandbox.sh start [shaders] [dh] [gpu]`: vanilla (shared GPU slot or CPU, “Vanilla rendering”) unless `shaders`, `dh` or `gpu`; mod-only client (sable-companion alongside: not embedded in dev; `shaders`
  adds Sodium/Iris/host shaderpack from instance, `dh` Distant Horizons), flat world (`Sandbox`: bedrock, stone,
  dirt, grass, grass top y 64; noon, no weather/mobs; same world every launch), headless in nested KWin, as laptop
  job (unit `incoming-job-sandbox-<tree_id>`); lives until `stop` or `INCOMING_SANDBOX_IDLE` seconds (1800; 0 never)
  without a command (`fx`/`swap` mark `fx/active`); in GPU mode also until Artem's game starts
  (`fx/stopped` says which; game running means immediate exit 75); other jobs cannot stop it. GPU mode, one GPU (`tools/gpu_lease.sh`): client (`nested_kwin.sh`, used by every client) acquires its
  session lease (owner from `wt-<S>…` tree) and GPU-client lock, holds/renews while alive; `start` requests them
  before building. Another session holds GPU: exit 75, line “GPU held by <owner> (<tree>), since <time>”; client
  already running (even own session's sandbox in another tree/look gate): exit 75, “client running on GPU …”;
  live nested KWin without lock (pre-lease script client or bypass launch): exit 75, “client running on GPU without
  lease …” (23.10 ZM sandbox ran under a “free” lease and FX client displaced it; `nested_kwin.sh` refuses second
  KWin on same socket, which used to crash first); `--wait` joins lease queue: fair FIFO (`$LEASE.queue`), first
  arrival gets released GPU; without `--wait` a queued free GPU cannot be taken (“waiting: …”); queue does not
  evict holder, interrupted waiter removes itself; holder/waiters via `tools/gpu_lease.sh status` (23.10 without
  queue, `start --wait` waited ~35 min while V3, L1, WV successively took GPU). A session restarts own sandbox
  without waiting; GPU-free jobs (GameTest/build) cannot stop it. Failed startup releases a newly acquired lease.
  Finish visuals with `stop`, then `tools/gpu_lease.sh release`; otherwise lease expires after 10 idle minutes.
  Why lease instead of job queue/memory rule: rule failed (23.10 three GPU jobs at once, sandbox restarted five
  times), process semaphores (`sem`, `ts`) do not hold GPU between frames while editing.
  The client runs `devtest` without Create and Sable: a GameTest holder that calls them directly stops it at launch
  (`gametest.md`, optional mods). `tools/sandbox.sh check` starts the same vanilla client on a CPU display in its own
  shell (CI “Песочница”: Xvfb; as with `start`, Gradle prepares outside any ceiling and, where a systemd user manager
  exists, GitHub's runner included, the client alone goes into a `laptop_job.sh --soft` job, whose heap + 2 GB ceiling a
  cold decompile overruns; refused while this tree's sandbox runs), waits for the world (`INCOMING_SANDBOX_CHECK_LIMIT`,
  900 s), takes one `blast drone` frame and stops; laptop, warm: build 5 s, world 69 s, done in 84 s (CI: 68, 105,
  196 s). That the check catches a holder calling Sable: `tools/probes/sandbox-check-catches-holder.sh` (by hand, two starts).
  `shaders` without Sodium/Iris jars, `dh` without Distant Horizons jar in sandbox mods (Gradle takes instance mods
  by patterns, silently skips missing) exits 2 explaining missing mods/Gradle source, instead of recording without
  Iris (`tools/test_sandbox.sh`). Sandbox installs no mods into pack: own `run/sandbox`.
- `scenery grass|paved|forest`: identical dust-source comparison geometry, 97×97 platform, stone paving or grass,
  forest of identical oak blocks (permeable canopies, trunks, central clearing). Before switching remove `house 0`,
  afterward `wait 80` for client block delivery. Sandbox world only.
- Commands: `tools/sandbox.sh fx '<command>'` (file in `run/sandbox/fx/inbox`, result `*.done`) or `/fx …` in chat;
  parser/list `SandboxCommand`. `blast <weapon> [seed] [height]`: client-only explosion packet through same entry
  as server (height above grass, airborne point; roof impact at `house <height> [half-width]`, solid stone brick
  beneath point; `house 0` removes). `raid [count] [radius] [seconds-between] [weapon] [water]`: that many packets
  (default Shahed) within circle at interval (default 16 over 45 s within 120 m), frame inside many strikes' smoke;
  `water` uses pond like `blast` (Grad water salvo, scene `grad-day`: `raid 12 15 1 rocket water`,
  `--cam '30 14 0 6'`); `SANDBOX shot` logs fps, layer GPU time (`FxLayer.describeTiming`), smoke fill
  (`FarRenderer.describeSmoke`) (`ClientHooks.blast`): game look/sound, without server crater/fire/debris, ground
  intact, repeatable explosion. `real <weapon> [deck|concrete <m> | paved]`: real warhead explosion
  (`Warheads.detonate`) on untouched ground each time (320-block +X steps; other effects return to original point;
  `deck` hits bridge center: stone deck this many meters above water channel along X, slab breach; `concrete`
  same concrete deck; `paved` meter of black concrete on ground; `town [n]`/`field [n]`: town block or grass with
  white/black concrete marker pairs along the street, faces 4.5–59.5 m from the camera `cam 15 1.6 0 1.6`, n ≤ 12 rounds at
  once at the side-street crossings, none in the marker street: the eye inside a B-2 ×12 salvo's dust; the shot log's
  `veil set` lists the veil Gaussians, from which the law's transmittance to each marker follows). `nuke <kt> [air|ground]`: `/incoming nuke now`
  6 km away (`… also <dx> <dz> <delay s>`, up to 15 groups: more detonations of that yield (dx, dz) m from the
  first, delay s after it on the client's world-tick clock: crossing waves, a nuke into old haze); `airburst`: SAM burst 30 m above point; `sam`: loaded SAM at point and Shahed from 1400 blocks north
  through it (like probe `sam-side`), time from interceptor launch rail exit (trail, rail-exit cloud, motor;
  `track on` follows it), `clear` removes SAM; `sam` starts at gameTime 6000 (same branch-comparison air, leaves
  time of day unchanged). `launch`/`flight <weapon> [from]`: real launch (`/incoming salvo … from`; final `own`
  means on player's behalf: own projectiles, screen list/markers), time from first projectile motion or nearest
  approach to point (Shahed flyby from 400 blocks ~45 s: launcher/catapult). `flight` crosses point on straight
  route: launch `from` blocks south (default 600, 50–5000), target same distance north; Shahed/missile/Lancet use
  point itself as `via` waypoint (`SandboxCommand.order`), otherwise loop behind shooter, pass 200–350 blocks from
  point and sandbox times out (“effect did not start”); Grad/B-2 ignore waypoints and fly straight themselves.
  `spent <weapon> [height]`: spent booster above point (default 20 m), time from motor burnout (hot nozzle: launch
  rarely shows it, booster tumbles in own trail). `fire [side]`: square netherrack fire (`clear` restores grass).
  Charge from weapon spec (no arbitrary charge: `BlastSource` supplies visual charge from packet weapon); B-2 bomb
  cannot detonate without server. `cam <distance> [height] [azimuth] [gaze]`: camera relative to point (azimuth like
  player yaw: 0 south, 90 west); `track on` follows `launch`/`flight` projectile; `zoom <k>` zoom ×k (FOV / k last,
  like Ok Zoomer; 1 none); `time day|night`; `overlay on` model values over frame (effect time, distance, last
  explosion, distant explosions, ear sound with pressure/echo/hearing, fires, `describe` lines from same classes as
  `SCENARIO`); `clear` removes everything (`IncomingClient.resetSession`, like world exit; projectiles/fire).
  `stance stand|fly`: standing creative player with flight off, or spectator (default); check body/neck lean
  standing with `cam … 1.62 …`, then `wait 10` to land; in flight only impact tremor visible.
  `weather clear|rain`: own bench weather; afterward `wait 40` for client rain/humidity.
  `dw <key>=<value>`: one dust-wave diagnostic, as the client scenario's `dw:` step (`DustWallSteps`: `probe=-2` each ray's
  end, `dump=<name>` the march targets into `run/sandbox/screenshots`, `freezeage=1` a still wave; it stays set until changed).
- `tools/sandbox.sh shot <name> '<effect>' --at 0.1,0.5,1,3|none --wav 4 [--cam …] [--time night] [--overlay]`: one command
  from edit to frames: build/replacement (`swap`), `clear`, effect; screenshot is first rendered frame after target
  tick (`capture.json` `age` includes tick fraction), audio slice from `audio.wav` (48 kHz) from effect start
  (`audio.from` seconds since effect start; `nuke … near` with pre-front frames starts from first frame). Effect
  clock uses world ticks, paused during resource reload/menus. Each shot sets own time (`--time`, default `day`):
  sandbox daylight cycle disabled, so previous shot's night would persist. `time` sends client time immediately,
  ends once client receives it (no receipt in 2 s: `error`): server normally sends every 20 ticks; an effect
  immediately after command previously spent first second in old light.
  `--at none`: sound only (WAV and the take's `capture.json`; the shot's directory is replaced once the WAV exists). A shot
  name is its output directory: any take under that name, frames or sound only, replaces it; reuse a name only to redo that take.
  Shot names: one directory name (letters, digits, `_ . -`). A take without frames (`shots <name> none… <s>`, also the CPU frame
  shot's separate sound take) runs in a 160×90 window at 20 fps (`Sandbox.quiet`: `glfwRestoreWindow` first, the
  nested compositor keeps a maximized window's size; a frame take's `ok armed` and the sound take's result wait until the
  frame is its size again; a failed command after `shots` drops the armed take and restores it); sound does not depend on it:
  OpenAL mixes in its own thread and a voice later than 30 ms seeks to its offset (`OwnSources`). 20.11, `real missile`,
  camera 20 m, 40 s on llvmpipe: CPU 222–249 s → 47 s per take, same 45 s wall; per-second levels within 1 dB, band
  levels within 0.13 dB, onset within 12 ms of the full-window take (two full-window takes differ as much). On the GPU
  73 → 20 s.
- Artem's game detection for sandbox/look gate/`--gpu` jobs: `artem_playing` (`gpu_lease.sh`), java working in Prism
  instance directory or Prism group, not command-line path: with `MC_DIR` pointing at instance, sandbox mods share
  its path and `pgrep -f 'PrismLauncher/instances/'` matched sandbox itself (code 75). `start shaders` without
  instance shaderpack refuses, instead of running packless sandbox.
- Nuclear effects near camera in seconds: `nuke <kt> [air|ground] near [m] [dry]` (`debris` scene without new client; `dry`: savanna site): at
  fresh location, stone-brick house with glass panes/plank roof, glass-windowed plank house (dry fuel behind north windows, hay bales outside: `Sandbox.furnish`), three oaks
  (`ClientScenario.debrisEvents` layout); burst `m` north (default 2000, like `debris`); effect time (`--at`) is
  front arrival at houses by client detonation clock, frames allowed beforehand (from −10 s). `debris` camera:
  `--cam '60 15 0 5'` (60 south, 15 above ground, facing houses). Wave same as all client scenarios (sound-speed
  scale 1.0: `TestScale` sets it at same-JVM scenario server start, sandbox too): 15 kt front reaches houses 2 km
  away in ~4 game seconds; detonation same tick as command, whole scene ~6 game seconds. `/tick rate` cannot
  accelerate approach: client 1.21.1 uses `max(50 ms, mspt)`, can slow only (probe 23.10: server advanced 98 ticks,
  client 35, ruins preceded visual front). Differences from full scenario: oaks have regular block canopies
  (scenario `place feature` random shapes), flat grass ground. Fresh location each run, but threshold scatter
  (`BlockResponse.jitterAt`, position hash) identical there (x shift k·2¹⁶, z k·2¹⁰ cancel in
  `Long.hashCode(BlockPos.asLong())`), same detonation ID (9000: scenario detonation seed), same detonation game time
  (6000: world wind is game-time function `Wind.at`; epicenter ground loads immediately, detonation same tick
  rather than deferred; world clock rewound, previously scheduled old-effect block ticks wait longer, `clear`
  removes them), so same blocks break in same wind; other position-based randomness (trail noise, debris sampling)
  differs, as between scenario runs. `overlay on`: front relative to point/eye, pressure at point, debris/dust
  streaks (`NukeDust.describe`).
- Hot replacement: agent `ua.zentix.hotswap.HotswapAgent` (own source set `src/sandboxAgent`, excluded from mod jar)
  watches `build/classes/java/{main,devtest}`, replaces loaded classes after Gradle finishes writing (`fx/hotswap`
  number/result). `swap` reloads resources like F3+T (`fx reload`): own shaders/textures/sounds/language; sound engine
  restarts (`OwnSources` forgotten, `client-sound.md`, `audio.wav` restarted). `reload` ends when loading screen
  disappears and pause ends: single-player pauses during reload, screen takes another ~1 s to fade, effects
  started then stood still (“3 s” frame was ~2 s without debris tails). Without restart supports method bodies,
  their numbers/javac-inlined constants (`static final` primitive/string), new classes. `restart` (~20 s) needed
  for new field/method/lambda, changed signature (`fx/hotswap` “fail”, code 3), static initializer (arrays/tables,
  `static final` expression not recalculated), registrations (particles/sounds/packets/commands), mixins/AT/settings.
  JetBrains Runtime (`tools/sandbox.sh jbr` installs to main tree `mod/run/jbr`; `start` auto-selects and adds
  `-XX:+AllowEnhancedClassRedefinition` only if selected JVM supports it, ordinary JVM fails with flag) supports new
  fields/methods/lambdas, but new static fields remain zero/null, initializer does not run: also `restart`.

- `/fx surface <depth> [black|gray]`: saved partial damage on fresh covering patch, no explosion; checks fracture/
  chip size by depth (0 < depth ≤ 1); ordinary display with same server path/light.

## Vanilla rendering: shared GPU slot or CPU (`tools/soft_gl.sh`)
Vanilla frames (no shader pack, DH or Voxy): `sandbox.sh start` and `client_scenario.sh` default to `vanilla_display`,
which at client launch takes a shared GPU slot (`gpu_lease.sh gpu_slot_take`: up to 3 vanilla clients on the GPU at once,
nested KWin on `wayland-incoming-shared-<name>`, holding its slot lock and a shared hold of the exclusive client lock, so
the kernel refuses any exclusive client beside it) or, at once and without waiting, renders with Mesa llvmpipe on the CPU:
slots full, the exclusive lease held or queued for (frame-cost measurement, shader pack), an exclusive or unlocked client running, admission busy,
Artem's game, under 1 GB of free VRAM, no NVIDIA GPU or no KWin (netcup, cloud). `INCOMING_CPU_ONLY=1` forces the CPU;
`gpu` takes the GPU alone under the lease. The exclusive side sees slot holders as busy and waits (`--wait`) for them
to end: a measurement never shares the GPU. A shared GPU client stops when Artem's game starts: its whole process group,
slot freed (75, “Artem's game started … shared GPU client stopped”; a restart lands on the CPU); a CPU client keeps running. The client log line
`renderer: GPU, shared slot N of 3` or `renderer: CPU (llvmpipe): <reason>`; `sandbox.sh start` repeats it and the
sandbox writes `fx/renderer`. Measured 20.11 (laptop, warm, 1280×720): `blast drone 7` with 4 frames 3.7 s and 6 s of
CPU on a shared slot against 17.4 s and 107 s on llvmpipe; `nuke 15 near` with 3 frames 9.8–13.7 s against 52.5 s
(320 s CPU); VRAM 0.35 GB per client (desktop 1.6 GB of 8), unit memory peak 4.25 GB on the GPU (3.0 on the CPU).
Same frames: blast frames GPU against CPU mean |Δ| 4–7 of 255, luma within 1 (as between two GPU runs: 4.6); the
llvmpipe `nuke … near` frames lag the GPU's front by 80–90 m (`R 2028` against `R 2107` at 0.1 s), and llvmpipe sound
takes of `real missile` are 5–11 dB louder at 2–6 s than the GPU's (both at 20 and 60 fps): prefer the GPU when they differ.
No lease, no GPU queue; the client runs in a `laptop_job.sh --soft` unit (idle CPU
class, memory ceiling heap + 2 GB without swap, a CPU client not stopped by Artem's game); `client_scenario.sh` puts it there
itself after Gradle writes the launch script (`rigLaunch`, outside the ceiling). Refused (75) when available memory,
less the unused ceilings of running CPU clients, cannot hold this ceiling plus a 4 GB reserve; `laptop_job.sh` checks
under a lock until the unit exists, so simultaneous starts cannot both pass. Gradle preparation (`rigLaunch`, `swap`)
is ordinary build work outside the ceiling, like any build or GameTest. Units and sockets carry `tree_id` (tree name
cut to 40 bytes and a path hash). The picture is the GPU's (same GL core pipeline; the client logs `GL info: llvmpipe`); fps, layer GPU
times and the pall's budget are not frame costs: frame cost belongs to the GPU and `tools/stand`.
- Laptop: nested KWin composes with QPainter (`KWIN_COMPOSE=Q`), Xwayland without glamor (`XWAYLAND_NO_GLAMOR=1`),
  the client takes Mesa's GLX (`__GLX_VENDOR_LIBRARY_NAME=mesa`): GLVND otherwise hands it the NVIDIA vendor that
  Xwayland names, and NVIDIA ignores `LIBGL_ALWAYS_SOFTWARE`. The socket is `wayland-incoming-soft-<name>`: its name is
  how `gpu_lease.sh` tells a CPU session from a GPU client (kwin_wayland has file capabilities, its environment is
  unreadable). Elsewhere (netcup, cloud) `xvfb-run`.
- llvmpipe draws 3–10 frames/s: a frame lands up to 0.3 s after its tick. While shot frames are due, the sandbox slows
  the world (`Sandbox.paceFrame`: `tickRateManager().setTickRate` for two frames per tick, not `/tick rate`, whose
  feedback prints into the frame's chat), so frames land within 0.02 s of their second (`blast drone` at 0.1, 0.5, 1 and 3 s:
  ages 0.102, 0.521, 1.005, 3.009 s; unpaced 0.163, 0.524, 1.292, 3.061 s). Pacing follows the armed take, not the
  current rate: effects that `clear` first (`nuke … near`) and fast stretches that raised the rate keep it. Only the
  last `Sandbox.PACE_LEAD` (2 s) before a due frame is slowed; farther the world runs at 20 ticks/s (paced whole, a
  nuke frame at 130 s took tens of minutes at 1–3 frames/s). A frame is taken at the first render whose age (tick +
  partial tick) reaches its second, never at a tick boundary: at whole ticks a GPU 30 fps clip advanced 25–28 ms per
  frame with a 45–145 ms jump every half second (each PNG write stalls the render thread ~0.1 s while game time runs),
  and everything moving lurched at ~2 Hz, read as flicker. GPU takes slow the world to `GPU_FRAMES_PER_TICK` (48)
  renders per tick; check `capture.json` ages (steps of 1/30 s) before judging motion in a clip. `shots` takes a time of day per frame
  (`<seconds>@<day time>`, e.g. `130@12500,130.5@12550`): a dusk strip in one take; the `SANDBOX shot` line logs it. A slowed
  take's sound is not real-time: `shot` records sound in a second take of the same effect at 20 ticks/s from the first
  frame's second (`shots .wav-<name>-XXXXXX none@<s> <wav>`, a fresh `mktemp` staging directory), cuts the WAV from it and writes
  its timing into the frames' `capture.json` (`audio.take`); the effect's deadline grows by 20 s per game second.
- Frame comparisons between takes: a plain `nuke` detonates at the take's game time and the world wind is a function of it
  (`Wind.at`), so main and branch takes in different orders drift the cloud by pixels; equality claims (noon/night) need
  the law shown equal by a test, not pixel identity. A frame's PNG is written on the render thread before the take moves
  on, so the files are whole when `shot`/`fx` returns. A sky-only frame for masks (same camera
  and time, `blast drone`) differs from the nuke frame beyond render distance (the pall's terrain floor draws ground there)
  and wherever vanilla clouds drift (their offset follows client ticks): set `renderClouds:"false"` in the sandbox
  `options.txt` (every start makes sure the line exists, `client_options`) and cut the mask at the frame's own horizon.
  Every take states its second 0: `capture.json` `clock` and each `SANDBOX shot` line ("t = 0 at effect start", or for
  `nuke … near` "front at the houses (burst N s earlier)"); read frame times against it. Server ruins and pall state accumulate with each nuclear take, and
  the default heap (“Vanilla rendering”) can run out on the second one: for nuclear takes `export INCOMING_SOFT_HEAP=4g`
  (or `5g`) before `start` and keep it set for every `restart` (a restart starts the client again from the current
  environment), restarting between nuclear takes.
- Mesa caches compiled shaders in `~/.cache/incoming-mesa-shaders`, shared by every tree: a cold llvmpipe compiles the
  nuclear volume shaders for 140 s (laptop) or 74 s (netcup) before `ready`; a cold blast's first frame takes seconds.
- Measured 20.11 (flat sandbox, 1280×720, `blast drone`): laptop (16 threads, `LP_NUM_THREADS` 8) start 240 s cold,
  shot 18 s for frames alone, 36 s with the sound take; unit memory peak 3.0 GB at `-Xmx3g`. netcup (12 vCPU beside
  three game servers) start 138 s cold, shot 40–45 s, peak 3.0 GB at `INCOMING_SOFT_HEAP=1536m`.
- A host with little free memory (a server beside game servers): Gradle with `-PnoRecompile` (binary Minecraft:
  Vineflower's decompile needs ~4 GB and was OOM-killed under a 3.5 GB ceiling), mods from `tools/fetch_runtime_mods.py`
  (`-PmcModsDir=run/ci-mods`), no Gradle daemon, `INCOMING_SOFT_HEAP=1536m` (ceiling 3.5 GB); one client at a time.
- A CPU client started on netcup is stopped by its job's end: `tools/sandbox.sh stop` or `tools/dev_client.sh stop`
  when the work is done, and the sandbox stops itself after its idle limit; `laptop_job.sh` stops its unit when its caller ends (TERM, Ctrl+C, HUP of
  an `ssh -t` link, systemd-run killed). A plain `ssh vps cmd` that drops sends nothing: the job ends by its own
  deadline. `tools/stray_clients.sh --host vps` lists clients that outlived their job (report only); a stray that is
  not yours is reported to the lead, never killed.

## Pitfalls
- Server may lag client ticks during world generation: client-scenario frames anchor to events, not a counter.
- Scenario frames compare across runs: with `incoming.scenario`, nuclear seed derives from ID (`NuclearWarhead`),
  wind from detonation game time (`Wind.at`), pall detail from setting without GPU budget (`PallPass.Detail.pin`:
  llvmpipe budget would lower to LOW). A frame-time run measures the game's cost only with the ladder on
  (`-Dincoming.pall.adaptive=true`): pinned, a 1 Mt passage at 3 km held the pall at 30–59 ms a frame, a cost the game
  steps down from within a second. While a detonation is live the scenario logs `SCENARIO nuke-layer` every half second
  (layer GPU by pass, smoke fill, near dust); the `far` line exists only once distant blasts do.
- A single-player frame-time run puts the integrated server on the client's CPU. At the Silo city spot (1 Mt at 3 km,
  front −1..+1 s) the single-player JVM keeps 13.2 cores busy with 8.6–9.2 more queued (C2 4.7–5.3 cores, three
  quarters of its compiles there recompiles), and its worst second has 8–12 frames. The same client on a dedicated server
  keeps 5.3–9.8 cores busy with 0.4–2.3 queued, and its worst second has 33–46 frames. Capping the compiler threads
  (`-XX:CICompilerCount=4`) only hands those cores to chunk meshing (12 frames).
- The player's server case: `commands` with `--prop incoming.server=host:port` and no `--world` joins that server (its
  resource pack accepted, as a player who answered yes; a failed or dropped join ends the run). The server must be in
  offline mode with the scenario's user (`Dev`) as operator, and its own config holds what `maxyield:` and `wave:` set in
  a world (they only log there; a world's scenario sets the CUSTOM preset); `burnable`, `site:`, `skip:` and
  `wait:blast` need the world's own server and are skipped. `spark …` profiles the server, `sparkc …` the client.
- `pall-front`: night post-front pall, camera 400 m from surface 15 kt, 120 m above ground (like host replay frame),
  frames relative to camera-front arrival. `INCOMING_EXPORT=2560x1440`: game frame larger than window, like Flashback
  video export; window reports output size (`Window.framebufferWidth/Height` fields via reflection; Flashback mixes
  into `Window.getWidth` while `ExportJob.shouldChangeFramebufferSize`), `Minecraft.resizeDisplay` applies it to game
  frame, checking all frame-size consumers (pall, `SceneDepth`, layer-shader `ScreenSize`); with
  `INCOMING_SIZE=640x360`, like host laptop (640×360 window, 1280×720 output). `*_off` frames show same instant
  without pall (OFF for a few frames), revealing whether pall darkens lit wall/city. `pall-low`: same burst but
  epicenter on stone column 45 blocks above camera ground; camera 8 above ground, below epicenter ground (valley
  trailer frame): below horizon beyond rendering/DH LOD edge, pall must extend to terrain (`PallGround`), without
  straight cutoff. `pall_low_*` frames 5/20/60 s after front (restore camera before each: wave carries it), with/without
  `dh`.
- `pall-mine`: daytime, 1.5 km from surface 15 kt; after front, fence/bars/post 3–5 blocks from camera; beneath,
  80-block mine tunnel (beyond `OpenAir` window), luminous ceiling and sky shaft at end. Frames `mine_surface`,
  `mine_tunnel` (no tunnel pall), `mine_shaft` (upward from shaft bottom: dust, shaft open to sky),
  `mine_rim_120s/240s` (time skip like `firestorm`: thinning local pall, visibility 17/51 blocks; bar/post edges
  against it), each paired with `_off`. Cloud shaderpack setup: add Sodium/Iris (Modrinth) to mods, pack into
  `run/scenario/shaderpacks`, `iris.properties` with `enableShaders=true`; pack `_off` captured after 40 ticks
  (frames take seconds).
- `mushroom`: distant volumetric mushroom, surface 15 kt at 10 km (north, or first clockwise direction where generator
  says epicenter and 600 blocks around it are land: scenario seed has sea north, water burst/column hid sphere);
  viewer 200 blocks above ground, `pall_mushroom_<seconds>s` frames from 2 s to 4 min; morning (side sun) or
  `INCOMING_MUSHROOM=night`, quality `INCOMING_PALL`. `INCOMING_MUSHROOM=shadow`: ground mushroom shadow (burst 5 km,
  `pall_shadow_*`); `glow`: nighttime ground fireball light (burst 3 km east, `pall_glow_<seconds>s`, 1–14 s);
  `film`: host video view above Newisle, daytime airburst 15 kt 1383 blocks away over land, spectator 40 above ground
  (wave cannot move it), `pall_film_front_<tenths>ds` from 1 to 3.5 s (approaching front, refraction ring at edge),
  `pall_film_<seconds>s` from 4 to 40 s (wall crosses viewer ~7 s), `pall_film_up_*` looking 55° up through 90 s,
  mushroom/stem visible while viewer in dust. `below`: haze under the camera long after a ground burst (`INCOMING_KT`, default 250, 2 km away), after
  `INCOMING_BELOW_MIN` minutes at heights `INCOMING_BELOW_UP` and pitches `INCOMING_BELOW_PITCH`
  (`pall_below_up<h>_p<deg>`, `_off` without pall, `_vanilla` without shaders when `INCOMING_SHADERS` is set),
  render distance `INCOMING_BELOW_RD`: past the drawn chunks the dust and mushroom rays end on the terrain map.
  `blanket`: ground-view dust/overhead pall (complaints 18.10), airburst
  15 kt at 3 km, `nuclear.wave_speed` 0.2 like host servers (`INCOMING_WAVE_SPEED`), spectator 20 blocks above highest
  terrain in first 800 blocks toward epicenter (eye-local hill hid wall); `pall_blanket_ground_<seconds>s` every 6 s
  through 54 s, then 1/5 minutes at heights 60/150/400/1000/2500 blocks (`pall_blanket_<minutes>m_up<height>`), plus
  overhead at 1000 blocks, 1.5 km from epicenter (`…_over1000`): old pall is one blanket from above, no patches/holes.
  `flash`: daytime fireball light, airburst 15 kt 4 km over land, spectator 30 above ground looks across explosion
  direction/down (slopes facing toward/away in same frame), `pall_flash_*` before flash and 2–60 ticks afterward;
  frame age from `ball r` in `SCENARIO pall` (226 blocks ~0.3 s, 250 ~1 s, 265 ~2.5 s): llvmpipe client stalls in
  first detonation ticks, screenshot takes frame rendered one/two ticks earlier. Fresh scenario world (first run)
  draws vertical pall stripes: compare runs on same world.
- Iris installed without shaderpack plus DH (like host laptop): `INCOMING_SHADERS=1 INCOMING_DH=1`, `-PmcModsDir`
  pointing to directory with `fetch_runtime_mods.py` mods plus Sodium/Iris/DH jar matching pack version (Modrinth,
  sha512 from open-skies-friends `pack/mods/*.pw.toml`); do not write `iris.properties`, shaders off. Cloud DH builds little:
  frame share covered by LOD beyond render distance in `SCENARIO … depth:`.
- Accessibility welcome screen/unfocused pause break headless client; `client_scenario.sh` writes own `options.txt`.
- Headless client works in cloud without KWin/shaders:
  `INCOMING_SCENARIO=launch LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientScenario
  -PmcModsDir=run/ci-mods` (mods from `tools/fetch_runtime_mods.py`, 3–6 fps). llvmpipe builds chunk sections slowly:
  after abrupt viewpoint change, entities may remain absent for several frames.
- `fx`/`fx-night`: close effects, viewer 50 blocks from target, frames from projectile disappearance (explosion),
  ICBM launch at end; previous strike columns removed before next (`FarRenderer.reset`). `fx-late`: Shahed/missile
  and columns at 45 s from half render distance/beyond edge. Cloud without KWin uses `xvfb-run`/llvmpipe
  (`LIBGL_ALWAYS_SOFTWARE=1`), ~10 fps; without Create/Sable in `run/scenario/mods`, sable-companion jar required.
- Volume-smoke scenarios (`planSmoke`): `smoke-salvo` full Grad pack (40 rockets, spread 15) onto plain 30 blocks
  before viewer, `smoke_salvo_<seconds>s` from first burst through minute; `smoke-salvo-pall` same salvo inside live
  surface-15-kt pall 2.5 km away (worst budget case); `smoke-column` Shahed, viewer flies through cloud after 30 s
  (center from same model, `smoke_column_*`). `smoke-fire`: nighttime Shahed into 15×7×15 wood house 60 blocks before
  viewer (house chunks `forceload`, since `fill` in unloaded place does nothing); few ignitions (fewer than 8 blocks
  after 3 s) means scenario places indoor fire (`SCENARIO smoke fire placed…`; our smoke only over crater fuel,
  house fire belongs to world), `smoke_fire_<seconds>s` through 90 s, `smoke_fire_far_*` from ~200 blocks;
  `INCOMING_SMOKE_FIRE=day` same by day (`smoke_fire_day_*`, black column against sky). `SCENARIO smoke` includes
  `FireSmoke.describe` sources; once per second logs grid sources, pall-pass GPU time and layer.
- `nuke-water`/`nuke-lake`: nuclear effects by water without flight (`nuke now`, surface 15 kt), `water_*`/`lake_*`
  frames from detonation, `SCENARIO water` every 10 ticks (`NukeWater.describe`: water under epicenter/source,
  column top/outer radius/water share, base-surge radius, ground-level billows over water, decal rings, spray,
  ground-dust particles). `nuke-water`: viewer 20 above sea, 40 blocks offshore at nearest warm ocean (frozen ocean
  has ice/icebergs); burst 1.5 km along direction where generator bed (`OCEAN_FLOOR_WG`) is below sea throughout path
  and 150 blocks sideways from viewer (side cape sends land dust over camera water). `nuke-lake`: plain with 1.6 km
  generator land along +Z (nearest plain may border sea/snow forest where pond freezes; choose via surrounding
  grid), 120×140-block pond before viewer (cleared 40 blocks over pond and toward viewer: hills/trees hid it), at
  generator height (client chunk may not have arrived, putting viewer underwater); server generates pond chunks
  immediately, since spectator loads asynchronously and `fill` said “That position is not loaded” (strips for
  32768-block limit); burst on land 1.5 km beyond. Spectator viewer: wave at 1.5 km (2 psi) throws creative player
  off platform. Without DH there is no beyond-render water: sea ring appears only when front enters render
  distance (~4 s for 15 kt); distant sea/LOD ring only host with `dh`. `nuke` (ICBM 2 km): `nuke_wall_*` HUD-free
  frames 2.25/3.75 s after front crosses viewing platform and throws viewer off (server `arrivalTicks`/clock:
  throw is server tick, server can trail client frame counter); beneath, airborne wall/logs/grass/3×3 pond,
  leaves on wall, canopy 30 blocks aside, roof ridge 50 blocks aside, against sky almost at eye horizon.
  Post-front ripple band: dark decal ring darkens pond; no dots on side faces of strips or leaf/roof edges against
  sky (block-row strips indicate water from height alone without horizontal surface; dots indicate horizontal
  inferred from background jump). Viewer then returns to platform (`nuke` mushroom frame at 20 s). This seed's
  `nuke` epicenter (0 63 −2000) is water: water burst (column, base surge, spray).
- `debris`: camera-local debris/dust, daytime nearest plain, 41×41 platform (stone 10 blocks deep, otherwise part
  hangs over lowland and ruin collapse drops whole platform), stone-brick house with glass panes/plank roof,
  glass-windowed plank house, three oaks (`place feature`); surface 15 kt north at `INCOMING_DEBRIS` meters (2000).
  Spectator 60 blocks south/15 above platform: right beside houses, fractions of a second after front viewer is
  already in wall head with meter-scale visibility (initial scene, both branches dark). `debris_<ticks>t` frames
  10 ticks before house-front arrival and 1–200 ticks after (client detonation clock); `SCENARIO debris` gives
  front/head relative to houses/camera (compare frames by these, frame instant varies by ticks), removed matter
  (`NukeDust.describe`: packets, positions, pieces by material, dust streaks/max depth), layer time.
- `firestorm` (`INCOMING_FIRESTORM=night|day`, pall quality `INCOMING_PALL`): airburst 15 kt where server biomes give
  most flammable land within 1.6 km (scenario spawn has ice/sea, no village within 1.6 km); `fs_*` frames from 2 km,
  800 m, inside beside fire tornado at ~1/5/15 minutes; roof alternates 2 s storm-on/off windows (`SCENARIO firestorm
  gpu`; llvmpipe only compares these). Minutes skipped: server `setGameTime` plus time packet to all; client
  `ClientNuclear.rewound`, like replay seek. Night: detonate at 4000, then `time set 18000` and seek; pall/storm
  morning from detonation time of day (without seeking, clock jump would move morning to nearest sunrise,
  4 minutes away). Camera above highest terrain within 8 blocks: inside ice spike frame would be dark block fill
  without hand; inside view beside tornado over flammable land (not sea/ice, which do not burn).
  `INCOMING_FIRESTORM_QUICK=1`: only two frames (beside tornado, from 2 km), immediately after tornado time skip,
  for appearance tuning.
- `guide` (`GuideShots`, guide frames): shot after three rendered frames (`settle`); llvmpipe ticks in batches
  between frames, immediate shot after step shows old state (empty crafting table before layout). Cloud DH builds
  almost nothing, draws columns instead of terrain: record without `dh`; map from loaded chunks (render distance
  20, target 180 blocks, generation manages ~14 surrounding chunks in 5 min). Headless Shift+left-click unavailable:
  scenario places GIF waypoint itself, renders key hint on frame. English frames like guide (Russian guide shares
  images): `client_scenario.sh guide` sets `lang:en_us` in `options.txt`, key hints English too; cloud without script
  needs hand-written `options.txt` (same as script plus `lang:en_us`), otherwise first-launch screen stalls client.
  World GIF: llvmpipe ~5 fps, GIF 12, scenario slows server (`/tick rate`) by measured fps to retain requested GIF
  pace (`startGif(name, pace)`; 0 GUI, one frame per step). Steps planned in constructor before world entry: all
  camera-place-dependent values (`view`, `along`, `across`) only in lambdas. Key caption over binoculars: HUD
  layers rise in z, GUI uses depth test; clear depth before caption. Viewer stands on peak among trees: launcher
  cannot fit, projectiles approach from afar (recorded that way); flat platform around viewer blocks downward
  village view (binocular crosshair hits grass at feet). `fill` in unloaded place does nothing; SAM under leaves
  has no open sky, needs own clearing. Missile approaches target from behind shooter, attack does not see slope
  between them: from viewer hill it hits summit trees. Missile camera records with viewer near village and straight
  path (`from`/`via` collinear), approaching from lower-terrain side.
- `firstuse`, `firstuse-ru` (`FirstUse`): a new player's screens on the CPU, no strike launched: flat survival world without cheats
  (the host alone), one language per launch, set by the script in options.txt (a language reload after a world outgrows the
  CPU client's memory ceiling), a fresh world and cleared `shown_hints`; the search repeats until the book lists the
  recipe (the index is built off-thread). Frames `firstuse-<lang>-<step>`: recipe book, first hold (toast and tooltip in one frame:
  toasts draw over screens), console Fire inactive (player target, nobody picked), console with the munitions line,
  Fire refused for missing munitions, the host's ICBM review, nuclear weapons off, binoculars 5° above the horizon
  (looking higher marks the surface under the ray's end, `TargetPicker`: only a ray ending past the loaded chunks
  has nothing under it). A required
  state not reached logs `FIRSTUSE FAIL`; `FIRSTUSE <lang> done, N failures` ends the run.
- `flame-edge`: plume at render edge, Grad rocket/missile on booster, night/day side views, same line from
  far-models viewer; client entity copy at 0.5/0.85/0.97 render distance, packet track at 0.97/1.1/1.4
  (`flame_<light>_<projectile>_<hundredths>_<entity|far>`, identical pose at 0.97 through both paths); reset
  tracks/trails after each (`FlightTracks.reset`, `FarRenderer.reset`), next projectile on same line.
  `SCENARIO flame-edge`: near-plume light from `FarFlightView.describe` (core/share/glare/veil).
- `flash` (`FlashScenario`, flash traces DESIGN-nuke §3.7): finds level plain for platform by generator heights
  (`ChunkGenerator.getBaseHeight`, biome from biome source, no chunk loads), builds over seconds on server thread;
  steps measured from completed platform/found wisp location, not world entry (client-tick timing detonated into
  empty location). Fires disabled for nearby burst: grass fire hid wall. Wall stripped birch (light wood best for
  scorch/shadows), stone-brick square under bodies (ground shadow). Wisps use second burst at real scale on another
  plain, ball offscreen to side: with ball at 1.3 km frame goes white. EMP arcs filmed there close from lamp shadow
  side, one frame per tick in first seconds, slowed with `/tick rate 4`: arc flash lasts 2–3 ticks, GPU-free client
  renders 3 fps after detonation; at 20 ticks, `Screenshot.grab` takes last rendered frame, repeating one frame for
  5 consecutive ticks, arcs passing between. `SCENARIO flash shot …` gives client marks, EMP arcs/wisps
  (`FlashAftermath.describe`).

- `arrival [count] [flash]`: client-only 12-second terminal sequence (1–128 heads), warning and authoritative
  approach packets, no siren or boost sound. Optional `flash` emits the unchanged 15 kt airburst for the first
  head. `--cam '2000 2 0 800'` frames the ground observer's view; capture 10–12.2 seconds, day/night, with WAV.

- `nuke <kt> camera open|sealed|window|torch`: isolated client flash fixture, detonation-relative clock and visible
  HUD. 1000 kt: 4550 m, HOB 2400 m; other yields: 1400 m, HOB 240·cube-root(Y); wave scale 0.35. The sealed/torch
  chamber is below ground, window room at ground. Waits for loaded blocks and propagated skylight before emitting
  the client-only detonation; logs raw sky/block light. No server damage. Use identical fixture code on main and
  branch for flash comparisons; `--time 16000 --at 1,3,10,20,33` for the megaton reproduction.
