# /// script
# dependencies = ["numpy", "Pillow", "opencv-python-headless"]
# ///
"""Кадры запекания (tools/bake_fx/bake.py) → текстуры слоя эффектов (mod/src/main/resources/assets/incoming/textures/fx/particle).

  uv run tools/bake_fx/pack.py <папка запекания> [<папка текстур>]

Огненный шар — fireball_00…15 (128×128, прямая альфа: лист слоя умножает сам) и fireball.json:
- кадры: {BURN_FRAMES} — равно по времени горения симуляции (0…t), остальные — остывание, равно по цветовой
  температуре пламени до точки Дрейпера; у кадра — время в долях горения («burn») и цветовая температура («kelvin»):
  мод берёт кадр по состоянию шара своей модели (горит — по времени, остывает — по температуре, FxAtlas.fireballFrame);
- цвет: рендер — излучение по Кирхгофу (яркость против чёрного тела 1490 K, белая стена днём — 1); на лист — плёночное
  плечо 1 − e^(−x) по каналам и примесь белого {CROSS} на единицу яркости сверх белой (пересвет), степень 1/2,2;
- кадр — по шару (непрозрачнее половины): полуразмер — {FIREBALL} радиуса шара, как FarSprites.FIREBALL;
- «fire» — огонь кадра на экране: средняя яркость по кругу шара (доля белого, FxAtlas.fireLight).
Температура кадра — из яркости самого яркого пламени кадра: в рендере она — e^(c₂/λ·(1/1490 − 1/T)) (Вин на 555 нм),
отсюда T. Цвет запекания — его же: проверка, что излучение поверхности в горении — порядка 275 кВт/м² CCPS, — в выводе.

Клуб дыма — puff6a_00…15 (R — свет справа, G — сверху, B — сзади, A — непрозрачность), puff6b_00…15 (слева, снизу,
спереди; A — та же), puff6v_00…15 (векторы движения к следующему кадру: R, G = 0,5 + смещение/(2·{FLOW}) пикселей
кадра; A — 1) и puff6.json: рост края вещества против первого кадра («growth», мод берёт кадр по росту облака своей
модели, FxAtlas.puffFrame), предел смещения («flow»), размер кадра и число карт. Карты — в одной мере (ярчайшее из
всех кадров и сторон — 1, без среза); «front» и «diffuse» в puff6.json — средний по клубу свет спереди и средний по
шести сторонам (на непрозрачность, весом непрозрачности): к ним шейдер приводит карты (белая стена — 1); на листе — степень
1/2,2 (шейдер складывает свет в линейном). Кадр — по краю вещества
(непрозрачность 0,05) с запасом 4 %: квадрат клуба в моде — этот край (SmokePuffs.edge).
"""
import json
import math
import os
import sys

import cv2
import numpy as np
from PIL import Image

TILE = 128
FRAMES = 16
BURN_FRAMES = 10
FIREBALL = 1.3
FLOW = 32.0
DRAPER = 798.0
C2, LAMBDA, T_REF = 1.4388e-2, 555e-9, 1490.0
SIGMA = 5.670374e-8
CROSS = 0.05


def load(path):
    return np.load(path).astype(np.float64)


def crop(img, cx, cy, half):
    """Квадрат с центром (cx, cy) и полуразмером half пикселей → TILE×TILE (за краем — пусто), площадью."""
    h, w = img.shape[:2]
    size = int(round(2 * half))
    x0, y0 = int(round(cx - half)), int(round(cy - half))
    out = np.zeros((size, size) + img.shape[2:], np.float32)
    sx0, sy0, sx1, sy1 = max(0, x0), max(0, y0), min(w, x0 + size), min(h, y0 + size)
    if sx1 > sx0 and sy1 > sy0:
        out[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = img[sy0:sy1, sx0:sx1]
    return cv2.resize(out, (TILE, TILE), interpolation=cv2.INTER_AREA if size > TILE else cv2.INTER_CUBIC)


def centre(alpha, level):
    ys, xs = np.nonzero(alpha > level)
    if len(xs) == 0:
        h, w = alpha.shape
        return w / 2, h / 2, w / 4, w / 4
    wgt = alpha[ys, xs]
    cx, cy = np.average(xs, weights=wgt), np.average(ys, weights=wgt)
    return cx, cy, math.sqrt(len(xs) / math.pi), np.hypot(xs - cx, ys - cy).max()


def save_rgba(rgb, alpha, path):
    """Прямая альфа, 8 бит: цвет — уже на экране (с гаммой)."""
    a = np.clip(alpha, 0, 1)
    arr = np.concatenate([np.clip(rgb, 0, 1), a[..., None]], -1)
    Image.fromarray(np.clip(np.nan_to_num(arr) * 255 + 0.5, 0, 255).astype(np.uint8), "RGBA").save(path, optimize=True)


def kelvin_of(lum):
    """Температура пламени по яркости против чёрного тела 1490 K (Вин на 555 нм)."""
    lum = max(lum, 1e-12)
    inv = 1 / T_REF - math.log(lum) * LAMBDA / C2
    return 1 / inv if inv > 0 else 1e5


def fireball(src, out):
    meta = json.load(open(os.path.join(src, "meta.json")))
    files = sorted(f for f in os.listdir(src) if f.startswith("f_") and f.endswith(".npy"))
    fps, burn_s = meta["fps"], meta["burn_s"]
    data = []
    for f in files:
        a = load(os.path.join(src, f))
        alpha = a[..., 3]
        rgb = a[..., :3] / np.maximum(alpha[..., None], 1e-4)
        rgb[alpha < 1e-4] = 0
        lum = rgb @ np.array([0.2126, 0.7152, 0.0722])
        lit = lum[alpha > 0.5]
        # самое яркое пламя кадра — верхняя десятая по яркости у непрозрачного
        top = float(np.percentile(lit, 90)) if lit.size else 0.0
        # излучение поверхности: σT⁴ пикселя по его яркости (Вин), среднее по непрозрачному
        sep = float(np.mean(SIGMA * np.vectorize(kelvin_of)(lit) ** 4)) if lit.size else 0.0
        data.append((rgb, alpha, lum, top, sep))
    times = [(int(f[2:5]) - 1) / fps for f in files]
    kel = [kelvin_of(d[3]) if d[3] > 0 else 0 for d in data]
    # горение: кадры равно по времени до t; остывание: от конца горения равно по температуре до Дрейпера
    burn_idx = [min(len(data) - 1, int(round(i * burn_s / BURN_FRAMES * fps))) for i in range(BURN_FRAMES)]
    end = next((i for i, t in enumerate(times) if t >= burn_s), len(data) - 1)
    k0 = kel[end]
    cool = []
    for j in range(1, FRAMES - BURN_FRAMES + 1):
        target = k0 + (DRAPER - k0) * j / (FRAMES - BURN_FRAMES)
        i = next((i for i in range(end, len(data)) if kel[i] <= target), len(data) - 1)
        cool.append(max(i, cool[-1] + 1 if cool else end))
    picks = [min(i, len(data) - 1) for i in burn_idx + cool]
    fire, burn, kelv = [], [], []
    for k, i in enumerate(picks):
        rgb, alpha, lum, top, _ = data[i]
        cx, cy, r, _ = centre(alpha, 0.5)
        half = max(r * FIREBALL, 8)
        # пересвет: по каналам 1 − e^(−x), и ярче белого — с примесью белого (глаз насыщается по всем каналам: вспышка
        # белёсая, а не лимонная; ⚠ доля подобрана: у прежних кадров gen_particles — 0,15, при ней шар топлива белый)
        disp = 1 - np.exp(-rgb)
        disp += (1 - disp) * np.clip(CROSS * np.maximum(lum - 1, 0), 0, 1)[..., None]
        tile = crop(np.concatenate([disp, alpha[..., None]], -1), cx, cy, half)
        save_rgba(tile[..., :3] ** (1 / 2.2), tile[..., 3], os.path.join(out, f"fireball_{k:02d}.png"))
        yy, xx = np.mgrid[0:TILE, 0:TILE]
        disc = (xx - TILE / 2 + 0.5) ** 2 + (yy - TILE / 2 + 0.5) ** 2 < (TILE / 2 / FIREBALL) ** 2
        shown = tile[..., :3] * tile[..., 3:4]
        fire.append(round(float((shown @ np.array([0.2126, 0.7152, 0.0722]))[disc].mean()), 4))
        burn.append(round(times[i] / burn_s, 4))
        kelv.append(round(kel[i], 1))
    # излучение поверхности в горении по цветовой температуре кадров горения — против CCPS 200–350 кВт/м²
    sep = np.mean([data[i][4] for i in burn_idx]) / 1e3
    with open(os.path.join(out, "fireball.json"), "w", encoding="utf-8") as f:
        json.dump({"tile": TILE, "frames": FRAMES, "fire": fire, "burn": burn, "kelvin": kelv}, f)
    print("fireball frames", picks, "\n  burn", burn, "\n  kelvin", kelv, "\n  fire", fire, f"\n  surface power in burning ~{sep:.0f} kW/m² (CCPS 200–350)")


SIDES_A = ("right", "top", "back")
SIDES_B = ("left", "bottom", "front")


def puff(src, out):
    meta = json.load(open(os.path.join(src, "meta.json")))
    picks = meta["picks"][-FRAMES:]
    maps = []
    for i in picks:
        m = {s: load(os.path.join(src, f"{s}_{i:03d}.npy")) for s in SIDES_A + SIDES_B}
        alpha = m["front"][..., 3]
        light = {s: m[s][..., :3].mean(-1) / np.maximum(m[s][..., 3], 1e-4) for s in m}
        maps.append((alpha, light))
    # карты — в одной мере на все кадры и стороны: ярчайшее (просвет края сзади) — 1, без среза
    lit = np.concatenate([l[x][a > 0.05].ravel() for a, l in maps for x in SIDES_A + SIDES_B])
    norm = 1 / max(np.percentile(lit, 99.5), 1e-6)
    # к чему шейдер нормирует карты (белая стена): средний свет клуба на его непрозрачность при свете спереди и средний по
    # шести сторонам — типичное место клуба в свете неба выходит как толстый слой его вещества (цвет — у мода)
    num_f = sum(float(np.sum(l["front"] * a)) for a, l in maps)
    num_d = sum(float(np.sum(np.mean([l[x] for x in SIDES_A + SIDES_B], 0) * a)) for a, l in maps)
    den = sum(float(np.sum(a * a)) for a, l in maps)
    front, diffuse = num_f / den * norm, num_d / den * norm
    tiles, growth, r0 = [], [], None
    for alpha, light in maps:
        cx, cy, _, extent = centre(alpha, 0.05)
        half = max(extent * 1.04, 8)
        r0 = r0 or half
        growth.append(round(float(half / r0), 4))
        a = crop(alpha, cx, cy, half)
        rgb = {name: crop(np.stack([np.clip(light[s] * norm, 0, 1) for s in sides], -1), cx, cy, half) for name, sides in (("a", SIDES_A), ("b", SIDES_B))}
        tiles.append((a, rgb))
    for k, (a, rgb) in enumerate(tiles):
        for name in ("a", "b"):
            save_rgba(np.clip(rgb[name], 0, 1) ** (1 / 2.2), a, os.path.join(out, f"puff6{name}_{k:02d}.png"))
        # векторы движения к следующему кадру: по непрозрачности (форма), Farneback
        if k + 1 < len(tiles):
            prev = np.clip(a * 255, 0, 255).astype(np.uint8)
            nxt = np.clip(tiles[k + 1][0] * 255, 0, 255).astype(np.uint8)
            flow = cv2.calcOpticalFlowFarneback(prev, nxt, None, 0.5, 3, 15, 3, 5, 1.2, 0)
        else:
            flow = np.zeros((TILE, TILE, 2), np.float32)
        flow = np.clip(flow, -FLOW, FLOW)
        enc = np.stack([0.5 + flow[..., 0] / (2 * FLOW), 0.5 + flow[..., 1] / (2 * FLOW), np.full((TILE, TILE), 0.5)], -1)
        save_rgba(enc, np.ones((TILE, TILE)), os.path.join(out, f"puff6v_{k:02d}.png"))
    with open(os.path.join(out, "puff6.json"), "w", encoding="utf-8") as f:
        json.dump({"tile": TILE, "frames": FRAMES, "maps": "abv", "flow": FLOW, "front": round(front, 4), "diffuse": round(diffuse, 4),
                   "growth": [float(g) for g in growth]}, f)
    print("puff frames", picks, "norm", round(norm, 3), "front", round(front, 3), "diffuse", round(diffuse, 3), "growth", growth)


if __name__ == "__main__":
    bake = sys.argv[1]
    dest = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "../../mod/src/main/resources/assets/incoming/textures/fx/particle")
    if os.path.isdir(os.path.join(bake, "fireball")):
        fireball(os.path.join(bake, "fireball"), dest)
    if os.path.isdir(os.path.join(bake, "puff")):
        puff(os.path.join(bake, "puff"), dest)
