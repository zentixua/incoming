#!/usr/bin/env bash
# Цена прохода свечения: текущий код (во весь кадр + полуразмер) против того же без полуразмера — горячей заменой в одной сессии.
set -uo pipefail
R=/mnt/data/projects/incoming/mod/run
T=$R/wt-HB2
F=$T/mod/src/main/java/ua/zentix/incoming/client/fx/layer/FxQuads.java
CFG=$T/mod/run/sandbox/config/incoming-client.toml
sed -i 's/^bloom = .*/bloom = "FAST"/' "$CFG"
"$T/tools/sandbox.sh" start shaders --size 2560x1600 --wait || { echo "start failed $?"; exit 1; }
cd "$T"
shots() { for r in 1 2 3; do "$T/tools/sandbox.sh" shot "$1$r" 'blast missile 1' --at 2,3 --cam '60 8 90 10' --time night --no-swap >/dev/null 2>&1; done; }
shots spA
cp "$F" /tmp/FxQuads.split.keep
sed -i 's|glowReach = Math.max(glowReach, keys\[i\] + half / unfold);|glowReach = Math.max(glowReach, keys[i]);|' "$F"
"$T/tools/sandbox.sh" swap; echo "swap $?"
shots spB
cp /tmp/FxQuads.split.keep "$F"; rm /tmp/FxQuads.split.keep
"$T/tools/sandbox.sh" stop
"$T/tools/gpu_lease.sh" release
L=$T/mod/run/sandbox/logs/latest.log
for v in spA spB; do grep "SANDBOX shot $v" "$L" | grep -o '_0*[0-9.]*s\.png\|glow pass [0-9.]*' | paste - - | sed "s/^/$v /"; done
echo "== $(date +%T) done"
