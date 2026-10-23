#!/usr/bin/env bash
# Проверка аренды видеокарты (CI, «Остановка проверок»): видеокарта у одной сессии; другая — отказ со строкой, кто и с
# какого времени, а с --wait — ждёт; своя сессия берёт снова без ожидания; брошенная аренда истекает; чужую не
# продлить и не отпустить; владелец — по дереву; laptop_job.sh --gpu и sandbox.sh start другой сессии отказывают до
# запуска чего-либо; задача без видеокарты аренды не берёт.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export INCOMING_GPU_LEASE=$tmp/lease INCOMING_GPU_LEASE_IDLE=600
unset INCOMING_GPU_OWNER
source "$ROOT/tools/gpu_lease.sh"
fail=0
ok() { echo "ok: $1"; }
bad() { echo "FAIL: $1"; fail=1; }

[ "$(lease_owner /x/mod/run/wt-FZC)" = FZC ] && [ "$(lease_owner /x/mod/run/wt-N3-main)" = N3 ] && [ "$(lease_owner /x/incoming)" = incoming ] \
  && ok "владелец — сессия по дереву (wt-N3-main → N3)" || bad "владелец по дереву"
[ "$(INCOMING_GPU_OWNER=SH lease_owner /x/mod/run/wt-FZC)" = SH ] && ok "владелец задан явно" || bad "INCOMING_GPU_OWNER"

lease_status >/dev/null && bad "пустая аренда занята" || ok "без аренды видеокарта свободна"
lease_take FZD /x/wt-FZD && ok "FZD взял" || bad "FZD не взял свободную"
since=$(cut -f3 "$INCOMING_GPU_LEASE")
msg=$(lease_take FZC /x/wt-FZC 2>&1); rc=$?
[ $rc = 75 ] && [[ $msg == *"видеокарта у FZD (/x/wt-FZD), с "* ]] && ok "другая сессия — отказ 75: $msg" || bad "отказ: $rc $msg"
sleep 1
lease_take FZD /x/wt-FZD-main && [ "$(cut -f3 "$INCOMING_GPU_LEASE")" = "$since" ] && ok "своя сессия снова — без ожидания, с того же времени" \
  || bad "своя сессия не взяла снова"
lease_renew FZC && bad "чужую продлили" || ok "чужую не продлить"
lease_release FZC && bad "чужую отпустили" || ok "чужую не отпустить"
[ "$(cut -f1 "$INCOMING_GPU_LEASE")" = FZD ] && ok "аренда осталась у FZD" || bad "аренду увели"

# брошенная: последняя работа давно — истекла
printf 'FZD\t/x/wt-FZD\t%s\t%s\n' $(( $(date +%s) - 4000 )) $(( $(date +%s) - 700 )) > "$INCOMING_GPU_LEASE"
lease_take FZC /x/wt-FZC && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = FZC ] && ok "аренда без работы дольше срока истекла — FZC взял" || bad "истёкшая не отдана"
# продлённая — не истекает
printf 'FZC\t/x/wt-FZC\t%s\t%s\n' $(( $(date +%s) - 4000 )) $(( $(date +%s) - 700 )) > "$INCOMING_GPU_LEASE"
lease_renew FZC && lease_take SH /x/wt-SH 2>/dev/null && bad "продлённую увели" || ok "идущая работа продлевает аренду"

# --wait: ждёт, пока отпустят
( lease_take SH /x/wt-SH --wait 2>/dev/null; echo done > "$tmp/sh" ) & w=$!
sleep 1
[ ! -f "$tmp/sh" ] && ok "с --wait ждёт" || bad "--wait не ждал"
lease_release FZC
for _ in $(seq 150); do [ -f "$tmp/sh" ] && break; sleep 0.1; done
[ -f "$tmp/sh" ] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = SH ] && ok "отпустили — ждущий взял" || bad "ждущий не взял"
wait $w 2>/dev/null

# laptop_job.sh --gpu и sandbox.sh start другой сессии — отказ до службы и до сборки
out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/laptop_job.sh" --gpu film -- false 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"видеокарта у SH"* ]] && ok "laptop_job --gpu другой сессии — отказ 75" || bad "laptop_job --gpu: $rc $out"
[ "$(cut -f1 "$INCOMING_GPU_LEASE")" = SH ] || { bad "аренда не у SH — дальше нельзя: start поднял бы клиент"; exit 1; }
if [ -n "$(systemctl --user list-units "incoming-job-sandbox-$(basename "$ROOT")-*" --state=active --no-legend --plain 2>/dev/null)" ]; then
  echo "пропуск: песочница этого дерева идёт (start ответил бы «уже идёт»)"
else
  out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/sandbox.sh" start 2>&1); rc=$?
  # на ноутбуке при игре Артёма раньше аренды отказывает она (тоже 75, аренду не берёт)
  [ $rc = 75 ] && [[ $out == *"видеокарта у SH"* || $out == *"игра Артёма запущена"* ]] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = SH ] \
    && ok "sandbox start другой сессии — отказ 75, аренда у SH" || bad "sandbox start: $rc $out"
fi
# задача без видеокарты аренды не берёт и не отказывает (служба systemd в CI не встаёт — смотрим только на аренду)
before=$(cat "$INCOMING_GPU_LEASE")
out=$(INCOMING_GPU_OWNER=FZC timeout 20 "$ROOT/tools/laptop_job.sh" gametest -- true 2>&1); rc=$?
[ $rc != 75 ] && [[ $out != *"видеокарта у"* ]] && [ "$(cat "$INCOMING_GPU_LEASE")" = "$before" ] \
  && ok "задача без видеокарты идёт рядом, аренда та же (код $rc)" || bad "задача без видеокарты: $rc $out"
# клиент на видеокарте (nested_kwin.sh): другая сессия — отказ до KWin; клиент уже идёт — отказ и своей сессии
out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/nested_kwin.sh" wayland-test 64 64 true 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"видеокарта у SH"* ]] && ok "клиент другой сессии — отказ 75 до KWin" || bad "nested_kwin другой сессии: $rc $out"
( source "$ROOT/tools/gpu_lease.sh"; INCOMING_GPU_RENEW=1 gpu_client_hold SH /x/wt-SH "клиент проверки"; exec sleep 4 ) & c=$!
sleep 0.5
out=$(INCOMING_GPU_OWNER=SH "$ROOT/tools/nested_kwin.sh" wayland-test 64 64 true 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"на видеокарте идёт клиент SH (/x/wt-SH)"* ]] && ok "второй клиент своей сессии — отказ: $out" || bad "второй клиент: $rc $out"
out=$(INCOMING_GPU_OWNER=SH "$ROOT/tools/laptop_job.sh" --gpu bake -- false 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"идёт клиент"* ]] && ok "задача --gpu при живом клиенте — отказ" || bad "задача --gpu при клиенте: $rc $out"
out=$(INCOMING_GPU_OWNER=SH "$ROOT/tools/gpu_lease.sh" release 2>&1); rc=$?
[ $rc = 75 ] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = SH ] && ok "release при своём клиенте — отказ" || bad "release при клиенте: $rc $out"
seen0=$(cut -f4 "$INCOMING_GPU_LEASE"); sleep 2.5
[ "$(cut -f4 "$INCOMING_GPU_LEASE")" -gt "$seen0" ] && ok "живой клиент продлевает аренду" || bad "клиент не продлевает"
wait $c; sleep 1.5
gpu_client_busy >/dev/null && bad "замок клиента остался после его конца" || ok "клиент кончился — замок свободен"
seen1=$(cut -f4 "$INCOMING_GPU_LEASE"); sleep 2
[ "$(cut -f4 "$INCOMING_GPU_LEASE")" = "$seen1" ] && ok "после клиента продление остановилось" || bad "продление живёт после клиента"
# клиенты одной работы (стенд, мультиплеер, клиент задачи --gpu): работа держит замок, её клиенты — потомки — не
# отказывают, и с унаследованным fd 199, и без него (Python close_fds); процесс не из неё — отказ
( source "$ROOT/tools/gpu_lease.sh"; gpu_client_hold SH /x/wt-SH "стенд" || exit 9
  bash -c 'source "$1/tools/gpu_lease.sh"; gpu_client_hold SH /x/wt-SH "клиент стенда"' _ "$ROOT" || exit 8
  bash -c 'exec 199>&-; source "$1/tools/gpu_lease.sh"; gpu_client_hold SH /x/wt-SH "клиент без fd"' _ "$ROOT" || exit 7
  sleep 5 ) & h=$!
for _ in $(seq 50); do grep -q стенд "$INCOMING_GPU_LEASE.client.who" 2>/dev/null && break; sleep 0.1; done
gpu_client_hold FZC /x/wt-FZC "чужой" 2>/dev/null && { bad "чужой процесс взял замок работы"; exec 199>&-; } || ok "процесс не из работы — отказ"
wait $h; rc=$?
[ $rc = 0 ] && ok "клиенты работы (с fd и без) не отказывают" || bad "клиенты работы (код $rc)"
artem_playing() { local p; for p in $(pgrep -x java); do grep -qa 'PrismLauncher/instances/' "/proc/$p/cmdline" 2>/dev/null && return 0; done; return 1; }
if artem_playing; then
  echo "пропуск: игра Артёма идёт — задача --gpu её ждёт (так и задумано)"
else
  # задача --gpu держит замок всю жизнь: её клиент (потомок, fd закрыт) — свой, снаружи — «идёт клиент задачи …»
  INCOMING_GPU_OWNER=SH "$ROOT/tools/laptop_job.sh" --in-unit gpu bake $$ -- \
    bash -c 'exec 199>&-; sleep 1; source "$1/tools/gpu_lease.sh"; gpu_client_hold SH /x/wt-SH "клиент задачи" && echo child-ok' _ "$ROOT" > "$tmp/job" 2>&1 & j=$!
  for _ in $(seq 50); do grep -q 'задача bake' "$INCOMING_GPU_LEASE.client.who" 2>/dev/null && break; sleep 0.1; done
  who=$(gpu_client_busy); [[ $who == *"задача bake"* ]] && ok "задача --gpu держит замок: $who" || bad "задача --gpu без замка: $who"
  wait $j; grep -q child-ok "$tmp/job" && ok "клиент внутри задачи --gpu — её частью" || bad "клиент задачи: $(cat "$tmp/job")"
  sleep 1.5; gpu_client_busy >/dev/null && bad "замок остался после задачи" || ok "задача кончилась — замок свободен"
fi
# отпустили, а работа идёт — продление берёт аренду снова
lease_release SH && lease_renew SH /x/wt-SH && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = SH ] && ok "продление берёт свободную аренду снова" || bad "продление свободной"
exit $fail
