#!/usr/bin/env bash
# Heavy job on the host laptop (scenario clients and sandbox, Blender, trailer editing, bench):
#
#   tools/laptop_job.sh [--gpu [--wait] | --light | --soft] <name> -- <command …>
#   tools/laptop_job.sh --status            ← running jobs, GPU holder, jobs waiting for Artem's game
#
# Job runs in its own transient systemd --user service (systemd-run --wait --collect), outside its caller's
# group (e.g. Claude app): on exit everything left in the group stops (no stranded nested-session D-Bus
# helpers), and if the system still runs out of memory, this job goes first (OOMScoreAdjust), not Artem's
# programs. Laptop stays awake while job runs (systemd-inhibit). No memory limits.
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
#   --soft   CPU-rendered client (tools/soft_gl.sh): immediate, no lease, idle CPU priority (CPUWeight=idle: Artem's game
#            and every normal process go first), keeps running while Artem plays
set -euo pipefail

# Artem's game: artem_playing (gpu_lease.sh), java in his Prism instance directory
source "$(dirname "$(readlink -f "$0")")/gpu_lease.sh"

if [ "${1:-}" = --status ]; then
  lease_status || true
  systemctl --user list-units 'incoming-job-*' --state=active --no-legend --plain | awk '{print "  " $1}'
  artem_playing && echo "Artem's game is running: --gpu jobs wait"
  exit 0
fi

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

mode=heavy wait=
case "${1:-}" in --gpu) mode=gpu; shift ;; --light) mode=light; shift ;; --soft) mode=soft; shift ;; esac
[ "$mode" = gpu ] && [ "${1:-}" = --wait ] && { wait=--wait; shift; }
[ $# -ge 3 ] && [ "$2" = "--" ] || { echo "usage: $0 [--gpu [--wait]|--light|--soft] <name> -- <command …> | --status" >&2; exit 2; }
name=$1; shift 2

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
while IFS= read -r line; do envs+=(-E "$line"); done < <(env | grep -E '^(JAVA_HOME|MC_DIR|MODS|INCOMING_[A-Z_]+|PYTHON_CPU_COUNT|XDG_RUNTIME_DIR)=' || true)

props=(-p OOMScoreAdjust=500)
[ "$mode" = soft ] && props+=(-p CPUWeight=idle -p Nice=10)
code=0
systemd-inhibit --what=sleep:idle --who="Incoming: $name" --why="mod check (tools/laptop_job.sh)" \
  systemd-run --user --wait --pipe --collect --quiet --same-dir --unit="incoming-job-$name-$$" \
    "${props[@]}" "${envs[@]}" -- "$(readlink -f "$0")" --in-unit "$mode" "$name" $$ -- "$@" || code=$?
# completion line for background waiter: exit code immediately visible, startup failure not lost
echo "laptop_job $name: exit $code"
exit $code
