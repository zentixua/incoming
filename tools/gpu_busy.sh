#!/usr/bin/env bash
# Видеокарта ноутбука — одна задача: source tools/gpu_busy.sh; gpu_busy [префикс службы …] — код 0, если чужая
# задача ноутбука (служба incoming-job-*, tools/laptop_job.sh) держит клиент (вложенный KWin, Xwayland, xvfb) или Blender.
# Службы с этими префиксами (своя; песочница эффектов — она уступает сама) не в счёт. Тест — tools/test_gpu_busy.sh
# (CI, «Остановка проверок»).
gpu_busy() {
  local u cg cmds skip mine root=${GPU_BUSY_CGROUP_ROOT:-/sys/fs/cgroup}
  for u in $(systemctl --user list-units 'incoming-job-*' --state=active --no-legend --plain 2>/dev/null | awk '{print $1}'); do
    mine=
    for skip in "$@"; do [ -n "$skip" ] && [[ $u == "$skip"* ]] && mine=1; done
    [ -n "$mine" ] && continue
    cg=$(systemctl --user show "$u" -p ControlGroup --value)
    [ -n "$cg" ] || continue
    # список — в переменную: grep -q в конвейере под pipefail выходит на первом совпадении, while получает SIGPIPE, и
    # конвейер «не находит» — так 22.10 съёмка F2b и песочница встали на видеокарту разом
    # процессы службы и её вложенных групп (задача может разложить их по своим cgroup)
    cmds=$(cat $(find "$root$cg" -name cgroup.procs 2>/dev/null) /dev/null 2>/dev/null \
      | while read -r p; do tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null; echo; done)
    grep -qE 'kwin|blender|Xwayland|xvfb' <<< "$cmds" && return 0
  done
  return 1
}
