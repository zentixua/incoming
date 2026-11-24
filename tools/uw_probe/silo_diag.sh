#!/bin/bash
# One diagnostic Silo take for a dust wave look defect: the full-pack client, his spot at 3.9 km from the 1 Mt water burst,
# and during the approach a sequence of dust-wave diagnostics (DustWallDiag through `dw:` steps), each followed by a dump of
# the pass's targets and a frame, so one run shows at which pass and under which switch a defect appears. Usage:
#   silo_diag.sh <tree> <tag> <MEDIUM|HIGH> "<step;step;...>"   (steps run after the burst, e.g. "wait:420;dw:dump=a;shot:a")
set -u
TREE=$1 TAG=$2 Q=$3 DIAG=$4
UW=/mnt/data/projects/incoming/mod/run/agents/unified-wave
D=$UW/silo-take
OUT=$UW/step4/$TAG
mkdir -p "$OUT"
export JAVA_HOME=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
rm -rf "$D/saves/world" "$D/screenshots" && cp -a "$UW/silo-golden/world" "$D/saves/world"
sed -i "s/^enableShaders=.*/enableShaders=false/" "$D/config/iris.properties"
C="gamemode spectator;time set 6000;gamerule doDaylightCycle false;gamerule doWeatherCycle false;weather clear;tp @s 3514 73 -2298 55 -2"
C="$C;wait:3600;hud:off;maxyield:1000;wave:0.35;pall:$Q;incoming nuke now at 315 69 -67 1000 ground;$DIAG;wait:front;wait:100"
echo "$C" > "$OUT/commands.txt"
cd "$TREE"
tools/laptop_job.sh --gpu --wait --full "uw-s4-$TAG" -- python3 tools/prod_client.py commands --no-copy --dir "$D" --world world \
  --size 1600x900 --video "$OUT/take.mkv" --prop "incoming.commands=$C" --prop incoming.nukeDiag=true \
  --prop incoming.frametimes=true > "$OUT/client.out" 2>&1
echo "exit $?" >> "$OUT/client.out"
cp "$D/logs/latest.log" "$OUT/latest.log" 2>/dev/null; cp "$D/logs/frametimes.txt" "$OUT/frametimes.txt" 2>/dev/null
cp "$D/logs/run.jfr" "$OUT/run.jfr" 2>/dev/null
mkdir -p "$OUT/frames"; cp "$D"/screenshots/* "$OUT/frames/" 2>/dev/null
(cd "$TREE" && tools/gpu_lease.sh release) >/dev/null 2>&1
echo "done $TAG"
