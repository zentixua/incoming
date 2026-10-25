#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "scipy>=1.11",
#     "soundfile>=0.12",
# ]
# ///
"""Сборка всех звуков мода Incoming из настоящих записей и синтеза (numpy + scipy + soundfile).

  uv run tools/build_sounds.py           → mod/src/main/resources/assets/incoming/sounds/*.ogg,
                                            assets/incoming/sounds.json, SOUND-CREDITS.md
  uv run tools/build_sounds.py blasts    → пересобрать файлы только этих разделов (остальные файлы не трогаются;
                                            sounds.json и авторы — по всем разделам)
  uv run tools/build_sounds.py --alone after → только эти разделы, без записей остальных (синтез, когда Freesound
                                            недоступен): прочие события sounds.json и авторы остаются как были
  uv run tools/build_sounds.py --relevel  → файлы моторов и стартов (MOTOR) — на MOTOR_LUFS по собранным файлам
                                            (громкость сменилась, а записей Freesound нет)
  uv run tools/build_sounds.py --measure  → только спектры и громкость звуков источника (SOURCES_AT) по собранным
                                            файлам — в Spectra.java
  uv run tools/build_sounds.py --previews drone → без входа в Freesound (облако): вместо оригиналов — превью
                                            (ogg с потерями); оригиналы дома дают те же числа, но чище

Записи — только с лицензией, разрешающей распространение: CC0 (общественное достояние) и CC BY (с указанием
автора: SOUND-CREDITS.md в корне и credits.txt рядом со звуками в jar). Список — SOURCES ниже. Записи берутся
с Freesound: превью (ogg с потерями) — в tools/.sound-cache/, оригиналы без потерь (src(…, orig=True); нужен вход
в Freesound, см. tools/freesound.py) — в tools/.sound-cache/orig/.

Как собран каждый звук:
- петли моторов (шахед, ракета, B-2, свист бомбы, ветер, дождь) — стационарный кусок записи без шва (хвост плавно
  вклеен в начало): клиент крутит их бесконечно и сам ставит громкость, тон и Доплер;
- взрывы (раздел blasts) — из оригиналов, по ракурсам: вблизи, на средней дистанции, вдали и удар низа; клиент сводит
  их по давлению и воздуху у уха, тон — по заряду (client/sound/BlastMix), эхо — от рельефа (client/sound/BlastEchoes). Без мягкого ограничителя: громкость — мгновенная
  громкость EBU R128 (master), пики — прозрачный ограничитель не глубже 6 дБ; удар — с первого отсчёта файла;
- то, чего не записать (бурение бетонобойной бомбы, звон в ушах, захват цели), — синтез из synth_mod_sounds.py.
Звуки моно (Minecraft размещает в пространстве только моно).
У каждого раздела свой генератор случайных чисел (от имени раздела): правка одного раздела не меняет другие.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import zlib
from fractions import Fraction

import numpy as np
import soundfile as sf
from scipy.ndimage import minimum_filter1d, uniform_filter1d
from scipy.signal import lfilter, resample_poly, welch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import freesound  # noqa: E402
import synth_mod_sounds as syn  # noqa: E402

SR = 44100
syn.SR = SR  # синтез — в той же частоте
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "incoming")
OUT = os.path.join(ASSETS, "sounds")
CACHE = os.path.join(ROOT, "tools", ".sound-cache")
rng = np.random.default_rng(7)
PREVIEWS = False  # --previews: оригиналы недоступны — превью вместо них

# ---------------------------------------------------------------- источники

CC0 = "CC0 1.0"
BY4 = "CC BY 4.0"
BY3 = "CC BY 3.0"

# id Freesound → (автор, название, лицензия, превью)
SOURCES = {
    # шахед: двухтактный мотор с винтом на номинале — мотопараплан на подлёте (вспышки ~127 Гц — у MD-550 вал 125 Гц)
    114628: ("dobroide", "20110212_paraglider.02.wav", BY4, "114/114628_8043"),
    # крылатая ракета: реактивный двигатель на месте — ровный вой и ровный рёв (без пролёта: тон не плывёт)
    437910: ("craigsmith", "G09-18-Constant Jet Noise.wav", CC0, "437/437910_2524442"),
    437917: ("craigsmith", "G10-02-Constant Jet Noise.wav", CC0, "437/437917_2524442"),
    # пуск: стартовые ускорители (записи Минобороны США), рёв ракеты
    184274: ("qubodup", "Launching Anti-Tank Missiles.flac [US DoD]", CC0, "184/184274_71257"),
    182794: ("qubodup", "Rocket Launch.flac", CC0, "182/182794_71257"),
    774270: ("TheLittleCrow", "Rocket Launch Boost and Burning (Version B)", CC0, "774/774270_5672786"),
    613855: ("felix.blume", "Rocket Launch Rumble at Canaveral", CC0, "613/613855_1661766"),
    675750: ("craigsmith", "S32-27 Titan missile launch; long.wav", CC0, "675/675750_2524442"),
    # РСЗО: пуск реактивного снаряда (HIMARS, Минобороны США), вой снарядов на подлёте, разрывы
    854476: ("qubodup", "M142 HIMARS Rocket Launch 8 [US DoD]", CC0, "854/854476_71257"),
    241837: ("Zagge28", "Incoming mortar 4", CC0, "241/241837_4404989"),
    241838: ("Zagge28", "Incoming mortar 3", CC0, "241/241838_4404989"),
    241839: ("Zagge28", "Incoming mortar 2", CC0, "241/241839_4404989"),
    241840: ("Zagge28", "Incoming mortar 1", CC0, "241/241840_4404989"),
    674897: ("craigsmith", "S18-01 Incoming shells; explosions.wav", CC0, "674/674897_2524442"),
    # барражирующий боеприпас: электромоторы с винтами (квадрокоптер), катапульта
    854352: ("qubodup", "Quadcopter Drone Flyby", CC0, "854/854352_71257"),
    479922: ("craigsmith", "R01-04-Catapult Launch.wav", CC0, "479/479922_2524442"),
    # B-2 и бомба
    152567: ("minian89", "four_jet_engines.wav", CC0, "152/152567_2467357"),
    162417: ("qubodup", "Jet Plane Wind Noise Loop of a KC-135 Stratotanker 1.flac", CC0, "162/162417_71257"),
    483296: ("craigsmith", "R30-12-Large Gun Shells Fly By and Explode.wav", CC0, "483/483296_2524442"),
    # взрывы (раздел blasts — из оригиналов без потерь)
    251401: ("felix.blume", "Dynamite explosion in the mountain", CC0, "251/251401_1661766"),
    475780: ("felix.blume", "Big dynamite explosion in an open mine (Chile)", CC0, "475/475780_1661766"),
    165808: ("sidohzen", "Explosion with debris - authentic. 4kg TNT", CC0, "165/165808_2872744"),
    811474: ("deleted_user_9900733", "UAV explosion in the city", CC0, "811/811474_9900733"),
    125937: ("alienistcog", "merrimack-st-demolition.aif", CC0, "125/125937_296888"),
    82682: ("juskiddink", "Quarry blasting.wav", BY4, "82/82682_649468"),
    866989: ("tim.kahn", "EXPLReal_Centennial Mills Watertower Demolition", BY4, "866/866989_7037"),
    182431: ("qubodup", "Explosive 1 v2 [DOD 130303].flac", CC0, "182/182431_71257"),
    741174: ("qubodup", "Huge Explosion", CC0, "741/741174_71257"),
    741175: ("qubodup", "Massive Explosion", CC0, "741/741175_71257"),
    486018: ("craigsmith", "R12-02-Large Explosions.wav", CC0, "486/486018_2524442"),
    438693: ("craigsmith", "G43-14-25 Distant Explosions.wav", CC0, "438/438693_2524442"),
    438538: ("craigsmith", "G33-32-Distant Bomb Explosion.wav", CC0, "438/438538_2524442"),
    550342: ("Nox_Sound", "Foley_Stones_Falls_Debris_Stereo_DR05.wav", CC0, "550/550342_9250976"),
    483304: ("craigsmith", "R09-58-Large Fire with Debris.wav", CC0, "483/483304_2524442"),
    675967: ("craigsmith", "S10-19 Falling wooden beam; big interior crash; house collapses; long.wav", CC0, "675/675967_2524442"),
    # сирены: Киев (CC BY) и сирена военного времени KVV (CC0)
    676589: ("Romaner66", "Air raid siren in Kyiv", BY4, "676/676589_8949254"),
    727588: ("KVV_Audio", "ALRMMisc_Air Raid Siren In Time Of War_KVV AUDIO_FREE", CC0, "727/727588_12846320"),
    # ядерный удар: гром, ветер, стекло, дождь, счётчик Гейгера
    267551: ("Yoyodaman234", "Thunder2", CC0, "267/267551_2792951"),
    243782: ("bastipictures", "peal of thunder - distant", CC0, "243/243782_997601"),
    350506: ("Spennnyyy", "Extremely Close THUNDER! (no rain)", BY4, "350/350506_5554674"),
    438880: ("craigsmith", "G56-25-Low Wind.wav", CC0, "438/438880_2524442"),
    438346: ("craigsmith", "G26-27-Huge Storm Sequence.wav", CC0, "438/438346_2524442"),
    758207: ("LukaCafuka", "Glass panel shattering", CC0, "758/758207_16236894"),
    336425: ("221Beimesche", "Glass Shattering and Falling", CC0, "336/336425_4663534"),
    453499: ("kyles", "neon tube smash explode shatter glass debris.flac", CC0, "453/453499_612689"),
    583065: ("Profispiesser", "FX SaSc Glass Tiles Shatter Crash.wav", CC0, "583/583065_6667441"),
    860133: ("KVV_Audio", "RAIN_Heavy Rain Outdoor 01_KVV_FREE", BY4, "860/860133_12846320"),
    674113: ("Sanderboah", "Geiger counter (dry)", CC0, "674/674113_13017680"),
    # горящий город: настоящие пожары домов — дом горит (архив Yle, 1978, плёнка Nagra) и деревянный дом, подожжённый
    # для съёмок (гул с низами, внутри трещит и ломается)
    257371: ("YleArkisto", "Tulipalo, talo palaa / Big fire, a house burning, flames humming and crackling", BY4, "257/257371_4415905"),
    871765: ("skowix", "house on fire", BY4, "871/871765_7081328"),
    # сеть: выход подстанции из строя, дуга, гул трансформатора, район гаснет и загорается
    438494: ("craigsmith", "G32-10-Massive Electrical Discharges.wav", CC0, "438/438494_2524442"),
    434359: ("csengeri", "Close lightning strike 2018 07 07", CC0, "434/434359_197070"),
    367456: ("jensfelger", "Jacob's ladder.wav", CC0, "367/367456_6750383"),
    210878: ("Sclolex", "highvoltagearc.wav", CC0, "210/210878_985466"),
    547900: ("nicotep", "EDF_Electrical_substation", CC0, "547/547900_7529214"),
    451933: ("kyles", "switch big breaker metal click on, off", CC0, "451/451933_612689"),
    573936: ("TRP", "Fridge hum whirl stops loud clunk bang", CC0, "573/573936_97550"),
    139970: ("jessepash", "Switch.Big.Power.wav", CC0, "139/139970_968325"),
    262509: ("OBXJohn", "Gym Lights Powering Up", CC0, "262/262509_3719168"),
}
USED = set()


def preview(fid):
    """Скачать превью записи fid в кэш: у части записей Freesound превью ogg уже нет — тогда mp3 того же качества;
    CDN временами отвечает 403 на частые запросы — тогда ещё раз через 2, 4, 8 с."""
    for kind in ("ogg", "mp3"):
        url = f"https://cdn.freesound.org/previews/{SOURCES[fid][3]}-hq.{kind}"
        path = os.path.join(CACHE, f"{fid}.{kind}")
        print("  скачиваю", url)
        for wait in (2, 4, 8, None):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (incoming build_sounds)"})
                with urllib.request.urlopen(req) as r, open(path + ".part", "wb") as fh:
                    fh.write(r.read())
                os.replace(path + ".part", path)
                return path
            except urllib.error.HTTPError as e:
                if e.code != 403 or wait is None:
                    break
                time.sleep(wait)
    sys.exit(f"превью записи {fid} не скачать")


def src(fid, orig=False, stereo=False):
    """Запись в SR, float64: моно, а stereo — два канала (n×2). orig — оригинал без потерь (tools/freesound.py),
    иначе превью Freesound (ogg); кэш — tools/.sound-cache."""
    USED.add(fid)
    if orig and not PREVIEWS:
        path = freesound.original(fid)
    else:
        os.makedirs(CACHE, exist_ok=True)
        path = os.path.join(CACHE, f"{fid}.ogg")
        if not os.path.exists(path) and os.path.exists(os.path.join(CACHE, f"{fid}.mp3")):
            path = os.path.join(CACHE, f"{fid}.mp3")
        if not os.path.exists(path):
            path = preview(fid)
    x, sr = sf.read(path, always_2d=True)
    x = (np.repeat(x[:, :1], 2, axis=1) if x.shape[1] == 1 else x[:, :2]) if stereo else x.mean(axis=1)
    if sr != SR:
        fr = Fraction(SR, sr).limit_denominator(1000)
        x = resample_poly(x, fr.numerator, fr.denominator, axis=0)
    return x - np.mean(x, axis=0)


# ---------------------------------------------------------------- инструменты

def cut(x, a, b=None):
    return x[int(a * SR): None if b is None else int(b * SR)].copy()


def fade(x, fi=0.004, fo=0.08):
    x = x.copy()
    a, b = int(fi * SR), int(fo * SR)
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= np.linspace(1, 0, b) ** 2
    return x


# Наибольший сдвиг записи по скорости (варипитч), раз: ускоренная втрое запись мотора звучит не другим мотором на
# других оборотах, а той же записью на перемотке — вместе с тоном уходят резонансы корпуса и выхлопа (шахед 3.5.0 —
# «мухи», проба P 21.10.2026: петля 232 Гц против 84 Гц у 3.4.1). Решение Артёма 21.10: записи источника — в своей
# скорости, сдвиг не больше ±25 %; нужен другой тон — другая запись.
SHIFT_MAX = 1.25
# Разделы, где причины нет вовсе (решение Артёма 22.10, N4): у ядерки «больше» не получается замедлением чужой записи —
# Сакс от заряда записи до килотонн дал бы десятки раз, а взятые 0,62 и 0,7 были на глаз. Величину ядерки дают законы
# клиента: скачок давления у уха с положительной фазой модели (BlastPulse), эхо от рельефа, ветер за фронтом, стёкла и
# обломки, что снесла волна (Clatter), гул шторма по его теплу.
NATIVE = {"sirens", "nuke", "after"}
SECTION = None  # раздел, который собирается сейчас


def speed(x, r, why=None):
    """Варипитч, как у плёнки: r > 1 — выше и короче, r < 1 — ниже, длиннее и «больше». Больше SHIFT_MAX — только с
    причиной why: закон (Сакс — время взрыва ∝ ∛W) или отступление «не по физике»; без неё сборка падает, а в разделах
    NATIVE (ядерка) — и с ней."""
    if max(r, 1 / r) > SHIFT_MAX + 1e-9 and (not why or SECTION in NATIVE):
        sys.exit(f"запись ускорена в {r:.2f} раза: больше ±{SHIFT_MAX - 1:.0%} — взять запись с нужным тоном (SHIFT_MAX"
                 + (f", раздел {SECTION} — без причин" if SECTION in NATIVE else "") + ")")
    fr = Fraction(r).limit_denominator(200)
    return resample_poly(x, fr.denominator, fr.numerator)


def F(x, lo=None, hi=None, bells=()):
    return syn.F(x, lo, hi, bells)


def rms(x):
    return np.sqrt(np.mean(x ** 2))


def norm(x, rms_db=-12, peak=0.9):
    return syn.norm(x, rms_db, peak)


def pad(x, n):
    return np.pad(x, (0, max(0, n - len(x))))[:n]


def mix(*parts):
    """Смесь (сигнал, громкость в долях RMS), выровненная по длине самого длинного."""
    n = max(len(p) for p, _ in parts)
    out = np.zeros(n)
    for p, g in parts:
        out[:len(p)] += p / max(rms(p), 1e-9) * g
    return out


def env_db(x, hop=0.01):
    h = int(SR * hop)
    e = np.sqrt(np.convolve(x ** 2, np.ones(2 * h) / (2 * h), "same")[::h])
    return 20 * np.log10(e / np.max(e) + 1e-12)


def trim_tail(x, floor_db=-48, fo=0.3):
    """Обрезать хвост, где запись ушла в шум, с плавным затуханием."""
    db = env_db(x)
    loud = np.nonzero(db > floor_db)[0]
    end = min(len(x), int(((loud[-1] if len(loud) else len(db)) + 1) * 0.01 * SR) + int(fo * SR))
    return fade(x[:end], 0.002, fo)


def gate_hiss(x, lo_cut=None):
    """Убрать постоянный фон записи (шум улицы/ветра) там, где идёт хвост, — мягкий экспандер."""
    db = env_db(x)
    g = np.clip((db + 55) / 15, 0.08, 1)
    g = np.repeat(g, int(0.01 * SR))[:len(x)]
    g = np.convolve(g, np.ones(int(0.05 * SR)) / int(0.05 * SR), "same")
    return pad(x * np.pad(g, (0, max(0, len(x) - len(g))), constant_values=g[-1]), len(x))


def punch(x, db=4, t=0.02):
    """Подчеркнуть атаку: первые t секунд громче (транзиент съедается у превью)."""
    n = int(0.25 * SR)
    g = np.ones(len(x))
    tt = np.arange(min(n, len(x))) / SR
    g[:len(tt)] = 1 + (10 ** (db / 20) - 1) * np.exp(-tt / t)
    return x * g


def loop(x, seconds, xf=0.6):
    """Бесшовная петля: хвост плавно (равная мощность) вклеен в начало."""
    return syn.loop(x, seconds, xf)


def join(a, b, xf=1.0):
    """Склейка двух кусков с плавным переходом (равная мощность)."""
    k = int(xf * SR)
    w = np.linspace(0, np.pi / 2, k)
    return np.concatenate([a[:-k], a[-k:] * np.cos(w) + b[:k] * np.sin(w), b[k:]])


def align(x, db=-20, pre=0.03):
    """Сдвинуть начало к удару: первый момент громче (пик − 20 дБ) — в pre секундах от начала."""
    e = env_db(x, 0.002)
    k = max(0, int((np.argmax(e > db) * 0.002 - pre) * SR))
    return fade(x[k:], 0.002, 0.0)


def sub_thump(dur, f0=48, sweep=10, tau=0.45):
    """Инфранизкий «удар в грудь» — превью записей режут низ ниже ~40 Гц."""
    t = np.arange(int(dur * SR)) / SR
    return np.sin(2 * np.pi * (f0 * t - sweep * t * t)) * np.exp(-t / tau) * np.minimum(1, t / 0.003)


# ---------------------------------------------------------------- громкость без искажений (взрывы)

def k_weight(x):
    """К-взвешивание ITU-R BS.1770: полка +4 дБ выше ~1,7 кГц и срез ниже ~38 Гц — как слух оценивает громкость."""
    G, Q, fc = 3.99984385397, 0.7071752369554193, 1681.9744509555319
    K, Vh = np.tan(np.pi * fc / SR), 10 ** (G / 20)
    Vb = Vh ** 0.499666774155
    a0 = 1 + K / Q + K * K
    y = lfilter([(Vh + Vb * K / Q + K * K) / a0, 2 * (K * K - Vh) / a0, (Vh - Vb * K / Q + K * K) / a0],
                [1, 2 * (K * K - 1) / a0, (1 - K / Q + K * K) / a0], x, axis=0)
    Q, fc = 0.5003270373253953, 38.13547087613982
    K = np.tan(np.pi * fc / SR)
    a0 = 1 + K / Q + K * K
    return lfilter([1, -2, 1], [1, 2 * (K * K - 1) / a0, (1 - K / Q + K * K) / a0], y, axis=0)


def loudness(x):
    """Наибольшая мгновенная громкость (окно 400 мс, шаг 100 мс; EBU R128), LUFS; стерео — сумма каналов."""
    y = k_weight(x) ** 2
    y = y if y.ndim == 1 else y.sum(axis=1)
    w, h = int(0.4 * SR), int(0.1 * SR)
    c = np.concatenate([[0], np.cumsum(y)])
    ms = [(c[i + w] - c[i]) / w for i in range(0, max(1, len(y) - w), h)]
    return -0.691 + 10 * np.log10(max(ms) + 1e-15)


def low_level(x):
    """Громкость низа (удар земли): наибольший средний квадрат за 400 мс без К-взвешивания, дБ — К-фильтр срезает
    как раз то, что слышно грудью."""
    y = x ** 2 if x.ndim == 1 else (x ** 2).sum(axis=1)
    w = int(0.4 * SR)
    c = np.concatenate([[0], np.cumsum(y)])
    return 10 * np.log10(max((c[i + w] - c[i]) / w for i in range(0, max(1, len(y) - w), int(0.1 * SR))) + 1e-15)


def limit(x, thr, attack=0.002, release=0.08):
    """Ограничитель пиков с упреждением: усиление падает плавно за attack до пика и возвращается за release —
    без искажений формы, в отличие от мягкого насыщения (tanh)."""
    a = np.abs(x) if x.ndim == 1 else np.max(np.abs(x), axis=1)
    r = np.minimum(1, thr / np.maximum(a, 1e-12))
    w = 2 * int(attack * SR) + 1
    s = uniform_filter1d(minimum_filter1d(r, w), w)  # у пика не больше нужного: окно среднего — внутри окна минимума
    k = 1 - np.exp(-1 / (release * SR))
    g = np.empty_like(s)
    cur = 1.0
    for i, v in enumerate(s):
        cur = v if v < cur else cur + (v - cur) * k
        g[i] = cur
    return x * (g if x.ndim == 1 else g[:, None])


def master(x, lufs, peak_db=-1.0, max_gr=6.0, meter=None):
    """Громкость взрыва без искажений: мгновенная громкость — lufs, пики выше peak_db — прозрачный ограничитель,
    но не глубже max_gr дБ (не хватает — тише целиком). Ограничитель сам немного убавляет громкость — усиление
    подбирается за несколько проходов."""
    meter = meter or loudness
    thr = 10 ** (peak_db / 20)
    top = thr * 10 ** (max_gr / 20) / np.max(np.abs(x))
    g = min(top, 10 ** ((lufs - meter(x)) / 20))
    for _ in range(4):
        y = limit(x * g, thr * 0.995)
        g = min(top, g * 10 ** ((lufs - meter(y)) / 20))
    return limit(x * g, thr * 0.995)


def onset(x, db=-26, pre=0.0005):
    """Начало файла — удар: первый отсчёт громче пика − 26 дБ, за pre секунд до него (клиент играет взрыв в тик
    прихода фронта; тишина в начале файла — пауза между подлётом и взрывом)."""
    m = np.abs(x if x.ndim == 1 else x.mean(axis=1))
    k = max(0, int(np.argmax(m > m.max() * 10 ** (db / 20))) - int(pre * SR))
    y = x[k:].copy()
    n = int(pre * SR)
    if n:
        ramp = np.linspace(0, 1, n)
        y[:n] *= ramp if y.ndim == 1 else ramp[:, None]
    return y


def chans(fn, x, *a, **kw):
    """Обработка моно-функцией каждого канала стерео."""
    return fn(x, *a, **kw) if x.ndim == 1 else np.stack([fn(x[:, c], *a, **kw) for c in range(x.shape[1])], axis=1)


# Поглощение звука воздухом, дБ/км, ISO 9613-1 (20 °C, влажность 70 %), октавы 63 Гц … 8 кГц
AIR_F = [63, 125, 250, 500, 1000, 2000, 4000, 8000]
AIR_DB_KM = [0.1, 0.3, 1.1, 2.8, 5.0, 9.0, 22.9, 76.6]


def air(x, metres):
    """Воздух на пути в metres метров: верха гаснут по ISO 9613-1 (дальняя перспектива из ближней записи)."""
    X = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    db = np.interp(np.log2(np.maximum(f, 1)), np.log2(AIR_F), AIR_DB_KM) * metres / 1000
    H = 10 ** (-db / 20)
    return np.fft.irfft(X * (H if x.ndim == 1 else H[:, None]), len(x), axis=0)


def friedlander(dur, positive, b=1.2):
    """Давление ударной волны в точке (форма Фридлендера): мгновенный скачок, спад через ноль за positive секунд
    и разрежение — то, что делает удар близкого взрыва; записи издалека его теряют."""
    t = np.arange(int(dur * SR)) / SR
    return (1 - t / positive) * np.exp(-b * t / positive)


# ---------------------------------------------------------------- петли моторов

# Мерка громкости в игре, LUFS (наибольшая мгновенная): так звучит звук моторов с уровнем Loudness.REF у уха
# (client/sound/Loudness: мощность источника из паспорта звука и путь, половина децибел уровня).
ENGINE_LUFS = -19.0
# Громкость всех петель моторов и разовых звуков старта, LUFS (наибольшая мгновенная, как у взрывов): одна на все —
# потолок громкости в игре, ближний ракурс взрыва (NEAR_LUFS, 1 psi; Loudness.CEILING_LUFS). Громкость в игре задаёт
# уровень у уха, файл — только как звук звучит; клиент ведёт громкость от мерки ENGINE_LUFS вверх до потолка, и файл
# на потолке не обрезает её раньше: в 3.5.0 файлы были на ENGINE_LUFS, и всё громче Loudness.REF (≈126 дБА — удар
# поджига ракеты ближе ~45 м к пусковой, рёв ускорителя ближе ~20 м) звучало одинаково.
MOTOR_LUFS = -13.5
# Сход снаряда РСЗО — короткий удар с шипением: на потолок его не свести без пережатия (ограничитель не глубже 6 дБ),
# на 2 дБ ниже — можно; он упирается в файл с ~133 дБА у уха (ближе ~25 м к пусковой «Града»).
ROCKET_LAUNCH_LUFS = MOTOR_LUFS - 2
# События, собранные на MOTOR_LUFS (SoundAssetsTest меряет их файлы; --relevel пересводит собранные файлы). Хлопок
# отделения ускорителя и катапульта — на ENGINE_LUFS: удар не свести на потолок без пережатия (ограничитель не глубже
# 6 дБ), а громче Loudness.REF они не бывают и в метре (~125 и ~120 дБА мощности — 117 и 112 дБА; LoudnessTest).
MOTOR = {"drone.engine", "drone.engine.far", "missile.engine", "missile.engine.rear", "missile.dive", "missile.engine.far",
         "missile.whistle", "launch.booster", "booster.engine", "bomber.engine", "bomber.engine.far", "bomb.fall",
         "bomb.fall.far", "bomb.drill", "rocket.launch", "rocket.incoming", "loiter.engine", "loiter.engine.far", "loiter.dive"}
# Дальние петли — ближние, прошедшие столько метров воздуха (client/sound/EngineSound.FAR_AIR): вдали ближняя запись
# с фильтром EFX уже не похожа на дальний звук — EFX глушит верха одной полкой, а воздух — по октавам.
FAR_M = 500
# Мгновенная громкость ровной петли ходит не больше чем на столько, дБ (СКО по окнам 400 мс): у ровных петель
# 0,2–0,7, у склеек из пролётов — 2–2,5
STEADY_DB = 1.0


def momentary(x):
    """Мгновенная громкость петли по кругу (окна 400 мс через 100 мс, шов петли тоже), LUFS."""
    w, h = int(0.4 * SR), int(0.1 * SR)
    y = k_weight(np.concatenate([x[-SR:], x, x[:w]]))[SR:] ** 2  # фильтр разогнан хвостом петли: шов без скачка
    c = np.concatenate([[0], np.cumsum(y)])
    return -0.691 + 10 * np.log10(np.array([(c[i + w] - c[i]) / w for i in range(0, len(x), h)]) + 1e-15)


def engine(x, lufs, name, steady=True):
    """Петля мотора: громкость lufs (master — без мягкого ограничителя) и проверка ровности. Колебание громкости
    в петле вдали слышно как дрожь и «шорох» (склейка из коротких кусков, волна полевой записи) — сборка падает.
    steady=False — петля из пролёта, которую ещё не заменили ровной записью (барражирующий)."""
    y = master(x, lufs, meter=lambda s: np.max(momentary(s)))
    sd = np.std(momentary(y))
    if steady and sd > STEADY_DB:
        fail(f"{name}: мгновенная громкость ходит на {sd:.1f} дБ (СКО), ровная петля — не больше {STEADY_DB}")
    return y


def noise_loop(sigs, seconds):
    """Ровная петля со звучанием записей, в которых звук сам не ровный (пролёт снаряда: тон плывёт, громкость
    растёт): шум с их средним спектром (Уэлч) и случайными фазами — обратное БПФ длиной петли периодично, шва нет."""
    f, psd = None, 0
    for s in sigs:
        f, p = welch(s / max(rms(s), 1e-9), SR, nperseg=4096)
        psd = psd + p / len(sigs)
    n = int(seconds * SR)
    mag = np.sqrt(np.interp(np.fft.rfftfreq(n, 1 / SR), f, psd))
    mag[0] = 0
    return np.fft.irfft(mag * np.exp(2j * np.pi * rng.random(len(mag))), n)


# ---------------------------------------------------------------- запись

EVENTS = {}  # имя события → (субтитр, [файлы])
PRELOAD = set()  # события, которые игра декодирует при загрузке ресурсов, а не при первом звуке
WRITING = True  # пишутся ли файлы текущего раздела (python3 build_sounds.py <разделы> — только этих)


def fail(msg):
    """Проверка записи не прошла — сборка падает. У раздела, чьи файлы не пишутся (собираются другие разделы), —
    только строка: он идёт ради событий и авторов, файлы его остаются прежние, а записи могут быть превью (--previews)."""
    if WRITING:
        sys.exit(msg)
    print("  не пишется, проверка не прошла:", msg)


def write(name, x, event=None, subtitle=None, level=0.35):
    """Файл sounds/<name>.ogg (моно — вектор, стерео — n×2); event — событие sounds.json, в которое он входит
    вариантом; level — сжатие Vorbis libsndfile (0 — лучшее качество, 0,35 ≈ q6,5)."""
    x = np.clip(x, -1, 1).astype(np.float32)
    if event:
        sub, files = EVENTS.setdefault(event, (subtitle, []))
        files.append(name)
    if not WRITING:
        return
    if event in SPECTRA or event in SOURCES_AT:
        SPECTRA_GOT.setdefault(event, []).append(octaves(x))
    if event in SOURCES_AT:
        LUFS_GOT.setdefault(event, []).append(loudness(x))
    os.makedirs(OUT, exist_ok=True)
    channels = 1 if x.ndim == 1 else x.shape[1]
    # кусками: libsndfile падает, если отдать Vorbis длинный файл одним блоком
    with sf.SoundFile(os.path.join(OUT, name + ".ogg"), "w", SR, channels, format="OGG", subtype="VORBIS",
                      compression_level=level) as fh:
        for i in range(0, len(x), 8192):
            fh.write(x[i:i + 8192])
    print(f"  {name:26} {len(x) / SR:5.1f} с  RMS {20 * np.log10(rms(x) + 1e-9):6.1f} дБ  {channels} кан.")


# События, чьи октавные спектры нужны паспорту звука (ua.zentix.incoming.sound.Spectra): по ним сервер решает,
# докуда слышно снаряд, а клиент — громкость; собранная заново запись переписывает свой спектр там сама.
SPECTRA = {"drone.engine", "loiter.engine", "loiter.dive", "missile.engine", "missile.engine.rear", "bomber.engine",
           "bomb.fall", "booster.engine", "rocket.incoming", "bomb.drill", "launch.booster", "rocket.launch",
           "loiter.launch", "booster.separate"}
SPECTRA_GOT = {}
# Разовые звуки и петли мира с громкостью по закону источника (client/sound/Sources: мощность звука из паспорта,
# путь ISO 9613, client/sound/Loudness), а не ручной дальностью: им в Spectra.java — спектр и громкость самого файла
# (наибольшая мгновенная, LUFS), по которой клиент приводит файл к ENGINE_LUFS — одной мерке всех звуков источника.
# Пересобирать файлы ради этого не нужно: --measure меряет уже собранные.
SOURCES_AT = {"siren", "nuke.alarm", "nuke.launch", "grid.fail", "grid.spark", "grid.hum", "grid.power_down",
              "grid.power_up", "nuke.collapse", "debris.fall", "blast.fire", "nuke.glass", "nuke.fire"}
LUFS_GOT = {}
SPECTRA_JAVA = os.path.join(ROOT, "mod", "src", "main", "java", "ua", "zentix", "incoming", "sound", "Spectra.java")
OCTAVES = [63, 125, 250, 500, 1000, 2000, 4000, 8000]


def octaves(x):
    """Мощность записи по октавам 63 Гц – 8 кГц (Уэлч), доли."""
    f, p = welch(x, SR, nperseg=8192)
    return np.array([p[(f >= c / np.sqrt(2)) & (f < c * np.sqrt(2))].sum() for c in OCTAVES])


def write_spectra():
    """Спектры собранных событий — в Spectra.java (дБ, относительно самой сильной октавы; варианты — среднее по
    мощности); прочие строки файла остаются как были."""
    if not SPECTRA_GOT:
        return
    with open(SPECTRA_JAVA, encoding="utf-8") as fh:
        java = fh.read()
    for ev, got in sorted(SPECTRA_GOT.items()):
        e = np.mean([g / g.sum() for g in got], axis=0)
        db = 10 * np.log10(e / e.max() + 1e-12)
        const = ev.replace(".", "_").upper()
        line = f"    public static final Spectrum {const} = Spectrum.of({', '.join(f'{v:.1f}' for v in db)});"
        head = f"    public static final Spectrum {const} = "
        lines = java.split("\n")
        hit = [i for i, l in enumerate(lines) if l.startswith(head)]
        if hit:
            lines[hit[0]] = line
        else:
            lines.insert(next(i for i, l in enumerate(lines) if "// @spectra-end" in l), line)
        java = "\n".join(lines)
        print(f"  спектр {ev}: {' '.join(f'{v:.0f}' for v in db)}")
        if ev in LUFS_GOT:
            # варианты — среднее по мощности: клиент берёт вариант по зерну, разница вариантов — их собственная
            lufs = 10 * np.log10(np.mean([10 ** (v / 10) for v in LUFS_GOT[ev]]))
            line = f"    public static final double {const}_LUFS = {lufs:.1f};"
            head = f"    public static final double {const}_LUFS = "
            lines = java.split("\n")
            hit = [i for i, l in enumerate(lines) if l.startswith(head)]
            if hit:
                lines[hit[0]] = line
            else:
                lines.insert(next(i for i, l in enumerate(lines) if "// @spectra-end" in l), line)
            java = "\n".join(lines)
            print(f"  громкость {ev}: {lufs:.1f} LUFS")
    with open(SPECTRA_JAVA, "w", encoding="utf-8") as fh:
        fh.write(java)


def variants(event, subtitle, name, sigs, **kw):
    for i, s in enumerate(sigs, 1):
        write(f"{name}_{i}" if len(sigs) > 1 else name, s, event, subtitle, **kw)


# ================================================================ шахед

# Шахед: MD-550 — копия Limbach L550E, двухтактный четырёхцилиндровый, 7500 об/мин, двухлопастной винт прямо на валу
# (паспорт звука WeaponSpec.DRONE_NOISE): вал — n/60 = 125 Гц, лопасти — 250 Гц, вспышки (каждый цилиндр раз за
# оборот) — 500 Гц (ref/formulas.md §6). Петля звучит на этих оборотах; клиент ведёт тон по оборотам винта (поступь
# винта постоянна: обороты ∝ воздушной скорости).
DRONE_RPM = 7500


def peak(x, lo, hi):
    """Частота самого сильного тона записи между lo и hi, Гц (Уэлч, окно ~0,74 с)."""
    f, p = welch(x, SR, nperseg=1 << 15)
    m = (f >= lo) & (f <= hi)
    return f[m][np.argmax(p[m])]


def tone_at(x, f0, name, lo=40, hi=1200, tol=0.03):
    """Проверка: самый сильный тон петли — f0 (±tol)."""
    got = peak(x, lo, hi)
    if abs(got / f0 - 1) > tol:
        fail(f"{name}: самый сильный тон {got:.0f} Гц, а у настоящего двигателя {f0:.0f} Гц")
    print(f"  {name}: самый сильный тон {got:.0f} Гц (у двигателя {f0:.0f} Гц)")


def steadiest(x, seconds, step=0.25):
    """Самый ровный кусок записи длиной seconds: меньше всех ходит мгновенная громкость (рулёжка, газ)."""
    w = int(seconds * SR)
    best, at = None, 0
    for a in range(0, max(1, len(x) - w), int(step * SR)):
        sd = np.std(momentary(x[a:a + w]))
        if best is None or sd < best:
            best, at = sd, a
    return x[at:at + w]


def drone():
    """Двухтактный мотор с винтом («мопед»): мотопараплан на подлёте, в своей скорости. Его мотор — одноцилиндровый
    двухтакт ~7600 об/мин (вспышки 127 Гц, винт через редуктор 1:3 — 42,7 Гц), то есть вспышки — на частоте вала
    MD-550 (125 Гц), и сдвиг — меньше 3 % (SHIFT_MAX). Тоны ниже вала срезаны: у шахеда винт прямо на валу, тонов
    редуктора нет. Чего нет: у MD-550 четыре цилиндра — вспышки 500 Гц сильнее, чем у одноцилиндрового (⚠ записи
    четырёхцилиндрового двухтакта с винтом под CC0/CC BY не нашлось). Дальний — он же через FAR_M воздуха."""
    shaft = DRONE_RPM / 60
    x = cut(src(114628, orig=True), 15, 31)  # подлёт: тон ровный (127 ± 1,5 Гц), газ не меняется; дальше — пролёт
    x = steadiest(speed(x, shaft / peak(x, 90, 200)), 4.7)
    body = F(x, lo=100, hi=12000)
    near = loop(body, 4.0)
    tone_at(near, shaft, "drone_engine")
    write("drone_engine", engine(near, MOTOR_LUFS, "drone_engine"), "drone.engine", "subtitles.incoming.drone")
    write("drone_engine_far", engine(loop(air(body, FAR_M), 4.0), MOTOR_LUFS, "drone_engine_far"), "drone.engine.far",
          "subtitles.incoming.drone")


# ================================================================ крылатая ракета

def missile():
    """Турбовентиляторный двигатель (у Х-101 — ТРДД-50): спереди — вой вентилятора и компрессора, сзади — рёв струи;
    в пике — выше тоном и с шумом обтекания; вдали — тот же двигатель через полкилометра воздуха: ровный глухой гул;
    на подлёте — вой, который слышно издалека. Всё — из ровных записей реактивного двигателя на месте (G09-18 — вой,
    G10-02 — рёв): в записи пролёта тон плывёт от Доплера, и петля из неё каждые несколько секунд прыгает тоном
    обратно; Доплер клиент считает сам."""
    whine = cut(src(437910, orig=True), 10, 100)
    roar = cut(src(437917, orig=True), 10, 100)
    front = mix((F(whine, lo=900, hi=12000), 1.0), (F(roar, lo=60, hi=900), 0.45))
    write("missile_engine", engine(loop(front, 12.0), MOTOR_LUFS, "missile_engine"), "missile.engine", "subtitles.incoming.missile")
    rear = mix((F(roar, lo=35, hi=2500, bells=[(160, 5, 1.0)]), 1.0), (F(whine, lo=2500, hi=9000), 0.2))
    write("missile_engine_rear", engine(loop(rear, 12.0), MOTOR_LUFS, "missile_engine_rear"), "missile.engine.rear",
          "subtitles.incoming.missile")
    dive = speed(mix((whine, 1.0), (roar, 0.6)), 1.12)
    wind = cut(src(162417, orig=True), 0, 6.2)
    dive = mix((F(dive, lo=700, hi=14000), 1.0), (F(dive, lo=50, hi=700), 0.4), (pad(np.tile(wind, 14), len(dive)), 0.35))
    write("missile_dive", engine(loop(dive, 12.0), MOTOR_LUFS, "missile_dive"), "missile.dive", "subtitles.incoming.missile")
    far = air(mix((F(roar, lo=35, hi=6000), 1.0), (F(whine, lo=400, hi=8000), 0.3)), FAR_M)
    write("missile_engine_far", engine(loop(far, 12.0), MOTOR_LUFS, "missile_engine_far"), "missile.engine.far",
          "subtitles.incoming.missile")
    # вдали впереди: вой вентилятора, который уносит вперёд, — через FAR_M воздуха; тон ведёт клиент (Доплер, обороты)
    whistle = air(F(whine, lo=500, hi=12000), FAR_M)
    write("missile_whistle", engine(loop(whistle, 12.0), MOTOR_LUFS, "missile_whistle"), "missile.whistle",
          "subtitles.incoming.missile.whistle")


# ================================================================ пуск

def launch():
    """Старт с направляющей: удар воспламенения, рёв стартового ускорителя, уход вдаль (записи Минобороны США,
    пуски ракет). Петля ускорителя — ровный рёв с треском, пока он горит (клиент ведёт её с Доплером)."""
    at = src(184274)
    rl = src(182794)
    sigs = []
    for x, a, b in [(at, 0.0, 9.9), (at, 9.95, 19.9), (rl, 0.5, 7.0), (rl, 19.1, 26.0)]:
        s = cut(x, a, b)
        s = mix((s, 1.0), (pad(sub_thump(1.2, 42, 8, 0.3), len(s)), 0.35))
        sigs.append(master(punch(trim_tail(s, -50, 0.6), 3), MOTOR_LUFS))
    variants("launch.booster", "subtitles.incoming.launch", "launch_booster", level(sigs, MOTOR_LUFS))
    burn = cut(src(774270), 1, 23)
    crackle = F(cut(src(613855), 20, 42), lo=60, hi=12000)
    boost = mix((F(burn, lo=40, hi=14000), 1.0), (pad(crackle, len(burn)), 0.45))
    write("booster_engine", engine(loop(boost, 9.0), MOTOR_LUFS, "booster_engine"), "booster.engine", "subtitles.incoming.launch")

    # отделение ускорителя: хлопок пиропатрона и металлический лязг замков
    sep = []
    for r, f0 in [(1.7, 620), (1.9, 740)]:
        pop = speed(cut(src(182431), 0, 1.4), r, "⚠ не по физике: хлопок пиропатрона из записи взрыва выше тоном на глаз "
                                                   "— заменить записью пиропатрона (S8)")
        n = int(1.2 * SR)
        tt = np.arange(n) / SR
        clang = sum(a * np.sin(2 * np.pi * f0 * k * tt) * np.exp(-tt / (0.25 / k ** 0.5))
                    for k, a in [(1, 1), (1.51, 0.7), (2.23, 0.5), (3.07, 0.35), (4.2, 0.2)])
        clang *= np.minimum(1, tt / 0.001)
        sep.append(master(fade(mix((pad(pop, n), 1.0), (clang, 0.5)), 0.001, 0.3), ENGINE_LUFS))
    variants("booster.separate", "subtitles.incoming.launch.separate", "booster_separate", level(sep, ENGINE_LUFS))


# ================================================================ РСЗО «Град»

def rocket():
    """Реактивная система залпового огня: каждый снаряд сходит с трубы резким «фш-ш» с треском (пуск HIMARS,
    выше тоном и короче — калибр 122 мм меньше), очередь по полсекунды складывается в сплошной рёв; на подлёте
    снаряды воют (миномётные мины и снаряды над головой). Разрывы — в разделе blasts."""
    hm = src(854476)
    decay = cut(hm, 4.1, 5.85)
    sigs = []
    for a, r in [(0.0, 1.32), (0.9, 1.45), (1.8, 1.38), (2.6, 1.52), (3.1, 1.28)]:
        body = join(cut(hm, a, a + 1.6), decay, 0.7)
        n = len(body)
        tt = np.arange(n) / SR
        # хлопок воспламенения: превью съедает атаку, возвращаем короткий треск и толчок
        crack = F(rng.standard_normal(n), lo=900, hi=12000) * np.exp(-tt / 0.018)
        x = mix((body, 1.0), (crack, 0.5), (sub_thump(n / SR, 60, 20, 0.12), 0.3))
        sigs.append(master(fade(speed(x, r, "⚠ не по физике: пуск HIMARS (227 мм) выше тоном под «Град» (122 мм) на "
                                       "глаз — заменить записью пуска без сдвига (S8)"), 0.001, 0.4), ROCKET_LAUNCH_LUFS))
    variants("rocket.launch", "subtitles.incoming.rocket", "rocket_launch", level(sigs, ROCKET_LAUNCH_LUFS))

    # вой на подлёте: ровный шум со спектром снарядов над головой (середины записей, без начала и разрыва) — в самих
    # записях тон плывёт от Доплера, а склейка их кусков дрожит; тон ведёт клиент
    grains = [cut(src(g, orig=True), a, b) for g, a, b in
              [(241840, 0.25, 1.2), (241838, 0.35, 1.5), (241837, 0.25, 1.1), (241839, 0.25, 1.15), (674897, 13.9, 15.3)]]
    tex = noise_loop([F(g, lo=250, hi=11000) for g in grains], 10.0)
    write("rocket_incoming", engine(tex, MOTOR_LUFS, "rocket_incoming"), "rocket.incoming", "subtitles.incoming.rocket.incoming")


# ================================================================ барражирующий боеприпас

# «Ланцет»: электромотор с толкающим двухлопастным винтом, ~9000 об/мин (паспорт звука WeaponSpec.LOITER_NOISE):
# лопасти — 300 Гц; мотор-«колокол» о 14 полюсах (7 пар) — тон обмоток n/60 × 7 = 1050 Гц (оценка: полюсов у
# моторов такого класса 12–14).
LOITER_RPM, LOITER_BLADES, LOITER_POLE_PAIRS = 9000, 2, 7


def loiter():
    """«Ланцет»: электромотор с винтом. Записи ровного электросамолёта с винтом нет, поэтому петля — как у неровных
    записей (noise_loop): широкий шум винтов и моторов квадрокоптера (его средний спектр, без тона пролёта) и тона
    по оборотам — гармоники лопастей (у винта малого БПЛА сильнее всего первая, дальше по 1/k) и тон обмоток мотора.
    Пике — рассекаемый воздух (ветер на KC-135); пуск с катапульты — удар и свист без огня."""
    quad = cut(src(854352, orig=True), 0.3, 4.4)
    broad = noise_loop([F(quad, lo=150, hi=12000)], 3.6)
    n = len(broad)
    tt = np.arange(n) / SR
    rev = LOITER_RPM / 60
    # целое число оборотов в петле: тона не прыгают на шве
    rev = round(rev * n / SR) * SR / n
    tones = sum(np.sin(2 * np.pi * LOITER_BLADES * rev * k * tt + rng.uniform(0, 2 * np.pi)) / k for k in range(1, 9))
    tones = tones + 0.15 * np.sin(2 * np.pi * LOITER_POLE_PAIRS * rev * tt) * rms(tones) / np.sqrt(0.5)
    body = mix((broad, 1.0), (tones, 1.0))
    tone_at(body, LOITER_BLADES * LOITER_RPM / 60, "loiter_engine")
    write("loiter_engine", engine(body, MOTOR_LUFS, "loiter_engine"), "loiter.engine", "subtitles.incoming.loiter")
    write("loiter_engine_far", engine(air(np.tile(body, 3), FAR_M)[n:2 * n], MOTOR_LUFS, "loiter_engine_far"), "loiter.engine.far",
          "subtitles.incoming.loiter")
    wind = cut(src(162417, orig=True), 0, 6.2)
    dive = F(loop(np.tile(wind, 2), 6.0, 0.6), lo=150, hi=14000)
    write("loiter_dive", engine(dive, MOTOR_LUFS, "loiter_dive"), "loiter.dive", "subtitles.incoming.loiter")
    # катапульта: удар поршня, свист направляющей
    cat = src(479922)
    sigs = []
    for a in (1.25, 5.07, 13.53):
        x = align(cut(cat, a - 0.05, a + 1.6), -20, 0.01)
        x = mix((x, 1.0), (pad(sub_thump(0.5, 70, 25, 0.08), len(x)), 0.4))
        sigs.append(master(punch(trim_tail(x, -46, 0.3), 4), ENGINE_LUFS))
    variants("loiter.launch", "subtitles.incoming.loiter.launch", "loiter_launch", level(sigs, ENGINE_LUFS))


# ================================================================ B-2 и бомба

def bomber():
    """Четыре турбовентиляторных двигателя B-2 (запись четырёх реактивных), вдали — они же через FAR_M воздуха."""
    fj = cut(src(152567), 15, 85)
    near = mix((F(fj, lo=30, hi=9000), 1.0), (syn.jet(len(fj) / SR)[:len(fj)], 0.15))
    write("bomber_engine", engine(loop(near, 10.0, 1.0), MOTOR_LUFS, "bomber_engine"), "bomber.engine", "subtitles.incoming.bomber")
    write("bomber_engine_far", engine(loop(air(near, FAR_M), 10.0, 1.0), MOTOR_LUFS, "bomber_engine_far"), "bomber.engine.far",
          "subtitles.incoming.bomber")
    # падающая бомба: рвущийся воздух (обтекание) + вой снарядов над головой
    wind = np.tile(cut(src(162417), 0, 6.2), 3)
    sh = cut(src(483296), 1.6, 18.3)
    sh = pad(np.tile(sh, 2), len(wind))
    fall = mix((F(wind, lo=200, hi=12000, bells=[(1100, 6, 0.4)]), 1.0), (F(sh, lo=400, hi=9000), 0.7),
               (syn.bomb_fall(len(wind) / SR)[:len(wind)], 0.35))
    # склейка ветра и пролёта снарядов ходит громкостью на ~1,2 дБ (ровной записи падающей бомбы нет)
    write("bomb_fall", engine(loop(fall, 8.0), MOTOR_LUFS, "bomb_fall", False), "bomb.fall", "subtitles.incoming.bomb.fall")
    write("bomb_fall_far", engine(loop(air(fall, FAR_M), 8.0), MOTOR_LUFS, "bomb_fall_far", False), "bomb.fall.far",
          "subtitles.incoming.bomb.fall")
    # бурение бетона: синтез скрежета + настоящий треск ломающихся перекрытий, глухо (бомба в грунте)
    crash = F(cut(src(675967), 10, 30), lo=40, hi=1600)
    dr = syn.drill(20.0)
    write("bomb_drill", engine(loop(mix((dr, 1.0), (pad(crash, len(dr)), 0.55)), 7.0), MOTOR_LUFS, "bomb_drill", False),
          "bomb.drill", "subtitles.incoming.bomb.drill")


# ================================================================ взрывы

# Громкость ракурсов взрыва, LUFS (наибольшая мгновенная, EBU R128; у удара низа — без взвешивания, дБ): каждый вариант
# сведён ровно на неё (level), клиент сводит ракурсы по расстоянию с этими же числами (client/sound/BlastMix).
NEAR_LUFS, MID_LUFS, FAR_LUFS, SUB_DB = -13.5, -13.5, -15.0, -10.0

# Записи взрывов: (id, начало, конец, канал) — из каждой три ракурса одного и того же взрыва: вблизи, на средней
# дистанции и вдали. Ракурсы одного варианта клиент выбирает одним зерном (одинаковое число вариантов), поэтому
# там, где ракурсы звучат вместе (переход по расстоянию), это один взрыв, а не два вразнобой. Канал 1 — только правый
# (у 475780 левый перегружен на ударе).
BLASTS = [(251401, 0.0, 9.5, None), (165808, 2.1, 4.6, None), (811474, 5.1, 15.0, None), (125937, 10.0, 18.0, None),
          (866989, 181.0, 189.0, None), (475780, 0.1, 14.0, 1)]


def level(sigs, lufs, meter=None):
    """Все варианты ракурса одной громкости: клиент сводит ракурсы по расстоянию (BlastMix), считая каждый ровным.
    Вариант, который не дотянул (ограничитель не глубже 6 дБ), — ошибка сборки: взять другую запись или убавить ракурс."""
    for i, x in enumerate(sigs, 1):
        got = (meter or loudness)(x)
        if abs(got - lufs) > 0.3:
            fail(f"вариант {i}: {got:.1f} LUFS, а у ракурса {lufs} LUFS")
    return sigs


def blasts():
    """Взрывы по ракурсам — из оригиналов без потерь, без перегруженных записей. Клиент сводит ракурсы по давлению и
    воздуху у уха (BlastMix): вблизи — удар и тело взрыва, ниже — «удар в грудь»; дальше — средний ракурс с эхом от
    склонов и домов, вдали — раскат без верхов. Вариант выбирает зерно взрыва."""
    PRELOAD.update(["blast.near", "blast.mid", "blast.far", "blast.sub"])
    near, mid, far = [], [], []
    for fid, a, b, ch in BLASTS:
        x = src(fid, orig=True) if ch is None else src(fid, orig=True, stereo=True)[:, ch]
        x = F(onset(cut(x, a, b), -30), lo=35)
        t = np.arange(len(x)) / SR
        # вблизи: удар и тело взрыва, эхо на 10 дБ за секунду тише (его дают склоны вокруг — эхо клиента); ударная волна — форма
        # Фридлендера, которую записи издалека теряют (положительная фаза ~8 мс — заряд ~50 кг в двух десятках метров)
        n = x[:int(4.2 * SR)] * 10 ** (-10 * np.clip(t[:int(4.2 * SR)] - 0.35, 0, None) / 20)
        shock = F(friedlander(0.15, 0.008), hi=9000)
        n[:len(shock)] += shock * 0.4 * np.max(np.abs(n)) / np.max(np.abs(shock))
        # низ ниже 60 Гц вблизи даёт blast.sub — без него у ближнего ракурса запас по пикам на сам удар
        near.append(master(fade(F(n, lo=60), 0, 0.5), NEAR_LUFS))
        # средняя дистанция (сотни метров): запись как есть — удар и эхо от склонов, домов, леса
        mid.append(master(trim_tail(gate_hiss(x), -50, 0.6), MID_LUFS))
        # вдали (с километр): тот же взрыв через воздух — верха гаснут по ISO 9613-1
        far.append(master(trim_tail(gate_hiss(air(x, 1000)), -50, 0.8), FAR_LUFS))
    variants("blast.near", "subtitles.incoming.blast", "blast_near", level(near, NEAR_LUFS), level=0.2)
    variants("blast.mid", "subtitles.incoming.blast", "blast_mid", level(mid, MID_LUFS), level=0.2)
    variants("blast.far", "subtitles.incoming.blast", "blast_far", level(far, FAR_LUFS), level=0.2)

    # «под ногами»: удар земли и воздуха ниже 200 Гц — низ настоящих больших подрывов (70 т в карьере, скальный
    # подрыв в карьере на октаву ниже) и синтез: затухающий тон 50 → 35 Гц и низкий рокот
    subs = []
    for fid, a, b, ch, r in [(475780, 0.1, 6.0, 1, 1.0), (82682, 6.9, 10.4, None, 0.6)]:
        x = src(fid, orig=True) if ch is None else src(fid, orig=True, stereo=True)[:, ch]
        s = F(speed(onset(cut(x, a, b), -30), r, "Сакс: низ подрыва большего заряда (⚠ множитель на глаз)"), lo=20, hi=200)
        n = len(s)
        t = np.arange(n) / SR
        thump = np.sin(2 * np.pi * (50 * t - 3.75 * t * t)) * np.exp(-t / 0.4) * np.minimum(1, t / 0.002)
        rumble = F(rng.standard_normal(n), lo=20, hi=160) * np.exp(-t / 1.2)
        x = mix((s, 1.0), (thump, 0.8), (rumble, 0.4))
        subs.append(master(fade(x, 0, 1.0), SUB_DB, meter=low_level))
    variants("blast.sub", "subtitles.incoming.blast", "blast_sub", level(subs, SUB_DB, low_level), level=0.2)

    # обломки: камни и земля падают на рядом стоящих
    deb = []
    x = src(550342)
    for a, b in [(0.0, 3.8), (4.85, 9.0), (15.17, 23.9), (24.95, 27.0)]:
        deb.append(norm(trim_tail(cut(x, a, b), -45, 0.4), -16, 0.9))
    variants("debris.fall", "subtitles.incoming.debris", "debris_fall", deb)

    crater_fire()


def crater_fire():
    """Ровный гул настоящего пожара с треском. Огибающая и длительность — у топлива в клиенте, не в записи.
    Сдвига тона нет: масштаб Сакса относится к детонации, а не к треску горящих обломков."""
    fire = cut(src(483304, orig=True), 0.2, 14.2)
    fire = loop(fire, 12.0, 1.0)
    # Preserve the recorded crackle's dynamics: norm() uses tanh and flattens its peaks. The client calibrates
    # this file by its measured LUFS, so peak normalization does not change the model's source level.
    fire -= np.mean(fire)
    fire *= 10 ** (-1 / 20) / max(np.max(np.abs(fire)), 1e-9)
    write("blast_fire", fire, "blast.fire", "subtitles.incoming.fire", level=0.2)


# Петли гула взрывов (client/sound/Rumble) — на потолке, как петли моторов: громкость гула задаёт смесь у клиента
# (BlastSounds.RUMBLE_LUFS), файл — только тембр; по варианту на четверть вокруг уха — шум четвертей не согласован.
RUMBLE_LUFS = MOTOR_LUFS
RUMBLE_VARIANTS = 4
RUMBLE_SECONDS = 8.0


def rumble():
    """Гул взрывов без голоса движка (client/sound/Rumble): налёт в сотню ударов звучит не сотней разовых звуков, а суммой
    в четырёх петлях вокруг уха. Тембр — хвосты дальнего ракурса (раскат через километр воздуха) без атак: шум с их средним
    спектром (Уэлч) и случайными фазами — ровная петля без шва, как noise_loop моторов; огибающую гулу задают его члены в
    клиенте (каждый тик), а не файл. Записей не берёт — собирается из собранных файлов blast_far (--alone rumble)."""
    tails = []
    with open(os.path.join(ASSETS, "sounds.json"), encoding="utf-8") as fh:
        far = [f["name"].split(":")[1] for f in json.load(fh)["blast.far"]["sounds"]]
    for name in far:
        x, sr = sf.read(os.path.join(OUT, name + ".ogg"), dtype="float64")
        if sr != SR or x.ndim != 1:
            sys.exit(f"{name}: {sr} Гц, {x.ndim} кан., а сборка — {SR} Гц моно")
        # хвост — после атаки: с конца окна 400 мс от самого громкого места (атака и её окно мгновенной громкости)
        a = int((np.argmax(env_db(x)) * 0.01 + 0.4) * SR)
        tails.append(x[a:])
    for i in range(1, RUMBLE_VARIANTS + 1):
        y = engine(noise_loop(tails, RUMBLE_SECONDS), RUMBLE_LUFS, f"blast_rumble_{i}")
        write(f"blast_rumble_{i}", y, "blast.rumble", "subtitles.incoming.blast", level=0.2)


def bunker():
    """Бетонобойная бомба: удар о грунт, подземный взрыв, выброс газов, обвал полостей, толчок."""
    # хлопка у удара нет: N-волну сверхзвуковой бомбы играет её полёт (Booms), на дозвуке её нет
    # удар о землю: тяжёлый глухой удар (большой взрыв в два раза медленнее, без верхов) + обломки
    imp = F(speed(cut(src(486018), 16.2, 21.1), 0.6, "Сакс: заряд бомбы больше записанного (⚠ множитель на глаз, S5)"), lo=20, hi=1800)
    imp = mix((imp, 1.0), (pad(syn.bomb_impact(), len(imp)), 0.8), (pad(cut(src(550342), 25.0, 27.0), len(imp)), 0.25))
    write("bomb_impact", norm(fade(imp, 0.001, 0.6), -12.9), "bomb.impact", "subtitles.incoming.bomb.impact")
    # подземный взрыв: глухой, как из-под земли
    deep = []
    for fid, a, b, r in [(741174, 0, 8, 0.6), (486018, 45.15, 50.0, 0.5)]:
        s = F(speed(cut(src(fid), a, b), r, "Сакс: заряд бомбы больше записанного (⚠ множитель на глаз, S5)"), lo=18, hi=320)
        s = mix((syn.reverb(s, t60=3.0, mix=0.4, hi=400), 1.0), (pad(syn.bomb_deep(), len(s)), 0.8))
        deep.append(norm(fade(s, 0.002, 1.0), -12.3))
    variants("bomb.deep", "subtitles.incoming.blast", "bomb_deep", deep)
    # выброс газов из воронки: порыв с грохотом (рёв ускорителя, коротко)
    vent = F(cut(src(613855), 30, 34.5), lo=40, hi=3000)
    vent = mix((vent * np.exp(-np.arange(len(vent)) / SR / 1.6), 1.0), (pad(syn.bomb_vent(), len(vent)), 0.6))
    write("bomb_vent", norm(fade(vent, 0.05, 1.8), -11.3), "bomb.vent", "subtitles.incoming.bomb.vent")
    # обвал полостей: гулкий подземный удар и осыпь
    cav = cut(src(675967), 0, 9)
    cav = mix((syn.reverb(F(cav, lo=30, hi=2500), t60=3.5, mix=0.55, hi=2000), 1.0),
              (pad(syn.bomb_cave(), len(cav)), 0.7))
    write("bomb_cave", norm(fade(cav, 0.002, 1.2), -12.8), "bomb.cave", "subtitles.incoming.blast")
    # толчок земли: низкий гром, почти инфразвук
    q = F(speed(cut(src(267551), 1.2, 5.2), 0.5, "⚠ не по физике: гром на октаву ниже — толчок земли (S5)"), lo=15, hi=140)
    q = mix((q, 1.0), (pad(syn.bomb_quake(), len(q)), 0.9))
    write("bomb_quake", norm(fade(q, 0.05, 1.5), -10.9), "bomb.quake", "subtitles.incoming.bomb.quake")


# ================================================================ сирены

def sirens():
    """Воздушная тревога — настоящая сирена в Киеве (разгон, вой, спад, эхо улиц); ядерная тревога — сирена
    военного времени (ровный вой ниже), петлёй: звучит весь отсчёт до подрыва."""
    kv = src(676589)
    s = cut(kv, 20.5, 47.0)
    write("siren", norm(fade(gate_hiss(s), 0.3, 3.0), -11.7, 0.85), "siren", "subtitles.incoming.siren")
    # 25–78,5 с записи: сирена уже раскручена и ещё не выключена (вой ровный, громкость ходит с поворотом рупора);
    # шов — где громкость по обе стороны та же. Раскрутку и выбег игра делает тоном (SirenSound). Наибольшая
    # мгновенная громкость — −7 LUFS
    al = src(727588)
    a = master(loop(cut(al, 25, 78.5), 52, 1.5), -7.0, meter=lambda x: np.max(momentary(x)))
    write("nuke_alarm", a, "nuke.alarm", "subtitles.incoming.nuke.alarm")


# ================================================================ ядерный удар

def nuke():
    """Ядерный удар. Вблизи — удар фронта (громче грома, перегружен), ураган за ним, звон стекла; вдали — глухой удар
    огромного взрыва (раскаты по рельефу — эхо в игре, NukeEchoes). Пуск МБР — Titan со стола."""
    t = src(675750)
    ln = align(cut(t, 3.0, 42))
    ln = mix((ln, 1.0), (pad(sub_thump(2.5, 34, 5, 0.8), len(ln)), 0.25))
    write("nuke_launch", norm(fade(ln, 0.01, 6.0), -14.6, 0.8), "nuke.launch", "subtitles.incoming.nuke.launch")

    # удар фронта: огромный взрыв + удар грома в упор + синтез лязга — все записи в своей скорости (NATIVE); низ самого
    # скачка с фазой модели играет клиент (BlastPulse)
    big = cut(src(741175), 0, 9)
    th = cut(src(350506), 0.9, 12)
    n = int(9.0 * SR)
    crack = mix((pad(big, n), 1.0), (pad(F(th, lo=30), n), 0.6), (pad(syn.nuke_crack(9.0), n), 0.9))
    tt = np.arange(n) / SR
    crack *= 0.35 + 0.65 * np.exp(-tt / 1.2)  # удар, затем спадающий грохот
    crack = crack / np.max(np.abs(crack[:int(0.5 * SR)]))
    slam = np.clip(2.5 * crack, -1, 1) * np.exp(-tt / 0.25) + np.clip(crack, -1, 1) * (1 - np.exp(-tt / 0.25))
    write("nuke_crack", fade(F(slam, hi=9000) * 0.92, 0.001, 1.5), "nuke.crack", "subtitles.incoming.nuke.blast")

    # далёкий подрыв: дальний взрыв в своей скорости, затем гром, катящийся по рельефу
    far = []
    for fid, a, b in [(438538, 47.81, 59.3), (438693, 72.38, 81.4)]:
        s = F(align(gate_hiss(cut(src(fid), a, b))), lo=20, hi=1500)
        roll = F(cut(src(243782), 4.0, 20.0), lo=20, hi=900)
        x = mix((pad(s, int(16 * SR)), 1.0), (pad(np.pad(roll, (int(1.2 * SR), 0)), int(16 * SR)), 0.7),
                (pad(syn.nuke_boom_far(16.0), int(16 * SR)), 0.6))
        far.append(norm(fade(x, 0.002, 3.0), -12.8))
    variants("nuke.boom_far", "subtitles.incoming.nuke.blast", "nuke_boom_far", far)

    # ураганный ветер (петля): ветер за фронтом, громкость — по его скорости у слушателя (NukeSounds)
    w = mix((F(cut(src(438880), 10.5, 43), lo=40), 1.0), (F(cut(src(438346), 20, 52.5), lo=60), 0.8),
            (syn.hurricane(32.5)[:int(32.5 * SR)], 0.35))
    write("nuke_wind", norm(loop(w, 10.0, 1.0), -11.5), "nuke.wind", "subtitles.incoming.nuke.wind")

    # стёкла: окна вылетают по всей улице — много разбитых стёкол вразнобой
    shards = [cut(src(758207), 0, 1.9), cut(src(336425), 0, 3.6), cut(src(453499), 1.0, 4.7),
              cut(src(583065), 0.3, 1.2), cut(src(583065), 4.0, 5.6)]
    glass = []
    for v in range(3):
        n = int(3.5 * SR)
        g = np.zeros(n)
        for j in range(9):
            s = shards[rng.integers(len(shards))]
            s = speed(s, rng.uniform(0.85, 1.2)) * rng.uniform(0.3, 1.0)
            k = int(rng.exponential(0.35) * SR) if j else 0
            if k < n:
                g[k:k + len(s)] += s[:n - k]
        g = F(g, lo=500)
        glass.append(norm(fade(syn.reverb(g, t60=0.9, mix=0.25, hi=8000), 0.001, 0.4), -16.5, 0.8))
    variants("nuke.glass", "subtitles.incoming.nuke.glass", "nuke_glass", glass)

    rain = mix((F(cut(src(860133), 2, 45), lo=80), 1.0), (syn.black_rain(43.0)[:int(43 * SR)], 0.55))
    write("nuke_rain", norm(loop(rain, 12.0, 1.0), -15.6), "nuke.rain", "subtitles.incoming.nuke.rain")

    # щелчки счётчика: настоящие, по одному
    g = src(674113)
    db = env_db(g, 0.001)
    peaks = [i for i in range(2, len(db) - 2) if db[i] > -12 and db[i] >= db[i - 2:i + 3].max()]
    clicks = []
    for p in peaks[::max(1, len(peaks) // 12)][:4]:
        k = int(p * 0.001 * SR) - int(0.002 * SR)
        clicks.append(norm(fade(cut(g, k / SR, k / SR + 0.03), 0.0005, 0.01), -18.8, 0.85))
    variants("geiger.click", "subtitles.incoming.geiger", "geiger_click", clicks)


def after():
    """После ядерного удара: удар фронта вдали (синтез по модели — запись на случай без своих источников OpenAL), звон в
    ушах (синтез, петля), гул горящего города — записи настоящих пожаров домов в своей скорости (петли; громкость — по
    теплу пожаров, Sources.NUKE_FIRE), обрушения в руинах (синтез). Ветер в огонь — петля урагана nuke_wind (раздел
    nuke) под своим субтитром."""
    # 2 кт за 5 км (0,3 psi, фаза 0,73 с) и 15 кт за 25 км (0,12 psi, 1,66 с) — BlastModel
    cracks = [syn.nuke_crack_far(4.0, t_plus=tp, pa=pa) for tp, pa in [(0.73, 2060.0), (1.66, 800.0)]]
    variants("nuke.crack_far", "subtitles.incoming.nuke.blast", "nuke_crack_far", cracks)
    PRELOAD.add("nuke.crack_far")
    write("nuke_tinnitus", syn.nuke_tinnitus(), "nuke.tinnitus")
    # самые ровные 13 с каждой записи (мгновенная громкость гуляет меньше чем на 1 дБ): гул ровный, громкость ведёт клиент
    fires = [norm(loop(F(cut(src(fid), a, a + 13.0), lo=25), 12.0, 1.0), -15.5) for fid, a in [(257371, 33.0), (871765, 0.0)]]
    variants("nuke.fire", "subtitles.incoming.nuke.fire", "nuke_fire", fires)
    EVENTS["nuke.fire_wind"] = ("subtitles.incoming.nuke.fire_wind", ["nuke_wind"])
    variants("nuke.collapse", "subtitles.incoming.nuke.collapse", "nuke_collapse", [syn.nuke_collapse(4.5) for _ in range(3)])


def misc():
    write("designator_lock", syn.lock_beep(), "designator.lock", "subtitles.incoming.designator.lock")
    write("silent", np.zeros(int(0.05 * SR)), "silent")


# ================================================================ сеть и блэкаут

def spin_down(x, seconds, low=0.35):
    """Гул, который глохнет: обороты (тон) падают до low и громкость — в ноль за seconds, как у остановленного
    трансформатора и моторов квартала."""
    n = int(seconds * SR)
    rate = low + (1 - low) * (1 - np.linspace(0, 1, n)) ** 1.6
    pos = np.cumsum(rate)
    out = np.interp(pos, np.arange(len(x)), x)
    return out * (1 - np.linspace(0, 1, n)) ** 1.3


def grid():
    """Сеть. Выход подстанции из строя: хлопок пробоя (близкий удар молнии + синтезированный низ), затем дуга
    трещит и гаснет. Искры выбитой подстанции — настоящая дуга 20 кВ. Гул работающей — трансформатор
    на подстанции EDF (100 Гц с нечётными гармониками). Квартал гаснет — щелчок автомата и гул, который глохнет;
    загорается — рубильник и гул ламп."""
    crack = F(cut(src(434359), 2.02, 6.0), lo=35)
    arc_sources = [cut(src(438494), 0.33, 4.6), cut(src(438494), 14.42, 18.7), cut(src(367456), 1.0, 5.3)]
    crackle = F(cut(src(210878), 0.2, 2.6), lo=250)
    sigs = []
    for i, arc in enumerate(arc_sources):
        n = int(4.6 * SR)
        t = np.arange(n) / SR
        a = pad(F(arc, lo=90, hi=12000), n) * np.where(t < 0.9, 1, np.exp(-(t - 0.9) / 1.1))
        tail = np.zeros(n)
        k = int((1.6 + 0.3 * i) * SR)
        tail[k:k + len(crackle)] = crackle[:n - k] * np.exp(-np.arange(min(len(crackle), n - k)) / SR / 0.9)
        bang = mix((pad(align(crack, -20, 0.005), n), 1.0), (pad(sub_thump(2.0, 42 + 4 * i, 9, 0.55), n), 0.8))
        x = mix((bang, 1.0), (a, 0.7), (tail, 0.25))
        sigs.append(norm(fade(syn.reverb(punch(x, 5), t60=1.6, mix=0.25), 0.001, 0.6), -11, 0.95))
    variants("grid.fail", "subtitles.incoming.grid.fail", "grid_fail", sigs)

    arc = F(src(210878), lo=300, hi=14000)
    sparks = []
    for a, b in ((0.5, 1.1), (2.0, 2.5), (3.2, 3.9), (4.4, 4.9)):
        sparks.append(norm(fade(punch(cut(arc, a, b), 3), 0.003, 0.12), -17, 0.9))
    variants("grid.spark", "subtitles.incoming.grid.spark", "grid_spark", sparks)

    hum = F(gate_hiss(src(547900)), lo=70, hi=3000)
    hums = [norm(fade(cut(hum, a, a + 4.2), 0.8, 0.8), -21, 0.7) for a in (10.0, 41.0)]
    variants("grid.hum", "subtitles.incoming.grid.hum", "grid_hum", hums)

    clunk = F(cut(src(451933), 0.0, 0.9), lo=60)
    fridge = F(cut(src(573936), 6.55, 8.2), lo=40)
    downs = []
    for i, (a, stop) in enumerate(((20.0, 1.6), (60.0, 2.2))):
        body = spin_down(cut(hum, a, a + 4.0), stop)
        n = int((stop + 0.9) * SR)
        c = np.zeros(n)
        k = int(0.08 * SR)
        c[k:k + len(clunk)] = clunk[:n - k]
        x = mix((pad(body, n), 1.0), (c, 0.9), (pad(fridge if i else np.zeros(1), n), 0.5))
        downs.append(norm(fade(syn.reverb(x, t60=1.0, mix=0.2), 0.002, 0.3), -13, 0.9))
    variants("grid.power_down", "subtitles.incoming.grid.power_down", "grid_power_down", downs)

    ups = [norm(trim_tail(gate_hiss(F(cut(src(139970), 1.1, 5.2), lo=50)), -50, 0.4), -13, 0.9),
           norm(trim_tail(gate_hiss(F(cut(src(262509), 6.9, 10.8), lo=50)), -50, 0.4), -13, 0.9)]
    variants("grid.power_up", "subtitles.incoming.grid.power_up", "grid_power_up", ups)


# ================================================================ N-волна

# Длительность N-волны у записей, с (client/sound/Booms: CRACK, BOOM): короткая — снаряд, долгая — большой корпус;
# клиент растягивает запись тоном (0,5…2) до длительности волны по Карлсону (sound/SonicBoom).
CRACK_S, BOOM_S = 0.004, 0.032


def sonic():
    """N-волна сверхзвукового корпуса: скачок вверх, ровный спад до такого же разрежения и скачок обратно
    (G. B. Whitham, 1952; H. W. Carlson, NASA TP-1122). Фронт скачка у уха не мгновенный: у волны снаряда — сотые
    миллисекунды, у большого самолёта — миллисекунды (турбулентность и поглощение); здесь — 2,5% длительности
    (оценка). Земля под ухом отражает волну в ту же фазу — она уже в давлении (K_R = 2); эхо округи — короткий хвост.
    Пик — −1 дБ полной шкалы: громкость в игре — по давлению скачка (как у взрывов)."""
    for event, name, dur in (("sonic.crack", "sonic_crack", CRACK_S), ("sonic.boom", "sonic_boom", BOOM_S)):
        n = int(dur * SR)
        rise = max(2, int(0.025 * dur * SR))
        t = np.arange(n)
        wave = 1 - 2 * t / (n - 1)
        wave[:rise] *= np.linspace(0, 1, rise)
        wave[-rise:] *= np.linspace(1, 0, rise)
        x = np.concatenate([wave, np.zeros(int(0.6 * SR))])
        x = mix((x, 1.0), (syn.reverb(x, t60=0.8, mix=1.0), 0.15))
        x = fade(x, 0, 0.3)
        write(name, 10 ** (-1 / 20) * x / np.max(np.abs(x)), event, "subtitles.incoming.sonic")
    PRELOAD.update(["sonic.crack", "sonic.boom"])


# ---------------------------------------------------------------- sounds.json и авторы

ORDER = ["drone.engine", "drone.engine.far", "launch.booster", "booster.engine", "booster.separate", "missile.engine",
         "missile.engine.rear", "missile.dive", "missile.engine.far", "missile.whistle", "bomber.engine",
         "bomber.engine.far", "bomb.fall", "bomb.fall.far", "bomb.drill", "siren", "blast.near", "blast.sub",
         "blast.far", "blast.mid", "debris.fall", "blast.fire", "blast.rumble", "bomb.impact", "bomb.quake", "bomb.deep", "bomb.vent", "bomb.cave",
         "designator.lock", "silent", "nuke.alarm", "nuke.launch", "nuke.crack", "nuke.boom_far",
         "nuke.wind", "nuke.glass", "nuke.tinnitus", "nuke.rain", "nuke.crack_far", "nuke.fire",
         "nuke.fire_wind", "nuke.collapse", "geiger.click", "rocket.launch",
         "rocket.incoming", "loiter.engine",
         "loiter.engine.far", "loiter.dive", "loiter.launch", "grid.fail", "grid.spark", "grid.hum", "grid.power_down",
         "grid.power_up", "sonic.crack", "sonic.boom"]


def write_json(old=None):
    """sounds.json по EVENTS; old — прежний sounds.json (--alone): события разделов, которые не собирались, — из него."""
    if old is None:
        assert set(ORDER) == set(EVENTS), set(ORDER) ^ set(EVENTS)
    else:
        assert set(ORDER) == set(EVENTS) | set(old), set(ORDER) ^ (set(EVENTS) | set(old))
    data = {}
    for ev in ORDER:
        if ev not in EVENTS:
            data[ev] = old[ev]
            continue
        sub, files = EVENTS[ev]
        entry = {"subtitle": sub} if sub else {}
        entry["sounds"] = [{"name": f"incoming:{f}", "preload": True} if ev in PRELOAD else {"name": f"incoming:{f}"}
                           for f in files]
        data[ev] = entry
    with open(os.path.join(ASSETS, "sounds.json"), "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)
        fh.write("\n")


def write_credits():
    rows = []
    for fid in sorted(USED, key=lambda i: (SOURCES[i][0].lower(), i)):
        author, title, lic, _ = SOURCES[fid]
        rows.append((author, title, lic, f"https://freesound.org/s/{fid}/"))
    lic_url = {CC0: "https://creativecommons.org/publicdomain/zero/1.0/",
               BY4: "https://creativecommons.org/licenses/by/4.0/", BY3: "https://creativecommons.org/licenses/by/3.0/"}
    md = ["# Sound credits", "",
          "The mod's sounds are built by `tools/build_sounds.py` from the recordings below (cut, mixed, filtered,",
          "resampled) and from synthesis (`tools/synth_mod_sounds.py`). CC0 recordings are in the public domain;",
          "CC BY recordings are used with attribution and modified. Thanks to the authors.", "",
          "| Author | Recording | License |", "|---|---|---|"]
    md += [f"| {a} | [{t}]({u}) | [{lic}]({lic_url[lic]}) |" for a, t, lic, u in rows]
    with open(os.path.join(ROOT, "SOUND-CREDITS.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(md) + "\n")
    txt = ["Incoming sounds are built from the recordings below (cut, mixed, filtered, resampled)",
           "plus synthesis. CC0 = public domain; CC BY = used with attribution, modified.", ""]
    txt += [f"{a} - \"{t}\" - {lic} - {u}" for a, t, lic, u in rows]
    with open(os.path.join(OUT, "credits.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(txt) + "\n")


def measure():
    """Спектры и громкость звуков источника (SOURCES_AT) по уже собранным файлам sounds.json — без записей Freesound."""
    with open(os.path.join(ASSETS, "sounds.json"), encoding="utf-8") as fh:
        data = json.load(fh)
    for ev in sorted(SOURCES_AT):
        for f in data[ev]["sounds"]:
            x, sr = sf.read(os.path.join(OUT, f["name"].split(":")[1] + ".ogg"), dtype="float64")
            if sr != SR:
                sys.exit(f"{f['name']}: {sr} Гц, а сборка — {SR}")
            x = x if x.ndim == 1 else x.mean(axis=1)
            SPECTRA_GOT.setdefault(ev, []).append(octaves(x))
            LUFS_GOT.setdefault(ev, []).append(loudness(x))
    write_spectra()


def relevel():
    """Файлы событий MOTOR — на MOTOR_LUFS по уже собранным файлам (без записей Freesound): тот же master(), что при
    сборке, — усиление и прозрачный ограничитель пиков; петли — мерой по кругу (engine), разовые — линейной
    (loudness). Сборка из записей с теми же числами даёт ту же громкость, без ещё одного сжатия Vorbis."""
    with open(os.path.join(ASSETS, "sounds.json"), encoding="utf-8") as fh:
        data = json.load(fh)
    for ev in sorted(MOTOR):
        for f in data[ev]["sounds"]:
            name = f["name"].split(":")[1]
            x, sr = sf.read(os.path.join(OUT, name + ".ogg"), dtype="float64")
            if sr != SR or x.ndim != 1:
                sys.exit(f"{name}: {sr} Гц, {x.ndim} кан., а сборка — {SR} Гц моно")
            loop = ev not in {"launch.booster", "rocket.launch"}
            lufs = ROCKET_LAUNCH_LUFS if ev == "rocket.launch" else MOTOR_LUFS
            y = engine(x, lufs, name, steady=False) if loop else level([master(x, lufs)], lufs)[0]
            write(name, y)


if __name__ == "__main__":
    PARTS = (drone, missile, launch, bomber, blasts, rumble, bunker, sirens, nuke, after, misc, rocket, loiter, grid, sonic)
    args = sys.argv[1:]
    if args == ["--measure"]:
        measure()
        sys.exit(0)
    if args == ["--relevel"]:
        relevel()
        sys.exit(0)
    PREVIEWS = "--previews" in args
    args = [a for a in args if a != "--previews"]
    # --alone: только названные разделы (без записей Freesound — например, синтез в облаке): прочие события
    # sounds.json и авторы остаются как были
    alone = "--alone" in args
    only = set(a for a in args if a != "--alone")
    unknown = only - {p.__name__ for p in PARTS}
    if unknown:
        sys.exit(f"нет таких разделов: {', '.join(sorted(unknown))}; есть: {', '.join(p.__name__ for p in PARTS)}")
    if alone and not only:
        sys.exit("--alone — с разделами")
    old = None
    if alone:
        with open(os.path.join(ASSETS, "sounds.json"), encoding="utf-8") as fh:
            old = json.load(fh)
    for part in PARTS:
        if alone and part.__name__ not in only:
            continue
        print(part.__name__ + ("" if not only or part.__name__ in only else " (файлы не пишутся)"))
        WRITING = not only or part.__name__ in only
        SECTION = part.__name__
        # свой генератор у каждого раздела: правка одного раздела не сдвигает случайные числа другим
        rng = np.random.default_rng(zlib.crc32(part.__name__.encode()))
        syn.rng = np.random.default_rng(zlib.crc32(("syn." + part.__name__).encode()))
        part()
    if alone:
        if USED:
            sys.exit("--alone: разделы берут записи — авторы в SOUND-CREDITS.md сменились бы; собрать без --alone")
        stale = {f["name"].split(":")[1] + ".ogg" for ev in EVENTS if ev in old for f in old[ev]["sounds"]}
        old = {ev: entry for ev, entry in old.items() if ev not in EVENTS}
        write_json(old)
        used = {f + ".ogg" for _, files in EVENTS.values() for f in files}
        used |= {f["name"].split(":")[1] + ".ogg" for entry in old.values() for f in entry["sounds"]}
        for f in stale - used:  # прежние файлы этих событий, которых больше нет
            if os.path.exists(os.path.join(OUT, f)):
                os.remove(os.path.join(OUT, f))
        write_spectra()
        print("готово:", len(EVENTS), "событий пересобрано")
        sys.exit(0)
    write_json()
    write_credits()
    write_spectra()
    used = {f + ".ogg" for _, files in EVENTS.values() for f in files}
    for f in os.listdir(OUT):  # прежние файлы, которых больше нет в сборке
        if f.endswith(".ogg") and f not in used:
            os.remove(os.path.join(OUT, f))
    print("готово:", len(EVENTS), "событий")
