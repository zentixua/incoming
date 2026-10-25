#!/usr/bin/env bash
# Стенд сцен: прогон одной версии Incoming по пакам или пара с принятым (README.md).
#
#   tools/stand/run.sh <имя> <jar> [--against <jar принятого>] [--packs none,complementary,photon] [--scenes 100,…]
#                      [--size 2560x1600] [--label …]
#
# <jar> — выпуск (v3.7.0), ci:<ветка|SHA> (артефакт incoming-jar прогона build) или путь к jar. Сначала без видеокарты:
# jar (и jar принятого), Sodium, Iris и шейдерпаки сборки по sha512, скрипт клиента (stand.py prepare). Потом клиенты —
# одной задачей ноутбука с видеокартой (tools/laptop_job.sh --gpu --wait: аренда в очереди, ждёт закрытой игры Артёма;
# игра запустилась — клиент гасится, код 75), каждый во вложенном KWin без окна и без звука хоста; с --against — парой
# A1, B, A2 на каждый пак под одной арендой. В конце аренда отпускается: следующий в очереди берёт видеокарту сразу.
# Итог — out/<имя>/ стенда (mod/run/stand основной копии): results.json, у пары — verdict.json и прогоны A1, B, A2.
set -uo pipefail
[ $# -ge 2 ] || { sed -n '2,13p' "$0"; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
NAME=$1 SPEC=$2; shift 2
export JAVA_HOME=${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}
# владелец аренды видеокарты — стенд (дерево Claude назвало бы её по своему имени)
export INCOMING_GPU_OWNER=${INCOMING_GPU_OWNER:-stand}
# у play нет своих --label и --spec из командной строки: подпись и путь — из prepare
args=() label= against=
while [ $# -gt 0 ]; do
  case $1 in
    --label) label=$2; shift 2 ;;
    --against) against=$2; shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done
prepare() {  # <spec> [--label …] → строка «JAR	путь	подпись»
  python3 "$HERE/stand.py" prepare "$@" "${args[@]}" | tee /dev/stderr | grep $'^JAR\t' | tail -n 1
}
line=$(prepare "$SPEC" ${label:+--label "$label"}) || exit 1
[ -n "$line" ] || { echo "stand $NAME: prepare не дал jar" >&2; exit 1; }
IFS=$'\t' read -r _ JAR LABEL <<< "$line"
pair=()
if [ -n "$against" ]; then
  bline=$(prepare "$against") || exit 1
  [ -n "$bline" ] || { echo "stand $NAME: prepare не дал jar принятого" >&2; exit 1; }
  IFS=$'\t' read -r _ BJAR BLABEL <<< "$bline"
  pair=(--against "$BJAR" --against-label "$BLABEL" --against-spec "$against")
fi
"$ROOT/tools/laptop_job.sh" --gpu --wait "stand-$NAME" -- python3 "$HERE/stand.py" play "$NAME" "$JAR" --spec "$SPEC" --label "$LABEL" \
  "${pair[@]}" "${args[@]}"
code=$?
"$ROOT/tools/gpu_lease.sh" release >/dev/null 2>&1 || true
# где итог — пишет play («итог в …»): каталоги стенда общие у деревьев репозитория (stand.py, STAND)
echo "stand $NAME: exit $code"
exit $code
