#!/bin/bash
set -euo pipefail
TASK_DATA=/mnt/data/projects/incoming/mod/run/wt-PERF-C2/mod/run/perf-c2
for run in final-a1 final-post-b final-a2; do
 date -u +'%FT%TZ'
 python3 "$TASK_DATA/run.py" "$run" > "$TASK_DATA/$run-job.log" 2>&1
 echo "$run completed"
done
