#!/usr/bin/env bash
# Review probe (3.14.0 release audit), kept as a regression test (run by tools/test_sandbox.sh): a sound-only take
# named like an earlier frame take refuses and keeps that take's files. Fake client, output owned by this probe.
set -euo pipefail
root=$(cd "${1:-$(dirname "$0")/../..}" && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
bash -c '
  source "$1"
  FX="$2/fx"
  mkdir -p "$FX/out/shot"
  printf "%s\n" soft > "$FX/renderer"
  printf "%s\n" original-frame > "$FX/out/shot/shot_0.png"
  printf "%s\n" original-notes > "$FX/out/shot/notes.txt"
  fx() {
    local command take rest
    read -r command take rest <<< "$1"
    if [ "$command" = shots ]; then
      mkdir -p "$FX/out/$take"
      printf "%s\n" "{\"audio\": {\"seconds\": 2}}" > "$FX/out/$take/capture.json"
    fi
    return 0
  }
  cut_wav() { touch "$2"; }
  ( shot shot "blast drone" --at none --wav 2 --no-swap ) 2>/dev/null && { echo "FAIL: sound-only take over frames was accepted"; exit 2; }
  missing=0
  for file in shot_0.png notes.txt; do
    if [ ! -e "$FX/out/shot/$file" ]; then
      echo "FAIL: sound-only capture deleted $file"
      missing=1
    fi
  done
  exit "$missing"
' "$root/tools/probe-driver" "$root/tools/sandbox.sh" "$scratch"
