#!/usr/bin/env bash
# Effects sandbox check without a client (CI, "Остановка проверок"): `shot` sets time before the effect, not after
# (23.10: 0,1 and 0,5 s "night" frames were daytime), separately for each shot (without --time, day); `shaders` and `dh`
# without their mods in the sandbox directory refuse with a message, rather than capturing without Iris or DH (23.10: FX captured "under Iris" without Iris).
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
exit $fail
