#!/usr/bin/env bash
set -euo pipefail
cd /mnt/data/projects/incoming/mod/run/wt-OL
tools/sandbox.sh shot ol-game-nuke-300 'nuke 15 ground near 290' --no-swap --at -.2,.1,.5,2,6,12 --cam '10 2 0 2' --time day --wav 14
tools/sandbox.sh shot ol-game-nuke-700 'nuke 15 ground near 690' --no-swap --at -.2,.1,.5,2,6,12 --cam '10 2 0 2' --time day --wav 14
