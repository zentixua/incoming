#!/usr/bin/env bash
# Финальный код круга 4: OFF / FAST / FULL в одной сессии, потом карта «закрыто» (отладка, не в коммит).
set -uo pipefail
R=/mnt/data/projects/incoming/mod/run
T=$R/wt-HB2 SC=$R/wt-HB2-scratch
CFG=$T/mod/run/sandbox/config/incoming-client.toml
OUT=$T/mod/src/main/resources/assets/incoming/shaders/core/fx_bloom.fsh
bloom() { sed -i "s/^bloom = .*/bloom = \"$1\"/" "$CFG"; sleep 5; grep '^bloom' "$CFG"; }
sed -i 's/^bloom = .*/bloom = "FAST"/' "$CFG"
"$T/tools/sandbox.sh" start shaders --wait || { echo "start failed $?"; exit 1; }
cd "$T"
bloom OFF;  bash "$SC/shots2.sh" "$T" r6o "missile_night drone_back drone_night"
bloom FAST; bash "$SC/shots2.sh" "$T" r6s "missile_night drone_back drone_night fire_night bomb_night"
bloom FULL; bash "$SC/shots2.sh" "$T" r6F "missile_night drone_back"
bloom FAST
cp "$OUT" /tmp/fx_bloom.r6.keep
python3 - "$OUT" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace("    fragColor = vec4(shown, 1.0);\n}","    fragColor = vec4(shown, 1.0);\n    fragColor = vec4(covered, covered, covered, 1.0);\n}",1)
open(p,'w').write(s)
PY
"$T/tools/sandbox.sh" swap; echo "swap $?"
bash "$SC/shots2.sh" "$T" r6dbg "missile_night"
cp /tmp/fx_bloom.r6.keep "$OUT"; rm /tmp/fx_bloom.r6.keep
"$T/tools/sandbox.sh" stop
"$T/tools/gpu_lease.sh" release
echo "== $(date +%T) done"
