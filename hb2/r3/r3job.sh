#!/usr/bin/env bash
# Круг 3 ревью #442: ночные кадры после правки нормировки. HB2 под Iris (FAST, затем FULL), HB2 без пака (FAST),
# main без пака — пожар и бомба (остальное main — m2s_/m2v_ того же дня, dc36fcba).
set -uo pipefail
R=/mnt/data/projects/incoming/mod/run
T=$R/wt-HB2 M=$R/wt-HB2-main SC=$R/wt-HB2-scratch
CFG=$T/mod/run/sandbox/config/incoming-client.toml
ALL="missile_night drone_night drone_back fire_night bomb_night"
bloom() { sed -i "s/^bloom = .*/bloom = \"$1\"/" "$CFG"; sleep 5; grep '^bloom' "$CFG"; }
echo "== $(date +%T) start HB2 shaders"
"$T/tools/sandbox.sh" start shaders --wait || { echo "start failed $?"; exit 1; }
bloom FAST
bash "$SC/shots2.sh" "$T" r3s "$ALL"
bloom FULL
bash "$SC/shots2.sh" "$T" r3sF "missile_night drone_back fire_night"
bloom FAST
echo "== $(date +%T) restart HB2 vanilla"
"$T/tools/sandbox.sh" restart || { echo "restart failed $?"; exit 1; }
bash "$SC/shots2.sh" "$T" r3v "$ALL"
"$T/tools/sandbox.sh" stop
echo "== $(date +%T) start main vanilla"
"$M/tools/sandbox.sh" start || { echo "main start failed $?"; exit 1; }
bash "$SC/shots2.sh" "$M" m2v "fire_night bomb_night"
"$M/tools/sandbox.sh" stop
"$T/tools/gpu_lease.sh" release
echo "== $(date +%T) done"
