#!/usr/bin/env python3
"""Every Incoming shader program through AMD's own OpenGL compiler, offline (Radeon GPU Analyzer, no AMD GPU).

    python3 tools/amd_shader_check.py                  check against tools/fixtures/amd_shader_budget.json
    python3 tools/amd_shader_check.py --only 'pall'    a subset (regex on the program name)
    python3 tools/amd_shader_check.py --record --jobs 1  rewrite the budget from this tree (a deliberate change: say why
                                                       in the PR; one job on an idle machine, see compiler memory below)
    python3 tools/amd_shader_check.py --tree DIR       another shaders directory (assets/incoming/shaders of any checkout)

AMD's Windows OpenGL driver compiles a program with the same compiler (glc) inside the game process. For each program
(core shaders in core/, post programs in program/ as "program/<name>") the tool reports what that compiler makes of it
for an RX 6600 XT (gfx1032, RDNA2): fragment-stage vector registers (256 is the hardware limit), scratch (registers
spilled to memory, dwords per lane, the larger stage), machine code of both stages, the compiler's peak memory and its
time. The check fails when a program's code or the compiler's memory grows past its budget, when a program gains
scratch, when a program does not compile (glc compiles each stage; link errors are the game's and Mesa's), or when a new program is born above the limits for new programs.
Code and scratch are exact for a given source; seconds depend on the machine and are only reported.

Compiler memory is the peak resident size of the compiler process tree (wait4). Memory pressure on the host lowers it
(pages reclaimed: three parallel 5 GB compiles on a loaded laptop read 4.5–4.7 GB against 4.97 GB alone), so a budget
is recorded with --jobs 1 on an idle machine; CI's numbers (the job summary) are the reference.

Sources are expanded as Minecraft's GlslPreprocessor does: each #moj_import <ns:file> once per stage, no namespace is
minecraft. Vanilla stages and includes come from the Minecraft 1.21.1 client jar (Gradle's copy, else Mojang's server,
sha1-checked), never from this repository. RGA 2.13 is the last release that targets RDNA2 (2.14+ list RDNA3 and newer
only). Both are fetched once, hash-checked and the needed files unpacked into mod/run/rga (INCOMING_RGA_DIR overrides). Standard library
only; Linux x86-64."""
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
import threading
import time
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "mod/src/main/resources/assets/incoming/shaders"
BUDGET = ROOT / "tools/fixtures/amd_shader_budget.json"
RGA_URL = "https://github.com/GPUOpen-Tools/radeon_gpu_analyzer/releases/download/2.13/rga-linux-2.13.tgz"
RGA_SHA256 = "76da94f739b46ad753c0fd2d887d65aca7f3a4853fbba5b253a2883b77545868"
RGA_VERSION = "2.13.0.27"
# Minecraft 1.21.1 client jar (version manifest downloads.client)
MC_JAR_URL = "https://piston-data.mojang.com/v1/objects/30c73b1c5da787909b2f73340419fdf13b9def88/client.jar"
MC_JAR_SHA1 = "30c73b1c5da787909b2f73340419fdf13b9def88"
MC_JAR_GRADLE = Path.home() / ".gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar"
ASIC = "gfx1032"
# Allowed growth over a program's budget: code bytes and compiler memory move by a few percent with ordinary edits; the
# regressions this check exists for were ×2.4 (fx_pall_composite: 412 KB → 986 KB of code, one PR) and ×7 in memory.
GROWTH = 0.10
# A one-line edit of a tiny shader moves its code by more than 10 %
CODE_SLACK = 2048
# Compiler memory of a small program is mostly RGA's own ~60 MB, which moves by a few MB between runs and machines
MEMORY_SLACK_MB = 32
# A program not in the budget: an ordinary effect shader is 1–60 KB of code; the nuclear volume programs that freeze
# AMD machines are 200–800 KB and take 0.5–5 GB of compiler memory.
NEW_CODE = 128 * 1024
NEW_MEMORY_MB = 1024
# One compile: the slowest program takes ~40 s on a CI runner
TIMEOUT_S = 600


def cache_dir():
    base = Path(os.environ.get("INCOMING_RGA_DIR") or ROOT / "mod/run/rga")
    base.mkdir(parents=True, exist_ok=True)
    return base


def digest(path, kind):
    h = hashlib.new(kind)
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(url, kind, want, base):
    """url into a fresh file under base, hash-checked; three tries."""
    fd, name = tempfile.mkstemp(dir=base, prefix=".download-")
    os.close(fd)
    try:
        return fetch_into(url, kind, want, Path(name))
    except BaseException:
        os.unlink(name)
        raise


def fetch_into(url, kind, want, name):
    for attempt in range(3):
        try:
            print(f"downloading {url} …", flush=True)
            with urllib.request.urlopen(url, timeout=300) as r, open(name, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
            if digest(name, kind) == want:
                return name
            print(f"{url}: {kind} mismatch", flush=True)
        except OSError as e:
            print(f"{url}: {e}", flush=True)
        time.sleep(5 * (attempt + 1))
    sys.exit(f"{url}: no verified download")


def unpacked(base, name, unpack):
    """base/name, unpacked once: into a temporary directory, then renamed whole (an interrupted unpack leaves nothing)."""
    target = base / name
    if (target / ".complete").exists():
        return target
    tmp = Path(tempfile.mkdtemp(dir=base, prefix=".unpack-"))
    try:
        unpack(tmp)
        (tmp / ".complete").write_text("")
        # another run may have finished the same unpack meanwhile: a complete target is never replaced
        if not (target / ".complete").exists():
            if target.exists():
                shutil.rmtree(target)
            try:
                tmp.rename(target)
            except OSError:
                if not (target / ".complete").exists():
                    raise
    finally:
        if tmp.exists():
            shutil.rmtree(tmp)
    return target


def glc():
    """AMD's OpenGL compiler from the RGA release: glc is self-contained (libstdc++ and libc only); kept with the
    release's licence files, the rest of RGA (GUI, Vulkan, OpenCL, 780 MB) is dropped."""
    base = cache_dir()

    def unpack(tmp):
        archive = fetch(RGA_URL, "sha256", RGA_SHA256, base)
        full = tmp / "full"
        try:
            with tarfile.open(archive) as t:
                t.extractall(full, filter="data")
        finally:
            archive.unlink()
        inner = full / f"rga-{RGA_VERSION}"
        for keep in ("utils/glc", "LICENSE.txt", "EULA.txt", "RGA_NOTICES.txt"):
            if (inner / keep).exists():
                (inner / keep).rename(tmp / Path(keep).name)
        shutil.rmtree(full)

    exe = unpacked(base, f"glc-{RGA_VERSION}", unpack) / "glc"
    if not exe.exists():
        sys.exit(f"{exe} missing after unpacking")
    return exe


def vanilla():
    """Minecraft 1.21.1's shaders directory (core/, include/, program/)."""
    base = cache_dir()

    def unpack(tmp):
        gradle = MC_JAR_GRADLE.exists() and digest(MC_JAR_GRADLE, "sha1") == MC_JAR_SHA1
        jar = MC_JAR_GRADLE if gradle else fetch(MC_JAR_URL, "sha1", MC_JAR_SHA1, base)
        try:
            with zipfile.ZipFile(jar) as z:
                for entry in z.namelist():
                    if re.fullmatch(r"assets/minecraft/shaders/(core|include|program)/[\w./-]+", entry):
                        out = tmp / entry.removeprefix("assets/minecraft/shaders/")
                        out.parent.mkdir(parents=True, exist_ok=True)
                        out.write_bytes(z.read(entry))
        finally:
            if not gradle:
                jar.unlink()

    return unpacked(base, "minecraft-1.21.1-shaders", unpack)


class BuildError(Exception):
    pass


def roots(tree, mc):
    return {"incoming": tree, "minecraft": mc}


def expand(source, dirs, seen=None):
    """Minecraft's GlslPreprocessor: each #moj_import <ns:file> is shaders/include/file of that namespace, once per stage."""
    if seen is None:
        seen = set()

    def include(match):
        space, name = (match[1] or "minecraft:")[:-1], match[2]
        if space not in dirs or dirs[space] is None:
            raise BuildError(f"include {space}:{name}: namespace {space} not available")
        path = dirs[space] / "include" / name
        if not path.exists():
            raise BuildError(f"include {space}:{name} does not exist")
        key = f"{space}:{name}"
        if key in seen:
            return ""
        seen.add(key)
        # GlslPreprocessor comments out an import's #version (vanilla includes carry #version 150) and raises the main
        # file's to the highest seen; every include here is 150 or has none, so dropping the line gives the same text
        text = re.sub(r"^\s*#version\b.*$", "", path.read_text(), flags=re.M)
        return expand(text, dirs, seen)

    return re.sub(r"^\s*#moj_import\s+<([\w.-]+:)?([\w./-]+)>", include, source, flags=re.M)


def programs(tree):
    """(name, kind) of every program: core shaders by plain name, post programs as program/<name>."""
    found = [(p.stem, "core") for p in sorted((tree / "core").glob("*.json"))]
    found += [(f"program/{p.stem}", "program") for p in sorted((tree / "program").glob("*.json"))]
    return found


def sources(tree, mc, name, kind):
    """(vertex, fragment) text of a program."""
    dirs = roots(tree, mc)
    setup = json.loads((tree / kind / (name.split("/")[-1] + ".json")).read_text())
    texts = []
    for key, ext in (("vertex", ".vsh"), ("fragment", ".fsh")):
        space, _, file = setup[key].rpartition(":")
        space = space or "minecraft"
        if space not in dirs or dirs[space] is None:
            raise BuildError(f"{key} {setup[key]}: namespace {space} not available")
        path = dirs[space] / kind / (file + ext)
        if not path.exists():
            raise BuildError(f"{key} {setup[key]}{ext} does not exist")
        texts.append(expand(path.read_text(), dirs))
    return texts


def stats(text):
    """glc's per-stage info file: code bytes, VGPRs, scratch (registers spilled to memory, dwords per lane)."""
    def value(key):
        m = re.search(rf"^{key}\s*=\s*(\d+)", text, re.M)
        if not m:
            raise BuildError(f"glc info has no {key}")
        return int(m[1])
    return {"vgpr": value("NumVgprs"), "scratch": value("ScratchSize"), "code": value("codeLenInByte")}


def compile_one(glc, tree, mc, name, kind, deadline=None):
    try:
        vert, frag = sources(tree, mc, name, kind)
    except BuildError as e:
        return name, {"error": [str(e)]}
    with tempfile.TemporaryDirectory(prefix="amd-shader-") as tmp:
        d = Path(tmp)
        (d / "s.vert").write_text(vert)
        (d / "s.frag").write_text(frag)
        # glc directly, every output in this run's directory: RGA itself puts glc's log at a fixed path in the account's
        # home (getpwuid, not $HOME), so parallel RGA runs read and delete each other's logs
        args = [str(glc), "-gfxip", ASIC.removeprefix("gfx"), "-set", f"in.vert.glsl={d / 's.vert'}",
                f"in.frag.glsl={d / 's.frag'}", f"out.glslLog={d / 'log.txt'}", "defaultOutput=0"]
        for stage in ("vert", "frag"):
            args += [f"out.{stage}.isaText={d / (stage + '.isa')}", f"out.{stage}.isaInfo={d / (stage + '.info')}"]
        start = time.monotonic()
        proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=d)
        limit = TIMEOUT_S if deadline is None else max(1.0, min(TIMEOUT_S, deadline - time.monotonic()))
        timer = threading.Timer(limit, proc.kill)
        timer.start()
        out = proc.stdout.read().decode(errors="replace")
        proc.stdout.close()
        _, status, usage = os.wait4(proc.pid, 0)
        timer.cancel()
        proc.returncode = os.waitstatus_to_exitcode(status)
        seconds = time.monotonic() - start
        infos = [d / f"{stage}.info" for stage in ("vert", "frag")]
        if proc.returncode != 0 or not all(i.exists() for i in infos):
            log = d / "log.txt"
            lines = [line for line in (log.read_text(errors="replace") if log.exists() else out).splitlines() if line.strip()]
            if seconds >= limit:
                lines.append(f"killed after {limit:.0f} s")
            return name, {"error": lines[-12:] or [f"glc exit {proc.returncode}"]}
        try:
            vs, fs = (stats(i.read_text()) for i in infos)
        except BuildError as e:
            return name, {"error": [str(e)]}
        return name, {"vgpr": fs["vgpr"], "scratch": max(vs["scratch"], fs["scratch"]), "code": vs["code"] + fs["code"],
                      "code_vert": vs["code"], "code_frag": fs["code"],
                      "memory_mb": round(usage.ru_maxrss / 1024), "seconds": round(seconds, 1)}


def judge(name, got, budget):
    """Failure messages for one program against its budget entry (None: a new program)."""
    if "error" in got:
        return [f"{name}: AMD compiler failed: {' / '.join(got['error'])}"]
    fails = []
    if budget is None:
        if got["code"] > NEW_CODE:
            fails.append(f"{name}: new program with {got['code'] // 1024} KB of AMD code (limit for a new program {NEW_CODE // 1024} KB)")
        if got["memory_mb"] > NEW_MEMORY_MB:
            fails.append(f"{name}: new program needs {got['memory_mb']} MB of AMD compiler memory (limit {NEW_MEMORY_MB} MB)")
        return fails
    # each stage on its own: in fx the vertex stage is 95 % of the code, a sum would hide a fragment ×3
    missing = [k for k in ("code_vert", "code_frag", "memory_mb", "scratch") if k not in budget]
    if missing:
        return [f"{name}: budget entry has no {', '.join(missing)} (record it: --record --jobs 1)"]
    for stage in ("vert", "frag"):
        key = "code_" + stage
        if got[key] > max(budget[key] * (1 + GROWTH), budget[key] + CODE_SLACK):
            fails.append(f"{name}: AMD {stage} code {got[key] // 1024} KB, budget {budget[key] // 1024} KB (+{GROWTH:.0%} allowed)")
    if got["memory_mb"] > max(budget["memory_mb"] * (1 + GROWTH), budget["memory_mb"] + MEMORY_SLACK_MB):
        fails.append(f"{name}: AMD compiler memory {got['memory_mb']} MB, budget {budget['memory_mb']} MB (+{GROWTH:.0%} allowed)")
    if got["scratch"] > budget["scratch"]:
        fails.append(f"{name}: spills {got['scratch']} dwords per lane to scratch memory, budget {budget['scratch']}")
    return fails


def record(budget, results, names, only):
    """New budget: measured entries for names; with only, the others kept; refused while any of names failed."""
    failed = [n for n in names if "error" in results[n]]
    if failed:
        raise BuildError("not recorded, these programs did not compile: " + ", ".join(failed))
    programs = dict(budget["programs"]) if only else {}
    for n in names:
        programs[n] = {k: results[n][k] for k in ("code_vert", "code_frag", "memory_mb", "scratch")}
    return {"rga": RGA_VERSION, "asic": ASIC, "programs": dict(sorted(programs.items()))}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tree", type=Path, default=SHADERS, help="shaders directory (core/, include/, program/)")
    ap.add_argument("--only", help="regex on program names")
    ap.add_argument("--record", action="store_true", help="write the measured values as the new budget (use --jobs 1)")
    ap.add_argument("--jobs", type=int, default=max(1, min(4, (os.cpu_count() or 2) // 2)))
    ap.add_argument("--json", type=Path, help="write all measurements here")
    ap.add_argument("--deadline", type=float, default=900, help="seconds for the whole run (CI job limit 20 min)")
    args = ap.parse_args()
    exe, mc = glc(), vanilla()
    todo = programs(args.tree)
    if args.only:
        todo = [(n, k) for n, k in todo if re.search(args.only, n)]
    names = [n for n, _ in todo]
    deadline = time.monotonic() + args.deadline
    results = {}
    with ThreadPoolExecutor(args.jobs) as pool:
        # printed as each program finishes: a hang in a shared include is named before any job limit
        for name, got in pool.map(lambda nk: compile_one(exe, args.tree, mc, *nk, deadline), todo):
            results[name] = got
            print(f"{name}: " + (f"FAILED {' / '.join(got['error'])}" if "error" in got
                                 else f"{got['code'] // 1024} KB, {got['memory_mb']} MB, {got['seconds']} s"), flush=True)
    budget = json.loads(BUDGET.read_text()) if BUDGET.exists() else {"programs": {}}
    rows = ["| program | VGPR | scratch dw | code KB | compiler MB | s | budget code KB / MB |", "|---|---|---|---|---|---|---|"]
    fails = []
    for name in names:
        got, b = results[name], budget["programs"].get(name)
        if "error" in got:
            rows.append(f"| {name} | FAILED ||||||")
        else:
            limit = f"{(b.get('code_vert', 0) + b.get('code_frag', 0)) // 1024} / {b.get('memory_mb')}" if b else "new"
            rows.append(f"| {name} | {got['vgpr']} | {got['scratch']} | {got['code'] // 1024} | {got['memory_mb']} | {got['seconds']} | {limit} |")
        fails += judge(name, got, b)
    gone = sorted(set(budget["programs"]) - {n for n, _ in programs(args.tree)})
    report = f"AMD OpenGL compiler (RGA {RGA_VERSION} glc, {ASIC} = RX 6600 XT, RDNA2)\n\n" + "\n".join(rows)
    if gone:
        report += "\n\nIn the budget but no longer in the tree (dropped at the next --record): " + ", ".join(gone)
    print(report)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(report + "\n\n" + "\n".join(f"- {m}" for m in fails) + "\n")
    if args.json:
        args.json.write_text(json.dumps(results, indent=1, sort_keys=True))
    if args.record:
        try:
            BUDGET.write_text(json.dumps(record(budget, results, names, args.only), indent=1) + "\n")
        except BuildError as e:
            print(e)
            return 1
        print(f"budget written: {BUDGET.relative_to(ROOT)}")
        return 0
    for m in fails:
        print("FAILED  " + m)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
