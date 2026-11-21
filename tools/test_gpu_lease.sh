#!/usr/bin/env bash
# GPU lease checks (CI, "Остановка проверок"): one session holds the GPU; another is refused with its owner and
# start time, or waits with --wait; the owner reacquires without waiting; abandoned leases expire; another session cannot
# renew or release it; owner comes from the tree; laptop_job.sh --gpu and sandbox.sh start from another session refuse before
# starting anything; jobs without GPU take no lease; a client without a lock (pre-lease script) also means a busy
# GPU, not a free one; a CPU session (soft socket) is not; waiters form a fair FIFO queue capped at two other sessions; Artem's game is detected by its instance directory, not its
# command-line path, and stops a --gpu job it starts during; a delegated task owns the lease across its trees; a running
# client's GPU samples and a job's CPU go to the load log; vanilla clients share GPU slots without waiting, fall back
# to the CPU, and never share the GPU with a measurement; every laptop job has a memory ceiling and says when it hits it.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export INCOMING_GPU_LEASE=$tmp/lease INCOMING_GPU_LEASE_IDLE=600
# The test "nested KWin" is a stub program: real laptop clients do not affect it. `sandbox.sh start gpu` below must be
# refused; JAVA_HOME=/nonexistent keeps a regression from building and launching a real client
export INCOMING_GPU_KWIN=$tmp/kwin_wayland
printf '#!/bin/bash\ntrap "kill \\$c; exit" TERM\nsleep 60 & c=$!\nwait\n' > "$INCOMING_GPU_KWIN"; chmod +x "$INCOMING_GPU_KWIN"
unset INCOMING_GPU_OWNER DELEGATE_TASK
export DELEGATE_LOAD_LOG=$tmp/load.tsv
source "$ROOT/tools/gpu_lease.sh"
fail=0
ok() { echo "ok: $1"; }
bad() { echo "FAIL: $1"; fail=1; }

[ "$(lease_owner /x/mod/run/wt-FZC)" = FZC ] && [ "$(lease_owner /x/mod/run/wt-N3-main)" = N3 ] && [ "$(lease_owner /x/incoming)" = incoming ] \
  && ok "owner is the session from its tree (wt-N3-main → N3)" || bad "owner from tree"
[ "$(INCOMING_GPU_OWNER=SH lease_owner /x/mod/run/wt-FZC)" = SH ] && ok "explicit owner" || bad "INCOMING_GPU_OWNER"
[ "$(DELEGATE_TASK=wave lease_owner /x/mod/run/wt-FZC)" = wave ] && [ "$(DELEGATE_TASK=wave lease_owner /x/incoming-wt-headmain)" = wave ] \
  && [ "$(DELEGATE_TASK=wave INCOMING_GPU_OWNER=SH lease_owner /x/mod/run/wt-FZC)" = SH ] \
  && ok "a delegated task owns the lease across its trees; an explicit owner still wins" || bad "DELEGATE_TASK owner"

# a stub nvidia-smi reports 97 % utilization for the load-log samples below
mkdir -p "$tmp/smi"
printf '#!/bin/bash\necho 97\n' > "$tmp/smi/nvidia-smi"; chmod +x "$tmp/smi/nvidia-smi"

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
  out=$(JAVA_HOME=/nonexistent INCOMING_GPU_OWNER=FZC "$ROOT/tools/sandbox.sh" start gpu 2>&1); rc=$?
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
  out=$(JAVA_HOME=/nonexistent INCOMING_GPU_OWNER=FZC "$ROOT/tools/sandbox.sh" start gpu 2>&1); rc=$?
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

# The queue is capped: with two other sessions waiting, a third is refused at once and pointed to the CPU path
rm -f "$INCOMING_GPU_LEASE" "$INCOMING_GPU_LEASE.queue"
lease_take V3 /x/wt-V3
( lease_take L1 /x/wt-L1 --wait 2>/dev/null ) & q1=$!
( lease_take WV /x/wt-WV --wait 2>/dev/null ) & q2=$!
sleep 0.5
out=$(timeout 5 bash -c "source '$ROOT/tools/gpu_lease.sh'; lease_take SM /x/wt-SM --wait" 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"GPU queue is full (2 sessions waiting"*"without shaders/dh/gpu"* ]] && ok "third waiter refused: queue full" || bad "queue cap: $rc $out"
out=$(INCOMING_GPU_QUEUE_MAX=3 timeout 1 bash -c "source '$ROOT/tools/gpu_lease.sh'; lease_take SM /x/wt-SM --wait" 2>&1); rc=$?
[ $rc = 124 ] && ok "a higher cap queues the third waiter" || bad "INCOMING_GPU_QUEUE_MAX=3: $rc $out"
kill $q1 $q2 2>/dev/null; wait $q1 $q2 2>/dev/null
lease_release V3; rm -f "$INCOMING_GPU_LEASE.queue"

# A CPU session (nested KWin on a wayland-incoming-soft-… socket, tools/soft_gl.sh) is no GPU client: the lease stays free
"$INCOMING_GPU_KWIN" --virtual --xwayland --socket wayland-incoming-soft-sandbox-x --exit-with-session "bash /x/sandbox.sh" & k=$!
sleep 0.3
gpu_client_busy >/dev/null && bad "CPU session counted as a GPU client: $(gpu_client_busy)" || ok "CPU session leaves the GPU free"
lease_take FZC /x/wt-FZC && ok "lease taken beside a CPU session" || bad "CPU session blocks the lease"
lease_release FZC
kill $k; wait $k 2>/dev/null
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

# Artem's game started while a --gpu job runs (19.11: his replay overlapped a measured GPU run unnoticed): the job's
# whole process tree stops within seconds, the output says so, the job ends with 75
if [ $real = 1 ]; then echo "skip: Artem's game is running"
else
  rm -f "$INCOMING_GPU_LEASE"
  start=$(date +%s)
  PRISM_DIR=$tmp/prism INCOMING_GPU_OWNER=SH INCOMING_GAME_POLL=0.2 "$ROOT/tools/laptop_job.sh" --in-unit gpu run $$ -- \
    bash -c 'sleep 60 & echo $! > "$1/grandchild"; wait' _ "$tmp" > "$tmp/run" 2>&1 & r=$!
  sleep 1
  ( cd "$tmp/prism/instances/Open Skies/minecraft" && exec "$tmp/bin/java" -cp x ) & j=$!
  wait $r; rc=$?
  took=$(( $(date +%s) - start ))
  [ $rc = 75 ] && grep -q "run: Artem's game started at [0-9:]*: job stopped" "$tmp/run" && [ $took -lt 5 ] \
    && ok "game started mid-job stops the job in ${took} s: $(head -n 1 "$tmp/run")" || bad "game mid-job: code $rc after $took s: $(cat "$tmp/run")"
  kill -0 "$(cat "$tmp/grandchild")" 2>/dev/null && bad "job's child survived the stop" || ok "job's child stopped with it"
  kill $j 2>/dev/null; wait $j 2>/dev/null
  # a job that ignores TERM (its children inherit the ignore) is killed 10 s later, still with 75
  start=$(date +%s)
  PRISM_DIR=$tmp/prism INCOMING_GPU_OWNER=SH INCOMING_GAME_POLL=0.2 "$ROOT/tools/laptop_job.sh" --in-unit gpu deaf $$ -- \
    bash -c 'trap "" TERM; sleep 60 & echo $! > "$1/deaf"; wait' _ "$tmp" > "$tmp/deaf.out" 2>&1 & r=$!
  sleep 1
  ( cd "$tmp/prism/instances/Open Skies/minecraft" && exec "$tmp/bin/java" -cp x ) & j=$!
  wait $r; rc=$?
  took=$(( $(date +%s) - start ))
  [ $rc = 75 ] && [ $took -ge 9 ] && [ $took -lt 16 ] && ! kill -0 "$(cat "$tmp/deaf")" 2>/dev/null \
    && ok "job ignoring TERM is killed after the grace in ${took} s" || bad "TERM-deaf job: code $rc after $took s: $(cat "$tmp/deaf.out")"
  kill $j 2>/dev/null; wait $j 2>/dev/null
  out=$(PRISM_DIR=$tmp/prism INCOMING_GPU_OWNER=SH "$ROOT/tools/laptop_job.sh" --in-unit gpu quick $$ -- bash -c 'exit 3' 2>&1); rc=$?
  [ $rc = 3 ] && [[ $out != *"Artem's game"* ]] && ok "job without his game keeps its own exit code" || bad "plain job: code $rc $out"
fi

# Shared GPU slots (vanilla clients): taken without waiting while the GPU is free of exclusive work, at most
# INCOMING_GPU_SLOTS; a measurement (lease, its queue, an exclusive client) keeps them off and sees them as busy; every
# refusal is a CPU fallback (vanilla_display), never a wait
if [ $real = 1 ]; then echo "skip: Artem's game is running (shared slots refuse during it)"
else
  rm -f "$INCOMING_GPU_LEASE" "$INCOMING_GPU_LEASE.queue"
  mkdir -p "$tmp/vram"
  ln -sf "$INCOMING_GPU_KWIN" "$tmp/vram/kwin_wayland"
  printf '#!/bin/bash\nif [[ "$*" == *memory.free* ]]; then echo "${STUB_FREE:-6000}"; else echo 97; fi\n' > "$tmp/vram/nvidia-smi"; chmod +x "$tmp/vram/nvidia-smi"
  export PATH="$tmp/vram:$PATH" PRISM_DIR=$tmp/prism INCOMING_GPU_SLOTS=2
  GPU_SLOTS=2
  slot() { bash -c 'source "$1/tools/gpu_lease.sh"; gpu_slot_take "$2" 2>"$3.err" || exit 9; echo "$GPU_SLOT" > "$3"; sleep "$4"' _ "$ROOT" "$1" "$tmp/$1" "${2:-3}"; }
  slot va & a=$!; slot vb & b=$!
  for _ in $(seq 50); do [ -s "$tmp/va" ] && [ -s "$tmp/vb" ] && break; sleep 0.1; done
  [ "$(sort "$tmp/va" "$tmp/vb" | tr '\n' ' ')" = "1 2 " ] && ok "two vanilla clients share the GPU: slots 1 and 2" || bad "shared slots: $(cat "$tmp/va" "$tmp/vb" "$tmp"/v?.err 2>&1)"
  out=$(gpu_slot_take vc 2>&1); rc=$?
  [ $rc = 1 ] && [[ $out == *"all 2 shared GPU slots are busy"*"slot 1: "*"slot 2: "* ]] && ok "third vanilla client goes to the CPU at once: $out" || { bad "third slot: $rc $out"; exec 197>&-; }
  out=$(lease_status); [[ $out == "no lease, but shared GPU clients are running"* ]] && ok "status shows shared clients: $out" || bad "status with shared clients: $out"
  out=$(lease_take FZC /x/wt-FZC 2>&1); rc=$?
  [ $rc = 75 ] && [[ $out == *"shared GPU clients are running"* ]] && [ ! -s "$INCOMING_GPU_LEASE" ] && ok "a measurement never shares the GPU: lease refused while slots are held" || bad "lease_take beside shared clients: $rc $out"
  out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/nested_kwin.sh" wayland-test 64 64 true 2>&1); rc=$?
  [ $rc = 75 ] && [[ $out == *"shared GPU clients are running"* ]] && ok "exclusive client refused beside shared ones" || bad "nested_kwin beside shared clients: $rc $out"
  out=$(INCOMING_GPU_OWNER=FZC "$ROOT/tools/laptop_job.sh" --gpu bench -- false 2>&1); rc=$?
  [ $rc = 75 ] && [[ $out == *"shared GPU clients are running"* ]] && ok "--gpu job refused beside shared clients" || bad "--gpu job beside shared clients: $rc $out"
  # a waiting measurement queues; its queue entry keeps new vanilla clients on the CPU until the GPU is its
  ( INCOMING_GPU_POLL=0.2 lease_take FZC /x/wt-FZC --wait 2>/dev/null; echo done > "$tmp/meas" ) & m=$!
  sleep 0.6
  out=$(gpu_slot_take vd 2>&1); rc=$?
  [ $rc = 1 ] && [[ $out == *"exclusive GPU work is waiting"*FZC* ]] && ok "queued measurement keeps new vanilla clients off: $out" || { bad "slot beside a queued measurement: $rc $out"; exec 197>&-; }
  [ ! -f "$tmp/meas" ] && ok "measurement waits for shared clients" || bad "measurement started beside shared clients"
  wait $a $b
  for _ in $(seq 50); do [ -f "$tmp/meas" ] && break; sleep 0.1; done
  [ -f "$tmp/meas" ] && [ "$(cut -f1 "$INCOMING_GPU_LEASE")" = FZC ] && ok "shared clients ended, the measurement took the GPU" || bad "measurement after shared clients: $(cat "$INCOMING_GPU_LEASE")"
  wait $m
  out=$(gpu_slot_take ve 2>&1); rc=$?
  [ $rc = 1 ] && [[ $out == *"GPU lease held by FZC"* ]] && ok "measurement holding the lease keeps vanilla clients on the CPU" || { bad "slot under a lease: $rc $out"; exec 197>&-; }
  lease_release FZC
  out=$(STUB_FREE=600 gpu_slot_take vf 2>&1); rc=$?
  [ $rc = 1 ] && [[ $out == *"600 MiB of VRAM free"* ]] && ok "too little free VRAM: CPU" || { bad "slot with 600 MiB free: $rc $out"; exec 197>&-; }
  out=$(PATH=/usr/bin:/bin gpu_slot_take vg 2>&1); rc=$?
  command -v nvidia-smi >/dev/null 2>&1 || { [ $rc = 1 ] && [[ $out == *"no NVIDIA GPU"* ]] && ok "no GPU: CPU" || bad "slot without nvidia-smi: $rc $out"; }
  exec 197>&-
  # shared KWin only with a slot and on its own socket prefix (gpu_lease.sh tells it by that name)
  out=$(INCOMING_GPU_SHARED=1 "$ROOT/tools/nested_kwin.sh" wayland-incoming-shared-t 64 64 true 2>&1); rc=$?
  [ $rc = 2 ] && [[ $out == *"without a slot"* ]] && ok "shared GPU session refused without a slot" || bad "shared session without slot: $rc $out"
  out=$(INCOMING_GPU_SHARED=1 "$ROOT/tools/nested_kwin.sh" wayland-test 64 64 true 2>&1); rc=$?
  [ $rc = 2 ] && [[ $out == *"wayland-incoming-shared-"* ]] && ok "shared GPU session needs a shared socket name" || bad "shared socket name: $rc $out"
  # vanilla_display: a free slot runs the client on the GPU with the slot held and no CPU GL selection; the slot is free
  # once it ends; Artem's game starting stops it (75); no slot runs it on the CPU at once
  mkdir -p "$tmp/vroot/tools"; cp "$ROOT/tools/soft_gl.sh" "$ROOT/tools/gpu_lease.sh" "$tmp/vroot/tools/"
  printf '#!/bin/bash\necho "stub fd197=$(readlink /proc/$$/fd/197) soft=${INCOMING_SOFT:-} libgl=${LIBGL_ALWAYS_SOFTWARE:-} socket=$1"; sleep "${STUB_RUN:-1}"\n' > "$tmp/vroot/tools/nested_kwin.sh"
  chmod +x "$tmp/vroot/tools/nested_kwin.sh"
  vd() { bash -c 'source "$1/tools/soft_gl.sh"; soft_gl_env; soft_display() { echo "cpu display $1"; }; INCOMING_RENDERER_FILE=$2 INCOMING_GAME_POLL=0.2 vanilla_display v1 64 64 true' _ "$tmp/vroot" "$tmp/renderer"; }
  out=$(vd); rc=$?
  [ $rc = 0 ] && [[ $out == *"renderer: GPU, shared slot 1 of 2"*"stub fd197=$(readlink -m "$INCOMING_GPU_LEASE").slot1 soft= libgl= socket=wayland-incoming-shared-v1"* ]] \
    && [ "$(cat "$tmp/renderer")" = gpu ] && ok "vanilla client on a shared slot, CPU GL selection dropped" || bad "vanilla_display GPU: $rc $out"
  [ -z "$(gpu_slots_busy)" ] && ok "slot free after the client" || bad "slot held after client: $(gpu_slots_busy)"
  out=$(STUB_FREE=10 vd); rc=$?
  [ $rc = 0 ] && [[ $out == *"renderer: CPU (llvmpipe): 10 MiB of VRAM free"*"cpu display v1"* ]] && [ "$(cat "$tmp/renderer")" = soft ] \
    && ok "no slot: CPU at once" || bad "vanilla_display fallback: $rc $out"
  start=$(date +%s)
  ( STUB_RUN=30 vd > "$tmp/vd.out" 2>&1; echo $? > "$tmp/vd.rc" ) & v=$!
  sleep 0.8
  ( cd "$tmp/prism/instances/Open Skies/minecraft" && exec "$tmp/bin/java" -cp x ) & j=$!
  wait $v; took=$(( $(date +%s) - start ))
  [ "$(cat "$tmp/vd.rc")" = 75 ] && grep -q "Artem's game started at .*: shared GPU client stopped" "$tmp/vd.out" && [ $took -lt 5 ] \
    && ok "Artem's game stops a shared GPU client in ${took} s" || bad "shared client and game: rc $(cat "$tmp/vd.rc") after $took s: $(cat "$tmp/vd.out")"
  out=$(vd 2>&1); [[ $out == *"renderer: CPU (llvmpipe): Artem's game is running"* ]] && ok "while Artem plays, vanilla clients render on the CPU" || bad "vanilla_display during game: $out"
  kill $j 2>/dev/null; wait $j 2>/dev/null
  unset INCOMING_GPU_SLOTS PRISM_DIR
fi

# review probes (tools/probes): shared and exclusive launches exclude each other also across slot counts and older
# scripts; a vanilla launch never waits on exclusive admission; a CPU client's admission uses its real ceiling; Artem's
# game stops the whole shared session and frees its slot, with and without errexit
for p in gpu-exclusion vanilla-no-wait soft-memory-override soft-memory-fractional; do
  out=$(bash "$ROOT/tools/probes/$p.sh" "$ROOT" 2>&1) && ok "probe $p" || bad "probe $p: $out"
done
if command -v dbus-run-session >/dev/null; then
  out=$(INCOMING_GPU_KWIN=no-such-kwin timeout 120 python3 "$ROOT/tools/probes/shared-game-stop.py" "$ROOT" 2>&1) && ok "probe shared-game-stop" || bad "probe shared-game-stop: $out"
fi
# an exclusive GPU client (shader pack, LOD mods, camera feeds) refuses to start beside Artem's game
out=$(timeout 60 python3 "$ROOT/tools/probes/exclusive-client-yields.py" "$ROOT" 2>&1) && ok "probe exclusive-client-yields" || bad "probe exclusive-client-yields: $out"

# a running client's GPU samples go to the load log under its owner, one per renewal; a job's CPU goes there at its stop
lease_take wave /x/wt-wave >/dev/null 2>&1
( PATH="$tmp/smi:$PATH" INCOMING_GPU_RENEW=0.2 DELEGATE_TASK=mission-wave gpu_client_hold wave /x/wt-wave "sample test" && sleep 1.2 )
sleep 0.5; lease_release wave
grep -qP '^gpu\t[0-9]+\tmission-wave\t0.2\t97$' "$tmp/load.tsv" && ! grep -qP '^gpu\t[0-9]+\twave\t' "$tmp/load.tsv" \
  && ok "client GPU samples billed to the client's task, not the lease name: $(grep -c '^gpu' "$tmp/load.tsv")" \
  || bad "GPU samples: $(cat "$tmp/load.tsv" 2>/dev/null)"
# handoff: once another client's pid is in the holder file, the old renewal writes nothing more
lease_take wave /x/wt-wave >/dev/null 2>&1
( PATH="$tmp/smi:$PATH" INCOMING_GPU_RENEW=0.2 DELEGATE_TASK=old-task gpu_client_hold wave /x/wt-wave "handoff test" \
    && echo "1 2" > "$GPU_CLIENT.pid" && sleep 1.2 )
sleep 0.5; lease_release wave
! grep -q 'old-task' "$tmp/load.tsv" && ok "a renewal stops sampling when another client holds the lock" \
  || bad "samples after handoff: $(grep -c old-task "$tmp/load.tsv")"
if systemctl --user show-environment >/dev/null 2>&1; then
  DELEGATE_TASK=wave timeout 60 "$ROOT/tools/laptop_job.sh" acct -- bash -c 'exit 3' >/dev/null 2>&1; rc=$?
  sleep 0.5
  [ $rc = 3 ] && grep -qP '^cpu\t[0-9]+\twave\tincoming-job-acct-[0-9]+\.service\t[0-9]+\t[0-9.]+$' "$tmp/load.tsv" \
    && ok "job CPU in the load log at its stop, exit code kept" || bad "job accounting: $rc $(grep '^cpu' "$tmp/load.tsv")"
  # a job past its memory ceiling is stopped inside its own unit and says so (20.11: unlimited jobs and 12.7 GB of swap
  # ended in a kernel OOM kill on the laptop)
  out=$(INCOMING_JOB_MEM=64M INCOMING_JOB_SWAP=0 timeout 60 "$ROOT/tools/laptop_job.sh" memtest -- python3 -c 'import time; b = b"x" * (256 << 20); time.sleep(5)' 2>&1); rc=$?
  [ $rc != 0 ] && [[ $out == *"laptop_job memtest: memory ceiling 64M (+ 0 swap) reached: "*"killed inside its unit"* ]] \
    && ok "job past its ceiling stopped with a memory ceiling line: $(grep 'memory ceiling' <<< "$out")" || bad "memory ceiling: $rc $out"
  out=$(timeout 60 "$ROOT/tools/laptop_job.sh" memok -- python3 -c 'b = bytearray(64 << 20)' 2>&1); rc=$?
  [ $rc = 0 ] && [[ $out != *"memory ceiling"* ]] && ok "job within its ceiling: no ceiling line" || bad "job within ceiling: $rc $out"
fi
# every mode has a ceiling: GameTest/build/GPU jobs 10 GB + 4 GB swap from measured peaks, the whole pack 20 GB, CPU
# clients their soft_gl.sh ceiling without swap
[ "$("$ROOT/tools/laptop_job.sh" --ceiling heavy)" = "10G 4G" ] && [ "$("$ROOT/tools/laptop_job.sh" --ceiling gpu)" = "10G 4G" ] \
  && [ "$("$ROOT/tools/laptop_job.sh" --ceiling full)" = "20G 4G" ] && [ "$(INCOMING_SOFT_HEAP=3g "$ROOT/tools/laptop_job.sh" --ceiling soft)" = "5120M 0" ] \
  && [ "$(INCOMING_JOB_MEM=2G "$ROOT/tools/laptop_job.sh" --ceiling heavy)" = "2G 4G" ] && ok "memory ceiling for every mode" || bad "ceilings: $("$ROOT/tools/laptop_job.sh" --ceiling heavy)"
exit $fail
