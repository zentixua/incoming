"""Разбор прогона стенда: frames.csv и events.jsonl одного клиента (версия × пак) → числа по окнам замера.

Окно — отрезок времени из событий `window` (начало и конец); кадр входит в окно, если начался в нём (у первого удара —
и кадр, который шёл в миг попадания: его худший кадр и есть замер). Кадр со снимком и следующий за ним (его интервал
включает чтение кадра) в замер не идут. Все окна плана обязательны: нет окна или в нём мало кадров — прогон не годен.
Только стандартная библиотека: разбор и шлагбаум идут в CI без пакетов (test_gate.py).

Величины кадра (мс):
- `interval` — от начала кадра до начала следующего, как видит игрок (1000 / к/с): в строке обвязки — время от начала
  прошлого кадра (`prev_interval_ms`), поэтому кадру достаётся число из строки следующего;
- `cpu` — работа кадра на процессоре: RenderFrameEvent.Pre → Post (мир, слои модов, интерфейс; без обмена буферов);
- `gpu` — видеокарта от первой до последней метки кадра (glQueryCounter); стадии — отрезки между метками.
"""
import csv
import json
import math
from pathlib import Path

import plan


def percentile(values, q):
    """Процентиль с линейной интерполяцией (как numpy по умолчанию); пусто — None."""
    v = sorted(values)
    if not v:
        return None
    k = (len(v) - 1) * q / 100
    lo, hi = math.floor(k), math.ceil(k)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def stats(values):
    v = [x for x in values if x is not None]
    if not v:
        return None
    return {"n": len(v), "mean": sum(v) / len(v), "p50": percentile(v, 50), "p95": percentile(v, 95),
            "p99": percentile(v, 99), "max": max(v)}


def _num(s):
    return float(s) if s not in ("", None) else None


def read_frames(path):
    out = []
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            stages = {}
            for part in (row.get("stages") or "").split(";"):
                if "=" in part:
                    k, v = part.split("=", 1)
                    stages[k] = float(v)
            out.append({"frame": int(row["frame"]), "t": float(row["t_ms"]), "prev": _num(row["prev_interval_ms"]),
                        "cpu": _num(row["cpu_ms"]), "gpu": _num(row["gpu_ms"]), "shot": int(row["shot"] or 0), "stages": stages})
    out.sort(key=lambda f: f["frame"])
    # длительность кадра — время до начала следующего: оно в строке следующего кадра (последний кадр — без неё)
    for a, b in zip(out, out[1:] + [None]):
        a["interval"] = b["prev"] if b is not None and b["frame"] == a["frame"] + 1 else None
    return out


def read_events(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def windows(events):
    """Окна замера: имя → (начало, конец), мс."""
    begin, out = {}, {}
    for e in events:
        if e["type"] != "window":
            continue
        if e["edge"] == "begin":
            begin[e["name"]] = e["t_ms"]
        elif e["name"] in begin:
            out[e["name"]] = (begin.pop(e["name"]), e["t_ms"])
    return out


def in_range(frames, t0, t1):
    return [f for f in frames if t0 <= f["t"] < t1 and f["shot"] == 0]


def overlapping(frames, t0, t1):
    """Кадры, которые шли в окне хоть миг: начатые в нём и тот, что начался раньше и шёл в его начало."""
    return [f for f in frames if f["shot"] == 0 and f["t"] < t1
            and (f["t"] >= t0 or (f["interval"] is not None and f["t"] + f["interval"] > t0))]


def summarize(frames, events, t0, t1):
    """Числа окна: кадры, процессор, видеокарта, стадии (p50 и среднее), тик сервера, упор, строки проб."""
    fr = in_range(frames, t0, t1)
    if not fr:
        return None
    w = {"duration_s": (t1 - t0) / 1000, "frames": len(fr), "fps": len(fr) / ((t1 - t0) / 1000),
         "interval": stats([f["interval"] for f in fr]), "cpu": stats([f["cpu"] for f in fr]),
         "gpu": stats([f["gpu"] for f in fr])}
    labels = sorted({k for f in fr for k in f["stages"]})
    w["stages"] = {k: {"p50": percentile([f["stages"][k] for f in fr if k in f["stages"]], 50),
                       "mean": sum(f["stages"].get(k, 0) for f in fr) / len(fr)} for k in labels}
    srv = [e for e in events if e["type"] == "srv" and t0 <= e["t_ms"] < t1]
    w["mspt"] = stats([e["mspt"] for e in srv])
    w["projectiles"] = max((e["projectiles"] for e in srv), default=None)
    w["entities"] = max((e["entities"] for e in srv), default=None)
    gpu50, iv50 = (w["gpu"] or {}).get("p50"), w["interval"]["p50"] if w["interval"] else None
    w["bound"] = None if gpu50 is None or not iv50 else ("gpu" if gpu50 >= 0.85 * iv50 else "cpu")
    probes = {}
    for e in events:
        if e["type"] == "probe" and t0 <= e["t_ms"] < t1:
            probes[e["name"].split("#")[0].rsplit(".", 1)[-1] + "." + e["name"].split("#")[1]] = e["value"]
    w["probes"] = probes
    return w


def buckets(frames, t0, t1, size_s):
    """Корзины по size_s секунд: среднее и p95 интервала, p50 видеокарты, p95 процессора."""
    out = []
    n = int(round((t1 - t0) / 1000 / size_s))
    for i in range(n):
        a, b = t0 + i * size_s * 1000, t0 + (i + 1) * size_s * 1000
        fr = in_range(frames, a, b)
        iv = [f["interval"] for f in fr if f["interval"] is not None]
        out.append({"t_s": i * size_s, "frames": len(fr), "interval_mean": sum(iv) / len(iv) if iv else None,
                    "interval_p95": percentile(iv, 95), "gpu_p50": percentile([f["gpu"] for f in fr if f["gpu"] is not None], 50),
                    "cpu_p95": percentile([f["cpu"] for f in fr if f["cpu"] is not None], 95)})
    return out


def growth(bks, minute_s=60):
    """Накопление: последняя минута против первой (среднее интервала): отношение и обе величины."""
    if not bks:
        return None
    size = bks[1]["t_s"] - bks[0]["t_s"] if len(bks) > 1 else minute_s
    k = max(1, int(round(minute_s / size)))
    if len(bks) < 2 * k:
        return None

    def mean(bs):
        v = [b["interval_mean"] for b in bs if b["interval_mean"] is not None]
        return sum(v) / len(v) if v else None
    first, last = mean(bks[:k]), mean(bks[-k:])
    return {"first_ms": first, "last_ms": last, "ratio": last / first if first and last else None}


def acc_load(events, scene, t0, t1):
    """Нагрузка накопления в окне: попадания волн в первую и последнюю минуту окна и после него (план — `planned` в
    минуту), опоздание команд волн от их расписания по выполнению сервером (событие cmd: сервер начал `ran_ms`, должна —
    `due_ms`): вставший сервер выполнил бы их пачкой, хоть клиент и слал вовремя."""
    w = scene["waves"]
    hits = sorted(e["t_ms"] for e in events if e["type"] == "impact" and e["salvo"] == scene["id"])
    cmds = [e for e in events if e["type"] == "cmd" and e.get("tag") == scene["id"]]
    late = [e["ran_ms"] - e["due_ms"] for e in cmds if e.get("due_ms") is not None and e.get("ran_ms") is not None]
    minute = 60_000
    return {"planned": w["strikes"] * minute / 1000 / w["every_s"], "wave": w["strikes"],
            "first": sum(1 for t in hits if t0 <= t < t0 + minute), "last": sum(1 for t in hits if t1 - minute <= t <= t1),
            "inside": sum(1 for t in hits if t0 <= t <= t1), "after": sum(1 for t in hits if t > t1),
            "commands": len(cmds), "late_max_ms": max(late) if late else None}


def analyze(run_dir, scenes, only=None):
    """Один клиент (версия × пак): окна, первый удар, накопление, проверки годности (only — сцены выборки, None — все)."""
    run_dir = Path(run_dir)
    frames = read_frames(run_dir / "frames.csv")
    events = read_events(run_dir / "events.jsonl")
    wins = windows(events)
    res = {"windows": {}, "checks": [], "meta": {}}
    for name, (t0, t1) in wins.items():
        s = summarize(frames, events, t0, t1)
        if s:
            res["windows"][name] = s
    first = wins.get("first")
    if first:
        fr = overlapping(frames, first[0], first[1])
        iv = [f["interval"] for f in fr if f["interval"] is not None]
        gp = [f["gpu"] for f in fr if f["gpu"] is not None]
        res["first"] = {"worst_ms": max(iv) if iv else None, "worst_gpu_ms": max(gp) if gp else None}
    acc_scene = next((s for s in scenes["scenes"] if s.get("waves")), None)
    if acc_scene and acc_scene["id"] in wins:
        t0, t1 = wins[acc_scene["id"]]
        bks = buckets(frames, t0, t1, acc_scene.get("bucket_s", 10))
        res["acc"] = {"buckets": bks, "growth": growth(bks), "load": acc_load(events, acc_scene, t0, t1)}
    gl = [e for e in events if e["type"] == "gl"]
    if gl:
        res["meta"]["gl"] = {k: gl[0][k] for k in ("renderer", "version", "vendor", "width", "height")}
    res["meta"]["once"] = {e["name"]: e["value"] for e in events if e["type"] == "once"}
    res["meta"]["impacts"] = {}
    for e in events:
        if e["type"] == "await":
            res["meta"]["impacts"][e["salvo"]] = {"got": e["impacts"], "expect": e["expect"], "why": e["why"], "took_s": e["took_s"]}
    done = [e for e in events if e["type"] == "done"]
    res["meta"]["done"] = done[0] if done else None
    res["meta"]["frames"] = len(frames)
    res["meta"]["duration_s"] = (frames[-1]["t"] - frames[0]["t"]) / 1000 if frames else 0
    res["meta"]["shots"] = sorted(e["name"] for e in events if e["type"] == "shot")
    res["checks"] = checks(events, res, plan.windows(scenes, only), acc_scene if acc_scene and (only is None or acc_scene["id"] in only) else None)
    return res


def checks(events, res, expected=(), acc_scene=None):
    """Годен ли прогон для сравнения: строки «что не так» (пусто — годен). expected — окна плана (все обязательны),
    acc_scene — сцена накопления плана (её нагрузка должна прийтись на окно)."""
    bad = []
    if not res["meta"]["done"]:
        bad.append("прогон не дошёл до конца (нет события done)")
    if any(e["type"] == "limit" for e in events):
        bad.append("прогон упёрся в предел времени")
    for e in events:
        if e["type"] == "cmd" and not e["ok"] and e["tag"] != "setup":
            bad.append(f"команда не прошла: {e['cmd']}")
        if e["type"] == "tp-timeout":
            bad.append(f"камера не дошла до {e['eye']}")
        if e["type"] == "impact-timeout":
            bad.append(f"окно {e['name']}: нет попадания залпа {e['salvo']}")
    for salvo, i in res["meta"]["impacts"].items():
        if i["why"] != "all":
            bad.append(f"залп {salvo}: {i['got']} попаданий из {i['expect']} ({i['why']})")
    salvos = {e["id"] for e in events if e["type"] == "salvo"}
    for salvo in sorted(salvos - set(res["meta"]["impacts"])):
        bad.append(f"залп {salvo}: конца нет (нет события await)")
    if not any(e["type"] == "gl" for e in events):
        bad.append("нет строки видеокарты")
    begun = {e["name"] for e in events if e["type"] == "window" and e["edge"] == "begin"}
    for name in expected:
        if name not in res.get("windows", {}) and name != FIRST:
            bad.append(f"окно {name}: " + ("нет кадров" if name in begun else "не снято"))
    for name, w in res.get("windows", {}).items():
        if name == FIRST:
            continue
        if not w.get("gpu"):
            bad.append(f"окно {name}: нет меток видеокарты")
        if w["frames"] < MIN_FRAMES:
            bad.append(f"окно {name}: {w['frames']} кадров (меньше {MIN_FRAMES})")
    # первый удар: замер — худший кадр, шедший в окне; клиент, вставший на всё окно, — тоже число, а не пустота
    if FIRST in expected and (res.get("first") or {}).get("worst_ms") is None:
        bad.append("первый удар: нет числа (окна нет или ни один кадр не шёл в нём)")
    if acc_scene:
        load = (res.get("acc") or {}).get("load")
        name = acc_scene["id"]
        if not load:
            bad.append(f"накопление {name}: окна нет — нагрузку не проверить")
        else:
            if load["after"]:
                bad.append(f"накопление {name}: {load['after']} попаданий после окна — последняя минута мерила не весь план")
            want = next((e["commands"] for e in events if e["type"] == "salvo" and e["id"] == name), None)
            if load["commands"] != want:
                bad.append(f"накопление {name}: прошло команд волн {load['commands']} из {want}")
            if load["late_max_ms"] is None:
                bad.append(f"накопление {name}: у команд волн нет срока или времени выполнения (due_ms, ran_ms) — расписание не проверить")
            elif load["late_max_ms"] > LATE_MS:
                bad.append(f"накопление {name}: команды волн не по расписанию (опоздание до {load['late_max_ms']:.0f} мс, можно {LATE_MS})")
            for part in ("first", "last"):
                if abs(load[part] - load["planned"]) > load["wave"]:
                    bad.append(f"накопление {name}: в {'первую' if part == 'first' else 'последнюю'} минуту окна {load[part]} попаданий, "
                               f"по плану {load['planned']:g} ± волна ({load['wave']})")
        if not (res.get("acc") or {}).get("growth"):
            bad.append(f"накопление {name}: нет числа роста")
    return bad


FIRST = "first"
# окно с меньшим числом кадров — не замер (клиент стоял)
MIN_FRAMES = 20
# команда волны позже расписания больше чем на столько — волны пошли пачкой (тик клиента 50 мс, снимок кадра ~0,5 с)
LATE_MS = 2000
# попаданий в первую и последнюю минуту окна накопления — по плану (strikes × 60 / every_s) ± одна волна: одна минута
# против другой сравнима, только если в обе пришлась одна нагрузка. Допуск — волна, а не доля: окно начинается с первого
# попадания (одного шахеда волны 0), полёт шахедов гуляет на ±3 с, и через каждую границу минуты переходит часть волны
# (v3.7.0: 35/25, 31/28, 32/28 при плане 30). Пачку волн ловит расписание (LATE_MS), волны за окном — `after`
