#!/bin/bash
# Probe clips and motion numbers (unified-wave step 1): 10 fps time lapses of the 1 frame/s takes, side-by-side sheets, and
# tools/clip_metrics.py on both sides (and on take 33's frames at 1 s spacing, -6..12 s).
set -u
S=/mnt/data/projects/incoming/mod/run/agents/unified-wave/step1
T33=/mnt/data/projects/incoming/mod/run/agents/dust-wall-pass-2/work/takes/t33_judge
TOOLS=/mnt/data/projects/incoming-wt-uwprobe/tools
mkdir -p $S/clips
for n in head main; do
  d=$S/takes/life_$n
  ls $d/*.png | python3 -c "
import sys
fs = [f for f in sys.stdin.read().split() if -8 <= float(f.rsplit('_', 1)[1][:-5]) <= 600]
fs.sort(key=lambda f: float(f.rsplit('_', 1)[1][:-5]))
print('\n'.join(\"file '\" + f + \"'\" for f in fs))" > /tmp/uw_lapse_$n.txt
  ffmpeg -y -loglevel error -r 10 -f concat -safe 0 -i /tmp/uw_lapse_$n.txt -c:v libx264 -pix_fmt yuv420p -crf 16 -r 10 $S/clips/lapse_$n.mp4
  # the lapse's frames as a 1 s step sequence for the metrics (sandbox naming: their spacing from the ages)
  rm -rf /tmp/uw_lapse_$n; mkdir -p /tmp/uw_lapse_$n
  while read -r line; do f=${line#file \'}; f=${f%\'}; ln -s "$f" /tmp/uw_lapse_$n/; done < /tmp/uw_lapse_$n.txt
done
{
  for n in head main; do
    for w in "0,0.35,1,1" "0,0.5,1,1"; do
      echo "== $n lapse -8..600 s (1 frame/s), region $w"
      uv run -q $TOOLS/clip_metrics.py change /tmp/uw_lapse_$n --region $w --lag 1
      uv run -q $TOOLS/clip_metrics.py flicker /tmp/uw_lapse_$n --region $w --map $S/clips/flicker_$n.jpg
    done
  done
  # birth to 12 s at 1 s spacing on all three takes (take 33 frames sampled every 30th)
  rm -rf /tmp/uw_t33s; mkdir -p /tmp/uw_t33s
  for f in $T33/*.png; do t=${f##*_}; t=${t%s.png}; python3 -c "import sys; t=float('$t'); sys.exit(0 if abs(t-round(t))<0.02 and -6<=t<=12 else 1)" && ln -s $f /tmp/uw_t33s/; done
  for n in head main; do
    rm -rf /tmp/uw_y_$n; mkdir -p /tmp/uw_y_$n
    for f in $S/takes/life_$n/*.png; do t=${f##*_}; t=${t%s.png}; python3 -c "import sys; t=float('$t'); sys.exit(0 if -6<=t<=12 else 1)" && ln -s $f /tmp/uw_y_$n/; done
  done
  for n in t33s y_head y_main; do
    echo "== $n -6..12 s at 1 s spacing, region 0,0.35,1,1"
    uv run -q $TOOLS/clip_metrics.py change /tmp/uw_$n --region 0,0.35,1,1 --lag 1
  done
} > $S/clip_metrics.txt 2>&1
echo "done clips"
