#!/usr/bin/env bash
set -euo pipefail
cd /mnt/data/projects/incoming/mod/run/wt-OL
tools/sandbox.sh fx 'scenery grass'
tools/sandbox.sh shot ol-main-grass 'blast drone 1' --no-swap --at .1,.3,.7,2,6,12 --cam '40 3 0 2' --wav 12
tools/sandbox.sh shot ol-main-grass-far 'blast drone 1' --no-swap --at .1,.3,.7,2,6,12 --cam '200 3 0 2' --wav 12
tools/sandbox.sh fx 'scenery forest'
tools/sandbox.sh shot ol-main-forest 'blast drone 1' --no-swap --at .1,.3,.7,2,6,12 --cam '60 5 0 2' --wav 12
tools/sandbox.sh fx 'scenery grass'
tools/sandbox.sh shot ol-main-high 'blast missile 1' --no-swap --at .1,.3,.7,2,6,12 --cam '70 20 0 2' --wav 12
tools/sandbox.sh shot ol-main-high-night 'blast missile 1' --no-swap --at .1,.3,.7,2,6,12 --cam '70 20 0 2' --time night --wav 12
