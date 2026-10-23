#!/usr/bin/env bash
# Тяжёлая задача на ноутбуке хоста (клиенты сценариев и песочница, Blender, монтаж трейлера, стенд):
#
#   tools/laptop_job.sh [--gpu [--wait] | --light] <имя> -- <команда …>
#   tools/laptop_job.sh --status            ← что идёт, у кого видеокарта, ждёт ли кто игру Артёма
#
# Задача идёт своей временной службой systemd --user (systemd-run --wait --collect), а не в группе того, кто её
# запустил (например, приложения Claude): по её выходу гасится всё, что осталось в группе (помощники вложенной сессии
# D-Bus не висят), а если памяти в системе всё же не хватит, первой пойдёт она (OOMScoreAdjust), а не программы
# Артёма. Пока задача идёт, ноутбук не засыпает (systemd-inhibit). Пределов памяти нет.
#
# Очереди задач нет: с картинкой и видеокартой работает одна сессия за раз, и это держит аренда видеокарты
# (tools/gpu_lease.sh), а не очередь каждой задачи.
#   --gpu    клиент или Blender: берёт аренду видеокарты своей сессии (владелец — по дереву) до запуска службы; она у
#            другой сессии — отказ 75 со строкой «видеокарта у …, с …» (с --wait — ждать её); пока задача идёт, аренда
#            продлевается. Ещё ждёт, пока игра Артёма закрыта (Java из папки инстанса Prism) — машина общая. Задача,
#            чей запустивший ушёл (timeout, обрыв сессии), пока она ждала игру, не начинается (код 75)
#   --light  то же, что без флага: сразу, без аренды (GameTest, сборка, сервер — видеокарта им не нужна)
set -euo pipefail

# игра Артёма: java, запущенная из папки инстанса Prism; не pgrep -f — тот находит свою же оболочку с этим текстом
artem_playing() {
  local p
  for p in $(pgrep -x java); do
    grep -qa 'PrismLauncher/instances/' "/proc/$p/cmdline" 2>/dev/null && return 0
  done
  return 1
}

source "$(dirname "$(readlink -f "$0")")/gpu_lease.sh"

if [ "${1:-}" = --status ]; then
  lease_status || true
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
    lease_renew "$INCOMING_GPU_OWNER" || true
    sleep 30
  done
  [ "$mode" = gpu ] || exec "$@"
  # аренда — своей сессии, пока задача идёт (истекает за INCOMING_GPU_LEASE_IDLE без работы)
  ( while sleep 30; do lease_renew "$INCOMING_GPU_OWNER" || true; done ) &
  renew=$!
  "$@"
  code=$?
  kill $renew 2>/dev/null
  lease_renew "$INCOMING_GPU_OWNER" || true
  exit $code
fi

mode=heavy wait=
case "${1:-}" in --gpu) mode=gpu; shift ;; --light) mode=light; shift ;; esac
[ "$mode" = gpu ] && [ "${1:-}" = --wait ] && { wait=--wait; shift; }
[ $# -ge 3 ] && [ "$2" = "--" ] || { echo "использование: $0 [--gpu [--wait]|--light] <имя> -- <команда …> | --status" >&2; exit 2; }
name=$1; shift 2

# видеокарта — аренда своей сессии: у другой — отказ (или ждать с --wait); до службы, чтобы отказ пришёл сразу
if [ "$mode" = gpu ]; then
  export INCOMING_GPU_OWNER="${INCOMING_GPU_OWNER:-$(lease_owner)}"
  lease_take "$INCOMING_GPU_OWNER" "$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)" $wait || { echo "laptop_job $name: exit 75"; exit 75; }
fi

# окружение службы — только нужное задаче (служба не наследует окружение оболочки)
envs=()
while IFS= read -r line; do envs+=(-E "$line"); done < <(env | grep -E '^(JAVA_HOME|MC_DIR|MODS|INCOMING_[A-Z_]+|PYTHON_CPU_COUNT|XDG_RUNTIME_DIR)=' || true)

code=0
systemd-inhibit --what=sleep:idle --who="Incoming: $name" --why="проверка мода (tools/laptop_job.sh)" \
  systemd-run --user --wait --pipe --collect --quiet --same-dir --unit="incoming-job-$name-$$" \
    -p OOMScoreAdjust=500 "${envs[@]}" -- "$(readlink -f "$0")" --in-unit "$mode" "$name" $$ -- "$@" || code=$?
# строка конца — для того, кто ждёт задачу в фоне: код виден сразу, и падение на старте не теряется
echo "laptop_job $name: exit $code"
exit $code
