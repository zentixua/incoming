#!/bin/bash
# E1 re-measure (2e): two repeats of the young window (+4..+37 s, camera 120 m up), take 33's scene, 2560x1600, one exclusive GPU
# session; a watcher flips the preset after each logged frame (HIGH, MEDIUM, LOW), the layer timer averages the last second.
set -u
TREE=${TREE:-/mnt/data/projects/incoming/.claude/worktrees/unified-wave}
OUT=${E1_OUT:-/mnt/data/projects/incoming/mod/run/agents/unified-wave/step1/gpu2e_e1}
mkdir -p "$OUT"
cd "$TREE" || exit 1
export JAVA_HOME=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
S=mod/run/sandbox
CFG=$S/config/incoming-client.toml
CONFIGS=("HIGH 1" "MEDIUM 1" "LOW 1" "HIGH 1" "MEDIUM 1" "LOW 1" "HIGH 1" "MEDIUM 1" "LOW 1" "HIGH 1" "MEDIUM 1" "LOW 1")
apply() {  # apply "<preset> <interleave 1|0>"
  local q=${1% *} m=${1#* }
  sed -i "s/^nuke_dust_quality = .*/nuke_dust_quality = \"$q\"/" $CFG
  echo "$m" > $S/fx/dustwall_interleave; rm -f $S/fx/planB $S/fx/dustwall_div_scale
  echo "$(date +%T) apply $1" >> "$OUT/watcher.log"
}
group() {  # frames of one view: T0, T0+3, ... (12 frames)
  python3 -c "print(','.join(str($1 + 3 * i) for i in range(12)))"
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
# E1 young window, two repeats (view A, camera 120 m up; presets alternate per frame as in 2c-ii and 2d)
for r in $(seq 1 ${REPEATS:-2}); do
  watch gpu_e1r$r 12 &
  W=$!
  tools/sandbox.sh shot gpu_e1r$r 'nuke 1000 ground near 4500' --at "$(group 4)" --cam '7632 120 148 -521' --wav 0 --time 12000
  kill $W 2>/dev/null
done
grep -E "SANDBOX shot|renderer:|GL info|quality" $S/logs/latest.log > "$OUT/shots.log"
for n in $(seq -f "gpu_e1r%g" 1 ${REPEATS:-2}); do rm -rf "$OUT/$n"; cp -r $S/fx/out/$n "$OUT/$n"; done
cp $S/logs/latest.log "$OUT/latest.log"
tools/sandbox.sh stop
tools/gpu_lease.sh release
echo "done gpu run -> $OUT"
