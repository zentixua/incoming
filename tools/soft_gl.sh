# CPU rendering (Mesa llvmpipe) for no-shader clients: no GPU, no lease, many at once beside the GPU client.
# Sourced by tools/nested_kwin.sh (KDE laptop) and tools/sandbox.sh, tools/client_scenario.sh (xvfb-run elsewhere:
# netcup, cloud). Frames match the GPU's for vanilla rendering (same GL 4.6 core pipeline, Mesa conformance);
# frame and GPU times do not: frame-cost questions go to the GPU (tools/stand, once on the final head).
#
#   soft_gl_env          exports the GL selection, thread and heap limits for the client
#   soft_mem_ok          refuses (75, with numbers) when the machine cannot spare one more client
#   soft_display <socket> <w> <h> <command>   runs the command on a CPU-only display: nested KWin where it exists
#                        (tools/nested_kwin.sh with INCOMING_SOFT=1), otherwise xvfb-run; no host audio either way
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
  export LP_NUM_THREADS="${INCOMING_SOFT_THREADS:-$(( n / 2 > 8 ? 8 : (n / 2 < 1 ? 1 : n / 2) ))}"
  # JVMs inside (the client; Gradle too in client_scenario.sh) get a fixed heap instead of a quarter of the machine
  export JAVA_TOOL_OPTIONS="-Xmx${INCOMING_SOFT_HEAP:-3g}${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}"
}

# Memory ceiling of one CPU client, MB: heap + 1.5 GB native (llvmpipe keeps textures and framebuffers in process memory;
# a flat-world sandbox with -Xmx3g peaked at 3.0 GB of unit memory, heap use under 0.9 GB). tools/laptop_job.sh --soft
# sets it as the unit's MemoryMax without swap, so the worst case is known before start
soft_mem_cap() {
  local heap=${INCOMING_SOFT_HEAP:-3g}
  case "$heap" in *g|*G) heap=$(( ${heap%?} * 1024 )) ;; *m|*M) heap=${heap%?} ;; esac
  echo $(( heap + 1536 ))
}

# Memory admission: the client's ceiling must leave the reserve free
soft_mem_ok() {
  local avail cap; cap=$(soft_mem_cap)
  avail=$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)
  if [ "$avail" -lt $(( cap + ${INCOMING_SOFT_RESERVE_MB:-4096} )) ]; then
    echo "soft client refused: ${avail} MB available, needs its ceiling ${cap} MB + reserve ${INCOMING_SOFT_RESERVE_MB:-4096} MB (INCOMING_SOFT_HEAP lowers the ceiling)" >&2
    return 75
  fi
}

soft_display() {
  local socket=$1 w=$2 h=$3 cmd=$4 root; root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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
