"""Шлагбаум стенда: кандидат против принятого выпуска по числам results.json (README.md, «Условие влития»).

Провал:
- окна «100 ударов» (`100/*`, любая камера, любой пак): видеокарта p50 или процессор p95 (работа кадра Pre → Post) хуже
  принятого больше допуска; нет окна или числа у кандидата или у принятого — тоже провал. Кадр p95 (интервал — что
  видит игрок) — в отчёте, без провала;
- первый удар: худший кадр от взрыва до +5 с больше чем вдвое против принятого и больше 100 мс; числа нет — провал;
- накопление: последняя минута против первой выросла больше, чем у принятого, сверх допуска. Против самого роста
  кандидата (дословно «последняя минута дороже первой больше допуска») — нельзя: у принятого он есть и так (дым копится),
  и тогда два прогона одной версии проваливали бы друг друга — а приёмка стенда требует, чтобы они проходили;
- кандидат снят на другой видеокарте или с другим кадром (строка OpenGL, размер кадра), чем принятый.
Допуск — из шума: принятый снят дважды, допуск = max(5 %, 3 × разброс двух прогонов) по каждой величине и окну
(`tolerance`); без калибровки — 5 % с пометкой. Калибровка (`calibrate`) годна, только если оба прогона годны, сняты
одинаково (стенд, паки, видеокарта, кадр) и второй против первого проходит шлагбаум; иначе принятого нет. Остальные окна — в отчёте, без провала. Вид числом не судится: сцены, где
доля «дымных» пикселей или яркость кадра заметно другие (`look_flags`), помечаются ревьюеру.

Только стандартная библиотека (CI: test_gate.py).
"""
GATED = "100/"
# величины шлагбаума (спека: видеокарта p50 и процессор p95) и те, что только в отчёте
METRICS = (("gpu", "p50"), ("cpu", "p95"))
REPORTED = METRICS + (("interval", "p95"),)
GL_KEYS = ("renderer", "version", "width", "height")
MIN_TOL = 0.05
NOISE_K = 3.0
FIRST_RATIO, FIRST_MIN_MS = 2.0, 100.0
# вид: доля дымных пикселей (абсолютная разница) и средняя яркость (0–1)
LOOK_SMOKE, LOOK_LUMA = 0.10, 0.08


def metric(window, m):
    part, q = m
    return ((window or {}).get(part) or {}).get(q)


def spread(a, b):
    """Разброс двух прогонов: |a − b| / среднее."""
    if a is None or b is None or a + b == 0:
        return None
    return abs(a - b) / ((a + b) / 2)


def tolerance(run1, run2):
    """Допуск по двум прогонам принятого: пак → окно → величина → доля; и для накопления."""
    tol = {}
    for pack, r1 in run1["packs"].items():
        r2 = run2["packs"].get(pack)
        if not r2:
            continue
        t = tol.setdefault(pack, {"windows": {}})
        for name, w1 in r1["windows"].items():
            w2 = r2["windows"].get(name)
            for m in METRICS:
                s = spread(metric(w1, m), metric(w2, m))
                if s is not None:
                    t["windows"].setdefault(name, {})[".".join(m)] = max(MIN_TOL, NOISE_K * s)
        g1, g2 = ((r1.get("acc") or {}).get("growth") or {}).get("ratio"), ((r2.get("acc") or {}).get("growth") or {}).get("ratio")
        s = spread(g1, g2)
        if s is not None:
            t["acc"] = max(MIN_TOL, NOISE_K * s)
    return tol


def mean_runs(run1, run2):
    """Принятый для сравнения — среднее двух прогонов калибровки (по числам окон, первого удара и накопления)."""
    def avg(a, b):
        if isinstance(a, dict) and isinstance(b, dict):
            return {k: avg(a[k], b.get(k)) for k in a}
        if isinstance(a, (int, float)) and not isinstance(a, bool) and isinstance(b, (int, float)) and not isinstance(b, bool):
            return (a + b) / 2
        return a
    out = {k: v for k, v in run1.items() if k != "packs"}
    out["packs"] = {}
    for pack, r1 in run1["packs"].items():
        r2 = run2["packs"].get(pack)
        if not r2:
            out["packs"][pack] = r1
            continue
        p = dict(r1)
        p["windows"] = {n: avg(w, r2["windows"][n]) if n in r2["windows"] else w for n, w in r1["windows"].items()}
        if r1.get("first") and r2.get("first"):
            p["first"] = avg(r1["first"], r2["first"])
        if (r1.get("acc") or {}).get("growth") and (r2.get("acc") or {}).get("growth"):
            p["acc"] = dict(r1["acc"])
            p["acc"]["growth"] = avg(r1["acc"]["growth"], r2["acc"]["growth"])
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


def calibrate(run1, run2):
    """Принятый по двум прогонам калибровки: {"base" (среднее), "tol", "self_check" (второй против первого), "problems"}.
    Годен, только если problems пуст: оба прогона годны, сняты одинаково (контекст — стенд, моды, паки, сцены,
    видеокарта, окно; строка OpenGL и кадр у каждого пака) и второй против первого проходит шлагбаум — иначе среднее
    и допуск подмешали бы негодный прогон или шум другой видеокарты."""
    problems = []
    for r in (run1, run2):
        for pack, x in r["packs"].items():
            if x.get("checks"):
                problems.append(f"{r.get('name')} {pack}: не годен: " + "; ".join(x["checks"]))
    c1, c2 = run1.get("context") or {}, run2.get("context") or {}
    diff = sorted(k for k in set(c1) | set(c2) if c1.get(k) != c2.get(k))
    if diff:
        problems.append("прогоны сняты иначе: " + ", ".join(diff))
    if set(run1["packs"]) != set(run2["packs"]):
        problems.append(f"паки прогонов разные: {sorted(run1['packs'])} и {sorted(run2['packs'])}")
    for pack in sorted(set(run1["packs"]) & set(run2["packs"])):
        problems += [f"{pack}: прогоны сняты иначе: {x}" for x in gl_diff(run2["packs"][pack], run1["packs"][pack])]
    tol = tolerance(run1, run2)
    v = compare(run2, run1, tol)
    if not v["pass"]:
        problems.append("второй прогон против первого не проходит: " + "; ".join(v["fails"]))
    return {"base": mean_runs(run1, run2), "tol": tol, "self_check": v, "problems": problems}


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
