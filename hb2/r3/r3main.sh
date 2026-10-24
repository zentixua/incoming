#!/usr/bin/env bash
# main при том же размере кадра, что круг 3 HB2 (песочница по умолчанию, 1280×720 → кадр 1280×692).
set -uo pipefail
R=/mnt/data/projects/incoming/mod/run
M=$R/wt-HB2-main SC=$R/wt-HB2-scratch
ALL="missile_night drone_night drone_back fire_night bomb_night"
echo "== $(date +%T) start main shaders"
"$M/tools/sandbox.sh" start shaders --wait || { echo "start failed $?"; exit 1; }
bash "$SC/shots2.sh" "$M" m3s "$ALL"
echo "== $(date +%T) restart main vanilla"
"$M/tools/sandbox.sh" restart || { echo "restart failed $?"; exit 1; }
bash "$SC/shots2.sh" "$M" m3v "missile_night drone_night drone_back"
"$M/tools/sandbox.sh" stop
"$M/tools/gpu_lease.sh" release
echo "== $(date +%T) done"
