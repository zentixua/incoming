"""Клиент стенда: каталог игры и запуск — то, от чего зависят числа (входит в версию стенда, ключ кэша — stand.py).

Каталог игры собирается с нуля на каждый пак: Incoming, Sodium, Iris, шейдерпак, настройки, план. Клиент идёт во
вложенном KWin (tools/nested_kwin.sh: без окна на рабочем столе, без звука хоста, аренда видеокарты) своей сессией
процессов с меткой в окружении: остановка гасит всё с меткой (TERM, через 20 с KILL) — и обёртки, и JVM, — не только
группу обёртки (JVM — внук dbus-run-session и kwin). Рядом с клиентом пишется нагрузка машины (host.jsonl): процессор
каждые 5 с (`Host`: чужое — всё, что не своя cgroup задачи) и видеокарта циклами по ~1 с подряд (`GpuWatch`); перед
клиентом — ждать тишины. Только стандартная библиотека.
"""
import json
import os
import shlex
import shutil
import signal
import subprocess
import threading
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
# цикл nvidia-smi pmon (-d 1): SM каждого процесса — среднее за цикл, циклы идут подряд; между концами соседних циклов
# больше PMON_GAP_S — пропуск (pmon вставал), а не один длинный цикл
PMON_S, PMON_GAP_S = 1.0, 2.5


def power_profile():
    """Профиль питания ноутбука (platform_profile: тихий ночью — другие частоты и время кадра); нет — unknown."""
    try:
        return Path("/sys/firmware/acpi/platform_profile").read_text().strip()
    except OSError:
        return "unknown"


class PmonCycles:
    """Строки `nvidia-smi pmon -d 1 -s u -o DT` → циклы видеокарты: {"kind": "gpu", "epoch" (конец цикла, настенные с),
    "dt" (его длина), "foreign_sm" (% SM чужих процессов, сумма), "who", "own_sm" (% SM своего рисующего клиента — JVM
    игры; None — числа нет: число его KWin или Xwayland замера не подтверждает)}. Чей процесс — classify(pid, имя) →
    CLIENT, OWN (своя cgroup, не клиент) или FOREIGN — решается, когда строка пришла, и помнится по паре (pid, имя): цикл
    кончается только со строкой следующего, а свой клиент к тому времени мог уже выйти (его /proc нет), и тогда он стал
    бы чужим; тот же pid с другим именем — другой процесс, решается заново.
    Цикл — строки с одним временем; кончается, когда пришла строка следующего (другое время или процесс, что в цикле уже
    был: два цикла в одной секунде); последний, недописанный, — не цикл.
    Время в pmon — до секунды: конец цикла — середина его секунды; длина — до конца прошлого цикла (циклы подряд), после
    пропуска и у первого — PMON_S. «-» у процесса — за цикл у него нет отсчёта SM: так у всех простаивающих (композитор
    рабочего стола, plasmashell, Xwayland), это 0. Есть ли у цикла замер, решает разбор по own_sm (results.host_in)."""

    def __init__(self, classify):
        self.classify = classify
        self.kinds = {}
        self.key = None
        self.lines = []
        self.prev_end = None

    def feed(self, line):
        """Строка pmon → законченный цикл (её строка начала следующий) или None."""
        f = line.split()
        if len(f) < 7 or f[0].startswith("#") or not (f[0].isdigit() and f[3].isdigit()):
            return None
        key = (f[0], f[1])
        who = (f[3], f[-1])
        if who not in self.kinds:
            self.kinds[who] = self.classify(*who)
        done = None
        if self.key is not None and (key != self.key or any(x[3] == f[3] for x in self.lines)):
            done = self._cycle()
            self.key = None
        if self.key is None:
            self.key, self.lines = key, []
        self.lines.append(f)
        return done

    def _cycle(self):
        try:
            end = time.mktime(time.strptime(" ".join(self.key), "%Y%m%d %H:%M:%S")) + 0.5
        except ValueError:
            return None
        gap = end - self.prev_end if self.prev_end is not None else None
        dt = gap if gap is not None and 0 < gap <= PMON_GAP_S else PMON_S
        self.prev_end = end
        foreign, who, own = 0, [], None
        for f in self.lines:
            kind, sm = self.kinds[(f[3], f[-1])], f[5]
            if not sm.isdigit():
                continue
            if kind == CLIENT:
                own = max(own or 0, int(sm))
            elif kind == FOREIGN:
                foreign += int(sm)
                who.append(f"{f[-1]}:{sm}")
        return {"kind": "gpu", "epoch": round(end, 3), "dt": round(dt, 3), "foreign_sm": foreign, "who": who, "own_sm": own}


CLIENT, OWN, FOREIGN = "client", "own", "foreign"


class GpuWatch:
    """Видеокарта непрерывно: `nvidia-smi pmon -d 1` своим процессом, циклы (PmonCycles) копятся до `drain`. Нет
    nvidia-smi или он упал — циклов нет: окна без них разбор не примет (замера нет — не ноль)."""

    def __init__(self, classify):
        self.cycles, self.lock, self.proc, self.thread = [], threading.Lock(), None, None
        self.parser = PmonCycles(classify)

    def start(self):
        try:
            self.proc = subprocess.Popen(["nvidia-smi", "pmon", "-d", "1", "-s", "u", "-o", "DT"], stdout=subprocess.PIPE,
                                         stderr=subprocess.DEVNULL, text=True, start_new_session=True)
        except OSError:
            return self
        self.thread = threading.Thread(target=self._read, name="stand-pmon", daemon=True)
        self.thread.start()
        return self

    def _read(self):
        for line in self.proc.stdout:
            c = self.parser.feed(line)
            if c is not None:
                with self.lock:
                    self.cycles.append(c)

    def drain(self):
        with self.lock:
            out, self.cycles = self.cycles, []
        return out

    def stop(self):
        if self.proc is not None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
        if self.thread is not None:
            self.thread.join(timeout=5)
        if self.proc is not None:
            self.proc.stdout.close()
        return self.drain()


class Host:
    """Нагрузка машины за время между выборками: ядра процессора всего и своей cgroup (задача ноутбука: в ней клиент,
    его KWin и JVM), чужие = всё − свои, по службам рядом (кто), профиль питания, load average; видеокарта — циклы
    GpuWatch за то же время (чужие процессы — вне своей cgroup) и её температура и частота. В выборке процессора
    видеокарта — только сводка для ожидания тишины и лога; разбор берёт сами циклы."""

    def __init__(self, gpu=None):
        self.cg = self._own_cgroup()
        self.prev = None
        self.gpu = gpu

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

    def own_pid(self, pid):
        """Процесс — в своей cgroup (путь его cgroup v2 совпадает со своим)."""
        try:
            path = Path(f"/proc/{pid}/cgroup").read_text().split("::", 1)[1].strip().lstrip("/")
        except (OSError, IndexError):
            return False
        return self.cg is not None and bool(path) and self.cg == Path("/sys/fs/cgroup") / path

    def classify(self, pid, name):
        """Процесс видеокарты: рисующий клиент (JVM игры в своей cgroup — «java» у pmon), другой свой (вложенный KWin,
        его Xwayland) или чужой."""
        if not self.own_pid(pid):
            return FOREIGN
        return CLIENT if name == "java" else OWN

    @staticmethod
    def _temp_clock():
        try:
            q = subprocess.run(["nvidia-smi", "--query-gpu=temperature.gpu,clocks.sm", "--format=csv,noheader,nounits"],
                               capture_output=True, text=True, timeout=15)
        except (OSError, subprocess.SubprocessError):
            return None, None
        if q.returncode != 0 or not q.stdout.strip():
            return None, None
        f = q.stdout.strip().splitlines()[0].split(",")
        return _num(f[0]), _num(f[1]) if len(f) > 1 else None

    def sample(self, phase):
        """Выборка за время с прошлой (первая — только точка отсчёта: None) и циклы видеокарты за это время:
        (выборка, [циклы])."""
        now = (time.time(), self._busy_s(), self._usage_s(self.cg) if self.cg else None, self._units())
        cycles = self.gpu.drain() if self.gpu else []
        prev, self.prev = self.prev, now
        if prev is None:
            return None, cycles
        dt = now[0] - prev[0]
        own = (now[2] - prev[2]) / dt if now[2] is not None and prev[2] is not None else 0.0
        total = (now[1] - prev[1]) / dt
        units = {n: round((u - prev[3][n]) / dt, 2) for n, u in now[3].items() if n in prev[3] and (u - prev[3][n]) / dt >= 0.1}
        worst = max(cycles, key=lambda c: c["foreign_sm"], default=None)
        temp, clock = self._temp_clock()
        gpu = {"foreign_sm": worst["foreign_sm"], "who": worst["who"], "cycles": len(cycles), "temp": temp, "clock": clock} if worst else None
        return {"kind": "cpu", "epoch": now[0], "dt": round(dt, 3), "phase": phase, "load1": float(Path("/proc/loadavg").read_text().split()[0]),
                "cpu_total": round(total, 2), "cpu_own": round(own, 2), "cpu_foreign": round(max(0.0, total - own), 2),
                "units": units, "gpu": gpu, "profile": power_profile()}, cycles


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
    hostf = proc = watch = None

    def write(records):
        for r in records:
            hostf.write(json.dumps(r, ensure_ascii=False) + "\n")
        hostf.flush()

    try:
        (game / "stand-out").mkdir(parents=True, exist_ok=True)
        hostf = open(game / "stand-out" / "host.jsonl", "a", encoding="utf-8")
        host = Host()
        watch = host.gpu = GpuWatch(host.classify).start()

        def sample(phase):
            s, cycles = host.sample(phase)
            write(cycles + ([s] if s is not None else []))
            return s

        # перед клиентом — тишина: чужие тяжёлые задачи (сборка, GameTest, клиент другой сессии) мешали бы замеру; ждём не
        # дольше QUIET_WAIT_S — дальше идём, а окна вердикта с чужой нагрузкой разбор признает негодными
        sample("wait")
        quiet, until, told = 0, time.time() + QUIET_WAIT_S, 0.0
        while quiet < QUIET_SAMPLES:
            time.sleep(SAMPLE_S)
            s = sample("wait")
            if artem_playing():
                return 75
            quiet = 0 if heavy(s) else quiet + 1
            if quiet == 0 and time.time() - told >= 60:
                told = time.time()
                log(f"жду тишины: чужие {s['cpu_foreign']} ядра, видеокарта {(s.get('gpu') or {}).get('who')}, службы {s['units']}")
            if time.time() > until:
                log(f"тишины нет {QUIET_WAIT_S // 60} мин — иду так (окна с чужой нагрузкой будут негодны)")
                break
        # экран вложенного KWin выше окна на рамку: окно 2560×1600 с заголовком (28 px у Breeze) помещается целиком, и
        # кадр игры — ровно заданный (на экране той же высоты KWin ужимал окно до 2560×1572)
        proc = subprocess.Popen([str(ROOT / "tools" / "nested_kwin.sh"), SOCKET, w, str(int(h) + DECOR), f"bash {script}"], stdout=logf,
                                stderr=subprocess.STDOUT, env=env, start_new_session=True)
        deadline = time.time() + limit_s + 600
        while True:
            try:
                code = proc.wait(timeout=SAMPLE_S)
                # заключительная выборка: без неё хвост последнего окна остался бы без записи
                sample("end")
                return code
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
        if proc is not None:
            left = kill_tagged(tag)
            if left:
                log(f"после клиента осталось процессов с меткой: {left}")
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        if watch is not None:
            # циклы после выхода клиента замером не станут (у них нет его числа), но и не теряются
            rest = watch.stop()
            if hostf is not None:
                write(rest)
        if hostf is not None:
            hostf.close()
        signal.signal(signal.SIGTERM, old)
