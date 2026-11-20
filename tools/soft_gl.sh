# CPU rendering (Mesa llvmpipe) for no-shader clients: no GPU, no lease, many at once beside the GPU client; the fallback of
# vanilla clients when no shared GPU slot is free (vanilla_display).
# Sourced by tools/nested_kwin.sh (KDE laptop) and tools/sandbox.sh, tools/client_scenario.sh (xvfb-run elsewhere:
# netcup, cloud). Frames match the GPU's for vanilla rendering (same GL 4.6 core pipeline, Mesa conformance);
# frame and GPU times do not: frame-cost questions go to the GPU (tools/stand, once on the final head).
#
#   soft_gl_env          exports the GL selection, thread and heap limits for the client; the heap
#                        (INCOMING_CLIENT_HEAP, read by build.gradle) must be exported before `gradlew rigLaunch`
#   soft_mem_ok          refuses (75, with numbers) when the machine cannot spare one more client: available memory
#                        minus the unused part of every running CPU client's ceiling must still hold this client's
#                        ceiling and the reserve (tools/laptop_job.sh --soft checks it again under a lock until its unit
#                        exists, so simultaneous starts cannot both spend the same memory)
#   tree_id [root]       unit and socket name of a worktree: its directory name plus a hash of its full path (two trees
#                        named alike in different places do not share a sandbox unit or a CPU display)
#   soft_display <name> <w> <h> <command>   runs the command on a CPU-only display: nested KWin on socket
#                        wayland-incoming-soft-<name> where KWin exists (tools/nested_kwin.sh with INCOMING_SOFT=1; the
#                        prefix is how tools/gpu_lease.sh tells it from a GPU client), otherwise xvfb-run; no host audio
#   vanilla_display <name> <w> <h> <command>   a vanilla client (no shader pack, DH or Voxy) on the GPU when a shared slot
#                        is free (tools/gpu_lease.sh gpu_slot_take: nested KWin on wayland-incoming-shared-<name>), else
#                        at once on the CPU (soft_display); never waits. Writes `gpu` or `soft` to INCOMING_RENDERER_FILE if
#                        set; Artem's game starting stops a GPU client (75, "Artem's game started"), as every GPU client.
#                        INCOMING_CPU_ONLY=1 keeps it on the CPU
#
# INCOMING_SOFT_HEAP: client heap (default 3g; 1536m fits netcup beside its servers: a flat sandbox used < 0.9 GB);
# INCOMING_SOFT_THREADS: llvmpipe raster threads (default half the CPUs, at most 8: two clients fit a 16-thread laptop
# beside Artem's game); INCOMING_SOFT_RESERVE_MB: memory left for everyone else after the client starts (default 4096,
# netcup's floor; the laptop keeps the same so Artem's game never swaps).

soft_gl_env() {
  local n; n=$(nproc 2>/dev/null || echo 4)
  export INCOMING_SOFT=1
  # GLVND: Mesa's GLX/EGL even where the X server names the NVIDIA vendor (Xwayland on the laptop)
  export __GLX_VENDOR_LIBRARY_NAME=mesa
  [ -e /usr/share/glvnd/egl_vendor.d/50_mesa.json ] && export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json
  export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
  # one compiled-shader cache for all trees and sockets: a cold llvmpipe compiles the nuclear volume shaders for 140 s
  export MESA_SHADER_CACHE_DIR="${MESA_SHADER_CACHE_DIR:-$HOME/.cache/incoming-mesa-shaders}"
  export LP_NUM_THREADS="${INCOMING_SOFT_THREADS:-$(( n / 2 > 8 ? 8 : (n / 2 < 1 ? 1 : n / 2) ))}"
  # the game JVM only (build.gradle clientScenario): JAVA_TOOL_OPTIONS would also size Gradle's JVMs inside the unit
  export INCOMING_CLIENT_HEAP="${INCOMING_SOFT_HEAP:-3g}"
}

# Memory ceiling of one vanilla client, MB: heap + 2 GB native (llvmpipe keeps textures and framebuffers in process memory;
# a flat-world sandbox with -Xmx3g peaked at 3.0 GB of unit memory on the CPU and 4.25 GB on a shared GPU slot, the NVIDIA
# driver's memory beside a grown heap, 20.11). tools/laptop_job.sh --soft sets it as the unit's MemoryMax without swap,
# so the worst case is known before start
soft_mem_cap() {
  local heap=${INCOMING_SOFT_HEAP:-3g}
  case "$heap" in *g|*G) heap=$(( ${heap%?} * 1024 )) ;; *m|*M) heap=${heap%?} ;; esac
  echo $(( heap + 2048 ))
}

# Unused part of the running CPU clients' ceilings, MB: units of laptop_job.sh --soft are the jobs with a MemoryMax
soft_mem_promised() {
  local u
  for u in $(systemctl --user list-units 'incoming-job-*' --state=active --no-legend --plain 2>/dev/null | awk '{print $1}'); do
    # key=value lines: `show --value` prints properties in systemd's order, not the order asked
    systemctl --user show "$u" -p MemoryMax -p MemoryCurrent 2>/dev/null; echo
  done | soft_mem_headroom
}

# MemoryMax=/MemoryCurrent= blocks (blank line between units) → MB still promised: units without a finite MemoryMax
# are not CPU clients
soft_mem_headroom() {
  awk -F= '/^MemoryMax=/ { max = $2 } /^MemoryCurrent=/ { cur = $2 }
    /^$/ { if (max ~ /^[0-9]+$/) { if (cur !~ /^[0-9]+$/) cur = 0; if (cur < max) sum += (max - cur) / 1048576 } max = ""; cur = "" }
    END { if (max ~ /^[0-9]+$/) { if (cur !~ /^[0-9]+$/) cur = 0; if (cur < max) sum += (max - cur) / 1048576 } printf "%d\n", sum }'
}

# Memory admission: the client's ceiling must leave the reserve free, after what running CPU clients may still take
soft_mem_ok() {
  local avail cap promised; cap=$(soft_mem_cap); promised=$(soft_mem_promised)
  avail=$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)
  if [ $(( avail - promised )) -lt $(( cap + ${INCOMING_SOFT_RESERVE_MB:-4096} )) ]; then
    echo "soft client refused: ${avail} MB available, ${promised} MB promised to running CPU clients; needs its ceiling ${cap} MB + reserve ${INCOMING_SOFT_RESERVE_MB:-4096} MB (INCOMING_SOFT_HEAP lowers the ceiling)" >&2
    return 75
  fi
}

tree_id() {
  local root; root=$(readlink -m "${1:-$(dirname "${BASH_SOURCE[0]}")/..}")
  # name cut to 40 bytes: wayland-incoming-soft-sandbox-<id> stays inside the 107-byte socket path limit
  local base; base=$(basename "$root"); base=${base:0:40}
  echo "$base-$(printf %s "$root" | md5sum | cut -c1-6)"
}

soft_display() {
  local socket=wayland-incoming-soft-$1 w=$2 h=$3 cmd=$4 root; root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  if command -v kwin_wayland >/dev/null; then
    INCOMING_SOFT=1 "$root/tools/nested_kwin.sh" "$socket" "$w" "$h" "$cmd"
  elif command -v xvfb-run >/dev/null; then
    soft_gl_env
    PIPEWIRE_REMOTE="$root/mod/run/no-audio-server" PULSE_SERVER="unix:$root/mod/run/no-audio-server" \
      nice -n "${INCOMING_SOFT_NICE:-10}" xvfb-run -a -s "-screen 0 ${w}x${h}x24" bash -c "$cmd"
  else
    echo "no CPU display: neither kwin_wayland nor xvfb-run" >&2; return 2
  fi
}

vanilla_display() {
  local name=$1 w=$2 h=$3 cmd=$4 root pc why err
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  # shellcheck source=gpu_lease.sh
  declare -F gpu_slot_take >/dev/null || source "$root/tools/gpu_lease.sh"
  if [ -n "${INCOMING_CPU_ONLY:-}" ]; then why="INCOMING_CPU_ONLY"
  elif ! command -v kwin_wayland >/dev/null; then why="no nested KWin"
  else
    # in this shell, not $(…): the slot lock (fd 197) must stay open for the client started below
    err=$(mktemp)
    if gpu_slot_take "$name" 2>"$err"; then why=; else why=$(cat "$err"); fi
    rm -f "$err"
  fi
  if [ -n "$why" ]; then
    echo "renderer: CPU (llvmpipe): $why"
    [ -n "${INCOMING_RENDERER_FILE:-}" ] && echo soft > "$INCOMING_RENDERER_FILE"
    soft_display "$@"
    return
  fi
  echo "renderer: GPU, shared slot $GPU_SLOT of $GPU_SLOTS"
  [ -n "${INCOMING_RENDERER_FILE:-}" ] && echo gpu > "$INCOMING_RENDERER_FILE"
  # the CPU selection of soft_gl_env (exported before Gradle by callers that may still land here) must not reach this client
  env -u INCOMING_SOFT -u LIBGL_ALWAYS_SOFTWARE -u GALLIUM_DRIVER -u __GLX_VENDOR_LIBRARY_NAME -u __EGL_VENDOR_LIBRARY_FILENAMES \
    -u LP_NUM_THREADS INCOMING_GPU_SHARED=1 "$root/tools/nested_kwin.sh" "wayland-incoming-shared-$name" "$w" "$h" "$cmd" &
  pc=$!
  # the slot stays with the client's session (fd 197 inherited); this shell lets it go
  exec 197>&-
  while kill -0 "$pc" 2>/dev/null; do
    if artem_playing; then
      echo "Artem's game started at $(date +%T): shared GPU client stopped"
      kill -TERM "$pc" 2>/dev/null; wait "$pc"
      return 75
    fi
    sleep "${INCOMING_GAME_POLL:-5}"
  done
  wait "$pc"
}
