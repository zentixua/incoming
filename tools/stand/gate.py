"""Шлагбаум стенда: кандидат против принятого, снятых парой в одном сеансе (README.md, «Условие влития»).

Пара: на каждый пак — принятый, кандидат, снова принятый (A1, B, A2) подряд под одной арендой видеокарты. Нагрев и
частота видеокарты, чужая нагрузка бьют внутри пары по обоим; база — худший из A1 и A2: то, что принятый сам дал в этом
сеансе. Один и тот же jar в одном сеансе расходится в окнах «100» на 10–15 % внутри дыма и до 1,7 раза снаружи
(дым то в кадре, то нет: приёмка 25.10, v3.7.0 против себя), а регрессии, ради которых стенд (3.5.0 и 3.7.0 против
3.4.1), — в 2,6–9,3 раза.

Провал пака (`compare_pack`):
- окна «100 ударов» (`100/*`, любая камера): видеокарта p50 или процессор p95 (время процессора потока отрисовки за кадр
  Pre → Post) у кандидата больше худшего из A1 и A2 на TOL; нет окна или числа — тоже провал. Настенное Pre → Post и
  кадр p95 — в отчёте, без провала;
- первый удар и накопление, если сцены сняты: худший кадр больше чем вдвое против базы и больше 100 мс; рост последней
  минуты к первой сверх роста у базы на TOL;
- прогон пары не годен (results.checks) или снят иначе (строка OpenGL, кадр).
Решение (`decide`): пак, который не прошёл с первой пары, снимается второй парой (`--tries`), и решает последняя.
Вид числом не судится: сцены, где доля «дымных» пикселей или яркость кадра заметно другие (`look_flags`), помечаются
ревьюеру. Только стандартная библиотека (CI: test_gate.py).
"""
GATED = "100/"
# величины шлагбаума (спека: видеокарта p50 и процессор p95 — процессор потока отрисовки) и те, что только в отчёте
METRICS = (("gpu", "p50"), ("thread", "p95"))
REPORTED = METRICS + (("cpu", "p95"), ("interval", "p95"))
GL_KEYS = ("renderer", "version", "width", "height")
# допуск к худшему из A1 и A2: v3.7.0 против себя дала не больше +16 % (видеокарта, 100/edge), регрессии — в разы
TOL = 0.25
FIRST_RATIO, FIRST_MIN_MS = 2.0, 100.0
# вид: доля дымных пикселей (абсолютная разница) и средняя яркость (0–1)
LOOK_SMOKE, LOOK_LUMA = 0.10, 0.08
PASS, FAIL = "pass", "fail"


def metric(window, m):
    part, q = m
    return ((window or {}).get(part) or {}).get(q)


def _num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def base_of(values):
    """База принятого по одной величине — худший прогон (A1, A2 или один); нет числа у кого-то — None."""
    if not values or any(not _num(v) for v in values):
        return None
    return max(values)


def gl_diff(a, b):
    """Чем различается съёмка двух прогонов одного пака: строка OpenGL (видеокарта, драйвер) и размер кадра."""
    ga, gb = (a.get("meta") or {}).get("gl") or {}, (b.get("meta") or {}).get("gl") or {}
    return [f"{k} {ga.get(k)} против {gb.get(k)}" for k in GL_KEYS if ga.get(k) != gb.get(k)]


def compare_pack(pack, c, bases):
    """Кандидат c против принятого bases (пак результатов: [A1, A2] пары или [A] одного прогона) →
    {"state": pass|fail, "fails", "rows", "notes"}. Негодный прогон и снятое иначе — провал."""
    fails, rows, notes = [], [], []
    if len(bases) < 2:
        notes.append(f"{pack}: принятый снят один раз")
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
            cv, bv = metric(cw, m), base_of([metric(w, m) for w in bws])
            if not _num(cv) or not bv:
                if judged:
                    fails.append(f"{pack} {name} {'.'.join(m)}: нет числа у {'кандидата' if not _num(cv) else 'принятого'}")
                continue
            change = cv / bv - 1
            # ровно на допуске — проход: 12,5 / 10 − 1 в двоичных дробях может выйти чуть больше 0,25
            bad = judged and change > TOL + 1e-9
            rows.append({"pack": pack, "window": name, "metric": ".".join(m), "cand": cv, "base": bv,
                         "bases": [metric(w, m) for w in bws], "change": change, "tol": TOL if judged else None,
                         "gated": judged, "fail": bad})
            if bad:
                fails.append(f"{pack} {name} {'.'.join(m)}: {cv:.2f} мс против {bv:.2f} у худшего принятого (+{change:.0%}, "
                             f"допуск {TOL:.0%})")
    cf = (c.get("first") or {}).get("worst_ms")
    bf = base_of([(b.get("first") or {}).get("worst_ms") for b in bases])
    # сцены нет в выборке — нет ни у кого; есть у одного — у другого провалена
    if c.get("first") or any(b.get("first") for b in bases):
        if not _num(cf) or not bf:
            fails.append(f"{pack} первый удар: нет числа у {'кандидата' if not _num(cf) else 'принятого'}")
        else:
            bad = cf > FIRST_RATIO * bf and cf > FIRST_MIN_MS
            rows.append({"pack": pack, "window": "first", "metric": "worst", "cand": cf, "base": bf, "change": cf / bf - 1,
                         "tol": None, "gated": True, "fail": bad})
            if bad:
                fails.append(f"{pack} первый удар: худший кадр {cf:.0f} мс против {bf:.0f} (больше {FIRST_RATIO:g}× и {FIRST_MIN_MS:g} мс)")
    cg = ((c.get("acc") or {}).get("growth") or {}).get("ratio")
    bg = base_of([((b.get("acc") or {}).get("growth") or {}).get("ratio") for b in bases])
    if c.get("acc") or any(b.get("acc") for b in bases):
        if not _num(cg) or not bg:
            fails.append(f"{pack} накопление: нет числа роста у {'кандидата' if not _num(cg) else 'принятого'}")
        else:
            change = cg / bg - 1
            bad = change > TOL + 1e-9
            rows.append({"pack": pack, "window": "acc", "metric": "growth", "cand": cg, "base": bg, "change": change,
                         "tol": TOL, "gated": True, "fail": bad})
            if bad:
                fails.append(f"{pack} накопление: последняя минута ×{cg:.2f} к первой против ×{bg:.2f} у принятого (допуск {TOL:.0%})")
    return {"state": FAIL if fails else PASS, "fails": fails, "rows": rows, "notes": notes}


def compare(cand, bases):
    """Кандидат (results) против принятого (список results: пара A1, A2 или один прогон) по всем пакам кандидата →
    {"pass", "state", "fails", "rows", "notes", "packs": {пак: compare_pack}}. Пак без принятого не судится."""
    packs, notes = {}, []
    for pack, c in cand["packs"].items():
        bs = [b["packs"].get(pack) for b in bases]
        if any(b is None for b in bs):
            notes.append(f"пак {pack}: у принятого не снят — не судится")
            continue
        packs[pack] = compare_pack(pack, c, bs)
    return summary(packs, notes)


def summary(packs, notes=()):
    """Вердикт по пакам ({пак: compare_pack или decide}): провал — хоть один провален."""
    state = FAIL if any(v["state"] == FAIL for v in packs.values()) else PASS
    out = {"state": state, "pass": state == PASS, "packs": packs, "notes": list(notes)}
    for k in ("fails", "rows"):
        out[k] = [x for v in packs.values() for x in v[k]]
    out["notes"] += [x for v in packs.values() for x in v["notes"]]
    return out


def decide(tries):
    """Пак по парам (compare_pack по порядку; следующая — только если прежняя не прошла) → итог пака: решает последняя;
    провалы прежних — в отчёт (`earlier`)."""
    last = dict(tries[-1])
    last["tries"] = len(tries)
    last["earlier"] = [{"state": t["state"], "fails": t["fails"]} for t in tries[:-1]]
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
