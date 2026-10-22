#!/usr/bin/env bash
# Проверка tools/gpu_busy.sh (CI): чужой клиент виден и под pipefail, когда процессов в службе много; своя служба
# и служба без клиента — не заняли видеокарту.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/tools/gpu_busy.sh"
tmp=$(mktemp -d); trap 'kill $kwin 2>/dev/null; rm -rf "$tmp"' EXIT
bash -c 'exec -a kwin_wayland sleep 60' & kwin=$!
mkdir -p "$tmp/other" "$tmp/plain"
# клиент первым, за ним тысячи строк: grep выходит раньше, чем их допишут
{ echo $kwin; for _ in $(seq 3000); do echo $$; done; } > "$tmp/other/cgroup.procs"
# клиент во вложенной группе службы
mkdir -p "$tmp/nested/inner"; for _ in $(seq 10); do echo $$; done > "$tmp/nested/cgroup.procs"; echo $kwin > "$tmp/nested/inner/cgroup.procs"
for _ in $(seq 3000); do echo $$; done > "$tmp/plain/cgroup.procs"
UNITS="incoming-job-sandbox-wt-1.service incoming-job-look-F2b7-2.service"
systemctl() {
  case "$*" in
    *list-units*) for u in $UNITS; do echo "$u loaded active running x"; done ;;
    *incoming-job-sandbox*) echo /plain ;;
    *incoming-job-look*) echo /other ;;
  esac
}
export GPU_BUSY_CGROUP_ROOT=$tmp
fail=0
gpu_busy incoming-job-sandbox-wt- && echo "ok: чужой клиент виден" || { echo "FAIL: чужой клиент не виден"; fail=1; }
gpu_busy incoming-job-look- && { echo "FAIL: служба без клиента заняла видеокарту"; fail=1; } || echo "ok: служба без клиента не занимает"
UNITS="incoming-job-look-F2b7-2.service"
gpu_busy incoming-job-look-F2b7 && { echo "FAIL: своя служба в счёт"; fail=1; } || echo "ok: своя служба не в счёт"
# шлагбаум не ждёт песочницу: она уступает видеокарту сама (второй префикс)
UNITS="incoming-job-sandbox-wt-1.service incoming-job-look-F2b7-2.service"
systemctl() {
  case "$*" in
    *list-units*) for u in $UNITS; do echo "$u loaded active running x"; done ;;
    *) echo /other ;;
  esac
}
gpu_busy incoming-job-look-F2b7 incoming-job-sandbox- && { echo "FAIL: песочница в счёт у шлагбаума"; fail=1; } || echo "ok: песочница не держит шлагбаум"
gpu_busy incoming-job-look-F2b7 && echo "ok: без исключения клиент песочницы виден" || { echo "FAIL: клиент песочницы не виден"; fail=1; }
UNITS="incoming-job-look-RV1-3.service"
systemctl() {
  case "$*" in
    *list-units*) for u in $UNITS; do echo "$u loaded active running x"; done ;;
    *) echo /nested ;;
  esac
}
gpu_busy incoming-job-sandbox- && echo "ok: клиент во вложенной группе виден" || { echo "FAIL: клиент во вложенной группе не виден"; fail=1; }
exit $fail
