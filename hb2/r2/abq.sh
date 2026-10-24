#!/usr/bin/env bash
# Цена свечения по настройке: OFF / FAST / FULL вперемешку, по 3 прогона; настройка — файлом incoming-client.toml песочницы
# (NeoForge перечитывает его сам). $1 — дерево, $2 — эффект, $3 — моменты, $4 — камера, $5 — приставка
set -uo pipefail
T=$1 EFF=$2 AT=$3 CAM=$4 TAG=$5
C=$T/mod/run/sandbox/config/incoming-client.toml
set_q() {
  if grep -q '^bloom' "$C"; then sed -i "s/^bloom = .*/bloom = \"$1\"/" "$C"; else
    sed -i "s/^nuke_dust_quality = .*/&\nbloom = \"$1\"/" "$C"; fi
  sleep 3
}
run() { "$T/tools/sandbox.sh" shot "$1" "$EFF" --at "$AT" --cam "$CAM" --time night --no-swap >/dev/null 2>&1; }
for r in 1 2 3; do
  for q in OFF FAST FULL; do set_q $q; run ${TAG}x$(echo $q | tr A-Z a-z)$r; done
done
set_q FAST
grep "SANDBOX shot ${TAG}x" "$T/mod/run/sandbox/logs/latest.log" | sed -E 's/.*shot [a-z0-9]+x([a-z]+)([0-9])_0*([0-9.]+)s.*GPU ([0-9.]+) ms \(longest [0-9.]+, ([0-9]+) frames; heat pass ([0-9.]+).*bloom ([A-Z]+) ([0-9.]+) ms.*/\1 \3 \4 \6 \8/' | awk '{v=$3-$4; k=$1" "$2; s[k]+=v; b[k]+=$5; n[k]++} END {for (k in s) printf "%s layer-heat %.3f bloomtimer %.3f\n", k, s[k]/n[k], b[k]/n[k]}' | sort
