#!/bin/bash
# E2 run (unified-wave step 2d): the take-33 scene as players run it (the volume's adaptive ladder, not pinned rungs; real-time
# frames between two sparse frames), every frame's time in logs/frametimes.txt; HIGH and MEDIUM, camera 120 m up (+0..+100 s)
# and inside the layer (30 m, +0..+95 s). Needs the measurement patch (incoming.pall.adaptive, FrameTimes in the sandbox,
# INCOMING_E2) in TREE. Usage: TREE=<tree> TAG=<name> [START_ARGS="gpu"] e2_run.sh
set -u
OUT=/mnt/data/projects/incoming/mod/run/agents/unified-wave/step1/e2_${TAG}
mkdir -p "$OUT"
cd "$TREE" || exit 1
export JAVA_HOME=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
export INCOMING_E2=1
S=mod/run/sandbox
tools/sandbox.sh stop >/dev/null 2>&1
mkdir -p $S/fx; rm -f $S/fx/frames_per_tick $S/fx/dustwall_* $S/fx/planB
tools/sandbox.sh start ${START_ARGS-gpu} --wait --size 2560x1600 || { echo "sandbox start failed"; exit 1; }
O=$S/options.txt
sed -i 's/^renderDistance:.*/renderDistance:32/' $O; grep -q '^renderClouds' $O && sed -i 's/^renderClouds:.*/renderClouds:"false"/' $O || echo 'renderClouds:"false"' >> $O
sed -i "s/^\tmax_yield = .*/\tmax_yield = 1000/" $S/config/incoming-server.toml
tools/sandbox.sh restart ${START_ARGS-gpu} --wait --size 2560x1600 || { echo "sandbox restart failed"; tools/sandbox.sh stop; exit 1; }
tools/sandbox.sh fx 'zoom 1.537'
for r in $(seq 1 ${REPEATS:-1}); do
for q in HIGH MEDIUM; do
  sed -i "s/^nuke_dust_quality = .*/nuke_dust_quality = \"$q\"/" $S/config/incoming-client.toml
  echo "$(date +%T) $q above" >> "$OUT/marks.txt"
  tools/sandbox.sh shot e2_${q}_above 'nuke 1000 ground near 4500' --at 40,100 --cam '7632 120 148 -521' --wav 0 --time 12000
  echo "$(date +%T) $q inside" >> "$OUT/marks.txt"
  tools/sandbox.sh shot e2_${q}_inside 'nuke 1000 ground near 4500' --at 95 --cam '7632 30 148 -521' --wav 0 --time 12000
done
done
echo "$(date +%T) end" >> "$OUT/marks.txt"
cp $S/logs/latest.log "$OUT/latest.log"; cp $S/logs/frametimes.txt "$OUT/frametimes.txt" 2>/dev/null
tools/sandbox.sh stop
cp $S/logs/frametimes.txt "$OUT/frametimes.txt" 2>/dev/null
echo "done e2 -> $OUT"
