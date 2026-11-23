#!/bin/bash
# Probe GPU feasibility run (unified-wave step 1): one exclusive GPU session at 2560x1600, take 33's scene, every preset with
# the full march and plan B, frame by frame (a watcher flips the preset and plan B after each logged frame; each setting runs
# 3 s before its frame, the layer timer averages the last second). Views: A after the passage (+4..+22 s, camera 120 m up),
# C grazing thin haze (+600..+618 s, same camera), B inside the layer (+60..+78 s, camera 30 m up).
set -u
TREE=/mnt/data/projects/incoming-wt-uwprobe
OUT=/mnt/data/projects/incoming/mod/run/agents/unified-wave/step1/gpu
mkdir -p "$OUT"
cd "$TREE" || exit 1
export JAVA_HOME=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
S=mod/run/sandbox
CFG=$S/config/incoming-client.toml
CONFIGS=("HIGH full" "HIGH planB" "MEDIUM full" "MEDIUM planB" "LOW full" "LOW planB" "HIGH full")
apply() {  # apply "<preset> <full|planB>"
  local q=${1% *} m=${1#* }
  sed -i "s/^nuke_dust_quality = .*/nuke_dust_quality = \"$q\"/" $CFG
  if [ "$m" = planB ]; then touch $S/fx/planB; else rm -f $S/fx/planB; fi
  echo "$(date +%T) apply $1" >> "$OUT/watcher.log"
}
group() {  # frames of one view: T0, T0+3, ... (7 frames)
  python3 -c "print(','.join(str($1 + 3 * i) for i in range(7)))"
}
watch() {  # flip the setting after each logged frame of take $1
  local name=$1 n=0 total=$2
  apply "${CONFIGS[0]}"
  while [ $n -lt $total ]; do
    local c; c=$(grep -c "SANDBOX shot ${name}_" $S/logs/latest.log 2>/dev/null); c=${c:-0}
    if [ "$c" -gt "$n" ]; then
      n=$c
      apply "${CONFIGS[$(( n % 7 ))]}"
    fi
    sleep 0.2
  done
}
tools/sandbox.sh stop >/dev/null 2>&1
mkdir -p $S/fx; echo 48 > $S/fx/frames_per_tick
tools/sandbox.sh start gpu --wait --size 2560x1600 || { echo "sandbox start failed"; exit 1; }
O=$S/options.txt
sed -i 's/^renderDistance:.*/renderDistance:32/' $O; grep -q '^renderClouds' $O && sed -i 's/^renderClouds:.*/renderClouds:"false"/' $O || echo 'renderClouds:"false"' >> $O
sed -i "s/^\tmax_yield = .*/\tmax_yield = 1000/" $S/config/incoming-server.toml
apply "HIGH full"
tools/sandbox.sh restart gpu --wait --size 2560x1600 || { echo "sandbox restart failed"; tools/sandbox.sh stop; exit 1; }
tools/sandbox.sh fx 'zoom 1.537'
# shot 1: views A and C, camera 120 m up
watch gpu_ac 14 &
W=$!
tools/sandbox.sh shot gpu_ac 'nuke 1000 ground near 4500' --at "$(group 4),$(group 600)" --cam '7632 120 148 -521' --wav 0 --time 12000
kill $W 2>/dev/null
# shot 2: view B, camera 30 m up (inside the layer)
watch gpu_b 7 &
W=$!
tools/sandbox.sh shot gpu_b 'nuke 1000 ground near 4500' --at "$(group 60)" --cam '7632 30 148 -521' --wav 0 --time 12000
kill $W 2>/dev/null
grep -E "SANDBOX shot|renderer:|GL info|quality" $S/logs/latest.log > "$OUT/shots.log"
for n in gpu_ac gpu_b; do rm -rf "$OUT/$n"; cp -r $S/fx/out/$n "$OUT/$n"; done
cp $S/logs/latest.log "$OUT/latest.log"
tools/sandbox.sh stop
tools/gpu_lease.sh release
rm -f $S/fx/planB
echo "done gpu run -> $OUT"
