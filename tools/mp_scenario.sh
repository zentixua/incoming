#!/usr/bin/env bash
# Multiplayer without windows: dedicated server with mod (Create, Sable, Aeronautics) and two clients, each in a nested
# KWin (tools/nested_kwin.sh). Alpha (operator) hits the player Bravo and at the point and in the middle of the approach he leaves and enters
# again; Bravo leaves while the shells are flying towards him and returns (scenario - src/devtest/.../MultiplayerScenario).
#   tools/mp_scenario.sh → mod/run/mp-server/logs/latest.log, mod/run/mp-a|mp-b/logs/latest.log, summary at the end
# Gradle only collects and prepares launches (rigLaunch → mod/build/rig/mp-*.sh) and goes out before the start: live Gradle
# UDP is a blocking socket on 0.0.0.0, but in the check it does not look into the network - the server and clients go directly from the MDG files.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"
RUN="$MOD/run"
# the server listens only on the loopback and a free port (the LoopbackGuard guard drops the server if the socket is not on a loopback)
PORT="${INCOMING_MP_PORT:-$(python3 "$ROOT/tools/free_port.py")}"
export INCOMING_MP_PORT=$PORT
export JAVA_HOME="${JAVA_HOME:-$(python3 "$ROOT/tools/paths.py" JAVA)}"

SERVER="$RUN/mp-server"
rm -rf "$SERVER/world" "$SERVER/logs"
mkdir -p "$SERVER"
echo 'eula=true' > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<PROPS
server-ip=127.0.0.1
server-port=$PORT
online-mode=false
enable-rcon=false
enable-query=false
spawn-protection=0
level-seed=20260927
difficulty=peaceful
allow-flight=true
view-distance=8
simulation-distance=8
motd=incoming-mp
PROPS
# NeoForge announces a dedicated server in LAN (UDP socket on 0.0.0.0, broadcasting MOTD and port) - checks are disabled
python3 "$ROOT/tools/rig_config.py" "$SERVER"
# ops.json: Alpha - operator (offline-UUID, as the server considers it without authorization)
python3 - "$SERVER/ops.json" <<'PY'
import hashlib, json, sys, uuid
def offline(name):
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    h[6] = h[6] & 0x0f | 0x30
    h[8] = h[8] & 0x3f | 0x80
    return str(uuid.UUID(bytes=bytes(h)))
json.dump([{"uuid": offline("Alpha"), "name": "Alpha", "level": 4, "bypassesPlayerLimit": False}], open(sys.argv[1], "w"))
PY

cd "$MOD"
# the directory, nickname and script of the client Gradle are read during preparation (-P and environment), each client has its own script;
# --no-daemon: no live Gradle after preparation, even if ~/.gradle/gradle.properties or GRADLE_OPTS include daemon
./gradlew --no-daemon --console=plain -q rigLaunch -PrigRun=runServer -PserverDir=run/mp-server -PrigOut=mp-server
for c in "Alpha mp-a" "Bravo mp-b"; do
  read -r name dir <<< "$c"
  INCOMING_SCENARIO=$dir ./gradlew --no-daemon --console=plain -q rigLaunch -PrigRun=runClientScenario \
    -PscenarioDir="run/$dir" -PscenarioUser="$name" -PrigOut="$dir"
done
RIG="$MOD/build/rig"
# server and clients - each in their own session; the script output (and Ctrl+C) extinguishes them entirely, with JVM under the wrappers
# on the desktop, clients go to the video card (nested_kwin.sh): rent and lock the client - once for the entire work, its
# clients inherit the lock (tools/gpu_lease.sh); up to rig_procs.sh - the extender does not receive his fd messages
if command -v kwin_wayland >/dev/null; then
  source "$ROOT/tools/gpu_lease.sh"
  who=$(gpu_client_busy) && { echo "$who" >&2; exit 75; }
  lease_take "${INCOMING_GPU_OWNER:-$(lease_owner "$ROOT")}" "$ROOT" || exit 75
  gpu_client_hold "${INCOMING_GPU_OWNER:-$(lease_owner "$ROOT")}" "$ROOT" "$(basename "$0")" || exit 75
fi
source "$ROOT/tools/rig_procs.sh"

rig_trap
rig_spawn "$RIG/mp-server.sh" > "$RUN/mp-server.out" 2>&1
SERVER_PID=$RIG_LAST
for _ in $(seq 600); do
  grep -q 'Done (' "$SERVER/logs/latest.log" 2>/dev/null && break
  kill -0 $SERVER_PID 2>/dev/null || { echo "the server did not start: $RUN/mp-server.out" >&2; exit 1; }
  sleep 1
done
grep -q 'Done (' "$SERVER/logs/latest.log" || { echo "the server did not come up in 10 minutes" >&2; exit 1; }

client() { # directory (aka script)
  local dir="$RUN/$1"
  rm -rf "$dir/logs" "$dir/screenshots"
  mkdir -p "$dir/logs"
  [ -f "$dir/options.txt" ] || printf 'onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:8\nsimulationDistance:8\nsoundCategory_master:0.0\ntutorialStep:none\njoinedFirstServer:true\n' > "$dir/options.txt"
  rig_spawn "$ROOT/tools/nested_kwin.sh" "wayland-incoming-$1" 960 540 "$RIG/$1.sh" > "$RUN/$1.out" 2>&1
}
client mp-b
B_PID=$RIG_LAST
# Alpha comes when Bravo is already in the world: script Alpha hits him
for _ in $(seq 600); do
  grep -q 'SCENARIO mp joined' "$RUN/mp-b/logs/latest.log" 2>/dev/null && break
  kill -0 $B_PID 2>/dev/null || break
  sleep 1
done
client mp-a
A_PID=$RIG_LAST
wait $A_PID $B_PID || true
rig_stop $SERVER_PID

echo "== server"
grep -E "joined the game|left the game|Strike|Projectile|ERROR|Exception|at ua\.zentix" "$SERVER/logs/latest.log" | cut -c1-200 || true
for c in mp-a mp-b; do
  echo "== $c"
  grep -E "SCENARIO mp|ERROR\] \[ua\.zentix|at ua\.zentix|Exception" "$RUN/$c/logs/latest.log" | cut -c1-200 || true
done
