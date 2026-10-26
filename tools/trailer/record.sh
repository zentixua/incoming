#!/usr/bin/env bash
# Trailer recording: a windowless client plays the scenario (mod/src/devtest/.../scenario/trailer/Trailer) and writes
# 60 fps frames in game time and a sound log to mod/run/scenario/trailer/; editing: tools/trailer/edit.py.
#   tools/trailer/record.sh [shaders]
#   shaders — Sodium, Iris and the host shaderpack from the instance (only on a PC with the instance and KWin)
# Frame size: INCOMING_SIZE (default 1920x1080). Without an instance (cloud), Create/Sable/Aeronautics
# are downloaded by tools/fetch_runtime_mods.py; the client runs under xvfb-run with software rendering.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUN="$ROOT/mod/run/scenario"
mkdir -p "$RUN/logs"
# recording folder: INCOMING_TRAILER_DIR (default trailer); retake individual shots without touching the main take:
#   INCOMING_TRAILER_SHOTS=bomb_bay,targets INCOMING_TRAILER_DIR=trailer-retake tools/trailer/record.sh shaders
#   uv run tools/trailer/edit.py --rec mod/run/scenario/trailer --rec mod/run/scenario/trailer-retake
# game language: INCOMING_LANG (default ru_ru); shots with game text in frame for an English video use en_us:
#   INCOMING_LANG=en_us INCOMING_TRAILER_SHOTS=remote,scope,targets INCOMING_TRAILER_DIR=trailer-en tools/trailer/record.sh shaders
export INCOMING_TRAILER_DIR="${INCOMING_TRAILER_DIR:-trailer}"
rm -rf "${RUN:?}/$INCOMING_TRAILER_DIR"
# device audio is not needed (game runs slowed down, audio track is assembled by the edit), but the sound engine must work
cat > "$RUN/alsoft.conf" <<CONF
[general]
drivers = wave
[wave]
file = /dev/null
CONF
cat > "$RUN/options.txt" <<OPT
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:${INCOMING_RENDER_DISTANCE:-12}
simulationDistance:16
entityDistanceScaling:1.0
guiScale:3
fov:0.0
bobView:false
soundCategory_music:0.0
tutorialStep:none
joinedFirstServer:true
lang:${INCOMING_LANG:-ru_ru}
OPT
export INCOMING_SCENARIO=trailer
export INCOMING_SIZE="${INCOMING_SIZE:-1920x1080}"
W="${INCOMING_SIZE%x*}"; H="${INCOMING_SIZE#*x}"
# PNG frames: ~1.2 bytes per pixel, ~12,000 frames (720p: 11 GB, 1080p: ~30 GB)
NEED_GB=$(( W * H * 12 / 10 * 12000 / 1000000000 + 1 ))
FREE_GB=$(( $(df -Pk "$RUN" | awk 'NR==2 {print $4}') / 1000000 ))
if [ "$FREE_GB" -lt "$NEED_GB" ]; then
  echo "record.sh: ${W}x${H} frames require ~${NEED_GB} GB; ${FREE_GB} GB free in $RUN" >&2
  exit 1
fi
MC="$(python3 "$ROOT/tools/paths.py" MC)"
GRADLE_ARGS=(runClientScenario --console=plain)
if [ ! -d "$MC/mods" ]; then
  python3 "$ROOT/tools/fetch_runtime_mods.py"
  GRADLE_ARGS+=(-PmcModsDir=run/ci-mods)
fi
if [ "${1:-}" = shaders ]; then
  export INCOMING_SHADERS=1
  PACK="$(sed -n 's/^shaderPack=//p' "$MC/config/iris.properties")"
  mkdir -p "$RUN/shaderpacks" "$RUN/config"
  rm -rf "$RUN/shaderpacks/$PACK"
  cp -r "$MC/shaderpacks/$PACK" "$RUN/shaderpacks/"
  printf 'enableShaders=true\nshaderPack=%s\ndisableUpdateMessage=true\n' "$PACK" > "$RUN/config/iris.properties"
else
  unset INCOMING_SHADERS
fi
JDK="$(python3 "$ROOT/tools/paths.py" JAVA)"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$JDK" ]; then export JAVA_HOME="$JDK"; fi
cd "$ROOT/mod"
if command -v kwin_wayland >/dev/null; then
  # KWin screen with a margin: the client window with its title bar does not fit the screen and shrinks (to 1920×1052);
  # the scenario removes the window border and sets it to exactly ${W}x${H} (Trailer.exactFrame)
  exec "$ROOT/tools/nested_kwin.sh" wayland-incoming-trailer "$((W + 64))" "$((H + 160))" "$ROOT/mod/gradlew ${GRADLE_ARGS[*]}"
fi
# cloud: sound server blocked for the client, as in nested_kwin.sh (trailer audio is assembled from the log)
LIBGL_ALWAYS_SOFTWARE=1 PIPEWIRE_REMOTE="$RUN/no-audio-server" PULSE_SERVER="unix:$RUN/no-audio-server" \
  exec xvfb-run -a -s "-screen 0 ${W}x${H}x24" ./gradlew "${GRADLE_ARGS[@]}"
