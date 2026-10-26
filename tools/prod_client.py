#!/usr/bin/env python3
"""Production client with the full host modpack, without Prism or a desktop window.

Copies instance mods/ (except Incoming), config/, options.txt, shaderpacks/, resourcepacks/ and optionally one
world to a separate directory; installs a test mod build with scenarios (./gradlew scenarioJar) and launches
Minecraft as Prism does: same libraries from the Prism directory and ForgeWrapper for NeoForge (meta/*.json).
Client runs inside nested KWin (tools/nested_kwin.sh). Instance, worlds and settings remain unchanged.

  tools/prod_client.py <scenario> [--world "New World (7)"] [--dir mod/run/prod] [--prop incoming.frametimes=true]
  tools/prod_client.py --world "New World (7)" --quickplay [--without-incoming | --incoming-jar F] --seconds 180   (join, A/B, ready jar)
  tools/prod_client.py commands --no-copy --dir mod/run/film/instance/minecraft --world greenfield-film \
      --prop "incoming.commands=dh pregen status;chunky"   (prepared copy without recopying: check mods with commands)
      incoming.commands separates entries by ";": commands without "/", wait:N adds N ticks, 0…72000 (to the 40-tick pause after a command and 20
      after a screenshot; other N logs a line and skips the step), shot:name captures screenshots/name_tick.png; ends with "SCENARIO done" and exit
  tools/prod_client.py --world /path/to/world --dir D --copy-only   (copy only: adjust its settings before --no-copy launch)
  tools/prod_client.py commands --no-copy --dir D --world world --size 1920x1080 --video D/take.mkv --prop "incoming.commands=…"
      (game window to real-time video, tools/x11_record.py; audio: D/audio.wav, 48 kHz)
  → <dir>/logs/latest.log, <dir>/screenshots/, <dir>/crash-reports/

Scenario matches tools/client_scenario.sh (incoming.scenario property); with --world, scenario receives world name
in incoming.world and opens it instead of a new world. --world is the player's world name or a world directory
path (copied under its directory name).
"""
import argparse
import glob
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import uuid
import hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402


def maven_path(name):
    """group:artifact:version[:classifier][@ext] → path in the library directory."""
    ext = "jar"
    if "@" in name:
        name, ext = name.split("@", 1)
    parts = name.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = f"-{parts[3]}" if len(parts) > 3 else ""
    return os.path.join(*group.split("."), artifact, version, f"{artifact}-{version}{classifier}.{ext}")


def allowed(lib):
    """Library rules Prism/Mojang for Linux: no rules - yes, otherwise the last one is suitable."""
    rules = lib.get("rules")
    if not rules:
        return True
    ok = False
    for r in rules:
        os_name = r.get("os", {}).get("name")
        if os_name is None or os_name == "linux":
            ok = r["action"] == "allow"
    return ok


def offline_uuid(name):
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    h[6] = h[6] & 0x0F | 0x30
    h[8] = h[8] & 0x3F | 0x80
    return str(uuid.UUID(bytes=bytes(h)))


def copy_instance(dest, world):
    mc = paths.MC
    os.makedirs(dest, exist_ok=True)
    for d in ("mods", "config", "shaderpacks", "resourcepacks", "saves", "logs", "screenshots", "crash-reports"):
        shutil.rmtree(os.path.join(dest, d), ignore_errors=True)
    shutil.copytree(os.path.join(mc, "mods"), os.path.join(dest, "mods"),
                    ignore=lambda d, names: [n for n in names if n.startswith("incoming-") or not (n.endswith(".jar") or os.path.isdir(os.path.join(d, n)))])
    for d in ("config", "shaderpacks", "resourcepacks"):
        if os.path.isdir(os.path.join(mc, d)):
            shutil.copytree(os.path.join(mc, d), os.path.join(dest, d))
    # windowless client: no pause when focus is lost and no welcome screen (keys are replaced - retry breaks loading)
    override = {"pauseOnLostFocus": "false", "onboardAccessibility": "false"}
    with open(os.path.join(mc, "options.txt")) as f:
        lines = [ln.rstrip("\n") for ln in f if ln.split(":", 1)[0] not in override]
    with open(os.path.join(dest, "options.txt"), "w") as f:
        f.write("\n".join(lines + [f"{k}:{v}" for k, v in override.items()]) + "\n")
    no_idle(dest)
    if world:
        src = world if os.sep in world else os.path.join(mc, "saves", world)
        shutil.copytree(src, os.path.join(dest, "saves", world_name(world)))


def no_idle(dest):
    """Without idle Dynamic FPS (it is assembled): by default, through 300 s no input it keeps 10–15 f/s “on battery”, and
     laptop with charge limit (“Not charging") for him on the battery - 25.10 scenes look_gate after 300 s went exactly 15 f/s.
     The input script does not: simple copy is disabled (idle.timeout = 0), other settings player - as is. When loading, the old key
    idle_time Dynamic FPS is transferred to idle.timeout on top of the new one (Serialization.upgradeIdleConfig, 3.11.4) -
     is not in the copy."""
    path = os.path.join(dest, "config", "dynamic_fps.json")
    try:
        with open(path) as f:
            conf = json.load(f)
    except (OSError, ValueError):
        conf = {}
    if not isinstance(conf, dict):
        conf = {}
    idle = conf.get("idle") if isinstance(conf.get("idle"), dict) else {}
    idle["timeout"] = 0
    conf["idle"] = idle
    conf.pop("idle_time", None)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(conf, f, indent=2)


def world_name(world):
    """The name of the world in the copy: --world is the name of the player's world or the path to the world directory."""
    return os.path.basename(os.path.normpath(world))


def launch_args(dest, props, username, game_extra, extra_jvm=()):
    prism = paths.PRISM
    libs = os.path.join(prism, "libraries")
    meta = lambda uid, v: json.load(open(os.path.join(prism, "meta", uid, f"{v}.json")))
    pack = json.load(open(os.path.join(os.path.dirname(paths.MC), "mmc-pack.json")))
    versions = {c["uid"]: c["version"] for c in pack["components"]}
    lwjgl, mcm, neo = meta("org.lwjgl3", versions["org.lwjgl3"]), meta("net.minecraft", versions["net.minecraft"]), meta("net.neoforged", versions["net.neoforged"])

    cp, seen = [], set()
    for lib in neo["libraries"] + mcm["libraries"] + lwjgl["libraries"]:
        if not allowed(lib):
            continue
        key = lib["name"].rsplit(":", 1)[0] if lib["name"].count(":") == 2 else lib["name"]
        if key in seen:
            continue
        seen.add(key)
        p = os.path.join(libs, maven_path(lib["name"]))
        if not os.path.isfile(p):
            sys.exit(f"no library {p} - launch the instance once from Prism")
        cp.append(p)
    client_jar = os.path.join(libs, maven_path(mcm["mainJar"]["name"]))
    cp.append(client_jar)
    installer = next(os.path.join(libs, maven_path(f["name"])) for f in neo["mavenFiles"] if f["name"].endswith(":installer"))

    subst = {
        "auth_player_name": username, "version_name": versions["net.minecraft"], "game_directory": dest,
        "assets_root": os.path.join(prism, "assets"), "assets_index_name": mcm["assetIndex"]["id"],
        "auth_uuid": offline_uuid(username), "auth_access_token": "0", "user_type": "legacy", "version_type": "release",
    }
    game = []
    for a in neo["minecraftArguments"].split():
        for k, v in subst.items():
            a = a.replace("${" + k + "}", v)
        game.append(a)

    jvm = ["-Xms512m", "-Xmx8196m", "-Duser.language=en",
           f"-Dforgewrapper.librariesDir={libs}", f"-Dforgewrapper.installer={installer}", f"-Dforgewrapper.minecraft={client_jar}",
           f"-Xlog:gc:file={os.path.join(dest, 'logs', 'gc.log')}:time,uptime"]
    jvm += [f"-D{k}={v}" for k, v in props.items()]
    jvm += mcm.get("+jvmArgs", [])
    # its arguments - last: of the two -Xmx JVM takes the last one
    jvm += list(extra_jvm)
    return jvm + ["-cp", os.pathsep.join(cp), neo["mainClass"]] + game + game_extra


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    ap.add_argument("scenario", nargs="?", help="script (incoming.scenario); without it the client simply goes to the menu or to --quickplay")
    ap.add_argument("--world", help="copy this player's world; with the script - open it")
    ap.add_argument("--quickplay", action="store_true", help="immediately enter --world using the game (quickPlaySingleplayer)")
    ap.add_argument("--without-incoming", action="store_true", help="host modpack without Incoming (comparison A/B)")
    ap.add_argument("--incoming-jar", help="ready-made jar mod (build CI or release) instead of a test build; no scripts")
    ap.add_argument("--prop", action="append", default=[], metavar="KEY=VALUE", help="property JVM, for example incoming.frametimes=true")
    ap.add_argument("--jvm", action="append", default=[], metavar="ARG",
                    help="argument JVM over the usual ones, for example --jvm=-Xmx12G --jvm=-XX:+UseZGC --jvm=-XX:+ZGenerational")
    ap.add_argument("--seconds", type=int, help="close the client after so many seconds (exit code 143)")
    ap.add_argument("--dir", default=os.path.join(paths.MOD, "run", "prod"))
    ap.add_argument("--no-copy", action="store_true",
                    help="do not copy the instance: launch a ready-made directory --dir (for example, a copy for shooting mod/run/film/instance/minecraft)")
    ap.add_argument("--user", default="Dev")
    ap.add_argument("--copy-only", action="store_true", help="just copy the instance (and --world) to --dir and exit")
    ap.add_argument("--size", default="1280x720", metavar="WxH", help="game window (attached screen KWin - with extra frame)")
    ap.add_argument("--video", metavar="FILE.mkv",
                    help="record the game window in real time (tools/x11_record.py: pieces FILE.NNN.mkv, marks FILE.jsonl)")
    a = ap.parse_args()
    if (a.without_incoming or a.incoming_jar) and a.scenario:
        ap.error("the scripts come from the test assembly Incoming: without it there is no script")
    if a.quickplay and not a.world:
        ap.error("--quickplay needed --world")
    if a.copy_only and a.no_copy:
        ap.error("--copy-only and --no-copy together do not make sense")
    width, _, height = a.size.partition("x")
    if not (width.isdigit() and height.isdigit()):
        ap.error("--size: width x height, for example 1920x1080")
    dest = os.path.abspath(a.dir)

    if a.no_copy:
        if not os.path.isdir(os.path.join(dest, "mods")):
            ap.error(f"--no-copy: in {dest} no mods/")
        # the previous launch could have left its mod assembly - exactly one, the one placed below
        for old in glob.glob(os.path.join(dest, "mods", "incoming-*.jar")):
            os.remove(old)
    else:
        copy_instance(dest, a.world)
        if a.copy_only:
            return
    if a.incoming_jar:
        shutil.copy2(a.incoming_jar, os.path.join(dest, "mods"))
    elif not a.without_incoming:
        subprocess.run([os.path.join(paths.MOD, "gradlew"), "-p", paths.MOD, "scenarioJar", "-q", "--console=plain"], check=True)
        jar = max(glob.glob(os.path.join(paths.MOD, "build", "scenario-libs", "incoming-*-scenario.jar")), key=os.path.getmtime)
        shutil.copy2(jar, os.path.join(dest, "mods"))
    os.makedirs(os.path.join(dest, "logs"), exist_ok=True)

    props = dict(p.split("=", 1) for p in a.prop)
    if a.scenario:
        props["incoming.scenario"] = a.scenario
        if a.world and not a.quickplay:
            props["incoming.world"] = world_name(a.world)
    # the game window is in the full screen of the nested KWin at once: the default size is 854×480, and switching to the full screen of
    #  in the nested KWin does not work (the entire take remains 854×480)
    game_extra = ["--width", width, "--height", height]
    if a.quickplay:
        game_extra += ["--quickPlaySingleplayer", world_name(a.world)]

    java = os.path.join(os.environ.get("JAVA_HOME") or paths.JAVA, "bin", "java")
    argfile = os.path.join(dest, "launch.args")
    with open(argfile, "w") as f:
        for arg in launch_args(dest, props, a.user, game_extra, a.jvm):
            f.write('"' + arg.replace("\\", "\\\\").replace('"', '\\"') + '"\n')
    # sound - like client_scenario.sh: driver OpenAL Soft “wave” writes everything that the client hears in <dest>/audio.wav
    #  (the host sound server is closed to the client nested_kwin.sh, so only in file)
    #  for video - 48 kHz and without past audio.wav: x11_record.py marks when the file appeared
    audio = os.path.join(dest, "audio.wav")
    alsoft = os.path.join(dest, "alsoft.conf")
    with open(alsoft, "w") as f:
        f.write(f"[general]\ndrivers = wave\nfrequency = {48000 if a.video else 22050}\nchannels = stereo\nsample-type = int16\n"
                f"[wave]\nfile = {audio}\n")
    tools = os.path.dirname(os.path.abspath(__file__))
    game = f"\"{java}\" @\"{argfile}\""
    if a.video:
        if os.path.exists(audio):
            os.remove(audio)
        game = f"python3 \"{os.path.join(tools, 'x11_record.py')}\" --out \"{os.path.abspath(a.video)}\" --size {width}x{height} --audio \"{audio}\" -- {game}"
    socket = "wayland-incoming-prod-" + os.path.basename(dest)
    cmd = f"sh -c 'cd \"{dest}\" && exec {game}'"
    # the KWin screen is larger than the window with a frame and a margin: a window with a frame in the entire screen KWin would be squeezed under the screen
    screen = [str(int(width) + 128), str(int(height) + 192)]
    sys.exit(run_in_group([os.path.join(tools, "nested_kwin.sh"), socket, *screen, cmd],
                          {**os.environ, "ALSOFT_CONF": alsoft}, a.seconds))


def run_in_group(argv, env, seconds):
    """Launch in your session; by deadline, Ctrl+C, terminal break and external TERM, the entire group is extinguished.

    Client - grandson nested_kwin.sh (dbus-run-session → kwin → sh → java): TERM one wrapper leaves it to work
     further, simultaneously with the next check. We extinguish the group: TERM, through 20 s - KILL the remaining (as rig_trap in
    tools/rig_procs.sh). Exit code: as for the process; by deadline - 143; by signal - 128 + its number.
    """
    for sig in STOP_SIGNALS:
        signal.signal(sig, _raise_stop)
    proc, code = None, None
    try:
        proc = subprocess.Popen(argv, env=env, start_new_session=True)
        code = proc.wait(timeout=seconds)
        if code < 0:
            code = 128 - code
    except subprocess.TimeoutExpired:
        code = 128 + signal.SIGTERM
    except Stop as e:
        code = 128 + e.signum
    finally:
        for sig in STOP_SIGNALS:
            signal.signal(sig, signal.SIG_IGN)  # repeated Ctrl+C does not interrupt cleaning
        if proc is not None:
            stop_group(proc)
    return code


STOP_SIGNALS = (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)


class Stop(Exception):
    def __init__(self, signum):
        super().__init__(signum)
        self.signum = signum


def _raise_stop(signum, frame):
    raise Stop(signum)


def group_alive(pgid):
    """Is there a live process in the group: according to /proc/*/stat, zombies (Z) do not count - the leader remains a zombie until wait()."""
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            with open(f"/proc/{pid}/stat") as f:
                stat = f.read()
        except OSError:
            continue
        fields = stat[stat.rindex(")") + 2:].split()  # the process name in parentheses may contain spaces
        if fields[0] != "Z" and int(fields[2]) == pgid:
            return True
    return False


def stop_group(proc):
    """TERM to the entire process group, through 20 s - KILL to the remaining; the leader is reaped at the end so that the group number does not leave."""
    for sig, wait in ((signal.SIGTERM, 20), (signal.SIGKILL, 5)):
        if not group_alive(proc.pid):
            break
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            break
        deadline = time.monotonic() + wait
        while group_alive(proc.pid) and time.monotonic() < deadline:
            time.sleep(0.5)
    proc.wait()


if __name__ == "__main__":
    main()
