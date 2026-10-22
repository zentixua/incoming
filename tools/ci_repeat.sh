#!/usr/bin/env bash
# Повтор нестабильного GameTest на GitHub, а не на ноутбуке (запускать в фоне — конец разбудит):
#
#   tools/ci_repeat.sh <ветка> <регвыр партий> [повторов на машину, по умолчанию 5]
#
# Ручной запуск build на ветке (ветка должна быть в origin): каждая из шести машин гоняет партии, чьё имя подходит
# под выражение (как -PgametestOnly), N раз в новом мире — всего 6·N прогонов за ~(1 + N·длина партий) мин. Итог —
# «прошло X из Y» по машинам и ссылка; код 0 — все прошли, 1 — были падения (логи упавших — артефакты logs-gametest-*).
# 22.10 одну нестабильную партию ловили 41 прогоном на ноутбуке (8,6 ч) и полными ручными прогонами CI.
set -euo pipefail
branch=${1:?использование: tools/ci_repeat.sh <ветка> <регвыр> [N]}; only=${2:?регвыр партий}; n=${3:-5}
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
gh workflow run build.yml --ref "$branch" -f release=false -f gametest_only="$only" -f gametest_repeat="$n"
run=
for _ in $(seq 30); do
  run=$(gh run list --workflow build.yml --branch "$branch" --event workflow_dispatch --limit 5 \
    --json databaseId,createdAt -q "[.[] | select(.createdAt >= \"$since\")][0].databaseId // empty")
  [ -n "$run" ] && break; sleep 5
done
[ -n "$run" ] || { echo "запуск не нашёлся"; exit 2; }
echo "запуск $run: «$only» × $n на 6 машинах — https://github.com/${GITHUB_REPOSITORY:-zentixua/incoming}/actions/runs/$run"
gh run watch "$run" --exit-status --interval 30 > /dev/null 2>&1 || true
total=0; ok=0
for job in $(gh run view "$run" --json jobs -q '.jobs[] | select(.name | startswith("GameTest")) | .databaseId'); do
  line=$(gh run view "$run" --job "$job" --log 2>/dev/null | grep -o 'GameTest repeat: .*' | tail -1)
  echo "  ${line:-задача $job: итога нет (упала до повторов?)}"
  p=$(sed -n 's/.*passed \([0-9]*\) of \([0-9]*\).*/\1 \2/p' <<< "$line")
  [ -n "$p" ] && { ok=$((ok + ${p% *})); total=$((total + ${p#* })); }
done
echo "итого: прошло $ok из $total"
[ "$total" -gt 0 ] && [ "$ok" = "$total" ]
