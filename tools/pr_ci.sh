#!/usr/bin/env bash
# Wait for PR checks with one command (run in background; completion wakes the session):
#
#   tools/pr_ci.sh <PR number> [--repo owner/repo]
#
# Wait for "Итог" on the current PR head (merge condition), then exit with code:
#   0 — green; 1 — failure: failed job names and log tail for each failed run (gh run view --log-failed);
#   3 — base conflict: GitHub does not start checks on conflicting PRs, so `gh pr checks --watch` would wait forever
#       (V and S5 waited silently for 10–20 min this way on 21–22.10); merge main and push;
#   4 — PR closed without merging. Merged returns 0.
# Push while waiting: wait for checks on the new head.
set -uo pipefail
pr=${1:?usage: tools/pr_ci.sh <PR number> [--repo owner/repo]}; shift
repo=(); [ "${1:-}" = --repo ] && { repo=(--repo "$2"); export GH_REPO=$2; }
need='^Итог$'
started=$(date +%s); head=; quiet=0
while true; do
  read -r state mergeable sha < <(gh pr view "$pr" "${repo[@]}" --json state,mergeable,headRefOid -q '"\(.state) \(.mergeable) \(.headRefOid)"') || { sleep 30; continue; }
  case $state in MERGED) echo "PR $pr is already merged"; exit 0 ;; CLOSED) echo "PR $pr was closed without merging"; exit 4 ;; esac
  [ "$sha" != "$head" ] && { [ -n "$head" ] && echo "$(date +%T) new head ${sha:0:8} — waiting for its checks"; head=$sha; }
  if [ "$mergeable" = CONFLICTING ]; then
    echo "PR $pr: conflict with the base — CI will not start. Merge main into the branch, resolve the conflict, and push."
    exit 3
  fi
  checks=$(gh pr checks "$pr" "${repo[@]}" --json name,bucket,link,completedAt -q '.[] | "\(.bucket)\t\(.name)\t\(.link)\t\(.completedAt)"' 2>/dev/null)
  # GitHub sometimes leaves a finished job in_progress (22.10: "Итог" #383 ended at 15:28:55 with
  # success, but status never changed); for a pending job with an end time, read the result from the job itself
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
  if [ "$(printf '%s\n' "$mine" | grep -c .)" -ge 1 ] && ! printf '%s\n' "$mine" | grep -q '^pending'; then
    if ! printf '%s\n' "$mine" | grep -qv '^pass'; then
      echo "PR $pr (${sha:0:8}): Итог is green after $(( ($(date +%s) - started) / 60 )) min of waiting"; exit 0
    fi
    # after body edits or reruns, gh pr checks briefly shows the old result: confirm failure on a second look
    if [ "${seen:-}" != "$sha" ]; then seen=$sha; sleep 30; continue; fi
    echo "PR $pr (${sha:0:8}): checks failed"
    printf '%s\n' "$checks" | awk -F'\t' '$1 == "fail" || $1 == "cancel" {print "  " $1 ": " $2 " " $3}'
    for run in $(printf '%s\n' "$checks" | awk -F'\t' '$1 == "fail" {print $3}' | sed -n 's|.*/actions/runs/\([0-9]*\)/.*|\1|p' | sort -u); do
      gh run view "$run" "${repo[@]}" --log-failed 2>/dev/null | cut -f2- | grep -v '^\s*$' | tail -40
    done
    exit 1
  fi
  # checks not yet present: after 5 min, say what is pending (one line, not every iteration)
  if [ -z "$mine" ] && [ $quiet = 0 ] && [ $(( $(date +%s) - started )) -gt 300 ]; then
    echo "$(date +%T) no checks for ${sha:0:8} after 5 min (mergeable=$mergeable) — still waiting"; quiet=1
  fi
  sleep 30
done
