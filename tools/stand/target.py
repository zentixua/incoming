"""Performance target: absolute frame-time, hitch and per-effect GPU limits a release candidate must hold on the scene
bench (target.json, README.md "Performance target"). Unlike the look gate (gate.py: candidate against accepted in one
session), the target judges the candidate's own numbers: a version as slow as the last one must not pass because the
last one was slow too.

For each row of target.json: every named window of the row's pack (tier) must exist in a valid run and hold every
limit given:
- `fps_p50`: median frame interval at most 1000 / fps_p50 ms;
- `fps_low1`: 1 % low (1000 / p99 frame interval, results.py) at least fps_low1;
- `slow`: at most this many frames over results.SLOW_MS (100 ms);
- `incoming_ms`: mean GPU time of the Incoming effects layer at most this;
- `pall_ms`: mean GPU time of the nuclear dust and smoke volume at most this; `pall_peak_ms`: its worst second; a second
  in which the volume is not drawn (off, failed, turned off by the GPU watchdog) fails the window.
A missing window, number or pack fails. A pack failing the first run is captured again (stand.py release), and the
last run decides, as in the look gate: GPU clocks of the laptop vary 6–29 % between sessions (README.md, "Look gate"),
which a true miss outlasts. Standard library only (CI: test_gate.py).
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
PASS, FAIL = "pass", "fail"
EPS = 1e-9


def load(path=HERE / "target.json"):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def scenes_of(target, packs=None):
    """Scene ids the target needs (window names are <scene>/<camera or window> or <scene>), in row order."""
    out = []
    for row in target["rows"]:
        if packs is not None and row["pack"] not in packs:
            continue
        for w in row["windows"]:
            s = w.split("/")[0].removesuffix("-hit")
            if s not in out:
                out.append(s)
    return out


def packs_of(target):
    return list(dict.fromkeys(row["pack"] for row in target["rows"]))


def _num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def check_window(row, name, w):
    """One window against one row → (fail lines, table rows). Every limit in the row is judged."""
    fails, rows = [], []
    where = f"{row['pack']} {name}"

    def judge(metric, got, limit, worse_if_higher, unit, shown=None):
        if not _num(got):
            fails.append(f"{where} {metric}: no number")
            return
        bad = got > limit + EPS if worse_if_higher else got < limit - EPS
        rows.append({"pack": row["pack"], "window": name, "metric": metric, "got": got, "limit": limit, "fail": bad,
                     "scene": row["scene"]})
        if bad:
            fails.append(f"{where} {metric}: {shown or f'{got:.1f}'} {unit}, target {'at most' if worse_if_higher else 'at least'} {limit:g}")

    if "fps_p50" in row:
        p50 = ((w.get("interval") or {}).get("p50"))
        judge("fps_p50", 1000 / p50 if _num(p50) and p50 > 0 else None, row["fps_p50"], False, "fps")
    if "fps_low1" in row:
        judge("fps_low1", w.get("low1_fps"), row["fps_low1"], False, "fps")
    if "slow" in row:
        s = w.get("slow") or {}
        n = s.get("count")
        judge("slow", n, row["slow"], True, "frames over 100 ms",
              f"{n} (worst {s['worst_ms']:.0f} ms)" if _num(n) and _num(s.get("worst_ms")) else None)
    if "incoming_ms" in row:
        judge("incoming_ms", w.get("incoming_gpu"), row["incoming_ms"], True, "ms GPU")
    if "pall_ms" in row or "pall_peak_ms" in row:
        off = (w.get("pall") or {}).get("off") or 0
        if off:
            fails.append(f"{where} nuclear volume not drawn in {off} s ({', '.join(w['pall'].get('off_why') or [])}): "
                         "no dust on screen is not a fast frame")
            rows.append({"pack": row["pack"], "window": name, "metric": "pall_off_s", "got": off, "limit": 0, "fail": True,
                         "scene": row["scene"]})
    if "pall_ms" in row:
        judge("pall_ms", (w.get("pall") or {}).get("mean"), row["pall_ms"], True, "ms GPU")
    if "pall_peak_ms" in row:
        judge("pall_peak_ms", (w.get("pall") or {}).get("max"), row["pall_peak_ms"], True, "ms GPU")
    return fails, rows


def judge_pack(target, pack, res):
    """One pack's results (results.analyze) against every row of that pack → {"state", "fails", "rows"}."""
    fails, rows = [], []
    if res is None:
        return {"state": FAIL, "fails": [f"{pack}: not captured"], "rows": []}
    if res.get("checks"):
        fails.append(f"{pack}: run is invalid: " + "; ".join(res["checks"]))
    for row in target["rows"]:
        if row["pack"] != pack:
            continue
        for name in row["windows"]:
            w = (res.get("windows") or {}).get(name)
            if w is None:
                fails.append(f"{pack} {name}: no window")
                continue
            f, r = check_window(row, name, w)
            fails += f
            rows += r
    return {"state": FAIL if fails else PASS, "fails": fails, "rows": rows}


def judge(target, runs):
    """Runs of one candidate in capture order (results.json each; a later run of a pack replaces the earlier one, the
second capture of a failed pack) → {"state", "pass", "packs": {pack: judge_pack + tries, earlier}, "fails", "rows"}."""
    packs = {}
    for pack in packs_of(target):
        tries = [judge_pack(target, pack, r["packs"][pack]) for r in runs if pack in r.get("packs", {})]
        if not tries:
            packs[pack] = {"state": FAIL, "fails": [f"{pack}: not captured"], "rows": [], "tries": 0, "earlier": []}
            continue
        last = dict(tries[-1])
        last["tries"] = len(tries)
        last["earlier"] = [{"state": t["state"], "fails": t["fails"]} for t in tries[:-1]]
        packs[pack] = last
    state = FAIL if any(v["state"] == FAIL for v in packs.values()) else PASS
    return {"state": state, "pass": state == PASS, "packs": packs, "target": target.get("version"),
            "fails": [x for v in packs.values() for x in v["fails"]], "rows": [x for v in packs.values() for x in v["rows"]]}


def table(verdict):
    """Markdown table of a verdict: one line per pack, window and metric."""
    lines = ["| pack | window | metric | got | target | |", "|---|---|---|---|---|---|"]
    for r in verdict["rows"]:
        better = "≥" if r["metric"].startswith("fps") else "≤"
        lines.append(f"| {r['pack']} | {r['window']} | {r['metric']} | {r['got']:.1f} | {better} {r['limit']:g} | {'FAIL' if r['fail'] else 'ok'} |")
    return "\n".join(lines)
