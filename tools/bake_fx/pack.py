# /// script
# dependencies = ["numpy", "Pillow", "opencv-python-headless", "scipy"]
# ///
"""Кадры запекания (tools/bake_fx/bake.py) → текстуры слоя эффектов (mod/src/main/resources/assets/incoming/textures/fx/particle).

  uv run tools/bake_fx/pack.py <папка запекания> [<папка текстур>]

Огненный шар — fireball_00…47 (256×256, прямая альфа: лист слоя умножает сам), их векторы движения fireballv_00…47
(128×128: R, G = 0,5 + смещение/(2·{FIREBALL_FLOW}) пикселей кадра 256 к следующему кадру; A — 1) и fireball.json:
- кадры: {BURN_FRAMES} — равно по времени горения симуляции (0…t, последний — сам конец горения), остальные — остывание,
  равно по времени симуляции до последнего кадра со светящимся пламенем; у кадра — время в долях горения («burn») и
  цветовая температура пламени («kelvin», у остывания — гладко и строго вниз): мод берёт кадр по состоянию шара своей
  модели (горит — по времени, остывает — по температуре, FxAtlas.fireballFrame), между кадрами — смесь вдоль векторов;
- цвет: рендер — излучение по Кирхгофу (яркость против чёрного тела 1490 K, белая стена днём — 1); на лист — подача:
  экспозиция по огню (film_exposure) и плёночная кривая по каналам (film), степень 1/2,2;
- кадр — одна мера на всю анимацию: радиус шара (круг площади непрозрачного) у горения — радиус шара модели, у остывания —
  мера конца горения; рамка вмещает всё вещество кадра (край — по плотности, EDGE_ALPHA), не уже {FIREBALL} радиусов
  шара и не сужается; столб от земли под шаром — до {DOWN} радиусов вниз; «half» — полуразмер кадра в радиусах шара;
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
FIREBALL_TILE = 256
FRAMES = 16
# огненный шар: кадров всего и горения; карты векторов движения — 128², смещение до {FIREBALL_FLOW} пикселей кадра 256
FIREBALL_FRAMES = 48
BURN_FRAMES = 32
FIREBALL_FLOW_TILE = 128
FIREBALL_FLOW = 16.0
# низ кадра шара: столб симуляции от земли под шаром — не дальше стольких радиусов шара от его середины (ниже — земля
# у любого оружия: середина шара модели — 0,45–1,5 радиуса над ней), к краю — плавно на последних {DOWN_SOFT}
DOWN, DOWN_SOFT = 1.5, 0.3
# рамка кадра шара — не меньше стольких радиусов шара (как у прежнего кадра, FarSprites.FIREBALL)
FIREBALL = 1.3
# край вещества шара — по плотности: тоньше этой непрозрачности дымка сходит на нет плавно
EDGE_ALPHA = 0.05
FLOW = 32.0
DRAPER = 798.0
C2, LAMBDA, T_REF = 1.4388e-2, 555e-9, 1490.0
SIGMA = 5.670374e-8
# экспозиция кадров шара (film_exposure): доля яркости горящего шара и где она встаёт на кривой
FILM_KEY, FILM_TOP = 95, 0.60
DARK_START = 0.02


def load(path):
    return np.load(path).astype(np.float64)


def crop(img, cx, cy, half, tile=TILE):
    """Квадрат с центром (cx, cy) и полуразмером half пикселей → tile×tile (за краем — пусто), площадью."""
    h, w = img.shape[:2]
    size = int(round(2 * half))
    x0, y0 = int(round(cx - half)), int(round(cy - half))
    out = np.zeros((size, size) + img.shape[2:], np.float32)
    sx0, sy0, sx1, sy1 = max(0, x0), max(0, y0), min(w, x0 + size), min(h, y0 + size)
    if sx1 > sx0 and sy1 > sy0:
        out[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = img[sy0:sy1, sx0:sx1]
    return cv2.resize(out, (tile, tile), interpolation=cv2.INTER_AREA if size > tile else cv2.INTER_CUBIC)


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


def film(x):
    """Плёночная кривая по каналам (ACES, приближение Narkowicz 2016, CC0, выбран из CC0/MIT у автора): носок держит сажу тёмной, плечо — ядро без среза
    в плоский белый; горячее по каналам уходит в жёлтое и белое, как у плёнки и глаза."""
    x = np.maximum(x, 0)
    return np.clip(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14), 0, 1)


def film_exposure(burning):
    """Экспозиция по шару — подача, не физика: камера в кино ставит её по огню, а не по белой стене. Яркость шара
    против белой стены — десятки раз (чёрное тело ~1700 K против дневного света), и кадр в мере белой стены срезал весь
    шар в ровный жёлтый диск — складок, языков и сажи не видно. Здесь {FILM_KEY}-я доля яркости горящего шара (по
    непрозрачному, во всех кадрах горения) встаёт в плечо кривой, на {FILM_TOP} белого: ярче — белеет, тусклее в
    10–100 раз (остывшие складки, сажа) — тёмно-красное и чёрное. Одна экспозиция на все кадры: остывание темнеет,
    как в жизни. Только сверх белого — плавное обесцвечивание ядра (связь каналов), чтобы нулевой синий
    чёрного тела не оставлял пересвет лимонно-жёлтым. Свет шара в модели — по-прежнему чёрное тело, но свет вокруг (блик, вуаль, зарево, свет на дым) не
    ярче огня кадра на экране («fire», FxAtlas.fireLightAt) — и он идёт за этой экспозицией: ~0,37 белого, а не ~0,87."""
    lit = np.concatenate([d[2][d[1] > 0.5] for d in burning]) if burning else np.ones(1)
    key = float(np.percentile(lit, FILM_KEY)) if lit.size else 1.0
    # film(x) = FILM_TOP — решение квадратного уравнения кривой
    a, b, c, d, e = 2.51, 0.03, 2.43, 0.59, 0.14
    t = FILM_TOP
    qa, qb, qc = a - t * c, b - t * d, -t * e
    x = (-qb + math.sqrt(qb * qb - 4 * qa * qc)) / (2 * qa)
    return x / max(key, 1e-9)


def kelvin_of(lum):
    """Температура пламени по яркости против чёрного тела 1490 K (Вин на 555 нм)."""
    lum = max(lum, 1e-12)
    inv = 1 / T_REF - math.log(lum) * LAMBDA / C2
    return 1 / inv if inv > 0 else 1e5


def smooth(values, sigma):
    """Гладко по кадрам (гаусс, края — отражением): размер и середина кадра без дрожи от кадра к кадру."""
    v = np.asarray(values, np.float64)
    if sigma <= 0 or len(v) < 3:
        return v
    from scipy.ndimage import gaussian_filter1d
    return gaussian_filter1d(v, sigma, mode="nearest")


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
    # готовая симуляция (bake.py fireball-vdb) начинается с кадров до поджига: пусто, потом облако паров и дыма источника,
    # под которым разгорается пламя. Шар топлива по CCPS светится весь с первого мига: время — с первого кадра, где
    # пламя кадра не меньше половины самого яркого и тёмного (тусклее десятой доли белой стены) — не больше {DARK_START}
    # непрозрачного
    peak = max(d[3] for d in data)

    def dark(d):
        m = d[1] > 0.5
        return float((d[2][m] < 0.1).mean()) if m.any() else 1.0

    first = next((i for i, d in enumerate(data) if d[3] >= 0.5 * peak and dark(d) <= DARK_START), 0)
    data, times, files = data[first:], [t - times[first] for t in times[first:]], files[first:]
    burn_s -= (int(files[0][2:5]) - 1) / fps
    kel = [kelvin_of(d[3]) if d[3] > 0 else 0 for d in data]
    # горение: {BURN_FRAMES} кадров равно по времени от вспышки до конца горения (последний — сам конец); между ними в
    # игре — смесь вдоль векторов движения, и кадров должно быть столько, чтобы смесь была движением, а не наплывом
    # неподвижных картинок (16 кадров на 2–3 с горения — ~0,2 с на кадр: силуэт стоял, ревью #426). Кадры — разные
    # рендеры, не повторы: иначе в игре снова стоп-кадр
    burn_idx = [int(np.argmin([abs(t - i * burn_s / (BURN_FRAMES - 1)) for t in times])) for i in range(BURN_FRAMES)]
    if len(set(burn_idx)) < BURN_FRAMES:
        raise ValueError(f"горению нужно {BURN_FRAMES} разных рендеров (bake.py --frames без пропусков), а не повторы")
    end = burn_idx[-1]
    # остывание: кадры равно по времени симуляции от конца горения до последнего, где пламя ещё светится, — движение
    # без рывков (смесь вдоль векторов движения); у кадра — цветовая температура его пламени (не выше прошлого кадра):
    # мод берёт кадр остывания по температуре шара своей модели
    last = max((i for i in range(end, len(data)) if kel[i] > 0), default=end)
    n_cool = FIREBALL_FRAMES - BURN_FRAMES
    cool = [int(round(end + (last - end) * j / n_cool)) for j in range(1, n_cool + 1)]
    if len(set(cool)) < n_cool or cool[0] <= end:
        raise ValueError(f"остыванию нужно {n_cool} разных рендеров после конца горения (bake.py --frames до потухания пламени)")
    picks = burn_idx + cool
    # температура остывающих кадров — гладко по времени и строго вниз: яркость пламени кадра шумит, а мод идёт по кадрам
    # по температуре, и площадка в таблице пролистывала бы несколько кадров за миг
    cooled = smooth([kel[end]] + [kel[i] for i in cool], 1.0)
    for j, i in enumerate(cool):
        kel[i] = min(float(cooled[j + 1]), kel[picks[BURN_FRAMES + j - 1]] - 1.0)
    exposure = film_exposure([data[i] for i in burn_idx])
    # Кадрирование — одна мера на всю анимацию, без скачков. Масштаб: у кадров горения радиус шара (круг площади
    # непрозрачного, гладко по кадрам) — радиус шара модели; остывая, шар симуляции тает в дым и непрозрачного всё
    # меньше, а мера — та, что в конец горения (сажа растёт в кадре сама, как в симуляции). Середина — середина
    # непрозрачного в горении, дальше — за серединой всего вещества. Рамка — всё видимое вещество кадра, а не круг шара:
    # круг обрезал остывающую сажу диском (ревью #426); рамка не меньше {FIREBALL} радиусов шара и не сужается.
    geo = []
    for k, i in enumerate(picks):
        alpha = data[i][1]
        cx, cy, r, _ = centre(alpha, 0.5)
        mx, my, _, _ = centre(alpha, 0.02)
        geo.append([cx, cy, r, mx, my])
    geo = np.array(geo)
    scale = smooth(geo[:BURN_FRAMES, 2], 1.5)
    scale = np.concatenate([scale, np.full(len(cool), scale[-1])])
    bx, by = geo[:BURN_FRAMES, 0], geo[:BURN_FRAMES, 1]
    ox, oy = bx[-1] - geo[BURN_FRAMES - 1, 3], by[-1] - geo[BURN_FRAMES - 1, 4]
    cxs = smooth(np.concatenate([bx, geo[BURN_FRAMES:, 3] + ox]), 1.5)
    cys = smooth(np.concatenate([by, geo[BURN_FRAMES:, 4] + oy]), 1.5)
    shown = []
    for k, i in enumerate(picks):
        rgb, alpha = data[i][0], data[i][1]
        # Подача: ниже плеча огонь оранжевый, а не весь жёлтый. Только сверх белого — связь каналов:
        # камера/глаз теряет насыщенность пересвета, горячие ядра белеют даже при нулевом синем у рендера.
        exposed = rgb * exposure
        disp = film(exposed)
        white = 0.35 * (1 - np.exp(-np.maximum(exposed.max(axis=-1) - 1, 0)))
        disp += (1 - disp) * white[..., None]
        # Под ONE, ONE_MINUS_SRC_ALPHA цвет листа умножается на альфу. Яркое тонкое пламя не должно быть
        # полупрозрачной жёлтой плёнкой поверх неба/сажи: его альфа не ниже экранной яркости.
        a = np.maximum(alpha, disp ** (1 / 2.2) @ np.array([0.2126, 0.7152, 0.0722]))
        # край — по плотности: редкая дымка тоньше {EDGE_ALPHA} непрозрачности сходит на нет плавно (там же и остаток шума
        # рендера), а не круг по радиусу кадра
        u = np.clip(a / EDGE_ALPHA, 0, 1)
        a = a * u * u * (3 - 2 * u)
        rows = np.arange(a.shape[0])[:, None]
        u = np.clip((cys[k] + DOWN * scale[k] - rows) / (DOWN_SOFT * scale[k]), 0, 1)
        a = a * u * u * (3 - 2 * u)
        shown.append((disp, a))
    # рамка: всё вещество кадра (непрозрачнее 1/255) вокруг середины, с запасом, не уже прошлой и {FIREBALL} радиусов шара
    halves = []
    for k, (disp, a) in enumerate(shown):
        ys, xs = np.nonzero(a > 1 / 255)
        extent = max(float(np.max(np.abs(xs - cxs[k]))), float(np.max(np.abs(ys - cys[k])))) if xs.size else 0.0
        w = max(1.03 * extent + 2, FIREBALL * scale[k], halves[-1] if halves else 0)
        halves.append(w)
    tiles = []
    for k, (disp, a) in enumerate(shown):
        # кадр — с умноженной альфой: при уменьшении горячий цвет не растекается в почти пустую сажу
        tile = crop(np.concatenate([disp * a[..., None], a[..., None]], -1), cxs[k], cys[k], halves[k], FIREBALL_TILE)
        pa = np.clip(tile[..., 3], 0, 1)
        # кромка рамки: два пикселя к нулю (вещество до неё не доходит; страховка от выборки соседнего места листа)
        side = np.minimum(np.arange(FIREBALL_TILE), np.arange(FIREBALL_TILE)[::-1])
        pa = pa * np.clip((np.minimum(side[None, :], side[:, None]) + 0.5) / 2, 0, 1)
        col = np.clip(tile[..., :3], 0, None) / np.maximum(tile[..., 3:4], 1e-6)
        # Кубическая выборка при увеличении даёт малые отрицательные выбросы у резкого края: до гаммы, не после NaN.
        col = np.clip(col, 0, 1)
        screen = col ** (1 / 2.2)
        # Кайма тоже часть огня: редея, она краснеет и темнеет, а не желтеет на чёрном/голубом фоне.
        thin = np.clip(pa / 0.85, 0, 1)
        screen[..., 1] *= thin
        screen[..., 2] *= thin * thin
        luminance = screen @ np.array([0.2126, 0.7152, 0.0722])
        screen *= np.minimum(1, pa / np.maximum(luminance, 1e-9))[..., None]
        tiles.append((screen, pa))
        save_rgba(screen, pa, os.path.join(out, f"fireball_{k:02d}.png"))
    # векторы движения к следующему кадру (как у клуба дыма): Farneback по кадру на сером фоне — и пламя, и сажа; смесь
    # кадров в шейдере идёт вдоль них, и огонь течёт, а не проступает двойным. Разная рамка соседних кадров — тоже движение
    fire, burn, kelv, flows = [], [], [], []
    grey = []
    for screen, pa in tiles:
        g = (screen * pa[..., None]) @ np.array([0.2126, 0.7152, 0.0722]) + 0.5 * (1 - pa)
        grey.append(np.clip(g * 255, 0, 255).astype(np.uint8))
    for k in range(len(tiles)):
        if k + 1 < len(tiles):
            flow = cv2.calcOpticalFlowFarneback(grey[k], grey[k + 1], None, 0.5, 4, 21, 5, 7, 1.5, 0)
        else:
            flow = np.zeros((FIREBALL_TILE, FIREBALL_TILE, 2), np.float32)
        flows.append(float(np.percentile(np.hypot(flow[..., 0], flow[..., 1])[tiles[k][1] > 0.05], 99)) if (tiles[k][1] > 0.05).any() else 0.0)
        small = cv2.resize(flow, (FIREBALL_FLOW_TILE, FIREBALL_FLOW_TILE), interpolation=cv2.INTER_AREA)
        small = np.clip(small, -FIREBALL_FLOW, FIREBALL_FLOW)
        enc = np.stack([0.5 + small[..., 0] / (2 * FIREBALL_FLOW), 0.5 + small[..., 1] / (2 * FIREBALL_FLOW),
                        np.full(small.shape[:2], 0.5)], -1)
        save_rgba(enc, np.ones(small.shape[:2]), os.path.join(out, f"fireballv_{k:02d}.png"))
    for k, i in enumerate(picks):
        screen, pa = tiles[k]
        yy, xx = np.mgrid[0:FIREBALL_TILE, 0:FIREBALL_TILE]
        ball = FIREBALL_TILE / 2 * scale[k] / halves[k]
        disc = (xx - FIREBALL_TILE / 2 + 0.5) ** 2 + (yy - FIREBALL_TILE / 2 + 0.5) ** 2 < ball ** 2
        # огонь на экране — в мере экрана (степень 1/2,2), как у прежнего шара (gen_particles: ball.json)
        lit = screen * pa[..., None]
        fire.append(round(float((lit @ np.array([0.2126, 0.7152, 0.0722]))[disc].mean()), 4))
        burn.append(round(times[i] / burn_s, 4))
        kelv.append(round(kel[i], 1))
    half = [round(float(halves[k] / scale[k]), 4) for k in range(len(picks))]
    # излучение поверхности в горении по цветовой температуре кадров горения — против CCPS 200–350 кВт/м²
    sep = np.mean([data[i][4] for i in burn_idx]) / 1e3
    with open(os.path.join(out, "fireball.json"), "w", encoding="utf-8") as f:
        json.dump({"tile": FIREBALL_TILE, "frames": FIREBALL_FRAMES, "burn_frames": BURN_FRAMES, "fire": fire, "burn": burn,
                   "kelvin": kelv, "half": half, "flow": FIREBALL_FLOW, "flow_tile": FIREBALL_FLOW_TILE,
                   "source_frames": [int(files[i][2:5]) for i in picks], "render": meta.get("render_size"),
                   "samples": meta.get("samples"), "adaptive_sampling": meta.get("adaptive_sampling")}, f)
    print("fireball frames", [int(files[i][2:5]) for i in picks], "\n  burn", burn, "\n  kelvin", kelv, "\n  fire", fire,
          "\n  half", half, "\n  flow p99", [round(v, 1) for v in flows],
          f"\n  surface power in burning ~{sep:.0f} kW/m² (CCPS 200–350)")


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
    # рендер готовой симуляции (bake.py puff-vdb) — своей камерой на кадр: рост — в мере сцены (вокселей на пиксель)
    scale = meta.get("scale", [1.0] * len(meta["picks"]))[-FRAMES:]
    tiles, growth, r0 = [], [], None
    for (alpha, light), k in zip(maps, scale):
        cx, cy, _, extent = centre(alpha, 0.05)
        half = max(extent * 1.04, 8)
        r0 = r0 or half * k
        growth.append(round(float(half * k / r0), 4))
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


def remeasure(dest, prefix, like):
    """Карты клуба prefix на листе — в меру нового дыма (like — его puff6.json): у слоя одна мера карт на все клубы
    (FxSixLight), а клуб дыма запечён заново без брызг. Свет — линейный (на листе — степень 1/2,2), альфа та же."""
    meta = json.load(open(os.path.join(dest, f"{prefix}.json")))
    k = like["diffuse"] / meta["diffuse"]
    if abs(k - 1) < 1e-6:
        return
    for i in range(meta["frames"]):
        for name in ("a", "b"):
            path = os.path.join(dest, f"{prefix}{name}_{i:02d}.png")
            img = np.asarray(Image.open(path).convert("RGBA"), np.float64) / 255
            save_rgba(np.clip(img[..., :3] ** 2.2 * k, 0, 1) ** (1 / 2.2), img[..., 3], path)
    meta["front"], meta["diffuse"] = round(meta["front"] * k, 4), like["diffuse"]
    with open(os.path.join(dest, f"{prefix}.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f)
    print(prefix, "maps remeasured ×", round(k, 4))


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
    elif os.path.isdir(os.path.join(bake, "puff")) and os.path.exists(os.path.join(dest, "spray6.json")):
        remeasure(dest, "spray6", json.load(open(os.path.join(dest, "puff6.json"))))
