#!/usr/bin/env bash
# Review probe of the GPU turn, kept as a regression test (run by tools/test_gpu_lease.sh). Exits 1 when: the next
# nested client of a job that holds the client lock is refused past its turn; a renewal reacquires a yielded lease
# ahead of the queue; an exited last queue row hides a live waiter from the turn check. No GPU, KWin or game is used.
set -euo pipefail
root=$(cd "${1:-$(dirname "$0")/../..}" && pwd)
t=$(mktemp -d)
waiter=
trap '[ -z "$waiter" ] || { kill "$waiter" 2>/dev/null || true; wait "$waiter" 2>/dev/null || true; }; rm -rf "$t"' EXIT
mkdir -p "$t/bin" "$t/tree/tools"
cp "$root/tools/"{gpu_lease,nested_kwin}.sh "$t/tree/tools/"
printf '#!/bin/sh\nexit 1\n' > "$t/bin/pgrep"
printf '#!/bin/sh\necho "compositor stub reached"\n' > "$t/bin/dbus-run-session"
printf '#!/bin/sh\nexit 1\n' > "$t/bin/nvidia-smi"
chmod +x "$t/bin/"*
export PATH="$t/bin:$PATH" XDG_RUNTIME_DIR="$t" INCOMING_GPU_LEASE="$t/lease"
export INCOMING_GPU_TURN=1800 INCOMING_GPU_YIELD=90 INCOMING_GPU_LEASE_IDLE=600 INCOMING_GPU_RENEW=1
export INCOMING_GPU_OWNER=A DELEGATE_LOAD_LOG="$t/load.tsv" INCOMING_GPU_KWIN=no-such-kwin
unset INCOMING_GPU_WAIT INCOMING_SOFT INCOMING_GPU_SHARED DELEGATE_TASK
source "$t/tree/tools/gpu_lease.sh"
sleep 30 & waiter=$!
now=$(date +%s)
queue() { printf 'B\t%s\t%s\t%s %s\n' "$t" "$now" "$waiter" "$(_proc_start "$waiter")" > "$QUEUE"; }
record() { _lease_write A "$t/tree" "$((now - 1801))" "$((now - $1))"; }
fail() { echo "FAIL: $*"; exit 1; }

# 1. a job holding the client lock (the bench) starts its next nested client past its turn: admitted, not refused
record 0; queue
( gpu_client_hold A "$t/tree" "stand parent"; lease_renew A "$t/tree"
  out=$("$t/tree/tools/nested_kwin.sh" probe 100 100 true 199>&- 2>&1) || true
  [[ $out == *"compositor stub reached"* ]] || fail "nested client of the lock holder refused: $out" ) || exit 1

# 2. a yielded lease (idle past the yield, turn over, B waiting) is not reacquired by a renewal ahead of B
record 100; queue
[[ $(lease_status) == "GPU is free, but queued: B"* ]] || fail "not yielded: $(lease_status)"
lease_renew A "$t/tree" && fail "renewal reacquired a yielded lease ahead of the queue"
lease_take B "$t" 2>/dev/null || fail "first waiter B refused"

# 3. an exited waiter as the last queue row does not hide the live one
record 100; queue
printf 'C\t%s\t%s\t99999999 1\n' "$t" "$now" >> "$QUEUE"
[[ $(lease_status) == "GPU is free, but queued: B"* ]] || fail "stale last row hid B: $(lease_status)"
echo "gpu turn: nested client admitted, renewal respects the queue, stale rows ignored"
