#!/usr/bin/env bash
# Набор сцен HB2 в песочнице дерева $1 с приставкой $2 (кадры — в $1/mod/run/sandbox/fx/out/<приставка>_<сцена>/).
# $3 — список сцен (по умолчанию все).
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
  "$S" shot "${TAG}_$name" "$@" $swap 2>&1 | tail -3
}
shot drone_day 'blast drone 1' --at 0.1,0.5,1,3,10 --cam '50 6 90 8'
shot missile_day 'blast missile 1' --at 0.1,1,3 --cam '60 8 90 10'
shot bomb_day 'real bunker' --at 0.1,1,3 --cam '100 8 90 12'
shot launch_day 'launch missile' --at 0.3,1,2.5 --cam '45 4 90 15'
shot drone_night 'blast drone 1' --at 0.1,0.5,1,3,15 --cam '50 6 90 8' --time night
shot missile_night 'blast missile 1' --at 0.1,1,3 --cam '60 8 90 10' --time night
shot bomb_night 'real bunker' --at 0.1,1,3 --cam '100 8 90 12' --time night
shot launch_night 'launch missile' --at 0.3,1,2.5 --cam '45 4 90 15' --time night
shot fire_night 'blast drone 2' --at 20,40 --cam '30 4 90 4' --time night
