#!/usr/bin/env bash
set -euo pipefail
cd /mnt/data/projects/incoming/mod/run/wt-MT
export INCOMING_GPU_OWNER=MT-LOD
tools/nested_kwin.sh wayland-incoming-mt-lod 1280 720 'bash /mnt/data/projects/incoming/mod/run/wt-MT/mod/build/rig/mt-lod-probe.sh' > mod/run/mt-lod-buffer/client-console.log 2>&1
