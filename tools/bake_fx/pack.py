# /// script
# dependencies = ["numpy", "Pillow", "pypng"]
# ///
"""Кадры Blender (tools/bake_fx/bake.py) → текстуры слоя эффектов (проба гибрида, handoff P).

  uv run tools/bake_fx/pack.py <папка запекания> <папка текстур fx/particle>

Огненный шар — fireball_00…15 (128×128, умноженная альфа): форма и доля горящего — из симуляции (поле flame), цвет —
чёрное тело: температура пикселя от {T_EDGE} K у края пламени до {T_CORE} K в самом густом (⚠ оценка: в симуляции поле
flame — доля реакции, а не температура), поверх — сажа (непрозрачность поля density). Кадр — по центру вещества, шар
(непрозрачнее половины) — 0,7 полуразмера, как у прежних кадров (FarSprites.FIREBALL). fireball.json — огонь кадра на
экране (средний свет пламени по шару, без сажи; FxAtlas.fireLight).
Клуб дыма — puff6a_00…15 (R — свет справа, G — сверху, B — сзади, A — непрозрачность) и puff6b_00…15 (слева, снизу,
спереди; A — та же): карты нормированы так, что густое место при свете спереди — 0,9; на листе — степень 1/2,2, как
прочие картинки слоя (слой смешивает цвета экрана).
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

T_EDGE, T_CORE = 1200.0, 2300.0
TILE = 128
FRAMES = 16


def load(path):
    """PNG Blender (16 бит, RGBA) → доли 0..1."""
    import png
    w, h, rows, info = png.Reader(filename=path).asDirect()
    a = np.vstack([np.asarray(r, np.float64) for r in rows]).reshape(h, w, info["planes"])
    if info["planes"] == 3:
        a = np.concatenate([a, np.full((h, w, 1), 2 ** info["bitdepth"] - 1.0)], -1)
    return a / (2 ** info["bitdepth"] - 1.0)


def blackbody_rgb(t):
    """Цвет чёрного тела в линейном sRGB (наибольший канал — 1), по Планку на трёх длинах волн и матрице XYZ (приближение)."""
    lam = np.array([610e-9, 550e-9, 465e-9])
    h, c, k = 6.626e-34, 2.998e8, 1.381e-23
    b = 1 / (lam ** 5 * (np.exp(h * c / (lam * k * np.maximum(t, 1)[..., None])) - 1))
    return b / b.max(axis=-1, keepdims=True)


def crop(img, cx, cy, half):
    """Квадрат с центром (cx, cy) и полуразмером half пикселей → TILE×TILE (за краем — пусто)."""
    h, w = img.shape[:2]
    x0, y0 = int(round(cx - half)), int(round(cy - half))
    size = int(round(2 * half))
    out = np.zeros((size, size) + img.shape[2:], img.dtype)
    sx0, sy0, sx1, sy1 = max(0, x0), max(0, y0), min(w, x0 + size), min(h, y0 + size)
    if sx1 > sx0 and sy1 > sy0:
        out[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = img[sy0:sy1, sx0:sx1]
    chans = [Image.fromarray(np.clip(out[..., i] * 65535, 0, 65535).astype(np.uint16).astype(np.int32), mode="I").convert("F")
             .resize((TILE, TILE), Image.LANCZOS) for i in range(out.shape[2])]
    return np.clip(np.stack([np.asarray(c) for c in chans], -1) / 65535.0, 0, 1)


def centre(alpha, level):
    ys, xs = np.nonzero(alpha > level)
    if len(xs) == 0:
        h, w = alpha.shape
        return w / 2, h / 2, w / 4
    wgt = alpha[ys, xs]
    cx, cy = np.average(xs, weights=wgt), np.average(ys, weights=wgt)
    return cx, cy, math.sqrt(len(xs) / math.pi)


def save(arr, path):
    Image.fromarray(np.clip(arr * 255 + 0.5, 0, 255).astype(np.uint8), "RGBA").save(path)


def fireball(src, out):
    flames = sorted(f for f in os.listdir(src) if f.startswith("flame_"))
    n = len(flames)
    data = [(load(os.path.join(src, f))[..., :3].mean(-1), load(os.path.join(src, f.replace("flame_", "soot_")))[..., 3]) for f in flames]
    fmax = np.concatenate([d[0][d[0] > 0].ravel() for d in data if (d[0] > 0).any()]).max()
    # кадры: от вспышки до сажи — с первого кадра, где пламя есть, до последнего, где оно ещё светит заметно
    lit = [i for i, d in enumerate(data) if d[0].max() > 0.02 * fmax]
    first, last = lit[0], min(n - 1, lit[-1] + 6)
    picks = [first + round((last - first) * (i / (FRAMES - 1)) ** 1.3) for i in range(FRAMES)]
    shown = []
    for k, i in enumerate(picks):
        flame, soot = data[i]
        f = np.clip(flame / fmax, 0, 1.5)
        fire_a = 1 - np.exp(-3 * f)
        bright = 1 - np.exp(-4 * f)
        alpha = np.maximum(soot, fire_a)
        cx, cy, r = centre(alpha, 0.5)
        half = max(r / 0.7, 8)
        # температура — по доле пламени от самого густого в кадре: ядро горячее края (⚠ оценка формы, не поле температуры)
        rel = np.clip(flame / max(flame.max(), 1e-9), 0, 1)
        t = T_EDGE + (T_CORE - T_EDGE) * rel ** 3
        rgb = blackbody_rgb(t) * bright[..., None]
        # сажа поверх пламени гасит его на свою непрозрачность за пламенем (половину — ⚠ оценка: сажа и пламя вперемешку)
        dark = np.array([0.05, 0.045, 0.04])
        colour = rgb * (1 - 0.5 * soot[..., None]) + dark * soot[..., None]
        tile = crop(np.concatenate([colour ** (1 / 2.2), alpha[..., None]], -1), cx, cy, half)
        pre = tile.copy()
        pre[..., :3] *= pre[..., 3:4]
        save(pre, os.path.join(out, f"fireball_{k:02d}.png"))
        fire = crop(np.concatenate([rgb, np.ones_like(alpha)[..., None]], -1), cx, cy, half)
        yy, xx = np.mgrid[0:TILE, 0:TILE]
        disc = (xx - TILE / 2 + 0.5) ** 2 + (yy - TILE / 2 + 0.5) ** 2 < (0.7 * TILE / 2) ** 2
        lum = fire[..., 0] * 0.2126 + fire[..., 1] * 0.7152 + fire[..., 2] * 0.0722
        shown.append(round(float(lum[disc].mean()), 4))
    # в тех же долях, что у прежних кадров (gen_particles.py: первый кадр — 1,14 белого): кадр на листе упирается в белое,
    # а свет вспышки ярче; форма спада по кадрам — из симуляции
    shown = [round(v * 1.14 / max(shown[0], 1e-6), 4) for v in shown]
    with open(os.path.join(out, "fireball.json"), "w", encoding="utf-8") as f:
        json.dump({"fire": shown}, f)
    print("fireball frames", picks, "fire", shown)


SIDES_A = ("right", "top", "back")
SIDES_B = ("left", "bottom", "front")


def puff(src, out):
    frames = sorted({int(f.split("_")[1][:3]) for f in os.listdir(src) if f.startswith("front_")})
    picks = [f for f in frames if f >= 8][:FRAMES]
    maps = []
    for i in picks:
        m = {s: load(os.path.join(src, f"{s}_{i:03d}.png")) for s in SIDES_A + SIDES_B}
        alpha = m["front"][..., 3]
        light = {s: m[s][..., :3].mean(-1) for s in m}
        maps.append((alpha, light))
    dense = np.concatenate([l["front"][a > 0.9].ravel() for a, l in maps if (a > 0.9).any()])
    norm = 0.9 / max(np.percentile(dense, 95), 1e-6)
    for k, (alpha, light) in enumerate(maps):
        cx, cy, _ = centre(alpha, 0.05)
        ys, xs = np.nonzero(alpha > 0.05)
        half = max(np.hypot(xs - cx, ys - cy).max() * 1.04, 8) if len(xs) else 64
        for name, sides in (("a", SIDES_A), ("b", SIDES_B)):
            rgb = np.stack([np.clip(light[s] * norm, 0, 1) for s in sides], -1) ** (1 / 2.2)
            tile = crop(np.concatenate([rgb, alpha[..., None]], -1), cx, cy, half)
            tile[..., :3] *= tile[..., 3:4]
            save(tile, os.path.join(out, f"puff6{name}_{k:02d}.png"))
    print("puff frames", picks, "norm", round(norm, 3))


if __name__ == "__main__":
    bake, dest = sys.argv[1], sys.argv[2]
    if os.path.isdir(os.path.join(bake, "fireball")):
        fireball(os.path.join(bake, "fireball"), dest)
    if os.path.isdir(os.path.join(bake, "puff")):
        puff(os.path.join(bake, "puff"), dest)
