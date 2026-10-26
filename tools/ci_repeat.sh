#!/usr/bin/env bash
# Replay of the unstable GameTest on GitHub, and not on the laptop (run in the background - the end will wake you up):
#
#   tools/ci_repeat.sh <branch> <regular batch> [repeats per machine, default 5]
#
# Manual launch of build on a branch (the branch must be in origin): each of the six cars runs a batch, whose name is in full
# matches the expression (as -PgametestOnly: batch name, not test name; none is a failure), N times in a new world — 6·N runs total in ~(1 + N·batch duration) min. Result:
# “passed X from Y” by car and link; code 0 - all passed, 1 - there were falls (logs of those who fell - artifacts logs-gametest-*).
# 22.10 one unstable batch was caught by 41 run on a laptop (8,6 h) and full manual runs of CI.
set -euo pipefail
branch=${1:?usage: tools/ci_repeat.sh <branch> <regexp> [N]}; only=${2:?regexp batch}; n=${3:-5}
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
gh workflow run build.yml --ref "$branch" -f release=false -f gametest_only="$only" -f gametest_repeat="$n"
run=
for _ in $(seq 30); do
  run=$(gh run list --workflow build.yml --branch "$branch" --event workflow_dispatch --limit 5 \
    --json databaseId,createdAt -q "[.[] | select(.createdAt >= \"$since\")][0].databaseId // empty")
  [ -n "$run" ] && break; sleep 5
done
[ -n "$run" ] || { echo "launch not found"; exit 2; }
echo "launching $run: \"$only\" × $n on 6 machines - https://github.com/${GITHUB_REPOSITORY:-zentixua/incoming}/actions/runs/$run"
# your survey, not gh run watch: the one on the newly created launch comes out immediately
until [ "$(gh run view "$run" --json status -q .status 2>/dev/null)" = completed ]; do sleep 30; done
total=0; ok=0
for job in $(gh run view "$run" --json jobs -q '.jobs[] | select(.name | startswith("GameTest")) | .databaseId'); do
  # the total line, not the source of the step (the log shows that too): with numbers
  line=$(gh run view "$run" --job "$job" --log 2>/dev/null | grep -oE 'GameTest repeat: .* passed [0-9]+ of [0-9]+ .*' | tail -1 || true)
  p=$(sed -n 's/.*passed \([0-9]*\) of \([0-9]*\).*/\1 \2/p' <<< "$line")
  if [ -n "$p" ]; then
    echo "  $line"; ok=$((ok + ${p% *})); total=$((total + ${p#* }))
  else
    echo "  task $job: no result (fell due to repetitions or time) - I consider $n a fall from $n"; total=$((total + n))
  fi
done
conclusion=$(gh run view "$run" --json conclusion -q .conclusion)
echo "total: passed $ok from $total (run: $conclusion)"
[ "$total" -gt 0 ] && [ "$ok" = "$total" ] && [ "$conclusion" = success ]
