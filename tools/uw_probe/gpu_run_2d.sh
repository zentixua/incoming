#!/bin/bash
# GPU cause run (unified-wave step 2d): the young view at HIGH, frames every 2 s at +16..+38 s, each switch between two
# baseline frames (fx/dustwall_debug: tufts, material, terrain atlas, outdoor-air window, interval bookkeeping, wrappers): one exclusive GPU session at 2560x1600, take 33's scene, every preset with
# the full march and plan B, frame by frame (a watcher flips the preset and plan B after each logged frame; each setting runs
# 3 s before its frame, the layer timer averages the last second). Views: A after the passage (+4..+22 s, camera 120 m up),
# C grazing thin haze (+600..+618 s, same camera), B inside the layer (+60..+78 s, camera 30 m up).
set -u
TREE=${TREE:-/mnt/data/projects/incoming/.claude/worktrees/unified-wave}
OUT=/mnt/data/projects/incoming/mod/run/agents/unified-wave/step1/gpu2d${GPU_TAG:-}
mkdir -p "$OUT"
cd "$TREE" || exit 1
export JAVA_HOME=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
S=mod/run/sandbox
CFG=$S/config/incoming-client.toml
if [ -n "${BASE_ONLY:-}" ]; then CONFIGS=("0 0 0 0 0 0" "0 0 0 0 0 0" "0 0 0 0 0 0" "0 0 0 0 0 0" "0 0 0 0 0 0" "0 0 0 0 0 0" "0 0 0 0 0 0" "0 0 0 0 0 0" "0 0 0 0 0 0" "0 0 0 0 0 0" "0 0 0 0 0 0" "0 0 0 0 0 0"); else CONFIGS=("0 0 0 0 0 0" "0 0 1 0 0 0" "0 0 0 0 0 0" "0 0 0 1 0 0" "0 0 0 0 0 0" "0 0 0 0 1 0" "0 0 0 0 0 0" "0 0 0 0 0 1" "0 0 0 0 0 0" "0 0 1 1 1 1" "0 0 0 0 0 0" "0 0 0 0 0 0"); fi
apply() {  # apply "<tufts> <material> <terrain> <open> <intervals> <wrappers>" (1: off) at HIGH
  sed -i "s/^nuke_dust_quality = .*/nuke_dust_quality = \"HIGH\"/" $CFG
  echo "$1" > $S/fx/dustwall_debug; rm -f $S/fx/planB $S/fx/dustwall_div_scale $S/fx/dustwall_interleave
  echo "$(date +%T) apply $1" >> "$OUT/watcher.log"
}
group() {  # frames of one view: T0, T0+3, ... (12 frames)
  python3 -c "print(','.join(str($1 + 2 * i) for i in range(12)))"
}
watch() {  # flip the setting after each logged frame of take $1
  local name=$1 n=0 total=$2
  apply "${CONFIGS[0]}"
  while [ $n -lt $total ]; do
    local c; c=$(grep -c "SANDBOX shot ${name}_" $S/logs/latest.log 2>/dev/null); c=${c:-0}
    if [ "$c" -gt "$n" ]; then
      n=$c
      apply "${CONFIGS[$(( n % 12 ))]}"
    fi
    sleep 0.2
  done
}
tools/sandbox.sh stop >/dev/null 2>&1
mkdir -p $S/fx; echo 2 > $S/fx/frames_per_tick  # cost frames need no exact second: 48 renders a tick stretched the run 3x
tools/sandbox.sh start gpu --wait --size 2560x1600 || { echo "sandbox start failed"; exit 1; }
O=$S/options.txt
sed -i 's/^renderDistance:.*/renderDistance:32/' $O; grep -q '^renderClouds' $O && sed -i 's/^renderClouds:.*/renderClouds:"false"/' $O || echo 'renderClouds:"false"' >> $O
sed -i "s/^\tmax_yield = .*/\tmax_yield = 1000/" $S/config/incoming-server.toml
apply "HIGH full"
tools/sandbox.sh restart gpu --wait --size 2560x1600 || { echo "sandbox restart failed"; tools/sandbox.sh stop; exit 1; }
tools/sandbox.sh fx 'zoom 1.537'
# view A, the young window
watch gpu_a 12 &
W=$!
tools/sandbox.sh shot gpu_a 'nuke 1000 ground near 4500' --at "$(group 16)" --cam '7632 120 148 -521' --wav 0 --time 12000
kill $W 2>/dev/null
grep -E "SANDBOX shot|renderer:|GL info|quality" $S/logs/latest.log > "$OUT/shots.log"
for n in gpu_a; do rm -rf "$OUT/$n"; cp -r $S/fx/out/$n "$OUT/$n"; done
cp $S/logs/latest.log "$OUT/latest.log"
tools/sandbox.sh stop
tools/gpu_lease.sh release
rm -f $S/fx/planB $S/fx/dustwall_div_scale $S/fx/dustwall_interleave $S/fx/dustwall_debug
echo "done gpu run -> $OUT"
