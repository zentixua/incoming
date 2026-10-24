#!/usr/bin/env bash
set -euo pipefail
WV=/mnt/data/projects/incoming/mod/run/wt-WV
REF=/mnt/data/projects/incoming/mod/run/wt-WV-main
cleanup() {
  (cd "$WV" && tools/sandbox.sh stop) || true
  (cd "$REF" && INCOMING_GPU_OWNER=WV tools/sandbox.sh stop) || true
  (cd "$WV" && tools/gpu_lease.sh release) || true
}
trap cleanup EXIT
cd "$WV"
tools/sandbox.sh start
python3 mod/run/wv-codex-evidence/capture-r2.py
tools/sandbox.sh stop
cd "$REF"
INCOMING_GPU_OWNER=WV tools/sandbox.sh start
INCOMING_GPU_OWNER=WV python3 "$WV/mod/run/wv-codex-evidence/capture-r2.py" --reference
INCOMING_GPU_OWNER=WV tools/sandbox.sh stop
cd "$WV"
tools/gpu_lease.sh release

trap - EXIT
python3 - <<'RESTORE'
from pathlib import Path
import subprocess
p=Path('mod/src/devtest/java/ua/zentix/incoming/scenario/Sandbox.java')
s=p.read_text()
for line in ['        Minecraft.getInstance().options.enableVsync().set(false);\n','        Minecraft.getInstance().getWindow().updateVsync(false);\n','        Minecraft.getInstance().options.framerateLimit().set(60);\n']:
 assert s.count(line)==1
 s=s.replace(line,'')
p.write_text(s)
subprocess.run(['git','restore','--','mod/src/devtest/java/ua/zentix/incoming/scenario/Sandbox.java','mod/src/devtest/java/ua/zentix/incoming/scenario/SandboxCommand.java'],cwd='/mnt/data/projects/incoming/mod/run/wt-WV-main',check=True)
RESTORE
uv run mod/run/wv-codex-evidence/assemble-r2.py
tools/laptop_job.sh --light wv-r2-final-encode -- python3 mod/run/wv-codex-evidence/encode-r2.py
python3 - <<'COPY'
from pathlib import Path
import shutil
root=Path('mod/run/wv-codex-evidence');out=root/'publish/r2'
shutil.copy2(root/'artifact-readme-r2.md',out/'README.md')
for name in ['capture-r2.py','assemble-r2.py','encode-r2.py','capture-after-du.sh','capture-settings.patch','baseline-r2-harness.patch']:
 shutil.copy2(root/name,out/name)
COPY
