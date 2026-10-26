#!/usr/bin/env bash
# Effects sandbox: a client with only the mod (optionally Iris with the host shader pack, DH) in a flat world, without a window
# (tools/nested_kwin.sh --virtual), runs until stopped; commands play effects in seconds, code changes through
# hot swap, resources through reload, without a new client. Scenario: Sandbox (mod/src/devtest); commands:
# SandboxCommand; client directory: mod/run/sandbox; frames and audio: mod/run/sandbox/fx/out/<name>/.
#
#   tools/sandbox.sh start [shaders] [dh] [soft] [--wait] [--size 1280x720]   background client (laptop job
#                                          sandbox-<tree>): takes its session GPU lease (tools/gpu_lease.sh);
#                                          held by another session: exit 75 with "GPU held by …" (--wait joins the lease
#                                          queue in arrival order); no Iris and Sodium (shaders), DH (dh): exit 2;
#                                          Artem is playing: exit 75; returns when the world is ready; runs until stop or
#                                          Artem's game starts, renewing the lease; other sessions' jobs without GPU do not stop it;
#                                          soft: no GPU or lease (xvfb-run, llvmpipe: 3–10 frames/s, without
#                                          shaders; cloud)
#   tools/sandbox.sh fx '<command>' …      /fx commands in sequence (blast drone, cam 60 10 90 8, time night, clear, …),
#                                          each waits for its result
#   tools/sandbox.sh swap                  build changes (Gradle), hot swap classes, reload resources
#                                          if changed; code 3: hot swap failed, restart required
#   tools/sandbox.sh shot <name> '<effect>' [--at 0.1,0.5,1,3] [--wav 4] [--cam 'range height azimuth look'] [--time day|night]
#                        [--overlay] [--no-swap]
#                                          swap, then the effect with frames at these seconds and audio: <name>_*.png, <name>.wav,
#                                          capture.json; finishes with seconds from the command to the last frame.
#                                          Each shot sets its own time: --time, default day (noon).
#                                          The nuke near the camera: shot wall 'nuke 15 near' --cam '60 15 0 5' --at -0.5,0.1,1 —
#                                          houses from the debris scene; time starts when the front reaches them (client-scenario.md)
#   tools/sandbox.sh restart [start options] stop and start again (after a change that hot swap cannot apply)
#   tools/sandbox.sh jbr                   install JetBrains Runtime (shared by all trees, mod/run/jbr in the main tree):
#                                          hot swap then accepts new fields, methods and lambdas; start selects it automatically
#   tools/sandbox.sh stop | status          done with visuals: stop, then tools/gpu_lease.sh release
#
# INCOMING_SANDBOX_JAVA=<java>: custom sandbox JVM (JetBrains Runtime with -XX:+AllowEnhancedClassRedefinition).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/mod/run/sandbox"
FX="$RUN/fx"
UNIT="incoming-job-sandbox-$(basename "$ROOT")"
# JetBrains Runtime: one for all trees, in mod/run of the main tree (git-common-dir points to the main .git)
JBR_DIR="$(cd "$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)/mod/run/jbr"
JBR_BUILD=jbr-21.0.11-linux-x64-b1163.116
jbr_java() { local j="$JBR_DIR/$JBR_BUILD/bin/java"; [ -x "$j" ] && echo "$j"; }
export JAVA_HOME="${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}"

# GPU lease (tools/gpu_lease.sh): owner is the session identified by its tree; artem_playing there detects Artem's game by its
# instance (not the command-line path: with MC_DIR pointing to the instance, sandbox mod paths contain it and would stop the sandbox itself)
source "$(dirname "$0")/gpu_lease.sh"
export INCOMING_GPU_OWNER="${INCOMING_GPU_OWNER:-$(lease_owner "$ROOT")}"
running() { [ -n "$(systemctl --user list-units "$UNIT-*" --state=active --no-legend --plain)" ]; }

# One /fx command: file in inbox, wait for its result (*.done); output is the result, code 1 is an error
fx() {
  local id done res limit=${FX_LIMIT:-3600} t=0
  id=$(date +%s%N)
  mkdir -p "$FX/inbox"
  printf '%s\n' "$1" > "$FX/inbox/$id.tmp" && mv "$FX/inbox/$id.tmp" "$FX/inbox/$id.cmd"
  done="$FX/inbox/$id.done"
  while [ ! -f "$done" ]; do
    running || { echo "sandbox is not running (tools/sandbox.sh start)" >&2; return 1; }
    sleep 0.1; t=$((t + 1))
    [ $t -gt $((limit * 10)) ] && { echo "no result after $limit s: $1" >&2; return 1; }
  done
  res=$(cat "$done"); rm -f "$done"
  echo "fx $1 → $res"
  [[ $res == ok* ]]
}

# Mods required for the requested mode: shaders needs Sodium and Iris, dh needs Distant Horizons (patterns match
# build.gradle); if missing, report what is missing and where Gradle gets it, code 1
mods_check() {
  local dir=$1 need=() miss=() p
  [ -n "${INCOMING_SHADERS:-}" ] && need+=('sodium-neoforge-*.jar' 'iris-neoforge-*.jar')
  [ -n "${INCOMING_DH:-}" ] && need+=('DistantHorizons-*.jar')
  for p in "${need[@]}"; do compgen -G "$dir/$p" >/dev/null || miss+=("$p"); done
  [ ${#miss[@]} -eq 0 ] && return 0
  echo "missing mode mods ($(IFS=' '; echo "${miss[*]}")) in $dir: Gradle takes them from $(python3 "$ROOT/tools/paths.py" MODS 2>/dev/null || echo 'the instance mods directory') (MC_DIR or -PmcModsDir) — place them there or run without shaders/dh" >&2
  return 1
}

# Seconds since timestamp $1 (date +%s.%N)
since() { awk -v a="$1" -v b="$(date +%s.%N)" 'BEGIN { printf "%.1f", b - a }'; }

hotswap_n() { tail -n 1 "$FX/hotswap" 2>/dev/null | cut -d' ' -f1; }

swap() {
  local stamp n0 classes res t0 done_ms w=0
  t0=$(date +%s.%N)
  stamp=$(mktemp -p "$RUN" .swap.XXXX)
  n0=$(hotswap_n); n0=${n0:-0}
  (cd "$ROOT/mod" && INCOMING_SANDBOX=1 ./gradlew compileJava compileDevtestJava processResources processDevtestResources -q --console=plain) || { rm -f "$stamp"; return 2; }
  done_ms=$(date +%s%3N)
  classes=$(find "$ROOT/mod/build/classes/java/main" "$ROOT/mod/build/classes/java/devtest" -name '*.class' -newer "$stamp" | wc -l)
  res=$(find "$ROOT/mod/build/resources/main" "$ROOT/mod/build/resources/devtest" -type f -newer "$stamp" 2>/dev/null | wc -l)
  rm -f "$stamp"
  if [ "$classes" -gt 0 ]; then
    # The agent caught up with the build: a scan started after Gradle finishes has no pending swaps (HotswapAgent, *.idle)
    until [ "$(cat "$FX/hotswap.idle" 2>/dev/null || echo 0)" -ge "$done_ms" ]; do
      sleep 0.1; w=$((w + 1)); [ $w -gt 300 ] && { echo "hot swap agent did not catch up with the build within 30 s" >&2; return 3; }
      running || { echo "sandbox is not running" >&2; return 1; }
    done
    # All swaps from this build: Gradle writes main and devtest in two tasks; either of the two swaps may fail
    local mine; mine=$(awk -v n="$n0" '$1 > n' "$FX/hotswap")
    echo "$mine" | cut -c1-300 | sed 's/^/swap: /'
    grep -q '^[0-9]* fail' <<< "$mine" && { echo "hot swap failed — tools/sandbox.sh restart" >&2; return 3; }
  fi
  if [ "$res" -gt 0 ]; then fx reload || return 1; fi
  echo "swap: classes $classes, resources $res, $(since "$t0") s"
}

# A slice of client audio.wav from the effect start (capture.json), saved as a separate WAV
cut_wav() {
  python3 - "$1" "$2" <<'PY'
import json, struct, sys, wave
cap = json.load(open(sys.argv[1]))
a = cap["audio"]
if a["seconds"] <= 0 or a["start"] < 0:
    sys.exit(0)
with open(a["file"], "rb") as f:
    data = f.read()
pos, fmt, start = 12, None, None
while pos + 8 <= len(data):
    cid, size = data[pos:pos + 4], struct.unpack("<I", data[pos + 4:pos + 8])[0]
    if cid == b"fmt ":
        fmt = struct.unpack("<HHIIHH", data[pos + 8:pos + 24])
    if cid == b"data":
        start = pos + 8
        break
    pos += 8 + size + (size & 1)
_, channels, rate, _, align, bits = fmt
off = a["start"] - (a["start"] - start) % align
n = int(a["seconds"] * rate) * align
chunk = data[off:off + n]
with wave.open(sys.argv[2], "wb") as w:
    w.setnchannels(channels)
    w.setsampwidth(bits // 8)
    w.setframerate(rate)
    w.writeframes(chunk)
print(f"audio: {sys.argv[2]} ({len(chunk) / align / rate:.1f} s, {rate} Hz)")
PY
}

start() {
  local size=1280x720 opts=() pack wait= fresh=
  while [ $# -gt 0 ]; do
    case "$1" in
      --wait) wait=--wait ;;
      shaders) export INCOMING_SHADERS=1 ;;
      dh) export INCOMING_DH=1 ;;
      soft) export INCOMING_SANDBOX_SOFT=1 ;;
      --size) size=$2; shift ;;
      *) echo "unknown parameter: $1" >&2; exit 2 ;;
    esac
    shift
  done
  running && { echo "sandbox is already running ($UNIT)"; exit 0; }
  # JetBrains Runtime, if installed (tools/sandbox.sh jbr): hot swap also accepts new fields and methods
  if [ -z "${INCOMING_SANDBOX_JAVA:-}" ] && jbr_java >/dev/null; then export INCOMING_SANDBOX_JAVA="$(jbr_java)"; fi
  unset INCOMING_SANDBOX_ENHANCED
  if [ -n "${INCOMING_SANDBOX_JAVA:-}" ] && "$INCOMING_SANDBOX_JAVA" -XX:+AllowEnhancedClassRedefinition -version >/dev/null 2>&1; then
    export INCOMING_SANDBOX_ENHANCED=1
  fi
  local swaps="method bodies only"; [ -n "${INCOMING_SANDBOX_ENHANCED:-}" ] && swaps="new fields, methods and lambdas too"
  echo "JVM: ${INCOMING_SANDBOX_JAVA:-Gradle toolchain}; hot swap: $swaps"
  if [ -z "${INCOMING_SANDBOX_SOFT:-}" ]; then
    artem_playing && { echo "Artem's game is running — leaving the GPU free" >&2; exit 75; }
    # One session at a time for visuals: another session holds the lease — refuse (leave its sandbox and jobs running); a GPU client
    # already running (our sandbox in another tree, our look gate) also causes refusal before building. --wait joins the lease queue
    # (tools/gpu_lease.sh: the first waiter takes the free GPU), then waits for our client if it is still running
    local who said=
    _lease_read
    fresh=1; [ "$L_OWNER" = "$INCOMING_GPU_OWNER" ] && ! _lease_free && fresh=
    if [ -z "$wait" ] && who=$(gpu_client_busy); then echo "$who" >&2; exit 75; fi
    lease_take "$INCOMING_GPU_OWNER" "$ROOT" $wait || exit 75
    # Startup failed (build, client): release the lease just acquired so other sessions do not wait 10 min for nothing
    [ -n "$fresh" ] && trap 'lease_release "$INCOMING_GPU_OWNER"' EXIT
    while who=$(gpu_client_busy); do
      [ -n "$wait" ] || { echo "$who" >&2; exit 75; }
      [ -z "$said" ] && { echo "$(date +%T) $who — waiting" >&2; said=1; }
      sleep 10
    done
  elif [ -n "${INCOMING_SHADERS:-}" ]; then
    echo "soft: without shaders (llvmpipe)" >&2; exit 2
  elif ! command -v xvfb-run >/dev/null; then
    # The NVIDIA driver ignores LIBGL_ALWAYS_SOFTWARE: a client in nested KWin would use the GPU
    echo "soft requires xvfb-run (cloud); it is not installed here" >&2; exit 2
  fi
  local t0; t0=$(date +%s.%N)
  mkdir -p "$RUN/logs" "$FX"
  rm -f "$FX/ready" "$FX/stopped"
  cat > "$RUN/alsoft.conf" <<CONF
[general]
drivers = wave
frequency = 48000
channels = stereo
sample-type = int16
[wave]
file = $RUN/audio.wav
CONF
  [ -f "$RUN/options.txt" ] || cat > "$RUN/options.txt" <<OPT
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:12
simulationDistance:8
guiScale:2
soundCategory_master:1.0
soundCategory_ambient:1.0
soundCategory_music:0.0
tutorialStep:none
joinedFirstServer:true
OPT
  mkdir -p "$RUN/config"
  if [ -n "${INCOMING_SHADERS:-}" ]; then
    local MC; MC="$(python3 "$ROOT/tools/paths.py" MC)"
    pack="$(sed -n 's/^shaderPack=//p' "$MC/config/iris.properties" 2>/dev/null)"
    # Without the shader pack the sandbox would silently run without it: frames "under Iris" would lack the pack
    [ -n "$pack" ] && [ -e "$MC/shaderpacks/$pack" ] \
      || { echo "shaders: no instance shader pack ($MC: iris.properties \"$pack\") — tools/paths.py or MC_DIR" >&2; exit 2; }
    mkdir -p "$RUN/shaderpacks"
    rm -rf "${RUN:?}/shaderpacks/$pack"
    cp -r "$MC/shaderpacks/$pack" "$RUN/shaderpacks/"
    printf 'enableShaders=true\nshaderPack=%s\ndisableUpdateMessage=true\n' "$pack" > "$RUN/config/iris.properties"
  fi
  (cd "$ROOT/mod" && INCOMING_SANDBOX=1 INCOMING_SCENARIO=sandbox INCOMING_SIZE=$size ./gradlew rigLaunch -PrigRun=runClientScenario \
      -PrigOut=sandbox -q --console=plain) || exit 1
  # Gradle copies mods from the instance mods directory by patterns (build.gradle), silently skipping missing ones:
  # without this check, `shaders` would capture without Iris, `dh` without Distant Horizons
  mods_check "$RUN/mods" || exit 2
  local build; build=$(since "$t0")
  local w=${size%x*} h=${size#*x}
  INCOMING_SANDBOX=1 nohup "$ROOT/tools/laptop_job.sh" --light "sandbox-$(basename "$ROOT")" -- "$ROOT/tools/sandbox.sh" _run "$w" "$h" > "$RUN/logs/job.log" 2>&1 &
  local job=$! waited=0
  # The job started: its client holds the lease; interrupting this wait (Ctrl+C, timeout) does not take the lease from a live client;
  # release it only if the job itself failed to start
  trap - EXIT
  until [ -f "$FX/ready" ]; do
    sleep 0.2; waited=$((waited + 1))
    # The client is alive but stuck before the world (another screen, shader pack): do not wait forever; last screen is in SANDBOX screen
    if [ -f "$FX/stopped" ] || ! kill -0 $job 2>/dev/null || [ $waited -gt 1500 ]; then
      echo "client did not start within $((waited / 5)) s:" >&2
      grep 'SANDBOX screen' "$RUN/logs/latest.log" 2>/dev/null | tail -n 1 >&2
      tail -n 20 "$RUN/logs/latest.log" "$RUN/logs/job.log" >&2
      [ $waited -gt 1500 ] && stop >&2
      # The job failed to start or stopped: release the lease just acquired
      if [ -n "$fresh" ] && { [ $waited -gt 1500 ] || ! kill -0 $job 2>/dev/null; }; then lease_release "$INCOMING_GPU_OWNER"; fi
      exit 1
    fi
  done
  echo "sandbox ready: build $build s, client $(cat "$FX/ready") s (JVM → world), total $(since "$t0") s"
}

# Inside a laptop job: client in nested KWin; only Artem's game (shared machine) and stop shut it down
run_client() {
  if [ -n "${INCOMING_SANDBOX_SOFT:-}" ]; then
    # No GPU or host audio, like the xvfb-run path in rigs.md (cloud)
    PIPEWIRE_REMOTE="$RUN/no-audio-server" PULSE_SERVER="unix:$RUN/no-audio-server" LIBGL_ALWAYS_SOFTWARE=1 \
      xvfb-run -a -s "-screen 0 ${1}x${2}x24" bash "$ROOT/mod/build/rig/sandbox.sh" > "$RUN/logs/client.out" 2>&1 &
  else
    "$ROOT/tools/nested_kwin.sh" wayland-incoming-sandbox "$1" "$2" "bash $ROOT/mod/build/rig/sandbox.sh" > "$RUN/logs/client.out" 2>&1 &
  fi
  local pc=$!
  # nested_kwin.sh (gpu_client_hold) renews the lease while the client is alive
  while kill -0 $pc 2>/dev/null; do
    if [ -z "${INCOMING_SANDBOX_SOFT:-}" ] && artem_playing; then echo "$(date +%T) Artem's game started — stopping the sandbox" | tee "$FX/stopped"; kill -TERM $pc; wait $pc; exit 75; fi
    sleep 5
  done
  wait $pc
}

# JetBrains Runtime 21 (DCEVM: -XX:+AllowEnhancedClassRedefinition), verified with the JetBrains SHA-512
jbr() {
  if jbr_java >/dev/null; then echo "already installed: $(jbr_java)"; return 0; fi
  local url="https://cache-redirector.jetbrains.com/intellij-jbr/$JBR_BUILD.tar.gz" dl
  dl=$(mktemp -d -p "$(dirname "$JBR_DIR")" .jbr-dl.XXXX) || return 1
  curl -fsSL -o "$dl/jbr.tar.gz" "$url" && curl -fsSL -o "$dl/sum" "$url.checksum" || { rm -rf "$dl"; return 1; }
  if [ "$(cut -d' ' -f1 "$dl/sum")" != "$(sha512sum "$dl/jbr.tar.gz" | cut -d' ' -f1)" ]; then
    echo "SHA-512 mismatch — not installing" >&2; rm -rf "$dl"; return 1
  fi
  mkdir -p "$JBR_DIR" && tar -xzf "$dl/jbr.tar.gz" -C "$JBR_DIR" && rm -rf "$dl"
  "$(jbr_java)" -XX:+AllowEnhancedClassRedefinition -version 2>&1 | head -1 && echo "installed: $(jbr_java)"
}

stop() {
  running || { echo "sandbox is not running"; return 0; }
  systemctl --user stop "$UNIT-*"
  echo "sandbox stopped"
}

shot() {
  local name=$1 effect=$2 at="0.1,0.5,1,2,4" wav=4 cam="" time=day overlay=off doswap=1 t0
  shift 2
  while [ $# -gt 0 ]; do
    case "$1" in
      --at) at=$2; shift ;;
      --wav) wav=$2; shift ;;
      --cam) cam=$2; shift ;;
      --time) time=$2; shift ;;
      --overlay) overlay=on ;;
      --no-swap) doswap=0 ;;
      *) echo "unknown parameter: $1" >&2; exit 2 ;;
    esac
    shift
  done
  t0=$(date +%s.%N)
  if [ $doswap = 1 ]; then swap || exit $?; fi
  fx clear || exit 1
  [ -n "$cam" ] && { fx "cam $cam" || exit 1; }
  # Each shot sets its own time (default day): the sandbox day cycle is disabled, otherwise the previous shot night
  # would persist into the next one; `time` finishes when the time reaches the client
  fx "time $time" || exit 1
  fx "overlay $overlay" || exit 1
  fx "shots $name $at $wav" || exit 1
  fx "$effect" || exit 1
  local out="$FX/out/$name"
  cut_wav "$out/capture.json" "$out/$name.wav"
  ls "$out"/*.png | sed 's/^/frame: /'
  echo "from command to last frame and audio: $(since "$t0") s"
}

# source tools/sandbox.sh: functions only (tools/test_sandbox.sh)
[ "${BASH_SOURCE[0]}" = "$0" ] || return 0
cmd=${1:-}
shift || true
case "$cmd" in
  start) start "$@" ;;
  restart) stop; sleep 2; start "$@" ;;
  stop) stop ;;
  jbr) jbr ;;
  status) if running; then echo "running; ready in $(cat "$FX/ready" 2>/dev/null) s; hot swap: $(cat "$FX/hotswap" 2>/dev/null)"; else echo "not running"; fi; lease_status || true ;;
  fx) for c in "$@"; do fx "$c" || exit 1; done ;;
  swap) swap ;;
  shot) [ $# -ge 2 ] || { echo "shot <name> '<effect>' [--at …] [--wav …] [--cam …]" >&2; exit 2; }; shot "$@" ;;
  _run) run_client "$@" ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac
