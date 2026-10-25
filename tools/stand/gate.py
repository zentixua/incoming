"""Шлагбаум стенда: кандидат против принятого выпуска по числам results.json (README.md, «Условие влития»).

Провал:
- окна «100 ударов» (`100/*`, любая камера, любой пак): видеокарта p50 или процессор p95 (время процессора потока
  отрисовки за кадр Pre → Post) хуже принятого больше допуска; нет окна или числа у кандидата или у принятого — тоже
  провал. Настенное Pre → Post и кадр p95 (интервал — что видит игрок) — в отчёте, без провала: в них ожидания
  драйвера и вытеснение чужими задачами;
- первый удар: худший кадр от взрыва до +5 с больше чем вдвое против принятого и больше 100 мс; числа нет — провал;
- накопление: последняя минута против первой выросла больше, чем у принятого, сверх допуска. Против самого роста
  кандидата (дословно «последняя минута дороже первой больше допуска») — нельзя: у принятого он есть и так (дым копится),
  и тогда два прогона одной версии проваливали бы друг друга — а приёмка стенда требует, чтобы они проходили;
- кандидат снят на другой видеокарте или с другим кадром (строка OpenGL, размер кадра), чем принятый.
Допуск — из шума: принятый снят N = 3 раза, база — медиана прогонов, допуск = max(5 %, 1,5 × (max − min) / медиана) по
каждой величине и окну (`tolerance`; размах трёх прогонов ≈ 1,7 σ, полтора размаха ≈ 2,5 σ шума; решение координатора
25.10 вместо прежних двух прогонов и 3 × разброса, где одна пара с чужой нагрузкой давала допуск до 120 %); без
калибровки — 5 % с пометкой. Калибровка (`calibrate`) годна, только если все прогоны годны, сняты одинаково (стенд,
паки, видеокарта, кадр), тик сервера в окнах шлагбаума у них сходится (MSPT_TOL: одна версия — разница только шум) и
каждый проходит шлагбаум против медианы остальных; иначе принятого нет. Остальные окна — в отчёте, без провала. Вид числом не судится: сцены, где
доля «дымных» пикселей или яркость кадра заметно другие (`look_flags`), помечаются ревьюеру.

Только стандартная библиотека (CI: test_gate.py).
"""
GATED = "100/"
# величины шлагбаума (спека: видеокарта p50 и процессор p95 — процессор потока отрисовки) и те, что только в отчёте
METRICS = (("gpu", "p50"), ("thread", "p95"))
REPORTED = METRICS + (("cpu", "p95"), ("interval", "p95"))
GL_KEYS = ("renderer", "version", "width", "height")
MIN_TOL = 0.05
# прогонов калибровки — не меньше (решение координатора 25.10): по двум медиана — среднее, а размах — одна разность
MIN_RUNS = 3
# допуск — столько размахов прогонов калибровки (max − min) / медиана
RANGE_K = 1.5
# тик сервера в окне шлагбаума у прогонов калибровки (одна версия) — не дальше этой доли от их медианы: внутри окна
# секундные значения ходят на 5–7 % (σ), среднее за 20 с — на ~1,5 %, а пара 25.10 с чужими задачами рядом разошлась
# на 12–15 % по всем пакам и окнам; 8 % — между ними. У кандидата тик — его собственная работа (PR может законно её
# менять), поэтому годность кандидата решает прямо измеренная чужая нагрузка (results.foreign), а не тик
MSPT_TOL = 0.08
FIRST_RATIO, FIRST_MIN_MS = 2.0, 100.0
# вид: доля дымных пикселей (абсолютная разница) и средняя яркость (0–1)
LOOK_SMOKE, LOOK_LUMA = 0.10, 0.08


def metric(window, m):
    part, q = m
    return ((window or {}).get(part) or {}).get(q)


def _median(xs):
    v = sorted(xs)
    n = len(v)
    return v[n // 2] if n % 2 else (v[n // 2 - 1] + v[n // 2]) / 2


def rel_range(values):
    """Размах прогонов: (max − min) / медиана; нет числа у кого-то или медиана 0 — None."""
    if not values or any(v is None for v in values):
        return None
    m = _median(values)
    return (max(values) - min(values)) / m if m else None


def tolerance(runs):
    """Допуск по прогонам калибровки: пак → окно → величина → доля; и для накопления."""
    tol = {}
    for pack in runs[0]["packs"]:
        rs = [r["packs"].get(pack) for r in runs]
        if any(x is None for x in rs):
            continue
        t = tol.setdefault(pack, {"windows": {}})
        for name in rs[0]["windows"]:
            ws = [x["windows"].get(name) for x in rs]
            for m in METRICS:
                s = rel_range([metric(w, m) for w in ws])
                if s is not None:
                    t["windows"].setdefault(name, {})[".".join(m)] = max(MIN_TOL, RANGE_K * s)
        s = rel_range([((x.get("acc") or {}).get("growth") or {}).get("ratio") for x in rs])
        if s is not None:
            t["acc"] = max(MIN_TOL, RANGE_K * s)
    return tol


def median_runs(runs):
    """Принятый для сравнения — медиана прогонов калибровки по каждому числу окон, первого удара и накопления (то, чего
    нет у всех, — как у первого)."""
    def med(xs):
        a = xs[0]
        if isinstance(a, dict) and all(isinstance(x, dict) for x in xs):
            return {k: med([a[k]] + [x.get(k) for x in xs[1:]]) for k in a}
        if all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in xs):
            return _median(xs)
        return a
    out = {k: v for k, v in runs[0].items() if k != "packs"}
    out["packs"] = {}
    for pack, r1 in runs[0]["packs"].items():
        rs = [r["packs"].get(pack) for r in runs]
        if any(x is None for x in rs):
            out["packs"][pack] = r1
            continue
        p = dict(r1)
        p["windows"] = {n: med([x["windows"][n] for x in rs]) if all(n in x["windows"] for x in rs) else w for n, w in r1["windows"].items()}
        if all(x.get("first") for x in rs):
            p["first"] = med([x["first"] for x in rs])
        if all((x.get("acc") or {}).get("growth") for x in rs):
            p["acc"] = dict(r1["acc"])
            p["acc"]["growth"] = med([x["acc"]["growth"] for x in rs])
        out["packs"][pack] = p
    return out


def compare(cand, base, tol=None):
    """Вердикт: {"pass", "fails": [строки], "rows": [сравнение по окнам], "notes": [...]}. Пак кандидата без
    принятого не судится (сказано в notes); окно «100», которое есть у принятого и нет у кандидата, — провал."""
    fails, rows, notes = [], [], []
    if tol is None:
        notes.append(f"допуск без калибровки: {MIN_TOL:.0%} на всё")
    for pack, c in cand["packs"].items():
        b = base["packs"].get(pack)
        if not b:
            notes.append(f"пак {pack}: у принятого не снят — не судится")
            continue
        if c.get("checks"):
            fails.append(f"{pack}: прогон кандидата не годен: " + "; ".join(c["checks"]))
        if b.get("checks"):
            fails.append(f"{pack}: прогон принятого не годен: " + "; ".join(b["checks"]))
        fails += [f"{pack}: снято иначе, чем принятый: {x}" for x in gl_diff(c, b)]
        t = (tol or {}).get(pack, {})
        for name in sorted(set(b["windows"]) | set(c["windows"])):
            bw, cw = b["windows"].get(name), c["windows"].get(name)
            gated = name.startswith(GATED)
            if cw is None or bw is None:
                if gated:
                    fails.append(f"{pack} {name}: нет окна у {'кандидата' if cw is None else 'принятого'}")
                continue
            for m in REPORTED:
                cv, bv = metric(cw, m), metric(bw, m)
                judged = gated and m in METRICS
                if cv is None or bv is None or bv == 0:
                    if judged:
                        fails.append(f"{pack} {name} {'.'.join(m)}: нет числа у {'кандидата' if cv is None else 'принятого'}")
                    continue
                lim = (t.get("windows", {}).get(name) or {}).get(".".join(m), MIN_TOL) if m in METRICS else None
                ratio = cv / bv - 1
                bad = judged and ratio > lim
                rows.append({"pack": pack, "window": name, "metric": ".".join(m), "cand": cv, "base": bv, "change": ratio,
                             "tol": lim, "gated": judged, "fail": bad})
                if bad:
                    fails.append(f"{pack} {name} {'.'.join(m)}: {cv:.2f} мс против {bv:.2f} (+{ratio:.0%}, допуск {lim:.0%})")
        cf, bf = (c.get("first") or {}).get("worst_ms"), (b.get("first") or {}).get("worst_ms")
        # сцены нет в выборке — нет ни у кого; есть у одного — у другого провалена
        if (c.get("first") or b.get("first")) and (cf is None or bf is None):
            fails.append(f"{pack} первый удар: нет числа у {'кандидата' if cf is None else 'принятого'}")
        elif cf is not None and bf is not None:
            bad = cf > FIRST_RATIO * bf and cf > FIRST_MIN_MS
            rows.append({"pack": pack, "window": "first", "metric": "worst", "cand": cf, "base": bf, "change": cf / bf - 1 if bf else None,
                         "tol": None, "gated": True, "fail": bad})
            if bad:
                fails.append(f"{pack} первый удар: худший кадр {cf:.0f} мс против {bf:.0f} (больше {FIRST_RATIO:g}× и {FIRST_MIN_MS:g} мс)")
        cg, bg = ((c.get("acc") or {}).get("growth") or {}).get("ratio"), ((b.get("acc") or {}).get("growth") or {}).get("ratio")
        if (c.get("acc") or b.get("acc")) and (cg is None or bg is None):
            fails.append(f"{pack} накопление: нет числа роста у {'кандидата' if cg is None else 'принятого'}")
        elif cg is not None and bg is not None:
            lim = t.get("acc", MIN_TOL)
            change = cg / bg - 1
            bad = change > lim
            rows.append({"pack": pack, "window": "acc", "metric": "growth", "cand": cg, "base": bg, "change": change,
                         "tol": lim, "gated": True, "fail": bad})
            if bad:
                fails.append(f"{pack} накопление: последняя минута ×{cg:.2f} к первой против ×{bg:.2f} у принятого (допуск {lim:.0%})")
    return {"pass": not fails, "fails": fails, "rows": rows, "notes": notes}


def gl_diff(a, b):
    """Чем различается съёмка двух прогонов одного пака: строка OpenGL (видеокарта, драйвер) и размер кадра."""
    ga, gb = (a.get("meta") or {}).get("gl") or {}, (b.get("meta") or {}).get("gl") or {}
    return [f"{k} {ga.get(k)} против {gb.get(k)}" for k in GL_KEYS if ga.get(k) != gb.get(k)]


def calibrate(runs):
    """Принятый по прогонам калибровки (не меньше MIN_RUNS): {"base" (медиана), "tol", "self_check" (каждый против медианы
    остальных: список вердиктов), "problems"}. Годен, только если problems пуст: все прогоны годны, сняты одинаково
    (контекст — стенд, моды, паки, сцены, видеокарта, окно, java, KWin, профиль питания; строка OpenGL и кадр у каждого
    пака), тик сервера в окнах шлагбаума сходится (MSPT_TOL) и каждый проходит шлагбаум против медианы остальных — иначе
    медиана и допуск подмешали бы негодный прогон, шум другой машины или чужую нагрузку."""
    problems = []
    if len(runs) < MIN_RUNS:
        return {"base": None, "tol": None, "self_check": [], "problems": [f"прогонов калибровки {len(runs)} — нужно не меньше {MIN_RUNS}"]}
    for r in runs:
        for pack, x in r["packs"].items():
            if x.get("checks"):
                problems.append(f"{r.get('name')} {pack}: не годен: " + "; ".join(x["checks"]))
    c0 = runs[0].get("context") or {}
    for r in runs[1:]:
        c = r.get("context") or {}
        diff = sorted(k for k in set(c0) | set(c) if c0.get(k) != c.get(k))
        if diff:
            problems.append(f"{r.get('name')} снят иначе, чем {runs[0].get('name')}: " + ", ".join(diff))
        if set(r["packs"]) != set(runs[0]["packs"]):
            problems.append(f"паки прогонов разные: {sorted(runs[0]['packs'])} и {sorted(r['packs'])}")
    for pack in sorted(set.intersection(*(set(r["packs"]) for r in runs))):
        for r in runs[1:]:
            problems += [f"{pack}: {r.get('name')} снят иначе: {x}" for x in gl_diff(r["packs"][pack], runs[0]["packs"][pack])]
        for name in sorted(n for n in runs[0]["packs"][pack]["windows"] if n.startswith(GATED)):
            ms = [((r["packs"][pack]["windows"].get(name) or {}).get("mspt") or {}).get("mean") for r in runs]
            if any(m is None for m in ms):
                problems.append(f"{pack} {name}: нет тика сервера у прогона калибровки")
                continue
            med = _median(ms)
            if med and max(abs(m - med) for m in ms) / med > MSPT_TOL:
                problems.append(f"{pack} {name}: тик сервера прогонов разошёлся — {', '.join(f'{m:.1f}' for m in ms)} мс "
                                f"(можно ±{MSPT_TOL:.0%} от медианы): машина была занята")
    tol = tolerance(runs)
    checks = []
    for i, r in enumerate(runs):
        v = compare(r, median_runs(runs[:i] + runs[i + 1:]), tol)
        v["run"] = r.get("name")
        checks.append(v)
        if not v["pass"]:
            problems.append(f"{r.get('name')} против медианы остальных не проходит: " + "; ".join(v["fails"]))
    return {"base": median_runs(runs), "tol": tol, "self_check": checks, "problems": problems}


def look_flags(cand_look, base_look):
    """Кадры, где вид заметно другой: {пак: {кадр: {smoke, luma}}} у обоих → строки «пак кадр: что»."""
    out = []
    for pack, shots in cand_look.items():
        for shot, c in shots.items():
            b = (base_look.get(pack) or {}).get(shot)
            if not b:
                continue
            why = []
            if abs(c["smoke"] - b["smoke"]) > LOOK_SMOKE:
                why.append(f"дымных пикселей {c['smoke']:.0%} против {b['smoke']:.0%}")
            if abs(c["luma"] - b["luma"]) > LOOK_LUMA:
                why.append(f"яркость {c['luma']:.2f} против {b['luma']:.2f}")
            if why:
                out.append(f"{pack} {shot}: " + ", ".join(why))
    return out
