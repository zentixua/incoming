#!/usr/bin/env bash
# Laptop GPU lease: one owner works on visuals at a time; a file enforces it, not memory.
#
#   tools/gpu_lease.sh status               holder, since when, worktree, last work time,
#                                           and waiters (in queue order)
#   tools/gpu_lease.sh take [--wait]        acquire (renew own); foreign lease refuses with 75 and "GPU held by …";
#                                           --wait queues until release or expiry
#   tools/gpu_lease.sh release              release own (finished visuals; refuses while own client runs: first
#                                           tools/sandbox.sh stop)
#   source tools/gpu_lease.sh               functions lease_owner, lease_take, lease_renew, lease_release, lease_status
#
# Owner is the session: INCOMING_GPU_OWNER or worktree name (mod/run/wt-<S>[-anything] → <S>, otherwise directory name), so
# worktrees of one session (wt-N3 and wt-N3-main) share an owner. Each GPU client acquires it itself:
# `tools/nested_kwin.sh` (all clients use it: sandbox, scenarios, prod_client, look gate, trailer), as do
# `tools/laptop_job.sh --gpu` jobs (Blender); sandbox and --gpu jobs also check before building for immediate refusal.
# While a client or job runs, the lease renews every 30 s; without GPU work it expires after
# INCOMING_GPU_LEASE_IDLE seconds (600): edits between frames retain it, but an abandoned session cannot hold the GPU
# forever. Only one GPU client even within a session (`$LEASE.client` lock, held by nested_kwin.sh and all descendants):
# own look gate beside own sandbox gets "GPU client is running …" instead of sharing frames. A session
# restarts its own sandbox without waiting; heavy CPU-only jobs (GameTest, build) take no lease and
# do not stop the sandbox. Artem's game stops everything running regardless of lease.
#
# Unlocked clients are visible too: any live nested KWin (`kwin_wayland --virtual`, used by all clients, including
# scripts predating the lock) outside our job means "GPU client is running without a lease". On 23.10 a sandbox
# started by a pre-lease script ran unlocked, lease reported "free", and another session's client displaced it. Artem's game is
# java running in his Prism instance directory or Prism group (artem_playing), not command-line text: sandbox
# mods with MC_DIR pointing at the instance also have instance paths.
#
# Why file and flock(1), rather than a remembered rule or job queue: the rule was just text, and on 23.10 three jobs
# took the GPU together; one sandbox restarted five times. Semaphores such as `sem` (GNU parallel) and queues
# (`ts`, `nq`) hold a process, not a session: between frames while editing there is no process, and a neighboring job
# would take the GPU.
#
# Waiters (--wait) use a fair queue (FIFO, `$LEASE.queue`): when free, the first waiter acquires, not whoever
# checks first; without --wait a free GPU with a queue is unavailable ("queued: …"). Queue never displaces the holder:
# own session renews and restarts its work as before; lease ends only on release or expiry. The queue holds
# the waiting process (PID and start time): exit or interruption (Ctrl+C) removes its position automatically.
# On 23.10, without a queue, `start --wait` waited ~35 min while three sessions checking earlier took turns on the GPU.
# Files live in XDG_RUNTIME_DIR
# (tmpfs): reboot clears it. Test: tools/test_gpu_lease.sh (CI).
LEASE=${INCOMING_GPU_LEASE:-${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/incoming-gpu.lease}
LEASE_IDLE=${INCOMING_GPU_LEASE_IDLE:-600}
# GPU client: lock and holder (owner, worktree, since when, client description)
GPU_CLIENT=$LEASE.client
# wait queue: "owner, worktree, since (epoch), process (PID start-time)" rows in arrival order; under $LEASE.lock
QUEUE=$LEASE.queue
# waiter polling interval, seconds
LEASE_POLL=${INCOMING_GPU_POLL:-3}

# owner for worktree $1 (default: this script's worktree)
lease_owner() {
  [ -n "${INCOMING_GPU_OWNER:-}" ] && { echo "$INCOMING_GPU_OWNER"; return; }
  # real path: main worktree also visible through a symlink (airstrike → incoming), with one owner
  local name; name=$(basename "$(readlink -m "${1:-$(dirname "${BASH_SOURCE[0]}")/..}")")
  if [[ $name =~ ^wt-([^-]+) ]]; then echo "${BASH_REMATCH[1]}"; else echo "$name"; fi
}

# lease row: owner, worktree, since (epoch), last work (epoch); under $LEASE.lock
_lease_read() { [ -s "$LEASE" ] && IFS=$'\t' read -r L_OWNER L_TREE L_SINCE L_SEEN < "$LEASE" || { L_OWNER=; L_TREE=; L_SINCE=0; L_SEEN=0; }; }
_lease_write() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" > "$LEASE.tmp" && mv "$LEASE.tmp" "$LEASE"; }
_lease_free() { [ -z "$L_OWNER" ] || [ $(( $(date +%s) - L_SEEN )) -gt "$LEASE_IDLE" ]; }
_lease_hm() { date -d "@$1" +%H:%M; }

# foreign holder gives "GPU held by …"; free with queue is also busy (waiters); queue description follows
lease_status() {
  local who q
  _lease_read
  q=$(_queue_line)
  if _lease_free; then
    who=$(gpu_client_busy) && { echo "no lease, but $who${q:+; $q}"; return 0; }
    [ -n "$q" ] && { echo "GPU is free, but $q"; return 0; }
    echo "GPU is free"; return 1
  fi
  echo "GPU held by $L_OWNER ($L_TREE), since $(_lease_hm "$L_SINCE"), last work $(( ($(date +%s) - L_SEEN) / 60 )) min ago" \
    "(free — tools/gpu_lease.sh release in its tree or after $(( (LEASE_IDLE - $(date +%s) + L_SEEN) / 60 + 1 )) min without work)${q:+; $q}"
}

# queue without exited waiters (under $LEASE.lock): live process rows in order
_queue_live() {
  local o t since pid
  [ -s "$QUEUE" ] || return 0
  while IFS=$'\t' read -r o t since pid; do
    [ -n "$pid" ] && [ "$(_proc_start "${pid%% *}")" = "${pid#* }" ] && printf '%s\t%s\t%s\t%s\n' "$o" "$t" "$since" "$pid"
  done < "$QUEUE"
}

# rewrite queue with live waiters, omitting owner $1 if specified
_queue_keep() {
  _queue_live | awk -F'\t' -v o="${1:-}" 'o == "" || $1 != o' > "$QUEUE.tmp"; mv "$QUEUE.tmp" "$QUEUE"
}

# first live waiter (owner), or nothing
_queue_head() { _queue_live | head -n 1 | cut -f1; }

# "queued: A (worktree) since 21:10, B (…) since 21:12" in order, each owner once; empty means none
_queue_line() {
  local o t since seen=" " parts=()
  while IFS=$'\t' read -r o t since _; do
    [[ $seen == *" $o "* ]] && continue
    seen+="$o "; parts+=("$o ($t) since $(_lease_hm "$since")")
  done < <(_queue_live)
  [ ${#parts[@]} -gt 0 ] || return 0
  local IFS=,; echo "queued: ${parts[*]}" | sed 's/,/, /g'
}

# lease_take <owner> <worktree> [--wait] — 0: own lease; 75: foreign lease, or free with others ahead in queue (line to
# stderr). --wait queues: first waiter takes free GPU; own unexpired lease bypasses queue
lease_take() {
  local owner=$1 tree wait=${3:-} fd said= now who why queued= me
  tree=$(readlink -m "$2")
  # PID of this process: $BASHPID inside $(…) is already the subshell PID
  local pid=$BASHPID; me="$pid $(_proc_start "$pid")"
  while true; do
    exec {fd}>"$LEASE.lock"; flock "$fd"
    _lease_read; now=$(date +%s)
    why=
    if [ "$L_OWNER" = "$owner" ] && ! _lease_free; then
      # own, unexpired: renew; queue cannot displace holder
      :
    elif ! _lease_free; then
      why=$(lease_status)
    elif [ -n "$(_queue_head)" ] && [ "$(_queue_head)" != "$owner" ]; then
      why="GPU is free, but $(_queue_line)"
    elif who=$(gpu_client_busy); then
      # free lease but unlocked client running (pre-lease script, outside launch): held by that client
      why=$who
    fi
    if [ -z "$why" ]; then
      [ "$L_OWNER" = "$owner" ] && ! _lease_free || L_SINCE=$now
      _lease_write "$owner" "$tree" "$L_SINCE" "$now"
      # acquired: queue positions of this and other processes of our session are no longer needed
      _queue_keep "$owner"
      exec {fd}>&-
      return 0
    fi
    if [ "$wait" != --wait ]; then exec {fd}>&-; echo "$why" >&2; return 75; fi
    # queue on first refusal (and again if removed: acquiring process of the same session removes it)
    local pos key; pos=$(_queue_live | awk -F'\t' -v m="$me" '$4 == m { print NR; exit }')
    if [ -z "$pos" ]; then
      _queue_keep
      printf '%s\t%s\t%s\t%s\n' "$owner" "$tree" "${queued:-$now}" "$me" >> "$QUEUE"
      queued=${queued:-$now}
      pos=$(_queue_live | wc -l)
    fi
    # print on entry and when queue position or holder changes
    key="$pos|$L_OWNER|$(_lease_free && echo free)"
    exec {fd}>&-
    [ "$said" != "$key" ] && { echo "$(date +%T) $why — waiting (queue position ${pos:-?})" >&2; said=$key; }
    sleep "$LEASE_POLL"
  done
}

# renew own (GPU work running); reacquire free lease (released or expired while work runs); foreign lease:
# leave alone, return 1
lease_renew() {
  local owner=$1 tree=${2:-} fd rc=1 now
  exec {fd}>"$LEASE.lock"; flock "$fd"
  _lease_read; now=$(date +%s)
  if [ "$L_OWNER" = "$owner" ] && ! _lease_free; then _lease_write "$L_OWNER" "$L_TREE" "$L_SINCE" "$now"; rc=0
  elif _lease_free; then _lease_write "$owner" "$(readlink -m "${tree:-$L_TREE}")" "$now" "$now"; rc=0; fi
  exec {fd}>&-
  return $rc
}

# process start time (/proc/<pid>/stat field 22; field 20 after the name, which can contain spaces and parentheses)
_proc_start() { sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | cut -d' ' -f20; }

# this process belongs to the job holding the client lock: inherited it (fd 199), or holder is an ancestor (Python
# with close_fds=True does not pass fd to descendants: prod_client.py → nested_kwin.sh inside a --gpu job)
gpu_client_inherited() {
  [ "$(readlink "/proc/$BASHPID/fd/199" 2>/dev/null)" = "$(readlink -f "$GPU_CLIENT")" ] && return 0
  # holder is PID plus process start time: a PID reused by another process does not count
  local holder; holder=$(cat "$GPU_CLIENT.pid" 2>/dev/null) || return 1
  [ -n "$holder" ] && ! flock -n -s "$GPU_CLIENT" true 2>/dev/null || return 1
  _descends "$BASHPID" "$holder"
}

# process $1 is a descendant of "PID start-time" process $2 (or itself)
_descends() {
  local p=$1
  while [ "${p:-1}" -gt 1 ]; do
    [ "$p $(_proc_start "$p")" = "$2" ] && return 0
    p=$(sed 's/.*) //' "/proc/$p/stat" 2>/dev/null | cut -d' ' -f2)
  done
  return 1
}

# live nested KWin (GPU clients) outside our job, as "PID command" lines, locked or unlocked: all clients use
# nested_kwin.sh, including pre-lease scripts. $1 is lock holder "PID start-time" if we belong to its
# job: its clients are ours. INCOMING_GPU_KWIN selects which program counts as KWin (tests substitute a stub)
gpu_clients_running() {
  local kwin=${INCOMING_GPU_KWIN:-kwin_wayland} p a virt hit
  for p in $(pgrep -f -- --virtual); do
    virt= hit=
    while IFS= read -r -d '' a; do
      [ "$a" = --virtual ] && virt=1
      if [ "$a" = "$kwin" ] || [ "${a##*/}" = "$kwin" ]; then hit=1; fi
    done 2>/dev/null < "/proc/$p/cmdline"
    [ -n "$virt" ] && [ -n "$hit" ] || continue
    [ -n "${1:-}" ] && _descends "$p" "$1" && continue
    echo "$p $(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | cut -c1-200)"
  done
}

# Artem's game: java in his Prism instance directory (game working directory is instance directory) or Prism group
# (flatpak); not command-line text: sandbox with instance MC_DIR carries the same path in mod paths
PRISM=${PRISM_DIR:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher}
artem_playing() {
  local p
  for p in $(pgrep -x java); do
    [[ $(readlink "/proc/$p/cwd" 2>/dev/null)/ == "$PRISM/instances/"* ]] && return 0
    grep -q 'org\.prismlauncher\.PrismLauncher' "/proc/$p/cgroup" 2>/dev/null && return 0
  done
  return 1
}

# current GPU client outside our job: "who" line (code 0), or nothing (code 1)
gpu_client_busy() {
  local holder= c
  # probes use a shared lock: compatible with each other, incompatible with exclusive holder
  if ! flock -n -s "$GPU_CLIENT" true 2>/dev/null; then
    gpu_client_inherited || { echo "GPU client is running: $(cat "$GPU_CLIENT.who" 2>/dev/null)"; return 0; }
    holder=$(cat "$GPU_CLIENT.pid" 2>/dev/null)
  fi
  c=$(gpu_clients_running "$holder" | head -n 1)
  [ -n "$c" ] && { echo "GPU client is running without a lease (pre-lease script?): $c"; return 0; }
  return 1
}

# gpu_client_hold <owner> <worktree> <description> — acquire client lock on fd 199 (inherited by exec and descendants:
# held while client or job lives), renew lease while held; busy refuses with 75. A job component whose job already holds
# the lock (bench, multiplayer, --gpu job client) does not acquire it
gpu_client_hold() {
  gpu_client_inherited && return 0
  exec 199>"$GPU_CLIENT"
  # wait up to 2 s: a neighboring `flock -n` probe (status, renewal) holds the lock briefly
  if ! flock -w 2 199; then echo "GPU client is running: $(cat "$GPU_CLIENT.who" 2>/dev/null)" >&2; exec 199>&-; return 75; fi
  printf '%s (%s), since %s: %s\n' "$1" "$(readlink -m "$2")" "$(date +%H:%M)" "$3" > "$GPU_CLIENT.who"
  local me=$BASHPID
  echo "$me $(_proc_start "$me")" > "$GPU_CLIENT.pid"
  # renew while lock held; renewal needs neither lock (would hold it itself) nor foreign fds (caller pipe,
  # tee, ssh would wait for it to finish)
  (
    set +e
    for f in /proc/$BASHPID/fd/*; do n=${f##*/}; [ "$n" -gt 2 ] 2>/dev/null && eval "exec $n>&-" 2>/dev/null; done
    exec </dev/null >/dev/null 2>&1
    # renew only while lock busy (code 1); free or file missing (code 0, open error) ends renewal
    while sleep "${INCOMING_GPU_RENEW:-30}"; do flock -n -s "$GPU_CLIENT" true 2>/dev/null; [ $? = 1 ] || exit 0; lease_renew "$1" "$2" || true; done
  ) &
}

# release own
lease_release() {
  local owner=$1 fd rc=1
  exec {fd}>"$LEASE.lock"; flock "$fd"
  _lease_read
  if [ "$L_OWNER" = "$owner" ]; then rm -f "$LEASE"; rc=0; fi
  exec {fd}>&-
  return $rc
}

[ "${BASH_SOURCE[0]}" = "$0" ] || return 0
set -uo pipefail
me=$(lease_owner)
case "${1:-}" in
  status) out=$(lease_status); echo "$out"; [[ $out == "no lease"* ]] || gpu_client_busy; exit 0 ;;
  take) lease_take "$me" "$(cd "$(dirname "$0")/.." && pwd)" "${2:-}" && echo "GPU held by $me" ;;
  release)
    # own client only if lock held ("who" file remains after client exit)
    ! flock -n -s "$GPU_CLIENT" true 2>/dev/null && who=$(gpu_client_busy) && [[ $(cat "$GPU_CLIENT.who" 2>/dev/null) == "$me "* ]] && { echo "$who — stop it first (tools/sandbox.sh stop)" >&2; exit 75; }
    if lease_release "$me"; then echo "GPU released"; else echo "lease is not held by $me: $(lease_status)"; fi ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac
