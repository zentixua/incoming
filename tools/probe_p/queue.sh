#!/usr/bin/env bash
# Очередь пробы: варианты по одному (видеокарта — одна задача). queue.sh <вариант>... ; вариант: A B C D E F
R=/mnt/data/projects/airstrike/mod/run
P=$R/probe-visual
blasts=drone-day-50,drone-night-50,grad-night
for v in "$@"; do
  case $v in
    A) $P/bin/run_variant.sh A $R/wt-P-a ;;
    B) $P/bin/run_variant.sh B $R/wt-P-b ;;
    C) $P/bin/run_variant.sh C $R/wt-P ;;
    D) $P/bin/run_variant.sh D $R/wt-P $P/mods/D $blasts ;;
    E) $P/bin/run_variant.sh E $R/wt-P $P/mods/E $blasts ;;
    F) $P/bin/run_variant.sh F $R/wt-P $P/mods/F drone-day-50,grad-day ;;
    C2) $P/bin/run_variant.sh C2 $R/wt-P ;;
    D2) $P/bin/run_variant.sh D2 $R/wt-P $P/mods/D2 $blasts ;;
    E2) $P/bin/run_variant.sh E2 $R/wt-P $P/mods/E $blasts ;;
    Bn) $P/bin/run_variant.sh Bn $R/wt-P-b - drone-night-300 ;;
    An) $P/bin/run_variant.sh An $R/wt-P-a - drone-night-300 ;;
    *) $P/bin/run_variant.sh $v $R/wt-P ;;
  esac
  echo "=== $v exit $?"
done
