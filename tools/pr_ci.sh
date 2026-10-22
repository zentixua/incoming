#!/usr/bin/env bash
# Ждать проверок PR одной командой (запускать в фоне — конец разбудит):
#
#   tools/pr_ci.sh <номер PR> [--repo владелец/репо]
#
# Ждёт «Итог» и «Записку» у текущей головы PR (условия влития) и выходит с кодом:
#   0 — обе зелёные; 1 — что-то упало: имена упавших задач и хвост их лога (gh run view --log-failed);
#   3 — конфликт с базой: GitHub проверки PR с конфликтом не запускает вовсе, и `gh pr checks --watch` ждал бы вечно
#       (21–22.10 так 10–20 мин молча стояли V и S5) — слить main и запушить;
#   4 — PR закрыт без влития. Влит — 0.
# Пуш в ветку во время ожидания: ждём проверки новой головы.
set -uo pipefail
pr=${1:?использование: tools/pr_ci.sh <номер PR> [--repo владелец/репо]}; shift
repo=(); [ "${1:-}" = --repo ] && repo=(--repo "$2")
need='^(Итог|Записка)$'
started=$(date +%s); head=; quiet=0
while true; do
  read -r state mergeable sha < <(gh pr view "$pr" "${repo[@]}" --json state,mergeable,headRefOid -q '"\(.state) \(.mergeable) \(.headRefOid)"') || { sleep 30; continue; }
  case $state in MERGED) echo "PR $pr уже влит"; exit 0 ;; CLOSED) echo "PR $pr закрыт без влития"; exit 4 ;; esac
  [ "$sha" != "$head" ] && { [ -n "$head" ] && echo "$(date +%T) новая голова ${sha:0:8} — жду её проверок"; head=$sha; }
  if [ "$mergeable" = CONFLICTING ]; then
    echo "PR $pr: конфликт с базой — CI не запустится. Слить main в ветку, разрешить конфликт, запушить."
    exit 3
  fi
  checks=$(gh pr checks "$pr" "${repo[@]}" --json name,bucket,link -q '.[] | "\(.bucket)\t\(.name)\t\(.link)"' 2>/dev/null)
  mine=$(printf '%s\n' "$checks" | awk -F'\t' -v re="$need" '$2 ~ re')
  if [ "$(printf '%s\n' "$mine" | grep -c .)" -ge 2 ] && ! printf '%s\n' "$mine" | grep -q '^pending'; then
    if ! printf '%s\n' "$mine" | grep -qv '^pass'; then
      echo "PR $pr (${sha:0:8}): Итог и Записка зелёные, $(( ($(date +%s) - started) / 60 )) мин ожидания"; exit 0
    fi
    echo "PR $pr (${sha:0:8}): не прошло"
    printf '%s\n' "$checks" | awk -F'\t' '$1 == "fail" || $1 == "cancel" {print "  " $1 ": " $2 " " $3}'
    for run in $(printf '%s\n' "$checks" | awk -F'\t' '$1 == "fail" {print $3}' | sed -n 's|.*/actions/runs/\([0-9]*\)/.*|\1|p' | sort -u); do
      gh run view "$run" "${repo[@]}" --log-failed 2>/dev/null | cut -f2- | grep -v '^\s*$' | tail -40
    done
    exit 1
  fi
  # проверки ещё не пришли: через 5 мин сказать, чего ждём (одна строка, не каждый круг)
  if [ -z "$mine" ] && [ $quiet = 0 ] && [ $(( $(date +%s) - started )) -gt 300 ]; then
    echo "$(date +%T) у ${sha:0:8} 5 мин нет проверок (mergeable=$mergeable) — жду дальше"; quiet=1
  fi
  sleep 30
done
