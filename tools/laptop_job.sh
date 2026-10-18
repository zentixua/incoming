#!/usr/bin/env bash
# Тяжёлая задача на ноутбуке хоста (проверки со всей сборкой, клиенты сценариев, монтаж трейлера, стенд):
#
#   tools/laptop_job.sh <имя> [--log ФАЙЛ] -- <команда …>
#
# Ждёт конца задачи и выходит с её кодом: запуск в фоне (run_in_background) будит того, кто её запустил, и при
# падении на старте. По концу — строка «laptop_job <имя>: exit N» и хвост лога (`--log`, 20 строк), если задача пишет
# в файл, а не в вывод.
#
# Задача идёт своей временной службой systemd --user (systemd-run --wait --collect), а не в группе того, кто её
# запустил (например, приложения Claude): по её выходу гасится всё, что осталось в группе (помощники вложенной сессии
# D-Bus не висят), а если памяти в системе всё же не хватит, первой пойдёт она (OOMScoreAdjust), а не программы
# Артёма. Пока задача идёт, ноутбук не засыпает (systemd-inhibit). Пределов памяти нет: одновременно — одна задача
# со всей сборкой или стенд, лёгкие — рядом, только если по сумме помещаются в ОЗУ.
set -euo pipefail
usage() { echo "использование: $0 <имя> [--log ФАЙЛ] -- <команда …>" >&2; exit 2; }
[ $# -ge 3 ] || usage
name=$1; shift
log=""
if [ "$1" = "--log" ]; then [ $# -ge 4 ] || usage; log=$2; shift 2; fi
[ "$1" = "--" ] || usage
shift

# окружение службы — только нужное задаче (служба не наследует окружение оболочки)
envs=()
while IFS= read -r line; do envs+=(-E "$line"); done < <(env | grep -E '^(JAVA_HOME|MC_DIR|MODS|INCOMING_[A-Z_]+|PYTHON_CPU_COUNT)=' || true)

set +e
systemd-inhibit --what=sleep:idle --who="Incoming: $name" --why="проверка мода (tools/laptop_job.sh)" \
  systemd-run --user --wait --pipe --collect --quiet --same-dir --unit="incoming-job-$name-$$" \
    -p OOMScoreAdjust=500 "${envs[@]}" -- "$@"
rc=$?
echo "laptop_job $name: exit $rc"
if [ -n "$log" ] && [ -f "$log" ]; then tail -n 20 "$log"; fi
exit "$rc"
