#!/usr/bin/env bash
# Стенд сцен: прогон одной версии Incoming по пакам или пара с принятым (README.md).
#
#   tools/stand/run.sh <имя> <jar> [--against <jar принятого>] [--packs none,complementary,photon] [--scenes 100,…]
#                      [--against-label …] [--size 2560x1600] [--label …] [--tries 1] [--no-warmup]
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
args=() label= against= against_label= play_only=()
while [ $# -gt 0 ]; do
  case $1 in
    --label) label=$2; shift 2 ;;
    --against) against=$2; shift 2 ;;
    --against-label) against_label=$2; shift 2 ;;
    --tries) play_only+=(--tries "$2"); shift 2 ;;
    --no-warmup) play_only+=(--no-warmup); shift ;;
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
  pair=(--against "$BJAR" --against-label "${against_label:-$BLABEL}" --against-spec "$against")
fi
# аренда отпускается при любом выходе, и по сигналу тоже: без ловушки SIGTERM убил бы скрипт до строки release, и
# видеокарта стояла бы занятой до конца срока аренды. Сигнал одному run.sh bash обработает после выхода задачи (клиент
# к тому времени закрыт). Сигнал всей группе (Ctrl+C) гасит и laptop_job.sh, а сама задача — своя служба systemd — идёт
# дальше: release, пока её клиент держит замок, отказывает (75). Тогда отпускает фоновый повтор, когда клиент кончится.
release_lease() {
  "$ROOT/tools/gpu_lease.sh" release >/dev/null 2>&1
  [ $? -eq 75 ] || return 0
  setsid nohup bash -c 'for _ in $(seq 720); do sleep 5; "$1" release >/dev/null 2>&1; [ $? -eq 75 ] || exit 0; done' \
    _ "$ROOT/tools/gpu_lease.sh" >/dev/null 2>&1 &
}
trap release_lease EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
"$ROOT/tools/laptop_job.sh" --gpu --wait "stand-$NAME" -- python3 "$HERE/stand.py" play "$NAME" "$JAR" --spec "$SPEC" --label "$LABEL" \
  "${pair[@]}" "${play_only[@]}" "${args[@]}"
code=$?
# где итог — пишет play («итог в …»): каталоги стенда общие у деревьев репозитория (stand.py, STAND)
echo "stand $NAME: exit $code"
exit $code
