#!/usr/bin/env bash
# A/B cost: raid with Iris, main and new alternating, 3 rounds; layer GPU ms at 20/40/55 s
set -u
W=/mnt/data/projects/incoming/mod/run/wt-SM2; M=/mnt/data/projects/incoming/mod/run/wt-SM2-main
for r in 1 2 3; do
  for t in "$M main" "$W new"; do
    set -- $t
    cd $1 && tools/sandbox.sh start shaders --wait | tail -1
    tools/sandbox.sh shot "ab$r-$2-raid" 'raid 16 120 3' --at 20,40,55 --cam '30 25 0 0' --wav 0 | grep -c кадр
    grep -a "SANDBOX shot ab$r-$2-raid" mod/run/sandbox/logs/latest.log | sed 's/.*SANDBOX shot //' | sed -E 's/ age .*GPU ([0-9.]+) ms \(longest ([0-9.]+).*smoke: fill ([0-9.]+) screens.*/ gpu \1 max \2 fill \3/'
    tools/sandbox.sh stop | tail -1
  done
done
tools/gpu_lease.sh release | tail -1
echo AB DONE
