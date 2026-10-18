#!/usr/bin/env python3
"""Расстановка стаи шахедов плана до съёмки (n02, 18.10). Стая из 20 шахедов с пусками на одной полосе (z≈1250) и
точками пути на другой (z≈770) на последнем отрезке шла к камере на одной глубине и в кадре выстраивалась в ряды:
пересъёмка сдвигом сроков (eta) рядов не убрала. Здесь — модель полёта, по которой места шахедов в кадре известны до
съёмки, и перебор мест пуска, точек пути разной дальности и сроков с проверкой той же `take_checks.swarm_verdict`.

Модель (сверена с журналами двух дублей T09: медиана 3 блока, 90 % — 6–7, в кадре — 1–3 % полуширины): шахед,
пущенный издалека (вне мира), идёт на маршевой высоте цель+45 прямыми отрезками «место пуска → точки пути → цель»
со скоростью 2,1 блока за тик; в тик t (от начала плана) он в `SPEED·(20·eta − t)` блоках пути до цели. Пуск —
за длину пути до срока; разносчик пусков постановщика (`Newisle.Launches`) сдвигает пуск ближе 7 тиков к прошлому,
поэтому пуски плана — не чаще. Стая не редеет ради порядка: среднее число шахедов в кадре (`density`) — не ниже
`DENSITY_MIN`, при равных рядах — гуще. Перехват: ракеты ЗРК достаются первым шахедам, вошедшим в зону ПВО (ЗРК и пулемётчики), падают через
`SAM_LAG` тиков (по журналу T09: в 70–120 блоках южнее установок).

    python3 tools/trailer/swarm_plan.py check <папка дубля> [--plan n02]    модель против журнала дубля
    python3 tools/trailer/swarm_plan.py plan [--plan n02] [--seed N] [--iters N] [--write]
"""
import argparse
import json
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import take_checks as T  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SCENES = os.path.join(HERE, "scenes")
SPEED = 2.1          # блоков за тик, маршевая шахеда (WeaponSpec.DRONE)
ABOVE = 45           # маршевая высота над целью у захода издалека (журналы T09)
LAUNCH_GAP = 7       # тиков между пусками: ближе разносчик сдвигает пуск (ANY_GAP 3 + разброс до 3)
SAME_PLACE = 45      # места пуска ближе — одна очередь разносчика (SAME_PLACE 40)
GROUND = 65          # высота установок ПВО у залива (земля южного края Города 1)
SAM_LAG = 30         # тиков от входа в зону до падения (журналы T09: 19–38)
ROW_BAND = 0.015     # полоса прямой «ряда», доля высоты кадра
ROW_MAX = 36         # ряд из 4 дольше стольких тиков (1,8 с) — строй; n02 17.10 — 4,3 с (до 4 случайных у прямой
                     # при 18 шахедах в кадре держатся ~1,5 с и строем не видны)
ROW5_MAX = 12        # ряд из 5+ — не дольше 0,6 с (n02 17.10 — 3,1 с)
CLUMP_R, CLUMP_MAX = 0.08, 12   # кучка: 4+ в круге радиуса 0,08 высоты кадра — не дольше 0,6 с (n02 17.10 — 1,55 с)
KILL_SPREAD = 0.25   # сбитые — не ближе этой доли ширины кадра друг к другу
KILL_X, KILL_Y = 0.85, (-0.6, 0.75)   # где сбитый виден: не у края кадра и не под верхней кромкой
DENSITY_MIN = 13     # шахедов в кадре в среднем за окно (n02 как снят 17.10 — 14)
PRE_TICKS = 200      # с какого тика до окна ЗРК уже стоят (ready плана — раньше момента записи)


def _path(s):
    return [tuple(s["from"])] + [tuple(v) for v in s.get("via", [])] + [(s["to"][0], s["to"][2])]


def length(s):
    p = _path(s)
    return sum(math.dist(a, b) for a, b in zip(p, p[1:]))


def launch_tick(s):
    """Тик пуска от начала плана (меньше нуля — до плана)."""
    return 20 * s["eta"] - length(s) / SPEED


def position(s, t):
    """Место шахеда в тик t от начала плана: (x, y, z) или None — ещё не пущен или уже у цели."""
    rem = SPEED * (20 * s["eta"] - t)
    if rem < 0:
        return None
    y = s["to"][1] + ABOVE
    p = _path(s)
    for a, b in zip(reversed(p[1:]), reversed(p[:-1])):
        seg = math.dist(a, b)
        if rem <= seg:
            k = rem / seg if seg else 0
            return (a[0] + (b[0] - a[0]) * k, y, a[1] + (b[1] - a[1]) * k)
        rem -= seg
    return None


def frames(strikes, cam, fov, ticks, gone=None):
    """Места в кадре по тикам: [(тик, {номер: (x, y)})]; `gone` — {номер: тик падения} (сбитые)."""
    out = []
    for t in ticks:
        pts = {}
        for i, s in enumerate(strikes):
            if gone and i in gone and t >= gone[i]:
                continue
            p = position(s, t)
            if p is not None:
                q = T._frame(cam, fov, p)
                if q:
                    pts[i] = q
        out.append((t, pts))
    return out


def rows(fr, k=4, band=ROW_BAND):
    """Самый долгий ряд: k и больше на одной прямой (в полосе ±band высоты кадра) — (тиков подряд, номера)."""
    run, best = {}, (0, ())
    step = fr[1][0] - fr[0][0] if len(fr) > 1 else 1
    for _, pts in fr:
        sp = {i: (p[0] * T.DENSITY_ASPECT, p[1]) for i, p in pts.items()}
        ids = sorted(sp)
        found = set()
        for a in range(len(ids)):
            for b in range(a + 1, len(ids)):
                (x1, y1), (x2, y2) = sp[ids[a]], sp[ids[b]]
                d = math.hypot(x2 - x1, y2 - y1)
                if d < 0.3:
                    continue
                on = tuple(i for i in ids if abs((x2 - x1) * (y1 - sp[i][1]) - (x1 - sp[i][0]) * (y2 - y1)) / d <= 2 * band)
                if len(on) >= k:
                    found.add(on[:k] if len(on) == k else on)
        nxt = {}
        for c in found:
            key = frozenset(c)
            n = max((v for kk, v in run.items() if len(kk & key) >= k), default=0) + step
            nxt[key] = max(nxt.get(key, 0), n)
            if n > best[0]:
                best = (n, tuple(sorted(c)))
        run = nxt
    return best


def clumps(fr, k=4, r=CLUMP_R):
    """Самая долгая кучка: k и больше шахедов в круге радиуса r (доля высоты кадра) — тиков подряд (шаг кадров `fr`)."""
    best = run = 0
    step = fr[1][0] - fr[0][0] if len(fr) > 1 else 1
    for _, pts in fr:
        sp = [(p[0] * T.DENSITY_ASPECT, p[1]) for p in pts.values()]
        hit = any(sum(1 for q in sp if math.dist(p, q) <= 2 * r) >= k for p in sp)
        run = run + step if hit else 0
        best = max(best, run)
    return best


def engagements(strikes, defenders, shots, ticks):
    """Перехваты: [(номер, тик падения, место)] — ракеты ЗРК (`shots` штук) достаются первым по времени шахедам,
    вошедшим в зону любой установки ПВО (`defenders`: [(место, дальность)] — ЗРК и пулемётчики): так в журналах T09
    сбиты первые 3–4 вошедших, через 20–40 тиков после входа."""
    entry = []
    for i, s in enumerate(strikes):
        for t in ticks:
            p = position(s, t)
            if p is not None and any(math.dist(p, at) <= r for at, r in defenders):
                entry.append((t, i))
                break
    out = []
    for t, i in sorted(entry)[:shots]:
        p = position(strikes[i], t + SAM_LAG)
        if p is not None:
            out.append((i, t + SAM_LAG, p))
    return out


def score(strikes, cam, fov, window, swarm, defense):
    """Оценка расстановки: (провалы, ряд 4+ тиков, ряд 5+ тиков, сбитые) — провалы списком строк."""
    ticks = list(range(0, window + 1))
    defenders, shots = defense
    # ЗРК стоят с готовности камеры (ready), до окна: вошедший в зону раньше сбит до плана и ракету тратит мимо кадра
    kills = engagements(strikes, defenders, shots, range(-PRE_TICKS, window + 1))
    gone = {i: t for i, t, _ in kills}
    fr = frames(strikes, cam, fov, ticks, gone)
    problems = list(T.swarm_verdict(fr, swarm))
    dens = sum(len(p) for _, p in fr) / len(fr)
    if dens < DENSITY_MIN:
        problems.append(f"в кадре в среднем {dens:.1f} шахеда, нужно {DENSITY_MIN}+")
    r4 = rows(fr[::2], 4)
    r5 = rows(fr[::2], 5)
    if r4[0] > ROW_MAX:
        problems.append(f"ряд из 4: пути {r4[1]} {r4[0] / 20:.1f} с")
    if r5[0] > ROW5_MAX:
        problems.append(f"ряд из 5+: пути {r5[1]} {r5[0] / 20:.1f} с")
    cl = clumps(fr[::2])
    if cl > CLUMP_MAX:
        problems.append(f"кучка из 4+ шахедов {cl / 20:.1f} с")
    close = close_launches(strikes)
    if close:
        problems.append(f"пусков ближе {LAUNCH_GAP} тиков: {close}")
    places = [s["from"] for s in strikes]
    if any(math.dist(a, b) < SAME_PLACE for i, a in enumerate(places) for b in places[i + 1:]):
        problems.append("места пуска ближе одной очереди разносчика")
    seen = [(i, T._frame(cam, fov, p)) for i, t, p in kills if 0 <= t <= window]
    # сбитый у края кадра не виден: в небе внутри кадра
    seen = [(i, q) for i, q in seen if q and abs(q[0]) <= KILL_X and KILL_Y[0] <= q[1] <= KILL_Y[1]]
    if not 3 <= len(seen) <= 4:
        problems.append(f"сбитых в кадре {len(seen)}, нужно 3–4")
    for (i, a), (j, b) in ((x, y) for n, x in enumerate(seen) for y in seen[n + 1:]):
        if math.hypot((a[0] - b[0]) / 2, (a[1] - b[1]) / 2) < KILL_SPREAD:
            problems.append(f"сбитые {i} и {j} в одном месте неба")
    return problems, r4[0], r5[0], seen


def density(strikes, cam, fov, window, defense):
    """Сколько шахедов в кадре в среднем за окно (со сбитыми): стая не должна редеть, чтобы уйти от рядов."""
    defenders, shots = defense
    ticks = list(range(0, window + 1, 2))
    gone = {i: t for i, t, _ in engagements(strikes, defenders, shots, range(-PRE_TICKS, window + 1))}
    fr = frames(strikes, cam, fov, ticks, gone)
    return sum(len(p) for _, p in fr) / len(fr)


def close_launches(strikes):
    """Пусков ближе `LAUNCH_GAP` тиков к прошлому (их сдвинул бы разносчик)."""
    launches = sorted(launch_tick(s) for s in strikes)
    return sum(1 for a, b in zip(launches, launches[1:]) if b - a < LAUNCH_GAP)


def spread_launches(strikes, eta_max):
    """Раздвинуть пуски: у позднего из близкой пары срок позже на недостающие тики (место в кадре сдвигается на
    2,1 блока за тик — меньше ошибки модели). Новый список ударов."""
    out = [dict(s) for s in strikes]
    for _ in range(len(out) * 4):
        order = sorted(range(len(out)), key=lambda i: launch_tick(out[i]))
        moved = False
        for a, b in zip(order, order[1:]):
            gap = launch_tick(out[b]) - launch_tick(out[a])
            if gap < LAUNCH_GAP:
                out[b]["eta"] = round(min(eta_max, out[b]["eta"] + (LAUNCH_GAP - gap + 0.5) / 20), 2)
                moved = True
                break
        if not moved:
            break
    return out


def mutate(s, rng, limits):
    """Одна правка удара: место пуска, точка пути или срок — в пределах `limits`."""
    s = json.loads(json.dumps(s))
    what = rng.random()
    if what < 0.3:
        s["from"] = [round(rng.uniform(*limits["from_x"])), round(rng.uniform(*limits["from_z"]))]
    elif what < 0.7:
        s["via"] = [[round(rng.uniform(*limits["via_x"])), round(rng.uniform(*limits["via_z"]))]]
    else:
        s["eta"] = round(min(limits["eta"][1], max(limits["eta"][0], s["eta"] + rng.uniform(-1.5, 1.5))), 2)
    return s


def search(strikes, cam, fov, window, swarm, defense, limits, iters=3000, seed=1):
    """Перебор правками по одному удару: лучшее по (число провалов, ряд 4+, ряд 5+). Цели ударов не меняются."""
    rng = random.Random(seed)
    cur = [json.loads(json.dumps(s)) for s in strikes]

    def key(pl):
        pr, r4, r5, _ = score(pl, cam, fov, window, swarm, defense)
        return (len(pr), close_launches(pl), r4, r5, -round(density(pl, cam, fov, window, defense), 1)), pr

    best, why = key(cur)
    for n in range(iters):
        cand = list(cur)
        if n == iters * 3 // 4:
            # к концу — пуски врозь, дальше перебор держит и это
            cand = spread_launches(cand, limits["eta"][1])
        else:
            i = rng.randrange(len(cand))
            cand[i] = mutate(cand[i], rng, limits)
        k, pr = key(cand)
        if k <= best or n == iters * 3 // 4:
            cur, best, why = cand, k, pr
    return cur, (best[0], best[2], best[3], -best[4]), why


# ---------------------------------------------------------------- данные дублей

def load_plan(plan, takes_path=None):
    """Удары, камера, окно, стая и ЗРК плана из scenes: (strikes, cam, fov, window, swarm, (ПВО, ракет), take, shot)."""
    takes = json.load(open(takes_path or os.path.join(SCENES, "newisle-takes.json"), encoding="utf-8"))
    scene = json.load(open(os.path.join(SCENES, "newisle.json"), encoding="utf-8"))
    poses = {p["id"]: p for p in json.load(open(os.path.join(SCENES, "newisle-poses.json"), encoding="utf-8"))}
    take, shot = next((t, s) for t in takes["takes"] for s in t["shots"] if s["plans"][0] == plan)
    f = poses[plan]["frames"][0]
    cam, fov = (f["eye"][0], f["eye"][1], f["eye"][2], f["yaw"], f["pitch"]), f["fov"]
    places = scene["places"]
    defenders, shots = [], 0
    engage = next((a.get("engage", 170) for a in shot.get("ready", []) if a.get("do") == "sam.config"), 170)
    for a in shot.get("ready", []):
        if a.get("do") == "sam":
            at = places[a["at"]]["at"]
            defenders.append(((at[0], GROUND, at[1]), engage))
            shots += int(a.get("load", 1))
    for a in shot.get("cues", []):
        if a.get("do") == "gunners.fire":
            at = next(x["at"] for x in take["stage"] if x.get("do") == "gunners" and x.get("group") == a["group"])
            at = takes["places"][at] if isinstance(at, str) else at
            at = at["at"] if isinstance(at, dict) else at
            defenders.append(((at[0], GROUND, at[1]), a.get("range", 200)))
    swarm = shot["cover"][plan]["swarm"]
    return shot["strikes"], cam, fov, int(20 * shot["seconds"]), swarm, (defenders, shots), take, shot


LIMITS = {"from_x": (-700, 500), "from_z": (1150, 1330), "via_x": (-600, 300), "via_z": (420, 980), "eta": (6.3, 15.0)}


def check(rec, plan, takes_path=None):
    """Модель против журнала дубля: места по тикам окна плана (удар — по сроку с задержкой из result.json)."""
    strikes = load_plan(plan, takes_path)[0]
    res = json.load(open(os.path.join(rec, "result.json"), encoding="utf-8"))["shots"]
    rep = next(s for s in res if s["plans"][0] == plan)["strikes"]
    marks, flights = {}, []
    path = os.path.join(rec, "replays", f"{plan}.events.jsonl")
    for r in T._jsonl(path):
        if "mark" in r:
            marks.setdefault(r["mark"], r["t"])
    flights = [f for f in T.read_events(path)[0] if f.weapon == "drone"]
    start = marks["start"]
    # отчёт удара — в порядке пусков: свой удар по сроку без задержки
    eta = {}
    for r in rep:
        base = r["eta"] - r.get("delay", 0)
        i = min((k for k in range(len(strikes)) if k not in eta), key=lambda k: abs(strikes[k]["eta"] - base))
        eta[i] = r["eta"]
    errs, used = [], set()
    for f in flights:
        ts = [t for t in sorted(f.pos) if start <= t <= start + 120]
        if not ts:
            continue

        def err(i):
            s = dict(strikes[i], eta=eta.get(i, strikes[i]["eta"]))
            d = [math.dist(p, f.pos[t]) for t in ts[::10] if (p := position(s, t - start)) is not None]
            return sum(d) / len(d) if d else 1e9

        i = min((k for k in range(len(strikes)) if k not in used), key=err)
        used.add(i)
        s = dict(strikes[i], eta=eta.get(i, strikes[i]["eta"]))
        errs += [math.dist(p, f.pos[t]) for t in ts if (p := position(s, t - start)) is not None]
    errs.sort()
    return {"places": len(errs), "median": errs[len(errs) // 2], "p90": errs[int(len(errs) * 0.9)], "max": errs[-1]}


def main(argv=None):
    ap = argparse.ArgumentParser(description="расстановка стаи шахедов плана по модели полёта")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check")
    c.add_argument("rec")
    c.add_argument("--plan", default="n02")
    c.add_argument("--takes", help="файл дублей, по которому снят дубль (по умолчанию — scenes)")
    p = sub.add_parser("plan")
    p.add_argument("--plan", default="n02")
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--iters", type=int, default=3000)
    p.add_argument("--write", action="store_true", help="удары плана — в scenes/newisle-takes.json")
    p.add_argument("--out", help="удары расстановки — в этот файл JSON")
    a = ap.parse_args(argv)
    if a.cmd == "check":
        print(json.dumps(check(a.rec, a.plan, a.takes), ensure_ascii=False))
        return 0
    strikes, cam, fov, window, swarm, defense, _, _ = load_plan(a.plan)
    before = score(strikes, cam, fov, window, swarm, defense)
    print("как в сценарии:", before[0], "ряд 4+", before[1], "тиков, 5+", before[2],
          "в кадре в среднем", round(density(strikes, cam, fov, window, defense), 1))
    got, best, why = search(strikes, cam, fov, window, swarm, defense, LIMITS, a.iters, a.seed)
    print("расстановка:", why, "ряд 4+", best[1], "тиков, 5+", best[2], "в кадре в среднем", best[3])
    if a.out:
        json.dump({"strikes": got, "problems": why, "rows4": best[1], "rows5": best[2], "density": best[3]}, open(a.out, "w"), ensure_ascii=False)
    if a.write and not why:
        write(a.plan, got)
        print("записано в scenes/newisle-takes.json")
    return 0 if not why else 1


def write(plan, strikes):
    """Удары плана в файл дублей — строкой на удар, как он записан руками."""
    path = os.path.join(SCENES, "newisle-takes.json")
    text = open(path, encoding="utf-8").read()
    takes = json.loads(text)
    shot = next(s for t in takes["takes"] for s in t["shots"] if s["plans"][0] == plan)
    old = shot["strikes"]
    for s, n in zip(old, strikes):
        src = json.dumps(s, ensure_ascii=False)
        assert text.count(src) == 1, src
        text = text.replace(src, json.dumps(n, ensure_ascii=False))
    json.loads(text)
    open(path, "w", encoding="utf-8").write(text)


if __name__ == "__main__":
    sys.exit(main())
