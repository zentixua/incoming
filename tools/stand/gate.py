"""Scene bench look gate: candidate against accepted version, captured as a pair in one session
(README.md, "Merge condition").

For each pack: accepted, candidate, accepted again (A1, B, A2), consecutively under one GPU lease. GPU heating,
clock frequency and foreign load affect both within the pair; baseline is the worse of A1 and A2, what the
accepted version produced in this session. The same jar in one session differs in "100" windows by 10–15 %
inside smoke and up to 1,7 times outside (smoke alternates in/out of frame: acceptance 25.10, v3.7.0 against
itself); the regressions this bench targets (3.5.0 and 3.7.0 against 3.4.1) are 2,6–9,3 times.

Pack failure (`compare_pack`):
- "100 strikes" windows (`100/*`, any camera): candidate GPU p50 or CPU p95 (render-thread CPU per frame,
  Pre → Post) exceeds the worse of A1 and A2 by TOL; missing window or number also fails. Wall-clock
  Pre → Post and frame p95 are reported without failing;
- first strike and accumulation, if captured: worst frame more than twice the baseline and above 100 ms;
  last-minute/first-minute growth exceeds baseline growth by TOL;
- invalid pair run (results.checks) or different capture setup (OpenGL string, frame size).
Decision (`decide`): a pack failing the first pair is captured as a second pair (`--tries`); the last decides.
Visual quality is not judged numerically: scenes with noticeably different smoky-pixel share or frame
brightness (`look_flags`) are flagged for the reviewer. Standard library only (CI: test_gate.py).
"""
GATED = "100/"
# Gated metrics (spec: GPU p50 and CPU p95, CPU means render-thread CPU) and report-only metrics
METRICS = (("gpu", "p50"), ("thread", "p95"))
REPORTED = METRICS + (("cpu", "p95"), ("interval", "p95"))
GL_KEYS = ("renderer", "version", "width", "height")
# Tolerance over the worse of A1 and A2: v3.7.0 against itself stayed within +16 % (GPU, 100/edge); regressions are several-fold
TOL = 0.25
FIRST_RATIO, FIRST_MIN_MS = 2.0, 100.0
# Visuals: smoky-pixel share (absolute difference) and mean brightness (0–1)
LOOK_SMOKE, LOOK_LUMA = 0.10, 0.08
PASS, FAIL = "pass", "fail"


def metric(window, m):
    part, q = m
    return ((window or {}).get(part) or {}).get(q)


def _num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def base_of(values):
    """Accepted baseline for one metric is the worst run (A1, A2, or one run); missing any number gives None."""
    if not values or any(not _num(v) for v in values):
        return None
    return max(values)


def gl_diff(a, b):
    """Capture differences between two runs of one pack: OpenGL string (GPU, driver) and frame size."""
    ga, gb = (a.get("meta") or {}).get("gl") or {}, (b.get("meta") or {}).get("gl") or {}
    return [f"{k} {ga.get(k)} versus {gb.get(k)}" for k in GL_KEYS if ga.get(k) != gb.get(k)]


def compare_pack(pack, c, bases):
    """Candidate c against accepted bases (pack results: paired [A1, A2] or single [A]) →
{"state": pass|fail, "fails", "rows", "notes"}. Invalid or differently captured runs fail."""
    fails, rows, notes = [], [], []
    if len(bases) < 2:
        notes.append(f"{pack}: accepted captured once")
    if c.get("checks"):
        fails.append(f"{pack}: candidate run is invalid: " + "; ".join(c["checks"]))
    for i, b in enumerate(bases, 1):
        if b.get("checks"):
            fails.append(f"{pack}: accepted run A{i} is invalid: " + "; ".join(b["checks"]))
        fails += [f"{pack}: A{i} captured differently from candidate: {x}" for x in gl_diff(c, b)]
    names = set(c.get("windows", {}))
    for b in bases:
        names |= set(b.get("windows", {}))
    for name in sorted(names):
        cw, bws = c.get("windows", {}).get(name), [b.get("windows", {}).get(name) for b in bases]
        gated = name.startswith(GATED)
        if cw is None or any(w is None for w in bws):
            if gated:
                fails.append(f"{pack} {name}: no window for {'candidate' if cw is None else 'accepted'}")
            continue
        for m in REPORTED:
            judged = gated and m in METRICS
            cv, bv = metric(cw, m), base_of([metric(w, m) for w in bws])
            if not _num(cv) or not bv:
                if judged:
                    fails.append(f"{pack} {name} {'.'.join(m)}: no number for {'candidate' if not _num(cv) else 'accepted'}")
                continue
            change = cv / bv - 1
            # Exactly at tolerance passes: 12,5 / 10 − 1 may be slightly above 0,25 in binary floating point
            bad = judged and change > TOL + 1e-9
            rows.append({"pack": pack, "window": name, "metric": ".".join(m), "cand": cv, "base": bv,
                         "bases": [metric(w, m) for w in bws], "change": change, "tol": TOL if judged else None,
                         "gated": judged, "fail": bad})
            if bad:
                fails.append(f"{pack} {name} {'.'.join(m)}: {cv:.2f} ms versus {bv:.2f} for worst accepted (+{change:.0%}, "
                             f"tolerance {TOL:.0%})")
    cf = (c.get("first") or {}).get("worst_ms")
    bf = base_of([(b.get("first") or {}).get("worst_ms") for b in bases])
    # Scene absent from subset: absent for everyone; present for one but absent for the other means failure
    if c.get("first") or any(b.get("first") for b in bases):
        if not _num(cf) or not bf:
            fails.append(f"{pack} first strike: no number for {'candidate' if not _num(cf) else 'accepted'}")
        else:
            bad = cf > FIRST_RATIO * bf and cf > FIRST_MIN_MS
            rows.append({"pack": pack, "window": "first", "metric": "worst", "cand": cf, "base": bf, "change": cf / bf - 1,
                         "tol": None, "gated": True, "fail": bad})
            if bad:
                fails.append(f"{pack} first strike: worst frame {cf:.0f} ms versus {bf:.0f} (above {FIRST_RATIO:g}× and {FIRST_MIN_MS:g} ms)")
    cg = ((c.get("acc") or {}).get("growth") or {}).get("ratio")
    bg = base_of([((b.get("acc") or {}).get("growth") or {}).get("ratio") for b in bases])
    if c.get("acc") or any(b.get("acc") for b in bases):
        if not _num(cg) or not bg:
            fails.append(f"{pack} accumulation: no growth number for {'candidate' if not _num(cg) else 'accepted'}")
        else:
            change = cg / bg - 1
            bad = change > TOL + 1e-9
            rows.append({"pack": pack, "window": "acc", "metric": "growth", "cand": cg, "base": bg, "change": change,
                         "tol": TOL, "gated": True, "fail": bad})
            if bad:
                fails.append(f"{pack} accumulation: last minute ×{cg:.2f} versus first, against ×{bg:.2f} for accepted (tolerance {TOL:.0%})")
    return {"state": FAIL if fails else PASS, "fails": fails, "rows": rows, "notes": notes}


def compare(cand, bases):
    """Candidate (results) against accepted (results list: pair A1, A2 or single run), for every candidate pack →
{"pass", "state", "fails", "rows", "notes", "packs": {pack: compare_pack}}. A pack without an accepted run is not judged."""
    packs, notes = {}, []
    for pack, c in cand["packs"].items():
        bs = [b["packs"].get(pack) for b in bases]
        if any(b is None for b in bs):
            notes.append(f"pack {pack}: not captured for accepted — not judged")
            continue
        packs[pack] = compare_pack(pack, c, bs)
    return summary(packs, notes)


def summary(packs, notes=()):
    """Verdict across packs ({pack: compare_pack or decide}): fail if any pack fails."""
    state = FAIL if any(v["state"] == FAIL for v in packs.values()) else PASS
    out = {"state": state, "pass": state == PASS, "packs": packs, "notes": list(notes)}
    for k in ("fails", "rows"):
        out[k] = [x for v in packs.values() for x in v[k]]
    out["notes"] += [x for v in packs.values() for x in v["notes"]]
    return out


def decide(tries):
    """Pack pairs (compare_pack in order; next only if previous failed) → pack verdict: last decides;
report earlier failures in `earlier`."""
    last = dict(tries[-1])
    last["tries"] = len(tries)
    last["earlier"] = [{"state": t["state"], "fails": t["fails"]} for t in tries[:-1]]
    return last


def look_flags(cand_look, base_looks):
    """Frames with noticeably different visuals: candidate and each accepted run (A1, A2) {pack: {frame: {smoke, luma}}}
→ "pack frame: what" lines. Compare to the nearest accepted run: Photon 100-hit itself differs between A1 and
A2 by 0,18–0,29 smoky-pixel share (#468, 25.10); flagging against A1 alone would be noise."""
    out = []
    for pack, shots in cand_look.items():
        for shot, c in shots.items():
            bs = [b for b in ((bl.get(pack) or {}).get(shot) for bl in base_looks) if b]
            if not bs:
                continue
            why = []
            for key, lim, fmt in (("smoke", LOOK_SMOKE, "{:.0%}"), ("luma", LOOK_LUMA, "{:.2f}")):
                near = min(bs, key=lambda b: abs(c[key] - b[key]))
                if abs(c[key] - near[key]) > lim:
                    word = "smoky pixels" if key == "smoke" else "brightness"
                    why.append(f"{word} {fmt.format(c[key])} versus " + " / ".join(fmt.format(b[key]) for b in bs))
            if why:
                out.append(f"{pack} {shot}: " + ", ".join(why))
    return out
