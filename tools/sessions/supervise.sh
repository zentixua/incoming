#!/usr/bin/env bash
# Служба incoming-claude-<S> (запускает tools/sessions/go.sh, сюда — копией): ход claude2, строка в sessions.log и —
# если сессия закончила ход, а её задачи на ноутбуке (laptop_job.sh из её дерева mod/run/wt-<S>, wt-<S>-*) ещё идут, —
# ждёт их конца и продолжает сессию сообщением с их кодами. В режиме -p ход кончается, когда модель «ждёт» фоновую
# задачу, и будить её некому: 21–22.10 S5 продолжали так вручную 6 раз, S6 — 7. Не больше 10 продолжений.
set -uo pipefail
S=$1; shift
D=$SESSIONS_DIR; root=$REPO
trees=${SESSION_TREES:-$root/mod/run/wt-$S}
claude=$(command -v claude2 || echo "$HOME/.local/bin/claude2")
live="Новые решения Артёма, пришедшие после твоего старта, — в $D/live.md: перечитай его перед каждым запуском дольше пяти минут и перед влитием; что там сказано, главнее этого брифа. Фоновую задачу жди в этом же ходе: tools/laptop_job.sh с run_in_background — его конец тебя разбудит; timeout фоновой команды ставь по длине задачи (по умолчанию 10 мин, предел 2 ч), иначе её остановят посреди работы; закончишь ход, пока твои задачи ноутбука идут, — тебя продолжат, когда они кончатся."
brief=$D/.context-$S.md
{ cat "$D/context.md" 2>/dev/null; printf '\n\n## Идущая сессия\n%s\n' "$live"; } > "$brief"

if [ $# -ge 2 ]; then args=(--resume "$1" -p "$2"); n=$(ls "$D/$S".r*.json 2>/dev/null | wc -l); n=$((n + 1)); else args=(-p "$(cat "$D/$S.txt")"); n=0; fi

# задачи ноутбука этой сессии: службы incoming-job-* с рабочим каталогом в её дереве
own_jobs() {
  local u wd
  for u in $(systemctl --user list-units 'incoming-job-*' --state=active,activating --no-legend --plain | awk '{print $1}'); do
    wd=$(systemctl --user show "$u" -p WorkingDirectory --value)
    case "$wd/" in "$trees"/*|"$trees"-*) echo "$u" ;; esac
  done
}

for _ in $(seq 11); do
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
res = (d.get("result") or "").strip().replace("\n", " ")
line = "%s %s ход %s: код %s, %d мин, ходов %s, $%.2f%s — %s" % (
    time.strftime("%d.%m %H:%M"), s, n, code, (time.time() - float(t0)) / 60, d.get("num_turns", "?"),
    d.get("total_cost_usd") or 0, ", ошибка" if d.get("is_error") else "", res[-300:] or "итога нет")
open(log, "a").write(line + "\n")
print(d.get("session_id", ""))
PY
)
  jobs=$(own_jobs)
  [ -z "$jobs" ] || [ -z "$sid" ] && break
  echo "$(date '+%d.%m %T') $S: ход кончился, идут задачи: $(echo $jobs)" | tee -a "$D/sessions.log"
  while [ -n "$(own_jobs)" ]; do sleep 30; done
  report=""
  for u in $jobs; do
    st=$(journalctl --user -u "$u" -o cat --no-pager 2>/dev/null | grep -oE 'Main process exited, code=[a-z]+, status=[^ ]+|Deactivated successfully' | tail -1)
    report+="${u%-*}: ${st:-конец}; "
  done
  args=(--resume "$sid" -p "Твои задачи на ноутбуке закончились: $report Продолжай с того места, где остановился.")
  n=$((n + 1))
done
