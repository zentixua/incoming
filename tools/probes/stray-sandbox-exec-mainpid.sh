#!/usr/bin/env bash
# Probe: stray_clients.sh never reports an idle sandbox. laptop_job.sh --in-unit does `exec "$@"` for every mode but
# --gpu, so the MainPID of a sandbox unit (--soft vanilla/CPU, --light GPU) is `bash <tree>/tools/sandbox.sh _run W H`,
# not `laptop_job.sh --in-unit`; unit_tree finds no tree, sandbox_idle is never computed, the verdict is empty while
# the (nohup'd, alive) caller exists. Fake /proc and systemctl as in test_stray_clients.sh; exit 1 = defect present.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export STRAY_PROC=$tmp/proc
source "$ROOT/tools/stray_clients.sh"
IDLE=1800 MAX_AGE=21600
mkdir -p "$STRAY_PROC/101" "$STRAY_PROC/201" "$tmp/t/tools" "$tmp/t/mod/run/sandbox/fx/inbox"
echo "100000.00 0" > "$STRAY_PROC/uptime"
touch -d "@$(( $(date +%s) - 7200 ))" "$tmp/t/mod/run/sandbox/fx/ready" "$tmp/t/mod/run/sandbox/fx/inbox"
# caller: nohup'd laptop_job.sh from sandbox.sh start (alive); main: what exec left (laptop_job.sh line 109)
printf '%s\0' bash "$tmp/t/tools/laptop_job.sh" --soft sandbox-t-ab12 -- "$tmp/t/tools/sandbox.sh" _run 1280 720 > "$STRAY_PROC/101/cmdline"
printf '%s\0' bash "$tmp/t/tools/sandbox.sh" _run 1280 720 > "$STRAY_PROC/201/cmdline"
U=incoming-job-sandbox-t-ab12-101.service
systemctl() {
  case "$2" in
    list-units) echo "$U loaded active running x" ;;
    show) case "$5" in MainPID) echo 201 ;; ActiveEnterTimestampMonotonic) echo $(( (100000 - 7300) * 1000000 )) ;; *) echo ;; esac ;;
  esac
}
pgrep() { :; }
out=$(report); rc=$?
echo "$out"
if [ $rc = 0 ]; then echo "DEFECT: sandbox idle 120 min not reported (unit_tree='$(unit_tree $U)')"; exit 1; fi
echo "ok: reported"
