#!/usr/bin/env bash
# Проверка песочницы эффектов без клиента (CI, «Остановка проверок»): `shot` ставит время суток до эффекта, а не после
# (23.10 кадры 0,1 и 0,5 с «ночи» были днём), и у каждого снимка своё (без --time — день); `shaders` и `dh` без своих
# модов в каталоге песочницы — отказ со строкой, а не съёмка без Iris или DH (23.10 FX снимал «под Iris» без Iris).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export INCOMING_GPU_LEASE=$tmp/lease
source "$ROOT/tools/sandbox.sh"
fail=0
ok() { echo "ok: $1"; }
bad() { echo "FAIL: $1"; fail=1; }

# команды shot — по порядку, без клиента: fx пишет их в журнал
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
[ "$(cat "$tmp/fx.log")" = "$want" ] && ok "shot: время до эффекта, без --time — день" || bad "shot — порядок команд:
$(cat "$tmp/fx.log")"

# режим и моды: shaders — Sodium и Iris, dh — Distant Horizons; без режима — ничего не нужно
mkdir -p "$tmp/mods"
( unset INCOMING_SHADERS INCOMING_DH; mods_check "$tmp/mods" ) && ok "без shaders и dh модов не нужно" || bad "без режима — отказ"
out=$(INCOMING_SHADERS=1 mods_check "$tmp/mods" 2>&1); rc=$?
[ $rc = 1 ] && [[ $out == *"sodium-neoforge-*.jar"*"iris-neoforge-*.jar"* ]] && ok "shaders без Iris и Sodium — отказ: $out" || bad "shaders без модов: $rc $out"
touch "$tmp/mods/sodium-neoforge-0.8.13+mc1.21.1.jar"
out=$(INCOMING_SHADERS=1 mods_check "$tmp/mods" 2>&1); rc=$?
[ $rc = 1 ] && [[ $out == *"iris-neoforge"* && $out != *sodium* ]] && ok "shaders без Iris — отказ" || bad "shaders без Iris: $rc $out"
touch "$tmp/mods/iris-neoforge-1.8.14-beta.1+mc1.21.1.jar"
INCOMING_SHADERS=1 mods_check "$tmp/mods" && ok "shaders с Iris и Sodium — годится" || bad "shaders с модами — отказ"
out=$(INCOMING_SHADERS=1 INCOMING_DH=1 mods_check "$tmp/mods" 2>&1); rc=$?
[ $rc = 1 ] && [[ $out == *"DistantHorizons-*.jar"* ]] && ok "dh без Distant Horizons — отказ" || bad "dh без DH: $rc $out"
exit $fail
