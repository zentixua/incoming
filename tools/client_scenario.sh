#!/usr/bin/env bash
# Client mod without window: virtual display KWin + Xwayland (tools/nested_kwin.sh - its own bus D-Bus and settings), sound is recorded in WAV (OpenAL Soft "wave").
# The script (mod/src/devtest/.../ClientScenario) launches all types of weapons and shoots footage.
#   tools/client_scenario.sh [all|guide|firstuse|firstuse-ru|launch|rocket|loiter|hud|map|target-map|nuke-map|salvo-map|route-map|launch-map|nuke|nuke-water|nuke-lake|pall|pall-front|pall-low|pall-mine|mushroom|firestorm|wall|debris|fx|fx-night|fx-late|models|far-models|occlusion|onboard|flyby|flyby-all|flyby-<case>] [shaders] [dh] [voxy] [lss]   → mod/run/scenario/screenshots/*.png, audio.wav, logs/latest.log
#   all - Shahed, rocket, bomb, salvo, binoculars and remote control (short flights from afar);
#   guide - player guide frames (GuideShots): recipes, binoculars, remote control, GIF cards, projectiles on the screen, camera,
#     "Lancet", SAM, stationary launcher; INCOMING_GUIDE=recipes,map - only these sections; map - based on drawing 20 chunks, without dh;
#     pictures in docs/guide/img - tools/guide_images.sh;
#   firstuse, firstuse-ru - a new player's first-use screens without strikes (FirstUse): recipe book, first hold, console
#     Fire states, refusals, binoculars at the sky; English or Russian, a fresh flat survival world without cheats;
#   launch - launch with the player's launcher, accelerator compartment, projectile chamber (N) before impact;
#   rocket - MLRS salvo: camera next to the package, arcs above the head, gaps in the area;
#   map - the projectile camera is further drawn: the video is nearby, then the map is based on telemetry, the hit is on the map;
#   target-map — remote control map: zoom, select a location by clicking, fire at Enter, projectile on the map;
#   nuke-map — 15 kt surface burst 1.5 km away, wave 0.35, targeting map open: the shock-front ring crosses the map;
#   salvo-map - Shahed salvo with scatter on the guidance map: frames of routes and area, time of opening the map in the log;
#   route-map - route on the guidance map: points, edit with the mouse, longer range - without fire, flying along points and hitting;
#   launch-map - the same with the launch location from the map: button and click, the icon is dragged, start from it (launcher or from a place outside the world);
#   loiter - a swarm of loitering: a catapult, a camera on a circle (the operator looks at the target), from the side - circles and dives;
#   nuke - ICBM and nuclear strike 15 kt from 2 km, black rain;
#   nuke-water - 15 kt at sea 1,5 km from the coast (viewer above the water near the coast): a column of water with a crown, a dark ring and splashes, a base wave;
#     INCOMING_WATER=far - the same explosion from 4 km (behind the coast, at an altitude of 400), narrow angle: pillar with crown, narrowing and falling (water_far_*);
#   nuke-lake - 15 kt on land at 1,5 km behind the pond in front of the viewer: the front goes across the pond in a dark ring with a wall of splashes;
#   pall - pall after a nuclear wave (15 kt near the ground): from a hill at 4 km, from the inside, from a height, at dusk and at night; quality -
#     INCOMING_PALL=LOW|MEDIUM|HIGH (by default LOW), in the log - lines SCENARIO pall (density at the camera, time on the video card);
#   pall-front — pall behind the front at night: camera at 400 m from the ground 15 kt at 120 m above the ground (FOV 50), behind the epicenter
#     tower block; frames pall_front_* from the arrival of the front to the camera (0–60 s), quality — INCOMING_PALL (default MEDIUM);
#     INCOMING_EXPORT=2560x1440 - the game frame is larger than the window, as when outputting video Flashback;
#   pall-low - the same, but the epicenter is on a pole at 45 m above the ground, the camera at 8 m above the ground (below the ground under the epicenter, like
#     from the valley): frames pall_low_* through 5, 20 and 60 s after the front; pall - before the relief and after drawing (with and without dh);
#   mushroom - mushroom volume: ground 15 kt in 10 km, viewer in the air; frames pall_mushroom_<seconds>s from 2 s to 4 minutes,
#     morning (sun on the side) or INCOMING_MUSHROOM=night; quality - INCOMING_PALL (default MEDIUM);
#     INCOMING_MUSHROOM=shadow - shadow of a mushroom: explosion in 5 km to the east, after 3 minutes the ground is under the viewer in the sun
#     from the east at different heights, the edge of the shadow - according to the viewer, and from the west (frames pall_shadow_<time>; 8000 - without shadow);
#     INCOMING_MUSHROOM=glow - light of the ball on the ground at night: explosion at 3 km to the east, the viewer looks at the ground in front of it
#     (frames pall_glow_<seconds>s from 1 to 14 s);
#     INCOMING_MUSHROOM=below - haze under the camera long after a ground nuke (INCOMING_KT, default 250 kt, 2 km away): after
#     INCOMING_BELOW_MIN minutes (default 10) at heights INCOMING_BELOW_UP and pitches INCOMING_BELOW_PITCH (frames
#     pall_below_up<h>_p<deg>, _off without the pall, _vanilla without shaders when INCOMING_SHADERS is set); INCOMING_BELOW_RD
#     sets the render distance, INCOMING_BELOW_TIME the time of day (default 6000);
#     INCOMING_MUSHROOM=blanket - air 15 kt in 3 km at wave_speed 0,2 (INCOMING_WAVE_SPEED): wall of dust from the ground
#     (pall_blanket_ground_*) and pall through 1 and 5 minutes from the heights of 60–2500 blocks (pall_blanket_<minutes>m_up*, _over1000);
#   firestorm - firestorm after air 15 kt: after ~1, 5 and 15 minutes (minutes are skipped by rewinding) from 2 km,
#     from the roof at 800 m and from the inside at the fire tornado (frames fs_<night|day>_<point>_<seconds>s); at night (default) or
#     INCOMING_FIRESTORM=day, pall quality — INCOMING_PALL (default MEDIUM), log — lines SCENARIO firestorm;
#   wall - a wall of dust passes by the viewer 1,5 km from ground 15 CT: dust near the camera and the wall in one frame (wall_*, world
#     walks slowly at the aisle); at night - INCOMING_WALL=night;
#   debris - during the day on the plain there is a stone house with glass, a plank house and oak trees in 60 blocks north of the viewer, ground 15 kt
#     even further north in INCOMING_DEBRIS meters (2000): what the wave breaks and carries (frames debris_<tick>t from the arrival of the front to
#     houses, lines SCENARIO debris);
#   fx, fx-night - close-up effects (Shahed explosions, rockets, bombs and ICBM launch; day and night);
#   fx-late — Shahed and the rocket close-up and their column of smoke through 45 s: from half of the rendering and from behind its edge (frames *_late50, *_late110);
#   models - close-up models of shells from three sides (on the launcher, in flight, B-2 with an open bomb bay);
#   far-models - projectiles in the distance in packets, like from a server: “entity / path in packets” pairs on one pose, a number of all
#     shells on 300, 800 and 1500 blocks day and night and through binoculars on 800–3000 (frames far_*, match_*);
#   occlusion - large volleys behind a stone wall, then the camera pans; in the log - particles by effects layer groups;
#   onboard - video from on board the missile (N) with the gunner on land and under water: frames onboard-dry_*, onboard-wet_*;
#   flyby - spectator on the ground in the path of shells from afar (MLRS salvo, rocket, Shahed): the sound of approaching and flying into the log and audio.wav,
#     INCOMING_FLIGHT_SPEED=<0..1> - world flight scale (1 - real speeds and altitudes) and frames flyby_* every 5 tick
#     from a platform above the crowns, looking at the target;
#   flyby-all - sound cases in turn (FlybySound): approach (rocket from 1,8 km per viewer), dogleg (rocket on detour
#     route - without approach whistle), pass (flying past), grad (MLRS salvo from 32 overhead), far (missile and Shahed due to
#     range of entities); each layer, loops, time stamps and totals “SCENARIO check ... PASS|FAIL” - to the log;
#     one case - flyby-grad, several - flyby-approach,pass;
#   Without shaders, dh, voxy, lss or vista the client is vanilla: on a shared GPU slot when one is free, else at once on
#     the CPU (Mesa llvmpipe; tools/soft_gl.sh vanilla_display, INCOMING_CPU_ONLY=1 forces the CPU): no GPU lease,
#     run it bare (the client goes into its own tools/laptop_job.sh --soft unit); frames are the vanilla picture, frame
#     and GPU times in its log are not frame costs. gpu - on the GPU alone (a frame-time measurement), under the lease;
#   shaders - also Sodium, Iris and a host shaderpack from the instance (like Artem); dh - another Distant Horizons from the instance;
#   lss - also LOD Server Support (jar lod-server-support-neoforge-*.jar in mods catalog MC_DIR): the built-in server distributes
#     LOD to his client (lss.test.integratedServer), the map and rays take the relief from him (target-map lss)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/mod/run/scenario"
mkdir -p "$RUN"
rm -rf "$RUN/screenshots"
cat > "$RUN/alsoft.conf" <<CONF
[general]
drivers = wave
frequency = 22050
channels = stereo
sample-type = int16
[wave]
file = $RUN/audio.wav
CONF
# first launch: no welcome screen and no pause when the window does not have focus; without music - it is in audio.wav on top of the sound mod
[ -f "$RUN/options.txt" ] || cat > "$RUN/options.txt" <<OPT
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:12
simulationDistance:10
guiScale:2
soundCategory_master:1.0
soundCategory_ambient:1.0
soundCategory_music:0.0
tutorialStep:none
joinedFirstServer:true
OPT
# the player's manual is in English, as is the manual itself (docs/guide and docs/guide/ru take the same frames)
# firstuse runs in one language per launch (firstuse English, firstuse-ru Russian): a language reload after a world
# outgrows the CPU client's memory ceiling
case "${1:-all}" in
  guide|firstuse) LANG_CODE=en_us ;;
  firstuse-ru) LANG_CODE=ru_ru ;;
  *) LANG_CODE= ;;
esac
if [ -n "$LANG_CODE" ]; then
  if grep -q '^lang:' "$RUN/options.txt"; then sed -i "s/^lang:.*/lang:$LANG_CODE/" "$RUN/options.txt"; else echo "lang:$LANG_CODE" >> "$RUN/options.txt"; fi
fi
export INCOMING_SCENARIO="${1:-all}"
MC="$(python3 "$ROOT/tools/paths.py" MC)"
# viewfx uses the disposable sandbox's flat world. Add "vista" to test actual TV feeds;
# without it the same scenario checks the ordinary renderer. INCOMING_VIEWFX_FEEDS=4 exercises the cache bound.
if [ "$INCOMING_SCENARIO" = viewfx ]; then export INCOMING_SANDBOX=1; fi
unset INCOMING_SHADERS INCOMING_DH INCOMING_VOXY INCOMING_LSS INCOMING_VISTA
GPU=
for opt in "${@:2}"; do
  case "$opt" in
    shaders)
      export INCOMING_SHADERS=1
      PACK="$(sed -n 's/^shaderPack=//p' "$MC/config/iris.properties")"
      mkdir -p "$RUN/shaderpacks" "$RUN/config"
      rm -rf "$RUN/shaderpacks/$PACK"
      cp -r "$MC/shaderpacks/$PACK" "$RUN/shaderpacks/"
      printf 'enableShaders=true\nshaderPack=%s\ndisableUpdateMessage=true\n' "$PACK" > "$RUN/config/iris.properties"
      ;;
    vista) export INCOMING_VISTA=1 ;;
    dh) export INCOMING_DH=1 ;;
    voxy) export INCOMING_VOXY=1 ;;
    lss) export INCOMING_LSS=1 ;;
    gpu) GPU=1 ;;
    *) echo "unknown parameter: $opt (there are shaders, dh, voxy, lss, vista, gpu)" >&2; exit 2 ;;
  esac
done
# shader packs, LOD mods and TV feeds need the GPU; everything else renders on the CPU
[ -n "${INCOMING_SHADERS:-}${INCOMING_DH:-}${INCOMING_VOXY:-}${INCOMING_LSS:-}${INCOMING_VISTA:-}" ] && GPU=1
export JAVA_HOME="${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}"
cd "$ROOT/mod"
if [ -z "$GPU" ]; then
  # Gradle only writes the launch script and exits, outside the client's memory ceiling; then the client alone runs in a
  # laptop_job --soft unit (idle CPU, ceiling, everything stopped with it), on a shared GPU slot if one is free, else on
  # the CPU (vanilla_display). Inside such a unit already (someone wrapped this script), the preparation shares its ceiling.
  source "$ROOT/tools/soft_gl.sh"
  # inside a --soft unit its admission already counted this client
  [ -n "${INCOMING_SOFT_UNIT:-}" ] || soft_mem_ok || exit 75
  soft_gl_env
  mkdir -p "$RUN/logs"
  "$ROOT/mod/gradlew" --no-daemon rigLaunch -PrigRun=runClientScenario -PrigOut=scenario -PscenarioDir=run/scenario -q --console=plain || exit 1
  name="scenario-$(tree_id "$ROOT")"
  [ -n "${INCOMING_SOFT_UNIT:-}" ] && { vanilla_display "$name" 1280 720 "bash $ROOT/mod/build/rig/scenario.sh"; exit $?; }
  exec "$ROOT/tools/laptop_job.sh" --soft "$name" -- bash -c 'source "$1/tools/soft_gl.sh"; vanilla_display "$2" 1280 720 "bash $1/mod/build/rig/scenario.sh"' _ "$ROOT" "$name"
fi
exec "$ROOT/tools/nested_kwin.sh" wayland-incoming-scenario 1280 720 "$ROOT/mod/gradlew runClientScenario -PscenarioDir=run/scenario --console=plain"
