#!/usr/bin/env python3
"""Review probe (3.14.0 release audit), kept as a regression test (run by tools/test_gpu_lease.sh): an exclusive GPU
client (here the Vista scenario) never starts while Artem's game runs. Disposable rig, simulated game, no real compositor."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

repository = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
with tempfile.TemporaryDirectory(prefix="incoming-gpu-audit-") as tmp:
    root = Path(tmp)
    tools = root / "tools"
    tools.mkdir()
    for name in ("client_scenario.sh", "nested_kwin.sh", "gpu_lease.sh"):
        shutil.copy2(repository / "tools" / name, tools / name)
    helper = tools / "gpu_lease.sh"
    source = helper.read_text()
    guard = '[ "${BASH_SOURCE[0]}" = "$0" ] || return 0'
    assert guard in source
    helper.write_text(source.replace(guard, "artem_playing() { return 0; }\n" + guard, 1))
    (tools / "paths.py").write_text('print("/unused")\n')
    # No real D-Bus, compositor, Gradle, or game is started.
    fake_dbus = root / "dbus-run-session"
    fake_dbus.write_text('#!/bin/sh\nprintf "%s\\n" "STARTED: $*"\n')
    fake_dbus.chmod(0o700)
    env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", XDG_RUNTIME_DIR=tmp,
               INCOMING_GPU_LEASE=str(root / "lease"), INCOMING_GPU_KWIN="stub-kwin-for-audit",
               INCOMING_GPU_RENEW="0.02")
    for name in ("INCOMING_GPU_WAIT", "INCOMING_SOFT", "INCOMING_GPU_SHARED", "INCOMING_GPU_OWNER"):
        env.pop(name, None)
    run = subprocess.run([tools / "client_scenario.sh", "viewfx", "vista"],
                         env=env, capture_output=True, text=True, timeout=10)
    print(run.stdout, end="")
    print(run.stderr, end="")
    assert "STARTED:" not in run.stdout, "Exclusive Vista run started while the host game was active"
