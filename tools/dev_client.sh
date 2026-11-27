#!/usr/bin/env bash
# The standard modder loop without a screen: ModDevGradle's runClient (the plain dev client: the mod with Create, Sable and
# Aeronautics) in a saved world, kept running; code by hot swap (JetBrains Runtime, jdb `redefine` over JDWP, what an IDE's
# "reload changed classes" does), resources and shaders by the F3+T reload, the scene by vanilla commands (/tp, /time,
# /tick freeze, /tick step), frames by the F2 screenshot. Only the hands are ours: the headless display (tools/soft_gl.sh
# vanilla_display: a shared GPU slot or the CPU, no host window or audio) passes no keyboard input to the game, so the keys
# go in as files the dev client reads (devtest DevHands, incoming.dev.inbox).
#
#   tools/dev_client.sh start <save> [--from <world dir>] [--size 1280x720] [--sound]
#                         copy <world dir> into mod/run/client/saves/<save> once (reflink on btrfs: no extra disk),
#                         build, launch straight into it (-PquickPlay, -PhotSwap; JDWP on a free loopback port), confirm
#                         vanilla's one-time warning for a world with experimental settings; returns when the player is in
#                         the world, HUD hidden (F1). --sound: OpenAL Soft's wave driver writes mod/run/client/audio.wav
#                         (about 0.7 GB an hour), else no sound
#   tools/dev_client.sh cmd '<command>' …      chat commands in order (without the slash): tp @s 3514 73 -2298 55 -2
#   tools/dev_client.sh hud on|off              F1
#   tools/dev_client.sh smoke on|off            log every smoke source twice a second ("DEV smoke": kind, age, bounds,
#                                               selected, sample age, clumps and their masses; the puff fill): a cloud's pop by source
#   tools/dev_client.sh watch on|off            log every frame whose upper screen jumps in luminance ("DEV pop": a
#                                               one-frame dropout of an effect), with the dust pass's state
#   tools/dev_client.sh age                     the newest detonation's shown age (s), front radius and eye distance (blocks)
#   tools/dev_client.sh skip <seconds>          the newest detonation that old (single player): the server's game time moves
#                                               there and the client rebuilds it, as a replay seek; across the show's end the
#                                               span reads at vanilla pace, after it at the real rate (a 24-hour day: skip to
#                                               1150, wait for the end, then skip on); answers with the burst and its wind
#   tools/dev_client.sh dw <key>=<value> …      the dust wave's diagnostics, as the client scenario's dw: steps (devtest
#                                               DustWallSteps: probe, dump, sweep, ground, freezeage, history, …)
#   tools/dev_client.sh swap                    Gradle compiles; changed loaded classes are redefined (jdb); changed
#                                               resources: the F3+T reload, answered when it has finished
#   tools/dev_client.sh reload                  the F3+T reload alone (resources, the mod's shaders)
#   tools/dev_client.sh shot <name> [--settle s] F2 after s seconds (default 1: temporal history after a change), the
#                                               frame to mod/run/client/shots/<name>.png
#   tools/dev_client.sh stop | status
# INCOMING_SOFT_HEAP sets the heap (default 3g); the unit's ceiling is the heap plus 6 GB (INCOMING_JOB_MEM overrides).
# Look work loop and timings: client-scenario.md "Standard dev client".
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

# The java a rigLaunch script runs (its jdb redefines classes): the last quoted .../bin/java on its exec line
rig_java() { sed -n "s/^exec env .* '\([^']*\/bin\/java\)' .*/\1/p" "$1"; }

# One line to the dev client (DevHands: cmd, reload, shot, hud, press, dw, age, skip, watch, smoke), waits for its answer; prints it, code 1 on "error"
hand() {
  local id done t=0 res limit=${2:-300}
  id=$(date +%s%N); mkdir -p "$DEV/inbox"
  printf '%s\n' "$1" > "$DEV/inbox/$id.tmp" && mv "$DEV/inbox/$id.tmp" "$DEV/inbox/$id.cmd"
  done="$DEV/inbox/$id.done"
  while [ ! -f "$done" ]; do
    running || { echo "dev client stopped" >&2; return 1; }
    sleep 0.1; t=$((t + 1)); [ $t -gt $((limit * 10)) ] && { echo "no answer to '$1' within $limit s" >&2; return 1; }
  done
  res=$(cat "$done"); rm -f "$done"
  echo "$1 → $res"
  [[ $res == ok* ]]
}

reload() {
  local t0 failed; t0=$(date +%s.%N)
  # the mod logs a shader that fails to compile and draws without it: say so instead of a silent "ok"
  failed=$(grep -c 'failed to load' "$RUN/logs/latest.log" 2>/dev/null); failed=${failed:-0}
  hand reload || return 1
  if [ "$(grep -c 'failed to load' "$RUN/logs/latest.log" 2>/dev/null || true)" -gt "$failed" ]; then
    grep 'failed to load' "$RUN/logs/latest.log" | tail -n 3 >&2; echo "reload: a shader failed to load (above)" >&2; return 1
  fi
  # the loading overlay fades out for a second after the reload ends
  sleep 1.2
  echo "reload: $(since "$t0") s"
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
    local jdb; jdb="$(dirname "$(cat "$DEV/java" 2>/dev/null)")/jdb"
    [ -x "$jdb" ] || { echo "no jdb beside the client's java ($DEV/java): tools/dev_client.sh stop, then start" >&2; return 3; }
    # jdb redefines classes the client has loaded; a class not loaded yet loads the new file when first used
    out=$(for f in "${classes[@]}"; do
      local name=${f#"$ROOT/mod/build/classes/java/"}; name=${name#*/}; name=${name%.class}; name=${name//\//.}
      echo "redefine $name $f"
    done | { cat; echo exit; } | "$jdb" -attach "127.0.0.1:$port" 2>&1)
    # jdb prints "Initializing jdb" once attached; a refused or failed redefinition names it
    if ! grep -q 'Initializing jdb' <<< "$out"; then
      echo "jdb could not attach to 127.0.0.1:$port: $(head -n 2 <<< "$out")" >&2; return 3
    fi
    if grep -q -i 'failed\|unsupported\|exception\|error' <<< "$out"; then
      grep -i 'failed\|unsupported\|exception' <<< "$out" | head -n 5 >&2
      echo "hot swap refused (a mixin, registration or static initializer): tools/dev_client.sh stop, then start" >&2
      return 3
    fi
  fi
  [ "$res" -gt 0 ] && { reload || return 1; }
  echo "swap: classes ${#classes[@]}, resources $res, $(since "$t0") s"
}

shot() {
  local name=$1 settle=1 t0; shift
  if [ "${1:-}" = --settle ]; then [ -n "${2:-}" ] || { echo "--settle <seconds>" >&2; exit 2; }; settle=$2; fi
  [[ $name =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]] || { echo "shot name: letters, digits, _ . -" >&2; exit 2; }
  t0=$(date +%s.%N); mkdir -p "$RUN/shots"
  sleep "$settle"
  # (vanilla writes the PNG on its I/O pool, then answers)
  hand "shot dev-$name" || return 1
  mv "$RUN/screenshots/dev-$name.png" "$RUN/shots/$name.png" && echo "frame: $RUN/shots/$name.png ($(since "$t0") s)"
}

start() {
  local save=$1 from= size=1280x720 sound= port t0; shift
  while [ $# -gt 0 ]; do
    case "$1" in
      --from) from=$2; shift ;;
      --size) size=$2; shift ;;
      --sound) sound=1 ;;
      *) echo "unknown parameter: $1" >&2; exit 2 ;;
    esac
    shift
  done
  if running; then
    echo "dev client already running ($UNIT) in $(cat "$DEV/save" 2>/dev/null): stop it first for another save"
    [ "$(cat "$DEV/save" 2>/dev/null)" = "$save" ] && exit 0 || exit 1
  fi
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
  if [ -n "$sound" ]; then
    printf '[general]\ndrivers = wave\nfrequency = 48000\n[wave]\nfile = %s/audio.wav\n' "$RUN" > "$DEV/alsoft.conf"
  else
    printf '[general]\ndrivers = null\n' > "$DEV/alsoft.conf"
  fi
  # the unit's ceiling: a real map with Create's renderers peaked above heap + 2 GB (the sandbox's soft ceiling) at load
  local heap_mb; heap_mb=$(( $(soft_mem_cap) - 2048 ))
  export INCOMING_JOB_MEM=${INCOMING_JOB_MEM:-$(( heap_mb + 6144 ))M}
  local cap_mb=$INCOMING_JOB_MEM
  case "$cap_mb" in *[gG]) cap_mb=$(( ${cap_mb%?} * 1024 )) ;; *[mM]) cap_mb=${cap_mb%?} ;; esac
  soft_mem_ok "$cap_mb" || exit 75
  port=$(python3 "$ROOT/tools/free_port.py")
  soft_gl_env
  (cd "$ROOT/mod" && ./gradlew --daemon rigLaunch -PrigRun=runClient -PrigOut=dev-client "-PquickPlay=$save" "-PhotSwap=$port" \
      "-PclientSize=$size" -q --console=plain) || exit 1
  echo "$port" > "$DEV/port"; echo "$save" > "$DEV/save"
  rig_java "$ROOT/mod/build/rig/dev-client.sh" > "$DEV/java"
  rm -rf "$DEV/inbox"; mkdir -p "$DEV/inbox"
  cat > "$DEV/launch.sh" <<EOF
export ALSOFT_CONF='$DEV/alsoft.conf'
exec bash '$ROOT/mod/build/rig/dev-client.sh'
EOF
  local w=${size%x*} h=${size#*x}
  nohup "$ROOT/tools/laptop_job.sh" --soft "devclient-$TREE_ID" -- "$ROOT/tools/dev_client.sh" _run "$w" "$h" > "$DEV/job.log" 2>&1 &
  local job=$!
  # the unit exists once laptop_job.sh passed its admission lock; a refusal ends the job first
  until running; do kill -0 $job 2>/dev/null || { cat "$DEV/job.log" >&2; exit 1; }; sleep 0.2; done
  # vanilla's one-time warning for a world with experimental settings (a map made with the pack's datapacks): the answer
  # comes once it is pressed or the player is in the world without it
  hand "press I know what I'm doing!" 900 || { tail -n 30 "$DEV/job.log" "$RUN/logs/latest.log" >&2; exit 1; }
  until grep -q 'joined the game' "$RUN/logs/latest.log" 2>/dev/null; do
    running || { tail -n 30 "$DEV/job.log" "$RUN/logs/latest.log" >&2; exit 1; }; sleep 0.5
  done
  # chunks around the player and the first frames; then frames without chat or hotbar
  sleep 5
  hand "hud off" >/dev/null
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
  cmd) need_running; for a in "$@"; do hand "cmd $a" || exit 1; done ;;
  hud) need_running; hand "hud ${1:-off}" ;;
  swap) need_running; swap ;;
  reload) need_running; reload ;;
  age) need_running; hand age ;;
  skip) need_running; [ $# -ge 1 ] || { echo "skip <seconds>" >&2; exit 2; }; hand "skip $1" ;;
  watch) need_running; hand "watch ${1:-on}" ;;
  smoke) need_running; hand "smoke ${1:-on}" ;;
  describe) need_running; hand describe ;;
  dw) need_running; for a in "$@"; do hand "dw $a" || exit 1; done ;;
  shot) need_running; [ $# -ge 1 ] || { echo "shot <name> [--settle s]" >&2; exit 2; }; shot "$@" ;;
  stop) stop ;;
  status) if running; then echo "running ($UNIT)"; else echo "not running"; fi ;;
  _run) vanilla_display "devclient-$TREE_ID" "$1" "$2" "bash $DEV/launch.sh" > "$DEV/client.out" 2>&1 ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac
