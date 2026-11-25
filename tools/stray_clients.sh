#!/usr/bin/env bash
# Stray client report: clients and jobs that outlived their job, here or on another host (netcup). Report only: it never
# stops anything, foreign processes included; stopping a stray is its owner's call (client-scenario.md).
#
#   tools/stray_clients.sh [--host <ssh host>] [--idle <s>] [--max-age <s>]
#
# A job unit (tools/laptop_job.sh: incoming-job-<name>-<caller pid>) is a stray when
#   - its caller (the laptop_job.sh whose pid ends the unit name) is gone: nobody waits for it;
#   - it is an effects sandbox (incoming-job-sandbox-*) whose tree had no command (fx/active, fx/inbox, fx/ready) for
#     --idle seconds (default 1800, the sandbox's own INCOMING_SANDBOX_IDLE): its job ended without `stop`;
#   - any other job running longer than --max-age seconds (default 21600): no client scenario runs that long.
# A client JVM (launch script under mod/build/rig/) reparented to init outside any job unit is a stray too.
# Output: one `stray …` line each with unit, tree, age, idle and CPU; exit 0 none, 1 strays listed, 2 usage or host
# unreachable. Remote: the script itself runs there over ssh (BatchMode), nothing is installed.
set -uo pipefail
PROC=${STRAY_PROC:-/proc}

# seconds since the last sandbox command of tree $1: newest of fx/active (every command), fx/inbox (its directory changes
# with each command file, also on sandboxes older than fx/active) and fx/ready; nothing without any of them
sandbox_idle() {
  local fx=$1/mod/run/sandbox/fx f t last=
  for f in "$fx/active" "$fx/inbox" "$fx/ready"; do
    t=$(stat -c %Y "$f" 2>/dev/null) || continue
    [ -z "$last" ] || [ "$t" -gt "$last" ] && last=$t
  done
  [ -n "$last" ] && echo $(( $(date +%s) - last ))
}

# NUL-separated command line of pid $1 as words, one per line
cmdline() { tr '\0' '\n' 2>/dev/null < "$PROC/$1/cmdline"; }

# the caller pid of unit $1 is a live laptop_job.sh
caller_alive() {
  local pid=${1##*-}; pid=${pid%.service}
  [[ $pid =~ ^[0-9]+$ ]] && cmdline "$pid" | grep -q 'laptop_job\.sh$'
}

# tree of a job unit: its main process is `laptop_job.sh --in-unit …` by absolute path (readlink -f in laptop_job.sh)
unit_tree() {
  local main p
  main=$(systemctl --user show "$1" -p MainPID --value 2>/dev/null)
  p=$(cmdline "$main" | grep -m1 '/tools/laptop_job\.sh$')
  [ -n "$p" ] && dirname "$(dirname "$p")"
}

# seconds the unit has been active
unit_age() {
  local us
  us=$(systemctl --user show "$1" -p ActiveEnterTimestampMonotonic --value 2>/dev/null)
  [[ $us =~ ^[0-9]+$ ]] && [ "$us" -gt 0 ] || { echo 0; return; }
  awk -v u="$us" '{ printf "%d\n", $1 - u / 1e6 }' "$PROC/uptime"
}

# CPU of the unit's processes now (% of one core, ps)
unit_cpu() {
  local cg pids
  cg=$(systemctl --user show "$1" -p ControlGroup --value 2>/dev/null)
  [ -n "$cg" ] || { echo 0; return; }
  pids=$(find "/sys/fs/cgroup$cg" -name cgroup.procs -exec cat {} + 2>/dev/null | paste -sd,)
  [ -n "$pids" ] && ps -o pcpu= -p "$pids" 2>/dev/null | awk '{ s += $1 } END { printf "%.0f", s }' || echo 0
}

# verdict for one unit: prints the reason when it is a stray (args: unit, caller alive 0/1, age s, idle s or empty)
verdict() {
  local unit=$1 alive=$2 age=$3 idle=$4
  if [ "$alive" = 0 ]; then echo "caller gone"
  elif [[ $unit == incoming-job-sandbox-* ]]; then
    [ -n "$idle" ] && [ "$idle" -ge "$IDLE" ] && echo "no sandbox command for $((idle / 60)) min"
  elif [ "$age" -ge "$MAX_AGE" ]; then echo "running $((age / 3600)) h"
  fi
}

report() {
  local unit tree age idle alive why n=0 pid
  for unit in $(systemctl --user list-units 'incoming-job-*' --state=active --no-legend --plain 2>/dev/null | awk '{print $1}'); do
    tree=$(unit_tree "$unit"); age=$(unit_age "$unit")
    idle=; [[ $unit == incoming-job-sandbox-* ]] && [ -n "$tree" ] && idle=$(sandbox_idle "$tree")
    alive=0; caller_alive "$unit" && alive=1
    why=$(verdict "$unit" "$alive" "$age" "$idle")
    [ -n "$why" ] || continue
    n=$((n + 1))
    echo "stray $(hostname -s) $unit tree ${tree:-?} up $((age / 3600)) h $(((age % 3600) / 60)) min${idle:+, last command $((idle / 60)) min ago}, cpu $(unit_cpu "$unit")%: $why"
  done
  # client JVMs reparented to init outside any job unit (a wrapper killed without its session)
  for pid in $(pgrep -u "$(id -u)" -x java 2>/dev/null); do
    cmdline "$pid" | grep -q '/mod/build/rig/' || continue
    [ "$(awk '{print $4}' "$PROC/$pid/stat" 2>/dev/null)" = 1 ] || continue
    grep -q 'incoming-job-' "$PROC/$pid/cgroup" 2>/dev/null && continue
    n=$((n + 1))
    echo "stray $(hostname -s) java $pid up $(ps -o etime= -p "$pid" | tr -d ' '), cpu $(ps -o pcpu= -p "$pid" | tr -d ' ')%: client outside a job unit, parent gone ($(cmdline "$pid" | grep -m1 '/mod/build/rig/'))"
  done
  [ $n = 0 ] && echo "no stray clients on $(hostname -s)"
  [ $n = 0 ]
}

# source tools/stray_clients.sh: functions only (tools/test_stray_clients.sh)
# (run over ssh as `bash -s`, it has no source file)
[ -n "${BASH_SOURCE[0]:-}" ] && [ "${BASH_SOURCE[0]}" != "$0" ] && return 0
host= IDLE=1800 MAX_AGE=21600
while [ $# -gt 0 ]; do
  case "$1" in
    --host) host=$2; shift ;;
    --idle) IDLE=$2; shift ;;
    --max-age) MAX_AGE=$2; shift ;;
    *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
  esac
  shift
done
if [ -n "$host" ]; then
  ssh -o BatchMode=yes -o ConnectTimeout=15 "$host" bash -s -- --idle "$IDLE" --max-age "$MAX_AGE" < "$0"; rc=$?
  [ $rc = 255 ] && { echo "$host unreachable" >&2; exit 2; }
  exit $rc
fi
report
