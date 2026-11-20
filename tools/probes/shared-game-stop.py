#!/usr/bin/env python3
"""Run the real launcher and D-Bus wrapper with a stand-in for KWin."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time


def main():
    source = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[2])
    ordinary = run(source, errexit=False)
    strict = run(source, errexit=True)
    return ordinary or strict


def run(source, errexit):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "tools").mkdir()
        for name in ("gpu_lease.sh", "soft_gl.sh", "nested_kwin.sh", "nested_kwin_bus.conf"):
            shutil.copy2(source / "tools" / name, root / "tools" / name)
        for name, script in (
            ("nvidia-smi", "echo 6000"),
            ("kwin_wayland", 'echo "$$" > "$PROBE_ROOT/client"; exec sleep 30'),
        ):
            fixture = root / name
            fixture.write_text("#!/usr/bin/env bash\n" + script + "\n")
            fixture.chmod(0o700)
        env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", XDG_RUNTIME_DIR=directory,
                   INCOMING_GPU_LEASE=str(root / "lease"), PROBE_ROOT=directory, INCOMING_GAME_POLL="0.02")
        for name in ("INCOMING_CPU_ONLY", "INCOMING_SOFT", "INCOMING_GPU_SHARED"):
            env.pop(name, None)
        with (root / "out").open("w") as out:
            shell = ["bash", "-e"] if errexit else ["bash"]
            process = subprocess.Popen([*shell, "-c", """
                source "$PROBE_ROOT/tools/gpu_lease.sh"
                source "$PROBE_ROOT/tools/soft_gl.sh"
                artem_playing() { [ -e "$PROBE_ROOT/game" ]; }
                trap 'echo "$?" > "$PROBE_ROOT/result"' EXIT
                vanilla_display review 64 64 true
                result=$?
                echo "$result" > "$PROBE_ROOT/result"
                # Model a surrounding job that has not finished cleaning up.
                sleep 30
            """], env=env, start_new_session=True, stdout=out, stderr=out)
            try:
                wait_for(root / "client")
                client = int((root / "client").read_text())
                (root / "game").touch()
                wait_for(root / "result")
                result = int((root / "result").read_text())
                live = (Path("/proc") / str(client)).exists()
                slot_free = subprocess.run(
                    ["flock", "-n", str(root / "lease.slot1"), "true"], check=False
                ).returncode == 0
                print((root / "out").read_text(), end="")
                print(f"errexit={errexit}; wrapper exit={result}; compositor live={live}; slot free={slot_free}")
                return int(result != 75 or live or not slot_free)
            finally:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()


def wait_for(file):
    for _ in range(200):
        if file.exists() and file.stat().st_size:
            return
        time.sleep(0.02)
    raise RuntimeError(f"fixture did not produce {file.name}")


if __name__ == "__main__":
    sys.exit(main())
