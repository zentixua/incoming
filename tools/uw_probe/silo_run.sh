#!/bin/bash
# Step 3 take: a fresh Zearth Silo copy in the full-pack single-player client (Artem's conditions), one burst, one observer, the
# whole duration from the flash to 10 min plus a frame at 20 min; video of the window, frames at fixed times after the front
# passes the observer, frame times, nukeDiag (A1/A4), the adaptive ladder. Usage:
#   silo_run.sh <tree> <tag> <MEDIUM|HIGH> <x y z yaw pitch> <gz "x y z"> <kt> <shaders 0|1> [after-front ticks to 10 min]
set -u
TREE=$1 TAG=$2 Q=$3 CAM=$4 GZ=$5 KT=$6 SH=$7 LATE=${8:-9780}
UW=/mnt/data/projects/incoming/mod/run/agents/unified-wave
D=$UW/silo-take
OUT=$UW/${UW_STEP:-step3}/$TAG
mkdir -p "$OUT"
export JAVA_HOME=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
# the instance folder (tools/paths.py names "Open Skies"; the host renamed it): MC_DIR overrides when given
export MC_DIR=${MC_DIR:-$(ls -d "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Open-Skies/minecraft" "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Open Skies/minecraft" 2>/dev/null | head -1)}
[ -n "$D" ] && [ -d "$D" ] || exit 1
rm -rf "$D/saves/world" "$D/screenshots" && cp -a "$UW/silo-golden/world" "$D/saves/world"
sed -i "s/^enableShaders=.*/enableShaders=$([ "$SH" = 1 ] && echo true || echo false)/" "$D/config/iris.properties"
L=$(echo "$TAG" | tr -c 'a-z0-9\n' '_')
C="gamemode spectator;time set 6000;gamerule doDaylightCycle false;gamerule doWeatherCycle false;weather clear;tp @s $CAM;wait:3600;hud:off;maxyield:1000;wave:0.35;pall:$Q;dw:rung=-2${UW_DW:+;$UW_DW};shot:${L}_before"
C="$C;incoming nuke now at $GZ $KT ground;wait:front;wait:20;shot:${L}_f1s;shot:${L}_f2s;wait:20;shot:${L}_f4s;wait:20;shot:${L}_f6s;wait:20;shot:${L}_f8s;wait:60;shot:${L}_f12s;wait:220;shot:${L}_f24s;wait:700;shot:${L}_f60s"
C="$C;wait:$((LATE / 2));shot:${L}_mid;wait:$((LATE - LATE / 2));shot:${L}_10min;skip:1200;wait:100;shot:${L}_20min"
echo "$C" > "$OUT/commands.txt"
cd "$TREE"
tools/laptop_job.sh --gpu --wait --full "uw-s3-$L" -- python3 tools/prod_client.py commands --no-copy --dir "$D" --world world --size 1600x900 \
  --video "$OUT/take.mkv" --prop "incoming.commands=$C" --prop incoming.nukeDiag=true --prop incoming.frametimes=true \
  --prop incoming.pall.adaptive=true > "$OUT/client.out" 2>&1
echo "exit $?" >> "$OUT/client.out"
cp "$D/logs/latest.log" "$OUT/latest.log" 2>/dev/null; cp "$D/logs/frametimes.txt" "$OUT/frametimes.txt" 2>/dev/null
mkdir -p "$OUT/frames"; cp "$D"/screenshots/${L}_*.png "$OUT/frames/" 2>/dev/null
(cd "$TREE" && tools/gpu_lease.sh release) >/dev/null 2>&1
echo "done $TAG"
