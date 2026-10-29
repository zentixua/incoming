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
- Nuclear shots use one fixed ground-level eye position, 1.6–1.7 km away, at night for a 5 kt ground burst. Choose
  framing once for the flash, fireball and rising cloud; record through the wave reaching and covering the camera.
- Store gallery publishing reconciles the configured set, never appends duplicates. Keep each image and shot once,
  with a caption describing what is actually visible; a repeated draft run must leave the same gallery.
- Before merge, a fresh visual session checks the actual stills and clip frames against Artem's shot list, including
  the camera, approach, impacts, nuclear phases and gallery. A successful export alone does not establish this.

## Scenario lines
Client scenarios log `SCENARIO …` lines (sounds, fps, flash; `SCENARIO far`: distant visuals and last distant roll;
`SCENARIO nuke-sound`: nuclear echo, rays, reflection delay/level, tracks, start anchor `by sound|tick`; burning city,
own OpenAL sources with obstruction, per-tick `FireWatch` cost). Use these and frames to check look/sound.
`commands` scenario (`incoming.commands`, semicolon-separated steps, `ScenarioCommands`/`CommandPlan`): commands,
`wait:N`, `shot:<name>`, `hud:off|on`, `wait:nuke` (wait for detonation packet), `wait:blast` (wait for next server
explosion, at most 3600 ticks). Detailed city for client-fire measurements: `INCOMING_CITY=N@tick` (`FireCity`:
surface section plus N − 1 below per client chunk, with global palette, client-only).

## Effects sandbox: frames during work; full pack under Iris only for one comparison with 3.4.1
Rule ([visual/audio checks](../../docs/dev-pipeline.md#4-visual-and-audio-checks)): inspect look/sound during work,
like a modder in a dev game: edit → sandbox frame/WAV in seconds; before/after frames/WAV in PR for reviewer.
Sandbox is an MDG dev client with `devtest` (like JEI's `clientGameTest`); custom parts are only frame-to-file at a
specified tick and chatless commands (Claude cannot see screen; NeoForge has no screenshot client tests,
NeoForge #3624). Full-pack Iris client (`look_gate`, `prod_client.py`: ~3 min to first frame, 10–25 min per scene set,
the nuke 5–6 min per scene) records only the one-off 3.4.1 comparison, in one session until scenes reach its level;
not per PR. One visual session at a time: one GPU. Tool-improvement options (own replacement agent → HotswapAgent
on JBR; scenarios as sandbox command lists): [evaluating tool changes](../../docs/dev-pipeline.md#7-evaluating-tool-changes).
Scene bench (`tools/stand`, Artem's decision 24.10) differs from sandbox: sandbox gives work-in-progress frames in
this tree's dev client; bench runs identical real strikes with any Incoming 3.4.1+ jar (release/CI artifact) beside
accepted release, measuring frame time and frames; visual PR merge condition is frame time at 100 strikes (status
“Стенд сцен”). Scenes compared with release belong in bench `scenes.json`, not sandbox. During work, sample bench
with `--packs none --scenes 100`.
Laptop measurement 22.10: sandbox start 19–24 s (build 5–8 s, JVM-to-world client 13–15 s); with Iris/shaderpack 39 s
(client 30 s); method-body/shader edit → frame at effect second 3 takes 10 s (Gradle/replacement 5–7 s), also 10 s
with Iris.
- `tools/sandbox.sh start [shaders] [dh]`: mod-only client (sable-companion alongside: not embedded in dev; `shaders`
  adds Sodium/Iris/host shaderpack from instance, `dh` Distant Horizons), flat world (`Sandbox`: bedrock, stone,
  dirt, grass, grass top y 64; noon, no weather/mobs; same world every launch), headless in nested KWin, as laptop
  job; lives until `stop` or Artem's game starts (`fx/stopped`; game running means immediate exit 75); other jobs
  cannot stop it. One GPU (`tools/gpu_lease.sh`): client (`nested_kwin.sh`, used by every client) acquires its
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
  `shaders` without Sodium/Iris jars, `dh` without Distant Horizons jar in sandbox mods (Gradle takes instance mods
  by patterns, silently skips missing) exits 2 explaining missing mods/Gradle source, instead of recording without
  Iris (`tools/test_sandbox.sh`). `soft` uses no GPU, only where xvfb-run exists (cloud): nested KWin's NVIDIA
  driver ignores `LIBGL_ALWAYS_SOFTWARE`, so client took GPU. Sandbox installs no mods into pack: own `run/sandbox`.
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
  same concrete deck; `paved` meter of black concrete on ground). `nuke <kt> [air|ground]`: `/incoming nuke now`
  6 km away; `airburst`: SAM burst 30 m above point; `sam`: loaded SAM at point and Shahed from 1400 blocks north
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
- `tools/sandbox.sh shot <name> '<effect>' --at 0.1,0.5,1,3 --wav 4 [--cam …] [--time night] [--overlay]`: one command
  from edit to frames: build/replacement (`swap`), `clear`, effect; screenshot is first rendered frame after target
  tick (`capture.json` `age` includes tick fraction), audio slice from `audio.wav` (48 kHz) from effect start
  (`audio.from` seconds since effect start; `nuke … near` with pre-front frames starts from first frame). Effect
  clock uses world ticks, paused during resource reload/menus. Each shot sets own time (`--time`, default `day`):
  sandbox daylight cycle disabled, so previous shot's night would persist. `time` sends client time immediately,
  ends once client receives it (no receipt in 2 s: `error`): server normally sends every 20 ticks; an effect
  immediately after command previously spent first second in old light.
- Artem's game detection for sandbox/look gate/`--gpu` jobs: `artem_playing` (`gpu_lease.sh`), java working in Prism
  instance directory or Prism group, not command-line path: with `MC_DIR` pointing at instance, sandbox mods share
  its path and `pgrep -f 'PrismLauncher/instances/'` matched sandbox itself (code 75). `start shaders` without
  instance shaderpack refuses, instead of running packless sandbox.
- Nuclear effects near camera in seconds: `nuke <kt> [air|ground] near [m]` (`debris` scene without new client): at
  fresh location, stone-brick house with glass panes/plank roof, glass-windowed plank house, three oaks
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

## Pitfalls
- Server may lag client ticks during world generation: client-scenario frames anchor to events, not a counter.
- Scenario frames compare across runs: with `incoming.scenario`, nuclear seed derives from ID (`NuclearWarhead`),
  wind from detonation game time (`Wind.at`), pall detail from setting without GPU budget (`PallPass.Detail.pin`:
  llvmpipe budget would lower to LOW).
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
  mushroom/stem visible while viewer in dust. `blanket`: ground-view dust/overhead pall (complaints 18.10), airburst
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
  sha512 from open-skies `pack/mods/*.pw.toml`); do not write `iris.properties`, shaders off. Cloud DH builds little:
  frame share covered by LOD beyond render distance in `SCENARIO … depth:`.
- Accessibility welcome screen/unfocused pause break headless client; `client_scenario.sh` writes own `options.txt`.
- Headless client works in cloud without KWin/shaders (Gradle creates `run/scenario/logs` for gc.log):
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
