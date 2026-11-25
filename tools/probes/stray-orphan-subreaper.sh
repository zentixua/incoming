#!/usr/bin/env bash
# Probe: stray_clients.sh lists an orphaned client JVM only when its parent is pid 1. Under a systemd user manager
# (systemd --user is a child subreaper) or any agent runner that is one, an orphan is reparented there, so the guard
# misses it. Starts a fake "java" (a copy of sleep under .../mod/build/rig/), orphans it, runs the report, kills it.
# exit 1 = defect present (orphan's parent is not 1 and it is not reported).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
tmp=$(mktemp -d); mkdir -p "$tmp/mod/build/rig"
cp "$(command -v sleep)" "$tmp/mod/build/rig/java"
( "$tmp/mod/build/rig/java" 60 & )
sleep 0.5
pid=$(pgrep -f "^$tmp/mod/build/rig/java 60" | head -1)
trap 'kill $pid 2>/dev/null; rm -rf "$tmp"' EXIT
ppid=$(awk '{print $4}' /proc/$pid/stat)
echo "orphan java $pid, parent $ppid ($(cat /proc/$ppid/comm))"
out=$("$ROOT/tools/stray_clients.sh"); echo "$out"
grep -q "java $pid" <<< "$out" && { echo "ok: reported"; exit 0; }
echo "DEFECT: orphaned client JVM not reported (parent $ppid is a subreaper, not init)"; exit 1
