#!/usr/bin/env bash
# Check of tools/stray_clients.sh without systemd or clients (CI, "Остановка проверок"): job units whose caller is gone,
# sandboxes idle past the limit and over-age jobs are listed, live work is not; exit 1 only with strays.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export STRAY_PROC=$tmp/proc
source "$ROOT/tools/stray_clients.sh"
IDLE=1800 MAX_AGE=43200
fail=0
ok() { echo "ok: $1"; }
bad() { echo "FAIL: $1"; fail=1; }

# fake /proc: uptime and command lines (NUL-separated)
mkdir -p "$STRAY_PROC"
proc() { mkdir -p "$STRAY_PROC/$1"; shift; printf '%s\0' "$@" > "$STRAY_PROC/${pid_:-x}/cmdline"; }
fake() { pid_=$1; shift; proc "$pid_" "$@"; }
# a tree with a sandbox whose last command was $2 seconds ago
tree() { mkdir -p "$tmp/$1/tools" "$tmp/$1/mod/run/sandbox/fx/inbox"; touch -d "@$(( $(date +%s) - $2 ))" "$tmp/$1/mod/run/sandbox/fx/ready" "$tmp/$1/mod/run/sandbox/fx/inbox"; }
tree idle 7200; tree busy 7200; touch "$tmp/busy/mod/run/sandbox/fx/active"; tree gone 60
# units: name → main pid, active since (monotonic µs); callers 101..105, main processes 201..205
declare -A MAIN=() SINCE=()
unit() { MAIN[$1]=$2; SINCE[$1]=$(date -d "@$(( $(date +%s) - 100000 + $3 / 1000000 ))" '+%a %Y-%m-%d %H:%M:%S %Z'); }
unit incoming-job-sandbox-idle-ab12-101.service 201 $(( (100000 - 7300) * 1000000 ))
unit incoming-job-sandbox-busy-cd34-102.service 202 $(( (100000 - 7300) * 1000000 ))
unit incoming-job-nuke-103.service 203 $(( (100000 - 600) * 1000000 ))
unit incoming-job-sweep-104.service 204 $(( (100000 - 50000) * 1000000 ))
unit incoming-job-guide-105.service 205 $(( (100000 - 600) * 1000000 ))
# main processes as they run: --soft/--light exec the command (sandbox.sh _run), --gpu stays laptop_job.sh --in-unit
fake 101 bash "$tmp/idle/tools/laptop_job.sh" --soft sandbox-idle-ab12 -- "$tmp/idle/tools/sandbox.sh" _run 1280 720
fake 201 bash "$tmp/idle/tools/sandbox.sh" _run 1280 720
fake 102 bash "$tmp/busy/tools/laptop_job.sh" --light sandbox-busy-cd34 -- "$tmp/busy/tools/sandbox.sh" _run 1280 720
fake 202 bash "$tmp/busy/tools/laptop_job.sh" --in-unit gpu sandbox-busy-cd34 102 -- "$tmp/busy/tools/sandbox.sh" _run 1280 720
fake 203 bash -c 'source "$1/tools/soft_gl.sh"; vanilla_display …' _ "$tmp/gone" nuke   # caller 103 gone
fake 104 bash "$tmp/gone/tools/laptop_job.sh"; fake 204 bash "$tmp/gone/tools/laptop_job.sh" --in-unit light x 104 --
# dev clients (tools/dev_client.sh, also kept up under nohup): one idle two hours, one with a fresh command
for d in devidle devbusy; do mkdir -p "$tmp/$d/tools" "$tmp/$d/mod/run/client/dev/inbox"; done
touch -d "@$(( $(date +%s) - 7200 ))" "$tmp/devidle/mod/run/client/dev/inbox"
unit incoming-job-devclient-devidle-ef56-106.service 206 $(( (100000 - 7300) * 1000000 ))
unit incoming-job-devclient-devbusy-ab78-107.service 207 $(( (100000 - 7300) * 1000000 ))
fake 106 bash "$tmp/devidle/tools/laptop_job.sh" --soft devclient-devidle-ef56 -- "$tmp/devidle/tools/dev_client.sh" _run 1280 720
fake 206 bash "$tmp/devidle/tools/dev_client.sh" _run 1280 720
fake 107 bash "$tmp/devbusy/tools/laptop_job.sh" --soft devclient-devbusy-ab78 -- "$tmp/devbusy/tools/dev_client.sh" _run 1280 720
fake 207 bash "$tmp/devbusy/tools/dev_client.sh" _run 1280 720
fake 105 bash -c sleep; fake 205 bash "$tmp/gone/tools/laptop_job.sh" --in-unit soft x 105 --   # pid reused by another program
systemctl() {
  case "$2" in
    list-units) printf '%s loaded active running x\n' "${!MAIN[@]}" ;;
    show) case "$5" in MainPID) echo "${MAIN[$3]}" ;; ActiveEnterTimestamp) echo "${SINCE[$3]}" ;; *) echo ;; esac ;;
  esac
}
pgrep() { :; }

[ "$(sandbox_idle "$tmp/idle")" -ge 7199 ] && [ "$(sandbox_idle "$tmp/busy")" -le 2 ] && [ -z "$(sandbox_idle "$tmp/none")" ] \
  && ok "sandbox idle: newest of active, inbox, ready; nothing without a sandbox" || bad "sandbox_idle"
[ "$(unit_tree incoming-job-sandbox-idle-ab12-101.service)" = "$tmp/idle" ] && [ "$(unit_tree incoming-job-sandbox-busy-cd34-102.service)" = "$tmp/busy" ] \
  && ok "unit tree from an exec'd command (sandbox.sh _run) and from laptop_job.sh --in-unit" || bad "unit_tree"
caller_alive incoming-job-sandbox-idle-ab12-101.service && ! caller_alive incoming-job-nuke-103.service \
  && ! caller_alive incoming-job-guide-105.service && ok "caller: a live laptop_job.sh only (a reused pid is not)" || bad "caller_alive"
out=$(report); rc=$?
echo "$out" | sed 's/^/  /'
[ $rc = 1 ] && [ "$(grep -c '^stray' <<< "$out")" = 5 ] \
  && grep -q 'devclient-devidle-ef56-106.*no client command for 120 min' <<< "$out" && ! grep -q devbusy <<< "$out" \
  && grep -q 'sandbox-idle-ab12-101.*no client command for 120 min' <<< "$out" \
  && grep -q 'nuke-103.*caller gone' <<< "$out" && grep -q 'guide-105.*caller gone' <<< "$out" \
  && grep -q 'sweep-104.*running 13 h' <<< "$out" && ! grep -q busy <<< "$out" \
  && ok "report: idle sandbox and dev client, callerless and over-age jobs listed; working ones not" || bad "report: rc $rc"
unset 'MAIN[incoming-job-sandbox-idle-ab12-101.service]' 'MAIN[incoming-job-nuke-103.service]' 'MAIN[incoming-job-sweep-104.service]' 'MAIN[incoming-job-guide-105.service]' 'MAIN[incoming-job-devclient-devidle-ef56-106.service]'
out=$(report); rc=$?
[ $rc = 0 ] && [[ $out == "no stray clients on "* ]] && ok "report: nothing stray, exit 0" || bad "report clean: rc $rc $out"
# orphaned client JVM: its parent no longer names the launch script (adopted by init, the user manager or a subreaper)
fake 301 java @"$tmp/x/mod/build/rig/sandbox-clientScenarioRunVmArgs.txt"; printf '1 (java) S 302 ' > "$STRAY_PROC/301/stat"
fake 302 /bin/sh /usr/bin/xvfb-run -a bash -c "bash $tmp/x/mod/build/rig/sandbox.sh"
orphan_jvm 301 && bad "a JVM under its xvfb-run is not an orphan" || ok "a JVM under its wrapper is not an orphan"
fake 302 /lib/systemd/systemd --user
orphan_jvm 301 && ok "a JVM adopted by the user manager is an orphan" || bad "JVM adopted by systemd --user missed"

# review probes kept as regression tests
for pr in stray-sandbox-exec-mainpid stray-orphan-subreaper; do
  out=$(env -u STRAY_PROC bash "$ROOT/tools/probes/$pr.sh" 2>&1) && ok "probe $pr" || bad "probe $pr: $out"
done

# laptop_job.sh with real units (needs systemd --user; skipped without it): HUP on the caller and a killed systemd-run
# both stop the unit, a normal run says nothing extra
unset -f systemctl pgrep   # the real ones from here on
if systemctl --user show-environment >/dev/null 2>&1 && systemd-run --user --quiet --wait true 2>/dev/null; then
  lj=$ROOT/tools/laptop_job.sh
  out=$("$lj" --light strayt-ok -- true 2>&1)
  [ "$out" = "laptop_job strayt-ok: exit 0" ] && ok "laptop_job: a normal run" || bad "laptop_job normal run: $out"
  "$lj" --light strayt-hup -- sleep 120 > "$tmp/hup.log" 2>&1 & p=$!
  for _ in $(seq 50); do systemctl --user is-active -q "incoming-job-strayt-hup-$p" && break; sleep 0.1; done
  kill -HUP $p; wait $p
  ! systemctl --user is-active -q "incoming-job-strayt-hup-$p" && grep -q 'strayt-hup: stopped' "$tmp/hup.log" \
    && ok "laptop_job: HUP on the caller stops its unit" || bad "laptop_job HUP: $(cat "$tmp/hup.log")"
  systemctl --user stop "incoming-job-strayt-hup-$p" 2>/dev/null
  "$lj" --light strayt-run -- sleep 120 > "$tmp/run.log" 2>&1 & p=$!
  for _ in $(seq 50); do systemctl --user is-active -q "incoming-job-strayt-run-$p" && break; sleep 0.1; done
  for sr in $(pgrep -x systemd-run); do
    tr '\0' ' ' < /proc/$sr/cmdline | grep -q -- "--unit=incoming-job-strayt-run-$p " && kill -KILL $sr
  done
  wait $p
  ! systemctl --user is-active -q "incoming-job-strayt-run-$p" && grep -q 'systemd-run ended before its unit' "$tmp/run.log" \
    && ok "laptop_job: a killed systemd-run stops its unit" || bad "laptop_job killed systemd-run: $(cat "$tmp/run.log")"
  systemctl --user stop "incoming-job-strayt-run-$p" 2>/dev/null
else
  echo "skip: laptop_job unit checks (no systemd --user)"
fi
exit $fail
