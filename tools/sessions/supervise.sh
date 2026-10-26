#!/usr/bin/env bash
# Service incoming-claude-<S> (started by tools/sessions/go.sh, copied here): claude2 turn, sessions.log line, then,
# if the session ends its turn while its laptop jobs (laptop_job.sh from mod/run/wt-<S>, wt-<S>-*) still run,
# wait for them and resume the session with their exit codes. In -p mode, a turn ends when the model "waits" for a background
# job and nobody can wake it: on 21–22.10 S5 was resumed manually 6 times, S6 7 times. At most 10 resumptions.
set -uo pipefail
S=$1; shift
D=$SESSIONS_DIR; root=$REPO
trees=${SESSION_TREES:-$root/mod/run/wt-$S}
claude=$(command -v claude2 || echo "$HOME/.local/bin/claude2")
live="Wait for a background job in the same turn: tools/laptop_job.sh with run_in_background wakes you on completion; set the background command timeout to the job duration (default 10 min, maximum 2 h), otherwise it will be stopped mid-run; if you end the turn while laptop jobs are running, you will be resumed when they finish."
brief=$D/.context-$S.md
{ cat "$D/context.md" 2>/dev/null; printf '\n\n## Active session\n%s\n' "$live"; } > "$brief"

if [ $# -ge 2 ]; then args=(--resume "$1" -p "$2"); n=$(ls "$D/$S".r*.json 2>/dev/null | wc -l); n=$((n + 1)); else args=(-p "$(cat "$D/$S.txt")"); n=0; fi

# Laptop jobs for this session: incoming-job-* services with a working directory in its tree
own_jobs() {
  local u wd
  for u in $(systemctl --user list-units 'incoming-job-*' --state=active,activating --no-legend --plain | awk '{print $1}'); do
    wd=$(systemctl --user show "$u" -p WorkingDirectory --value)
    case "$wd/" in "$trees"/*|"$trees"-*) echo "$u" ;; esac
  done
}

for turn in $(seq 0 10); do
  out=$D/$S.json; [ "$n" -gt 0 ] && out=$D/$S.r$n.json
  t0=$(date +%s)
  "$claude" "${args[@]}" --append-system-prompt-file "$brief" --effort high --dangerously-skip-permissions \
    --output-format json > "$out" 2> "${out%.json}.log"
  code=$?
  sid=$(python3 - "$out" "$D/sessions.log" "$S" "$n" "$code" "$t0" <<'PY'
import json, sys, time
out, log, s, n, code, t0 = sys.argv[1:]
try:
    d = json.load(open(out))
except Exception:
    d = {}
if not isinstance(d, dict):
    d = {}
res = (d.get("result") or "").strip().replace("\n", " ")
line = "%s %s turn %s: code %s, %d min, turns %s, $%.2f%s — %s" % (
    time.strftime("%d.%m %H:%M"), s, n, code, (time.time() - float(t0)) / 60, d.get("num_turns", "?"),
    d.get("total_cost_usd") or 0, ", error" if d.get("is_error") else "", res[-300:] or "no result")
open(log, "a").write(line + "\n")
print(d.get("session_id", ""))
PY
)
  jobs=$(own_jobs)
  # Nothing to resume (turn ended, no jobs), no session ID, or already 10 resumptions
  [ -z "$jobs" ] || [ -z "$sid" ] || [ "$turn" = 10 ] && break
  echo "$(date '+%d.%m %T') $S: turn ended, jobs running: $(echo $jobs)" | tee -a "$D/sessions.log"
  while [ -n "$(own_jobs)" ]; do sleep 30; done
  report=""
  for u in $jobs; do
    st=$(journalctl --user -u "$u" -o cat --no-pager 2>/dev/null | grep -oE 'Main process exited, code=[a-z]+, status=[^ ]+|Deactivated successfully' | tail -1)
    report+="${u%-*}: ${st:-finished}; "
  done
  args=(--resume "$sid" -p "Your laptop jobs finished: $report Continue where you stopped.")
  n=$((n + 1))
done
