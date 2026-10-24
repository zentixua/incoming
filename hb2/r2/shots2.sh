#!/usr/bin/env bash
# Контроль после ревью: дым перед огнём. $1 — дерево, $2 — приставка, $3 — сцены
set -uo pipefail
TREE=$1 TAG=$2 ONLY=${3:-}
S="$TREE/tools/sandbox.sh"
first=1
shot() {
  local name=$1; shift
  [ -n "$ONLY" ] && [[ " $ONLY " != *" $name "* ]] && return 0
  local swap=--no-swap
  [ $first = 1 ] && swap= && first=0
  echo "== $TAG $name"
  "$S" shot "${TAG}_$name" "$@" $swap 2>&1 | tail -1
}
shot missile_night 'blast missile 1' --at 0.1,0.5,1,2 --cam '60 8 90 10' --time night
shot drone_night 'blast drone 1' --at 0.1,0.5,1,3 --cam '50 6 90 8' --time night
shot drone_back 'blast drone 1' --at 0.5,1,2 --cam '45 4 270 8' --time night
shot drone_day 'blast drone 1' --at 0.1,0.5,1,3 --cam '50 6 90 8'
shot fire_night 'blast drone 2' --at 20,40 --cam '30 4 90 4' --time night
shot bomb_night 'real bunker' --at 0.1,1,3 --cam '100 8 90 12' --time night
