#!/usr/bin/env bash
# Аренда видеокарты ноутбука: с картинкой работает один владелец за раз, и это держит файл, а не память.
#
#   tools/gpu_lease.sh status               кто держит, с какого времени, из какого дерева, когда работал последний раз
#   tools/gpu_lease.sh take [--wait]        взять (своя — продлить); чужая — отказ 75 со строкой «видеокарта у …»,
#                                           с --wait — ждать, пока её отпустят или она истечёт
#   tools/gpu_lease.sh release              отпустить свою (закончил с картинкой; пока свой клиент идёт — отказ: сначала
#                                           tools/sandbox.sh stop)
#   source tools/gpu_lease.sh               функции lease_owner, lease_take, lease_renew, lease_release, lease_status
#
# Владелец — сессия: INCOMING_GPU_OWNER или имя дерева (mod/run/wt-<S>[-что угодно] → <S>, иначе имя папки), так что
# деревья одной сессии (wt-N3 и wt-N3-main) — один владелец. Берёт её каждый клиент на видеокарте сам —
# `tools/nested_kwin.sh` (через него идут все: песочница, сценарии, prod_client, шлагбаум, трейлер), — и задачи
# `tools/laptop_job.sh --gpu` (Blender); песочница и задачи --gpu спрашивают её ещё до сборки, чтобы отказ пришёл сразу.
# Пока клиент или задача идёт, аренда продлевается раз в 30 с; без работы на видеокарте истекает через
# INCOMING_GPU_LEASE_IDLE секунд (600): правка кода между кадрами её не теряет, а брошенная сессия не держит видеокарту
# вечно. Клиент на видеокарте — один и внутри сессии (замок `$LEASE.client`, держит nested_kwin.sh и все его потомки):
# свой шлагбаум рядом со своей песочницей получает отказ «на видеокарте идёт клиент …», а не делит кадры. Своя сессия
# перезапускает свою песочницу без ожидания; тяжёлые задачи без видеокарты (GameTest, сборка) аренды не берут и
# песочницу не гасят. Игра Артёма гасит всё, что идёт, при любой аренде.
#
# Клиент без замка тоже виден: всякий живой вложенный KWin (`kwin_wayland --virtual` — через него идут все клиенты, и
# скриптов тех версий, что о замке не знают) не своей работы — «на видеокарте идёт клиент без аренды». 23.10 песочница,
# поднятая скриптом до аренды, шла без замка, аренда отвечала «свободна», и клиент другой сессии её выбил. Игра Артёма —
# java, что идёт в папке его инстанса Prism или в группе Prism (artem_playing), а не строка в командной строке: моды
# песочницы с MC_DIR на инстанс тоже лежат по пути инстанса.
#
# Почему файл и flock(1), а не правило на память или очередь задач: правило держалось текстом, и 23.10 видеокарту
# брали три работы разом — одна песочница перезапускалась пять раз. Семафоры вроде `sem` (GNU parallel) и очереди
# (`ts`, `nq`) держат процесс, а не сессию: между кадрами, пока сессия правит код, процесса нет, и соседняя задача
# заняла бы видеокарту. Файл живёт в XDG_RUNTIME_DIR
# (tmpfs): перезагрузка его снимает. Проверка — tools/test_gpu_lease.sh (CI).
LEASE=${INCOMING_GPU_LEASE:-${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/incoming-gpu.lease}
LEASE_IDLE=${INCOMING_GPU_LEASE_IDLE:-600}
# клиент на видеокарте: замок и кто его держит (владелец, дерево, с какого времени, что за клиент)
GPU_CLIENT=$LEASE.client

# владелец по дереву $1 (по умолчанию — дерево этого скрипта)
lease_owner() {
  [ -n "${INCOMING_GPU_OWNER:-}" ] && { echo "$INCOMING_GPU_OWNER"; return; }
  # настоящий путь: главное дерево видно и через симлинк (airstrike → incoming), а владелец у него один
  local name; name=$(basename "$(readlink -m "${1:-$(dirname "${BASH_SOURCE[0]}")/..}")")
  if [[ $name =~ ^wt-([^-]+) ]]; then echo "${BASH_REMATCH[1]}"; else echo "$name"; fi
}

# строка аренды: владелец, дерево, с (эпоха), последняя работа (эпоха); под замком $LEASE.lock
_lease_read() { [ -s "$LEASE" ] && IFS=$'\t' read -r L_OWNER L_TREE L_SINCE L_SEEN < "$LEASE" || { L_OWNER=; L_TREE=; L_SINCE=0; L_SEEN=0; }; }
_lease_write() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" > "$LEASE.tmp" && mv "$LEASE.tmp" "$LEASE"; }
_lease_free() { [ -z "$L_OWNER" ] || [ $(( $(date +%s) - L_SEEN )) -gt "$LEASE_IDLE" ]; }
_lease_hm() { date -d "@$1" +%H:%M; }

# занятая чужим — строка «видеокарта у …»
lease_status() {
  local who
  _lease_read
  if _lease_free; then
    who=$(gpu_client_busy) && { echo "аренды нет, но $who"; return 0; }
    echo "видеокарта свободна"; return 1
  fi
  echo "видеокарта у $L_OWNER ($L_TREE), с $(_lease_hm "$L_SINCE"), последняя работа $(( ($(date +%s) - L_SEEN) / 60 )) мин назад" \
    "(свободна — tools/gpu_lease.sh release в её дереве или через $(( (LEASE_IDLE - $(date +%s) + L_SEEN) / 60 + 1 )) мин без работы)"
}

# lease_take <владелец> <дерево> [--wait] — 0: аренда своя; 75: чужая (строка — в stderr)
lease_take() {
  local owner=$1 tree wait=${3:-} fd said= now who
  tree=$(readlink -m "$2")
  while true; do
    exec {fd}>"$LEASE.lock"; flock "$fd"
    _lease_read; now=$(date +%s)
    # свободна, а клиент без замка идёт (скрипт до аренды, чужой запуск мимо неё) — занята им
    if _lease_free && who=$(gpu_client_busy); then
      exec {fd}>&-
      [ "$wait" = --wait ] || { echo "$who" >&2; return 75; }
      [ -z "$said" ] && { echo "$(date +%T) $who — жду" >&2; said=1; }
      sleep 10; continue
    fi
    if _lease_free || [ "$L_OWNER" = "$owner" ]; then
      [ "$L_OWNER" = "$owner" ] && ! _lease_free || L_SINCE=$now
      _lease_write "$owner" "$tree" "$L_SINCE" "$now"
      exec {fd}>&-
      return 0
    fi
    exec {fd}>&-
    [ "$wait" = --wait ] || { lease_status >&2; return 75; }
    [ -z "$said" ] && { echo "$(date +%T) $(lease_status) — жду" >&2; said=1; }
    sleep 10
  done
}

# продлить свою (идёт работа на видеокарте); свободную (отпустили или истекла, а работа идёт) — взять снова; чужая —
# не трогать, код 1
lease_renew() {
  local owner=$1 tree=${2:-} fd rc=1 now
  exec {fd}>"$LEASE.lock"; flock "$fd"
  _lease_read; now=$(date +%s)
  if [ "$L_OWNER" = "$owner" ] && ! _lease_free; then _lease_write "$L_OWNER" "$L_TREE" "$L_SINCE" "$now"; rc=0
  elif _lease_free; then _lease_write "$owner" "$(readlink -m "${tree:-$L_TREE}")" "$now" "$now"; rc=0; fi
  exec {fd}>&-
  return $rc
}

# время старта процесса (поле 22 /proc/<pid>/stat; после имени, где бывают пробелы и скобки, — 20-е)
_proc_start() { sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | cut -d' ' -f20; }

# этот процесс — часть работы, что держит замок клиента: унаследовал его (fd 199) или держатель — его предок (Python
# с close_fds=True потомкам fd не отдаёт: prod_client.py → nested_kwin.sh внутри задачи --gpu)
gpu_client_inherited() {
  [ "$(readlink "/proc/$BASHPID/fd/199" 2>/dev/null)" = "$(readlink -f "$GPU_CLIENT")" ] && return 0
  # держатель — номер и время старта процесса: номер, занятый заново другим процессом, не в счёт
  local holder; holder=$(cat "$GPU_CLIENT.pid" 2>/dev/null) || return 1
  [ -n "$holder" ] && ! flock -n -s "$GPU_CLIENT" true 2>/dev/null || return 1
  _descends "$BASHPID" "$holder"
}

# процесс $1 — потомок процесса «номер время-старта» $2 (или он сам)
_descends() {
  local p=$1
  while [ "${p:-1}" -gt 1 ]; do
    [ "$p $(_proc_start "$p")" = "$2" ] && return 0
    p=$(sed 's/.*) //' "/proc/$p/stat" 2>/dev/null | cut -d' ' -f2)
  done
  return 1
}

# живые вложенные KWin (клиенты на видеокарте) не своей работы, по строке «номер команда» — с замком и без: через
# nested_kwin.sh идут все клиенты, и скриптов до аренды. $1 — держатель замка «номер время-старта», если мы часть его
# работы: её клиенты свои. INCOMING_GPU_KWIN — какую программу считать KWin (проверка подставляет свою)
gpu_clients_running() {
  local kwin=${INCOMING_GPU_KWIN:-kwin_wayland} p a virt hit
  for p in $(pgrep -f -- --virtual); do
    virt= hit=
    while IFS= read -r -d '' a; do
      [ "$a" = --virtual ] && virt=1
      if [ "$a" = "$kwin" ] || [ "${a##*/}" = "$kwin" ]; then hit=1; fi
    done 2>/dev/null < "/proc/$p/cmdline"
    [ -n "$virt" ] && [ -n "$hit" ] || continue
    [ -n "${1:-}" ] && _descends "$p" "$1" && continue
    echo "$p $(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | cut -c1-200)"
  done
}

# игра Артёма: java, что идёт в папке инстанса его Prism (рабочий каталог игры — папка инстанса) или в группе Prism
# (flatpak); не текст командной строки — песочница с MC_DIR на инстанс несёт тот же путь в путях модов
PRISM=${PRISM_DIR:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher}
artem_playing() {
  local p
  for p in $(pgrep -x java); do
    [[ $(readlink "/proc/$p/cwd" 2>/dev/null)/ == "$PRISM/instances/"* ]] && return 0
    grep -q 'org\.prismlauncher\.PrismLauncher' "/proc/$p/cgroup" 2>/dev/null && return 0
  done
  return 1
}

# клиент на видеокарте сейчас (не своей работы): строка «кто» (код 0) или ничего (код 1)
gpu_client_busy() {
  local holder= c
  # пробы — разделяемым замком: друг другу не мешают, с держателем (эксклюзивный) не сходятся
  if ! flock -n -s "$GPU_CLIENT" true 2>/dev/null; then
    gpu_client_inherited || { echo "на видеокарте идёт клиент $(cat "$GPU_CLIENT.who" 2>/dev/null)"; return 0; }
    holder=$(cat "$GPU_CLIENT.pid" 2>/dev/null)
  fi
  c=$(gpu_clients_running "$holder" | head -n 1)
  [ -n "$c" ] && { echo "на видеокарте идёт клиент без аренды (скрипт до неё?): $c"; return 0; }
  return 1
}

# gpu_client_hold <владелец> <дерево> <что> — взять замок клиента на fd 199 (его наследуют exec и потомки: держится,
# пока жив клиент или работа) и продлевать аренду, пока он держится; занят — отказ 75. Часть работы, что уже держит
# замок (стенд, мультиплеер, клиент задачи --gpu), его не берёт
gpu_client_hold() {
  gpu_client_inherited && return 0
  exec 199>"$GPU_CLIENT"
  # ждать до 2 с: проба `flock -n` соседа (status, продлеватель) держит замок на миг
  if ! flock -w 2 199; then echo "на видеокарте идёт клиент $(cat "$GPU_CLIENT.who" 2>/dev/null)" >&2; exec 199>&-; return 75; fi
  printf '%s (%s), с %s: %s\n' "$1" "$(readlink -m "$2")" "$(date +%H:%M)" "$3" > "$GPU_CLIENT.who"
  local me=$BASHPID
  echo "$me $(_proc_start "$me")" > "$GPU_CLIENT.pid"
  # продление — пока замок держат; продлевателю не нужны ни замок (держал бы его сам), ни чужие fd (канал вызывающего,
  # tee, ssh ждали бы его конца)
  (
    set +e
    for f in /proc/$BASHPID/fd/*; do n=${f##*/}; [ "$n" -gt 2 ] 2>/dev/null && eval "exec $n>&-" 2>/dev/null; done
    exec </dev/null >/dev/null 2>&1
    # продлевать, только пока замок занят (код 1); свободен или файла нет (код 0, ошибка открытия) — конец
    while sleep "${INCOMING_GPU_RENEW:-30}"; do flock -n -s "$GPU_CLIENT" true 2>/dev/null; [ $? = 1 ] || exit 0; lease_renew "$1" "$2" || true; done
  ) &
}

# отпустить свою
lease_release() {
  local owner=$1 fd rc=1
  exec {fd}>"$LEASE.lock"; flock "$fd"
  _lease_read
  if [ "$L_OWNER" = "$owner" ]; then rm -f "$LEASE"; rc=0; fi
  exec {fd}>&-
  return $rc
}

[ "${BASH_SOURCE[0]}" = "$0" ] || return 0
set -uo pipefail
me=$(lease_owner)
case "${1:-}" in
  status) out=$(lease_status); echo "$out"; [[ $out == "аренды нет"* ]] || gpu_client_busy; exit 0 ;;
  take) lease_take "$me" "$(cd "$(dirname "$0")/.." && pwd)" "${2:-}" && echo "видеокарта у $me" ;;
  release)
    # свой клиент — только если замок держат (файл «кто» остаётся после конца клиента)
    ! flock -n -s "$GPU_CLIENT" true 2>/dev/null && who=$(gpu_client_busy) && [[ $(cat "$GPU_CLIENT.who" 2>/dev/null) == "$me "* ]] && { echo "$who — сначала остановить его (tools/sandbox.sh stop)" >&2; exit 75; }
    if lease_release "$me"; then echo "видеокарта отпущена"; else echo "аренда не у $me: $(lease_status)"; fi ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac
