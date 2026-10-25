#!/usr/bin/env python3
"""Стенд сцен: версия Incoming × шейдерпаки, время кадра и кадры; шлагбаум — пара с принятым в одном сеансе (README.md).

  stand.py prepare <jar> [--size WxH]                 jar, Sodium, Iris, паки, скрипт клиента (без видеокарты)
  stand.py play <имя> <jar> [--packs …] [--scenes …]  клиенты по пакам подряд (внутри tools/laptop_job.sh --gpu)
           [--against <jar принятого>]                … парой: на каждый пак принятый, кандидат, принятый (A1, B, A2);
                                                      пак без прохода — второй парой; вердикт → verdict.json
  stand.py analyze <имя>                              results.json прогона (у пары — каждого из A1, B, A2) из сырых данных
  stand.py gate <имя>                                 вердикт пары заново из сырых данных → verdict.json
  stand.py pr <номер> [--post] [--against <база>]     PR: jar CI головы парой с main, сцена «100», вердикт;
                                                      --post — статус «Стенд сцен» и комментарий

<jar> — выпуск (v3.7.0), `ci:<ветка или SHA>` (артефакт incoming-jar прогона build), путь к jar.
Паки: none (Iris без шейдерпака), complementary, photon — по умолчанию все три. Сцены — id из scenes.json (по умолчанию
все; у pr — «100», окна шлагбаума). Каталоги — mod/run/stand/{dl,game,out} основной копии репозитория, общие для всех её
деревьев git (INCOMING_STAND_ROOT переопределяет; имя с INCOMING_ — его видит и задача ноутбука). Только стандартная
библиотека.
"""
import argparse
import hashlib
import itertools
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
import facing  # noqa: E402
import gate  # noqa: E402
import plan  # noqa: E402
import results  # noqa: E402



def _main_checkout():
    """Основная копия репозитория (у дерева git — та, чей .git общий); не git — это дерево."""
    try:
        common = subprocess.run(["git", "-C", str(HERE), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                capture_output=True, text=True, check=True).stdout.strip()
        return Path(common).parent
    except (OSError, subprocess.CalledProcessError):
        return ROOT


STAND = Path(os.environ.get("INCOMING_STAND_ROOT") or _main_checkout() / "mod" / "run" / "stand")
DL, GAME, OUT = STAND / "dl", STAND / "game", STAND / "out"
REPO, PACK_REPO = "zentixua/incoming", "zentixua/open-skies"
# пак стенда → файл pw.toml в pack/shaderpacks сборки (None — Iris без шейдерпака)
PACKS = {"none": None, "complementary": "complementary-unbound", "photon": "photon-shader"}
MODS = ("sodium", "iris")
STATUS = "Стенд сцен"
# сцены шлагбаума PR: окна «100» (условие влития — время кадра при 100 ударах)
GATE_SCENES = ["100"]
# пары на пак: вторая — если первая не прошла (gate.decide)
PAIR_TRIES = 2
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


# ---------------------------------------------------------------- версия стенда и контекст

def stand_hash():
    """Версия стенда: исходники обвязки, её сборка (build.gradle с подготовкой запуска standLaunch, settings.gradle,
    Gradle мода, которым она собирается, с wrapper), сцены, план, каталог игры и запуск клиента (client.py: настройки
    игры, окружение, скрипт standLaunch; nested_kwin.sh и его шина — окружение и экран клиента) — то, от чего зависит,
    что снимается. Java клиента и KWin — отдельно в контексте. gpu_lease.sh, который nested_kwin.sh зовёт до запуска
    KWin (аренда), — нет: клиент он не трогает. Разбор (results.py, gate.py) в неё не входит: вердикт считается из сырых
    данных заново (stand.py gate)."""
    h = hashlib.sha256()
    for f in stand_files():
        h.update(str(f.relative_to(ROOT)).encode())
        h.update(f.read_bytes())
    return h.hexdigest()


def stand_files():
    """Файлы версии стенда (stand_hash), по порядку."""
    files = [*(HERE / "src" / "main").rglob("*"), HERE / "build.gradle", HERE / "gradle.properties", HERE / "settings.gradle",
             HERE / "scenes.json", HERE / "plan.py", HERE / "client.py",
             ROOT / "mod" / "gradlew", ROOT / "mod" / "gradle" / "wrapper" / "gradle-wrapper.properties",
             ROOT / "mod" / "gradle" / "wrapper" / "gradle-wrapper.jar", ROOT / "tools" / "nested_kwin.sh",
             ROOT / "tools" / "nested_kwin_bus.conf"]
    return sorted(f for f in files if f.is_file())


def gpu_info():
    try:
        r = subprocess.run(["nvidia-smi", "--query-gpu=name,driver_version", "--format=csv,noheader"], capture_output=True, text=True, timeout=10)
        name, driver = [s.strip() for s in r.stdout.strip().splitlines()[0].split(",")]
        return name, driver
    except (OSError, IndexError, ValueError, subprocess.SubprocessError):
        return "unknown", "unknown"


def kwin_version():
    """Версия композитора, во вложенном экземпляре которого идёт клиент (пакет системы обновляется сам)."""
    try:
        return subprocess.run(["kwin_wayland", "--version"], capture_output=True, text=True, timeout=20).stdout.strip() or "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def context(packs, scenes_sel, size, files):
    """Всё, кроме jar, от чего зависят числа: стенд, паки и моды (sha512), сцены, видеокарта и драйвер, окно, java
    клиента (скрипт запуска — уже подготовлен), KWin, профиль питания."""
    name, driver = gpu_info()
    return {"stand": stand_hash(), "mods": {m: files[m][1] for m in MODS},
            "packs": {p: files[p][1] if PACKS[p] else "none" for p in packs}, "scenes": scenes_sel or "all",
            "gpu": name, "driver": driver, "size": size, "java": client.java_of(SCRIPT), "kwin": kwin_version(),
            "power": client.power_profile()}


# ---------------------------------------------------------------- клиент

SCRIPT = HERE / "build" / "launch" / "client.sh"


def cmd_prepare(a):
    jar, label = resolve_jar(a.jar)
    fetch_pack(a.packs)
    script = client.launch_script(GAME, a.size)
    log(f"скрипт клиента {script}")
    # последняя строка — для run.sh: что отдать play
    print(f"JAR\t{jar}\t{a.label or label}")


def jar_info(jar, label, spec):
    return {"label": label, "file": jar.name, "sha256": sha(jar), "spec": spec}


def shoot(out, pack, jar, files, scenes_sel, size, log_name, quiet=True):
    """Один клиент: версия jar × пак → сырые данные в out/<пак>; назад — (код выхода, минуты видеокарты). quiet — ждать
    тишины перед клиентом (client.run)."""
    p = client.prepare_game(GAME, jar, files[pack][0] if PACKS[pack] else None, [files[m][0] for m in MODS], scenes_sel)
    t0 = time.time()
    log(f"{log_name}: пак {pack}, {len(p['steps'])} шагов")
    out.mkdir(parents=True, exist_ok=True)
    with open(out / f"client-{pack}.out", "w") as logf:
        code = client.run(GAME, SCRIPT, size, p["limit_s"], logf, log, quiet)
    dst = out / pack
    if dst.exists():
        shutil.rmtree(dst)
    if (GAME / "stand-out").exists():
        shutil.copytree(GAME / "stand-out", dst)
    dst.mkdir(exist_ok=True)
    for f in ("logs/latest.log", "stand-plan.json"):
        if (GAME / f).exists():
            shutil.copy2(GAME / f, dst / Path(f).name)
    if (GAME / "crash-reports").exists():
        shutil.copytree(GAME / "crash-reports", dst / "crash-reports")
    (GAME / "audio.wav").unlink(missing_ok=True)
    took = (time.time() - t0) / 60
    log(f"{log_name}: пак {pack} — выход {code}, {took:.1f} мин")
    return code, took


def add_pack(out, meta, pack, code, took, files):
    """Пак в meta.json прогона (пишется после каждого пака: прерванный прогон разбирается тем, что снято)."""
    meta["packs"][pack] = {"exit": code, "gpu_minutes": round(took, 2), "file": files[pack][0].name if PACKS[pack] else None}
    out.mkdir(parents=True, exist_ok=True)
    (out / "meta.json").write_text(json.dumps(meta, indent=1, ensure_ascii=False))


def run_meta(name, info, ctx):
    return {"name": name, "label": info["label"], "jar": info, "context": ctx,
            "started": datetime.now(timezone.utc).isoformat(timespec="seconds"), "packs": {}}


def cmd_play(a):
    jar = Path(a.jar).resolve()
    files = fetch_pack(a.packs, offline=True)
    if not SCRIPT.exists():
        raise SystemExit("нет build/launch/client.sh — сначала stand.py prepare")
    out = OUT / a.name
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    ctx = context(a.packs, a.scenes, a.size, files)
    cand = jar_info(jar, a.label or jar.stem.removeprefix("incoming-"), a.spec or a.jar)
    if a.against:
        return play_pair(a, out, jar, cand, files, ctx)
    meta = run_meta(a.name, cand, ctx)
    worst = 0
    for pack in a.packs:
        code, took = shoot(out, pack, jar, files, a.scenes, a.size, a.name)
        add_pack(out, meta, pack, code, took, files)
        if code == 75:
            return 75
        worst = worst or code
    analyze(out)
    log(f"{a.name}: итог в {out}")
    return 1 if worst else 0


# роли прогонов пары: принятый, кандидат, принятый; у второй пары — с суффиксом
ROLES = ("A1", "B", "A2")


def role_dir(out, role, attempt):
    return out / (role if attempt == 1 else f"{role}-{attempt}")


def play_pair(a, out, jar, cand, files, ctx):
    """Пара: прогрев видеокарты принятым на первом паке, затем на каждый пак A1, B, A2 подряд; пак, который не прошёл
    (gate.compare_pack: провал, шум, негодный прогон), — второй парой сразу же. Итог — verdict.json (judge_pair)."""
    base_jar = Path(a.against).resolve()
    base = jar_info(base_jar, a.against_label or base_jar.stem.removeprefix("incoming-"), a.against_spec or a.against)
    jars = {"A1": (base_jar, base), "B": (jar, cand), "A2": (base_jar, base)}
    pair = {"name": a.name, "cand": cand, "base": base, "context": ctx, "packs": a.packs,
            "started": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    (out / "pair.json").write_text(json.dumps(pair, indent=1, ensure_ascii=False))
    metas = {}
    # прогрев: холодная видеокарта первого прогона шла бы на большей частоте, чем горячая в A2 (85–88 °C в окнах), —
    # расхождение пары от нагрева, а не от шума; прогон прогрева в вердикт не идёт
    if not a.no_warmup and a.packs:
        code, _ = shoot(out / "warmup", a.packs[0], base_jar, files, a.scenes, a.size, f"{a.name} прогрев")
        if code == 75:
            return 75
    for pack in a.packs:
        for attempt in range(1, a.tries + 1):
            for role in ROLES:
                d = role_dir(out, role, attempt)
                j, info = jars[role]
                meta = metas.setdefault(d.name, run_meta(f"{a.name}/{d.name}", info, ctx))
                # тишины ждать только перед A1: B и A2 идут сразу за ним, в тех же условиях, а аренда не стоит впустую
                code, took = shoot(d, pack, j, files, a.scenes, a.size, f"{a.name} {d.name}", quiet=role == "A1")
                add_pack(d, meta, pack, code, took, files)
                if code == 75:
                    return 75
            rs = [analyze(role_dir(out, r, attempt), write=False)["packs"][pack] for r in ROLES]
            v = gate.compare_pack(pack, rs[1], [rs[0], rs[2]])
            log(f"{a.name}: пак {pack}, пара {attempt} — {v['state']}" + "".join(f"\n  {x}" for x in v["noisy"] + v["fails"]))
            if v["state"] == gate.PASS:
                break
    for d in sorted(metas):
        analyze(out / d)
    v = judge_pair(out)
    log(f"{a.name}: {v['state']}; итог в {out}")
    return 0 if v["pass"] else 1


def analyze(out, write=True):
    """results.json прогона из сырых данных: числа по пакам + meta. Вердикт (gate, pr, report.py) всегда разбирает сырые
    данные заново (write=False): числа — по нынешнему разбору, а не по тому, что был при съёмке."""
    out = Path(out)
    meta = json.loads((out / "meta.json").read_text())
    scenes = plan.load()
    only = None if meta["context"]["scenes"] == "all" else meta["context"]["scenes"]
    res = {k: meta[k] for k in ("name", "label", "jar", "context", "started")}
    res["packs"] = {}
    for pack, info in meta["packs"].items():
        d = out / pack
        if not (d / "frames.csv").exists() or not (d / "events.jsonl").exists():
            res["packs"][pack] = {"windows": {}, "checks": [f"нет данных клиента (выход {info['exit']})"], "meta": {}, "load": []}
            continue
        r = results.analyze(d, scenes, only, meta["context"].get("power"))
        r["meta"]["exit"] = info["exit"]
        r["meta"]["gpu_minutes"] = info["gpu_minutes"]
        # шейдерпак, который Iris включил на самом деле (иначе кадры «под паком» были бы без него)
        got = str(r["meta"]["once"].get("net.irisshaders.iris.Iris#getCurrentPackName"))
        want = info.get("file")
        if want and want not in got:
            r["checks"].append(f"шейдерпак в игре — {got}, а не {want}")
        if not want and ".zip" in got:
            r["checks"].append(f"пак none, а Iris включил {got}")
        # имя пака Iris держит и тогда, когда конвейер пака не собрался и рисует ваниль (ошибка шейдера): работает ли
        # пак — только по конвейеру (isPackInUseQuick: IrisRenderingPipeline, проверено по Iris 1.8.14)
        live = str(r["meta"]["once"].get("net.irisshaders.iris.Iris#isPackInUseQuick"))
        if want and live != "true":
            r["checks"].append(f"шейдерпак {want} не рисует (конвейер Iris — {'ваниль' if live == 'false' else live})")
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


def is_pair(out):
    return (Path(out) / "pair.json").exists()


def pair_runs(out):
    """Прогоны пары из сырых данных: {каталог роли: results} (A1, B, A2 и вторая пара, где снималась)."""
    out = Path(out)
    return {d.name: analyze(d, write=False) for d in sorted(out.iterdir()) if d.is_dir() and (d / "meta.json").exists() and d.name != "warmup"}


def judge_pair(out, write=True):
    """Вердикт пары заново из сырых данных: на каждый пак — пары по порядку до первой прошедшей (вторая — где снималась),
    gate.decide;
    → verdict.json. Пак, где пара неполна (прервано), — провал «не снято»."""
    out = Path(out)
    info = json.loads((out / "pair.json").read_text())
    runs = pair_runs(out)
    packs = {}
    for pack in info["packs"]:
        tries = []
        # пары — все снятые (--tries), до первой прошедшей
        for attempt in itertools.count(1):
            names = [role_dir(out, r, attempt).name for r in ROLES]
            got = [runs.get(n, {}).get("packs", {}).get(pack) for n in names]
            if all(g is None for g in got):
                break
            if any(g is None for g in got):
                tries.append({"state": gate.FAIL, "fails": [f"{pack}: пара {attempt} не снята целиком ({', '.join(n for n, g in zip(names, got) if g is None)})"],
                              "noisy": [], "rows": [], "notes": []})
                break
            tries.append(gate.compare_pack(pack, got[1], [got[0], got[2]]))
            # как play_pair: пак, прошедший пару, больше не снимается — пара, снятая под старым правилом, не в счёт
            if tries[-1]["state"] == gate.PASS:
                break
        packs[pack] = gate.decide(tries) if tries else {"state": gate.FAIL, "fails": [f"{pack}: пара не снята"], "noisy": [], "rows": [],
                                                        "notes": [], "tries": 0, "earlier": []}
    v = gate.summary(packs)
    v["cand"], v["base"] = info["cand"], info["base"]
    if write:
        (out / "verdict.json").write_text(json.dumps(v, indent=1, ensure_ascii=False))
    return v


def cmd_analyze(a):
    out = OUT / a.name
    if is_pair(out):
        for d in pair_runs(out):
            analyze(out / d)
    else:
        analyze(out)


def cmd_gate(a):
    out = OUT / a.name
    if not is_pair(out):
        raise SystemExit(f"{out}: не пара (stand.py play … --against <принятый>)")
    v = judge_pair(out)
    log(f"{a.name}: {v['cand']['label']} против {v['base']['label']}: {STATE_WORD[v['state']]}")
    for f in v["noisy"] + v["fails"]:
        log("  " + f)
    for n in v["notes"]:
        log("  (" + n + ")")
    return 0 if v["pass"] else 1


STATE_WORD = {gate.PASS: "ПРОХОДИТ", gate.FAIL: "ПРОВАЛ", gate.NOISY: "ШУМНО — вердикта нет"}


def main_base():
    """База PR — `main` (CLAUDE.md, «Картинка и звук»): голова main с успешным build (push) — её jar, ci:<SHA>."""
    runs = gh("run", "list", "-R", REPO, "-w", "build", "-b", "main", "-e", "push", "--status", "success", "-L", "1",
              "--json", "headSha", json_out=True)
    if not runs:
        raise SystemExit("нет успешного build на main — базы для PR нет")
    return f"ci:{runs[0]['headSha']}", f"main@{runs[0]['headSha'][:8]}"


# ---------------------------------------------------------------- PR

def pr_paths(records):
    """Пути файлов PR из API (pulls/<n>/files): новое имя и у переноса прежнее — перенос из того, что видит игрок, —
    тоже правка видимого."""
    out = []
    for r in records:
        out.append(r["filename"])
        if r.get("previous_filename"):
            out.append(r["previous_filename"])
    return out


def cmd_pr(a):
    """PR с картинкой: jar CI его головы парой с main (сцена «100»; --against — другая база); --post — статус «Стенд
    сцен» и комментарий."""
    pr = gh("pr", "view", str(a.number), "-R", REPO, "--json", "headRefOid,url,number", json_out=True)
    head = pr["headRefOid"]
    files = gh("api", "--paginate", f"repos/{REPO}/pulls/{a.number}/files", "--jq", ".[] | {filename, previous_filename}")
    hit = facing.player_facing(pr_paths(json.loads(line) for line in files.splitlines() if line.strip()))
    if not hit and not a.force:
        log(f"PR {a.number} не меняет того, что видит игрок: стенд не нужен")
        if a.post:
            post_status(head, "success", "Стенд не нужен: PR не меняет того, что видит игрок", None)
        return 0
    base, version = (a.against, a.against) if a.against else main_base()
    name = f"pr{a.number}-{head[:8]}"
    scenes = a.scenes if a.scenes is not None else GATE_SCENES
    full = "--post — только со всеми паками, сценой «100» и окном по умолчанию; выборка — без --post"
    if a.post and (a.packs != list(PACKS) or scenes != GATE_SCENES or a.size != DEFAULT_SIZE):
        # статус влития — только у полного шлагбаума: выборка паков пропустила бы регрессию в остальных
        raise SystemExit(full)
    out = OUT / name
    if a.rejudge:
        # пара этой головы уже снята: вердикт заново из сырых данных (правка разбора не требует пересъёмки)
        if not is_pair(out):
            raise SystemExit(f"{out}: пары этой головы нет — снять без --rejudge")
        info = json.loads((out / "pair.json").read_text())
        ctx = info["context"]
        # полнота — у снятой пары, а не у этой команды
        if a.post and (info["packs"] != list(PACKS) or ctx["scenes"] != GATE_SCENES or ctx["size"] != DEFAULT_SIZE):
            raise SystemExit(f"{full} (снятая пара: {','.join(info['packs'])}, сцены {ctx['scenes']}, {ctx['size']})")
        code = 0
    else:
        if a.post:
            post_status(head, "pending", f"Стенд идёт: парой с {version}", None)
        code = subprocess.run([str(HERE / "run.sh"), name, f"ci:{head}", "--against", base, "--packs", ",".join(a.packs), "--size", a.size,
                               "--label", f"PR {a.number}", "--scenes", ",".join(scenes)]).returncode
    if not is_pair(out) or code not in (0, 1):
        if a.post:
            post_status(head, "error", f"Стенд не снял пару (код {code})", None)
        return code or 1
    v = judge_pair(out)
    version = v["base"].get("label") or version
    # отчёт — тем же путём (judge_pair): его вердикт тот же
    subprocess.run(["uv", "run", str(HERE / "report.py"), name])
    log(f"PR {a.number}: {STATE_WORD[v['state']]}; отчёт {out / 'report.md'}")
    if a.post:
        body = (out / "comment.md").read_text() if (out / "comment.md").exists() else "\n".join(v["noisy"] + v["fails"])
        comment = gh("api", f"repos/{REPO}/issues/{a.number}/comments", "-f", f"body={body}", "--jq", ".html_url").strip()
        if v["state"] == gate.PASS:
            state, desc = "success", f"Время кадра при 100 ударах — не хуже {version}"
        elif v["state"] == gate.FAIL:
            state, desc = "failure", f"Хуже {version}: " + v["fails"][0]
        else:
            # шум пары — не вердикт о PR: снять заново на тихой машине
            state, desc = "error", "Шумно: принятый в паре разошёлся сам с собой — снять заново"
        post_status(head, state, desc[:140], comment)
    return 0 if v["pass"] else 1


def post_status(sha_, state, desc, url):
    args = ["api", f"repos/{REPO}/statuses/{sha_}", "-f", f"state={state}", "-f", f"context={STATUS}", "-f", f"description={desc[:140]}"]
    if url:
        args += ["-f", f"target_url={url}"]
    gh(*args)


def positive(text):
    n = int(text)
    if n < 1:
        raise argparse.ArgumentTypeError("не меньше 1")
    return n


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--packs", default=",".join(PACKS), type=lambda s: [x for x in s.split(",") if x])
        p.add_argument("--scenes", default=None, type=lambda s: None if s == "all" else s.split(","))
        p.add_argument("--size", default=DEFAULT_SIZE)
        p.add_argument("--label", help="подпись версии в отчёте")

    p = sub.add_parser("prepare"); p.add_argument("jar"); common(p); p.set_defaults(fn=cmd_prepare)
    p = sub.add_parser("play"); p.add_argument("name"); p.add_argument("jar"); p.add_argument("--spec")
    p.add_argument("--against", help="jar принятого: снять парой A1, B, A2 на каждый пак")
    p.add_argument("--against-label"); p.add_argument("--against-spec")
    p.add_argument("--no-warmup", action="store_true", help="без прогрева видеокарты перед парой")
    p.add_argument("--tries", type=positive, default=PAIR_TRIES, help="пар на пак, пока не прошёл (1 — без повторной: приёмка)")
    common(p); p.set_defaults(fn=cmd_play)
    p = sub.add_parser("analyze"); p.add_argument("name"); p.set_defaults(fn=cmd_analyze)
    p = sub.add_parser("gate"); p.add_argument("name"); p.set_defaults(fn=cmd_gate)
    p = sub.add_parser("pr"); p.add_argument("number", type=int); p.add_argument("--post", action="store_true")
    p.add_argument("--force", action="store_true")
    p.add_argument("--rejudge", action="store_true", help="не снимать: вердикт по уже снятой паре этой головы")
    p.add_argument("--against", help="база вместо main: выпуск (v3.7.0), ci:<ветка|SHA> или путь")
    common(p); p.set_defaults(fn=cmd_pr)
    a = ap.parse_args()
    for pk in getattr(a, "packs", []) or []:
        if pk not in PACKS:
            raise SystemExit(f"нет пака {pk}: {', '.join(PACKS)}")
    sys.exit(a.fn(a) or 0)


if __name__ == "__main__":
    main()
