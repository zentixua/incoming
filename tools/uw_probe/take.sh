#!/bin/bash
# Probe take (unified-wave step 1): take 33's judging scene (1 Mt surface burst, camera 120 m up 4.5 km out, low sun, zoom
# 1.537, HIGH, render distance 32) in the sandbox of a tree, frames at the given times (s after the front passes the camera).
# Usage: take.sh <tree> <name> <times> [frames-per-tick] [size] [extra sandbox start args...]
set -u
TREE=$1 NAME=$2 AT=$3 FPT=${4:-2} SIZE=${5:-1280x720}; shift 5 2>/dev/null || shift $#
OUT=/mnt/data/projects/incoming/mod/run/agents/unified-wave/step1/takes
mkdir -p "$OUT"
cd "$TREE" || exit 1
export JAVA_HOME=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
S=mod/run/sandbox
tools/sandbox.sh stop >/dev/null 2>&1
mkdir -p $S/fx; echo "$FPT" > $S/fx/frames_per_tick
if [ -n "${DW_DIV_SCALE:-}" ]; then echo "$DW_DIV_SCALE" > $S/fx/dustwall_div_scale; else rm -f $S/fx/dustwall_div_scale; fi
tools/sandbox.sh start --size "$SIZE" "$@" || { echo "sandbox start failed"; exit 1; }
O=$S/options.txt
sed -i 's/^renderDistance:.*/renderDistance:32/' $O; grep -q '^renderClouds' $O && sed -i 's/^renderClouds:.*/renderClouds:"false"/' $O || echo 'renderClouds:"false"' >> $O
sed -i "s/^\tmax_yield = .*/\tmax_yield = 1000/" $S/config/incoming-server.toml
sed -i "s/^nuke_dust_quality = .*/nuke_dust_quality = \"${DW_QUALITY:-HIGH}\"/" $S/config/incoming-client.toml
tools/sandbox.sh restart --size "$SIZE" "$@" || { echo "sandbox restart failed"; tools/sandbox.sh stop; exit 1; }
tools/sandbox.sh fx 'zoom 1.537'
tools/sandbox.sh shot "$NAME" 'nuke 1000 ground near 4500' --at "$AT" --cam '7632 120 148 -521' --wav 0 --time 12000 \
  || { echo "shot $NAME failed"; tools/sandbox.sh stop; exit 1; }
D=$S/fx/out/$NAME
grep "SANDBOX shot\|renderer:" $S/logs/latest.log > "$D/shots.log" 2>/dev/null
rm -rf "$OUT/$NAME"; cp -r "$D" "$OUT/$NAME"
tools/sandbox.sh stop
echo "done $NAME -> $OUT/$NAME"
