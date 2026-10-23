#!/usr/bin/env bash
# Проверка аренды видеокарты (CI, «Остановка проверок»): видеокарта у одной сессии; другая — отказ со строкой, кто и с
# какого времени, а с --wait — ждёт; своя сессия берёт снова без ожидания; брошенная аренда истекает; чужую не
# продлить и не отпустить; владелец — по дереву; laptop_job.sh --gpu и sandbox.sh start другой сессии отказывают до
# запуска чего-либо; задача без видеокарты аренды не берёт; клиент без замка (скрипт до аренды) — тоже занятая
# видеокарта, а не «свободна»; ждущие — честная очередь (FIFO); игра Артёма — по папке его инстанса, не по пути в
# командной строке.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export INCOMING_GPU_LEASE=$tmp/lease INCOMING_GPU_LEASE_IDLE=600
# «вложенный KWin» проверки — своя программа: настоящие клиенты на ноутбуке ей не мешают
export INCOMING_GPU_KWIN=$tmp/kwin_wayland
printf '#!/bin/bash\ntrap "kill \\$c; exit" TERM\nsleep 60 & c=$!\nwait\n' > "$INCOMING_GPU_KWIN"; chmod +x "$INCOMING_GPU_KWIN"
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

# клиент без замка и аренды (песочница, поднятая скриптом до аренды; 23.10 так шла песочница ZM, аренда отвечала
# «свободна», и клиент FX её выбил): вторая сессия — отказ, а не «свободно»
lease_release SH
"$INCOMING_GPU_KWIN" --virtual --xwayland --socket wayland-incoming-sandbox --exit-with-session "bash /x/wt-ZM/sandbox.sh" & k=$!
sleep 0.3
out=$(lease_status); rc=$?
[ $rc = 0 ] && [[ $out == *"клиент без аренды"*"wt-ZM"* ]] && ok "аренды нет, клиент идёт — status: $out" || bad "status при клиенте без замка: $rc $out"
out=$(lease_take FZC /x/wt-FZC 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"клиент без аренды"* ]] && [ ! -s "$INCOMING_GPU_LEASE" ] && ok "lease_take другой сессии — отказ 75, аренда не взята" || bad "lease_take при клиенте без замка: $rc $out"
out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/nested_kwin.sh" wayland-test 64 64 true 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"клиент без аренды"* ]] && ok "клиент другой сессии — отказ до KWin" || bad "nested_kwin при клиенте без замка: $rc $out"
out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/laptop_job.sh" --gpu film -- false 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"клиент без аренды"* ]] && ok "laptop_job --gpu — отказ" || bad "laptop_job --gpu при клиенте без замка: $rc $out"
if [ -z "$(systemctl --user list-units "incoming-job-sandbox-$(basename "$ROOT")-*" --state=active --no-legend --plain 2>/dev/null)" ]; then
  out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/sandbox.sh" start 2>&1); rc=$?
  [ $rc = 75 ] && [[ $out == *"клиент без аренды"* || $out == *"игра Артёма запущена"* ]] && [ ! -s "$INCOMING_GPU_LEASE" ] \
    && ok "sandbox start — отказ 75" || bad "sandbox start при клиенте без замка: $rc $out"
fi
out=$(lease_take FZC /x/wt-FZC --wait 2>&1 & w=$!; sleep 1; kill -0 $w && echo waiting; kill $k; wait $w; echo "rc=$?")
[[ $out == *waiting*"rc=0"* ]] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = FZC ] && ok "с --wait ждёт клиента без замка, он кончился — взял" || bad "--wait при клиенте без замка: $out"
lease_release FZC
# клиенты своей работы (стенд: замок у работы, её KWin — потомки) — свои, и без замка у каждого
( source "$ROOT/tools/gpu_lease.sh"; gpu_client_hold SH /x/wt-SH "стенд" || exit 9
  "$INCOMING_GPU_KWIN" --virtual & c=$!
  sleep 0.3
  bash -c 'source "$1/tools/gpu_lease.sh"; ! gpu_client_busy' _ "$ROOT"; rc=$?
  kill $c; exit $rc ) ; rc=$?
[ $rc = 0 ] && ok "KWin своей работы — не чужой клиент" || bad "KWin своей работы (код $rc)"

# очередь ждущих (FIFO): освободилась — берёт первый пришедший, а не тот, кто первым проверил; держащего не выбивает;
# без --wait свободную при очереди не взять; прерванный ждущий уходит из очереди сам. 23.10 start --wait ждал ~35 мин,
# пока видеокарту по очереди брали три сессии, проверявшие раньше него
export INCOMING_GPU_POLL=0.2
rm -f "$INCOMING_GPU_LEASE" "$INCOMING_GPU_LEASE.queue"
lease_take V3 /x/wt-V3
( lease_take L1 /x/wt-L1 --wait 2>"$tmp/q-l1"; echo L1 >> "$tmp/order" ) & q1=$!
sleep 0.5
( lease_take WV /x/wt-WV --wait 2>"$tmp/q-wv"; echo WV >> "$tmp/order" ) & q2=$!
sleep 0.5
out=$(lease_status)
[[ $out == "видеокарта у V3 "*"её ждут: L1 (/x/wt-L1) с "*", WV (/x/wt-WV) с "* ]] && ok "status — кто держит и кто ждёт по порядку: $out" || bad "status очереди: $out"
grep -q 'в очереди 2-й' "$tmp/q-wv" && ok "ждущий знает своё место: $(head -n 1 "$tmp/q-wv")" || bad "место в очереди: $(cat "$tmp/q-wv")"
lease_take V3 /x/wt-V3 && lease_renew V3 && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = V3 ] && ok "держащий при очереди продлевает и берёт снова — не выбит" || bad "держащего выбила очередь"
out=$(lease_take N1 /x/wt-N1 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"видеокарта у V3"*"её ждут: L1"* ]] && ok "без --wait при очереди — отказ с очередью" || bad "без --wait: $rc $out"
lease_release V3
for _ in $(seq 50); do [ -s "$tmp/order" ] && break; sleep 0.1; done
[ "$(cut -f1 "$INCOMING_GPU_LEASE")" = L1 ] && [ "$(cat "$tmp/order")" = L1 ] && ok "отпустили — взял первый в очереди (L1), второй ждёт" || bad "первым взял не L1: $(cat "$tmp/order") / $(cut -f1 "$INCOMING_GPU_LEASE")"
# пока L1 держит, а WV ждёт, отпущенную в промежутке аренду не перехватить без очереди
lease_release L1
out=$(lease_take N1 /x/wt-N1 2>&1); rc=$?
for _ in $(seq 50); do grep -q WV "$tmp/order" && break; sleep 0.1; done
[ $rc = 75 ] && [[ $out == *"свободна, но её ждут: WV"* ]] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = WV ] \
  && ok "свободную при очереди без --wait не взять, взял ждущий WV" || bad "перехват мимо очереди: $rc $out / $(cut -f1 "$INCOMING_GPU_LEASE")"
wait $q1 $q2
[ ! -s "$INCOMING_GPU_LEASE.queue" ] && ok "взявшие ушли из очереди" || bad "очередь после взятия: $(cat "$INCOMING_GPU_LEASE.queue")"
# прерванный ждущий (Ctrl+C, срок) уходит из очереди сам: следующий не ждёт его
( lease_take SM /x/wt-SM --wait 2>/dev/null ) & q3=$!
sleep 0.5
( lease_take TR /x/wt-TR --wait 2>/dev/null; echo TR > "$tmp/tr" ) & q4=$!
sleep 0.5
kill $q3; wait $q3 2>/dev/null
pkill -P $q3 2>/dev/null
lease_release WV
for _ in $(seq 50); do [ -f "$tmp/tr" ] && break; sleep 0.1; done
[ "$(cut -f1 "$INCOMING_GPU_LEASE")" = TR ] && ok "прерванный ждущий не держит очередь — взял следующий" || bad "прерванный держит очередь: $(cut -f1 "$INCOMING_GPU_LEASE") $(cat "$INCOMING_GPU_LEASE.queue")"
wait $q4
lease_release TR
unset INCOMING_GPU_POLL

# игра Артёма — java в папке его инстанса; java песочницы с путями инстанса в командной строке — не она
mkdir -p "$tmp/prism/instances/Open Skies/minecraft" "$tmp/bin" "$tmp/sandbox"
printf '#!/bin/bash\nsleep 5\n' > "$tmp/bin/java"; chmod +x "$tmp/bin/java"
real=0; artem_playing && real=1
( cd "$tmp/sandbox" && exec "$tmp/bin/java" -Dfml.modFolders="$tmp/prism/instances/Open Skies/minecraft/mods" ) & j=$!
sleep 0.3
if [ $real = 1 ]; then echo "пропуск: игра Артёма идёт"
else (PRISM=$tmp/prism; artem_playing) && bad "песочница с путями инстанса — «игра Артёма»" || ok "песочница с путями инстанса — не игра Артёма"; fi
kill $j 2>/dev/null; wait $j 2>/dev/null
( cd "$tmp/prism/instances/Open Skies/minecraft" && exec "$tmp/bin/java" -cp x ) & j=$!
sleep 0.3
(PRISM=$tmp/prism; artem_playing) && ok "java в папке инстанса — игра Артёма" || bad "java в папке инстанса не видна"
kill $j 2>/dev/null; wait $j 2>/dev/null
exit $fail
