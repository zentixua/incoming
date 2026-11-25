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
#   - any other job running longer than --max-age seconds (default 43200: the longest measured job, a 7 h GameTest
#     run, with margin; laptop_job.sh header).
# A client JVM (launch script under mod/build/rig/) outside any job unit whose wrapper is gone is a stray too: its
# parent no longer names the launch script (adopted by init, the systemd user manager or another subreaper).
# Output: one `stray …` line each with unit, tree, age, idle and CPU over one second; exit 0 none, 1 strays listed, 2 usage or host
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

# tree of a job unit: the first absolute <tree>/tools/<script>.sh in its main process (`laptop_job.sh --in-unit` for
# --gpu; other modes exec the command itself, e.g. `sandbox.sh _run`) or its caller (`laptop_job.sh`)
unit_tree() {
  local main caller=${1##*-} p
  caller=${caller%.service}
  main=$(systemctl --user show "$1" -p MainPID --value 2>/dev/null)
  p=$({ cmdline "$main"; cmdline "$caller"; } | grep -m1 '^/.*/tools/[^/]*\.sh$')
  [ -n "$p" ] && dirname "$(dirname "$p")"
}

# seconds the unit has been active, wall clock (its monotonic stamp leaves out suspend, /proc/uptime does not)
unit_age() {
  local at
  # "Wed 2026-11-25 20:12:39 CET": the weekday dropped, date -d reads the rest in any locale
  at=$(systemctl --user show "$1" -p ActiveEnterTimestamp --value 2>/dev/null | cut -d' ' -f2-)
  at=$(date -d "$at" +%s 2>/dev/null) && [ -n "$at" ] || { echo 0; return; }
  echo $(( $(date +%s) - at ))
}

# CPU of the unit now: its cgroup's usage over one second (% of one core; ps would give the lifetime average)
unit_cpu() {
  local cg a b
  cg=$(systemctl --user show "$1" -p ControlGroup --value 2>/dev/null)
  [ -n "$cg" ] || { echo ?; return; }
  a=$(awk '$1 == "usage_usec" { print $2 }' "/sys/fs/cgroup$cg/cpu.stat" 2>/dev/null); sleep 1
  b=$(awk '$1 == "usage_usec" { print $2 }' "/sys/fs/cgroup$cg/cpu.stat" 2>/dev/null)
  [ -n "$a" ] && [ -n "$b" ] && echo $(( (b - a) / 10000 )) || echo ?
}

# CPU of process $1 now: utime + stime over one second (% of one core)
pid_cpu() {
  local a b hz
  hz=$(getconf CLK_TCK)
  a=$(awk '{ print $14 + $15 }' "$PROC/$1/stat" 2>/dev/null); sleep 1
  b=$(awk '{ print $14 + $15 }' "$PROC/$1/stat" 2>/dev/null)
  [ -n "$a" ] && [ -n "$b" ] && echo $(( (b - a) * 100 / hz )) || echo ?
}

# client JVM $1 (outside a job unit) lost its wrapper: its parent (xvfb-run, kwin_wayland or the rig session, each
# naming the launch script under mod/build/rig/) is gone, so init or a subreaper adopted it
orphan_jvm() {
  local pp
  pp=$(awk '{ print $4 }' "$PROC/$1/stat" 2>/dev/null) || return 1
  [ -n "$pp" ] || return 1
  ! cmdline "$pp" | grep -q '/mod/build/rig/'
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
    grep -q 'incoming-job-' "$PROC/$pid/cgroup" 2>/dev/null && continue
    orphan_jvm "$pid" || continue
    n=$((n + 1))
    echo "stray $(hostname -s) java $pid up $(ps -o etime= -p "$pid" | tr -d ' '), cpu $(pid_cpu "$pid")%: client outside a job unit, its wrapper gone ($(cmdline "$pid" | grep -m1 '/mod/build/rig/'))"
  done
  [ $n = 0 ] && echo "no stray clients on $(hostname -s)"
  [ $n = 0 ]
}

# source tools/stray_clients.sh: functions only (tools/test_stray_clients.sh)
# (run over ssh as `bash -s`, it has no source file)
[ -n "${BASH_SOURCE[0]:-}" ] && [ "${BASH_SOURCE[0]}" != "$0" ] && return 0
host= IDLE=1800 MAX_AGE=43200
while [ $# -gt 0 ]; do
  case "$1" in
    --host|--idle|--max-age)
      [ $# -ge 2 ] || { echo "$1 needs a value" >&2; exit 2; }
      case "$1" in --host) host=$2 ;; --idle) IDLE=$2 ;; --max-age) MAX_AGE=$2 ;; esac
      shift ;;
    *) echo "usage: tools/stray_clients.sh [--host <ssh host>] [--idle <s>] [--max-age <s>] (header of the script)" >&2; exit 2 ;;
  esac
  shift
done
[[ $IDLE =~ ^[0-9]+$ ]] && [[ $MAX_AGE =~ ^[0-9]+$ ]] || { echo "--idle and --max-age take whole seconds" >&2; exit 2; }
if [ -n "$host" ]; then
  ssh -o BatchMode=yes -o ConnectTimeout=15 "$host" bash -s -- --idle "$IDLE" --max-age "$MAX_AGE" < "$0"; rc=$?
  [ $rc = 255 ] && { echo "$host unreachable" >&2; exit 2; }
  exit $rc
fi
report
