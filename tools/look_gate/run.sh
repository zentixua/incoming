#!/usr/bin/env bash
# Шлагбаум картинки и звука: сцены ProbeScenario (сценарий клиента probe) одной версии мода под Iris со всей сборкой.
#   tools/look_gate/run.sh <имя> <дерево incoming> [сцены через запятую] [каталог доп. модов]
#   INCOMING_LOOK_HEARING=off — без оглушения слуха (сравнение звука)
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
# видеокарта — одна задача (tools/gpu_busy.sh): чужая задача ноутбука с клиентом или Blender — ждать её конца; лёгкие
# (сервер, тесты) — рядом, если памяти хватает на клиент со всей сборкой (~13 ГБ)
source "$(dirname "$0")/../gpu_busy.sh"
# песочница эффектов (tools/sandbox.sh) не в счёт: видя клиент шлагбаума, она гасится сама за ~5 с
gpu_job() { gpu_busy "incoming-job-look-$V-" incoming-job-sandbox-; }
short_of_memory() { [ "$(awk '/MemAvailable/ {print int($2/1048576)}' /proc/meminfo)" -lt 13 ]; }
# очередь шлагбаумов — замком: проверка по процессам не видит чужую задачу, пока та ещё копирует инстанс и собирает jar
# (22.10 два прогона так шли одновременно, и кадры одного сняты при 20–30 fps вместо ~80); замок держится до выхода
mkdir -p "$ROOT"; exec 9>"$ROOT/.gpu.lock"
until flock -n 9; do echo "$(date +%T) другой прогон шлагбаума — жду"; sleep 30; done
while gpu_job || short_of_memory; do echo "$(date +%T) видеокарта или память заняты чужой задачей — жду"; sleep 30; done
python3 "$TREE/tools/prod_client.py" --copy-only --dir "$INST" || exit 1
sed -i -e 's/^fullscreen:.*/fullscreen:false/' -e 's/^soundCategory_music:.*/soundCategory_music:0.0/' "$INST/options.txt"
# INCOMING_LOOK_HEARING=off — без оглушения и звона (nuke_tinnitus = false в копии): сцены идут подряд одним игроком,
# и глухота от взрыва в 50 м переходит в следующие сцены — для сравнения источников звука её выключают
if [ "${INCOMING_LOOK_HEARING:-}" = off ]; then
  sed -i "s/^nuke_tinnitus = .*/nuke_tinnitus = false/" "$INST/config/incoming-client.toml"
  grep -q '^nuke_tinnitus = false' "$INST/config/incoming-client.toml" || { echo "nuke_tinnitus не выключен"; exit 1; }
fi
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
