#!/usr/bin/env bash
# Review probe of the shared GPU / sound-only work, kept as a regression test (run by tools/test_gpu_lease.sh, test_sandbox.sh).
# Exits 1 if a shared and exclusive launch can overlap. No GPU or client is started.
set -euo pipefail
root=$(cd "${1:-$(dirname "$0")/../..}" && pwd)
tmp=$(mktemp -d)
children=()
trap 'for pid in "${children[@]}"; do kill "$pid" 2>/dev/null || :; wait "$pid" 2>/dev/null || :; done; rm -rf "$tmp"' EXIT
export PATH="$tmp:$PATH" PRISM_DIR="$tmp/prism" INCOMING_GPU_KWIN="$tmp/kwin-review-probe"
unset INCOMING_GPU_SLOTS INCOMING_GPU_OWNER DELEGATE_TASK
printf '#!/usr/bin/env bash\necho 6000\n' > "$tmp/nvidia-smi"
cat > "$INCOMING_GPU_KWIN" <<'STUB'
#!/usr/bin/env bash
# Review probe of the shared GPU / sound-only work, kept as a regression test (run by tools/test_gpu_lease.sh, test_sandbox.sh).
touch "$GPU_PROBE_READY"
trap 'exit' TERM
while :; do sleep 0.05; done
STUB
chmod +x "$tmp/nvidia-smi" "$INCOMING_GPU_KWIN"

source "$root/tools/gpu_lease.sh"
artem_playing() { return 1; }
failed=0
ready() {
  for _ in {1..100}; do [ -f "$1" ] && return; sleep 0.02; done
  echo "fixture never started: $1" >&2
  exit 2
}

LEASE="$tmp/legacy-live"; GPU_CLIENT="$LEASE.client"; QUEUE="$LEASE.queue"
GPU_PROBE_READY="$tmp/legacy.ready" "$INCOMING_GPU_KWIN" --virtual --socket wayland-incoming-pre-lease &
legacy=$!
children+=("$legacy")
ready "$tmp/legacy.ready"
gpu_clients_running | grep -q wayland-incoming-pre-lease
rc=0
gpu_slot_take legacy-live 2>"$tmp/legacy.err" || rc=$?
printf 'Legacy compositor %s is live; shared admission exit %s\n' "$legacy" "$rc"
if [ "$rc" = 0 ]; then failed=1; exec 197>&-; fi
kill "$legacy"
wait "$legacy"
children=()

# Fill all default slots, put another launch in slot 4, then free default slots.
LEASE="$tmp/mixed"; GPU_CLIENT="$LEASE.client"; QUEUE="$LEASE.queue"
for i in 1 2 3; do
  exec {fd}>"$LEASE.slot$i"
  flock -x "$fd"
  case $i in 1) slot1=$fd ;; 2) slot2=$fd ;; 3) slot3=$fd ;; esac
done
(
  exec {slot1}>&- {slot2}>&- {slot3}>&-
  GPU_SLOTS=4
  gpu_slot_take mixed
  printf '%s\n' "$GPU_SLOT" > "$tmp/slot"
  GPU_PROBE_READY="$tmp/shared.ready" exec "$INCOMING_GPU_KWIN" --virtual --socket wayland-incoming-shared-review
) &
shared=$!
children+=("$shared")
ready "$tmp/shared.ready"
exec {slot1}>&- {slot2}>&- {slot3}>&-
rc=0
lease_take measure "$root" 2>"$tmp/mixed.err" || rc=$?
printf 'Shared compositor %s is live in slot %s; default exclusive lease exit %s\n' "$shared" "$(cat "$tmp/slot")" "$rc"
if [ "$rc" = 0 ]; then failed=1; fi
kill "$shared"
wait "$shared"
children=()

# A script that predates slots (an older worktree) takes the GPU client lock exclusively, as its gpu_client_hold did
# (`exec 199>"$GPU_CLIENT"; flock -w 2 199`) once its lease check passed; it sees a shared KWin only after the process
# appears. Pause the new launch between taking its slot and starting KWin.
export INCOMING_GPU_LEASE="$tmp/mixed-revisions"
source "$root/tools/gpu_lease.sh"
gpu_slot_take still-launching
bash -c '
  exec 196>&- 197>&-
  exec 199>"$1"
  rc=0; flock -w 2 199 || rc=75
  echo "$rc" > "$2.tmp"
  mv "$2.tmp" "$2"
  if [ "$rc" = 0 ]; then exec sleep 10; fi
' _ "$GPU_CLIENT" "$tmp/old.rc" 2>"$tmp/old.err" &
old=$!
children+=("$old")
ready "$tmp/old.rc"
rc=$(cat "$tmp/old.rc")
printf 'While fd 197 is held and KWin is starting, old exclusive admission exit %s\n' "$rc"
if [ "$rc" = 0 ]; then
  GPU_PROBE_READY="$tmp/late-shared.ready" "$INCOMING_GPU_KWIN" --virtual --socket wayland-incoming-shared-late &
  children+=("$!")
  ready "$tmp/late-shared.ready"
  if ! flock -n -s "$GPU_CLIENT" true && ! flock -n "$LEASE.slot1" true; then
    echo "Old exclusive client and new shared compositor hold their locks concurrently"
    failed=1
  fi
fi
exit "$failed"
