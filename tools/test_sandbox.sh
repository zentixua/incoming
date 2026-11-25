#!/usr/bin/env bash
# Effects sandbox check without a client (CI, "Остановка проверок"): `shot` sets time before the effect, not after
# (23.10: 0,1 and 0,5 s "night" frames were daytime), separately for each shot (without --time, day); `shaders` and `dh`
# without their mods in the sandbox directory refuse with a message, rather than capturing without Iris or DH (23.10: FX captured "under Iris" without Iris);
# a frame take with sound records the sound in its own unslowed take (frames are paced on any renderer), `--at none` only
# that take; CPU client memory ceiling, admission, GL environment
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
eval "$(declare -f cut_wav | sed '1s/^cut_wav/real_cut_wav/')"
cut_wav() { :; }
# (no sound: a frame take with sound runs a second take, checked below)
( shot n1 'blast drone' --time night --wav 0 --no-swap ) >/dev/null 2>&1
( shot n2 'flight missile' --at 0.5 --wav 0 ) >/dev/null 2>&1
want='clear
time night
overlay off
shots n1 0.1,0.5,1,2,4 0
blast drone
swap
clear
time day
overlay off
shots n2 0.5 0
flight missile'
[ "$(cat "$tmp/fx.log")" = "$want" ] && ok "shot: time before effect, default day without --time" || bad "shot command order:
$(cat "$tmp/fx.log")"

# options.txt always holds renderClouds (an edit of a missing line silently did nothing), and a later start keeps edits
client_options "$tmp/options.txt"; sed -i 's/^renderClouds:.*/renderClouds:"false"/' "$tmp/options.txt"; client_options "$tmp/options.txt"
[ "$(grep -c '^renderClouds:' "$tmp/options.txt")" = 1 ] && grep -q '^renderClouds:"false"$' "$tmp/options.txt" \
  && ok "options: renderClouds present, edits kept" || bad "options.txt renderClouds: $(grep renderClouds "$tmp/options.txt")"
printf 'guiScale:2\n' > "$tmp/old-options.txt"; client_options "$tmp/old-options.txt"
grep -q '^renderClouds:"true"$' "$tmp/old-options.txt" && ok "options: an old sandbox gets the line" || bad "old options.txt without renderClouds"

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

# CPU renderer (tools/soft_gl.sh): frames in a paced take without sound, sound in a second take of the same effect from
# the first frame's second, in a staging directory of its own (an existing shot named like it stays); the WAV is cut
# from that take and its timing goes into the frames' capture.json; a missing sound take fails the shot
cut_wav() { real_cut_wav "$@"; }
python3 - "$tmp/audio.wav" <<'PY'
import sys, wave
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(48000); w.writeframes(b"\0" * 4 * 48000 * 3)
PY
AUDIO=$tmp/audio.wav
fx() {
  echo "$1" >> "$tmp/fx.log"
  echo "${FX_LIMIT:-} $1" >> "$tmp/limit.log"
  if [[ $1 == "shots "* ]]; then
    local a=($1); mkdir -p "$FX/out/${a[1]}"
    printf '{"name": "%s", "audio": {"file": "%s", "start": 44, "from": 0, "seconds": %s}, "frames": []}\n' "${a[1]}" "$AUDIO" "${a[3]:-0}" > "$FX/out/${a[1]}/capture.json"
  fi
}
: > "$tmp/fx.log"; mkdir -p "$FX/out/n3-wav"; echo soft > "$FX/renderer"; touch "$FX/out/n3-wav/keep.png"
( shot n3 'nuke 15 near' --at -0.5,1 --wav 2 --no-swap ) >/dev/null 2>&1; rc=$?
take=$(sed -n 's/^shots \(_wav-n3-[A-Za-z0-9]*\) none@-0.5 2$/\1/p' "$tmp/fx.log")
[ $rc = 0 ] && grep -qx 'shots n3 -0.5,1 0' "$tmp/fx.log" && [ -n "$take" ] && [ "$(grep -cx 'nuke 15 near' "$tmp/fx.log")" = 2 ] \
  && [ "$(grep -n -x 'clear' "$tmp/fx.log" | tail -1 | cut -d: -f1)" -lt "$(grep -n "shots $take" "$tmp/fx.log" | cut -d: -f1)" ] \
  && ok "CPU shot: frames take without sound, cleared, then a sound take from -0.5 s ($take)" || bad "CPU shot: rc $rc $(tr '\n' ';' < "$tmp/fx.log")"
python3 -c 'import wave,sys; w=wave.open(sys.argv[1]); sys.exit(0 if abs(w.getnframes()/w.getframerate()-2)<0.01 else 1)' "$FX/out/n3/n3.wav" 2>/dev/null \
  && ok "CPU shot: WAV cut from the sound take" || bad "CPU shot: no 2 s WAV"
python3 -c 'import json,sys; a=json.load(open(sys.argv[1]))["audio"]; sys.exit(0 if a["seconds"]==2 and a["take"].startswith("separate") else 1)' "$FX/out/n3/capture.json" 2>/dev/null \
  && ok "CPU shot: sound take timing in the frames' capture.json" || bad "CPU shot capture.json: $(cat "$FX/out/n3/capture.json")"
[ -f "$FX/out/n3-wav/keep.png" ] && [ ! -d "$FX/out/$take" ] && ok "CPU shot: another shot named n3-wav kept, staging removed" || bad "CPU shot staging: $(ls "$FX/out")"
AUDIO=$tmp/missing.wav
( shot n5 'blast drone' --at 0.5 --wav 2 --no-swap ) >/dev/null 2>&1; rc=$?
[ $rc != 0 ] && ok "CPU shot without a readable sound take fails" || bad "CPU shot with a broken sound take returned 0"
AUDIO=$tmp/audio.wav
# a client that recorded no sound (audio.start -1) fails the shot instead of a silent success
python3 -c 'import json,sys; json.dump({"audio": {"file": sys.argv[1], "start": -1, "from": 0, "seconds": 2}}, open(sys.argv[2], "w"))' "$AUDIO" "$tmp/nosound.json"
real_cut_wav "$tmp/nosound.json" "$tmp/nosound.wav" 2>/dev/null && bad "cut_wav without recorded sound returned 0" || ok "cut_wav fails when the client recorded no sound"
# the deadline reads the same last frame as Java ("600," is "600"): 20 wall s per game s on a CPU renderer
: > "$tmp/limit.log"
( shot n6 'blast drone' --at 1,600, --wav 0 --no-swap ) >/dev/null 2>&1
grep -qx '15600 blast drone' "$tmp/limit.log" && ok "CPU shot deadline from the last frame, trailing comma too" || bad "CPU deadline: $(tr '\n' ';' < "$tmp/limit.log")"
# sound only (--at none) on a CPU renderer: no frame take, one sound take (its quiet window is Sandbox.quiet, in the
# client), WAV and capture.json in the shot's directory on a fresh tree (no fx/out yet)
rm -rf "$FX/out"; : > "$tmp/fx.log"
( shot n7 'real missile' --at none --wav 2 --no-swap ) >/dev/null 2>&1; rc=$?
[ $rc = 0 ] && [ "$(grep -c '^shots' "$tmp/fx.log")" = 1 ] && grep -qE '^shots _wav-n7-[A-Za-z0-9]+ none@0 2$' "$tmp/fx.log" \
  && [ "$(grep -cx 'real missile' "$tmp/fx.log")" = 1 ] && ok "CPU sound-only shot: one sound take, no frame take" || bad "CPU sound-only shot: rc $rc $(tr '\n' ';' < "$tmp/fx.log")"
python3 -c 'import json,sys,wave; w=wave.open(sys.argv[1]); a=json.load(open(sys.argv[2])); sys.exit(0 if abs(w.getnframes()/w.getframerate()-2)<0.01 and a["frames"]==[] and a["audio"]["seconds"]==2 else 1)' \
  "$FX/out/n7/n7.wav" "$FX/out/n7/capture.json" 2>/dev/null && ok "CPU sound-only shot: WAV and capture.json" || bad "CPU sound-only shot output: $(ls "$FX/out" "$FX/out/n7" 2>&1 | tr '\n' ' ')"
: > "$tmp/fx.log"; echo gpu > "$FX/renderer"
# GPU frames are paced too (Sandbox.paceFrame): frames and sound in separate takes, as on a CPU renderer
( shot n4 'blast drone' --at 0.5 --wav 2 --no-swap ) >/dev/null 2>&1
grep -qx 'shots n4 0.5 0' "$tmp/fx.log" && [ "$(grep -cE '^shots _wav-n4-[A-Za-z0-9]+ none@0 2$' "$tmp/fx.log")" = 1 ] \
  && ok "GPU shot: frames take without sound, then a sound take" || bad "GPU shot: $(tr '\n' ';' < "$tmp/fx.log")"
# every take name sent to the client is one its `shots` accepts (SandboxCommand: not starting with a dot), and a first
# frame with a time of day gives the sound take a plain second
: > "$tmp/fx.log"
( shot n9 'blast drone' --at -0.5@12500,1 --wav 2 --no-swap ) >/dev/null 2>&1
bad_names=$(sed -n 's/^shots \([^ ]*\) .*/\1/p' "$tmp/fx.log" | grep -vE '^[A-Za-z0-9_][A-Za-z0-9_.-]*$')
[ -z "$bad_names" ] && grep -qE '^shots _wav-n9-[A-Za-z0-9]+ none@-0.5 2$' "$tmp/fx.log" && ok "sound take: a name and second the client accepts" \
  || bad "sound take names: $(tr '\n' ';' < "$tmp/fx.log")"
# sound only on a GPU: nothing is paced, one take with sound
: > "$tmp/fx.log"
( shot n8 'blast drone' --at none --wav 2 --no-swap ) >/dev/null 2>&1
[ "$(grep -c '^shots' "$tmp/fx.log")" = 1 ] && grep -qx 'shots n8 none 2' "$tmp/fx.log" && ok "GPU sound-only shot: one take" || bad "GPU sound-only shot: $(tr '\n' ';' < "$tmp/fx.log")"

# review probe: an empty shot name never replaces fx/out, and any valid effect text keeps capture.json valid
out=$(bash "$ROOT/tools/probes/sound-only-output.sh" "$ROOT" 2>&1) && ok "probe sound-only-output" || bad "probe sound-only-output: $out"

source "$ROOT/tools/soft_gl.sh"
long=$(printf 'x%.0s' $(seq 80))
[ "$(tree_id /a/wt-main)" != "$(tree_id /b/wt-main)" ] && [[ $(tree_id /a/wt-main) == wt-main-* ]] && [ ${#long} = 80 ] && [ "$(tree_id "/a/$long" | wc -c)" -le 48 ] && ok "tree id: alike names in different places differ" || bad "tree_id"
[ "$(printf 'MemoryCurrent=1048576000\nMemoryMax=4831838208\n\nMemoryCurrent=5890895872\nMemoryMax=infinity\n\nMemoryMax=3221225472\nMemoryCurrent=[not set]\n' | soft_mem_headroom)" = 6680 ] \
  && ok "promised memory: unused ceilings of CPU clients only, in either property order" || bad "soft_mem_headroom"
[ "$(INCOMING_SOFT_HEAP=3g soft_mem_cap)" = 5120 ] && [ "$(INCOMING_SOFT_HEAP=1536m soft_mem_cap)" = 3584 ] && ok "vanilla client ceiling: heap + 2 GB" || bad "soft_mem_cap"
out=$(INCOMING_SOFT_RESERVE_MB=100000000 soft_mem_ok 2>&1); rc=$?
[ $rc = 75 ] && [[ $out == *"soft client refused"* ]] && ok "CPU client refused when memory is short" || bad "soft_mem_ok: $rc $out"
( soft_gl_env; [ "$__GLX_VENDOR_LIBRARY_NAME" = mesa ] && [ "$LIBGL_ALWAYS_SOFTWARE" = 1 ] && [ -n "$INCOMING_CLIENT_HEAP" ] && [ "$LP_NUM_THREADS" -ge 1 ] ) \
  && ok "CPU client env: Mesa vendor, software GL, fixed heap" || bad "soft_gl_env"
# gpu_lease.sh tells a CPU session by its socket: nested_kwin refuses any other name on the CPU path, before KWin
out=$(INCOMING_SOFT=1 "$ROOT/tools/nested_kwin.sh" wayland-incoming-sandbox 64 64 true 2>&1); rc=$?
[ $rc = 2 ] && [[ $out == *"wayland-incoming-soft-"* ]] && ok "CPU session needs a soft socket name" || bad "nested_kwin soft socket: $rc $out"
# Idle limit: a client whose job ended without `stop` (no command for INCOMING_SANDBOX_IDLE s) stops itself, its JVM
# first (the client JVM is a grandchild of the display wrapper), and says why; a command keeps it alive; 0 never stops
(
  source "$ROOT/tools/sandbox.sh"
  RUN=$tmp/idle-run FX=$tmp/idle-run/fx
  # fake JVM: a script named java (with a direct #!/bin/bash its process name is java); a copy of sleep fails where coreutils is one multicall
  # binary (GitHub runners: "coreutils: unknown program 'java'")
  mkdir -p "$RUN/logs" "$FX" "$tmp/bin"
  printf '#!/bin/bash\ntrap '"'"'kill $s 2>/dev/null; exit 0'"'"' TERM\nsleep "$1" & s=$!\nwait $s\n' > "$tmp/bin/java"; chmod +x "$tmp/bin/java"
  export INCOMING_SANDBOX_VANILLA=1 INCOMING_SANDBOX_POLL=0.2 INCOMING_SANDBOX_IDLE=60
  vanilla_display() { "$tmp/bin/java" 300 & wait; }
  jpid() { pgrep -f "$tmp/bin/java 300" | head -1; }
  ago() { touch -d "@$(( $(date +%s) - $1 ))" "${@:2}"; }
  # the fake JVM itself must run, or every case below passes or fails for nothing
  "$tmp/bin/java" 0 || echo "FAIL: fake JVM does not run: $(ls -l "$tmp/bin/java") $(findmnt -no OPTIONS -T "$tmp" 2>&1) $(command -v sleep)"
  # idle since the world became ready two hours ago: stops within a poll
  ago 7200 "$FX/ready"; rm -f "$FX/active" "$FX/stopped"
  run_client 1280 720 & r=$!
  for _ in $(seq 100); do kill -0 $r 2>/dev/null || break; sleep 0.1; done
  kill -0 $r 2>/dev/null && { kill $(jpid) $r; echo "FAIL: idle sandbox still running after 10 s"; }
  wait $r; rc=$?
  [ $rc = 0 ] && [ -z "$(jpid)" ] && grep -q 'no command for 120 min (INCOMING_SANDBOX_IDLE=60 s): sandbox stopped' "$FX/stopped" \
    && echo "ok: idle sandbox stops itself, JVM included, and says why" || echo "FAIL: idle stop: rc $rc java $(jpid) $(cat "$FX/stopped" 2>&1)"
  # a recent command keeps it; the command aging past the limit stops it
  rm -f "$FX/stopped"; touch "$FX/active"
  run_client 1280 720 & r=$!
  sleep 1.5
  if [ -n "$(jpid)" ] && kill -0 $r 2>/dev/null; then echo "ok: a recent command keeps the sandbox"
  else echo "FAIL: stopped despite a recent command: java '$(jpid)', run_client $(kill -0 $r 2>/dev/null && echo alive || echo gone), idle $(idle_seconds), stopped '$(cat "$FX/stopped" 2>/dev/null)', client.out '$(cat "$RUN/logs/client.out" 2>/dev/null)', $(ps -eo pid,ppid,stat,args | grep -F "$tmp" | grep -v grep)"; fi
  ago 120 "$FX/active"
  for _ in $(seq 50); do kill -0 $r 2>/dev/null || break; sleep 0.1; done
  kill -0 $r 2>/dev/null && { echo "FAIL: not stopped after its last command aged"; kill $(jpid) $r; } || echo "ok: stops once the last command ages past the limit"
  # 0: never stops
  rm -f "$FX/stopped"; INCOMING_SANDBOX_IDLE=0 run_client 1280 720 & r=$!
  sleep 1
  kill -0 $r 2>/dev/null && [ ! -f "$FX/stopped" ] && echo "ok: INCOMING_SANDBOX_IDLE=0 never stops" || echo "FAIL: IDLE=0 stopped"
  kill $(jpid) 2>/dev/null; wait $r 2>/dev/null
  # every command marks activity; a command finding the client gone tells why it stopped
  running() { true; }
  ago 7200 "$FX/active"
  ( for _ in $(seq 50); do for c in "$FX"/inbox/*.cmd; do [ -f "$c" ] && { echo ok > "${c%.cmd}.done"; rm -f "$c"; exit; }; done; sleep 0.1; done ) &
  fx 'blast drone' >/dev/null
  [ "$(idle_seconds)" -le 2 ] && echo "ok: a command marks the sandbox active" || echo "FAIL: fx did not mark activity: $(idle_seconds)"
  # a long command (a nuke shot runs for minutes) keeps marking while it waits: answered after 13 s, fx/active is
  # younger than the wait
  ( sleep 13; for c in "$FX"/inbox/*.cmd; do echo ok > "${c%.cmd}.done"; rm -f "$c"; done ) &
  fx 'nuke 15' >/dev/null &
  sleep 0.5; ago 7200 "$FX/active"; wait
  [ "$(idle_seconds)" -le 6 ] && echo "ok: a running command keeps marking the sandbox active" || echo "FAIL: long command not marked: $(idle_seconds)"
  running() { false; }
  echo "12:00:00 no command for 30 min (INCOMING_SANDBOX_IDLE=1800 s): sandbox stopped" > "$FX/stopped"
  out=$(fx 'blast drone' 2>&1)
  [[ $out == *"sandbox is not running"*"no command for 30 min"* ]] && echo "ok: a command after the idle stop says why" || echo "FAIL: not-running message: $out"
) > "$tmp/idle.log" 2>&1
cat "$tmp/idle.log"
grep -q '^FAIL' "$tmp/idle.log" && fail=1
[ "$(grep -c '^ok:' "$tmp/idle.log")" = 7 ] || { echo "FAIL: idle checks incomplete"; fail=1; }
exit $fail
