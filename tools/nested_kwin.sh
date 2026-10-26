#!/usr/bin/env bash
# Windowless nested KWin (--virtual + Xwayland) for Minecraft clients: scenarios and trailer.
#
#   tools/nested_kwin.sh <socket> <width> <height> <command>
#
# Isolated from the desktop:
#   - own session D-Bus (dbus-run-session): on shared bus, nested KWin attaches to host kglobalaccel
#     under the same "kwin" component name and disables all its shortcuts (Alt+Tab, etc.) on exit;
#   - this bus starts no services (nested_kwin_bus.conf without D-Bus activation): XDG_RUNTIME_DIR is shared with
#     desktop, and xdg-document-portal started by it would unmount host $XDG_RUNTIME_DIR/doc, preventing flatpak apps
#     (Prism) from starting. Session only puts uniquely named sockets there (wayland-incoming-…);
#   - own XDG directories (config, data, cache, state) in mod/run/kwin/<socket>: KConfig reads and writes kwinrc
#     and others according to XDG Base Directory spec, leaving desktop settings untouched;
#   - no desktop window: --virtual backend renders to an offscreen buffer (GPU, through EGL), with no window
#     on the host screen at all;
#   - no speaker output: client cannot reach host sound server; PIPEWIRE_REMOTE and PULSE_SERVER point to
#     nonexistent sockets, so neither PipeWire, PulseAudio nor ALSA/JACK over PipeWire opens.
#     Scenarios needing sound (client_scenario.sh) write it via OpenAL Soft "wave" driver to a file
#     (ALSOFT_CONF); trailer assembles audio from the log; neither needs the server.
# Command (string) runs inside nested KWin session (--exit-with-session): KWin closes when it exits.
# One GPU (tools/gpu_lease.sh): client acquires session lease and "GPU client" lock, holding them
# while alive; foreign lease or running client refuses with 75 and holder details (INCOMING_GPU_WAIT=1 waits in lease queue).
set -euo pipefail
[ $# -eq 4 ] || { echo "usage: $0 <socket> <width> <height> <command>" >&2; exit 2; }
SOCKET="$1" WIDTH="$2" HEIGHT="$3" COMMAND="$4"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOME_DIR="$ROOT/mod/run/kwin/$SOCKET"
source "$ROOT/tools/gpu_lease.sh"
owner="${INCOMING_GPU_OWNER:-$(lease_owner "$ROOT")}"
# client lock first (otherwise refusal would leave a newly acquired lease for 10 min), then lease
# (with INCOMING_GPU_WAIT, lease queue waits for running client: lease_take)
if [ -z "${INCOMING_GPU_WAIT:-}" ] && who=$(gpu_client_busy); then echo "$who" >&2; exit 75; fi
# socket held by live compositor (libwayland flocks <socket>.lock): second KWin on the same name crashes the first
RT=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
if [ -e "$RT/$SOCKET.lock" ] && ! flock -n -s "$RT/$SOCKET.lock" true 2>/dev/null; then
  echo "socket $SOCKET is occupied by another KWin" >&2; exit 75
fi
lease_take "$owner" "$ROOT" ${INCOMING_GPU_WAIT:+--wait} || exit 75
# own lease but own session client still running: INCOMING_GPU_WAIT waits for it
while [ -n "${INCOMING_GPU_WAIT:-}" ] && gpu_client_busy >/dev/null; do sleep 10; done
gpu_client_hold "$owner" "$ROOT" "$COMMAND" || exit 75
mkdir -p "$HOME_DIR/config" "$HOME_DIR/data" "$HOME_DIR/cache" "$HOME_DIR/state"
exec env -u DISPLAY -u WAYLAND_DISPLAY -u DBUS_SESSION_BUS_ADDRESS \
    XDG_CONFIG_HOME="$HOME_DIR/config" XDG_DATA_HOME="$HOME_DIR/data" \
    XDG_CACHE_HOME="$HOME_DIR/cache" XDG_STATE_HOME="$HOME_DIR/state" \
    PIPEWIRE_REMOTE="$HOME_DIR/no-audio-server" PULSE_SERVER="unix:$HOME_DIR/no-audio-server" \
    dbus-run-session --config-file="$ROOT/tools/nested_kwin_bus.conf" -- \
    kwin_wayland --virtual --xwayland --socket "$SOCKET" --width "$WIDTH" --height "$HEIGHT" \
    --exit-with-session "$COMMAND"
