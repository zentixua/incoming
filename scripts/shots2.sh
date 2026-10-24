#!/usr/bin/env bash
# shots2.sh <prefix> [night] — scenes beyond strike smoke that use the same puff sheet: water spray, launch cloud, SAM trail
cd "$(dirname "$0")/../.." || exit 1
P=$1; T=(); S=""
[ "${2:-}" = night ] && { T=(--time night); S=N; }
run() { local n=$1; shift; tools/sandbox.sh shot "$P-$n$S" "$@" "${T[@]}" --wav 0 2>&1 | grep -E 'кадр|error|ошибка|не ' | tail -1; }
run water  'blast drone 7 water' --at 1,3,10 --cam '80 10 90 5'
run launch 'launch missile'      --at 1,3,6  --cam '60 8 90 5'
run sam    'sam'                 --at 1,3,6  --cam '80 10 90 15'
