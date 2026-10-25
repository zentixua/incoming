# /// script
# dependencies = ["Pillow"]
# ///
"""Отчёт стенда: таблицы по сценам и камерам, листы кадров «строки — версии, столбцы — сцены», график накопления,
пометки вида для ревьюера, вердикт шлагбаума.

  uv run tools/stand/report.py <имя> [--ref <прогон>[=подпись] …]

<имя> — прогон out/<имя> стенда: пара (stand.py play … --against, stand.py pr) — кандидат B рядом с принятым A1 и A2 той
пары, что решила пак, и вердикт тем же путём, что stand.py gate (stand.judge_pair: заново из сырых данных); или один
прогон — без вердикта. --ref — ещё прогоны в таблицы и листы (например 3.4.1). Итог — в каталоге прогона: report.md,
comment.md (таблица для комментария в PR), verdict.json (у пары), sheet-<пак>.png, acc-<пак>.png, look.json.
"""
import argparse
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import gate  # noqa: E402
import plan  # noqa: E402
import stand  # noqa: E402

COLORS = [(230, 90, 60), (70, 140, 230), (120, 190, 80), (220, 180, 50), (170, 100, 210)]


class Run:
    """Прогон: числа (из сырых данных), подпись, где кадры."""

    def __init__(self, d, res, label=None):
        self.dir = Path(d)
        self.res = res
        self.label = label or res.get("label") or self.dir.name

    @staticmethod
    def of(ref, label=None):
        d = Path(ref) if Path(ref).is_dir() else stand.OUT / ref
        return Run(d, stand.analyze(d, write=False), label)

    def shot(self, pack, name):
        f = self.dir / pack / "shots" / f"{name}.png"
        return f if f.exists() else None


def font(size):
    for f in ("/usr/share/fonts/TTF/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def look_stats(png):
    """Доля «дымных» пикселей (почти без цвета, не чёрные и не белые) и средняя яркость кадра (0–1)."""
    im = Image.open(png).convert("RGB").resize((256, 160), Image.BILINEAR)
    smoke = luma = 0
    px = list(im.get_flattened_data() if hasattr(im, "get_flattened_data") else im.getdata())
    for r, g, b in px:
        mx, mn = max(r, g, b), min(r, g, b)
        lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        luma += lum
        if mx > 0 and (mx - mn) / mx < 0.15 and 0.10 < lum < 0.92:
            smoke += 1
    return {"smoke": smoke / len(px), "luma": luma / len(px)}


def shot_order(scenes, only):
    """Кадры плана по порядку: шаги «кадр» и кадры окон от попадания (имена — как пишет обвязка)."""
    names = []
    for st in plan.build(scenes, only)["steps"]:
        if st["do"] == "shot":
            names.append(st["name"])
        elif st["do"] == "impact_window":
            names += [f"{st['prefix']}_{plan_trim(t)}s" for t in st["shots"]]
    return names


def plan_trim(s):
    return str(int(s)) if float(s).is_integer() else f"{s:.1f}"


def sheet(runs, pack, names, out, w=400):
    h = w * 10 // 16
    pad, top, left = 4, 30, 170
    names = [n for n in names if any(r.shot(pack, n) for r in runs)]
    if not names:
        return None
    im = Image.new("RGB", (left + len(names) * (w + pad), top + len(runs) * (h + pad)), (24, 24, 24))
    d = ImageDraw.Draw(im)
    f = font(17)
    d.text((8, 6), pack, fill=(255, 255, 255), font=f)
    for j, n in enumerate(names):
        d.text((left + j * (w + pad) + 6, 6), n, fill=(255, 255, 255), font=f)
    for i, r in enumerate(runs):
        y = top + i * (h + pad)
        d.text((8, y + h // 2 - 10), r.label, fill=(255, 255, 255), font=f)
        for j, n in enumerate(names):
            p = r.shot(pack, n)
            if p:
                im.paste(Image.open(p).convert("RGB").resize((w, h), Image.LANCZOS), (left + j * (w + pad), y))
            else:
                d.text((left + j * (w + pad) + 20, y + h // 2), "no frame", fill=(200, 80, 80), font=f)
    im.save(out)
    return out


def acc_chart(runs, pack, out, size=(960, 420)):
    series = [(r.label, ((r.res["packs"].get(pack) or {}).get("acc") or {}).get("buckets")) for r in runs]
    series = [(lab, b) for lab, b in series if b]
    if not series:
        return None
    W, H = size
    l, r_, t, b_ = 70, 20, 30, 50
    ymax = max(x["interval_mean"] or 0 for _, bs in series for x in bs) * 1.1 or 1
    xmax = max(x["t_s"] for _, bs in series for x in bs) + 10
    im = Image.new("RGB", size, (255, 255, 255))
    d = ImageDraw.Draw(im)
    f = font(14)

    def xy(tx, ty):
        return l + (W - l - r_) * tx / xmax, H - b_ - (H - t - b_) * ty / ymax
    for k in range(6):
        yv = ymax * k / 5
        _, yy = xy(0, yv)
        d.line([(l, yy), (W - r_, yy)], fill=(225, 225, 225))
        d.text((8, yy - 8), f"{yv:.0f} ms", fill=(60, 60, 60), font=f)
    for tx in range(0, int(xmax) + 1, 60):
        xx, _ = xy(tx, 0)
        d.text((xx - 10, H - b_ + 8), f"{tx} s", fill=(60, 60, 60), font=f)
    d.text((l, 6), f"{pack}: frame time per 10 s since the first strike of the waves (mean)", fill=(0, 0, 0), font=f)
    for i, (lab, bs) in enumerate(series):
        c = COLORS[i % len(COLORS)]
        pts = [xy(x["t_s"] + 5, x["interval_mean"]) for x in bs if x["interval_mean"] is not None]
        if len(pts) > 1:
            d.line(pts, fill=c, width=3)
        d.text((W - r_ - 200, t + 18 * i), lab, fill=c, font=f)
    im.save(out)
    return out


def fmt(v, digits=1):
    return "—" if v is None else f"{v:.{digits}f}"


def cell(w):
    if not w:
        return "—"
    g, t, iv, fps = (w.get("gpu") or {}).get("p50"), (w.get("thread") or {}).get("p95"), (w.get("interval") or {}).get("p95"), w.get("fps")
    return f"{fmt(g)} / {fmt(t)} / {fmt(iv)} ({fmt(fps, 0)})"


def columns(d, verdict, refs):
    """Столбцы отчёта по пакам: {пак: [Run]} — у пары кандидат B и принятый A1, A2 той пары, что решила пак; дальше refs."""
    if verdict is None:
        cand = Run(d, stand.analyze(d, write=False))
        return {p: [cand] + refs for p in cand.res["packs"]}, cand
    runs = stand.pair_runs(d)
    out = {}
    for pack, pv in verdict["packs"].items():
        attempt = max(1, pv.get("tries") or 1)
        names = [stand.role_dir(d, r, attempt).name for r in stand.ROLES]
        if not all(n in runs for n in names):
            continue
        a1, b, a2 = (runs[n] for n in names)
        out[pack] = [Run(d / names[1], b, f"{verdict['cand']['label']} (B)"), Run(d / names[0], a1, f"{verdict['base']['label']} A1"),
                     Run(d / names[2], a2, f"{verdict['base']['label']} A2")] + refs
    first = next(iter(out.values()))[0] if out else None
    return out, first


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("name")
    ap.add_argument("--ref", action="append", default=[])
    a = ap.parse_args()
    d = stand.OUT / a.name
    verdict = stand.judge_pair(d) if stand.is_pair(d) else None
    refs = [Run.of(*r.split("=", 1)) for r in a.ref]
    cols, cand = columns(d, verdict, refs)
    if cand is None:
        raise SystemExit(f"{d}: нет ни одной целой пары")
    scenes = plan.load()
    only = cand.res["context"]["scenes"]
    only = None if only == "all" else only
    wins = plan.windows(scenes, only)
    names = shot_order(scenes, only)

    # вид: доля дымных пикселей и яркость по кадрам, по столбцам каждого пака
    looks = {p: [{n: look_stats(r.shot(p, n)) for n in names if r.shot(p, n)} for r in runs] for p, runs in cols.items()}
    (d / "look.json").write_text(json.dumps({p: [{"label": r.label, "dir": str(r.dir), "look": lk} for r, lk in zip(cols[p], looks[p])]
                                             for p in cols}, indent=1))
    if verdict:
        # вид кандидата против A1 той же пары
        verdict["look"] = gate.look_flags({p: looks[p][0] for p in cols}, {p: looks[p][1] for p in cols})
        (d / "verdict.json").write_text(json.dumps(verdict, indent=1, ensure_ascii=False))

    md = [f"# Scene stand: {cand.label}", ""]
    ctx = cand.res["context"]
    md.append(f"GPU {ctx['gpu']}, driver {ctx['driver']}, window {ctx['size']}, scenes {ctx['scenes']}, stand {ctx['stand'][:12]}, "
              f"power {ctx.get('power')}, jar sha256 {cand.res['jar']['sha256'][:12]} ({cand.res['jar']['file']}).")
    md.append("")
    comment = [f"### {gate_title(verdict)}", ""]
    if verdict:
        md.append(f"## Verdict against {verdict['base']['label']}: {verdict['state'].upper()}")
        md.append("Each pack is shot as a pair in one session: base A1, candidate B, base A2. The base is the mean of A1 "
                  f"and A2; the tolerance per value is max({gate.MIN_TOL:.0%}, {gate.SPREAD_K:g} × |A1 − A2| / base). A pair whose "
                  f"A1 and A2 differ by more than {gate.MAX_SPREAD:.0%} is noisy; a pack that does not pass is shot by a second pair, "
                  "and the second pair decides.")
        md += [f"- {x}" for x in verdict["noisy"] + verdict["fails"]] or ["- frame time at 100 strikes within tolerance"]
        md += [f"- note: {x}" for x in verdict["notes"]]
        for pack, pv in verdict["packs"].items():
            for i, e in enumerate(pv.get("earlier") or [], 1):
                md.append(f"- {pack}, pair {i} (re-shot): {e['state']}: " + "; ".join(e["noisy"] + e["fails"]))
        if verdict["look"]:
            md += ["", "Look differs noticeably (for the reviewer, not a failure):", *[f"- {x}" for x in verdict["look"]]]
        md.append("")
        comment += ["| pack | window | value | PR | base A1 / A2 | change | tolerance |", "|---|---|---|---|---|---|---|"]
        for r in verdict["rows"]:
            if r["gated"] and r["window"].startswith(gate.GATED):
                bases = " / ".join(fmt(x) for x in r.get("bases") or [r["base"]])
                comment.append(f"| {r['pack']} | {r['window']} | {r['metric']} | {fmt(r['cand'])} | {bases} | {r['change']:+.0%} | "
                               f"{r['tol']:.0%}{' (noisy)' if r.get('noisy') else ''}{' **FAIL**' if r['fail'] else ''} |")
        comment += ["", *([f"- {x}" for x in verdict["noisy"] + verdict["fails"]]
                          or ["Frame time at 100 strikes within tolerance of the base."])]
        if verdict["look"]:
            comment += ["", "Look differs noticeably (reviewer, compare the frames):", *[f"- {x}" for x in verdict["look"]]]
    md.append("Cells: GPU p50 / CPU p95 / frame p95 (frames per second), ms. GPU — the GPU clock from the first to the last command "
              "of the frame; CPU — CPU time of the render thread in the frame (RenderFrameEvent.Pre → Post, without the buffer swap, "
              "preemption and driver sleep); frame — from the frame start to the next frame start, as the player sees it. The gate "
              "judges GPU p50 and CPU p95 in the `100/*` windows.")
    for pack, runs in cols.items():
        md += ["", f"## Pack: {pack}", "", "| window | " + " | ".join(r.label for r in runs) + " |", "|---|" + "---|" * len(runs)]
        for wname in wins:
            row = [cell((r.res["packs"].get(pack) or {}).get("windows", {}).get(wname)) for r in runs]
            md.append(f"| {wname} | " + " | ".join(row) + " |")
        if any((r.res["packs"].get(pack) or {}).get("first") for r in runs):
            md.append("| first strike, worst frame to +5 s | "
                      + " | ".join(fmt(((r.res["packs"].get(pack) or {}).get("first") or {}).get("worst_ms"), 0) for r in runs) + " |")
        growth = [((r.res["packs"].get(pack) or {}).get("acc") or {}).get("growth") for r in runs]
        if any(growth):
            md.append("| accumulation, last minute / first | " + " | ".join(
                "—" if not g or g["ratio"] is None else f"×{g['ratio']:.2f} ({g['first_ms']:.1f} → {g['last_ms']:.1f})" for g in growth) + " |")
        c, b = runs[0], runs[1] if len(runs) > 1 else None
        # где время кадра видеокарты в окнах «100»: стадии кандидата против первого принятого
        for wname in [w for w in wins if w.startswith(gate.GATED)]:
            cw = (c.res["packs"].get(pack) or {}).get("windows", {}).get(wname)
            bw = ((b.res["packs"].get(pack) or {}).get("windows") or {}).get(wname) if b else None
            if not cw:
                continue
            st = sorted(cw["stages"].items(), key=lambda kv: -(kv[1]["mean"] or 0))[:8]
            md += ["", f"GPU by stage, {wname} (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, "
                       f"`tail` = after the last stage: GUI, post):", "",
                   "| stage | " + c.label + (f" | {b.label}" if bw else "") + " |", "|---|---|" + ("---|" if bw else "")]
            for k, v in st:
                bv = ((bw or {}).get("stages") or {}).get(k, {}).get("mean")
                md.append(f"| {k} | {fmt(v['mean'], 2)}" + (f" | {fmt(bv, 2)}" if bw else "") + " |")
            md.append("")
            h = cw.get("host") or {}
            md.append(f"Bound: {cw['bound']}; server tick mean {fmt((cw.get('mspt') or {}).get('mean'))} ms; "
                      f"wall-clock Pre→Post p95 {fmt((cw.get('cpu') or {}).get('p95'))} ms; other load on the machine "
                      f"{fmt(h.get('cpu_foreign'))} cores, GPU {h.get('gpu_foreign', '—')} % ({h.get('units') or 'nothing heavy'}); probes: "
                      + "; ".join(f"{k}: {v}" for k, v in (cw.get("probes") or {}).items()))
        for r in runs:
            pr = r.res["packs"].get(pack) or {}
            if pr.get("checks"):
                md += ["", f"{r.label}: run not valid for comparison: " + "; ".join(pr["checks"])]
            if pr.get("load"):
                md += ["", f"{r.label}: machine load in the verdict windows: " + "; ".join(pr["load"])]
        meta = (c.res["packs"].get(pack) or {}).get("meta") or {}
        md += ["", f"{c.label}: frames {meta.get('frames')}, {fmt(meta.get('gpu_minutes'))} GPU minutes, impacts: "
               + ", ".join(f"{k} {v['got']}/{v['expect']} ({v['why']}, {v['took_s']:.0f} s)" for k, v in (meta.get("impacts") or {}).items())]
        s = sheet(runs, pack, names, d / f"sheet-{pack}.png")
        if s:
            md += ["", f"Frames side by side: `{s.name}`"]
        ch = acc_chart(runs, pack, d / f"acc-{pack}.png")
        if ch:
            md += [f"Accumulation chart: `{ch.name}`"]
    comment += ["", "Values: GPU p50 and CPU p95 (render thread), ms. Report and frames: `mod/run/stand/out/" + d.name + "/` on the laptop."]
    (d / "report.md").write_text("\n".join(md) + "\n")
    (d / "comment.md").write_text("\n".join(comment) + "\n")
    print(d / "report.md")


def gate_title(verdict):
    if not verdict:
        return "Scene stand"
    word = {gate.PASS: "pass", gate.FAIL: "FAIL", gate.NOISY: "noisy, no verdict"}[verdict["state"]]
    return f"Scene stand, pair with {verdict['base']['label']}: {word}"


if __name__ == "__main__":
    main()
