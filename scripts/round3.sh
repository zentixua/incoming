#!/usr/bin/env bash
# evidence shots for review round 2: new (v4 without pack, v4i with Iris) and main night extras (water, launch, SAM)
set -u
W=/mnt/data/projects/incoming/mod/run/wt-SM2; M=/mnt/data/projects/incoming/mod/run/wt-SM2-main
cd $W && tools/sandbox.sh start --wait | tail -1 || exit 1
scratch/bin/shots.sh v4; scratch/bin/shots3.sh v4; scratch/bin/shots2.sh v4; scratch/bin/shots2.sh v4 night
tools/sandbox.sh restart shaders | tail -1
scratch/bin/shots.sh v4i; scratch/bin/shots3.sh v4i; scratch/bin/shots2.sh v4i; scratch/bin/shots2.sh v4i night
tools/sandbox.sh stop | tail -1
cd $M && tools/sandbox.sh start shaders --wait | tail -1
scratch/bin/shots2.sh basei night
tools/sandbox.sh restart | tail -1
scratch/bin/shots2.sh base; scratch/bin/shots2.sh base night
tools/sandbox.sh stop | tail -1
tools/gpu_lease.sh release | tail -1
echo ROUND3 DONE
