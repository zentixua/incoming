# /// script
# dependencies = ["Pillow"]
# ///
"""Отчёт стенда: таблицы по сценам и камерам, листы кадров «строки — версии, столбцы — сцены», график накопления,
пометки вида для ревьюера, вердикт шлагбаума.

  uv run tools/stand/report.py <имя> [--against <выпуск> | --base <прогон|кэш> [--tol-from <кэш>]]
                                    [--ref <прогон|кэш>[=подпись] …]

<имя> — прогон кандидата (out/<имя> стенда); принятый, с которым считается вердикт, — кэш выпуска по ключу кандидата
(--against, как stand.py gate --against), каталог кэша stand.py baseline или прогон (--base, допуск к прогону — из
кэша --tol-from, как stand.py gate --base-run); --ref — ещё версии в таблицы и листы (например 3.4.1). Числа и вердикт —
тем же путём, что stand.py gate (stand.against, stand.judge: заново из сырых данных, кэш — только годный): вердикт
отчёта и статуса один. Итог — в каталоге прогона: report.md, comment.md (таблица для комментария в PR), verdict.json,
sheet-<пак>.png, acc-<пак>.png, look.json.
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
    """Прогон или кэш принятого: числа (из сырых данных), подпись, где кадры."""

    def __init__(self, d, res, label=None):
        self.dir = Path(d)
        self.res = res
        self.label = label or res.get("label") or self.dir.name
        # у кэша кадры — первого прогона калибровки
        self.shots_root = self.dir / "run1" if (self.dir / "run1").is_dir() else self.dir

    @staticmethod
    def of(ref, label=None):
        """Прогон out/<имя> или каталог кэша (среднее двух прогонов, только годный)."""
        d = Path(ref) if Path(ref).is_dir() else stand.OUT / ref
        res = stand.cached(d)["base"] if (d / "run1").is_dir() else stand.analyze(d, write=False)
        return Run(d, res, label)

    def shot(self, pack, name):
        f = self.shots_root / pack / "shots" / f"{name}.png"
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
    names = []
    for s in scenes["scenes"]:
        if only is not None and s["id"] not in only:
            continue
        if s.get("impact_window"):
            names += [f"{s['id']}_{plan_trim(t)}s" for t in s.get("shots", [])]
        else:
            names += [f"{s['id']}_{c}" for c in s["cameras"]]
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
    g, c, iv, fps = (w.get("gpu") or {}).get("p50"), (w.get("cpu") or {}).get("p95"), (w.get("interval") or {}).get("p95"), w.get("fps")
    return f"{fmt(g)} / {fmt(c)} / {fmt(iv)} ({fmt(fps, 0)})"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("name")
    ap.add_argument("--against", help="выпуск: принятый из кэша по ключу кандидата")
    ap.add_argument("--base", help="каталог кэша или прогон out/<имя>")
    ap.add_argument("--tol-from", help="каталог кэша, чей допуск взять к прогону --base")
    ap.add_argument("--ref", action="append", default=[])
    a = ap.parse_args()
    cand = Run(stand.OUT / a.name, stand.analyze(stand.OUT / a.name, write=False))
    against = None
    if a.against or a.base:
        against = stand.against(cand.res, a.against, a.base, a.tol_from)
        if against["res"] is None:
            raise SystemExit(f"принятого {against['label']} в кэше нет ({against['dir']}): stand.py baseline {against['label']}")
    base = Run(against["dir"], against["res"], against["label"]) if against else None
    refs = [Run.of(*r.split("=", 1)) for r in a.ref]
    runs = [cand] + ([base] if base else []) + refs
    scenes = plan.load()
    only = cand.res["context"]["scenes"]
    only = None if only == "all" else only
    wins = plan.windows(scenes, only)
    names = shot_order(scenes, only)
    out = cand.dir
    packs = list(cand.res["packs"])

    # вид: доля дымных пикселей и яркость по кадрам
    # по порядку строк: у кандидата и принятого подпись может совпасть (два прогона одной версии)
    looks = [{p: {n: look_stats(r.shot(p, n)) for n in names if r.shot(p, n)} for p in packs} for r in runs]
    (out / "look.json").write_text(json.dumps([{"label": r.label, "dir": str(r.dir), "look": lk} for r, lk in zip(runs, looks)], indent=1))

    verdict = None
    if base:
        verdict = stand.judge(cand.res, against)
        verdict["look"] = gate.look_flags(looks[0], looks[1])
        (out / "verdict.json").write_text(json.dumps(verdict, indent=1, ensure_ascii=False))

    md = [f"# Scene stand: {cand.label}", ""]
    ctx = cand.res["context"]
    md.append(f"GPU {ctx['gpu']}, driver {ctx['driver']}, window {ctx['size']}, scenes {ctx['scenes']}, stand {ctx['stand'][:12]}, "
              f"key {cand.res['key']}, jar sha256 {cand.res['jar']['sha256'][:12]} ({cand.res['jar']['file']}).")
    md.append("")
    if verdict:
        md.append(f"## Verdict against {base.label}: {'PASS' if verdict['pass'] else 'FAIL'}")
        md += [f"- {x}" for x in verdict["fails"]] or ["- frame time at 100 strikes, first strike and accumulation within tolerance"]
        md += [f"- note: {x}" for x in verdict["notes"]]
        if verdict["look"]:
            md.append("")
            md.append("Look differs noticeably (for the reviewer, not a failure):")
            md += [f"- {x}" for x in verdict["look"]]
        md.append("")
    md.append("Cells: GPU p50 / CPU p95 / frame p95 (frames per second), ms. GPU — the GPU clock from the first to the last command "
              "of the frame; CPU — the frame's own work on the render thread (RenderFrameEvent.Pre → Post, without the buffer swap); "
              "frame — from the frame start to the next frame start, as the player sees it.")
    comment = [f"### {gate_title(verdict, base)}", "", "| pack | window | " + " | ".join(r.label for r in runs) + " |",
               "|---|---|" + "---|" * len(runs)]
    for pack in packs:
        md += ["", f"## Pack: {pack}", "", "| window | " + " | ".join(r.label for r in runs) + " |", "|---|" + "---|" * len(runs)]
        for wname in wins:
            row = [cell((r.res["packs"].get(pack) or {}).get("windows", {}).get(wname)) for r in runs]
            md.append(f"| {wname} | " + " | ".join(row) + " |")
            if wname.startswith(gate.GATED):
                comment.append(f"| {pack} | {wname} | " + " | ".join(row) + " |")
        firsts = [fmt(((r.res["packs"].get(pack) or {}).get("first") or {}).get("worst_ms"), 0) for r in runs]
        md.append("| first strike, worst frame to +5 s | " + " | ".join(firsts) + " |")
        growth = [((r.res["packs"].get(pack) or {}).get("acc") or {}).get("growth") for r in runs]
        md.append("| accumulation, last minute / first | " + " | ".join("—" if not g or g["ratio"] is None else f"×{g['ratio']:.2f} ({g['first_ms']:.1f} → {g['last_ms']:.1f})" for g in growth) + " |")
        comment.append(f"| {pack} | first strike worst | " + " | ".join(firsts) + " |")
        # где время кадра видеокарты в окнах «100»: стадии кандидата против принятого
        for wname in [w for w in wins if w.startswith(gate.GATED)]:
            cw = (cand.res["packs"][pack].get("windows") or {}).get(wname)
            bw = ((base.res["packs"].get(pack) or {}).get("windows") or {}).get(wname) if base else None
            if not cw:
                continue
            st = sorted(cw["stages"].items(), key=lambda kv: -(kv[1]["mean"] or 0))[:8]
            md += ["", f"GPU by stage, {wname} (mean ms; segment up to a stage = game work before it, `+` = mod handlers at it, "
                       f"`tail` = after the last stage: GUI, post):", "",
                   "| stage | " + cand.label + (f" | {base.label}" if bw else "") + " |", "|---|---|" + ("---|" if bw else "")]
            for k, v in st:
                bv = ((bw or {}).get("stages") or {}).get(k, {}).get("mean")
                md.append(f"| {k} | {fmt(v['mean'], 2)}" + (f" | {fmt(bv, 2)}" if bw else "") + " |")
            md.append("")
            md.append(f"Bound: {cw['bound']}; server tick mean {fmt((cw.get('mspt') or {}).get('mean'))} ms; "
                      f"CPU (frame work Pre→Post) p95 {fmt((cw.get('cpu') or {}).get('p95'))} ms; probes: "
                      + "; ".join(f"{k}: {v}" for k, v in (cw.get("probes") or {}).items()))
        pr = cand.res["packs"][pack]
        if pr.get("checks"):
            md += ["", "Run not valid for comparison: " + "; ".join(pr["checks"])]
        meta = pr.get("meta") or {}
        md += ["", f"Frames {meta.get('frames')}, {fmt(meta.get('gpu_minutes'))} GPU minutes, impacts: "
               + ", ".join(f"{k} {v['got']}/{v['expect']} ({v['why']}, {v['took_s']:.0f} s)" for k, v in (meta.get("impacts") or {}).items())
               + (f"; {meta['size_note']}" if meta.get("size_note") else "")]
        s = sheet(runs, pack, names, out / f"sheet-{pack}.png")
        if s:
            md += ["", f"Frames side by side: `{s.name}`"]
        c = acc_chart(runs, pack, out / f"acc-{pack}.png")
        if c:
            md += [f"Accumulation chart: `{c.name}`"]
    if verdict:
        comment += ["", *([f"- {x}" for x in verdict["fails"]] or ["Frame time at 100 strikes within tolerance of the accepted release."])]
        if verdict["look"]:
            comment += ["", "Look differs noticeably (reviewer, compare the frames):", *[f"- {x}" for x in verdict["look"]]]
    comment += ["", "Cells: GPU p50 / CPU p95 / frame p95 (fps), ms. Report and frames: `mod/run/stand/out/" + out.name + "/` on the laptop."]
    (out / "report.md").write_text("\n".join(md) + "\n")
    (out / "comment.md").write_text("\n".join(comment) + "\n")
    print(out / "report.md")


def gate_title(verdict, base):
    if not verdict:
        return "Scene stand"
    return f"Scene stand against {base.label}: {'pass' if verdict['pass'] else 'FAIL'}"


if __name__ == "__main__":
    main()
