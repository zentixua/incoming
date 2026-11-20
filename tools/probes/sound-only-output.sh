#!/usr/bin/env bash
# Review probe of the shared GPU / sound-only work, kept as a regression test (run by tools/test_gpu_lease.sh, test_sandbox.sh).
# Exercises the real shot and capture metadata dispatch in a temporary directory.
set -euo pipefail
root=$(cd "${1:-$(dirname "$0")/../..}" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# sandbox.sh derives its source paths from $0, also when sourced.
bash -c '
  source "$1"
  FX="$2/fx"
  mkdir -p "$FX/out/kept"
  echo soft > "$FX/renderer"
  echo prior-capture > "$FX/out/kept/capture.json"
  fx() {
    if [[ $1 == shots* ]]; then
      local verb take args
      read -r verb take args <<<"$1"
      mkdir -p "$FX/out/$take"
      printf "{\"audio\": {\"seconds\": 2}}\n" > "$FX/out/$take/capture.json"
    fi
    return 0
  }
  cut_wav() { touch "$2"; }
  failed=0
  ( shot "" "blast drone" --at none --wav 2 --no-swap ) > "$2/empty.out" 2>&1
  rc=$?
  kept=no; [ ! -f "$FX/out/kept/capture.json" ] || kept=yes
  printf "Empty name: exit %s, prior capture kept=%s\n" "$rc" "$kept"
  [ "$kept" = yes ] || failed=1
  ( shot tabbed "$(printf "blast\tdrone")" --at none --wav 2 --no-swap ) > "$2/tabbed.out" 2>&1
  rc=$?
  printf "Valid effect separated by a tab: exit %s\n" "$rc"
  if [ "$rc" != 0 ]; then cat "$2/tabbed.out"; failed=1; fi
  exit "$failed"
' "$root/tools/probe-driver" "$root/tools/sandbox.sh" "$tmp"
