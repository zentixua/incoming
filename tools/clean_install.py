#!/usr/bin/env python3
"""Smoke-test the built jar in fresh production NeoForge directories, without optional mods.

Server: python3 tools/clean_install.py mod/build/libs/incoming-<version>.jar
Server + client: the same command with --client, on a GitHub runner (software rendering only).
All files and logs are retained under mod/run/clean-install-*. No launcher instance is read.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import tempfile
import time
from urllib.request import urlopen

from release_checks import ROOT, check_jar, properties


def wait_for(process, log, pattern, timeout, other=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Process exited with {process.returncode} before {pattern}; see {log}")
        if other is not None and other.poll() is not None:
            raise RuntimeError(f"Client exited with {other.returncode}; see {log.parent.parent / 'client.log'}")
        if log.exists() and re.search(pattern, log.read_text(errors="replace")):
            return
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for {pattern}; see {log}")


def terminate(process):
    if process is not None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=20)
        except ProcessLookupError:
            pass
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)


def smoke(jar, client=False):
    if client and (os.environ.get("GITHUB_ACTIONS") != "true"
                   or os.environ.get("LIBGL_ALWAYS_SOFTWARE") != "true"):
        raise ValueError("Client smoke runs only on GitHub Actions with LIBGL_ALWAYS_SOFTWARE=true")
    props = properties()
    check_jar(jar, props["mod_version"])
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    launch_env = os.environ.copy()
    if Path(java).is_absolute():
        launch_env["PATH"] = str(Path(java).parent) + os.pathsep + launch_env.get("PATH", "")
    run = ROOT / "mod/run"
    run.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="clean-install-", dir=run))
    print(f"Clean installation logs: {work}", flush=True)
    server = work / "server"
    server.mkdir()
    neo = props["neo_version"]
    url = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{neo}/neoforge-{neo}-installer.jar"
    installer = work / "installer.jar"
    with urlopen(url, timeout=60) as response, installer.open("wb") as output:
        shutil.copyfileobj(response, output)
    with (work / "installer.log").open("w") as log:
        subprocess.run([java, "-jar", str(installer), "--installServer"], cwd=server,
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
    mods = server / "mods"
    mods.mkdir(exist_ok=True)
    shutil.copy2(jar, mods / jar.name)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    (server / "eula.txt").write_text("eula=true\n")
    (server / "user_jvm_args.txt").write_text("-Xms512m\n-Xmx3G\n-XX:ActiveProcessorCount=2\n")
    (server / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nview-distance=2\n"
        "simulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\n"
        "spawn-protection=0\n")
    config = server / "config"
    config.mkdir(exist_ok=True)
    (config / "neoforge-server.toml").write_text("advertiseDedicatedServerToLan=false\n")
    server_process = client_process = None
    try:
        with (work / "server.log").open("w") as log:
            server_process = subprocess.Popen(["bash", "run.sh", "nogui"], cwd=server, stdin=subprocess.PIPE,
                                              stdout=log, stderr=subprocess.STDOUT, text=True,
                                              env=launch_env, start_new_session=True)
        server_log = server / "logs/latest.log"
        wait_for(server_process, server_log, r'Done \([0-9.,]+s\)!', 240)
        if client:
            client_dir = work / "client"
            (client_dir / "mods").mkdir(parents=True)
            shutil.copy2(jar, client_dir / "mods" / jar.name)
            # PortableMC installs the production loader and libraries; it adds no test mods or dev classpath.
            command = ["xvfb-run", "-a", "uv", "run", "--with", "portablemc[certifi]==4.4.1", "portablemc",
                       "--main-dir", str(work / "launcher"), "--work-dir", str(client_dir), "start",
                       "--jvm", java, "--jvm-args=-Xms512m -Xmx3G -XX:ActiveProcessorCount=2",
                       "--username", "SmokeTest", "--resolution", "640x480", "--server", "127.0.0.1",
                       "--server-port", str(port), f"neoforge:{neo}"]
            with (work / "client.log").open("w") as log:
                client_process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            wait_for(server_process, server_log, r"SmokeTest joined the game", 600, client_process)
            print("Clean production client: joined the clean server without optional mods", flush=True)
            terminate(client_process)
            client_process = None
        server_process.stdin.write("stop\n")
        server_process.stdin.flush()
        if server_process.wait(timeout=60) != 0:
            raise RuntimeError("Production server failed during shutdown")
        print("Clean production server: PASS (startup and clean shutdown, only the built Incoming jar)", flush=True)
    finally:
        terminate(client_process)
        terminate(server_process)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--client", action="store_true")
    args = parser.parse_args()
    smoke(args.jar.resolve(), args.client)
