# Check processes (stress.sh, mp_scenario.sh): each server and client has its own session (setsid); stop the entire
# session. Client wrappers (xvfb-run, nested_kwin.sh → dbus-run-session → kwin_wayland) hold the JVM as a grandchild: kill
# of the wrapper leaves Minecraft orphaned; terminal Ctrl+C never reaches a JVM in its own session, so rig_stop_all stops it.
# Source this file; the caller installs traps (rig_trap).

RIG_SESSIONS=()
# Environment tag on all launch processes (carried by exec env, xvfb-run, dbus-run-session and KWin): shutdown leaves a session
# with a reused PID/session number alone if it lacks this tag
RIG_TAG="INCOMING_RIG_TAG=$$.$RANDOM"
# Messages go to the script terminal, not the .out file receiving rig_spawn output
exec {RIG_ERR}>&2 || exec {RIG_ERR}>/dev/null # No stderr (closed): discard messages and keep the script running

# rig_spawn <command…>: background, new session; RIG_LAST is the PID and session ID (for wait and kill -0).
# Without job control a background process is not a group leader, so setsid creates the session in place without fork.
rig_spawn() {
  # Do not inherit the message fd: after script KILL, orphans would keep its terminal, tee or ssh open
  env "$RIG_TAG" setsid "$@" {RIG_ERR}>&- &
  RIG_LAST=$!
  RIG_SESSIONS+=("$RIG_LAST")
  local sid=""
  for _ in $(seq 50); do
    sid=$(ps -o sid= -p "$RIG_LAST" 2>/dev/null | tr -d ' ') || sid=""
    [ "$sid" = "$RIG_LAST" ] && return 0
    [ -z "$sid" ] && return 0 # Already exited: the caller checks with kill -0 and reports the problem
    sleep 0.1
  done
  echo "rig: $1 is outside its own session (sid $sid); shutdown will not find its children" >&$RIG_ERR || true
  return 1
}

# Session processes with our tag (empty if absent or the ID now belongs to another session)
rig_members() {
  local pid
  for pid in $(pgrep -s "$1" 2>/dev/null); do
    grep -qzxF "$RIG_TAG" "/proc/$pid/environ" 2>/dev/null && echo "$pid"
  done
  return 0
}

# Seconds before KILL: RIG_GRACE (test uses 2); empty or non-numeric means 20
rig_grace() {
  local g=${RIG_GRACE:-20}
  [[ $g =~ ^[0-9]+$ ]] || g=20
  echo $((10#$g))
}

# rig_stop <sid…>: TERM the entire session (JVM can save the world), KILL remaining processes after rig_grace s.
rig_stop() {
  local sid live=() left grace
  grace=$(rig_grace)
  for sid in "$@"; do [ -n "$(rig_members "$sid")" ] && live+=("$sid"); done
  [ ${#live[@]} -eq 0 ] && return 0
  for sid in "${live[@]}"; do pkill -TERM -s "$sid" 2>/dev/null || true; done
  left=("${live[@]}") # Timeout 0: KILL immediately
  local deadline=$((SECONDS + grace))
  while [ $SECONDS -lt $deadline ]; do
    left=()
    for sid in "${live[@]}"; do [ -n "$(rig_members "$sid")" ] && left+=("$sid"); done
    [ ${#left[@]} -eq 0 ] && return 0
    sleep 0.2
  done
  for sid in "${left[@]}"; do
    # KILL first: writing to a closed terminal (or dead tee) must not cancel it
    local names
    names=$(ps -o comm= -s "$sid" 2>/dev/null | sort -u | tr '\n' ' ') || names=""
    pkill -KILL -s "$sid" 2>/dev/null || true
    echo "rig: session $sid did not exit within $grace s after TERM — KILL: $names" >&$RIG_ERR || true
  done
}

rig_stop_all() {
  # A second Ctrl+C during shutdown must not interrupt it before KILL
  trap '' INT TERM HUP PIPE
  if [ ${#RIG_SESSIONS[@]} -gt 0 ]; then
    [ -n "${RIG_SIG:-}" ] && { echo "rig: stopping runs (up to $(rig_grace) s)…" >&$RIG_ERR || true; }
    rig_stop "${RIG_SESSIONS[@]}"
  fi
  # Exit by the same signal: a wrapper loop and systemd see interruption, not an error exit code
  if [ -n "${RIG_SIG:-}" ]; then
    trap - "$RIG_SIG" EXIT
    kill -"$RIG_SIG" $$
  fi
}

# Every exit (end, set -e error, Ctrl+C, script kill) shuts down all sessions
rig_trap() {
  trap rig_stop_all EXIT
  trap 'RIG_SIG=INT; exit 130' INT
  trap 'RIG_SIG=TERM; exit 143' TERM
  trap 'RIG_SIG=HUP; exit 129' HUP
}
