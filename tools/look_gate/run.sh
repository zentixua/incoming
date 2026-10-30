#!/usr/bin/env bash
# Gate for picture and sound: ProbeScenario scenes (client scenario probe) of one mod version under Iris
# with the full pack.
#   tools/look_gate/run.sh <name> <incoming tree> [scenes, comma-separated] [extra mods directory]
# Hearing without deafening always; sampled scenes in the order and at the game time of the reference:
# a run where this is not so is not usable
# (check.py by log, code 1).
# Copy of Artem's instance → $LOOK_ROOT/inst-<name> (his instance is not touched), client — the tree's
# prod_client.py (scenario jar of this tree);
# waits until Artem's game is closed and there is enough memory; if the game has started, the client
# is shut down, code 75.
# Result — $LOOK_ROOT/out/<name>/: frames, log, audio.wav, probe-frames.txt, gpu.csv. Run through
# tools/laptop_job.sh --gpu.
set -uo pipefail
V=$1; TREE=$2; CASES=${3:-}; EXTRA=${4:--}
ROOT=${LOOK_ROOT:-/mnt/data/projects/incoming/mod/run/look-gate}
INST=$ROOT/inst-$V; OUT=$ROOT/out/$V
export JAVA_HOME=${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}
# Artem's game — by his instance (artem_playing in gpu_lease.sh): the gate client's instance copy is not it
source "$(dirname "$(readlink -f "$0")")/../gpu_lease.sh"
while artem_playing; do echo "$(date +%T) Artem's game is running — waiting"; sleep 60; done
# with picture — one session at a time (CLAUDE.md): it shuts down its own clients and its own sandbox
# itself; light jobs (server,
# tests) can run alongside if there is enough memory for a client with the full pack (~13 GB)
short_of_memory() { [ "$(awk '/MemAvailable/ {print int($2/1048576)}' /proc/meminfo)" -lt 13 ]; }
# gate queue — by lock: a process check does not see another job while it is still copying the instance
# and building the jar
# (on 22.10 two runs went at once this way, and frames of one were taken at 20–30 fps instead of ~80);
# the lock is held until exit
mkdir -p "$ROOT"; exec 9>"$ROOT/.gpu.lock"
until flock -n 9; do echo "$(date +%T) another gate run — waiting"; sleep 30; done
while short_of_memory; do echo "$(date +%T) less than 13 GB of memory — waiting"; sleep 30; done
python3 "$TREE/tools/prod_client.py" --copy-only --dir "$INST" || exit 1
sed -i -e 's/^fullscreen:.*/fullscreen:false/' -e 's/^soundCategory_music:.*/soundCategory_music:0.0/' "$INST/options.txt"
# no deafening and ringing (nuke_tinnitus = false in the copy) — always: scenes run back to back with one player,
# and deafness from a blast at 50 m carries into the next scenes. Previously the variable
# INCOMING_LOOK_HEARING=off enabled this, and runs F2b7, F2b8
# went with hearing without it; now the script turns it off itself, and the probe records what the game sees (check.py)
sed -i "s/^nuke_tinnitus = .*/nuke_tinnitus = false/" "$INST/config/incoming-client.toml"
grep -q '^nuke_tinnitus = false' "$INST/config/incoming-client.toml" || { echo "nuke_tinnitus not disabled"; exit 1; }
if [ "$EXTRA" != - ]; then cp -v "$EXTRA"/*.jar "$INST/mods/"; fi
rm -rf "$OUT"; mkdir -p "$OUT"
PROPS=(--prop incoming.frametimes=false)
[ -n "$CASES" ] && PROPS+=(--prop "incoming.probe.cases=$CASES")
( while true; do nvidia-smi --query-gpu=timestamp,memory.used,utilization.gpu --format=csv,noheader >> "$OUT/gpu.csv"; sleep 2; done ) &
GPU=$!
python3 "$TREE/tools/prod_client.py" probe --no-copy --dir "$INST" --size 1920x1080 "${PROPS[@]}" --seconds 3600 > "$OUT/client.out" 2>&1 &
PC=$!
code=
while kill -0 $PC 2>/dev/null; do
  if artem_playing; then echo "$(date +%T) Artem's game is up, stopping"; kill -TERM $PC; wait $PC; code=75; break; fi
  sleep 5
done
[ -z "$code" ] && { wait $PC; code=$?; }
kill $GPU 2>/dev/null
cp -r "$INST/screenshots" "$OUT/" 2>/dev/null
cp "$INST/logs/latest.log" "$INST/logs/probe-frames.txt" "$INST/audio.wav" "$OUT/" 2>/dev/null
cp -r "$INST/crash-reports" "$OUT/" 2>/dev/null
grep -c 'SCENARIO probe shot' "$OUT/latest.log"
grep -E 'SCENARIO probe (case|.* blast|.* launch|.*no )|SCENARIO (done|fx)|Exception' "$OUT/latest.log" | head -40
# is the run usable: hearing is off, each scene at its time in the reference game
if [ "$code" != 75 ] && ! python3 "$(dirname "$0")/check.py" "$OUT/latest.log"; then
  echo "look $V: run is not usable for comparison (reason above)"
  [ "$code" = 0 ] && code=1
fi
echo "look $V exit $code"
exit $code
