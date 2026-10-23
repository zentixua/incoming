#!/usr/bin/env bash
# Песочница эффектов: клиент только с модом (по желанию — Iris с шейдерпаком хоста, DH) в плоском мире, без окна
# (tools/nested_kwin.sh --virtual), живёт, пока его не остановят; эффект играется командой за секунды, код меняется
# горячей заменой, ресурсы — перезагрузкой, без нового клиента. Сценарий — Sandbox (mod/src/devtest), команды —
# SandboxCommand; каталог клиента — mod/run/sandbox, кадры и звук — mod/run/sandbox/fx/out/<имя>/.
#
#   tools/sandbox.sh start [shaders] [dh] [soft] [--wait] [--size 1280x720]   клиент в фоне (задача ноутбука
#                                          sandbox-<дерево>): берёт аренду видеокарты своей сессии (tools/gpu_lease.sh) —
#                                          она у другой сессии: выход 75 со строкой «видеокарта у …» (--wait — в очередь
#                                          аренды, по порядку прихода); нет Iris и Sodium (shaders), DH (dh) — выход 2;
#                                          при игре Артёма — выход 75; выходит, когда мир готов; живёт, пока не stop или
#                                          игра Артёма, и продлевает аренду; чужие задачи без видеокарты её не гасят;
#                                          soft — без видеокарты и без аренды (xvfb-run, llvmpipe: 3–10 кадров/с, без
#                                          шейдеров; облако)
#   tools/sandbox.sh fx '<команда>' …      команды /fx по очереди (blast drone, cam 60 10 90 8, time night, clear, …),
#                                          каждая ждёт своего итога
#   tools/sandbox.sh swap                  собрать изменённое (Gradle), горячая замена классов, перезагрузка ресурсов,
#                                          если они менялись; код 3 — замена не встала, нужен restart
#   tools/sandbox.sh shot <имя> '<эффект>' [--at 0.1,0.5,1,3] [--wav 4] [--cam 'дальн выс азимут взгляд'] [--time day|night]
#                        [--overlay] [--no-swap]
#                                          swap, потом эффект с кадрами на эти секунды и звуком: <имя>_*.png, <имя>.wav,
#                                          capture.json; в конце — сколько секунд от команды до последнего кадра.
#                                          Время — у каждого снимка своё: --time, по умолчанию day (полдень).
#                                          Ядерка у камеры: shot wall 'nuke 15 near' --cam '60 15 0 5' --at -0.5,0.1,1 —
#                                          дома сцены debris, время — от прихода фронта к ним (client-scenario.md)
#   tools/sandbox.sh restart [опции start] остановить и запустить заново (после правки, которую замена не берёт)
#   tools/sandbox.sh jbr                   поставить JetBrains Runtime (общий для всех деревьев, mod/run/jbr главного):
#                                          с ним замена берёт и новые поля, методы, лямбды; start берёт его сам
#   tools/sandbox.sh stop | status          закончил с картинкой — stop, потом tools/gpu_lease.sh release
#
# INCOMING_SANDBOX_JAVA=<java> — своя JVM песочницы (JetBrains Runtime — с -XX:+AllowEnhancedClassRedefinition).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/mod/run/sandbox"
FX="$RUN/fx"
UNIT="incoming-job-sandbox-$(basename "$ROOT")"
# JetBrains Runtime — один на все деревья: в mod/run главного дерева (у дерева git-common-dir — .git главного)
JBR_DIR="$(cd "$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)/mod/run/jbr"
JBR_BUILD=jbr-21.0.11-linux-x64-b1163.116
jbr_java() { local j="$JBR_DIR/$JBR_BUILD/bin/java"; [ -x "$j" ] && echo "$j"; }
export JAVA_HOME="${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}"

# аренда видеокарты (tools/gpu_lease.sh): владелец — сессия по дереву; там же artem_playing — игра Артёма по его
# инстансу (не по пути в командной строке: с MC_DIR на инстанс песочница несёт его в путях модов и гасила бы себя)
source "$(dirname "$0")/gpu_lease.sh"
export INCOMING_GPU_OWNER="${INCOMING_GPU_OWNER:-$(lease_owner "$ROOT")}"
running() { [ -n "$(systemctl --user list-units "$UNIT-*" --state=active --no-legend --plain)" ]; }

# одна команда /fx: файл в inbox, ждать итога (*.done); вывод — итог, код 1 — ошибка
fx() {
  local id done res limit=${FX_LIMIT:-3600} t=0
  id=$(date +%s%N)
  mkdir -p "$FX/inbox"
  printf '%s\n' "$1" > "$FX/inbox/$id.tmp" && mv "$FX/inbox/$id.tmp" "$FX/inbox/$id.cmd"
  done="$FX/inbox/$id.done"
  while [ ! -f "$done" ]; do
    running || { echo "песочница не запущена (tools/sandbox.sh start)" >&2; return 1; }
    sleep 0.1; t=$((t + 1))
    [ $t -gt $((limit * 10)) ] && { echo "нет итога за $limit с: $1" >&2; return 1; }
  done
  res=$(cat "$done"); rm -f "$done"
  echo "fx $1 → $res"
  [[ $res == ok* ]]
}

# моды, без которых выбранный режим не тот, что заказан: shaders — Sodium и Iris, dh — Distant Horizons (шаблоны — как
# в build.gradle); нет — строка, чего нет и откуда их берёт Gradle, код 1
mods_check() {
  local dir=$1 need=() miss=() p
  [ -n "${INCOMING_SHADERS:-}" ] && need+=('sodium-neoforge-*.jar' 'iris-neoforge-*.jar')
  [ -n "${INCOMING_DH:-}" ] && need+=('DistantHorizons-*.jar')
  for p in "${need[@]}"; do compgen -G "$dir/$p" >/dev/null || miss+=("$p"); done
  [ ${#miss[@]} -eq 0 ] && return 0
  echo "нет модов для режима ($(IFS=' '; echo "${miss[*]}")) в $dir: Gradle берёт их из $(python3 "$ROOT/tools/paths.py" MODS 2>/dev/null || echo 'каталога модов инстанса') (MC_DIR или -PmcModsDir) — положить туда или запустить без shaders/dh" >&2
  return 1
}

# секунды с отметки времени $1 (date +%s.%N)
since() { awk -v a="$1" -v b="$(date +%s.%N)" 'BEGIN { printf "%.1f", b - a }'; }

hotswap_n() { tail -n 1 "$FX/hotswap" 2>/dev/null | cut -d' ' -f1; }

swap() {
  local stamp n0 classes res t0 done_ms w=0
  t0=$(date +%s.%N)
  stamp=$(mktemp -p "$RUN" .swap.XXXX)
  n0=$(hotswap_n); n0=${n0:-0}
  (cd "$ROOT/mod" && INCOMING_SANDBOX=1 ./gradlew compileJava compileDevtestJava processResources processDevtestResources -q --console=plain) || { rm -f "$stamp"; return 2; }
  done_ms=$(date +%s%3N)
  classes=$(find "$ROOT/mod/build/classes/java/main" "$ROOT/mod/build/classes/java/devtest" -name '*.class' -newer "$stamp" | wc -l)
  res=$(find "$ROOT/mod/build/resources/main" "$ROOT/mod/build/resources/devtest" -type f -newer "$stamp" 2>/dev/null | wc -l)
  rm -f "$stamp"
  if [ "$classes" -gt 0 ]; then
    # агент догнал сборку: обход, начатый после конца Gradle, ничего не ждёт замены (HotswapAgent, *.idle)
    until [ "$(cat "$FX/hotswap.idle" 2>/dev/null || echo 0)" -ge "$done_ms" ]; do
      sleep 0.1; w=$((w + 1)); [ $w -gt 300 ] && { echo "агент замены не догнал сборку за 30 с" >&2; return 3; }
      running || { echo "песочница не запущена" >&2; return 1; }
    done
    # все замены этой сборки: Gradle пишет main и devtest двумя задачами — замен бывает две, и отказ — в любой
    local mine; mine=$(awk -v n="$n0" '$1 > n' "$FX/hotswap")
    echo "$mine" | cut -c1-300 | sed 's/^/замена: /'
    grep -q '^[0-9]* fail' <<< "$mine" && { echo "замена не встала — tools/sandbox.sh restart" >&2; return 3; }
  fi
  if [ "$res" -gt 0 ]; then fx reload || return 1; fi
  echo "swap: классов $classes, ресурсов $res, $(since "$t0") с"
}

# кусок audio.wav клиента от начала эффекта (capture.json) в отдельный WAV
cut_wav() {
  python3 - "$1" "$2" <<'PY'
import json, struct, sys, wave
cap = json.load(open(sys.argv[1]))
a = cap["audio"]
if a["seconds"] <= 0 or a["start"] < 0:
    sys.exit(0)
with open(a["file"], "rb") as f:
    data = f.read()
pos, fmt, start = 12, None, None
while pos + 8 <= len(data):
    cid, size = data[pos:pos + 4], struct.unpack("<I", data[pos + 4:pos + 8])[0]
    if cid == b"fmt ":
        fmt = struct.unpack("<HHIIHH", data[pos + 8:pos + 24])
    if cid == b"data":
        start = pos + 8
        break
    pos += 8 + size + (size & 1)
_, channels, rate, _, align, bits = fmt
off = a["start"] - (a["start"] - start) % align
n = int(a["seconds"] * rate) * align
chunk = data[off:off + n]
with wave.open(sys.argv[2], "wb") as w:
    w.setnchannels(channels)
    w.setsampwidth(bits // 8)
    w.setframerate(rate)
    w.writeframes(chunk)
print(f"звук: {sys.argv[2]} ({len(chunk) / align / rate:.1f} с, {rate} Гц)")
PY
}

start() {
  local size=1280x720 opts=() pack wait= fresh=
  while [ $# -gt 0 ]; do
    case "$1" in
      --wait) wait=--wait ;;
      shaders) export INCOMING_SHADERS=1 ;;
      dh) export INCOMING_DH=1 ;;
      soft) export INCOMING_SANDBOX_SOFT=1 ;;
      --size) size=$2; shift ;;
      *) echo "неизвестный параметр: $1" >&2; exit 2 ;;
    esac
    shift
  done
  running && { echo "песочница уже идёт ($UNIT)"; exit 0; }
  # JetBrains Runtime, если поставлен (tools/sandbox.sh jbr): замена берёт и новые поля и методы
  if [ -z "${INCOMING_SANDBOX_JAVA:-}" ] && jbr_java >/dev/null; then export INCOMING_SANDBOX_JAVA="$(jbr_java)"; fi
  unset INCOMING_SANDBOX_ENHANCED
  if [ -n "${INCOMING_SANDBOX_JAVA:-}" ] && "$INCOMING_SANDBOX_JAVA" -XX:+AllowEnhancedClassRedefinition -version >/dev/null 2>&1; then
    export INCOMING_SANDBOX_ENHANCED=1
  fi
  local swaps="только тела методов"; [ -n "${INCOMING_SANDBOX_ENHANCED:-}" ] && swaps="и новые поля, методы, лямбды"
  echo "JVM: ${INCOMING_SANDBOX_JAVA:-тулчейн Gradle}; замена — $swaps"
  if [ -z "${INCOMING_SANDBOX_SOFT:-}" ]; then
    artem_playing && { echo "игра Артёма запущена — видеокарту не берём" >&2; exit 75; }
    # с картинкой — одна сессия за раз: аренда у другой — отказ (её песочницу и задачи не гасим); клиент на видеокарте
    # уже идёт (своя песочница другого дерева, свой шлагбаум) — тоже отказ, до сборки. С --wait — в очередь аренды
    # (tools/gpu_lease.sh: свободную берёт первый ждущий), потом — своего клиента, если он ещё идёт
    local who said=
    _lease_read
    fresh=1; [ "$L_OWNER" = "$INCOMING_GPU_OWNER" ] && ! _lease_free && fresh=
    if [ -z "$wait" ] && who=$(gpu_client_busy); then echo "$who" >&2; exit 75; fi
    lease_take "$INCOMING_GPU_OWNER" "$ROOT" $wait || exit 75
    # не поднялись (сборка, клиент) — только что взятую аренду отпустить: другие сессии не ждут 10 мин впустую
    [ -n "$fresh" ] && trap 'lease_release "$INCOMING_GPU_OWNER"' EXIT
    while who=$(gpu_client_busy); do
      [ -n "$wait" ] || { echo "$who" >&2; exit 75; }
      [ -z "$said" ] && { echo "$(date +%T) $who — жду" >&2; said=1; }
      sleep 10
    done
  elif [ -n "${INCOMING_SHADERS:-}" ]; then
    echo "soft — без шейдеров (llvmpipe)" >&2; exit 2
  elif ! command -v xvfb-run >/dev/null; then
    # LIBGL_ALWAYS_SOFTWARE драйвер NVIDIA не слушает: во вложенном KWin клиент взял бы видеокарту
    echo "soft — только с xvfb-run (облако); здесь его нет" >&2; exit 2
  fi
  local t0; t0=$(date +%s.%N)
  mkdir -p "$RUN/logs" "$FX"
  rm -f "$FX/ready" "$FX/stopped"
  cat > "$RUN/alsoft.conf" <<CONF
[general]
drivers = wave
frequency = 48000
channels = stereo
sample-type = int16
[wave]
file = $RUN/audio.wav
CONF
  [ -f "$RUN/options.txt" ] || cat > "$RUN/options.txt" <<OPT
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:12
simulationDistance:8
guiScale:2
soundCategory_master:1.0
soundCategory_ambient:1.0
soundCategory_music:0.0
tutorialStep:none
joinedFirstServer:true
OPT
  mkdir -p "$RUN/config"
  if [ -n "${INCOMING_SHADERS:-}" ]; then
    local MC; MC="$(python3 "$ROOT/tools/paths.py" MC)"
    pack="$(sed -n 's/^shaderPack=//p' "$MC/config/iris.properties" 2>/dev/null)"
    # без шейдерпака песочница шла бы молча без него — кадры «под Iris» были бы без пака
    [ -n "$pack" ] && [ -e "$MC/shaderpacks/$pack" ] \
      || { echo "shaders: нет шейдерпака инстанса ($MC: iris.properties «$pack») — tools/paths.py или MC_DIR" >&2; exit 2; }
    mkdir -p "$RUN/shaderpacks"
    rm -rf "${RUN:?}/shaderpacks/$pack"
    cp -r "$MC/shaderpacks/$pack" "$RUN/shaderpacks/"
    printf 'enableShaders=true\nshaderPack=%s\ndisableUpdateMessage=true\n' "$pack" > "$RUN/config/iris.properties"
  fi
  (cd "$ROOT/mod" && INCOMING_SANDBOX=1 INCOMING_SCENARIO=sandbox INCOMING_SIZE=$size ./gradlew rigLaunch -PrigRun=runClientScenario \
      -PrigOut=sandbox -q --console=plain) || exit 1
  # моды берёт Gradle из каталога модов инстанса по шаблонам (build.gradle) и молча пропускает те, которых там нет:
  # без этой проверки `shaders` снимал бы без Iris, `dh` — без Distant Horizons
  mods_check "$RUN/mods" || exit 2
  local build; build=$(since "$t0")
  local w=${size%x*} h=${size#*x}
  INCOMING_SANDBOX=1 nohup "$ROOT/tools/laptop_job.sh" --light "sandbox-$(basename "$ROOT")" -- "$ROOT/tools/sandbox.sh" _run "$w" "$h" > "$RUN/logs/job.log" 2>&1 &
  local job=$! waited=0
  # задача пошла: аренду держит её клиент; обрыв этого ожидания (Ctrl+C, срок) аренду у живого клиента не отнимает —
  # отпустить, только если задача сама не поднялась
  trap - EXIT
  until [ -f "$FX/ready" ]; do
    sleep 0.2; waited=$((waited + 1))
    # клиент жив, но встал до мира (чужой экран, шейдерпак) — не ждать без конца: последний экран — строка SANDBOX screen
    if [ -f "$FX/stopped" ] || ! kill -0 $job 2>/dev/null || [ $waited -gt 1500 ]; then
      echo "клиент не поднялся за $((waited / 5)) с:" >&2
      grep 'SANDBOX screen' "$RUN/logs/latest.log" 2>/dev/null | tail -n 1 >&2
      tail -n 20 "$RUN/logs/latest.log" "$RUN/logs/job.log" >&2
      [ $waited -gt 1500 ] && stop >&2
      # задача не поднялась или остановлена — только что взятую аренду отпустить
      if [ -n "$fresh" ] && { [ $waited -gt 1500 ] || ! kill -0 $job 2>/dev/null; }; then lease_release "$INCOMING_GPU_OWNER"; fi
      exit 1
    fi
  done
  echo "песочница готова: сборка $build с, клиент $(cat "$FX/ready") с (JVM → мир), всего $(since "$t0") с"
}

# внутри задачи ноутбука: клиент во вложенном KWin; гасится только игрой Артёма (машина общая) и stop
run_client() {
  if [ -n "${INCOMING_SANDBOX_SOFT:-}" ]; then
    # без видеокарты и без звука хоста, как путь xvfb-run в rigs.md (облако)
    PIPEWIRE_REMOTE="$RUN/no-audio-server" PULSE_SERVER="unix:$RUN/no-audio-server" LIBGL_ALWAYS_SOFTWARE=1 \
      xvfb-run -a -s "-screen 0 ${1}x${2}x24" bash "$ROOT/mod/build/rig/sandbox.sh" > "$RUN/logs/client.out" 2>&1 &
  else
    "$ROOT/tools/nested_kwin.sh" wayland-incoming-sandbox "$1" "$2" "bash $ROOT/mod/build/rig/sandbox.sh" > "$RUN/logs/client.out" 2>&1 &
  fi
  local pc=$!
  # аренду, пока клиент жив, продлевает nested_kwin.sh (gpu_client_hold)
  while kill -0 $pc 2>/dev/null; do
    if [ -z "${INCOMING_SANDBOX_SOFT:-}" ] && artem_playing; then echo "$(date +%T) игра Артёма запустилась — гашу песочницу" | tee "$FX/stopped"; kill -TERM $pc; wait $pc; exit 75; fi
    sleep 5
  done
  wait $pc
}

# JetBrains Runtime 21 (DCEVM: -XX:+AllowEnhancedClassRedefinition) с проверкой SHA-512 от JetBrains
jbr() {
  if jbr_java >/dev/null; then echo "уже стоит: $(jbr_java)"; return 0; fi
  local url="https://cache-redirector.jetbrains.com/intellij-jbr/$JBR_BUILD.tar.gz" dl
  dl=$(mktemp -d -p "$(dirname "$JBR_DIR")" .jbr-dl.XXXX) || return 1
  curl -fsSL -o "$dl/jbr.tar.gz" "$url" && curl -fsSL -o "$dl/sum" "$url.checksum" || { rm -rf "$dl"; return 1; }
  if [ "$(cut -d' ' -f1 "$dl/sum")" != "$(sha512sum "$dl/jbr.tar.gz" | cut -d' ' -f1)" ]; then
    echo "SHA-512 не сходится — не ставлю" >&2; rm -rf "$dl"; return 1
  fi
  mkdir -p "$JBR_DIR" && tar -xzf "$dl/jbr.tar.gz" -C "$JBR_DIR" && rm -rf "$dl"
  "$(jbr_java)" -XX:+AllowEnhancedClassRedefinition -version 2>&1 | head -1 && echo "поставлен: $(jbr_java)"
}

stop() {
  running || { echo "песочница не запущена"; return 0; }
  systemctl --user stop "$UNIT-*"
  echo "песочница остановлена"
}

shot() {
  local name=$1 effect=$2 at="0.1,0.5,1,2,4" wav=4 cam="" time=day overlay=off doswap=1 t0
  shift 2
  while [ $# -gt 0 ]; do
    case "$1" in
      --at) at=$2; shift ;;
      --wav) wav=$2; shift ;;
      --cam) cam=$2; shift ;;
      --time) time=$2; shift ;;
      --overlay) overlay=on ;;
      --no-swap) doswap=0 ;;
      *) echo "неизвестный параметр: $1" >&2; exit 2 ;;
    esac
    shift
  done
  t0=$(date +%s.%N)
  if [ $doswap = 1 ]; then swap || exit $?; fi
  fx clear || exit 1
  [ -n "$cam" ] && { fx "cam $cam" || exit 1; }
  # время — у каждого снимка своё (по умолчанию день): смена суток в мире песочницы выключена, и ночь прошлого снимка
  # держалась бы в следующем; `time` кончается, когда время пришло к клиенту
  fx "time $time" || exit 1
  fx "overlay $overlay" || exit 1
  fx "shots $name $at $wav" || exit 1
  fx "$effect" || exit 1
  local out="$FX/out/$name"
  cut_wav "$out/capture.json" "$out/$name.wav"
  ls "$out"/*.png | sed 's/^/кадр: /'
  echo "от команды до последнего кадра и звука: $(since "$t0") с"
}

# source tools/sandbox.sh — только функции (проверка tools/test_sandbox.sh)
[ "${BASH_SOURCE[0]}" = "$0" ] || return 0
cmd=${1:-}
shift || true
case "$cmd" in
  start) start "$@" ;;
  restart) stop; sleep 2; start "$@" ;;
  stop) stop ;;
  jbr) jbr ;;
  status) if running; then echo "идёт; готов за $(cat "$FX/ready" 2>/dev/null) с; замена: $(cat "$FX/hotswap" 2>/dev/null)"; else echo "не запущена"; fi; lease_status || true ;;
  fx) for c in "$@"; do fx "$c" || exit 1; done ;;
  swap) swap ;;
  shot) [ $# -ge 2 ] || { echo "shot <имя> '<эффект>' [--at …] [--wav …] [--cam …]" >&2; exit 2; }; shot "$@" ;;
  _run) run_client "$@" ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac
