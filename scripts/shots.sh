#!/usr/bin/env bash
# shots.sh <prefix> [only-regex] — the comparison set (same effects, cameras, times as SM/#428 + night)
cd "$(dirname "$0")/../.." || exit 1
P=$1; ONLY=${2:-.}
run() { local n=$1; shift; [[ $n =~ $ONLY ]] || return 0; tools/sandbox.sh shot "$P-$n" "$@" --wav 0 2>&1 | grep -E 'кадр|error|ошибка|не ' | tail -3; }
run drone      'blast drone 7'   --at 3,10,30 --cam '80 10 90 5'
run droneN     'blast drone 7'   --at 3,10,30 --cam '80 10 90 5' --time night
run near       'blast drone 7'   --at 3,10    --cam '40 6 90 8'
run missileN   'blast missile 7' --at 1,3,10,30 --cam '120 15 90 5' --time night
run missile    'blast missile 7' --at 3,10,30 --cam '120 15 90 5'
run fire       'fire 16'         --at 10,20   --cam '60 15 90 5'
run fireN      'fire 16'         --at 20      --cam '60 15 90 5' --time night
run raid       'raid 16 120 3'   --at 20,55   --cam '30 25 0 0'
run raidN      'raid 16 120 3'   --at 20,55   --cam '30 25 0 0' --time night
