#!/usr/bin/env bash
# Review probe of the shared GPU / sound-only work, kept as a regression test (run by tools/test_gpu_lease.sh, test_sandbox.sh).
# Cap the synthetic admission inputs only. The real laptop_job/soft_gl code
# runs up to submission, but systemd-run is stubbed and no memory is allocated.
set -euo pipefail
root=$(cd "${1:-$(dirname "$0")/../..}" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export PATH="$tmp:$PATH" XDG_RUNTIME_DIR="$tmp" JOB_PROBE_OUT="$tmp/args"
export INCOMING_SOFT_HEAP=3g INCOMING_SOFT_RESERVE_MB=4096 INCOMING_JOB_MEM=16G
cat > "$tmp/awk" <<'STUB'
#!/usr/bin/env bash
# Review probe of the shared GPU / sound-only work, kept as a regression test (run by tools/test_gpu_lease.sh, test_sandbox.sh).
if [ "${*: -1}" = /proc/meminfo ]; then
  echo 10240
else
  PATH="${PATH#*:}" exec awk "$@"
fi
STUB
cat > "$tmp/systemd-run" <<'STUB'
#!/usr/bin/env bash
# Review probe of the shared GPU / sound-only work, kept as a regression test (run by tools/test_gpu_lease.sh, test_sandbox.sh).
printf '%s\n' "$@" > "$JOB_PROBE_OUT"
STUB
printf '#!/usr/bin/env bash\nexit 1\n' > "$tmp/systemd-inhibit"
printf '#!/usr/bin/env bash\nexit 0\n' > "$tmp/systemctl"
chmod +x "$tmp"/{awk,systemd-run,systemd-inhibit,systemctl}
rc=0
"$root/tools/laptop_job.sh" --soft review -- true || rc=$?
printf 'Synthetic host: 10240 MiB available, 4096 MiB reserve, requested 16 GiB ceiling; exit %s\n' "$rc"
if [ -e "$JOB_PROBE_OUT" ]; then grep '^MemoryMax=' "$JOB_PROBE_OUT"; fi
[ "$rc" = 75 ] && [ ! -e "$JOB_PROBE_OUT" ]
