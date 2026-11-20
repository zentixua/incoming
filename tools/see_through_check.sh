#!/usr/bin/env bash
# Effects behind see-through blocks, the regression check: a drone blast seen through a wall of each block (sandbox pane)
# and in the open; tools/see_through.py measures the blast's visible share 2 s after it and fails outside the bounds
# (stained glass, ice, slime and honey tint the blast and never hide it; stone hides it).
#
#   tools/see_through_check.sh [fast|fancy|fabulous] [sandbox start options…]   frames → mod/run/see_through/<graphics>/
#
# Starts this tree's sandbox (on the CPU by default) and stops it at the end; code 1 — a share outside its bound.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
graphics=fancy
case "${1:-}" in fast|fancy|fabulous) graphics=$1; shift ;; esac
S="$ROOT/tools/sandbox.sh" OUT="$ROOT/mod/run/see_through/$graphics" first=1
mkdir -p "$OUT"
rm -f "$OUT"/*.png
# the check owns its sandbox: one already running (another task's, or with other code) is not stopped or reused
"$S" status 2>/dev/null | grep -q "^running" && { echo "this tree's sandbox is already running: stop it first" >&2; exit 2; }
"$S" start "$@" || exit $?
trap '"$S" stop > /dev/null' EXIT
"$S" fx "graphics $graphics" 'wait 40' > /dev/null || exit 1
for p in none stone glass_pane white_stained_glass red_stained_glass_pane tinted_glass ice slime_block honey_block; do
  if [ $p = none ]; then "$S" fx 'pane off' > /dev/null || exit 1; else "$S" fx "pane $p" > /dev/null || exit 1; fi
  "$S" fx 'wait 30' > /dev/null || exit 1
  sw=--no-swap; [ $first = 1 ] && sw="" && first=0
  "$S" shot "base_$p" 'fire 1' --cam '60 10 90 8' --at 0.05 --wav 0 $sw > /dev/null || exit 1
  "$S" shot "blast_$p" 'blast drone 1' --cam '60 10 90 8' --at 2 --wav 0 --no-swap > /dev/null || exit 1
  cp "$ROOT"/mod/run/sandbox/fx/out/{base,blast}_"$p"/*.png "$OUT/" || exit 1
done
"$S" fx 'pane off' > /dev/null
# bounds: the shares measured on the change that made effects visible behind these blocks, less 0.05 (frame noise); the
# upper bounds catch a missing tint (an untinted blast through a pane is near 1)
uv run "$ROOT/tools/see_through.py" "$OUT" --sheet "$OUT/sheet.png" --max stone=0.05 --min glass_pane=0.55 \
  --min white_stained_glass=0.42 --min red_stained_glass_pane=0.39 --min tinted_glass=0.35 --min ice=0.16 \
  --min slime_block=0.10 --min honey_block=0.08 --max white_stained_glass=0.65 --max red_stained_glass_pane=0.65 \
  --max tinted_glass=0.6 --max ice=0.4 --max slime_block=0.35 --max honey_block=0.3
