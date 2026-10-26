#!/usr/bin/env bash
# GPU lease checks (CI, "Остановка проверок"): one session holds the GPU; another is refused with its owner and
# start time, or waits with --wait; the owner reacquires without waiting; abandoned leases expire; another session cannot
# renew or release it; owner comes from the tree; laptop_job.sh --gpu and sandbox.sh start from another session refuse before
# starting anything; jobs without GPU take no lease; a client without a lock (pre-lease script) also means a busy
# GPU, not a free one; waiters form a fair FIFO queue; Artem's game is detected by its instance directory, not its
# command-line path.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export INCOMING_GPU_LEASE=$tmp/lease INCOMING_GPU_LEASE_IDLE=600
# The test "nested KWin" is a stub program: real laptop clients do not affect it
export INCOMING_GPU_KWIN=$tmp/kwin_wayland
printf '#!/bin/bash\ntrap "kill \\$c; exit" TERM\nsleep 60 & c=$!\nwait\n' > "$INCOMING_GPU_KWIN"; chmod +x "$INCOMING_GPU_KWIN"
unset INCOMING_GPU_OWNER
source "$ROOT/tools/gpu_lease.sh"
fail=0
ok() { echo "ok: $1"; }
bad() { echo "FAIL: $1"; fail=1; }

[ "$(lease_owner /x/mod/run/wt-FZC)" = FZC ] && [ "$(lease_owner /x/mod/run/wt-N3-main)" = N3 ] && [ "$(lease_owner /x/incoming)" = incoming ] \
  && ok "owner is the session from its tree (wt-N3-main → N3)" || bad "owner from tree"
[ "$(INCOMING_GPU_OWNER=SH lease_owner /x/mod/run/wt-FZC)" = SH ] && ok "explicit owner" || bad "INCOMING_GPU_OWNER"

lease_status >/dev/null && bad "empty lease is busy" || ok "GPU free without a lease"
lease_take FZD /x/wt-FZD && ok "FZD acquired" || bad "FZD did not acquire a free GPU"
since=$(cut -f3 "$INCOMING_GPU_LEASE")
msg=$(lease_take FZC /x/wt-FZC 2>&1); rc=$?
[ $rc = 75 ] && [[ $msg == *"GPU held by FZD (/x/wt-FZD), since "* ]] && ok "another session refused with 75: $msg" || bad "refusal: $rc $msg"
sleep 1
lease_take FZD /x/wt-FZD-main && [ "$(cut -f3 "$INCOMING_GPU_LEASE")" = "$since" ] && ok "owner reacquires immediately, preserving the start time" \
  || bad "owner failed to reacquire"
lease_renew FZC && bad "renewed another session lease" || ok "cannot renew another session lease"
lease_release FZC && bad "released another session lease" || ok "cannot release another session lease"
[ "$(cut -f1 "$INCOMING_GPU_LEASE")" = FZD ] && ok "lease still belongs to FZD" || bad "lease was taken away"

# Abandoned: last work was long ago, lease expired
printf 'FZD\t/x/wt-FZD\t%s\t%s\n' $(( $(date +%s) - 4000 )) $(( $(date +%s) - 700 )) > "$INCOMING_GPU_LEASE"
lease_take FZC /x/wt-FZC && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = FZC ] && ok "idle lease expired, FZC acquired" || bad "expired lease not handed over"
# A renewed lease does not expire
printf 'FZC\t/x/wt-FZC\t%s\t%s\n' $(( $(date +%s) - 4000 )) $(( $(date +%s) - 700 )) > "$INCOMING_GPU_LEASE"
lease_renew FZC && lease_take SH /x/wt-SH 2>/dev/null && bad "renewed lease taken away" || ok "running work renews the lease"

# --wait waits for release
( lease_take SH /x/wt-SH --wait 2>/dev/null; echo done > "$tmp/sh" ) & w=$!
sleep 1
[ ! -f "$tmp/sh" ] && ok "--wait waits" || bad "--wait did not wait"
lease_release FZC
for _ in $(seq 150); do [ -f "$tmp/sh" ] && break; sleep 0.1; done
[ -f "$tmp/sh" ] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = SH ] && ok "released, waiter acquired" || bad "waiter did not acquire"
wait $w 2>/dev/null

# laptop_job.sh --gpu and sandbox.sh start from another session refuse before service startup and building
out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/laptop_job.sh" --gpu film -- false 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"GPU held by SH"* ]] && ok "laptop_job --gpu from another session refused with 75" || bad "laptop_job --gpu: $rc $out"
[ "$(cut -f1 "$INCOMING_GPU_LEASE")" = SH ] || { bad "lease is not held by SH; cannot continue: start would launch a client"; exit 1; }
if [ -n "$(systemctl --user list-units "incoming-job-sandbox-$(basename "$ROOT")-*" --state=active --no-legend --plain 2>/dev/null)" ]; then
  echo "skip: sandbox in this tree is running (start would say \"already running\")"
else
  out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/sandbox.sh" start 2>&1); rc=$?
  # On the laptop, Artem's game may refuse first (also 75, no lease acquired)
  [ $rc = 75 ] && [[ $out == *"GPU held by SH"* || $out == *"Artem's game is running"* ]] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = SH ] \
    && ok "sandbox start from another session refused with 75, lease held by SH" || bad "sandbox start: $rc $out"
fi
# A job without GPU neither acquires a lease nor refuses (systemd cannot start in CI; inspect only the lease)
before=$(cat "$INCOMING_GPU_LEASE")
out=$(INCOMING_GPU_OWNER=FZC timeout 20 "$ROOT/tools/laptop_job.sh" gametest -- true 2>&1); rc=$?
[ $rc != 75 ] && [[ $out != *"GPU held by"* ]] && [ "$(cat "$INCOMING_GPU_LEASE")" = "$before" ] \
  && ok "job without GPU runs alongside, lease unchanged (code $rc)" || bad "job without GPU: $rc $out"
# GPU client (nested_kwin.sh): another session refuses before KWin; an existing client refuses even its owner session
out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/nested_kwin.sh" wayland-test 64 64 true 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"GPU held by SH"* ]] && ok "another session client refused with 75 before KWin" || bad "nested_kwin from another session: $rc $out"
( source "$ROOT/tools/gpu_lease.sh"; INCOMING_GPU_RENEW=1 gpu_client_hold SH /x/wt-SH "test client"; exec sleep 4 ) & c=$!
sleep 0.5
out=$(INCOMING_GPU_OWNER=SH "$ROOT/tools/nested_kwin.sh" wayland-test 64 64 true 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"GPU client is running: SH (/x/wt-SH)"* ]] && ok "second client in owner session refused: $out" || bad "second client: $rc $out"
out=$(INCOMING_GPU_OWNER=SH "$ROOT/tools/laptop_job.sh" --gpu bake -- false 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"client is running"* ]] && ok "--gpu job refused while a client is alive" || bad "--gpu job with a client: $rc $out"
out=$(INCOMING_GPU_OWNER=SH "$ROOT/tools/gpu_lease.sh" release 2>&1); rc=$?
[ $rc = 75 ] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = SH ] && ok "release refused with own client running" || bad "release with a client: $rc $out"
seen0=$(cut -f4 "$INCOMING_GPU_LEASE"); sleep 2.5
[ "$(cut -f4 "$INCOMING_GPU_LEASE")" -gt "$seen0" ] && ok "live client renews the lease" || bad "client does not renew"
wait $c; sleep 1.5
gpu_client_busy >/dev/null && bad "client lock remains after exit" || ok "client exited, lock is free"
seen1=$(cut -f4 "$INCOMING_GPU_LEASE"); sleep 2
[ "$(cut -f4 "$INCOMING_GPU_LEASE")" = "$seen1" ] && ok "renewal stopped after client exit" || bad "renewal lives after client exit"
# Clients within one job (scene bench, multiplayer, --gpu job client): the job holds the lock; descendant clients do not
# refuse, with inherited fd 199 or without it (Python close_fds); unrelated processes refuse
( source "$ROOT/tools/gpu_lease.sh"; gpu_client_hold SH /x/wt-SH "bench" || exit 9
  bash -c 'source "$1/tools/gpu_lease.sh"; gpu_client_hold SH /x/wt-SH "bench client"' _ "$ROOT" || exit 8
  bash -c 'exec 199>&-; source "$1/tools/gpu_lease.sh"; gpu_client_hold SH /x/wt-SH "client without fd"' _ "$ROOT" || exit 7
  sleep 5 ) & h=$!
for _ in $(seq 50); do grep -q bench "$INCOMING_GPU_LEASE.client.who" 2>/dev/null && break; sleep 0.1; done
gpu_client_hold FZC /x/wt-FZC "foreign" 2>/dev/null && { bad "foreign process acquired job lock"; exec 199>&-; } || ok "process outside the job refused"
wait $h; rc=$?
[ $rc = 0 ] && ok "job clients (with and without fd) do not refuse" || bad "job clients (code $rc)"
if artem_playing; then
  echo "skip: Artem's game is running; --gpu job waits for it (intended)"
else
  # A --gpu job holds the lock throughout: its descendant client with closed fd is ours; outside reports "job client is running …"
  INCOMING_GPU_OWNER=SH "$ROOT/tools/laptop_job.sh" --in-unit gpu bake $$ -- \
    bash -c 'exec 199>&-; sleep 1; source "$1/tools/gpu_lease.sh"; gpu_client_hold SH /x/wt-SH "job client" && echo child-ok' _ "$ROOT" > "$tmp/job" 2>&1 & j=$!
  for _ in $(seq 50); do grep -q 'job bake' "$INCOMING_GPU_LEASE.client.who" 2>/dev/null && break; sleep 0.1; done
  who=$(gpu_client_busy); [[ $who == *"job bake"* ]] && ok "--gpu job holds the lock: $who" || bad "--gpu job has no lock: $who"
  wait $j; grep -q child-ok "$tmp/job" && ok "client inside --gpu job belongs to it" || bad "job client: $(cat "$tmp/job")"
  sleep 1.5; gpu_client_busy >/dev/null && bad "lock remains after job" || ok "job exited, lock free"
fi
# Released while work continues: renewal reacquires the lease
lease_release SH && lease_renew SH /x/wt-SH && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = SH ] && ok "renewal reacquires free lease" || bad "renewal of free lease"

# Client without lock or lease (sandbox started by a pre-lease script; 23.10 ZM sandbox ran while the lease reported
# "free", and the FX client displaced it): a second session refuses, rather than reporting "free"
lease_release SH
"$INCOMING_GPU_KWIN" --virtual --xwayland --socket wayland-incoming-sandbox --exit-with-session "bash /x/wt-ZM/sandbox.sh" & k=$!
sleep 0.3
out=$(lease_status); rc=$?
[ $rc = 0 ] && [[ $out == *"client is running without a lease"*"wt-ZM"* ]] && ok "no lease but client running — status: $out" || bad "status with unlocked client: $rc $out"
out=$(lease_take FZC /x/wt-FZC 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"client is running without a lease"* ]] && [ ! -s "$INCOMING_GPU_LEASE" ] && ok "another session lease_take refused with 75, no lease acquired" || bad "lease_take with unlocked client: $rc $out"
out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/nested_kwin.sh" wayland-test 64 64 true 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"client is running without a lease"* ]] && ok "another session client refused before KWin" || bad "nested_kwin with unlocked client: $rc $out"
out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/laptop_job.sh" --gpu film -- false 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"client is running without a lease"* ]] && ok "laptop_job --gpu refused" || bad "laptop_job --gpu with unlocked client: $rc $out"
if [ -z "$(systemctl --user list-units "incoming-job-sandbox-$(basename "$ROOT")-*" --state=active --no-legend --plain 2>/dev/null)" ]; then
  out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/sandbox.sh" start 2>&1); rc=$?
  [ $rc = 75 ] && [[ $out == *"client is running without a lease"* || $out == *"Artem's game is running"* ]] && [ ! -s "$INCOMING_GPU_LEASE" ] \
    && ok "sandbox start refused with 75" || bad "sandbox start with unlocked client: $rc $out"
fi
out=$(lease_take FZC /x/wt-FZC --wait 2>&1 & w=$!; sleep 1; kill -0 $w && echo waiting; kill $k; wait $w; echo "rc=$?")
[[ $out == *waiting*"rc=0"* ]] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = FZC ] && ok "--wait waits for unlocked client, then acquires after exit" || bad "--wait with unlocked client: $out"
lease_release FZC
# Clients of our job (bench: job holds lock, its KWin processes are descendants) are ours, without a separate lock each
( source "$ROOT/tools/gpu_lease.sh"; gpu_client_hold SH /x/wt-SH "bench" || exit 9
  "$INCOMING_GPU_KWIN" --virtual & c=$!
  sleep 0.3
  bash -c 'source "$1/tools/gpu_lease.sh"; ! gpu_client_busy' _ "$ROOT"; rc=$?
  kill $c; exit $rc ) ; rc=$?
[ $rc = 0 ] && ok "KWin of our job is not a foreign client" || bad "KWin of our job (code $rc)"

# Waiter queue (FIFO): the first arrival acquires on release, not the first to check; the owner is not displaced;
# without --wait, a queued free GPU cannot be acquired; interrupted waiters leave the queue themselves. 23.10: start --wait waited ~35 min
# while three sessions that checked earlier took the GPU in turn
export INCOMING_GPU_POLL=0.2
rm -f "$INCOMING_GPU_LEASE" "$INCOMING_GPU_LEASE.queue"
lease_take V3 /x/wt-V3
# The waiter predates its queue entry (sleep before lease_take): identify it by its PID and start time, not the subshell;
# otherwise a live waiter was treated as exited and the queue was empty
( sleep 0.3; lease_take L1 /x/wt-L1 --wait 2>"$tmp/q-l1"; echo L1 >> "$tmp/order" ) & q1=$!
sleep 0.3
sleep 0.5
( lease_take WV /x/wt-WV --wait 2>"$tmp/q-wv"; echo WV >> "$tmp/order" ) & q2=$!
sleep 0.5
out=$(lease_status)
[[ $out == "GPU held by V3 "*"queued: L1 (/x/wt-L1) since "*", WV (/x/wt-WV) since "* ]] && ok "status shows owner and waiters in order: $out" || bad "queue status: $out"
grep -q 'queue position 2' "$tmp/q-wv" && ok "waiter knows its position: $(head -n 1 "$tmp/q-wv")" || bad "queue position: $(cat "$tmp/q-wv")"
lease_take V3 /x/wt-V3 && lease_renew V3 && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = V3 ] && ok "owner renews and reacquires with waiters queued, without being displaced" || bad "queue displaced the owner"
out=$(lease_take N1 /x/wt-N1 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"GPU held by V3"*"queued: L1"* ]] && ok "without --wait, queued GPU is refused with queue details" || bad "without --wait: $rc $out"
lease_release V3
for _ in $(seq 50); do [ -s "$tmp/order" ] && break; sleep 0.1; done
[ "$(cut -f1 "$INCOMING_GPU_LEASE")" = L1 ] && [ "$(cat "$tmp/order")" = L1 ] && ok "released, first waiter (L1) acquired, second waits" || bad "L1 did not acquire first: $(cat "$tmp/order") / $(cut -f1 "$INCOMING_GPU_LEASE")"
# While L1 holds and WV waits, a lease released in between cannot be taken outside the queue
lease_release L1
out=$(lease_take N1 /x/wt-N1 2>&1); rc=$?
for _ in $(seq 50); do grep -q WV "$tmp/order" && break; sleep 0.1; done
# (WV checks every 0,2 s and may acquire before N1; N1 then sees it held by WV; the requirement is that N1 did not acquire)
[ $rc = 75 ] && [[ $out == *"free, but queued: WV"* || $out == *"GPU held by WV"* ]] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = WV ] \
  && ok "free queued GPU cannot be acquired without --wait; waiter WV acquired" || bad "queue bypass: $rc $out / $(cut -f1 "$INCOMING_GPU_LEASE")"
# Stop waiters that never acquired (queue error), rather than waiting forever
kill $q1 $q2 2>/dev/null; wait $q1 $q2 2>/dev/null
[ ! -s "$INCOMING_GPU_LEASE.queue" ] && ok "owners left the queue" || bad "queue after acquisition: $(cat "$INCOMING_GPU_LEASE.queue")"
# An interrupted waiter (Ctrl+C, timeout) leaves the queue itself; the next does not wait for it
( lease_take SM /x/wt-SM --wait 2>/dev/null ) & q3=$!
sleep 0.5
( lease_take TR /x/wt-TR --wait 2>/dev/null; echo TR > "$tmp/tr" ) & q4=$!
sleep 0.5
kill $q3; wait $q3 2>/dev/null
pkill -P $q3 2>/dev/null
lease_release WV
for _ in $(seq 50); do [ -f "$tmp/tr" ] && break; sleep 0.1; done
[ "$(cut -f1 "$INCOMING_GPU_LEASE")" = TR ] && ok "interrupted waiter does not hold the queue; next waiter acquired" || bad "interrupted waiter holds queue: $(cut -f1 "$INCOMING_GPU_LEASE") $(cat "$INCOMING_GPU_LEASE.queue")"
kill $q4 2>/dev/null; wait $q4 2>/dev/null
lease_release TR
unset INCOMING_GPU_POLL

# Artem's game is java in its instance directory; sandbox java with instance paths in its command line is not that game
mkdir -p "$tmp/prism/instances/Open Skies/minecraft" "$tmp/bin" "$tmp/sandbox"
printf '#!/bin/bash\nsleep 5\n' > "$tmp/bin/java"; chmod +x "$tmp/bin/java"
real=0; artem_playing && real=1
( cd "$tmp/sandbox" && exec "$tmp/bin/java" -Dfml.modFolders="$tmp/prism/instances/Open Skies/minecraft/mods" ) & j=$!
sleep 0.3
if [ $real = 1 ]; then echo "skip: Artem's game is running"
else (PRISM=$tmp/prism; artem_playing) && bad "sandbox with instance paths mistaken for Artem's game" || ok "sandbox with instance paths is not Artem's game"; fi
kill $j 2>/dev/null; wait $j 2>/dev/null
( cd "$tmp/prism/instances/Open Skies/minecraft" && exec "$tmp/bin/java" -cp x ) & j=$!
sleep 0.3
(PRISM=$tmp/prism; artem_playing) && ok "java in instance directory is Artem's game" || bad "java in instance directory not detected"
kill $j 2>/dev/null; wait $j 2>/dev/null
exit $fail
