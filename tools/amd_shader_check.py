#!/usr/bin/env python3
"""Every Incoming core shader program through AMD's own OpenGL compiler, offline (Radeon GPU Analyzer, no AMD GPU).

    python3 tools/amd_shader_check.py                  check against tools/fixtures/amd_shader_budget.json
    python3 tools/amd_shader_check.py --only 'pall'    a subset (regex on the program name)
    python3 tools/amd_shader_check.py --record         rewrite the budget from this tree (a deliberate change: say why in the PR)
    python3 tools/amd_shader_check.py --tree DIR       another shaders directory (assets/incoming/shaders of any checkout)

AMD's Windows OpenGL driver compiles a program with the same compiler (glc) inside the game process. For each program
the tool reports what that compiler makes of it for an RX 6600 XT (gfx1032, RDNA2): vector registers (256 is the
hardware limit), scratch (registers spilled to memory, bytes per lane), machine code size, the compiler's peak memory
and its time. The check fails when a program's code or the compiler's memory grows past its budget, when a program
gains scratch, or when a new program is born above the limits for new programs. Code size and compiler memory are
deterministic for a given source; seconds depend on the machine and are only reported.

RGA 2.13 is the last release that targets RDNA2 (2.14+ list RDNA3 and newer only). It is downloaded once, checked by
sha256 and unpacked into mod/run/rga (INCOMING_RGA_DIR overrides). Standard library only; Linux x86-64."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "mod/src/main/resources/assets/incoming/shaders"
BUDGET = ROOT / "tools/fixtures/amd_shader_budget.json"
RGA_URL = "https://github.com/GPUOpen-Tools/radeon_gpu_analyzer/releases/download/2.13/rga-linux-2.13.tgz"
RGA_SHA256 = "76da94f739b46ad753c0fd2d887d65aca7f3a4853fbba5b253a2883b77545868"
RGA_VERSION = "2.13.0.27"
ASIC = "gfx1032"
# Allowed growth over a program's budget: code bytes and compiler memory move by a few percent with ordinary edits; the
# regressions this check exists for were ×2.4 (fx_pall_composite: 412 KB → 986 KB of code, one PR) and ×7 in memory.
GROWTH = 0.10
# A program not in the budget: an ordinary effect shader is 1–60 KB of code; the nuclear volume programs that freeze
# AMD machines are 200–800 KB and take 0.5–5 GB of compiler memory.
NEW_CODE = 128 * 1024
# Compiler memory of a small program is mostly RGA's own ~60 MB, which moves by a few MB between runs and machines
MEMORY_SLACK_MB = 32
NEW_MEMORY_MB = 1024


def rga_dir():
    base = Path(os.environ.get("INCOMING_RGA_DIR") or ROOT / "mod/run/rga")
    exe = base / f"rga-{RGA_VERSION}/rga"
    if exe.exists():
        return exe
    base.mkdir(parents=True, exist_ok=True)
    archive = base / "rga-linux-2.13.tgz"
    if not archive.exists() or sha256(archive) != RGA_SHA256:
        print(f"downloading {RGA_URL} …", flush=True)
        part = archive.with_suffix(".part")
        with urllib.request.urlopen(RGA_URL, timeout=300) as r, open(part, "wb") as f:
            shutil.copyfileobj(r, f, 1 << 20)
        part.replace(archive)
    if sha256(archive) != RGA_SHA256:
        sys.exit(f"{archive}: sha256 does not match the pinned RGA {RGA_VERSION}")
    with tarfile.open(archive) as t:
        t.extractall(base, filter="data")
    if not exe.exists():
        sys.exit(f"{exe} missing after unpacking")
    return exe


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


class MissingInclude(Exception):
    pass


def expand(source, include_dir, seen=None):
    """Minecraft's GlslPreprocessor for core shaders: each #moj_import <incoming:file> is the file's text, once."""
    if seen is None:
        seen = set()

    def include(match):
        # no namespace is Minecraft's: vanilla includes are not in this tree
        space, name = match[1] or "minecraft:", match[2]
        if space != "incoming:":
            raise MissingInclude(space + name)
        path = include_dir / name
        if not path.exists():
            raise MissingInclude(name)
        if name in seen:
            return ""
        seen.add(name)
        return expand(path.read_text(), include_dir, seen)

    return re.sub(r"^\s*#moj_import\s+<(\w+:)?([\w./-]+)>", include, source, flags=re.M)


def sources(tree, name):
    """(vertex, fragment) text of core program `name`, or raise MissingInclude."""
    setup = json.loads((tree / "core" / (name + ".json")).read_text())
    texts = []
    for key, ext in (("vertex", ".vsh"), ("fragment", ".fsh")):
        ref = setup[key]
        space, _, file = ref.rpartition(":")
        path = tree / "core" / (file + ext)
        if space not in ("", "incoming") or not path.exists():
            raise MissingInclude(ref + ext)
        texts.append(expand(path.read_text(), tree / "include"))
    return texts


def stats(text):
    row = dict(re.findall(r"-\s*(\S+)\s*=\s*(\S+)", text))
    return {"vgpr": int(row["USED_VGPRs"]), "sgpr": int(row["USED_SGPRs"]), "scratch": int(row["SCRATCH_SIZE"]),
            "code": int(row["ISA_SIZE"])}


def compile_one(rga, asic, tree, name):
    try:
        vert, frag = sources(tree, name)
    except MissingInclude as e:
        return name, {"skipped": f"source outside this tree: {e}"}
    with tempfile.TemporaryDirectory(prefix="amd-shader-") as tmp:
        d = Path(tmp)
        (d / "s.vert").write_text(vert)
        (d / "s.frag").write_text(frag)
        start = time.monotonic()
        proc = subprocess.Popen([str(rga), "-s", "opengl", "-c", asic, "-a", str(d / "a.csv"), "--vert", str(d / "s.vert"),
                                 "--frag", str(d / "s.frag")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=d,
                                # RGA keeps its intermediate files and glc's log under $HOME/.rga, one fixed log name
                                # for every run: parallel runs would read each other's results
                                env=dict(os.environ, HOME=str(d)))
        out = proc.stdout.read().decode(errors="replace")
        _, status, usage = os.wait4(proc.pid, 0)
        seconds = time.monotonic() - start
        frag_stats = d / f"{asic}_a_frag.csv"
        if os.waitstatus_to_exitcode(status) != 0 or not frag_stats.exists():
            return name, {"error": [line for line in out.strip().splitlines() if line.strip()][-8:]}
        result = stats(frag_stats.read_text())
        # ru_maxrss of a reaped child includes its own reaped children (glc), in KiB
        result["memory_mb"] = round(usage.ru_maxrss / 1024)
        result["seconds"] = round(seconds, 1)
        return name, result


def judge(name, got, budget):
    """Failure messages for one program against its budget entry (None: a new program)."""
    if "error" in got:
        return [f"{name}: AMD compiler failed: {' / '.join(got['error'])}"]
    if "skipped" in got:
        return []
    fails = []
    if budget is None:
        if got["code"] > NEW_CODE:
            fails.append(f"{name}: new program with {got['code'] // 1024} KB of AMD code (limit for a new program {NEW_CODE // 1024} KB)")
        if got["memory_mb"] > NEW_MEMORY_MB:
            fails.append(f"{name}: new program needs {got['memory_mb']} MB of AMD compiler memory (limit {NEW_MEMORY_MB} MB)")
        return fails
    if got["code"] > budget["code"] * (1 + GROWTH):
        fails.append(f"{name}: AMD code {got['code'] // 1024} KB, budget {budget['code'] // 1024} KB (+{GROWTH:.0%} allowed)")
    if got["memory_mb"] > max(budget["memory_mb"] * (1 + GROWTH), budget["memory_mb"] + MEMORY_SLACK_MB):
        fails.append(f"{name}: AMD compiler memory {got['memory_mb']} MB, budget {budget['memory_mb']} MB (+{GROWTH:.0%} allowed)")
    if got["scratch"] > budget["scratch"]:
        fails.append(f"{name}: spills {got['scratch']} bytes per lane to scratch memory, budget {budget['scratch']}")
    return fails


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tree", type=Path, default=SHADERS, help="shaders directory (core/, include/)")
    ap.add_argument("--only", help="regex on program names")
    ap.add_argument("--record", action="store_true", help="write the measured values as the new budget")
    ap.add_argument("--jobs", type=int, default=max(1, min(4, (os.cpu_count() or 2) // 2)))
    ap.add_argument("--json", type=Path, help="write all measurements here")
    args = ap.parse_args()
    rga = rga_dir()
    names = sorted(p.stem for p in (args.tree / "core").glob("*.json"))
    if args.only:
        names = [n for n in names if re.search(args.only, n)]
    with ThreadPoolExecutor(args.jobs) as pool:
        results = dict(pool.map(lambda n: compile_one(rga, ASIC, args.tree, n), names))
    budget = json.loads(BUDGET.read_text()) if BUDGET.exists() else {"programs": {}}
    rows = ["| program | VGPR | scratch B | code KB | compiler MB | s | budget code KB / MB |", "|---|---|---|---|---|---|---|"]
    fails = []
    for name in names:
        got, b = results[name], budget["programs"].get(name)
        if "skipped" in got or "error" in got:
            rows.append(f"| {name} | {got.get('skipped') or 'FAILED'} ||||||")
        else:
            limit = f"{b['code'] // 1024} / {b['memory_mb']}" if b else "new"
            rows.append(f"| {name} | {got['vgpr']} | {got['scratch']} | {got['code'] // 1024} | {got['memory_mb']} | {got['seconds']} | {limit} |")
        fails += judge(name, got, b)
    report = f"AMD OpenGL compiler (RGA {RGA_VERSION} glc, {ASIC} = RX 6600 XT, RDNA2)\n\n" + "\n".join(rows)
    print(report)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(report + "\n\n" + "\n".join(f"- {m}" for m in fails) + "\n")
    if args.json:
        args.json.write_text(json.dumps(results, indent=1, sort_keys=True))
    if args.record:
        programs = budget["programs"] if args.only else {}
        for name in names:
            got = results[name]
            if "code" in got:
                programs[name] = {k: got[k] for k in ("code", "memory_mb", "scratch")}
        BUDGET.write_text(json.dumps({"rga": RGA_VERSION, "asic": ASIC, "programs": dict(sorted(programs.items()))}, indent=1) + "\n")
        print(f"budget written: {BUDGET.relative_to(ROOT)}")
        return 0
    for m in fails:
        print("FAILED  " + m)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
