#!/usr/bin/env python3
"""Review probe (3.14.0 release audit, PR 720), kept as a regression test (run by tools/test_gpu_lease.sh): an exclusive
GPU client starts on an idle host, never beside Artem's game (also one that starts while it waits for the lease), and a
refused Vista run keeps the previous frames. Real lease, wrapper and launcher; fake game and D-Bus."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
with tempfile.TemporaryDirectory(prefix="incoming-review720-gpu-") as directory:
    root = Path(directory)
    scripts = root / "tools"
    scripts.mkdir()
    for filename in ("client_scenario.sh", "gpu_lease.sh", "nested_kwin.sh", "laptop_job.sh"):
        shutil.copy2(repo / "tools" / filename, scripts / filename)
    helper = scripts / "gpu_lease.sh"
    source = helper.read_text()
    marker = '[ "${BASH_SOURCE[0]}" = "$0" ] || return 0'
    assert marker in source
    helper.write_text(source.replace(marker, 'artem_playing() { [ -f "$REVIEW_GAME" ]; }\n' + marker, 1))
    (scripts / "paths.py").write_text('print("/unused")\n')
    fake_dbus = root / "dbus-run-session"
    fake_dbus.write_text('#!/bin/sh\necho STARTED\n')
    fake_dbus.chmod(0o700)
    game = root / "game"
    lease = root / "lease"
    env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", XDG_RUNTIME_DIR=directory,
               INCOMING_GPU_LEASE=str(lease), INCOMING_GPU_KWIN="review720-fake-kwin",
               INCOMING_GPU_RENEW="0.05", INCOMING_GPU_POLL="0.05",
               INCOMING_GAME_POLL="0.05", DELEGATE_LOAD_LOG=str(root / "load.tsv"),
               REVIEW_GAME=str(game))
    for var in ("INCOMING_SOFT", "INCOMING_GPU_SHARED", "INCOMING_GPU_WAIT", "INCOMING_GPU_OWNER"):
        env.pop(var, None)

    def run(args, **extra):
        return subprocess.run(args, env=dict(env, **extra), capture_output=True, text=True, timeout=12)

    vista = [scripts / "client_scenario.sh", "viewfx", "vista"]
    wrapper = [scripts / "nested_kwin.sh", "wayland-incoming-review720", "64", "64", "true"]
    helper_cmd = ["bash", "-c", 'source "$1"; shift; "$@"', "_", helper]
    wrapped = [scripts / "laptop_job.sh", "--in-unit", "gpu", "review720", str(os.getpid()), "--",
               sys.executable, "-c", "import subprocess,sys; sys.exit(subprocess.call(sys.argv[1:]))", *wrapper]
    result = run(wrapped, INCOMING_GPU_OWNER="wrapped")
    assert result.returncode == 0 and "STARTED" in result.stdout, result
    assert run(helper_cmd + ["lease_release", "wrapped"]).returncode == 0
    print("PASS idle host: GPU job permits nested wrapper through Python with close_fds")

    screenshots = root / "mod/run/scenario/screenshots"
    screenshots.mkdir(parents=True)
    frame = screenshots / "previous.png"
    frame.write_bytes(b"original")
    game.touch()
    result = run(vista)
    assert result.returncode == 75 and "STARTED" not in result.stdout, result
    deleted = not frame.is_file()
    print(f"{'FAIL' if deleted else 'PASS'} Vista exit 75: previous frame {'deleted' if deleted else 'kept'}")
    game.unlink()

    assert run(helper_cmd + ["lease_take", "holder", str(root)]).returncode == 0
    queue = Path(f"{lease}.queue")
    proc = subprocess.Popen(wrapper, env=dict(env, INCOMING_GPU_WAIT="1", INCOMING_GPU_OWNER="waiter"),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        deadline = time.monotonic() + 4
        while not (queue.exists() and "waiter" in queue.read_text()):
            assert proc.poll() is None and time.monotonic() < deadline, "waiter did not queue"
            time.sleep(0.02)
        game.touch()
        assert run(helper_cmd + ["lease_release", "holder"]).returncode == 0
        out, err = proc.communicate(timeout=12)
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.communicate()
    started = "STARTED" in out
    print(f"{'FAIL' if started else 'PASS'} host starts during lease wait: exit {proc.returncode}, "
          f"compositor stub {'ran' if started else 'did not run'}")
    sys.exit(int(deleted or started))
