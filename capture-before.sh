#!/usr/bin/env bash
set -euo pipefail
cd /mnt/data/projects/incoming/mod/run/wt-AS2-before
export INCOMING_GPU_OWNER=AS2
trap 'tools/sandbox.sh stop; tools/gpu_lease.sh release' EXIT
tools/sandbox.sh start --wait
tools/sandbox.sh shot road-wide-before 'real drone paved' --cam '18 5 90 0' --at 0.1,1,10,60 --wav 4
tools/sandbox.sh shot road-close-before 'real drone paved' --cam '4 3 90 0' --at 10
tools/sandbox.sh shot slab-wide-before 'real loiter concrete 1' --cam '18 5 90 0' --at 10
tools/sandbox.sh shot slab-close-before 'real loiter concrete 1' --cam '4 3 90 0' --at 10
cp -a mod/run/sandbox/fx/out/. /mnt/data/projects/incoming/mod/run/as2-evidence/
# Keep the same AS2 lease for the paired captures; only one client runs at a time.
tools/sandbox.sh stop
/mnt/data/projects/incoming/mod/run/as2-evidence/capture-after.sh
