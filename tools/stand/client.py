"""Клиент стенда: каталог игры и запуск — то, от чего зависят числа (входит в версию стенда, ключ кэша — stand.py).

Каталог игры собирается с нуля на каждый пак: Incoming, Sodium, Iris, шейдерпак, настройки, план. Клиент идёт во
вложенном KWin (tools/nested_kwin.sh: без окна на рабочем столе, без звука хоста, аренда видеокарты) своей сессией
процессов с меткой в окружении: остановка гасит всё с меткой (TERM, через 20 с KILL) — и обёртки, и JVM, — не только
группу обёртки (JVM — внук dbus-run-session и kwin). Только стандартная библиотека.
"""
import json
import os
import shlex
import shutil
import signal
import subprocess
import time
import uuid
from pathlib import Path

import plan

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SOCKET = "wayland-incoming-stand"
# запас высоты экрана вложенного KWin на рамку окна
DECOR = 40

# настройки игры — одни у всех версий: без vsync и предела к/с, прорисовка 12, частицы «все», красивая графика
OPTIONS = """onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:12
simulationDistance:8
enableVsync:false
maxFps:260
particles:0
graphicsMode:1
fullscreen:false
guiScale:2
soundCategory_master:1.0
soundCategory_music:0.0
tutorialStep:none
joinedFirstServer:true
skipMultiplayerWarning:true
narrator:0
"""


def launch_script(game, size):
    """Скрипт клиента без Gradle (`standLaunch` в build.gradle): каталог игры — game, окно — size (Ш×В); назад — путь.
    Тот же java, аргументы, classpath и окружение, что у runClient; Gradle выходит до замера."""
    w, h = size.split("x")
    env = dict(os.environ)
    env.setdefault("JAVA_HOME", str(Path.home() / ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta"))
    subprocess.run([str(ROOT / "mod" / "gradlew"), "-p", str(HERE), "standLaunch", f"-PrunDir={game}", f"-Pwidth={w}", f"-Pheight={h}",
                    "--console=plain", "-q"], check=True, env=env)
    return HERE / "build" / "launch" / "client.sh"


def java_of(script):
    """Java, на которой идёт клиент (из скрипта standLaunch): версия из файла release рядом с ней — в ключ кэша (JDK
    лаунчера обновляется сам, а с ним и время кадра)."""
    try:
        words = shlex.split(Path(script).read_text().splitlines()[-1])
        java = next(Path(x) for x in words if x.endswith("/bin/java"))
        rel = dict(line.split("=", 1) for line in (java.parent.parent / "release").read_text().splitlines() if "=" in line)
        version = (rel.get("JAVA_RUNTIME_VERSION") or rel["JAVA_VERSION"]).strip('"')
        return " ".join([version, rel.get("IMPLEMENTOR", "").strip('"')]).strip()
    except (OSError, IndexError, KeyError, StopIteration, ValueError):
        return "unknown"


def server_toml(cfg):
    """Правила мира стенда ({раздел: {ключ: значение}}) в TOML настроек сервера Incoming: недостающие ключи NeoForge
    допишет значениями по умолчанию."""
    lines = []
    for section, values in cfg.items():
        lines.append(f"[{section}]")
        lines += [f"\t{k} = {json.dumps(v)}" for k, v in values.items()]
    return "\n".join(lines) + "\n"


def prepare_game(game, jar, pack_file, mods, scenes_sel):
    """Каталог игры с нуля: моды, шейдерпак (None — Iris без пака), настройки игры и правила мира, план; план — назад."""
    if game.exists():
        for sub in ("saves", "stand-out", "mods", "shaderpacks", "config", "logs", "crash-reports", "screenshots"):
            shutil.rmtree(game / sub, ignore_errors=True)
        for f in ("options.txt", "audio.wav", "stand-plan.json"):
            (game / f).unlink(missing_ok=True)
    (game / "mods").mkdir(parents=True)
    (game / "config").mkdir()
    shutil.copy2(jar, game / "mods" / jar.name)
    for m in mods:
        shutil.copy2(m, game / "mods" / m.name)
    if pack_file:
        (game / "shaderpacks").mkdir()
        shutil.copy2(pack_file, game / "shaderpacks" / pack_file.name)
        iris = f"enableShaders=true\nshaderPack={pack_file.name}\ndisableUpdateMessage=true\n"
    else:
        iris = "enableShaders=false\ndisableUpdateMessage=true\n"
    (game / "config" / "iris.properties").write_text(iris)
    scenes = plan.load()
    # настройки сервера NeoForge 21.1 — в config/ каталога игры (serverconfig мира их только переопределяет)
    (game / "config" / "incoming-server.toml").write_text(server_toml(scenes.get("server_config", {})))
    (game / "options.txt").write_text(OPTIONS)
    (game / "alsoft.conf").write_text(f"[general]\ndrivers = wave\nfrequency = 48000\nchannels = stereo\nsample-type = int16\n"
                                      f"[wave]\nfile = {game / 'audio.wav'}\n")
    p = plan.build(scenes, scenes_sel)
    (game / "stand-plan.json").write_text(json.dumps(p, indent=1, ensure_ascii=False))
    return p


def artem_playing():
    return subprocess.run(["bash", "-c", f"source '{ROOT}/tools/gpu_lease.sh' && artem_playing"]).returncode == 0


def tagged(tag):
    """Процессы с меткой стенда в окружении."""
    out = []
    for d in Path("/proc").iterdir():
        if not d.name.isdigit():
            continue
        try:
            if f"STAND_TAG={tag}".encode() in (d / "environ").read_bytes().split(b"\0"):
                out.append(int(d.name))
        except OSError:
            pass
    return out


def kill_tagged(tag, grace=20):
    """Всё с меткой: TERM, ждать до grace секунд, остальным KILL; назад — сколько осталось (0 — все ушли)."""
    for sig, wait in ((signal.SIGTERM, grace), (signal.SIGKILL, 5)):
        pids = tagged(tag)
        if not pids:
            return 0
        for pid in pids:
            try:
                os.kill(pid, sig)
            except OSError:
                pass
        end = time.time() + wait
        while time.time() < end and tagged(tag):
            time.sleep(0.5)
    return len(tagged(tag))


def _term(*_):
    # TERM задачи (laptop_job останавливают) — как Ctrl+C: finally ниже гасит клиента
    raise KeyboardInterrupt


def run(game, script, size, limit_s, logf, log):
    """Клиент во вложенном KWin; игра Артёма запустилась — гасим, 75; не уложился в срок — гасим, 124. В конце (и при
    исключении, Ctrl+C, TERM задачи) — ни одного процесса с меткой."""
    w, h = size.split("x")
    tag = uuid.uuid4().hex
    env = dict(os.environ, ALSOFT_CONF=str(game / "alsoft.conf"), STAND_TAG=tag,
               # кэш шейдеров драйвера выключен: первый удар у каждого прогона — со сборкой шейдеров, как у свежей
               # игры, а не с тем, что оставил прошлый прогон
               __GL_SHADER_DISK_CACHE="0")
    old = signal.signal(signal.SIGTERM, _term)
    # экран вложенного KWin выше окна на рамку: окно 2560×1600 с заголовком (28 px у Breeze) помещается целиком, и кадр
    # игры — ровно заданный (на экране той же высоты KWin ужимал окно до 2560×1572)
    proc = subprocess.Popen([str(ROOT / "tools" / "nested_kwin.sh"), SOCKET, w, str(int(h) + DECOR), f"bash {script}"], stdout=logf,
                            stderr=subprocess.STDOUT, env=env, start_new_session=True)
    deadline = time.time() + limit_s + 600
    try:
        while True:
            try:
                return proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                pass
            if artem_playing():
                log("игра Артёма запустилась — гашу клиент")
                return 75
            if time.time() > deadline:
                log("клиент не уложился в срок — гашу")
                return 124
    finally:
        left = kill_tagged(tag)
        if left:
            log(f"после клиента осталось процессов с меткой: {left}")
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        signal.signal(signal.SIGTERM, old)
