#!/usr/bin/env bash
# Шлагбаум картинки и звука: сцены ProbeScenario (сценарий клиента probe) одной версии мода под Iris со всей сборкой.
#   tools/look_gate/run.sh <имя> <дерево incoming> [сцены через запятую] [каталог доп. модов]
# Копия инстанса Артёма → $LOOK_ROOT/inst-<имя> (его инстанс не трогается), клиент — prod_client.py дерева (сценарный
# jar этого дерева); ждёт, пока игра Артёма закрыта и нет чужой задачи ноутбука; игра запустилась — клиент гасится, код 75.
# Итог — $LOOK_ROOT/out/<имя>/: кадры, лог, audio.wav, probe-frames.txt, gpu.csv. Запускать через tools/laptop_job.sh.
set -uo pipefail
V=$1; TREE=$2; CASES=${3:-}; EXTRA=${4:--}
ROOT=${LOOK_ROOT:-/mnt/data/projects/airstrike/mod/run/look-gate}
INST=$ROOT/inst-$V; OUT=$ROOT/out/$V
export JAVA_HOME=${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}
artem() { local p; for p in $(pgrep -x java); do tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q 'PrismLauncher/instances/' && return 0; done; return 1; }
while artem; do echo "$(date +%T) игра Артёма запущена — жду"; sleep 60; done
# видеокарта — одна задача: чужая задача ноутбука идёт — ждать её конца
others() { systemctl --user list-units 'incoming-job-*' --state=active --no-legend --plain | awk '{print $1}' | grep -v -- "-look-$V" | grep -q .; }
while others; do echo "$(date +%T) идёт чужая задача ноутбука — жду"; sleep 30; done
python3 "$TREE/tools/prod_client.py" --copy-only --dir "$INST" || exit 1
sed -i -e 's/^fullscreen:.*/fullscreen:false/' -e 's/^soundCategory_music:.*/soundCategory_music:0.0/' "$INST/options.txt"
if [ "$EXTRA" != - ]; then cp -v "$EXTRA"/*.jar "$INST/mods/"; fi
rm -rf "$OUT"; mkdir -p "$OUT"
PROPS=(--prop incoming.frametimes=false)
[ -n "$CASES" ] && PROPS+=(--prop "incoming.probe.cases=$CASES")
( while true; do nvidia-smi --query-gpu=timestamp,memory.used,utilization.gpu --format=csv,noheader >> "$OUT/gpu.csv"; sleep 2; done ) &
GPU=$!
python3 "$TREE/tools/prod_client.py" probe --no-copy --dir "$INST" --size 1920x1080 "${PROPS[@]}" --seconds 3600 > "$OUT/client.out" 2>&1 &
PC=$!
code=
while kill -0 $PC 2>/dev/null; do
  if artem; then echo "$(date +%T) игра Артёма запустилась — гашу клиент"; kill -TERM $PC; wait $PC; code=75; break; fi
  sleep 5
done
[ -z "$code" ] && { wait $PC; code=$?; }
kill $GPU 2>/dev/null
cp -r "$INST/screenshots" "$OUT/" 2>/dev/null
cp "$INST/logs/latest.log" "$INST/logs/probe-frames.txt" "$INST/audio.wav" "$OUT/" 2>/dev/null
cp -r "$INST/crash-reports" "$OUT/" 2>/dev/null
grep -c 'SCENARIO probe shot' "$OUT/latest.log"
grep -E 'SCENARIO probe (case|.* blast|.* launch|.*no )|SCENARIO (done|fx)|Exception' "$OUT/latest.log" | head -40
echo "look $V exit $code"
exit $code
