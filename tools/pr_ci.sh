#!/usr/bin/env bash
# Ждать проверок PR одной командой (запускать в фоне — конец разбудит):
#
#   tools/pr_ci.sh <номер PR> [--repo владелец/репо]
#
# Ждёт «Итог» и «Записку» у текущей головы PR (условия влития), у PR с картинкой (PLAYER_FACING в tools/pr_note.py) —
# ещё статус «Стенд сцен» (tools/stand: stand.py pr <номер> --post на ноутбуке), и выходит с кодом:
#   0 — обе зелёные; 1 — что-то упало: имена упавших задач и хвост лога каждого упавшего запуска (gh run view --log-failed);
#   3 — конфликт с базой: GitHub проверки PR с конфликтом не запускает вовсе, и `gh pr checks --watch` ждал бы вечно
#       (21–22.10 так 10–20 мин молча стояли V и S5) — слить main и запушить;
#   4 — PR закрыт без влития. Влит — 0.
# Пуш в ветку во время ожидания: ждём проверки новой головы.
set -uo pipefail
pr=${1:?использование: tools/pr_ci.sh <номер PR> [--repo владелец/репо]}; shift
repo=(); [ "${1:-}" = --repo ] && { repo=(--repo "$2"); export GH_REPO=$2; }
started=$(date +%s); head=; quiet=0
# нужные проверки головы: PR с картинкой ждёт и стенд. Файлы — из API, у переноса и прежний путь (как note.yml): перенос
# из того, что видит игрок, — тоже его правка. Список не получен — не решаем (код 1): иначе сбой API снял бы условие стенда
needs() {
  local files
  files=$(gh api --paginate "repos/{owner}/{repo}/pulls/$pr/files" --jq '.[] | .filename, (.previous_filename // empty)' 2>/dev/null) || return 1
  need='^(Итог|Записка)$'; count=2
  if printf '%s\n' "$files" \
      | python3 -c 'import sys; sys.path.insert(0, sys.argv[1]); import pr_note; sys.exit(0 if pr_note.player_facing([l.strip() for l in sys.stdin if l.strip()]) else 1)' "$(dirname "$0")"; then
    need='^(Итог|Записка|Стенд сцен)$'; count=3
  fi
}
while true; do
  read -r state mergeable sha < <(gh pr view "$pr" "${repo[@]}" --json state,mergeable,headRefOid -q '"\(.state) \(.mergeable) \(.headRefOid)"') || { sleep 30; continue; }
  case $state in MERGED) echo "PR $pr уже влит"; exit 0 ;; CLOSED) echo "PR $pr закрыт без влития"; exit 4 ;; esac
  if [ "$sha" != "$head" ]; then
    needs || { sleep 30; continue; }
    [ -n "$head" ] && echo "$(date +%T) новая голова ${sha:0:8} — жду её проверок"
    head=$sha
  fi
  if [ "$mergeable" = CONFLICTING ]; then
    echo "PR $pr: конфликт с базой — CI не запустится. Слить main в ветку, разрешить конфликт, запушить."
    exit 3
  fi
  checks=$(gh pr checks "$pr" "${repo[@]}" --json name,bucket,link,completedAt -q '.[] | "\(.bucket)\t\(.name)\t\(.link)\t\(.completedAt)"' 2>/dev/null)
  # GitHub иногда оставляет у законченной задачи состояние in_progress (22.10: «Итог» #383 закончился в 15:28:55 с
  # success, а статус так и не сменился) — у такой «ждущей» с временем конца итог берётся из самой задачи
  checks=$(printf '%s\n' "$checks" | while IFS=$'\t' read -r b name link done; do
    [ -z "$name" ] && continue
    if [ "$b" = pending ] && [ -n "$done" ] && [ "${done#0001}" = "$done" ]; then
      case $(gh api "repos/{owner}/{repo}/actions/jobs/${link##*/}" -q .conclusion 2>/dev/null) in
        success) b=pass ;; failure) b=fail ;; cancelled) b=cancel ;; skipped) b=skipping ;;
      esac
    fi
    printf '%s\t%s\t%s\n' "$b" "$name" "$link"
  done)
  mine=$(printf '%s\n' "$checks" | awk -F'\t' -v re="$need" '$2 ~ re')
  if [ "$(printf '%s\n' "$mine" | grep -c .)" -ge "$count" ] && ! printf '%s\n' "$mine" | grep -q '^pending'; then
    if ! printf '%s\n' "$mine" | grep -qv '^pass'; then
      echo "PR $pr (${sha:0:8}): $(printf '%s\n' "$mine" | cut -f2 | paste -sd, | sed 's/,/, /g') зелёные, $(( ($(date +%s) - started) / 60 )) мин ожидания"; exit 0
    fi
    # после правки описания или перезапуска gh pr checks ещё миг показывает прежний итог: падение — со второго взгляда
    if [ "${seen:-}" != "$sha" ]; then seen=$sha; sleep 30; continue; fi
    echo "PR $pr (${sha:0:8}): не прошло"
    printf '%s\n' "$checks" | awk -F'\t' '$1 == "fail" || $1 == "cancel" {print "  " $1 ": " $2 " " $3}'
    for run in $(printf '%s\n' "$checks" | awk -F'\t' '$1 == "fail" {print $3}' | sed -n 's|.*/actions/runs/\([0-9]*\)/.*|\1|p' | sort -u); do
      gh run view "$run" "${repo[@]}" --log-failed 2>/dev/null | cut -f2- | grep -v '^\s*$' | tail -40
    done
    exit 1
  fi
  # проверки ещё не пришли: через 5 мин сказать, чего ждём (одна строка, не каждый круг)
  if [ "$(printf '%s\n' "$mine" | grep -c .)" -lt "$count" ] && [ $quiet = 0 ] && [ $(( $(date +%s) - started )) -gt 300 ]; then
    if [ "$count" = 3 ] && ! printf '%s\n' "$mine" | grep -q $'\tСтенд сцен'; then
      echo "$(date +%T) у ${sha:0:8} нет статуса «Стенд сцен»: PR с картинкой — на ноутбуке tools/stand/stand.py pr $pr --post; жду дальше"
    else
      echo "$(date +%T) у ${sha:0:8} 5 мин нет проверок (mergeable=$mergeable) — жду дальше"
    fi
    quiet=1
  fi
  sleep 30
done
