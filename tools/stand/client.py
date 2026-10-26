"""Scene bench client: game directory and launch settings affect measurements (part of bench version,
stand.py context). Rebuild the game directory per pack: Incoming, Sodium, Iris, shader pack, settings,
plan. Run client in nested KWin (tools/nested_kwin.sh: no desktop window, no host audio, GPU lease), in a
tagged process session. Shutdown stops all tagged processes (TERM, then KILL after 20 s), wrappers and
JVM, not just wrapper group (JVM is a grandchild of dbus-run-session and kwin). Record machine load
alongside (host.jsonl): CPU every 5 s (`Host`, foreign means outside job cgroup) and consecutive ~1 s
GPU cycles (`GpuWatch`); wait for quiet before client. Standard library only.
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
# Extra nested-KWin screen height for window decorations
DECOR = 40

# Identical game settings for all versions: vsync off, unlimited fps, render distance 12, particles all, fancy graphics
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
    """Client script without Gradle (`standLaunch` in build.gradle): game directory game, window size (W×H); return path.
Same Java, arguments, classpath and environment as runClient; Gradle exits before measurement."""
    w, h = size.split("x")
    env = dict(os.environ)
    env.setdefault("JAVA_HOME", str(Path.home() / ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta"))
    subprocess.run([str(ROOT / "mod" / "gradlew"), "-p", str(HERE), "standLaunch", f"-PrunDir={game}", f"-Pwidth={w}", f"-Pheight={h}",
                    "--console=plain", "-q"], check=True, env=env)
    return HERE / "build" / "launch" / "client.sh"


def java_of(script):
    """Client Java (from standLaunch script): version from adjacent release file enters run context
(launcher JDK auto-updates, changing frame time too)."""
    try:
        words = shlex.split(Path(script).read_text().splitlines()[-1])
        java = next(Path(x) for x in words if x.endswith("/bin/java"))
        rel = dict(line.split("=", 1) for line in (java.parent.parent / "release").read_text().splitlines() if "=" in line)
        version = (rel.get("JAVA_RUNTIME_VERSION") or rel["JAVA_VERSION"]).strip('"')
        return " ".join([version, rel.get("IMPLEMENTOR", "").strip('"')]).strip()
    except (OSError, IndexError, KeyError, StopIteration, ValueError):
        return "unknown"


def server_toml(cfg):
    """Bench world rules ({section: {key: value}}) in Incoming server TOML: NeoForge adds missing keys with defaults."""
    lines = []
    for section, values in cfg.items():
        lines.append(f"[{section}]")
        lines += [f"\t{k} = {json.dumps(v)}" for k, v in values.items()]
    return "\n".join(lines) + "\n"


def prepare_game(game, jar, pack_file, mods, scenes_sel):
    """Fresh game directory: mods, shader pack (None for Iris without pack), game settings/world rules, plan; return plan."""
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
    # NeoForge 21.1 server settings live in game config/ (world serverconfig only overrides them)
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
    """Processes carrying the bench environment tag."""
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
    """All tagged processes: TERM, wait up to grace seconds, KILL survivors; return remaining count (0 means all exited)."""
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


SAMPLE_S = 5
# Wait for quiet before client: this many consecutive samples without heavy foreign load (10 s, long
# jobs show immediately), at most QUIET_WAIT_S while holding GPU lease; pair waits only before pack A1,
# B/A2 follow immediately (stand.py); the pair itself protects from steady foreign load (gate.py)
QUIET_SAMPLES, QUIET_WAIT_S = 2, 120
# nvidia-smi pmon cycle (-d 1): per-process SM is cycle mean, cycles are consecutive; neighbouring ends
# over PMON_GAP_S apart indicate a gap (pmon stalled), not one long cycle
PMON_S, PMON_GAP_S = 1.0, 2.5


def power_profile():
    """Laptop power profile (platform_profile: quiet at night changes clocks/frame time); absent means unknown."""
    try:
        return Path("/sys/firmware/acpi/platform_profile").read_text().strip()
    except OSError:
        return "unknown"


class PmonCycles:
    """`nvidia-smi pmon -d 1 -s u -o DT` lines → GPU cycles: {"kind": "gpu", "epoch" (cycle end, wall seconds),
"dt" (duration), "foreign_sm" (foreign % SM sum), "who", "own_sm" (rendering client/game JVM % SM;
None if absent; its KWin or Xwayland number does not confirm measurement)}. classify(pid, name) returns
CLIENT, OWN (our cgroup, not client), FOREIGN when a line arrives, caching by (pid, name): a cycle closes
only on a later cycle line; our client may already have exited (/proc gone), otherwise becoming foreign.
Same PID with another name means a different process, reclassify. A cycle groups equal-time lines;
a next timestamp or repeated process (two cycles in one second) closes it. Last unfinished cycle is
excluded. pmon time has one-second precision: cycle end is its second midpoint; duration reaches previous
cycle end, except first/after-gap cycles use PMON_S. Process "-" means no SM sample this cycle, normal
for idle desktop compositor, plasmashell, Xwayland; count 0. Parser determines measured cycles using own_sm
(results.host_in)."""

    def __init__(self, classify):
        self.classify = classify
        self.kinds = {}
        self.key = None
        self.lines = []
        self.prev_end = None

    def feed(self, line):
        """pmon line → completed cycle (this line started the next), or None."""
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
    """Continuous GPU: own `nvidia-smi pmon -d 1` process, cycles (PmonCycles) accumulate until `drain`. Missing
or failed nvidia-smi means no cycles; parser rejects windows without them (no measurement is not zero)."""

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
    """Machine load between samples: whole-machine/job-cgroup CPU cores (laptop job includes client, KWin,
JVM), foreign = all − own; neighbouring services (who), power profile, load average; GpuWatch cycles for
the same interval (foreign outside job cgroup), GPU temperature/frequency. CPU sample GPU fields only
summarize for quiet waits/logs; parsing uses cycles themselves."""

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
        """Whole-machine CPU time since boot, s (user+nice+system+irq+softirq+steal)."""
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
        """Services alongside ours (app.slice): name → CPU seconds since startup."""
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
        """Process belongs to our cgroup (cgroup v2 path matches ours)."""
        try:
            path = Path(f"/proc/{pid}/cgroup").read_text().split("::", 1)[1].strip().lstrip("/")
        except (OSError, IndexError):
            return False
        return self.cg is not None and bool(path) and self.cg == Path("/sys/fs/cgroup") / path

    def classify(self, pid, name):
        """GPU process: rendering client (game JVM in our cgroup, pmon "java"), other own process (nested KWin,
its Xwayland), or foreign."""
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
        """Sample since previous call (first is only a reference point: None), plus GPU cycles for the interval:
(sample, [cycles])."""
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
        gpu = {"foreign_sm": worst["foreign_sm"], "foreign_mean": round(sum(c["foreign_sm"] for c in cycles) / len(cycles), 2),
               "who": worst["who"], "cycles": len(cycles), "temp": temp, "clock": clock} if worst else None
        return {"kind": "cpu", "epoch": now[0], "dt": round(dt, 3), "phase": phase, "load1": float(Path("/proc/loadavg").read_text().split()[0]),
                "cpu_total": round(total, 2), "cpu_own": round(own, 2), "cpu_foreign": round(max(0.0, total - own), 2),
                "units": units, "gpu": gpu, "profile": power_profile()}, cycles


def _num(s):
    try:
        return float(s)
    except ValueError:
        return None


def heavy(s):
    """Sample with heavy foreign load (results.FOREIGN_CPU, FOREIGN_GPU, FOREIGN_GPU_PEAK thresholds)."""
    g = s.get("gpu") or {}
    return (s["cpu_foreign"] > results.FOREIGN_CPU or (g.get("foreign_mean") or 0) > results.FOREIGN_GPU
            or (g.get("foreign_sm") or 0) > results.FOREIGN_GPU_PEAK)


def _term(*_):
    # Job TERM (stopping laptop_job) acts like Ctrl+C: finally below stops the client
    raise KeyboardInterrupt


def run(game, script, size, limit_s, logf, log, quiet=True):
    """Client in nested KWin (quiet waits for quiet first); Artem's game starts: stop, 75; timeout: stop, 124.
On completion, exception, Ctrl+C or job TERM, no tagged processes remain."""
    w, h = size.split("x")
    tag = uuid.uuid4().hex
    env = dict(os.environ, ALSOFT_CONF=str(game / "alsoft.conf"), STAND_TAG=tag,
               # Driver shader cache disabled: every run first strike compiles shaders, like a fresh game,
               # not using the previous run cache
               __GL_SHADER_DISK_CACHE="0")
    # Driver waits for GPU as for the player (default): not inside RenderFrameEvent.Pre → Post; thread CPU
    # equals wall-clock Pre → Post within 0,5 %, with __GL_YIELD=USLEEP (sleeping wait), without it (spinning),
    # and under 6-core foreign load (A/B 25.10, scene 100, Photon). GPU-bound wait is buffer swap, outside
    # measurement. Caller environment override is not forwarded to the client
    env.pop("__GL_YIELD", None)
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

        # Wait for quiet: heavy foreign jobs (build, GameTest, another session client) affect measurement; wait
        # at most QUIET_WAIT_S, then continue (window load is reported)
        sample("wait")
        calm, until, told = 0, time.time() + QUIET_WAIT_S, 0.0
        while quiet and calm < QUIET_SAMPLES:
            time.sleep(SAMPLE_S)
            s = sample("wait")
            if artem_playing():
                return 75
            calm = 0 if heavy(s) else calm + 1
            if calm == 0 and time.time() - told >= 60:
                told = time.time()
                log(f"waiting for quiet: foreign {s['cpu_foreign']} cores, GPU {(s.get('gpu') or {}).get('who')}, services {s['units']}")
            if time.time() > until:
                log(f"not quiet after {QUIET_WAIT_S // 60} min — proceeding (window load is reported)")
                break
        # Nested KWin screen includes decoration height: window 2560×1600 plus title (28 px in Breeze) fits
        # fully, leaving exactly requested game dimensions (same-height screen shrank it to 2560×1572)
        proc = subprocess.Popen([str(ROOT / "tools" / "nested_kwin.sh"), SOCKET, w, str(int(h) + DECOR), f"bash {script}"], stdout=logf,
                                stderr=subprocess.STDOUT, env=env, start_new_session=True)
        deadline = time.time() + limit_s + 600
        while True:
            try:
                code = proc.wait(timeout=SAMPLE_S)
                # Final sample: without it, the last window tail lacks a record
                sample("end")
                return code
            except subprocess.TimeoutExpired:
                pass
            sample("run")
            if artem_playing():
                log("Artem's game started — stopping client")
                return 75
            if time.time() > deadline:
                log("client timed out — stopping")
                return 124
    finally:
        if proc is not None:
            left = kill_tagged(tag)
            if left:
                log(f"tagged processes remaining after client: {left}")
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        if watch is not None:
            # Cycles after client exit cannot measure it (no client number), but are still retained
            rest = watch.stop()
            if hostf is not None:
                write(rest)
        if hostf is not None:
            hostf.close()
        signal.signal(signal.SIGTERM, old)
