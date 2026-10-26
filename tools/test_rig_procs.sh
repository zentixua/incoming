#!/usr/bin/env bash
# tools/rig_procs.sh check (CI and manual): all check processes stop, leaving no orphans.
#   tools/test_rig_procs.sh → ok/FAIL lines, exit 1 for any error
# Replace JVM with uniquely numbered sleep processes under a bash wrapper (grandchildren like JVM under xvfb-run/nested_kwin), including
# a TERM-ignoring process; KILL timeout is RIG_GRACE=2 instead of 20 s.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
FAILS=0
# sleep tags: "<B><two digits>": B comes from the test PID; two runs on one machine do not confuse each other processes
B=$(( $$ % 90000 + 10000 ))
export B
T=""
ok() { echo "ok   $*"; }
fail() { echo "FAIL $*"; FAILS=$((FAILS + 1)); }
alive() { pgrep -f "^sleep $B$1\$" >/dev/null; } # Alive (pgrep -f cannot see zombies: they have no command line)
cleanup() {
  [ -n "$T" ] && kill -KILL "$T" 2>/dev/null
  pkill -KILL -f "^sleep $B[0-9][0-9]\$" 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

# Scenario: script with rig_trap launches sessions and waits; $1 is its body after rig_trap, $2 is RIG_GRACE (2)
subject() {
  cat > "$WORK/subject.sh" <<EOF
set -euo pipefail
export RIG_GRACE=${2:-2}
source "$ROOT/tools/rig_procs.sh"
rig_trap
$1
EOF
}

# Wait up to 10 s for all sleep tags to appear
wait_for() { for _ in $(seq 100); do local all=1; for n in "$@"; do alive "$n" || all=0; done; [ $all -eq 1 ] && return 0; sleep 0.1; done; return 1; }

# 1. Ctrl+C: TERM-ignoring grandchild receives KILL after RIG_GRACE; a second Ctrl+C during shutdown does not interrupt it;
# the script dies by INT
subject 'rig_spawn bash -c "trap \"\" TERM; sleep ${B}01 & sleep ${B}02 & wait" > /dev/null 2>&1
rig_spawn bash -c "sleep ${B}03 & wait" > /dev/null 2>&1
wait
sleep ${B}99'
set -m
bash "$WORK/subject.sh" 2>"$WORK/err1" & T=$!
set +m
if wait_for 01 02 03; then
  kill -INT $T; sleep 0.5; kill -INT $T
  # 2 s timeout plus margin; an INT-ignoring script cannot hang the run (cleanup kills it)
  for _ in $(seq 150); do kill -0 $T 2>/dev/null || break; sleep 0.1; done
  if kill -0 $T 2>/dev/null; then st="alive after 15 s"; else wait $T; st=$?; T=""; fi
  [ "$st" = 130 ] && ok "Ctrl+C: script died by INT" || fail "Ctrl+C: code $st, expected 130"
  for n in 01 02 03; do alive $n && fail "Ctrl+C: sleep $B$n alive"; done
  alive 01 || alive 02 || alive 03 || ok "Ctrl+C: no orphans"
  grep -q "did not exit within 2 s after TERM — KILL" "$WORK/err1" && ok "Ctrl+C: KILL of TERM-ignoring process after RIG_GRACE logged" \
    || fail "Ctrl+C: no KILL line after 2 s: $(cat "$WORK/err1")"
else fail "Ctrl+C: processes did not start"; fi

# 2. TERM to script: script dies by TERM itself (not exit 143; wrapper loop and systemd see interruption), sessions
# are stopped; only waitpid distinguishes death status, so use Python
subject 'rig_spawn bash -c "sleep ${B}11 & wait" > /dev/null 2>&1
wait'
st=$(python3 - "$WORK/subject.sh" "^sleep ${B}11\$" <<'PY'
import signal, subprocess, sys, time
p = subprocess.Popen(["bash", sys.argv[1]], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
for _ in range(100):
    if subprocess.run(["pgrep", "-f", sys.argv[2]], capture_output=True).returncode == 0:
        break
    time.sleep(0.1)
else:
    p.kill()
    print("did not start")
    sys.exit()
p.send_signal(signal.SIGTERM)
try:
    print(p.wait(timeout=30))
except subprocess.TimeoutExpired:
    p.kill()
    print("did not exit within 30 s")
PY
)
[ "$st" = "-15" ] && ok "TERM: script died by TERM signal" || fail "TERM: $st, expected -15 (death by TERM)"
sleep 0.3
alive 11 && fail "TERM: sleep $B""11 alive" || ok "TERM: no orphans"

# 3. Normal exit: remaining sessions stop; a launch that exits immediately does not fail the script
subject 'rig_spawn bash -c "exit 3" > /dev/null 2>&1
p=$RIG_LAST
rig_spawn bash -c "sleep ${B}21 & wait" > /dev/null 2>&1
started=0
for _ in $(seq 100); do pgrep -f "^sleep ${B}21\$" >/dev/null && { started=1; break; }; sleep 0.1; done
[ $started -eq 1 ] || echo "did not start"
sleep 0.5
kill -0 $p 2>/dev/null && echo "first still alive" || echo "first exited"'
out=$(bash "$WORK/subject.sh" 2>&1); st=$?
[ $st -eq 0 ] && [[ $out == *"first exited"* ]] && [[ $out != *"did not start"* ]] \
  && ok "exit: immediately exited launch did not fail the script" || fail "exit: code $st, output: $out"
sleep 0.3
alive 21 && fail "exit: sleep $B""21 alive" || ok "exit: no orphans"

# 4. Foreign untagged session (PID/session ID reuse) remains untouched; children do not inherit the message fd
setsid sleep "${B}31" & F=$!
subject "echo \$RIG_ERR > $WORK/errfd
rig_spawn bash -c 'ls -l /proc/self/fd > $WORK/fds; sleep ${B}32' > /dev/null 2>&1
RIG_SESSIONS+=($F)
wait_for() { for _ in \$(seq 100); do pgrep -f '^sleep ${B}32\$' >/dev/null && return 0; sleep 0.1; done; }
wait_for"
bash "$WORK/subject.sh" 2>/dev/null; sleep 0.3
alive 31 && ok "foreign untagged session untouched" || fail "foreign session killed"
kill $F 2>/dev/null
alive 32 && fail "fd: sleep $B""32 alive" || true
fd=$(cat "$WORK/errfd")
[ -s "$WORK/fds" ] || fail "fd: list not written"
grep -q " $fd -> " "$WORK/fds" && fail "message fd $fd inherited" || ok "children do not inherit message fd ($fd)"

# 5. RIG_GRACE=0: KILL immediately (no wait-loop iteration); non-numeric means default timeout, not a set -u exit
subject 'rig_spawn bash -c "trap \"\" TERM; sleep ${B}41 & wait" > /dev/null 2>&1
for _ in $(seq 100); do pgrep -f "^sleep ${B}41\$" >/dev/null && break; sleep 0.1; done' 0
bash "$WORK/subject.sh" 2>"$WORK/err5"; sleep 0.3
alive 41 && fail "RIG_GRACE=0: sleep $B""41 alive" || ok "RIG_GRACE=0: TERM-ignoring process killed immediately"
grep -q "did not exit within 0 s after TERM — KILL" "$WORK/err5" || fail "RIG_GRACE=0: no KILL line: $(cat "$WORK/err5")"
# Non-numeric through the full rig_stop path: TERM-responsive session stops, code 0, empty stderr (no arithmetic errors)
subject 'rig_spawn bash -c "sleep ${B}51 & wait" > /dev/null 2>&1
for _ in $(seq 100); do pgrep -f "^sleep ${B}51\$" >/dev/null && { echo started; break; }; sleep 0.1; done' abc
out=$(bash "$WORK/subject.sh" 2>"$WORK/err6"); st=$?; sleep 0.3
[ $st -eq 0 ] && [ "$out" = started ] && [ ! -s "$WORK/err6" ] && ! alive 51 && ok "RIG_GRACE=abc: rig_stop stops session without errors" \
  || fail "RIG_GRACE=abc: code $st, output \"$out\", stderr: $(cat "$WORK/err6")"
bad=$FAILS
for g in abc 20s -3 08; do
  got=$(RIG_GRACE=$g bash -c 'set -euo pipefail; source "$1/tools/rig_procs.sh"; rig_grace' _ "$ROOT" 2>&1)
  want=20; [ "$g" = 08 ] && want=8
  [ "$got" = "$want" ] || fail "RIG_GRACE=$g: timeout \"$got\", expected $want"
done
[ $FAILS -eq $bad ] && ok "RIG_GRACE: non-numeric means 20 s, 08 means 8 s"

[ $FAILS -eq 0 ] && echo "rig_procs: all ok" || echo "rig_procs: $FAILS errors"
exit $((FAILS > 0))
