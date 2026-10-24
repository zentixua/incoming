#!/usr/bin/env bash
# birth.sh <дерево> <метка> [повторов] — рывок кадра в тике рождения залпа из 8 шахедов (камера 50 м), N раз подряд.
set -e
cd "$1"; tag=$2; n=${3:-5}
cam='50 1.62 0 3'
times=$(python3 -c "print(','.join(f'{i/60:.4f}' for i in range(13)))")
tools/sandbox.sh shot warm 'blast drone 7' --at 0.1 --wav 1 --cam "$cam" --no-swap >/dev/null
for rep in $(seq 1 $n); do
  tools/sandbox.sh shot "b8-$tag-$rep" 'raid 8 15 0' --at "$times" --wav 1 --cam "$cam" --no-swap >/dev/null
done
python3 - mod/run/sandbox/fx/out "$tag" "$n" <<'PY'
import json, sys
out, tag, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
firsts, means = [], []
for rep in range(1, n + 1):
    c = json.load(open(f'{out}/b8-{tag}-{rep}/capture.json'))
    ages = sorted({round(f['age'], 4) for f in c['frames']})
    gaps = [1000 * (b - a) for a, b in zip(ages, ages[1:])]
    firsts.append(max(gaps[:2])); means.append(sum(gaps) / len(gaps))
print(tag, 'birth gap ms:', ' '.join(f'{g:.0f}' for g in firsts), '| mean interval first 0.2 s:', ' '.join(f'{m:.0f}' for m in means))
PY
