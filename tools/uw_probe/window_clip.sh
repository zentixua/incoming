#!/bin/bash
# Blend-window clip (2f gate): take 33's scene, HIGH, vanilla sandbox (shared GPU slot or CPU), 1280x720, frames at 15 fps over
# +20..+70 s after the front, then tools/clip_metrics.py flicker on them. Usage: TREE=<tree> TAG=<name> window_clip.sh
set -u
OUT=/mnt/data/projects/incoming/mod/run/agents/unified-wave/step1/window_${TAG}
mkdir -p "$OUT"
cd "$TREE" || exit 1
export JAVA_HOME=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
export INCOMING_SOFT_HEAP=${INCOMING_SOFT_HEAP:-3g}
S=mod/run/sandbox
tools/sandbox.sh stop >/dev/null 2>&1
mkdir -p $S/fx; echo 2 > $S/fx/frames_per_tick
tools/sandbox.sh start --size 1280x720 || exit 1
O=$S/options.txt
sed -i 's/^renderDistance:.*/renderDistance:32/' $O; grep -q '^renderClouds' $O && sed -i 's/^renderClouds:.*/renderClouds:"false"/' $O || echo 'renderClouds:"false"' >> $O
sed -i "s/^\tmax_yield = .*/\tmax_yield = 1000/" $S/config/incoming-server.toml
sed -i "s/^nuke_dust_quality = .*/nuke_dust_quality = \"HIGH\"/" $S/config/incoming-client.toml
tools/sandbox.sh restart --size 1280x720 || { tools/sandbox.sh stop; exit 1; }
tools/sandbox.sh fx 'zoom 1.537'
AT=$(python3 -c "print(','.join(f'{20 + i / 15:.3f}' for i in range(751)))")
tools/sandbox.sh shot win_$TAG 'nuke 1000 ground near 4500' --at "$AT" --cam '7632 120 148 -521' --wav 0 --time 12000
rm -rf "$OUT/frames"; cp -r $S/fx/out/win_$TAG "$OUT/frames"
grep -E "SANDBOX shot|renderer:" $S/logs/latest.log > "$OUT/shots.log"
tools/sandbox.sh stop
cd /mnt/data/projects/incoming/.claude/worktrees/unified-wave
uv run -q tools/clip_metrics.py flicker "$OUT/frames" --map "$OUT/flicker.jpg" > "$OUT/flicker.txt" 2>&1
uv run -q tools/clip_metrics.py change "$OUT/frames" > "$OUT/change.txt" 2>&1
echo "done window clip $TAG"
