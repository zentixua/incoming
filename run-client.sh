#!/usr/bin/env bash
set -euo pipefail
cd /mnt/data/projects/incoming/mod/run/wt-MT
export INCOMING_GPU_OWNER=MT-B3
export JAVA_TOOL_OPTIONS="-Dincoming.lodProbe.changedOnly=true -Dincoming.lodProbe.serverTransport=true"
tools/nested_kwin.sh wayland-incoming-mt-b3 1280 720 'bash /mnt/data/projects/incoming/mod/run/wt-MT/mod/build/rig/mt-b3-probe.sh' > mod/run/mt-b3/client-console.log 2>&1
