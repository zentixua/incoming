#!/usr/bin/env bash
set -euo pipefail
cd /mnt/data/projects/incoming/mod/run/wt-AS2
export INCOMING_GPU_OWNER=AS2
trap 'tools/sandbox.sh stop; tools/gpu_lease.sh release' EXIT
tools/sandbox.sh start --wait
tools/sandbox.sh shot r2-road-wide 'real drone paved' --cam '18 5 90 0' --at 0.1,1,10,60 --wav 4
tools/sandbox.sh shot r2-road-close 'real drone paved' --cam '4 3 90 0' --at 10 --wav 4
tools/sandbox.sh shot r2-slab-wide 'real loiter concrete 1' --cam '18 5 90 0' --at 10 --wav 4
tools/sandbox.sh shot r2-slab-close 'real loiter concrete 1' --cam '4 3 90 0' --at 10 --wav 4
tools/sandbox.sh shot r2-earth-wide 'real drone' --cam '18 5 90 0' --at 10 --wav 4
tools/sandbox.sh shot r2-depth001-close 'surface 0.01 black' --cam '4 3 90 0' --at 1 --wav 0
tools/sandbox.sh shot r2-depth040-close 'surface 0.4 black' --cam '4 3 90 0' --at 1 --wav 0
tools/sandbox.sh shot r2-depth090-close 'surface 0.9 black' --cam '4 3 90 0' --at 1 --wav 0
tools/sandbox.sh shot r2-depth001-wide 'surface 0.01 black' --cam '18 5 90 0' --at 1 --wav 0
tools/sandbox.sh shot r2-depth090-wide 'surface 0.9 black' --cam '18 5 90 0' --at 1 --wav 0
mkdir -p /mnt/data/projects/incoming/mod/run/as2-evidence/r2
cp -a mod/run/sandbox/fx/out/r2-* /mnt/data/projects/incoming/mod/run/as2-evidence/r2/
