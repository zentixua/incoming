#!/usr/bin/env bash
# Effects sandbox check without a client (CI, "Остановка проверок"): `shot` sets time before the effect, not after
# (23.10: 0,1 and 0,5 s "night" frames were daytime), separately for each shot (without --time, day); `shaders` and `dh`
# without their mods in the sandbox directory refuse with a message, rather than capturing without Iris or DH (23.10: FX captured "under Iris" without Iris);
# on a CPU renderer `shot` records sound in its own unslowed take; CPU client memory ceiling, admission, GL environment
# and socket naming (tools/soft_gl.sh).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export INCOMING_GPU_LEASE=$tmp/lease
source "$ROOT/tools/sandbox.sh"
fail=0
ok() { echo "ok: $1"; }
bad() { echo "FAIL: $1"; fail=1; }

# shot commands in sequence, without a client: fx logs them
FX=$tmp/fx
fx() { echo "$1" >> "$tmp/fx.log"; }
swap() { echo swap >> "$tmp/fx.log"; }
cut_wav() { :; }
( shot n1 'blast drone' --time night --no-swap ) >/dev/null 2>&1
( shot n2 'flight missile' --at 0.5 --wav 2 ) >/dev/null 2>&1
want='clear
time night
overlay off
shots n1 0.1,0.5,1,2,4 4
blast drone
swap
clear
time day
overlay off
shots n2 0.5 2
flight missile'
[ "$(cat "$tmp/fx.log")" = "$want" ] && ok "shot: time before effect, default day without --time" || bad "shot command order:
$(cat "$tmp/fx.log")"

# Modes and mods: shaders needs Sodium and Iris, dh needs Distant Horizons; no mode needs no mods
mkdir -p "$tmp/mods"
( unset INCOMING_SHADERS INCOMING_DH; mods_check "$tmp/mods" ) && ok "without shaders and dh, no mods required" || bad "refused without a mode"
out=$(INCOMING_SHADERS=1 mods_check "$tmp/mods" 2>&1); rc=$?
[ $rc = 1 ] && [[ $out == *"sodium-neoforge-*.jar"*"iris-neoforge-*.jar"* ]] && ok "shaders without Iris and Sodium refused: $out" || bad "shaders without mods: $rc $out"
touch "$tmp/mods/sodium-neoforge-0.8.13+mc1.21.1.jar"
out=$(INCOMING_SHADERS=1 mods_check "$tmp/mods" 2>&1); rc=$?
[ $rc = 1 ] && [[ $out == *"iris-neoforge"* && $out != *sodium* ]] && ok "shaders without Iris refused" || bad "shaders without Iris: $rc $out"
touch "$tmp/mods/iris-neoforge-1.8.14-beta.1+mc1.21.1.jar"
INCOMING_SHADERS=1 mods_check "$tmp/mods" && ok "shaders with Iris and Sodium accepted" || bad "shaders refused with mods"
out=$(INCOMING_SHADERS=1 INCOMING_DH=1 mods_check "$tmp/mods" 2>&1); rc=$?
[ $rc = 1 ] && [[ $out == *"DistantHorizons-*.jar"* ]] && ok "dh without Distant Horizons refused" || bad "dh without DH: $rc $out"

# CPU renderer (tools/soft_gl.sh): frames in a paced take without sound, sound in a second take of the same effect
: > "$tmp/fx.log"; mkdir -p "$FX/out/n3"; echo soft > "$FX/renderer"
( shot n3 'blast drone' --at 0.5,1 --wav 2 --no-swap ) >/dev/null 2>&1
grep -qx 'shots n3 0.5,1 0' "$tmp/fx.log" && grep -qx 'shots n3-wav none 2' "$tmp/fx.log" && [ "$(grep -cx 'blast drone' "$tmp/fx.log")" = 2 ] \
  && [ "$(grep -n -x 'clear' "$tmp/fx.log" | tail -1 | cut -d: -f1)" -lt "$(grep -n 'shots n3-wav' "$tmp/fx.log" | cut -d: -f1)" ] \
  && ok "CPU shot: frames take without sound, cleared, then a sound take" || bad "CPU shot: $(tr '\n' ';' < "$tmp/fx.log")"
: > "$tmp/fx.log"; echo gpu > "$FX/renderer"
( shot n4 'blast drone' --at 0.5 --wav 2 --no-swap ) >/dev/null 2>&1
[ "$(grep -c '^shots' "$tmp/fx.log")" = 1 ] && grep -qx 'shots n4 0.5 2' "$tmp/fx.log" && ok "GPU shot: one take with sound" || bad "GPU shot: $(tr '\n' ';' < "$tmp/fx.log")"

source "$ROOT/tools/soft_gl.sh"
[ "$(INCOMING_SOFT_HEAP=3g soft_mem_cap)" = 4608 ] && [ "$(INCOMING_SOFT_HEAP=1536m soft_mem_cap)" = 3072 ] && ok "CPU client ceiling: heap + 1.5 GB" || bad "soft_mem_cap"
out=$(INCOMING_SOFT_RESERVE_MB=100000000 soft_mem_ok 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"soft client refused"* ]] && ok "CPU client refused when memory is short" || bad "soft_mem_ok: $rc $out"
( soft_gl_env; [ "$__GLX_VENDOR_LIBRARY_NAME" = mesa ] && [ "$LIBGL_ALWAYS_SOFTWARE" = 1 ] && [ -n "$INCOMING_CLIENT_HEAP" ] && [ "$LP_NUM_THREADS" -ge 1 ] ) \
  && ok "CPU client env: Mesa vendor, software GL, fixed heap" || bad "soft_gl_env"
# gpu_lease.sh tells a CPU session by its socket: nested_kwin refuses any other name on the CPU path, before KWin
out=$(INCOMING_SOFT=1 "$ROOT/tools/nested_kwin.sh" wayland-incoming-sandbox 64 64 true 2>&1); rc=$?
[ $rc = 2 ] && [[ $out == *"wayland-incoming-soft-"* ]] && ok "CPU session needs a soft socket name" || bad "nested_kwin soft socket: $rc $out"
exit $fail
