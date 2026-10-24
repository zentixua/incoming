#!/usr/bin/env bash
# salvo.sh <дерево> <метка> — цена кадра залпа в песочнице дерева: 8 шахедов разом в 15 м от точки, камера в 50 м.
# Кадры редкие (к/с за прошлую секунду — mc.getFps(), без нагрузки снимками) и плотные первые 0,5 с (интервалы).
set -e
cd "$1"; tag=$2
cam='50 1.62 0 3'
tools/sandbox.sh fx 'wait 60' >/dev/null
tools/sandbox.sh shot warm 'blast drone 7' --at 0.1 --wav 1 --cam "$cam" --no-swap >/dev/null
for rep in 1 2; do
  tools/sandbox.sh shot "s8-$tag-$rep" 'raid 8 15 0' --at 0.5,1,1.5,2,3,4,5,6,8,10,12,14 --wav 1 --cam "$cam" --no-swap >/dev/null
  tools/sandbox.sh shot "s8d-$tag-$rep" 'raid 8 15 0' --at $(python3 -c "print(','.join(f'{i/60:.4f}' for i in range(31)))") --wav 1 --cam "$cam" --no-swap >/dev/null
  tools/sandbox.sh shot "s1d-$tag-$rep" 'blast drone 7' --at $(python3 -c "print(','.join(f'{i/60:.4f}' for i in range(31)))") --wav 1 --cam "$cam" --no-swap >/dev/null
done
out=mod/run/sandbox/fx/out
python3 - "$out" "$tag" <<'PY'
import json, sys
out, tag = sys.argv[1], sys.argv[2]
for rep in (1, 2):
    c = json.load(open(f'{out}/s8-{tag}-{rep}/capture.json'))
    print(f'{tag} rep{rep} salvo fps by second:', ' '.join(f"{f['age']:.1f}s:{f['fps']}" for f in c['frames']))
    for name in (f's8d-{tag}-{rep}', f's1d-{tag}-{rep}'):
        c = json.load(open(f'{out}/{name}/capture.json'))
        ages = sorted({round(f['age'], 4) for f in c['frames']})
        gaps = [1000 * (b - a) for a, b in zip(ages, ages[1:])]
        print(f'  {name}: frames {len(ages)} in {ages[-1]-ages[0]:.3f} s, first gap {gaps[0]:.0f} ms, max gap {max(gaps):.0f} ms, mean {sum(gaps)/len(gaps):.1f} ms')
PY
grep -h "SANDBOX shot s8-$tag" mod/run/sandbox/logs/latest.log | sed -E 's/.*shot (s8-[^ ]*) age ([0-9.]+) s fps ([0-9]+); layer: ([^;]*);.*/\1 \2s fps \3 layer \4/' | cut -c1-200
