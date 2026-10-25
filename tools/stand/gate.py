"""Шлагбаум стенда: кандидат против принятого выпуска, снятых парой в одном сеансе (README.md, «Условие влития»).

Пара: на каждый пак — принятый, кандидат, снова принятый (A1, B, A2) подряд под одной арендой видеокарты. Видеокарта
ноутбука в окнах греется до 85–88 °C, её частота от прогона к прогону ходит 1700–2550 МГц, и два «тихих» прогона одной
версии в разных сеансах расходились на 6–29 % (замер 25.10, NOTES-noise.md); ровная чужая нагрузка поднимает процессор
потока на 30–45 %. Внутри пары это бьёт по обоим одинаково: база — среднее A1 и A2 (линейный дрейф между ними гасится),
а шум сеанса виден по их расхождению.

Провал пака (`compare_pack`):
- окна «100 ударов» (`100/*`, любая камера): видеокарта p50 или процессор p95 (время процессора потока отрисовки за кадр
  Pre → Post) у кандидата хуже базы больше допуска; нет окна или числа — тоже провал. Допуск по каждой величине и окну —
  max(MIN_TOL, SPREAD_K × |A1 − A2| / база). Настенное Pre → Post и кадр p95 — в отчёте, без провала;
- первый удар и накопление, если сцены сняты: худший кадр больше чем вдвое против базы и больше 100 мс; рост последней
  минуты к первой больше, чем у базы, сверх допуска;
- прогон пары не годен (results.checks) или снят иначе (строка OpenGL, кадр).
Шумно (`noisy`) — A1 и A2 в окне шлагбаума разошлись больше MAX_SPREAD: сеанс не годится для вердикта.
Решение (`decide`): пак, который не прошёл с первой пары (провал, шум, негодный прогон), снимается второй парой, и решает
она: провал подтверждён, только если повторился; шумно и во второй — вердикта нет (статус error, а не failure).
Вид числом не судится: сцены, где доля «дымных» пикселей или яркость кадра заметно другие (`look_flags`), помечаются
ревьюеру. Только стандартная библиотека (CI: test_gate.py).
"""
GATED = "100/"
# величины шлагбаума (спека: видеокарта p50 и процессор p95 — процессор потока отрисовки) и те, что только в отчёте
METRICS = (("gpu", "p50"), ("thread", "p95"))
REPORTED = METRICS + (("cpu", "p95"), ("interval", "p95"))
GL_KEYS = ("renderer", "version", "width", "height")
# допуск — не меньше: регрессии, ради которых стенд (3.5.0 и 3.7.0 против 3.4.1), — в разы, улучшения PR — вдвое; 10 %
# не пропустят их и не дадут ложного провала от шума внутри пары
MIN_TOL = 0.10
# допуск — столько расхождений A1 и A2: разность B − (A1 + A2)/2 шумит на √1,5 σ, |A1 − A2| в среднем ≈ 1,13 σ — два
# расхождения ≈ 1,8 σ разности; ложный провал на одной величине — несколько процентов, и его снимает повторная пара
SPREAD_K = 2.0
# A1 и A2 в окне шлагбаума разошлись больше — сеанс шумный (чужая задача в одном из прогонов, троттлинг посреди пары)
MAX_SPREAD = 0.20
FIRST_RATIO, FIRST_MIN_MS = 2.0, 100.0
# вид: доля дымных пикселей (абсолютная разница) и средняя яркость (0–1)
LOOK_SMOKE, LOOK_LUMA = 0.10, 0.08
PASS, FAIL, NOISY = "pass", "fail", "noisy"


def metric(window, m):
    part, q = m
    return ((window or {}).get(part) or {}).get(q)


def _num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def base_of(values):
    """База и расхождение пары принятого по одной величине: (среднее, |A1 − A2| / среднее); у одного прогона —
    (значение, None); нет числа у кого-то или среднее 0 — (None, None)."""
    if not values or any(not _num(v) for v in values):
        return None, None
    mean = sum(values) / len(values)
    if not mean:
        return None, None
    return mean, (max(values) - min(values)) / mean if len(values) > 1 else None


def tol_of(spread):
    return MIN_TOL if spread is None else max(MIN_TOL, SPREAD_K * spread)


def gl_diff(a, b):
    """Чем различается съёмка двух прогонов одного пака: строка OpenGL (видеокарта, драйвер) и размер кадра."""
    ga, gb = (a.get("meta") or {}).get("gl") or {}, (b.get("meta") or {}).get("gl") or {}
    return [f"{k} {ga.get(k)} против {gb.get(k)}" for k in GL_KEYS if ga.get(k) != gb.get(k)]


def compare_pack(pack, c, bases):
    """Кандидат c против принятого bases (пак результатов: [A1, A2] пары или [A] одного прогона) →
    {"state": pass|fail|noisy, "fails", "noisy", "rows", "notes"}. Негодный прогон и снятое иначе — провал."""
    fails, noisy, rows, notes = [], [], [], []
    if len(bases) < 2:
        notes.append(f"{pack}: принятый снят один раз — допуск {MIN_TOL:.0%} без оценки шума")
    if c.get("checks"):
        fails.append(f"{pack}: прогон кандидата не годен: " + "; ".join(c["checks"]))
    for i, b in enumerate(bases, 1):
        if b.get("checks"):
            fails.append(f"{pack}: прогон принятого A{i} не годен: " + "; ".join(b["checks"]))
        fails += [f"{pack}: A{i} снят иначе, чем кандидат: {x}" for x in gl_diff(c, b)]
    names = set(c.get("windows", {}))
    for b in bases:
        names |= set(b.get("windows", {}))
    for name in sorted(names):
        cw, bws = c.get("windows", {}).get(name), [b.get("windows", {}).get(name) for b in bases]
        gated = name.startswith(GATED)
        if cw is None or any(w is None for w in bws):
            if gated:
                fails.append(f"{pack} {name}: нет окна у {'кандидата' if cw is None else 'принятого'}")
            continue
        for m in REPORTED:
            judged = gated and m in METRICS
            cv = metric(cw, m)
            bv, spread = base_of([metric(w, m) for w in bws])
            if not _num(cv) or bv is None:
                if judged:
                    fails.append(f"{pack} {name} {'.'.join(m)}: нет числа у {'кандидата' if not _num(cv) else 'принятого'}")
                continue
            lim = tol_of(spread) if m in METRICS else None
            change = cv / bv - 1
            bad = judged and change > lim
            loud = judged and spread is not None and spread > MAX_SPREAD
            rows.append({"pack": pack, "window": name, "metric": ".".join(m), "cand": cv, "base": bv,
                         "bases": [metric(w, m) for w in bws], "spread": spread, "change": change, "tol": lim,
                         "gated": judged, "fail": bad, "noisy": loud})
            if loud:
                noisy.append(f"{pack} {name} {'.'.join(m)}: принятый A1/A2 — "
                             + " / ".join(f"{metric(w, m):.2f}" for w in bws) + f" мс (расхождение {spread:.0%}, можно {MAX_SPREAD:.0%})")
            if bad:
                fails.append(f"{pack} {name} {'.'.join(m)}: {cv:.2f} мс против {bv:.2f} (+{change:.0%}, допуск {lim:.0%})")
    cf = (c.get("first") or {}).get("worst_ms")
    bf, _ = base_of([(b.get("first") or {}).get("worst_ms") for b in bases])
    # сцены нет в выборке — нет ни у кого; есть у одного — у другого провалена
    if c.get("first") or any(b.get("first") for b in bases):
        if not _num(cf) or bf is None:
            fails.append(f"{pack} первый удар: нет числа у {'кандидата' if not _num(cf) else 'принятого'}")
        else:
            bad = cf > FIRST_RATIO * bf and cf > FIRST_MIN_MS
            rows.append({"pack": pack, "window": "first", "metric": "worst", "cand": cf, "base": bf, "change": cf / bf - 1,
                         "tol": None, "gated": True, "fail": bad, "noisy": False})
            if bad:
                fails.append(f"{pack} первый удар: худший кадр {cf:.0f} мс против {bf:.0f} (больше {FIRST_RATIO:g}× и {FIRST_MIN_MS:g} мс)")
    cg = ((c.get("acc") or {}).get("growth") or {}).get("ratio")
    bg, gspread = base_of([((b.get("acc") or {}).get("growth") or {}).get("ratio") for b in bases])
    if c.get("acc") or any(b.get("acc") for b in bases):
        if not _num(cg) or bg is None:
            fails.append(f"{pack} накопление: нет числа роста у {'кандидата' if not _num(cg) else 'принятого'}")
        else:
            lim = tol_of(gspread)
            change = cg / bg - 1
            bad = change > lim
            rows.append({"pack": pack, "window": "acc", "metric": "growth", "cand": cg, "base": bg, "spread": gspread,
                         "change": change, "tol": lim, "gated": True, "fail": bad, "noisy": False})
            if bad:
                fails.append(f"{pack} накопление: последняя минута ×{cg:.2f} к первой против ×{bg:.2f} у принятого (допуск {lim:.0%})")
    # шумный сеанс вердикта не даёт — даже если в нём же «провал»: его снимает повторная пара
    state = NOISY if noisy else FAIL if fails else PASS
    return {"state": state, "fails": fails, "noisy": noisy, "rows": rows, "notes": notes}


def compare(cand, bases):
    """Кандидат (results) против принятого (список results: пара A1, A2 или один прогон) по всем пакам кандидата →
    {"pass", "state", "fails", "noisy", "rows", "notes", "packs": {пак: compare_pack}}. Пак без принятого не судится."""
    packs, notes = {}, []
    for pack, c in cand["packs"].items():
        bs = [b["packs"].get(pack) for b in bases]
        if any(b is None for b in bs):
            notes.append(f"пак {pack}: у принятого не снят — не судится")
            continue
        packs[pack] = compare_pack(pack, c, bs)
    return summary(packs, notes)


def summary(packs, notes=()):
    """Вердикт по пакам ({пак: compare_pack или decide}): провал — хоть один провален, иначе шумно — хоть один шумный."""
    states = {v["state"] for v in packs.values()}
    state = FAIL if FAIL in states else NOISY if NOISY in states else PASS
    out = {"state": state, "pass": state == PASS, "packs": packs, "notes": list(notes)}
    for k in ("fails", "noisy", "rows"):
        out[k] = [x for v in packs.values() for x in v[k]]
    out["notes"] += [x for v in packs.values() for x in v["notes"]]
    return out


def decide(tries):
    """Пак по парам (compare_pack по порядку; вторая — только если первая не прошла) → итог пака: решает последняя пара;
    провалы и шум первой — в отчёт (`earlier`)."""
    last = dict(tries[-1])
    last["tries"] = len(tries)
    last["earlier"] = [{"state": t["state"], "fails": t["fails"], "noisy": t["noisy"]} for t in tries[:-1]]
    return last


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
