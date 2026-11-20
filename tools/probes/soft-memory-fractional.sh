#!/usr/bin/env bash
# Review probe (round 2), kept as a regression test (run by tools/test_gpu_lease.sh).
# Runs the real admission logic with a synthetic host and records attempted submissions.
set -euo pipefail
root=$(cd "${1:?pass the path to the source snapshot}" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export PATH="$tmp:$PATH" XDG_RUNTIME_DIR="$tmp" JOB_PROBE_OUT="$tmp/submission"
export INCOMING_GPU_LEASE="$tmp/lease" INCOMING_SOFT_HEAP=3g INCOMING_SOFT_RESERVE_MB=4096

cat > "$tmp/awk" <<'STUB'
#!/usr/bin/env bash
if [ "${*: -1}" = /proc/meminfo ]; then
  echo 10240
else
  PATH="${PATH#*:}" exec awk "$@"
fi
STUB
cat > "$tmp/systemd-run" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$JOB_PROBE_OUT"
STUB
printf '#!/usr/bin/env bash\nexit 1\n' > "$tmp/systemd-inhibit"
printf '#!/usr/bin/env bash\nexit 0\n' > "$tmp/systemctl"
chmod +x "$tmp"/{awk,systemd-run,systemd-inhibit,systemctl}

check() {
  local ceiling=$1 expected=$2 rc=0 submitted=no
  rm -f "$JOB_PROBE_OUT"
  INCOMING_JOB_MEM="$ceiling" "$root/tools/laptop_job.sh" --soft review -- true || rc=$?
  if [ -f "$JOB_PROBE_OUT" ]; then
    submitted=yes
    grep -Fx "MemoryMax=$ceiling" "$JOB_PROBE_OUT"
  fi
  printf 'MemoryMax=%s, 10240 MiB available, 4096 MiB reserve: exit=%s, submitted=%s\n' \
    "$ceiling" "$rc" "$submitted"
  if [ "$expected" = 0 ]; then
    [ "$rc" = 0 ] && [ "$submitted" = yes ]
  else
    [ "$rc" = "$expected" ] && [ "$submitted" = no ]
  fi
}

failed=0
check 5G 0 || failed=1
check 16G 75 || failed=1
# 6.5 GiB requires 6656 MiB. The host can admit at most 6144 MiB.
check 6.5G 75 || failed=1
exit "$failed"
