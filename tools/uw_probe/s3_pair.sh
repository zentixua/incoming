#!/bin/bash
# side-by-side clips of a head take and main's take of the same scene: main left, head right, labelled; the same clip kinds
# (flash_to_passage, passage, lapse) from s3_analyse.py. Usage: s3_pair.sh <head take dir> <main take dir> <out prefix>
set -u
H=$1 M=$2 O=$3
for k in flash_to_passage passage lapse; do
  [ -f "$H/$k.mp4" ] && [ -f "$M/$k.mp4" ] || continue
  ffmpeg -y -loglevel error -i "$M/$k.mp4" -i "$H/$k.mp4" -filter_complex \
    "[0:v]scale=960:540,drawtext=text='main (old wave)':x=12:y=12:fontsize=26:fontcolor=white:box=1:boxcolor=black@0.5[a];[1:v]scale=960:540,drawtext=text='head (new wave)':x=12:y=12:fontsize=26:fontcolor=white:box=1:boxcolor=black@0.5[b];[a][b]hstack=inputs=2,format=yuv420p[v]" \
    -map "[v]" -shortest -c:v libx264 -crf 20 "${O}_$k.mp4"
done
