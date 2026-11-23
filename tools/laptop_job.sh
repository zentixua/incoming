#!/usr/bin/env bash
# Heavy job on the host laptop (scenario clients and sandbox, Blender, trailer editing, bench):
#
#   tools/laptop_job.sh [--gpu [--wait] | --light | --soft] [--full] <name> -- <command …>
#   tools/laptop_job.sh --status            ← running jobs, GPU holder, jobs waiting for Artem's game
#   tools/laptop_job.sh --ceiling <mode>    ← memory ceiling of a mode (heavy, light, gpu, soft, full): "MemoryMax MemorySwapMax"
#
# Job runs in its own transient systemd --user service (systemd-run --wait --collect), outside its caller's
# group (e.g. Claude app): on exit everything left in the group stops (no stranded nested-session D-Bus
# helpers), and if the system still runs out of memory, this job goes first (OOMScoreAdjust), not Artem's
# programs. Laptop stays awake while job runs (systemd-inhibit).
# The unit lives in agents.slice (laptop-agents installs it with one MemoryMax/MemorySwapMax for all agent work: missions,
# delegate runs, laptop jobs): the sum of all jobs stays inside it, so swap never fills and systemd-oomd never kills the
# Claude app; a job that does not fit is killed inside the slice ("agent work memory … is full"). Without the installed
# unit (a server) the slice has no limit. The caller's TMPDIR passes on (agent units keep temporary files on disk).
# Every job has a memory ceiling (MemoryMax, MemorySwapMax): a job past it is stopped inside its own unit and its output
# ends with "laptop_job <name>: memory ceiling … reached", instead of the kernel picking a victim on the whole laptop.
# Ceilings from measured unit peaks (journal, 14 days to 20.11, RAM + swap): GameTest, sweeps, builds and GPU jobs at most
# 9.5 + 1.5 GB (a 7 h GameTest run) and 8.8 + 4.2 GB (a GPU sandbox with a shader pack), so 10 GB + 4 GB swap;
# --full (the whole pack: prod_client.py, look gate; ≈12 GB, runs alone) 20 GB + 4 GB; --soft its client's own
# (soft_gl.sh soft_mem_cap) without swap. INCOMING_JOB_MEM=<size> (INCOMING_JOB_SWAP) sets MemoryMax (MemorySwapMax) for one job.
#
# No job queue: one session works on visuals and GPU at a time, enforced by the GPU lease
# (tools/gpu_lease.sh), not a queue for each job.
#   --gpu    client or Blender: acquires session GPU lease (owner by worktree) before starting service; held by
#            another session: refusal 75 with "GPU held by …, since …" (--wait joins lease queue in arrival
#            order); renews lease while running. Also waits until Artem's game closes (Java in Prism instance
#            directory): shared machine. If caller exits (timeout, lost session) while job waits for game, job never starts (code 75).
#            His game started while the job runs (polled every second) stops the job: "Artem's game started at T: job
#            stopped", code 75
#   --light  same as no flag: immediate, no lease (GameTest, build, server need no GPU)
#   --soft   vanilla client (tools/soft_gl.sh: shared GPU slot or CPU): immediate, no lease, idle CPU priority (CPUWeight=idle:
#            Artem's game and every normal process go first), memory capped (soft_gl.sh soft_mem_cap) without swap; a CPU client keeps running while Artem plays
#   --full   the whole pack (prod_client.py, look gate; with any mode but --soft): the larger memory ceiling
set -euo pipefail

# Artem's game: artem_playing (gpu_lease.sh), java in his Prism instance directory
source "$(dirname "$(readlink -f "$0")")/gpu_lease.sh"

if [ "${1:-}" = --status ]; then
  lease_status || true
  systemctl --user list-units 'incoming-job-*' --state=active --no-legend --plain | awk '{print "  " $1}'
  artem_playing && echo "Artem's game is running: --gpu jobs wait"
  exit 0
fi

# ExecStopPost of every job unit: one row for the delegated task's laptop load (laptop-agents mission-load reads it):
#   cpu <epoch> <task> <unit> <wall s> <cpu s>   — CPU from the unit's own cgroup (cpu.stat), still there at stop-post
if [ "${1:-}" = --account ]; then
  set +e
  cg=$(cut -d: -f3 /proc/self/cgroup 2>/dev/null | head -n 1)
  us=$(awk '$1 == "usage_usec" { print $2 }' "/sys/fs/cgroup$cg/cpu.stat" 2>/dev/null)
  [ -n "$us" ] || exit 0
  log=${DELEGATE_LOAD_LOG:-${XDG_STATE_HOME:-$HOME/.local/state}/delegate/load.tsv}
  mkdir -p "$(dirname "$log")" 2>/dev/null
  printf 'cpu\t%s\t%s\t%s\t%s\t%s\n' "$(date +%s)" "${DELEGATE_TASK:--}" "${cg##*/}" \
    "$(( $(date +%s) - ${INCOMING_JOB_START:-$(date +%s)} ))" "$(awk -v u="$us" 'BEGIN { printf "%.1f", u / 1e6 }')" >> "$log" 2>/dev/null
  # the unit hit its memory ceiling (the kernel killed inside it; systemd then stops the rest): the caller prints it
  # memory.events: oom = this cgroup reached its own limit, oom_kill = processes killed in it (also by a global OOM);
  # the slice's oom count grown since the job started (INCOMING_SLICE_OOM) = agents.slice reached its limit
  oom=$(awk '$1 == "oom_kill" { print $2 }' "/sys/fs/cgroup$cg/memory.events" 2>/dev/null)
  own=$(awk '$1 == "oom" { print $2 }' "/sys/fs/cgroup$cg/memory.events" 2>/dev/null)
  slice=$(awk '$1 == "oom" { print $2 }' "/sys/fs/cgroup${cg%/*}/memory.events" 2>/dev/null)
  if [ "${SERVICE_RESULT:-}" = oom-kill ] || [ "${oom:-0}" -gt 0 ]; then
    peak=$(awk '{ printf "%.1f", $1 / 1073741824 }' "/sys/fs/cgroup$cg/memory.peak" 2>/dev/null)
    where=host
    [ "${slice:-0}" -gt "${INCOMING_SLICE_OOM:-0}" ] && where=slice
    [ "${own:-0}" -gt 0 ] && where=own
    [ -n "${INCOMING_JOB_OOM:-}" ] && echo "${oom:-1} ${peak:-?} $where" > "$INCOMING_JOB_OOM"
  fi
  exit 0
fi

# memory ceiling of a mode: "MemoryMax MemorySwapMax" (systemd sizes); INCOMING_JOB_MEM, INCOMING_JOB_SWAP override them
_ceiling() {
  local max swap=4G
  case "$1" in
    soft) source "$(dirname "$(readlink -f "$0")")/soft_gl.sh"; max="$(soft_mem_cap)M"; swap=0 ;;
    full) max=20G ;;
    *) max=10G ;;
  esac
  echo "${INCOMING_JOB_MEM:-$max} ${INCOMING_JOB_SWAP:-$swap}"
}
[ "${1:-}" = --ceiling ] && { _ceiling "${2:-heavy}"; exit 0; }
# systemd size (4608M, 6.5G, 1T, bytes) → whole MB (rounded up); nothing (code 1) when it is not one
_mb() {
  LC_ALL=C awk -v v="$1" 'BEGIN {
    if (v !~ /^[0-9]+(\.[0-9]+)?[KkMmGgTt]?$/) exit 1
    u = substr(v, length(v)); n = v + 0
    f = u ~ /[Kk]/ ? 1 / 1024 : u ~ /[Mm]/ ? 1 : u ~ /[Gg]/ ? 1024 : u ~ /[Tt]/ ? 1048576 : 1 / 1048576
    m = n * f; printf "%d\n", (m == int(m)) ? m : int(m) + 1 }'
}

# inside service: --gpu job waits for Artem's game to close. Output without set -e: caller may have exited, and writing to its
# closed pipe must not fail the job
if [ "${1:-}" = --in-unit ]; then
  set +e
  mode=$2; name=$3; caller=$4; shift 5
  said=
  while [ "$mode" = gpu ] && artem_playing; do
    [ -z "$said" ] && { echo "$(date +%T) $name: Artem's game is running — waiting"; said=1; }
    kill -0 "$caller" 2>/dev/null || { echo "$name: caller exited before startup — not starting"; exit 75; }
    lease_renew "$INCOMING_GPU_OWNER" "$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)" || true
    sleep 30
  done
  [ "$mode" = gpu ] || exec "$@"
  # --gpu job is one GPU client for its whole lifetime (Blender itself, clients inside as components), and
  # session lease renews while it holds the lock
  tree=$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)
  gpu_client_hold "$INCOMING_GPU_OWNER" "$tree" "job $name" || exit 75
  # Artem's game started during the job (19.11: a GPU run measured frame times while he watched a replay): poll every
  # second, stop the whole job (its own process group: TERM, KILL after 10 s) and say so; the job ends with 75.
  # The service stops whatever else is left in its group when this script exits
  set -m
  "$@" &
  job=$!
  set +m
  code=
  while kill -0 "$job" 2>/dev/null; do
    if artem_playing; then
      echo "$name: Artem's game started at $(date +%T): job stopped"
      kill -TERM -- "-$job" 2>/dev/null
      for _ in $(seq 100); do kill -0 -- "-$job" 2>/dev/null || break; sleep 0.1; done
      kill -KILL -- "-$job" 2>/dev/null
      code=75
      break
    fi
    sleep "${INCOMING_GAME_POLL:-1}"
  done
  wait "$job"; rc=$?
  [ -n "$code" ] || code=$rc
  lease_renew "$INCOMING_GPU_OWNER" "$tree" || true
  exit $code
fi

# a path as one systemd command-line word: double-quoted, \ and " escaped (a transient property expands no $ or %)
_sd_quote() { local p=${1//\\/\\\\}; p=${p//\"/\\\"}; printf '"%s"' "$p"; }

mode=heavy wait= full=
case "${1:-}" in --gpu) mode=gpu; shift ;; --light) mode=light; shift ;; --soft) mode=soft; shift ;; esac
[ "$mode" = gpu ] && [ "${1:-}" = --wait ] && { wait=--wait; shift; }
[ "${1:-}" = --full ] && [ "$mode" != soft ] && { full=1; shift; }
[ $# -ge 3 ] && [ "$2" = "--" ] || { echo "usage: $0 [--gpu [--wait]|--light|--soft] [--full] <name> -- <command …> | --status | --ceiling <mode>" >&2; exit 2; }
name=$1; shift 2
read -r mem_max mem_swap <<< "$(_ceiling "$([ -n "$full" ] && echo full || echo "$mode")")"

# GPU lease belongs to session: foreign holder refuses (or waits with --wait); before service for immediate refusal
if [ "$mode" = gpu ]; then
  export INCOMING_GPU_OWNER="${INCOMING_GPU_OWNER:-$(lease_owner)}"
  # without --wait, immediate refusal; with --wait, lease queue waits for running client (lease_take)
  if [ -z "$wait" ] && who=$(gpu_client_busy); then echo "$who" >&2; echo "laptop_job $name: exit 75"; exit 75; fi
  lease_take "$INCOMING_GPU_OWNER" "$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)" $wait || { echo "laptop_job $name: exit 75"; exit 75; }
  # own lease but own session client still running (another worktree sandbox): --wait waits before service
  said=
  while [ -n "$wait" ] && who=$(gpu_client_busy); do
    [ -z "$said" ] && { echo "$(date +%T) $who — waiting" >&2; said=1; }
    sleep 10
  done
fi

# service environment contains only what the job needs (service does not inherit shell environment)
envs=()
while IFS= read -r line; do envs+=(-E "$line"); done < <(env | grep -E '^(JAVA_HOME|MC_DIR|MODS|INCOMING_[A-Z_]+|DELEGATE_[A-Z_]+|PYTHON_CPU_COUNT|TMPDIR|XDG_RUNTIME_DIR|XDG_STATE_HOME)=' || true)

# memory ceiling (_ceiling); OOMPolicy=stop: a kill inside the unit stops the whole job, --account reports it
oomfile="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/incoming-job-$name-$$.oom"
rm -f "$oomfile"
props=(-p OOMScoreAdjust=500 -p MemoryMax="$mem_max" -p MemorySwapMax="$mem_swap" -p OOMPolicy=stop -E INCOMING_JOB_OOM="$oomfile")
# agents.slice: one ceiling for all agent work (header); its oom count now, so --account tells a full slice from the unit's own
slice_cg=$(systemctl --user show agents.slice -p ControlGroup --value 2>/dev/null || true)
slice_mem=$(systemctl --user show agents.slice -p MemoryMax --value 2>/dev/null || true)
props+=(--slice=agents.slice -E INCOMING_SLICE_OOM="$(awk '$1 == "oom" { print $2 }' "/sys/fs/cgroup$slice_cg/memory.events" 2>/dev/null || echo 0)")
if [ "$mode" = soft ]; then
  # idle CPU class and the client's memory ceiling (soft_mem_cap, above): a CPU client never pushes the host into swap;
  # INCOMING_SOFT_UNIT tells scripts inside that the ceiling is already theirs
  source "$(dirname "$(readlink -f "$0")")/soft_gl.sh"
  props+=(-p CPUWeight=idle -p Nice=10 -E INCOMING_SOFT_UNIT=1)
  # admission and unit start under one lock: a second start counts this unit's ceiling as promised
  # fd 198 (199 is the GPU client lock): closed for the job itself, so the lock ends with this script's hold
  exec 198>"${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/incoming-soft-admit.lock"
  flock 198
  # admission for the ceiling this unit actually gets (INCOMING_JOB_MEM included), not the default estimate
  cap_mb=$(_mb "$mem_max") || { echo "laptop_job $name: memory ceiling '$mem_max' is not a size (INCOMING_JOB_MEM)" >&2; exit 2; }
  soft_mem_ok "$cap_mb" || { echo "laptop_job $name: exit 75"; exit 75; }
fi
# keep the laptop awake; a server without a session seat (netcup: "Access denied") has no sleep to inhibit
inhibit=(systemd-inhibit --what=sleep:idle --who="Incoming: $name" --why="mod check (tools/laptop_job.sh)")
"${inhibit[@]}" true 2>/dev/null || inhibit=()
# the delegated task's load: the unit's CPU goes to the load log at stop (--account); only names, never the command's
# arguments, reach the description and the log
code=0
"${inhibit[@]}" \
  systemd-run --user --wait --pipe --collect --quiet --same-dir --unit="incoming-job-$name-$$" \
    --description="incoming job $name (task ${DELEGATE_TASK:--})" -E INCOMING_JOB_START="$(date +%s)" \
    -p ExecStopPost="$(_sd_quote "$(readlink -f "$0")") --account" \
    "${props[@]}" "${envs[@]}" -- "$(readlink -f "$0")" --in-unit "$mode" "$name" $$ -- "$@" 0<&0 198>&- &
run=$!
# stopping this script (Ctrl+C, TERM from a caller's timeout) stops its service: the client does not outlive the command
trap 'systemctl --user stop "incoming-job-$name-$$" 2>/dev/null; wait $run; echo "laptop_job $name: stopped"; exit 143' TERM INT
if [ "$mode" = soft ]; then
  # hold the admission lock until the unit (and its MemoryMax) exists or the start failed: never while it is pending
  until systemctl --user is-active -q "incoming-job-$name-$$" || ! kill -0 $run 2>/dev/null; do sleep 0.1; done
  exec 198>&-
fi
wait $run || code=$?
if [ -s "$oomfile" ]; then
  read -r killed peak where < "$oomfile"
  if [ "$where" = slice ]; then
    echo "laptop_job $name: agent work memory (agents.slice: missions, delegate runs and laptop jobs together, MemoryMax $(awk -v b="$slice_mem" 'BEGIN { printf "%.0f GB", b / 1073741824 }')) is full: $killed process(es) killed inside its unit, peak $peak GB; job stopped (other agent work holds the memory: run it when that ends)"
  elif [ "$where" = host ]; then
    echo "laptop_job $name: out of memory on the laptop (below its ceiling $mem_max): $killed process(es) killed inside its unit, peak $peak GB; job stopped"
  else
    echo "laptop_job $name: memory ceiling $mem_max (+ $mem_swap swap) reached: $killed process(es) killed inside its unit, peak $peak GB; job stopped (laptop_job.sh --full or INCOMING_JOB_MEM for a larger job)"
  fi
  rm -f "$oomfile"
fi
# completion line for background waiter: exit code immediately visible, startup failure not lost
echo "laptop_job $name: exit $code"
exit $code
