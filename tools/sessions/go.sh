#!/usr/bin/env bash
# Background claude2 session on the laptop: start, resume, wait, status:
#
#   tools/sessions/go.sh <S>                          — new session from <S>.txt
#   tools/sessions/go.sh <S> <session_id> <message>   — resume session
#   tools/sessions/go.sh --wait <S>                   — wait for completion (run in background: completion wakes you)
#   tools/sessions/go.sh --status                     — running sessions and recent sessions.log lines
#
# Files in $SESSIONS_DIR (default mod/run/incoming-sessions in the main copy): <S>.txt task, context.md brief;
# each turn result: <S>.json (first), <S>.r1.json, <S>.r2.json …, plus a line in
# sessions.log. The session runs in its own systemd service incoming-claude-<S>: losing its caller does not stop it
# (22.10 01:16: S5 and S8 died with the parent session; Claude children die with its group, setsid does not help).
# The service script is a copy (supervise.sh → .supervise-<S>.sh): live edits cannot break it (21.10: editing run.sh
# during a run overwrote P and S3 results; bash resumes reading the modified file at its old offset).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$(git -C "$here" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)
D=${SESSIONS_DIR:-$root/mod/run/incoming-sessions}

case "${1:-}" in
  --wait)
    [ -n "${2:-}" ] || { echo "go.sh --wait <S>" >&2; exit 2; }
    while systemctl --user is-active --quiet "incoming-claude-$2"; do sleep 60; done
    grep -P "^\S+ \S+ \Q$2\E[ :]" "$D/sessions.log" 2>/dev/null | tail -1; exit 0 ;;
  --status)
    systemctl --user list-units 'incoming-claude-*' --state=active --no-legend --plain | awk '{print "running: " $1}'
    tail -n 15 "$D/sessions.log" 2>/dev/null; exit 0 ;;
  ''|-*) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
S=$1; shift
[ $# -eq 0 ] || [ $# -eq 2 ] || { echo "resume needs two arguments: <session_id> <message>" >&2; exit 2; }
[ $# -eq 0 ] && [ ! -s "$D/$S.txt" ] && { echo "no task $D/$S.txt" >&2; exit 2; }
install -m 755 "$here/supervise.sh" "$D/.supervise-$S.sh"
git -C "$root" fetch origin -q || true
systemd-run --user --unit="incoming-claude-$S" --collect -p WorkingDirectory="$root" \
  --setenv=PATH="$PATH" --setenv=HOME="$HOME" --setenv=SESSIONS_DIR="$D" --setenv=REPO="$root" \
  ${SESSION_TREES:+--setenv=SESSION_TREES="$SESSION_TREES"} \
  -p StandardOutput="append:$D/$S.supervise.log" -p StandardError="append:$D/$S.supervise.log" \
  "$D/.supervise-$S.sh" "$S" "$@"
echo "started incoming-claude-$S; wait: $0 --wait $S"
