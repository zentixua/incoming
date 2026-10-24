#!/usr/bin/env bash
set -uo pipefail
T=$1 EFF=$2 AT=$3 CAM=$4 TAG=$5
F=$T/mod/src/main/java/ua/zentix/incoming/client/fx/layer/FxBloom.java
cp "$F" /tmp/fxbloom.keep.java
run() { "$T/tools/sandbox.sh" shot "$1" "$EFF" --at "$AT" --cam "$CAM" --time night $2 >/dev/null 2>&1; }
off() { python3 - "$F" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace("        if (!quads.glows() || failed","        if (quads.glows()) return;\n        if (!quads.glows() || failed",1)
open(p,'w').write(s)
PY
}
for r in 1 2 3; do
  cp /tmp/fxbloom.keep.java "$F"; run ${TAG}xon$r ""
  off; run ${TAG}xoff$r ""
done
cp /tmp/fxbloom.keep.java "$F"
"$T/tools/sandbox.sh" swap >/dev/null 2>&1
grep "SANDBOX shot ${TAG}x" "$T/mod/run/sandbox/logs/latest.log" | sed -E 's/.*shot [a-z0-9]+x([a-z]+)([0-9])_0*([0-9.]+)s.*GPU ([0-9.]+) ms \(longest [0-9.]+, ([0-9]+) frames; heat pass ([0-9.]+).*/\1 \2 \3 \4 \6/' | awk '{v=$4-$5; k=$1" "$3; s[k]+=v; n[k]++} END {for (k in s) printf "%s %.3f\n", k, s[k]/n[k]}' | sort
