#!/usr/bin/env bash
# Аренда видеокарты ноутбука: с картинкой работает один владелец за раз, и это держит файл, а не память.
#
#   tools/gpu_lease.sh status               кто держит, с какого времени, из какого дерева, когда работал последний раз
#   tools/gpu_lease.sh take [--wait]        взять (своя — продлить); чужая — отказ 75 со строкой «видеокарта у …»,
#                                           с --wait — ждать, пока её отпустят или она истечёт
#   tools/gpu_lease.sh release              отпустить свою (закончил с картинкой)
#   source tools/gpu_lease.sh               функции lease_owner, lease_take, lease_renew, lease_release, lease_status
#
# Владелец — сессия: INCOMING_GPU_OWNER или имя дерева (mod/run/wt-<S>[-что угодно] → <S>, иначе имя папки), так что
# деревья одной сессии (wt-N3 и wt-N3-main) — один владелец. Берут её песочница (`tools/sandbox.sh start`) и задачи
# `tools/laptop_job.sh --gpu`; пока они идут, они продлевают её раз в 30 с. Без работы на видеокарте аренда истекает через
# INCOMING_GPU_LEASE_IDLE секунд (600): правка кода между кадрами её не теряет, а брошенная сессия не держит видеокарту
# вечно. Своя сессия перезапускает свою песочницу и ставит свои задачи без ожидания; тяжёлые задачи без видеокарты
# (GameTest, сборка) аренды не берут и песочницу не гасят. Игра Артёма гасит всё, что идёт, при любой аренде.
#
# Почему файл и flock(1), а не правило на память или очередь задач: правило держалось текстом, и 23.10 видеокарту
# брали три работы разом — одна песочница перезапускалась пять раз. Семафоры вроде `sem` (GNU parallel) и очереди
# (`ts`, `nq`) держат процесс, а не сессию: между кадрами, пока сессия правит код, процесса нет, и соседняя задача
# заняла бы видеокарту. Файл живёт в XDG_RUNTIME_DIR
# (tmpfs): перезагрузка его снимает. Проверка — tools/test_gpu_lease.sh (CI).
LEASE=${INCOMING_GPU_LEASE:-${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/incoming-gpu.lease}
LEASE_IDLE=${INCOMING_GPU_LEASE_IDLE:-600}

# владелец по дереву $1 (по умолчанию — дерево этого скрипта)
lease_owner() {
  [ -n "${INCOMING_GPU_OWNER:-}" ] && { echo "$INCOMING_GPU_OWNER"; return; }
  local name; name=$(basename "${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}")
  if [[ $name =~ ^wt-([^-]+) ]]; then echo "${BASH_REMATCH[1]}"; else echo "$name"; fi
}

# строка аренды: владелец, дерево, с (эпоха), последняя работа (эпоха); под замком $LEASE.lock
_lease_read() { [ -s "$LEASE" ] && IFS=$'\t' read -r L_OWNER L_TREE L_SINCE L_SEEN < "$LEASE" || { L_OWNER=; L_TREE=; L_SINCE=0; L_SEEN=0; }; }
_lease_write() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" > "$LEASE.tmp" && mv "$LEASE.tmp" "$LEASE"; }
_lease_free() { [ -z "$L_OWNER" ] || [ $(( $(date +%s) - L_SEEN )) -gt "$LEASE_IDLE" ]; }
_lease_hm() { date -d "@$1" +%H:%M; }

# занятая чужим — строка «видеокарта у …»
lease_status() {
  _lease_read
  if _lease_free; then echo "видеокарта свободна"; return 1; fi
  echo "видеокарта у $L_OWNER ($L_TREE), с $(_lease_hm "$L_SINCE"), последняя работа $(( ($(date +%s) - L_SEEN) / 60 )) мин назад" \
    "(свободна — tools/gpu_lease.sh release в её дереве или через $(( (LEASE_IDLE - $(date +%s) + L_SEEN) / 60 + 1 )) мин без работы)"
}

# lease_take <владелец> <дерево> [--wait] — 0: аренда своя; 75: чужая (строка — в stderr)
lease_take() {
  local owner=$1 tree=$2 wait=${3:-} fd said= now
  while true; do
    exec {fd}>"$LEASE.lock"; flock "$fd"
    _lease_read; now=$(date +%s)
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

# продлить свою (идёт работа на видеокарте); чужая — не трогать, код 1
lease_renew() {
  local owner=$1 fd rc=1
  exec {fd}>"$LEASE.lock"; flock "$fd"
  _lease_read
  if [ "$L_OWNER" = "$owner" ]; then _lease_write "$L_OWNER" "$L_TREE" "$L_SINCE" "$(date +%s)"; rc=0; fi
  exec {fd}>&-
  return $rc
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
  status) lease_status; exit 0 ;;
  take) lease_take "$me" "$(cd "$(dirname "$0")/.." && pwd)" "${2:-}" && echo "видеокарта у $me" ;;
  release) if lease_release "$me"; then echo "видеокарта отпущена"; else echo "аренда не у $me: $(lease_status)"; fi ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac
