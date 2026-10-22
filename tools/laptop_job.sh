#!/usr/bin/env bash
# Тяжёлая задача на ноутбуке хоста (проверки со всей сборкой, клиенты сценариев, монтаж трейлера, стенд):
#
#   tools/laptop_job.sh [--gpu | --light] <имя> -- <команда …>
#   tools/laptop_job.sh --status            ← что идёт и что ждёт очереди
#
# Задача идёт своей временной службой systemd --user (systemd-run --wait --collect), а не в группе того, кто её
# запустил (например, приложения Claude): по её выходу гасится всё, что осталось в группе (помощники вложенной сессии
# D-Bus не висят), а если памяти в системе всё же не хватит, первой пойдёт она (OOMScoreAdjust), а не программы
# Артёма. Пока задача идёт, ноутбук не засыпает (systemd-inhibit). Пределов памяти нет.
#
# Очередь: тяжёлая задача (по умолчанию) идёт одна — служба ждёт замка ($XDG_RUNTIME_DIR/incoming-laptop.lock) и
# держит его до конца. Две съёмки сразу или съёмка поверх прогона GameTest делили видеокарту и процессор, сервер пробы
# подвисал, и камера теряла ракету (21–22.10: 45 пересечений, 5,7 ч). Задача, чей запустивший ушёл (timeout, обрыв
# сессии), пока она ждала очереди, не начинается (код 75); начавшаяся идёт до конца своей службой.
#   --gpu    клиент или Blender: ещё и ждать, пока игра Артёма закрыта (Java из папки инстанса Prism); пока ждёт игру,
#            очередь не держит — тяжёлые задачи без видеокарты идут
#   --light  лёгкая задача без очереди (короткий скрипт; сервер, к которому следом подключится клиент-задача, — иначе
#            клиент ждал бы конца сервера) — рядом, если хватает памяти
set -euo pipefail
lock=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/incoming-laptop.lock

# игра Артёма: java, запущенная из папки инстанса Prism; не pgrep -f — тот находит свою же оболочку с этим текстом
artem_playing() {
  local p
  for p in $(pgrep -x java); do
    grep -qa 'PrismLauncher/instances/' "/proc/$p/cmdline" 2>/dev/null && return 0
  done
  return 1
}

if [ "${1:-}" = --status ]; then
  if [ -s "$lock.owner" ] && ! flock -n "$lock" true; then echo "идёт: $(cat "$lock.owner")"; else echo "очередь свободна"; fi
  systemctl --user list-units 'incoming-job-*' --state=active --no-legend --plain | awk '{print "  " $1}'
  artem_playing && echo "игра Артёма запущена: задачи --gpu ждут"
  exit 0
fi

# внутри службы: дождаться очереди (и закрытой игры), записать, чья очередь, выполнить. Вывод — без set -e: запустивший
# мог уйти, и запись в его закрытый канал не должна ронять задачу
if [ "${1:-}" = --in-unit ]; then
  set +e
  mode=$2; name=$3; caller=$4; shift 5
  [ "$mode" = light ] && exec "$@"
  gone() { ! kill -0 "$caller" 2>/dev/null; }
  exec 9>"$lock"
  said=
  while true; do
    while [ "$mode" = gpu ] && artem_playing; do
      [ -z "$said" ] && { echo "$(date +%T) $name: игра Артёма запущена — жду"; said=1; }
      gone && { echo "$name: запустивший ушёл до начала — не запускаю"; exit 75; }
      sleep 60
    done
    if flock -n 9; then
      # игра могла запуститься, пока ждали очереди: отпустить очередь и ждать игру снова
      [ "$mode" = gpu ] && artem_playing && { flock -u 9; continue; }
      gone && { echo "$name: запустивший ушёл до начала — не запускаю"; exit 75; }
      break
    fi
    [ -z "$said" ] && { echo "$(date +%T) $name ждёт очереди: идёт $(cat "$lock.owner" 2>/dev/null || echo '?')"; said=1; }
    gone && { echo "$name: запустивший ушёл до начала — не запускаю"; exit 75; }
    sleep 10
  done
  echo "$name ($mode) с $(date '+%d.%m %T'), служба $(sed 's|.*/||' /proc/self/cgroup)" > "$lock.owner"
  echo "$(date +%T) $name: очередь моя"
  "$@" 9>&-
  code=$?
  : > "$lock.owner"
  exit $code
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
