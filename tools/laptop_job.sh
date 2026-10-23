#!/usr/bin/env bash
# Тяжёлая задача на ноутбуке хоста (клиенты сценариев и песочница, Blender, монтаж трейлера, стенд):
#
#   tools/laptop_job.sh [--gpu | --light] <имя> -- <команда …>
#   tools/laptop_job.sh --status            ← что идёт и что ждёт игру Артёма
#
# Задача идёт своей временной службой systemd --user (systemd-run --wait --collect), а не в группе того, кто её
# запустил (например, приложения Claude): по её выходу гасится всё, что осталось в группе (помощники вложенной сессии
# D-Bus не висят), а если памяти в системе всё же не хватит, первой пойдёт она (OOMScoreAdjust), а не программы
# Артёма. Пока задача идёт, ноутбук не засыпает (systemd-inhibit). Пределов памяти нет.
#
# Очереди нет: с картинкой и видеокартой работает одна сессия за раз (CLAUDE.md, «Как работаем»; docs/dev-pipeline.md,
# К1) — задачи одной сессии она сама и ставит по одной. Прежний замок очереди обслуживал четыре сессии с картинкой на
# одной видеокарте (15 ч ожидания за ночь 22–23.10) и убран вместе с этой причиной.
#   --gpu    клиент или Blender: ждать, пока игра Артёма закрыта (Java из папки инстанса Prism) — машина общая. Задача,
#            чей запустивший ушёл (timeout, обрыв сессии), пока она ждала, не начинается (код 75)
#   --light  то же, что без флага: сразу (оставлен для прежних вызовов)
set -euo pipefail

# игра Артёма: java, запущенная из папки инстанса Prism; не pgrep -f — тот находит свою же оболочку с этим текстом
artem_playing() {
  local p
  for p in $(pgrep -x java); do
    grep -qa 'PrismLauncher/instances/' "/proc/$p/cmdline" 2>/dev/null && return 0
  done
  return 1
}

if [ "${1:-}" = --status ]; then
  systemctl --user list-units 'incoming-job-*' --state=active --no-legend --plain | awk '{print "  " $1}'
  artem_playing && echo "игра Артёма запущена: задачи --gpu ждут"
  exit 0
fi

# внутри службы: задача --gpu ждёт закрытой игры Артёма. Вывод — без set -e: запустивший мог уйти, и запись в его
# закрытый канал не должна ронять задачу
if [ "${1:-}" = --in-unit ]; then
  set +e
  mode=$2; name=$3; caller=$4; shift 5
  said=
  while [ "$mode" = gpu ] && artem_playing; do
    [ -z "$said" ] && { echo "$(date +%T) $name: игра Артёма запущена — жду"; said=1; }
    kill -0 "$caller" 2>/dev/null || { echo "$name: запустивший ушёл до начала — не запускаю"; exit 75; }
    sleep 60
  done
  exec "$@"
fi

mode=heavy
case "${1:-}" in --gpu) mode=gpu; shift ;; --light) mode=light; shift ;; esac
[ $# -ge 3 ] && [ "$2" = "--" ] || { echo "использование: $0 [--gpu|--light] <имя> -- <команда …> | --status" >&2; exit 2; }
name=$1; shift 2

# окружение службы — только нужное задаче (служба не наследует окружение оболочки)
envs=()
while IFS= read -r line; do envs+=(-E "$line"); done < <(env | grep -E '^(JAVA_HOME|MC_DIR|MODS|INCOMING_[A-Z_]+|PYTHON_CPU_COUNT)=' || true)

code=0
systemd-inhibit --what=sleep:idle --who="Incoming: $name" --why="проверка мода (tools/laptop_job.sh)" \
  systemd-run --user --wait --pipe --collect --quiet --same-dir --unit="incoming-job-$name-$$" \
    -p OOMScoreAdjust=500 "${envs[@]}" -- "$(readlink -f "$0")" --in-unit "$mode" "$name" $$ -- "$@" || code=$?
# строка конца — для того, кто ждёт задачу в фоне: код виден сразу, и падение на старте не теряется
echo "laptop_job $name: exit $code"
exit $code
