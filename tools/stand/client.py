"""Клиент стенда: каталог игры и запуск — то, от чего зависят числа (входит в версию стенда, ключ кэша — stand.py).

Каталог игры собирается с нуля на каждый пак: Incoming, Sodium, Iris, шейдерпак, настройки, план. Клиент идёт во
вложенном KWin (tools/nested_kwin.sh: без окна на рабочем столе, без звука хоста, аренда видеокарты) своей сессией
процессов с меткой в окружении: остановка гасит всё с меткой (TERM, через 20 с KILL) — и обёртки, и JVM, — не только
группу обёртки (JVM — внук dbus-run-session и kwin). Рядом с клиентом каждые 5 с пишется нагрузка машины (`Host`,
host.jsonl): чужая — всё, что не своя cgroup задачи; перед клиентом — ждать тишины. Только стандартная библиотека.
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
import results

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


# Как драйвер NVIDIA ждёт видеокарту в вызовах OpenGL (когда упор в неё): по умолчанию — sched_yield() в цикле, то есть
# крутится, и это ожидание идёт в время процессора потока отрисовки (cpu_thread_ms) — оно мерило бы видеокарту, а не
# процессор; USLEEP — usleep(0): поток спит (README драйвера, «__GL_YIELD»). Одно для всех версий; в ключе кэша
# (stand.py context); INCOMING_STAND_GL_YIELD="" — как у драйвера по умолчанию (для проверки, крутится ли он)
GL_YIELD = os.environ.get("INCOMING_STAND_GL_YIELD", "USLEEP")
SAMPLE_S = 5
# перед клиентом ждать тишины: столько выборок подряд без тяжёлой чужой нагрузки (10 с: долгая задача видна сразу, до окон
# шлагбаума у клиента ещё ~5 мин), не дольше QUIET_WAIT_S
QUIET_SAMPLES, QUIET_WAIT_S = 2, 600


class Host:
    """Нагрузка машины за время между выборками: ядра процессора всего и своей cgroup (задача ноутбука: в ней клиент,
    его KWin и JVM), чужие = всё − свои, по службам рядом (кто), видеокарта чужими процессами (% SM по nvidia-smi pmon,
    свои — из своей cgroup), её температура и частота, профиль питания, load average. Без nvidia-smi — без видеокарты."""

    def __init__(self):
        self.cg = self._own_cgroup()
        self.prev = None

    @staticmethod
    def _own_cgroup():
        try:
            for line in Path("/proc/self/cgroup").read_text().splitlines():
                if line.startswith("0::"):
                    return Path("/sys/fs/cgroup") / line.split("::", 1)[1].strip().lstrip("/")
        except OSError:
            pass
        return None

    @staticmethod
    def _busy_s():
        """Процессор, занятый всей машиной с загрузки, с (user+nice+system+irq+softirq+steal)."""
        v = [int(x) for x in Path("/proc/stat").read_text().splitlines()[0].split()[1:9]]
        return (v[0] + v[1] + v[2] + v[5] + v[6] + v[7]) / os.sysconf("SC_CLK_TCK")

    @staticmethod
    def _usage_s(cg):
        try:
            for line in (cg / "cpu.stat").read_text().splitlines():
                if line.startswith("usage_usec "):
                    return int(line.split()[1]) / 1e6
        except OSError:
            pass
        return None

    def _units(self):
        """Службы рядом со своей (app.slice): имя → процессор с их запуска, с."""
        out = {}
        if self.cg is None:
            return out
        for d in self.cg.parent.iterdir():
            if d.is_dir() and d != self.cg:
                u = self._usage_s(d)
                if u is not None:
                    out[d.name] = u
        return out

    def _own_pid(self, pid):
        """Процесс — в своей cgroup (путь его cgroup v2 совпадает со своим)."""
        try:
            path = Path(f"/proc/{pid}/cgroup").read_text().split("::", 1)[1].strip().lstrip("/")
        except (OSError, IndexError):
            return False
        return self.cg is not None and bool(path) and self.cg == Path("/sys/fs/cgroup") / path

    def _gpu(self):
        """Чужие процессы на видеокарте (% SM, сумма) и её температура и частота; nvidia-smi нет — None."""
        try:
            pm = subprocess.run(["nvidia-smi", "pmon", "-c", "1", "-s", "u"], capture_output=True, text=True, timeout=15).stdout
            q = subprocess.run(["nvidia-smi", "--query-gpu=temperature.gpu,clocks.sm", "--format=csv,noheader,nounits"],
                               capture_output=True, text=True, timeout=15).stdout.strip().splitlines()[0].split(",")
        except (OSError, subprocess.SubprocessError, IndexError):
            return None
        foreign, who = 0, []
        for line in pm.splitlines():
            f = line.split()
            if len(f) < 4 or f[0].startswith("#") or not f[3].isdigit() or self._own_pid(f[1]):
                continue
            foreign += int(f[3])
            who.append(f"{f[-1]}:{f[3]}")
        return {"foreign_sm": foreign, "who": who, "temp": _num(q[0]), "clock": _num(q[1])}

    def sample(self, phase):
        """Выборка за время с прошлой (первая — только точка отсчёта: None)."""
        now = (time.time(), self._busy_s(), self._usage_s(self.cg) if self.cg else None, self._units())
        prev, self.prev = self.prev, now
        if prev is None:
            return None
        dt = now[0] - prev[0]
        own = (now[2] - prev[2]) / dt if now[2] is not None and prev[2] is not None else 0.0
        total = (now[1] - prev[1]) / dt
        units = {n: round((u - prev[3][n]) / dt, 2) for n, u in now[3].items() if n in prev[3] and (u - prev[3][n]) / dt >= 0.1}
        try:
            profile = Path("/sys/firmware/acpi/platform_profile").read_text().strip()
        except OSError:
            profile = None
        return {"epoch": now[0], "dt": round(dt, 3), "phase": phase, "load1": float(Path("/proc/loadavg").read_text().split()[0]),
                "cpu_total": round(total, 2), "cpu_own": round(own, 2), "cpu_foreign": round(max(0.0, total - own), 2),
                "units": units, "gpu": self._gpu(), "profile": profile}


def _num(s):
    try:
        return float(s)
    except ValueError:
        return None


def heavy(s):
    """Выборка с тяжёлой чужой нагрузкой (пороги — results.FOREIGN_CPU, FOREIGN_GPU)."""
    return s["cpu_foreign"] > results.FOREIGN_CPU or ((s.get("gpu") or {}).get("foreign_sm") or 0) > results.FOREIGN_GPU


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
    env.pop("__GL_YIELD", None)
    if GL_YIELD:
        env["__GL_YIELD"] = GL_YIELD
    old = signal.signal(signal.SIGTERM, _term)
    (game / "stand-out").mkdir(parents=True, exist_ok=True)
    hostf = open(game / "stand-out" / "host.jsonl", "a", encoding="utf-8")
    host = Host()

    def sample(phase):
        s = host.sample(phase)
        if s is not None:
            hostf.write(json.dumps(s, ensure_ascii=False) + "\n")
            hostf.flush()
        return s

    # перед клиентом — тишина: чужие тяжёлые задачи (сборка, GameTest, клиент другой сессии) мешали бы замеру; ждём не
    # дольше QUIET_WAIT_S — дальше идём, а окна шлагбаума с чужой нагрузкой разбор признает негодными
    sample("wait")
    quiet, until, told = 0, time.time() + QUIET_WAIT_S, 0.0
    while quiet < QUIET_SAMPLES:
        time.sleep(SAMPLE_S)
        s = sample("wait")
        if artem_playing():
            hostf.close()
            signal.signal(signal.SIGTERM, old)
            return 75
        quiet = 0 if heavy(s) else quiet + 1
        if quiet == 0 and time.time() - told >= 60:
            told = time.time()
            log(f"жду тишины: чужие {s['cpu_foreign']} ядра, видеокарта {(s.get('gpu') or {}).get('who')}, службы {s['units']}")
        if time.time() > until:
            log(f"тишины нет {QUIET_WAIT_S // 60} мин — иду так (окна с чужой нагрузкой будут негодны)")
            break
    # экран вложенного KWin выше окна на рамку: окно 2560×1600 с заголовком (28 px у Breeze) помещается целиком, и кадр
    # игры — ровно заданный (на экране той же высоты KWin ужимал окно до 2560×1572)
    proc = subprocess.Popen([str(ROOT / "tools" / "nested_kwin.sh"), SOCKET, w, str(int(h) + DECOR), f"bash {script}"], stdout=logf,
                            stderr=subprocess.STDOUT, env=env, start_new_session=True)
    deadline = time.time() + limit_s + 600
    try:
        while True:
            try:
                return proc.wait(timeout=SAMPLE_S)
            except subprocess.TimeoutExpired:
                pass
            sample("run")
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
        hostf.close()
        signal.signal(signal.SIGTERM, old)
