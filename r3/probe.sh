#!/usr/bin/env bash
# probe.sh <имя> <камера 'дальн выс азимут взгляд'> <времена> [эффект] — swap, прогрев, кадры, лист view/<имя>.jpg
set -e
cd /mnt/data/projects/incoming/mod/run/wt-WV
name=$1 cam=$2 times=$3 fx=${4:-blast drone 7}
tools/sandbox.sh shot warm "$fx" --at 0.1 --wav 1 --cam "$cam" >/dev/null 2>&1 || { echo "swap/warm failed"; exit 1; }
tools/sandbox.sh shot "$name" "$fx" --at "$times" --wav 1 --cam "$cam" --no-swap 2>&1 | grep -v '^кадр\|^fx\|^звук' || true
cd mod/run/wv-r3 && uv run -q sheet.py view/$name.jpg 4 640 ../sandbox/fx/out/$name
