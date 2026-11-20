#!/usr/bin/env bash
# Review probe of the shared GPU / sound-only work, kept as a regression test (run by tools/test_gpu_lease.sh, test_sandbox.sh).
# Hold the admission mutex as an exclusive lease operation would.
set -euo pipefail
root=$(cd "${1:-$(dirname "$0")/../..}" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export INCOMING_GPU_LEASE="$tmp/lease" PATH="$tmp:$PATH"
unset INCOMING_CPU_ONLY
printf '#!/usr/bin/env bash\necho 6000\n' > "$tmp/nvidia-smi"
printf '#!/usr/bin/env bash\nexit 97\n' > "$tmp/kwin_wayland"
chmod +x "$tmp/nvidia-smi" "$tmp/kwin_wayland"
exec 196>"$INCOMING_GPU_LEASE.lock"
flock -x 196
rc=0
timeout 1 bash -c '
  source "$1/tools/gpu_lease.sh"
  source "$1/tools/soft_gl.sh"
  artem_playing() { return 1; }
  soft_display() { echo FALLBACK; }
  vanilla_display review 64 64 true
' _ "$root" 196>&- > "$tmp/out" 2>&1 || rc=$?
printf 'Vanilla display while lease mutex is busy: exit %s, output %q\n' "$rc" "$(cat "$tmp/out")"
[ "$rc" = 0 ] && grep -qx FALLBACK "$tmp/out"
