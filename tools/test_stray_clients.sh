#!/usr/bin/env bash
# Check of tools/stray_clients.sh without systemd or clients (CI, "Остановка проверок"): job units whose caller is gone,
# sandboxes idle past the limit and over-age jobs are listed, live work is not; exit 1 only with strays.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export STRAY_PROC=$tmp/proc
source "$ROOT/tools/stray_clients.sh"
IDLE=1800 MAX_AGE=21600
fail=0
ok() { echo "ok: $1"; }
bad() { echo "FAIL: $1"; fail=1; }

# fake /proc: uptime and command lines (NUL-separated)
mkdir -p "$STRAY_PROC"; echo "100000.00 0" > "$STRAY_PROC/uptime"
proc() { mkdir -p "$STRAY_PROC/$1"; shift; printf '%s\0' "$@" > "$STRAY_PROC/${pid_:-x}/cmdline"; }
fake() { pid_=$1; shift; proc "$pid_" "$@"; }
# a tree with a sandbox whose last command was $2 seconds ago
tree() { mkdir -p "$tmp/$1/tools" "$tmp/$1/mod/run/sandbox/fx/inbox"; touch -d "@$(( $(date +%s) - $2 ))" "$tmp/$1/mod/run/sandbox/fx/ready" "$tmp/$1/mod/run/sandbox/fx/inbox"; }
tree idle 7200; tree busy 7200; touch "$tmp/busy/mod/run/sandbox/fx/active"; tree gone 60
# units: name → main pid, active since (monotonic µs); callers 101..105, main processes 201..205
declare -A MAIN=() SINCE=()
unit() { MAIN[$1]=$2; SINCE[$1]=$3; }
unit incoming-job-sandbox-idle-ab12-101.service 201 $(( (100000 - 7300) * 1000000 ))
unit incoming-job-sandbox-busy-cd34-102.service 202 $(( (100000 - 7300) * 1000000 ))
unit incoming-job-nuke-103.service 203 $(( (100000 - 600) * 1000000 ))
unit incoming-job-sweep-104.service 204 $(( (100000 - 30000) * 1000000 ))
unit incoming-job-guide-105.service 205 $(( (100000 - 600) * 1000000 ))
fake 101 bash "$tmp/idle/tools/laptop_job.sh"; fake 201 bash "$tmp/idle/tools/laptop_job.sh" --in-unit soft x 101 --
fake 102 bash "$tmp/busy/tools/laptop_job.sh"; fake 202 bash "$tmp/busy/tools/laptop_job.sh" --in-unit soft x 102 --
fake 203 bash "$tmp/gone/tools/laptop_job.sh" --in-unit soft x 103 --   # caller 103 gone
fake 104 bash "$tmp/gone/tools/laptop_job.sh"; fake 204 bash "$tmp/gone/tools/laptop_job.sh" --in-unit light x 104 --
fake 105 bash -c sleep; fake 205 bash "$tmp/gone/tools/laptop_job.sh" --in-unit soft x 105 --   # pid reused by another program
systemctl() {
  case "$2" in
    list-units) printf '%s loaded active running x\n' "${!MAIN[@]}" ;;
    show) case "$5" in MainPID) echo "${MAIN[$3]}" ;; ActiveEnterTimestampMonotonic) echo "${SINCE[$3]}" ;; *) echo ;; esac ;;
  esac
}
pgrep() { :; }

[ "$(sandbox_idle "$tmp/idle")" -ge 7199 ] && [ "$(sandbox_idle "$tmp/busy")" -le 2 ] && [ -z "$(sandbox_idle "$tmp/none")" ] \
  && ok "sandbox idle: newest of active, inbox, ready; nothing without a sandbox" || bad "sandbox_idle"
[ "$(unit_tree incoming-job-sandbox-idle-ab12-101.service)" = "$tmp/idle" ] && ok "unit tree from its main process" || bad "unit_tree"
caller_alive incoming-job-sandbox-idle-ab12-101.service && ! caller_alive incoming-job-nuke-103.service \
  && ! caller_alive incoming-job-guide-105.service && ok "caller: a live laptop_job.sh only (a reused pid is not)" || bad "caller_alive"
out=$(report); rc=$?
echo "$out" | sed 's/^/  /'
[ $rc = 1 ] && [ "$(grep -c '^stray' <<< "$out")" = 4 ] \
  && grep -q 'sandbox-idle-ab12-101.*no sandbox command for 120 min' <<< "$out" \
  && grep -q 'nuke-103.*caller gone' <<< "$out" && grep -q 'guide-105.*caller gone' <<< "$out" \
  && grep -q 'sweep-104.*running 8 h' <<< "$out" && ! grep -q busy <<< "$out" \
  && ok "report: idle sandbox, callerless and over-age jobs listed; a working sandbox not" || bad "report: rc $rc"
unset 'MAIN[incoming-job-sandbox-idle-ab12-101.service]' 'MAIN[incoming-job-nuke-103.service]' 'MAIN[incoming-job-sweep-104.service]' 'MAIN[incoming-job-guide-105.service]'
out=$(report); rc=$?
[ $rc = 0 ] && [[ $out == "no stray clients on "* ]] && ok "report: nothing stray, exit 0" || bad "report clean: rc $rc $out"
exit $fail
