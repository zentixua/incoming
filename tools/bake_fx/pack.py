# /// script
# dependencies = ["numpy", "Pillow", "opencv-python-headless", "scipy"]
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
(непрозрачность 0,05) с запасом 4 %: квадрат клуба в моде — этот край (SmokePuffs.edge). «detail» — мелкая структура
кадра (разброс карт света за вычетом их сглаживания по 7×7 пикселям, в непрозрачной части; у самого клубящегося — 1):
симуляция — конечный клуб, к концу он рассеивается и теряет клубы, а настоящее облако, пока растёт, остаётся
турбулентным (термик подобен себе: клубы растут вместе с ним, Scorer 1957), и мод берёт кадры только клубящейся части
(FxAtlas.puffFrame). Пересчитать «detail» по готовым кадрам листа, без запекания:
  uv run tools/bake_fx/pack.py --detail [<папка текстур>]

Поверхность огненного шара ядерки — nukeball.bin рядом с листом (textures/fx; читает client/nuclear/NukeBallSurface):
у каждого кадра запекания — карта по направлениям от центра шара (октаэдрическая, {BALL_N}², ось y — вверх), в
текселе — узор p: во сколько раз перепад температуры поверхности здесь больше среднего. Поверхность — то, что
видно, глядя на шар по радиусу снаружи: свечение газа шара по Кирхгофу вдоль луча к центру (ослабление ∝ доле газа,
{BALL_TAU} толщ на радиус чистого газа — ⚠ оценка: горячий воздух непрозрачен, G&D 2.117), яркость по Вину на 555 нм,
обратно в температуру. Температура газа — смешение: T = T_возд + c·ΔT, ΔT — у мода у 15 кт в этот миг
(FireballModel.temperature; миг — по пути всплытия кадра, CloudModel у 15 кт). Узор кадра нормирован по
Стефану–Больцману: среднее p⁴ по сфере — 1, шар светит мощностью модели (яркость и цвет — по-прежнему её чёрное тело;
воздух против шара не в счёте: T_возд⁴ против T⁴ — до ~1 % к концу сворачивания).
Формат (little-endian): int32 N, int32 кадров, float32 путь всплытия кадра в радиусах шара × кадров, uint16 p/{BALL_P}·65535
× кадров × N × N (строка — v, в строке — u).
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
        # огонь на экране — в мере экрана (степень 1/2,2), как у прежнего шара (gen_particles: ball.json)
        shown = np.clip(tile[..., :3], 0, None) ** (1 / 2.2) * np.clip(tile[..., 3:4], 0, 1)
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


def detail(tiles):
    """Мелкая структура кадров клуба: tiles — (непрозрачность, карты света rgb a и b) кадров; у самого клубящегося — 1."""
    out = []
    for a, rgb in tiles:
        inside = a > 0.5
        spread = []
        for name in ("a", "b"):
            for c in range(3):
                ch = rgb[name][..., c]
                smooth = cv2.blur(ch.astype(np.float32), (7, 7))
                spread.append(float(np.std((ch - smooth)[inside])) if inside.any() else 0.0)
        out.append(float(np.mean(spread)))
    top = max(out) or 1
    return [round(v / top, 4) for v in out]


def detail_only(dest):
    """«detail» в puff6.json и spray6.json — по кадрам листа (карты — степень 1/2,2, обратно в линейные)."""
    for prefix in ("puff6", "spray6"):
        path = os.path.join(dest, f"{prefix}.json")
        if not os.path.exists(path):
            continue
        tiles = []
        for k in range(FRAMES):
            rgb = {}
            for name in ("a", "b"):
                img = np.asarray(Image.open(os.path.join(dest, f"{prefix}{name}_{k:02d}.png")).convert("RGBA"), dtype=np.float64) / 255
                rgb[name] = img[..., :3] ** 2.2
                a = img[..., 3]
            tiles.append((a, rgb))
        meta = json.load(open(path, encoding="utf-8"))
        meta["detail"] = detail(tiles)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(meta, f)
        print(prefix, "detail", meta["detail"])


def puff(src, out, prefix="puff6", like=None):
    """Клуб (prefix — имя кадров на листе). like — puff6.json дыма: карты брызг приводятся к его «diffuse» (у слоя одна
    мера карт на все клубы, FxSixLight), их собственная мера — в «own»."""
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
    own = diffuse
    if like is not None:
        norm *= like["diffuse"] / diffuse
        front, diffuse = front * like["diffuse"] / diffuse, like["diffuse"]
    tiles, growth, r0 = [], [], None
    for alpha, light in maps:
        cx, cy, _, extent = centre(alpha, 0.05)
        half = max(extent * 1.04, 8)
        r0 = r0 or half
        growth.append(round(float(half / r0), 4))
        a = crop(alpha, cx, cy, half)
        rgb = {name: crop(np.stack([np.clip(light[s] * norm, 0, 1) for s in sides], -1), cx, cy, half) for name, sides in (("a", SIDES_A), ("b", SIDES_B))}
        # край кадра — к нулю (последние 8 % радиуса): кадр по краю вещества 0,05, и на густом клубе срез у края квадрата
        # шёл прямой чертой
        yy, xx = np.mgrid[0:TILE, 0:TILE]
        rr = np.hypot(xx - TILE / 2 + 0.5, yy - TILE / 2 + 0.5) / (TILE / 2)
        a = a * np.clip((1 - rr) / 0.08, 0, 1)
        tiles.append((a, rgb))
    for k, (a, rgb) in enumerate(tiles):
        for name in ("a", "b"):
            save_rgba(np.clip(rgb[name], 0, 1) ** (1 / 2.2), a, os.path.join(out, f"{prefix}{name}_{k:02d}.png"))
        # векторы движения к следующему кадру: по непрозрачности (форма), Farneback
        if k + 1 < len(tiles):
            prev = np.clip(a * 255, 0, 255).astype(np.uint8)
            nxt = np.clip(tiles[k + 1][0] * 255, 0, 255).astype(np.uint8)
            flow = cv2.calcOpticalFlowFarneback(prev, nxt, None, 0.5, 3, 15, 3, 5, 1.2, 0)
        else:
            flow = np.zeros((TILE, TILE, 2), np.float32)
        flow = np.clip(flow, -FLOW, FLOW)
        enc = np.stack([0.5 + flow[..., 0] / (2 * FLOW), 0.5 + flow[..., 1] / (2 * FLOW), np.full((TILE, TILE), 0.5)], -1)
        save_rgba(enc, np.ones((TILE, TILE)), os.path.join(out, f"{prefix}v_{k:02d}.png"))
    with open(os.path.join(out, f"{prefix}.json"), "w", encoding="utf-8") as f:
        json.dump({"tile": TILE, "frames": FRAMES, "maps": "abv", "flow": FLOW, "front": round(front, 4), "diffuse": round(diffuse, 4),
                   "own": round(own, 4), "growth": [float(g) for g in growth], "detail": detail(tiles)}, f)
    print("puff frames", picks, "norm", round(norm, 3), "front", round(front, 3), "diffuse", round(diffuse, 3), "growth", growth)


BALL_N = 128
BALL_TAU = 10.0
BALL_P = 2.0
# 15 кт в воздухе (мод): радиус шара, второй максимум, подъём центра (CloudModel: (9840 − 1,3·R)·(1 − e^(−t/121)) м)
BALL_R_M = 70.0 * 15 ** 0.4
BALL_TMAX = 0.032 * 15 ** 0.5
BALL_RISE_M, BALL_RISE_TAU = 9840 - 1.3 * BALL_R_M, 121.0
T_AIR, T_PEAK, COOLING = 288.0, 7700.0, 0.35


def octa_dirs(n):
    """Направления текселей октаэдрической карты n×n (середины), ось y — вверх; и телесный угол текселя."""
    u = (np.arange(n) + 0.5) / n * 2 - 1
    px, pz = np.meshgrid(u, u)
    return octa_decode(px, pz), octa_area(n)


def octa_decode(px, pz):
    y = 1 - np.abs(px) - np.abs(pz)
    x, z = px.copy(), pz.copy()
    low = y < 0
    x[low] = (1 - np.abs(pz[low])) * np.sign(px[low])
    z[low] = (1 - np.abs(px[low])) * np.sign(pz[low])
    v = np.stack([x, y, z], -1)
    return v / np.linalg.norm(v, axis=-1, keepdims=True)


def octa_area(n):
    """Телесный угол текселя: площадь сферического четырёхугольника по углам (два треугольника, Ван Остерома)."""
    e = np.linspace(-1, 1, n + 1)
    ex, ez = np.meshgrid(e, e)
    c = octa_decode(ex, ez)

    def tri(a, b, d):
        num = np.abs(np.einsum("...i,...i", a, np.cross(b, d)))
        den = 1 + np.einsum("...i,...i", a, b) + np.einsum("...i,...i", b, d) + np.einsum("...i,...i", d, a)
        return 2 * np.arctan2(num, den)
    a, b, cc, d = c[:-1, :-1], c[:-1, 1:], c[1:, 1:], c[1:, :-1]
    return tri(a, b, cc) + tri(a, cc, d)


def ball_temperature(rise):
    """Видимая температура шара 15 кт, K, когда его центр поднялся на rise радиусов после второго максимума
    (FireballModel.surfaceRise, CloudModel)."""
    done = BALL_RISE_M * (1 - math.exp(-BALL_TMAX / BALL_RISE_TAU)) + rise * BALL_R_M
    t = max(-BALL_RISE_TAU * math.log(max(1 - done / BALL_RISE_M, 1e-9)), BALL_TMAX)
    return T_AIR + (T_PEAK - T_AIR) * (t / BALL_TMAX) ** -COOLING


def nukeball(src, dest, preview=None):
    from scipy import ndimage
    meta = json.load(open(os.path.join(src, "meta.json")))
    r = meta["radius"]
    dirs, area = octa_dirs(BALL_N)
    flat = dirs.reshape(-1, 3)
    maps, rises = [], []
    # доля газа — в долях её средней в середине шара первого кадра: сетка шума Mantaflow (noise) масштабирует плотность
    # по-своему (у шара — до ×2), а ослабление и температура считаются от доли чистого газа шара
    first = meta["picks"][0]
    c0 = np.load(os.path.join(src, f"c_{first['frame']:04d}.npy")).astype(np.float64)
    near = np.sqrt(((np.indices(c0.shape) - np.array(first["centre"])[:, None, None, None]) ** 2).sum(0)) * first["voxel_m"] < 0.6 * r
    unit = float(c0[near].mean())
    for pick in meta["picks"]:
        c = np.load(os.path.join(src, f"c_{pick['frame']:04d}.npy")).astype(np.float32) / unit
        # лесенка шара по клеткам сетки дала бы на карте кольца по широте: подробностей мельче клетки у счёта нет
        c = ndimage.gaussian_filter(np.clip(c, 0, None), 1.0)
        vox = pick["voxel_m"]
        # оси Blender x, y, z (z — вверх) → у мода x, z, y
        centre = np.array(pick["centre"], np.float64)
        dt = ball_temperature(pick["rise_radii"]) - T_AIR
        reach = 3 * r / vox
        step = 0.5
        n_steps = int(reach / step)
        lum = np.zeros(len(flat))
        tau = np.zeros(len(flat))
        kappa = BALL_TAU / (r / vox)
        for i in range(n_steps):
            d = reach - (i + 0.5) * step
            pos = centre[None, :] + np.stack([flat[:, 0], flat[:, 2], flat[:, 1]], -1) * d
            cs = ndimage.map_coordinates(c, pos.T, order=1, mode="constant", cval=0.0)
            k = kappa * cs * step
            temp = T_AIR + cs * dt
            wien = np.exp(-C2 / (LAMBDA * temp))
            lum += np.exp(-tau) * (1 - np.exp(-k)) * wien
            tau += k
        tb = C2 / (LAMBDA * -np.log(np.clip(lum, 1e-300, None)))
        p = np.clip((tb - T_AIR) / dt, 0, None).reshape(BALL_N, BALL_N)
        p /= (np.sum(area * p ** 4) / np.sum(area)) ** 0.25
        maps.append(p)
        rises.append(pick["rise_radii"])
    maps = np.array(maps)
    assert maps.max() < BALL_P, f"узор выше {BALL_P}: {maps.max()}"
    import struct
    path = os.path.join(dest, "nukeball.bin")
    with open(path, "wb") as f:
        f.write(struct.pack("<ii", BALL_N, len(maps)))
        f.write(np.array(rises, "<f4").tobytes())
        f.write(np.clip(np.round(maps / BALL_P * 65535), 0, 65535).astype("<u2").tobytes())
    w = area / area.sum()
    for k in (0, len(maps) // 4, len(maps) // 2, len(maps) - 1):
        m = maps[k]
        mean = float(np.sum(w * m))
        print(f"nukeball frame {k}: rise {rises[k]:.2f} R, p mean {mean:.3f}, std {float(np.sqrt(np.sum(w * (m - mean) ** 2))):.3f}, "
              f"min {m.min():.2f}, max {m.max():.2f}")
    print("nukeball", path, os.path.getsize(path), "bytes")
    if preview:
        os.makedirs(preview, exist_ok=True)
        for k in range(0, len(maps), max(1, len(maps) // 16)):
            save_rgba(np.repeat(np.clip(maps[k] / 1.6, 0, 1)[..., None], 3, -1), np.ones((BALL_N, BALL_N)),
                      os.path.join(preview, f"map_{k:02d}.png"))


if __name__ == "__main__":
    if sys.argv[1] == "--detail":
        detail_only(sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "../../mod/src/main/resources/assets/incoming/textures/fx/particle"))
        sys.exit(0)
    bake = sys.argv[1]
    dest = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "../../mod/src/main/resources/assets/incoming/textures/fx/particle")
    if os.path.isdir(os.path.join(bake, "fireball")):
        fireball(os.path.join(bake, "fireball"), dest)
    if os.path.isdir(os.path.join(bake, "puff")):
        puff(os.path.join(bake, "puff"), dest)
    if os.path.isdir(os.path.join(bake, "nukeball")):
        nukeball(os.path.join(bake, "nukeball"), os.path.join(dest, ".."), os.environ.get("NUKEBALL_PREVIEW"))
    if os.path.isdir(os.path.join(bake, "spray")):
        puff(os.path.join(bake, "spray"), dest, "spray6", json.load(open(os.path.join(dest, "puff6.json"))))
