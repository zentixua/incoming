#!/usr/bin/env bash
# Stress bench: dedicated server with a director (StressDirector) and three windowless player clients (StressClient):
# Host launches from the console, Friend1 leaves during an impact and returns, Friend2 is an ordinary friend.
# The director schedules salvos of 30, strikes on players, points and craft at 300–1000 blocks,
# retargeting, target deaths, leaving loaded terrain, the Nether, a nuke, save-all and Recall; ends with a summary.
#
#   tools/stress.sh                 # full scenario
#   INCOMING_STRESS_RESTART=1 tools/stress.sh   # + stop the server mid-flight and continue after restart
#                                                (surface-path probes do not run in this mode)
#   INCOMING_JFR=1 tools/stress.sh   # + server JFR profile (settings=profile) → mod/run/stress/server/stress.jfr
#   INCOMING_STRESS_PROBES=false tools/stress.sh   # no MLRS probe salvos in fresh areas (A/B measurement of server stalls)
#   INCOMING_RIG_JVM=artem tools/stress.sh   # server JVM as in Artem's game: 8 GB, generational ZGC (VPS only:
#                                             a 15 GB cloud host lacks memory); collector in the "server started" line
#
# Mods: MC_DIR (instance) or -PmcModsDir; in the cloud, python3 tools/fetch_runtime_mods.py and MODS=run/ci-mods.
# On a KDE desktop, each client runs in its own nested KWin (tools/nested_kwin.sh: windowless and silent) on the GPU;
# in the cloud (no KWin), under xvfb-run with software rendering (llvmpipe): slow but accurate — same packets, camera and HUD.
# On four cloud cores the clients consume CPU, chunk generation queues up, and any synchronous chunk load
# (Sable) holds the tick for tens of seconds, causing client timeouts; ticks longer than 0.5 s are logged with a stack and
# a "stall:" line (chunk loaded synchronously, nearby ticket levels, nearby mod tickets). Bench teleports wait for the area
# in the background: simulation-distance square + 2 chunks (load ticket without ticking), otherwise vanilla itself stalls at the new player position.
# Result: mod/run/stress/server/logs/latest.log (STRESS lines) and mod/run/stress/<player>/logs/latest.log (STRESSC).
# The next run moves previous run logs (including interrupted runs) to mod/run/stress/logs/<commit>-<time>/ instead of erasing them.
# Gradle only builds and prepares launches (rigLaunch → mod/build/rig/stress-*.sh), exiting before startup: a live Gradle
# has a locking UDP socket on 0.0.0.0; nothing in the bench looks at the network — server and clients run directly from MDG files.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/mod/run/stress"
MODS_ARG=()
[ -n "${MODS:-}" ] && MODS_ARG=("-PmcModsDir=$MODS")
cd "$ROOT/mod"

# server listens only on loopback and a free port: on 0.0.0.0 with online-mode=false, internet scanners find it
# (LoopbackGuard watchdog crashes the server if the socket is not on loopback); clients take the port from INCOMING_STRESS_PORT
export INCOMING_STRESS_PORT="${INCOMING_STRESS_PORT:-$(python3 "$ROOT/tools/free_port.py")}"
CLIENTS=("Host host" "Friend1 leaver" "Friend2 friend")

# Gradle reads launch settings (client name and role, port, director mode) from the environment during preparation;
# --no-daemon: no live Gradle after preparation, even if ~/.gradle/gradle.properties or GRADLE_OPTS enables the daemon
INCOMING_STRESS="${INCOMING_STRESS:-run}" ./gradlew --no-daemon --console=plain -q rigLaunch -PrigRun=runStressServer -PrigOut=stress-server "${MODS_ARG[@]}"
for c in "${CLIENTS[@]}"; do
  read -r name role <<< "$c"
  INCOMING_STRESS_NAME=$name INCOMING_STRESS_ROLE=$role \
    ./gradlew --no-daemon --console=plain -q rigLaunch -PrigRun=runStressClient -PrigOut="stress-$name" "${MODS_ARG[@]}"
done
RIG="$ROOT/mod/build/rig"
# move previous run logs aside under its label (commit and start time)
if [ -f "$RUN/run-id" ]; then
  keep="$RUN/logs/$(cat "$RUN/run-id")"
  mkdir -p "$keep"
  # everything the run writes: logs, JVM output (*.out), GC pauses, crash reports, JFR profile
  [ -d "$RUN/server/logs" ] && mv "$RUN/server/logs" "$keep/server"
  for f in "$RUN"/*.out "$RUN"/server/gc.log* "$RUN/server/stress.jfr"; do
    [ -e "$f" ] && mv "$f" "$keep/"
  done
  [ -d "$RUN/server/crash-reports" ] && mv "$RUN/server/crash-reports" "$keep/server-crash-reports"
  for c in "${CLIENTS[@]}"; do
    name=${c%% *}
    [ -d "$RUN/$name/logs" ] && mv "$RUN/$name/logs" "$keep/$name"
    [ -d "$RUN/$name/crash-reports" ] && mv "$RUN/$name/crash-reports" "$keep/$name-crash-reports"
  done
  echo "previous run logs: $keep"
fi
rm -rf "$RUN/server/world" "$RUN/server/logs"
mkdir -p "$RUN/server"
echo "$(git -C "$ROOT" rev-parse --short HEAD)$(git -C "$ROOT" diff --quiet HEAD -- mod tools || echo +dirty)-$(date -u +%Y%m%dT%H%M%SZ)" > "$RUN/run-id"
echo eula=true > "$RUN/server/eula.txt"
cat > "$RUN/server/server.properties" <<PROPS
server-ip=127.0.0.1
server-port=$INCOMING_STRESS_PORT
online-mode=false
enable-rcon=false
enable-query=false
enforce-secure-profile=false
view-distance=8
simulation-distance=6
spawn-protection=0
gamemode=creative
difficulty=peaceful
spawn-monsters=false
level-seed=20260928
max-tick-time=-1
sync-chunk-writes=false
PROPS
ln -sfn ../mods "$RUN/server/mods"
# NeoForge advertises a dedicated server on LAN (UDP socket on 0.0.0.0, MOTD and port broadcast); disabled for tests
python3 "$ROOT/tools/rig_config.py" "$RUN/server"

# server and clients each have their own session; script exit (including Ctrl+C) stops them entirely, including JVMs under wrappers
# desktop clients use the GPU (nested_kwin.sh): acquire the lease and client lock once for the entire job; its
# clients inherit the lock (tools/gpu_lease.sh); before rig_procs.sh so the renewal process does not inherit its message fd
if command -v kwin_wayland >/dev/null; then
  source "$ROOT/tools/gpu_lease.sh"
  who=$(gpu_client_busy) && { echo "$who" >&2; exit 75; }
  lease_take "${INCOMING_GPU_OWNER:-$(lease_owner "$ROOT")}" "$ROOT" || exit 75
  gpu_client_hold "${INCOMING_GPU_OWNER:-$(lease_owner "$ROOT")}" "$ROOT" "$(basename "$0")" || exit 75
fi
source "$ROOT/tools/rig_procs.sh"

rig_trap

rig_spawn "$RIG/stress-server.sh" > "$RUN/server.out" 2>&1
server=$RIG_LAST
until grep -q 'Done (' "$RUN/server.out" 2>/dev/null; do
  kill -0 "$server" 2>/dev/null || { echo "server failed to start: $RUN/server.out"; exit 1; }
  sleep 2
done

client() {
  local name=$1
  mkdir -p "$RUN/$name/logs"
  ln -sfn ../mods "$RUN/$name/mods"
  cat > "$RUN/$name/options.txt" <<OPT
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:4
simulationDistance:6
maxFps:15
graphicsMode:0
particles:1
soundCategory_master:0.0
tutorialStep:none
joinedFirstServer:true
skipMultiplayerWarning:true
OPT
  if command -v kwin_wayland >/dev/null; then
    # KDE desktop: a separate nested KWin for each client (windowless, silent, own bus and socket), on the GPU
    rig_spawn "$ROOT/tools/nested_kwin.sh" "wayland-incoming-stress-$name" 854 480 "$RIG/stress-$name.sh" > "$RUN/$name.out" 2>&1
  else
    # cloud: xvfb-run and software rendering; sound server blocked for the client, as in nested_kwin.sh
    LIBGL_ALWAYS_SOFTWARE=1 PIPEWIRE_REMOTE="$RUN/no-audio-server" PULSE_SERVER="unix:$RUN/no-audio-server" \
      rig_spawn xvfb-run -a -s "-screen 0 854x480x24" "$RIG/stress-$name.sh" > "$RUN/$name.out" 2>&1
  fi
}
for i in "${!CLIENTS[@]}"; do
  [ "$i" -gt 0 ] && sleep 20
  client "${CLIENTS[$i]%% *}"
done

wait "$server" || true
sleep 30
grep -h 'STRESS summary\|STRESS problem\|STRESS lost\|STRESS probe' "$RUN/server/logs/latest.log" || true
