#!/usr/bin/env bash
# shots3.sh <prefix> — fireball-to-smoke handoff: missile by day (dark halo), drone at night (jump near 1200 K)
cd "$(dirname "$0")/../.." || exit 1
P=$1
run() { local n=$1; shift; tools/sandbox.sh shot "$P-$n" "$@" --wav 0 2>&1 | grep -E 'кадр|error|ошибка|не ' | tail -1; }
run early     'blast missile 7' --at 0.05,0.1,0.2,0.5,1,2 --cam '120 15 90 5'
run earlyN    'blast drone 7'   --at 0.2,0.5,1,1.5,2,3    --cam '80 10 90 5' --time night
