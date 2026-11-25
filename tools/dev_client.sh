#!/usr/bin/env bash
# The standard modder loop without a screen: ModDevGradle's runClient (the plain dev client: the mod with Create, Sable and
# Aeronautics) in a saved world, kept running; code by hot swap (JetBrains Runtime, jdb `redefine` over JDWP, what an IDE's
# "reload changed classes" does), resources and shaders by F3+T, the scene by vanilla commands (/tp, /time, /tick freeze,
# /tick step), frames by F2. Only the hands are ours: an agent has no keyboard or screen, so keys go in with xdotool on the
# client's own nested display (tools/soft_gl.sh vanilla_display: a shared GPU slot or the CPU, no host window or audio).
#
#   tools/dev_client.sh start <save> [--from <world dir>] [--size 1280x720] [--port 5005]
#                         copy <world dir> into mod/run/client/saves/<save> once (reflink on btrfs: no extra disk),
#                         build, launch straight into it (-PquickPlay, -PhotSwap); returns when the player is in the world;
#                         the HUD is hidden (F1) so frames carry no chat
#   tools/dev_client.sh cmd '<command>' …      chat commands in order (without the slash): tp @s 3514 73 -2298 55 -2
#   tools/dev_client.sh key <xdotool key> …    vanilla keys: F1 (HUD), r (Iris: reload the shader pack), F3+g …
#   tools/dev_client.sh swap                    Gradle compiles; changed loaded classes are redefined (jdb); changed
#                                               resources: F3+T and wait for the reload to finish
#   tools/dev_client.sh reload                  F3+T alone (resources, the mod's shaders), waits for its end
#   tools/dev_client.sh shot <name> [--settle s] F2 after s seconds (default 1: temporal history after a change), the
#                                               frame to mod/run/client/shots/<name>.png
#   tools/dev_client.sh stop | status
# Sound: OpenAL Soft's wave driver writes mod/run/client/audio.wav. INCOMING_SOFT_HEAP sets the heap (default 3g; a 1 Mt
# burst on a large map wants 5g). Look work loop and timings: client-scenario.md "Standard dev client".
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/mod/run/client"
DEV="$RUN/dev"
source "$ROOT/tools/soft_gl.sh"
TREE_ID=$(tree_id "$ROOT")
UNIT="incoming-job-devclient-$TREE_ID"
export JAVA_HOME="${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}"

running() { [ -n "$(systemctl --user list-units "$UNIT-*" --state=active --no-legend --plain)" ]; }
since() { awk -v a="$1" -v b="$(date +%s.%N)" 'BEGIN { printf "%.1f", b - a }'; }
need_running() { running || { echo "dev client is not running (tools/dev_client.sh start)" >&2; exit 1; }; }

# xdotool on the client's display (written by the launcher inside the nested session)
x() {
  [ -f "$DEV/display" ] || { echo "no display file: the client has not started" >&2; return 1; }
  env $(cat "$DEV/display") xdotool "$@"
}

# lines of latest.log matching $1
count() { grep -c -- "$1" "$RUN/logs/latest.log" 2>/dev/null || echo 0; }

# wait until latest.log has more than $2 lines matching $1, at most $3 s
wait_log() {
  local t=0
  until [ "$(count "$1")" -gt "$2" ]; do
    running || { echo "dev client stopped" >&2; return 1; }
    sleep 0.2; t=$((t + 1)); [ $t -gt $(($3 * 5)) ] && { echo "no '$1' in the log within $3 s" >&2; return 1; }
  done
}

cmd() {
  # "/" opens the chat with the slash typed; the command, Enter
  x key --clearmodifiers slash && sleep 0.3 && x type --delay 8 -- "$1" && x key Return && sleep 0.2
  echo "cmd /$1"
}

reload() {
  local n t0; t0=$(date +%s.%N); n=$(count 'Resource reload finished')
  x keydown F3 && x key t && x keyup F3 || return 1
  wait_log 'Resource reload finished' "$n" 300 || return 1
  # the loading overlay fades out for a second after the reload ends
  sleep 1.2
  echo "reload: $(grep 'Resource reload finished' "$RUN/logs/latest.log" | tail -n 1 | sed 's/.*finished after/finished after/'), $(since "$t0") s"
}

swap() {
  local t0 stamp classes res port out
  t0=$(date +%s.%N); stamp=$(mktemp -p "$DEV" .swap.XXXX)
  (cd "$ROOT/mod" && ./gradlew --daemon compileJava compileDevtestJava processResources processDevtestResources -q --console=plain) || { rm -f "$stamp"; return 2; }
  mapfile -t classes < <(find "$ROOT/mod/build/classes/java/main" "$ROOT/mod/build/classes/java/devtest" -name '*.class' -newer "$stamp")
  res=$(find "$ROOT/mod/build/resources/main" "$ROOT/mod/build/resources/devtest" -type f -newer "$stamp" 2>/dev/null | wc -l)
  rm -f "$stamp"
  if [ ${#classes[@]} -gt 0 ]; then
    port=$(cat "$DEV/port")
    # jdb redefines classes the client has loaded; a class not loaded yet loads the new file when first used
    out=$(for f in "${classes[@]}"; do
      local name=${f#"$ROOT/mod/build/classes/java/"}; name=${name#*/}; name=${name%.class}; name=${name//\//.}
      echo "redefine $name $f"
    done | { cat; echo exit; } | "$(dirname "$(cat "$DEV/java")")/jdb" -attach "127.0.0.1:$port" 2>&1)
    if grep -q -i 'failed\|unsupported\|exception' <<< "$out"; then
      grep -i 'failed\|unsupported\|exception' <<< "$out" | head -n 5 >&2
      echo "hot swap refused (a mixin, registration or static initializer): tools/dev_client.sh stop, then start" >&2
      return 3
    fi
  fi
  [ "$res" -gt 0 ] && { reload || return 1; }
  echo "swap: classes ${#classes[@]}, resources $res, $(since "$t0") s"
}

shot() {
  local name=$1 settle=1 shots="$RUN/screenshots" before f t0; shift
  [ "${1:-}" = --settle ] && settle=$2
  [[ $name =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]] || { echo "shot name: letters, digits, _ . -" >&2; exit 2; }
  t0=$(date +%s.%N); mkdir -p "$shots" "$RUN/shots"
  sleep "$settle"
  before=$(ls "$shots" 2>/dev/null | wc -l)
  x key F2 || return 1
  for _ in $(seq 100); do [ "$(ls "$shots" | wc -l)" -gt "$before" ] && break; sleep 0.1; done
  f=$(ls -t "$shots"/*.png 2>/dev/null | head -n 1)
  [ -n "$f" ] || { echo "no screenshot" >&2; return 1; }
  # the PNG is written on a worker thread: wait for its size to settle
  local s=-1; while [ "$s" != "$(stat -c %s "$f")" ]; do s=$(stat -c %s "$f"); sleep 0.2; done
  mv "$f" "$RUN/shots/$name.png" && echo "frame: $RUN/shots/$name.png ($(since "$t0") s)"
}

start() {
  local save=$1 from= size=1280x720 port=5005 t0; shift
  while [ $# -gt 0 ]; do
    case "$1" in
      --from) from=$2; shift ;;
      --size) size=$2; shift ;;
      --port) port=$2; shift ;;
      *) echo "unknown parameter: $1" >&2; exit 2 ;;
    esac
    shift
  done
  running && { echo "dev client already running ($UNIT)"; exit 0; }
  t0=$(date +%s.%N)
  mkdir -p "$RUN/saves" "$RUN/logs" "$DEV"
  if [ ! -d "$RUN/saves/$save" ]; then
    [ -n "$from" ] || { echo "no saved world $RUN/saves/$save: give --from <world dir>" >&2; exit 2; }
    cp -a --reflink=auto "$from" "$RUN/saves/$save" || exit 1
    rm -f "$RUN/saves/$save/session.lock"
    echo "world: $from -> $RUN/saves/$save ($(since "$t0") s)"
  fi
  # first launch only (later ones keep the client's own edits): no onboarding, no pause on lost focus
  [ -f "$RUN/options.txt" ] || printf '%s\n' onboardAccessibility:false pauseOnLostFocus:false renderDistance:12 \
    simulationDistance:8 guiScale:2 soundCategory_music:0.0 tutorialStep:none joinedFirstServer:true > "$RUN/options.txt"
  printf '[general]\ndrivers = wave\nfrequency = 48000\n[wave]\nfile = %s/audio.wav\n' "$RUN" > "$DEV/alsoft.conf"
  soft_mem_ok || exit 75
  soft_gl_env
  (cd "$ROOT/mod" && ./gradlew --daemon rigLaunch -PrigRun=runClient -PrigOut=dev-client "-PquickPlay=$save" "-PhotSwap=$port" \
      "-PclientSize=$size" -q --console=plain) || exit 1
  echo "$port" > "$DEV/port"
  sed -n "s/^exec env .* '\([^']*\/bin\/java\)' .*/\1/p" "$ROOT/mod/build/rig/dev-client.sh" > "$DEV/java"
  rm -f "$DEV/display"
  cat > "$DEV/launch.sh" <<EOF
printf 'DISPLAY=%s\nXAUTHORITY=%s\n' "\$DISPLAY" "\${XAUTHORITY:-}" > '$DEV/display'
export ALSOFT_CONF='$DEV/alsoft.conf'
exec bash '$ROOT/mod/build/rig/dev-client.sh'
EOF
  # a fresh log: the old one would answer the wait below
  [ -f "$RUN/logs/latest.log" ] && mv "$RUN/logs/latest.log" "$RUN/logs/previous.log"
  local w=${size%x*} h=${size#*x}
  nohup "$ROOT/tools/laptop_job.sh" --soft "devclient-$TREE_ID" -- "$ROOT/tools/dev_client.sh" _run "$w" "$h" > "$DEV/job.log" 2>&1 &
  sleep 2
  wait_log 'joined the game' 0 900 || { tail -n 30 "$DEV/job.log" "$RUN/logs/latest.log" >&2; exit 1; }
  # chunks around the player and the first frames; then the HUD off (frames without chat or hotbar)
  sleep 5
  x key F1
  echo "dev client in $save: $(since "$t0") s from the command; $(grep -m1 '^renderer:' "$DEV/client.out" 2>/dev/null)"
}

stop() {
  running || { echo "dev client is not running"; return 0; }
  # the client JVM first, alone (its shutdown saves the world and writes the JFR ring), then the unit with KWin and
  # Xwayland; JVMs of the unit's own cgroup subtree only (tools/sandbox.sh stop)
  local u cg p own java=() alive
  for u in $(systemctl --user list-units "$UNIT-*" --state=active --no-legend --plain | awk '{print $1}'); do
    cg=$(systemctl --user show "$u" -p ControlGroup --value)
    [[ $cg == */"$u" ]] && [ -d "/sys/fs/cgroup$cg" ] || continue
    for p in $(find "/sys/fs/cgroup$cg" -name cgroup.procs -exec cat {} + 2>/dev/null); do
      own=$(sed -n 's/^0:://p' "/proc/$p/cgroup" 2>/dev/null)
      [ "$(cat /proc/"$p"/comm 2>/dev/null)" = java ] && { [ "$own" = "$cg" ] || [[ $own == "$cg"/* ]]; } && java+=("$p")
    done
  done
  if [ ${#java[@]} -gt 0 ]; then
    kill -TERM "${java[@]}" 2>/dev/null
    for _ in $(seq 600); do
      alive=0; for p in "${java[@]}"; do [ -d /proc/"$p" ] && [ "$(awk '{print $3}' /proc/"$p"/stat 2>/dev/null)" != Z ] && alive=1; done
      [ $alive = 0 ] && break; sleep 0.1
    done
  fi
  systemctl --user stop "$UNIT-*"
  echo "dev client stopped"
}

[ "${BASH_SOURCE[0]}" = "$0" ] || return 0
c=${1:-}; shift || true
case "$c" in
  start) [ $# -ge 1 ] || { echo "start <save> [--from <world dir>]" >&2; exit 2; }; start "$@" ;;
  cmd) need_running; for a in "$@"; do cmd "$a" || exit 1; done ;;
  key) need_running; for a in "$@"; do x key -- "$a" || exit 1; done ;;
  swap) need_running; swap ;;
  reload) need_running; reload ;;
  shot) need_running; [ $# -ge 1 ] || { echo "shot <name> [--settle s]" >&2; exit 2; }; shot "$@" ;;
  stop) stop ;;
  status) if running; then echo "running ($UNIT)"; else echo "not running"; fi ;;
  _run) vanilla_display "devclient-$TREE_ID" "$1" "$2" "bash $DEV/launch.sh" > "$DEV/client.out" 2>&1 ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac
