#!/usr/bin/env python3
"""Стенд сцен: одна версия Incoming × шейдерпаки, время кадра и кадры, кэш принятого, шлагбаум (README.md).

  stand.py prepare <jar> [--size WxH]                 jar, Sodium, Iris, паки, скрипт клиента (без видеокарты)
  stand.py play <имя> <jar> [--packs …] [--scenes …]  клиенты по пакам подряд (внутри tools/laptop_job.sh --gpu)
  stand.py analyze <имя>                              out/<имя>/results.json из сырых данных
  stand.py baseline <выпуск> [--packs …]              принятый: два прогона → кэш с допуском (run.sh дважды)
  stand.py gate <имя> [--against <выпуск>]            вердикт против принятого из кэша → verdict.json
               [--base-run <имя> --tol-from <кэш>]    … или против одного прогона с допуском кэша (приёмка)
  stand.py pr <номер> [--post]                        PR: jar CI головы, прогон, вердикт; --post — статус и комментарий
  stand.py key <jar> [--packs …] [--scenes …]         ключ кэша (что снимали и на чём)

<jar> — выпуск (v3.7.0), `ci:<ветка или SHA>` (артефакт incoming-jar прогона build), путь к jar.
Паки: none (Iris без шейдерпака), complementary, photon — по умолчанию все три. Сцены — id из scenes.json (по умолчанию
все). Каталоги — mod/run/stand/{dl,game,out,cache} (STAND_ROOT переопределяет). Только стандартная библиотека.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import tomllib
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(HERE))
import client  # noqa: E402
import gate  # noqa: E402
import plan  # noqa: E402
import results  # noqa: E402

STAND = Path(os.environ.get("STAND_ROOT", ROOT / "mod" / "run" / "stand"))
DL, GAME, OUT, CACHE = STAND / "dl", STAND / "game", STAND / "out", STAND / "cache"
REPO, PACK_REPO = "zentixua/incoming", "zentixua/open-skies"
# пак стенда → файл pw.toml в pack/shaderpacks сборки (None — Iris без шейдерпака)
PACKS = {"none": None, "complementary": "complementary-unbound", "photon": "photon-shader"}
MODS = ("sodium", "iris")
STATUS = "Стенд сцен"
DEFAULT_SIZE = "2560x1600"
UA = {"User-Agent": "zentixua/incoming stand (github.com/zentixua/incoming)"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def sha(path, algo="sha256"):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def gh(*args, json_out=False) -> Any:
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"gh {' '.join(args)}: {r.stderr.strip()}")
    return json.loads(r.stdout) if json_out else r.stdout


# ---------------------------------------------------------------- моды и паки сборки

def pw_toml(kind, name):
    """pack/<kind>/<name>.pw.toml сборки open-skies (репозиторий закрытый — через gh)."""
    import base64
    data = gh("api", f"repos/{PACK_REPO}/contents/pack/{kind}/{name}.pw.toml", "--jq", ".content")
    return tomllib.loads(base64.b64decode(data).decode())


def fetch_file(meta, into):
    """Файл из pw.toml (url, sha512) в каталог into; есть и совпал — не качать."""
    into.mkdir(parents=True, exist_ok=True)
    path = into / meta["filename"]
    want = meta["download"]["hash"]
    if meta["download"]["hash-format"] != "sha512":
        raise SystemExit(f"{meta['filename']}: хеш {meta['download']['hash-format']}, жду sha512")
    if path.exists() and sha(path, "sha512") == want:
        return path
    with urllib.request.urlopen(urllib.request.Request(meta["download"]["url"], headers=UA), timeout=120) as r:
        data = r.read()
    if hashlib.sha512(data).hexdigest() != want:
        raise SystemExit(f"{meta['filename']}: sha512 не совпал")
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_bytes(data)
    tmp.replace(path)
    log(f"скачан {meta['filename']} ({len(data) // 1024} КБ)")
    return path


def fetch_pack(packs, offline=False):
    """Sodium, Iris и шейдерпаки сборки — по sha512 из её pw.toml: {имя: (путь, sha512)}. Что взяли — в DL/pack-lock.json:
    offline (внутри задачи ноутбука) берёт файлы по нему и сверяет sha512, без сети."""
    lock_file = DL / "pack-lock.json"
    lock = json.loads(lock_file.read_text()) if lock_file.exists() else {}
    out = {}
    for name in [*MODS, *(p for p in packs if PACKS[p])]:
        if offline:
            if name not in lock:
                raise SystemExit(f"{name}: нет в {lock_file} — сначала stand.py prepare")
            path = DL / ("mods" if name in MODS else "shaderpacks") / lock[name]["filename"]
            if not path.exists() or sha(path, "sha512") != lock[name]["sha512"]:
                raise SystemExit(f"{path}: нет или sha512 не тот — stand.py prepare")
            out[name] = (path, lock[name]["sha512"])
            continue
        meta = pw_toml("mods", name) if name in MODS else pw_toml("shaderpacks", PACKS[name])
        path = fetch_file(meta, DL / ("mods" if name in MODS else "shaderpacks"))
        out[name] = (path, meta["download"]["hash"])
        lock[name] = {"filename": path.name, "sha512": meta["download"]["hash"]}
    if not offline:
        lock_file.write_text(json.dumps(lock, indent=1))
    return out


# ---------------------------------------------------------------- jar Incoming

def resolve_jar(spec):
    """Выпуск vX.Y.Z (GitHub), ci:<ветка|SHA> (артефакт incoming-jar прогона build) или путь → (путь, подпись)."""
    p = Path(spec)
    if p.is_file():
        return p.resolve(), p.stem.removeprefix("incoming-")
    if spec.startswith("v"):
        d = DL / "jars" / spec
        jars = sorted(d.glob("incoming-*.jar")) if d.is_dir() else []
        if not jars:
            d.mkdir(parents=True, exist_ok=True)
            gh("release", "download", spec, "-R", REPO, "-p", "incoming-*.jar", "-D", str(d), "--clobber")
            jars = sorted(d.glob("incoming-*.jar"))
        if len(jars) != 1:
            raise SystemExit(f"{spec}: jar выпуска не один: {jars}")
        return jars[0], spec
    if spec.startswith("ci:"):
        ref = spec[3:]
        # ветка → её голова сейчас; прогон build — по SHA (у веток PR он pull_request, у main — push)
        head = ref if len(ref) >= 7 and all(c in "0123456789abcdef" for c in ref.lower()) \
            else gh("api", f"repos/{REPO}/commits/{ref}", "--jq", ".sha").strip()
        runs = gh("run", "list", "-R", REPO, "-w", "build", "--status", "success", "-L", "100", "--json",
                  "databaseId,headSha", json_out=True)
        run = next((r for r in runs if r["headSha"].startswith(head)), None)
        if not run:
            raise SystemExit(f"{spec}: нет успешного прогона build у {head[:12]} (jar — артефакт incoming-jar)")
        d = DL / "jars" / f"ci-{run['headSha'][:12]}"
        jars = sorted(d.glob("incoming-*.jar")) if d.is_dir() else []
        if not jars:
            d.mkdir(parents=True, exist_ok=True)
            gh("run", "download", str(run["databaseId"]), "-R", REPO, "-n", "incoming-jar", "-D", str(d))
            jars = sorted(d.glob("incoming-*.jar"))
        if len(jars) != 1:
            raise SystemExit(f"{spec}: jar артефакта не один: {jars}")
        return jars[0], f"{ref[:12]}@{run['headSha'][:8]}"
    raise SystemExit(f"не понял jar: {spec} (vX.Y.Z, ci:<ветка|SHA> или путь)")


# ---------------------------------------------------------------- ключ кэша

def stand_hash():
    """Версия стенда: исходники обвязки, сборка, сцены, план и запуск клиента (настройки игры, окружение) — то, от чего
    зависит, что снимается. Разбор (results.py, gate.py) в неё не входит: числа принятого считаются из сырых данных кэша
    заново при каждом сравнении (find_baseline)."""
    h = hashlib.sha256()
    files = sorted([*(HERE / "src" / "main").rglob("*"), HERE / "build.gradle", HERE / "gradle.properties",
                    HERE / "scenes.json", HERE / "plan.py", HERE / "client.py"])
    for f in files:
        if f.is_file():
            h.update(str(f.relative_to(HERE)).encode())
            h.update(f.read_bytes())
    return h.hexdigest()


def gpu_info():
    try:
        r = subprocess.run(["nvidia-smi", "--query-gpu=name,driver_version", "--format=csv,noheader"], capture_output=True, text=True, timeout=10)
        name, driver = [s.strip() for s in r.stdout.strip().splitlines()[0].split(",")]
        return name, driver
    except (OSError, IndexError, ValueError, subprocess.SubprocessError):
        return "unknown", "unknown"


def context(packs, scenes_sel, size, files):
    """Всё, кроме jar, от чего зависят числа: стенд, паки и моды (sha512), сцены, видеокарта и драйвер, окно."""
    name, driver = gpu_info()
    return {"stand": stand_hash(), "mods": {m: files[m][1] for m in MODS},
            "packs": {p: files[p][1] if PACKS[p] else "none" for p in packs}, "scenes": scenes_sel or "all",
            "gpu": name, "driver": driver, "size": size}


def key(jar_sha, ctx):
    return hashlib.sha256(json.dumps({"jar": jar_sha, **ctx}, sort_keys=True).encode()).hexdigest()[:16]


# ---------------------------------------------------------------- клиент

def launch_script(size):
    """Скрипт клиента без Gradle (standLaunch): каталог игры — GAME, окно — size."""
    w, h = size.split("x")
    env = dict(os.environ)
    env.setdefault("JAVA_HOME", str(Path.home() / ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta"))
    subprocess.run([str(ROOT / "mod" / "gradlew"), "-p", str(HERE), "standLaunch", f"-PrunDir={GAME}", f"-Pwidth={w}", f"-Pheight={h}",
                    "--console=plain", "-q"], check=True, env=env)
    return HERE / "build" / "launch" / "client.sh"


def cmd_prepare(a):
    jar, label = resolve_jar(a.jar)
    fetch_pack(a.packs)
    script = launch_script(a.size)
    log(f"скрипт клиента {script}")
    # последняя строка — для run.sh: что отдать play
    print(f"JAR\t{jar}\t{a.label or label}")


def cmd_play(a):
    jar = Path(a.jar).resolve()
    label = a.label or jar.stem.removeprefix("incoming-")
    files = fetch_pack(a.packs, offline=True)
    script = HERE / "build" / "launch" / "client.sh"
    if not script.exists():
        raise SystemExit("нет build/launch/client.sh — сначала stand.py prepare")
    out = OUT / a.name
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    ctx = context(a.packs, a.scenes, a.size, files)
    meta = {"name": a.name, "label": label, "jar": {"file": jar.name, "sha256": sha(jar), "spec": a.spec or a.jar},
            "context": ctx, "key": key(sha(jar), ctx), "started": datetime.now(timezone.utc).isoformat(timespec="seconds"), "packs": {}}
    worst = 0
    for pack in a.packs:
        p = client.prepare_game(GAME, jar, files[pack][0] if PACKS[pack] else None, [files[m][0] for m in MODS], a.scenes)
        t0 = time.time()
        log(f"{a.name}: пак {pack}, {len(p['steps'])} шагов")
        with open(out / f"client-{pack}.out", "w") as logf:
            code = client.run(GAME, script, a.size, p["limit_s"], logf, log)
        took = time.time() - t0
        dst = out / pack
        if (GAME / "stand-out").exists():
            shutil.copytree(GAME / "stand-out", dst)
        dst.mkdir(exist_ok=True)
        for f in ("logs/latest.log", "stand-plan.json"):
            if (GAME / f).exists():
                shutil.copy2(GAME / f, dst / Path(f).name)
        if (GAME / "crash-reports").exists():
            shutil.copytree(GAME / "crash-reports", dst / "crash-reports")
        (GAME / "audio.wav").unlink(missing_ok=True)
        meta["packs"][pack] = {"exit": code, "gpu_minutes": round(took / 60, 2), "file": files[pack][0].name if PACKS[pack] else None}
        log(f"{a.name}: пак {pack} — выход {code}, {took / 60:.1f} мин")
        (out / "meta.json").write_text(json.dumps(meta, indent=1, ensure_ascii=False))
        if code == 75:
            return 75
        worst = worst or code
    analyze(out)
    return 1 if worst else 0


def analyze(out, write=True):
    """results.json прогона из сырых данных: числа по пакам + meta."""
    meta = json.loads((out / "meta.json").read_text())
    scenes = plan.load()
    res = {k: meta[k] for k in ("name", "label", "jar", "context", "key", "started")}
    res["packs"] = {}
    for pack, info in meta["packs"].items():
        d = out / pack
        if not (d / "frames.csv").exists() or not (d / "events.jsonl").exists():
            res["packs"][pack] = {"windows": {}, "checks": [f"нет данных клиента (выход {info['exit']})"], "meta": {}}
            continue
        r = results.analyze(d, scenes)
        r["meta"]["exit"] = info["exit"]
        r["meta"]["gpu_minutes"] = info["gpu_minutes"]
        # шейдерпак, который Iris включил на самом деле (иначе кадры «под паком» были бы без него)
        got = str(r["meta"]["once"].get("net.irisshaders.iris.Iris#getCurrentPackName"))
        want = info.get("file")
        if want and want not in got:
            r["checks"].append(f"шейдерпак в игре — {got}, а не {want}")
        if not want and ".zip" in got:
            r["checks"].append(f"пак none, а Iris включил {got}")
        w, h = meta["context"]["size"].split("x")
        gl = r["meta"].get("gl") or {}
        if gl and (gl.get("width") != int(w) or gl.get("height") != int(h)):
            # заливка растёт с площадью кадра: снятое не тем размером с принятым не сравнить
            r["checks"].append(f"кадр {gl.get('width')}×{gl.get('height')} вместо {w}×{h}")
        res["packs"][pack] = r
    if write:
        (out / "results.json").write_text(json.dumps(res, indent=1, ensure_ascii=False))
        for pack, r in res["packs"].items():
            bad = r["checks"]
            log(f"{out.name} {pack}: {'годен' if not bad else 'НЕ ГОДЕН: ' + '; '.join(bad)}")
    return res


def cmd_analyze(a):
    analyze(OUT / a.name)


# ---------------------------------------------------------------- принятый: кэш и допуск

def cache_dir(version, k):
    return CACHE / f"{version}-{k}"


def cmd_baseline(a):
    """Принятый выпуск: два прогона подряд (каждый — своя задача видеокарты), допуск по ним, кэш по ключу."""
    jar, label = resolve_jar(a.version)
    files = fetch_pack(a.packs)
    ctx = context(a.packs, a.scenes, a.size, files)
    k = key(sha(jar), ctx)
    d = cache_dir(a.version, k)
    if (d / "results.json").exists() and not a.force:
        log(f"принятый {a.version} уже в кэше: {d}")
        return 0
    runs = []
    for i in (1, 2):
        name = f"base-{a.version}-{k}-{i}"
        if not (OUT / name / "results.json").exists() or a.force:
            code = subprocess.run([str(HERE / "run.sh"), name, a.version, "--packs", ",".join(a.packs), "--size", a.size]
                                  + (["--scenes", ",".join(a.scenes)] if a.scenes else [])).returncode
            if code != 0:
                log(f"прогон {name}: код {code}")
                return code
        runs.append(json.loads((OUT / name / "results.json").read_text()))
    for r in runs:
        bad = {p: x["checks"] for p, x in r["packs"].items() if x["checks"]}
        if bad:
            raise SystemExit(f"прогон {r['name']} не годен: {bad}")
    d.mkdir(parents=True, exist_ok=True)
    for i, r in enumerate(runs, 1):
        dst = d / f"run{i}"
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(OUT / r["name"], dst)
    tol = gate.tolerance(runs[0], runs[1])
    base = gate.mean_runs(runs[0], runs[1])
    (d / "tolerance.json").write_text(json.dumps(tol, indent=1, ensure_ascii=False))
    (d / "results.json").write_text(json.dumps(base, indent=1, ensure_ascii=False))
    # два прогона одной версии проходят шлагбаум друг против друга (ложного провала нет) — проверка калибровки
    v = gate.compare(runs[1], runs[0], tol)
    (d / "self-check.json").write_text(json.dumps(v, indent=1, ensure_ascii=False))
    log(f"принятый {a.version} в кэше {d}; второй прогон против первого: {'проходит' if v['pass'] else 'ПРОВАЛ: ' + '; '.join(v['fails'])}")
    return 0


def find_baseline(version, ctx):
    """Принятый из кэша: числа и допуск — заново из сырых данных двух прогонов (разбор мог измениться после съёмки)."""
    jar, _ = resolve_jar(version)
    d = cache_dir(version, key(sha(jar), ctx))
    if not ((d / "run1" / "meta.json").exists() and (d / "run2" / "meta.json").exists()):
        return None, None, d
    r1, r2 = analyze(d / "run1", write=False), analyze(d / "run2", write=False)
    return gate.mean_runs(r1, r2), gate.tolerance(r1, r2), d


def accepted_release():
    return gh("release", "view", "-R", REPO, "--json", "tagName", "--jq", ".tagName").strip()


def cmd_gate(a):
    out = OUT / a.name
    cand = json.loads((out / "results.json").read_text())
    if a.base_run:
        # против одного прогона (приёмка: 3.5.0 против 3.4.1), допуск — из кэша принятого (шум стенда)
        base = json.loads((OUT / a.base_run / "results.json").read_text())
        if base["context"] != cand["context"]:
            diff = sorted(k for k in cand["context"] if cand["context"][k] != base["context"].get(k))
            raise SystemExit(f"{a.base_run} снят иначе: {', '.join(diff)}")
        tol = json.loads((Path(a.tol_from) / "tolerance.json").read_text()) if a.tol_from else None
        version, d = base["label"], OUT / a.base_run
    else:
        version = a.against or accepted_release()
        base, tol, d = find_baseline(version, cand["context"])
        if base is None:
            raise SystemExit(f"принятого {version} с тем же стендом, паками, сценами, видеокартой и окном в кэше нет ({d}): "
                             f"stand.py baseline {version}")
    v = gate.compare(cand, base, tol)
    v["against"] = {"version": version, "dir": str(d), "tolerance": a.tol_from if a.base_run else str(d)}
    (out / "verdict.json").write_text(json.dumps(v, indent=1, ensure_ascii=False))
    log(f"{a.name} против {version}: {'ПРОХОДИТ' if v['pass'] else 'ПРОВАЛ'}")
    for f in v["fails"]:
        log("  " + f)
    for n in v["notes"]:
        log("  (" + n + ")")
    return 0 if v["pass"] else 1


# ---------------------------------------------------------------- PR

def cmd_pr(a):
    """PR с картинкой: jar CI его головы против принятого выпуска; --post — статус «Стенд сцен» и комментарий."""
    import pr_note
    pr = gh("pr", "view", str(a.number), "-R", REPO, "--json", "headRefOid,files,url,number", json_out=True)
    head = pr["headRefOid"]
    hit = pr_note.player_facing([f["path"] for f in pr["files"]])
    if not hit and not a.force:
        log(f"PR {a.number} не меняет того, что видит игрок: стенд не нужен")
        if a.post:
            post_status(head, "success", "Стенд не нужен: PR не меняет того, что видит игрок", None)
        return 0
    version = accepted_release()
    name = f"pr{a.number}-{head[:8]}"
    if a.post and (a.packs != list(PACKS) or a.scenes is not None or a.size != DEFAULT_SIZE):
        # статус влития — только у полного стенда: выборка паков или сцен пропустила бы регрессию в остальных
        raise SystemExit("--post — только со всеми паками, всеми сценами и окном по умолчанию; выборка — без --post")
    if a.post:
        post_status(head, "pending", f"Стенд идёт: против {version}", None)
    code = subprocess.run([str(HERE / "run.sh"), name, f"ci:{head}", "--packs", ",".join(a.packs), "--size", a.size,
                           "--label", f"PR {a.number}"]).returncode
    if code != 0:
        if a.post:
            post_status(head, "error", f"Стенд не снял прогон (код {code})", None)
        return code
    cand = json.loads((OUT / name / "results.json").read_text())
    base, tol, d = find_baseline(version, cand["context"])
    if base is None:
        log(f"принятого {version} в кэше нет — снимаю его ({d})")
        ns = argparse.Namespace(version=version, packs=a.packs, scenes=None, size=a.size, force=False)
        if cmd_baseline(ns) != 0:
            return 1
        base, tol, d = find_baseline(version, cand["context"])
    v = gate.compare(cand, base, tol)
    v["against"] = {"version": version, "cache": str(d)}
    (OUT / name / "verdict.json").write_text(json.dumps(v, indent=1, ensure_ascii=False))
    subprocess.run(["uv", "run", str(HERE / "report.py"), name, "--base", str(d)])
    log(f"PR {a.number}: {'проходит' if v['pass'] else 'ПРОВАЛ'}; отчёт {OUT / name / 'report.md'}")
    if a.post:
        body = (OUT / name / "comment.md").read_text() if (OUT / name / "comment.md").exists() else "\n".join(v["fails"])
        comment = gh("api", f"repos/{REPO}/issues/{a.number}/comments", "-f", f"body={body}", "--jq", ".html_url").strip()
        desc = "Время кадра при 100 ударах — не хуже " + version if v["pass"] else f"Хуже {version}: " + v["fails"][0]
        post_status(head, "success" if v["pass"] else "failure", desc[:140], comment)
    return 0 if v["pass"] else 1


def post_status(sha_, state, desc, url):
    args = ["api", f"repos/{REPO}/statuses/{sha_}", "-f", f"state={state}", "-f", f"context={STATUS}", "-f", f"description={desc[:140]}"]
    if url:
        args += ["-f", f"target_url={url}"]
    gh(*args)


def cmd_key(a):
    jar, _ = resolve_jar(a.jar)
    files = fetch_pack(a.packs)
    ctx = context(a.packs, a.scenes, a.size, files)
    print(json.dumps({"key": key(sha(jar), ctx), "jar": sha(jar), **ctx}, indent=1, ensure_ascii=False))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--packs", default=",".join(PACKS), type=lambda s: [x for x in s.split(",") if x])
        p.add_argument("--scenes", default=None, type=lambda s: None if s == "all" else s.split(","))
        p.add_argument("--size", default=DEFAULT_SIZE)
        p.add_argument("--label", help="подпись версии в отчёте")

    p = sub.add_parser("prepare"); p.add_argument("jar"); common(p); p.set_defaults(fn=cmd_prepare)
    p = sub.add_parser("play"); p.add_argument("name"); p.add_argument("jar"); p.add_argument("--spec"); common(p); p.set_defaults(fn=cmd_play)
    p = sub.add_parser("analyze"); p.add_argument("name"); p.set_defaults(fn=cmd_analyze)
    p = sub.add_parser("baseline"); p.add_argument("version"); p.add_argument("--force", action="store_true"); common(p); p.set_defaults(fn=cmd_baseline)
    p = sub.add_parser("gate"); p.add_argument("name"); p.add_argument("--against", help="выпуск из кэша (по умолчанию — последний)")
    p.add_argument("--base-run", help="вместо кэша — прогон out/<имя>"); p.add_argument("--tol-from", help="каталог кэша, чей допуск взять")
    p.set_defaults(fn=cmd_gate)
    p = sub.add_parser("pr"); p.add_argument("number", type=int); p.add_argument("--post", action="store_true")
    p.add_argument("--force", action="store_true"); common(p); p.set_defaults(fn=cmd_pr)
    p = sub.add_parser("key"); p.add_argument("jar"); common(p); p.set_defaults(fn=cmd_key)
    a = ap.parse_args()
    for pk in getattr(a, "packs", []) or []:
        if pk not in PACKS:
            raise SystemExit(f"нет пака {pk}: {', '.join(PACKS)}")
    sys.exit(a.fn(a) or 0)


if __name__ == "__main__":
    main()
