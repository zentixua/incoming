#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "pillow>=10",
# ]
# ///
"""Mod textures drawn in code (fixed seed for reproducible output).

  uv run tools/gen_textures.py   → mod/src/main/resources/assets/incoming/textures/…

  item/strike_designator, item/geiger_counter — 16×16 pixel art (character grid + palette);
  item/shahed, lancet, cruise_missile, grad_rockets, bunker_buster, icbm, nuclear_warhead — ammunition, also pixel art;
  mob_effect/radiation_sickness, mob_effect/burns — 18×18 effect icons;
  block/trinitite — 16×16 fused green sand, seamless;
  block/substation_* — 16×16 transformer substation (tank, front with sign, radiator, insulator, slab) and burnt variants;
  block/fixed_launcher_* — 16×16 fixed launcher (pedestal with hatch, top with turntable ring, bottom);
  block/sam_* — 16×16 SAM (chassis with louvers, container side, end with four tube covers, top, radar grid);
  item/interceptor — 16×16 interceptor missile;
  nuke/puffs — 4×2 atlas of mushroom billows, 64×64 each (nearly gray: renderer supplies color),
  nuke/rain — 64×256 black-rain drop sheet (vertically seamless); circular glow is a formula in the nuke_flare shader.
Requires Pillow and numpy.
"""
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "incoming", "textures")
rng = np.random.default_rng(7)

# Aiming panel: protective housing, two rangefinder lenses, screen with reticle, red launch button
DESIGNATOR = [
    "................",
    "..KKKK....KKKK..",
    ".KLLLLK..KLLLLK.",
    ".KLGGLK..KLGGLK.",
    ".KLLLLKKKKLLLLK.",
    "..KKKKOOOOKKKK..",
    "..KOOOOOOOOOOK..",
    "..KODDDDDDDDOK..",
    "..KODSSSXSSDOK..",
    "..KODSXXXXSDOK..",
    "..KODSSSXSSDOK..",
    "..KODDDDDDDDOK..",
    "..KOOOOOORROOK..",
    "..KOOoOoORROOK..",
    "...KKKKKKKKKK...",
    "................",
]
PALETTE = {
    ".": (0, 0, 0, 0),
    "K": (24, 26, 22, 255),     # outline
    "O": (86, 98, 58, 255),     # body (olive)
    "o": (64, 74, 44, 255),     # body shadow
    "L": (60, 62, 66, 255),     # lens frame
    "G": (120, 190, 220, 255),  # glass
    "D": (30, 34, 30, 255),     # screen frame
    "S": (40, 90, 50, 255),     # screen
    "X": (140, 255, 140, 255),  # aiming mark
    "R": (220, 40, 30, 255),    # launch button/arrow
    "Y": (206, 174, 44, 255),   # meter housing
    "y": (150, 124, 32, 255),   # body shadow
    "W": (232, 230, 212, 255),  # scale
    "g": (70, 62, 34, 255),     # speaker grille
    "n": (44, 44, 46, 255),     # pens
    "M": (196, 200, 206, 255),  # sensor tube
    "m": (118, 124, 134, 255),  # tube shadow
    "H": (46, 46, 50, 255),     # sensor handle
    "c": (30, 30, 30, 255),     # twisted cord
    "B": (250, 214, 40, 255),   # radiation sign: yellow
    "b": (20, 20, 20, 255),     # radiation sign: black
    "F": (255, 214, 90, 255),   # flame: core
    "f": (240, 120, 30, 255),   # flame
    "r": (170, 40, 20, 255),    # flame: edge
    "A": (182, 180, 170, 255),  # shahed glider (grey-beige)
    "a": (126, 124, 116, 255),  # glider shadow
}

# Burn icon 18×18: flames
BURNS_ICON = [
    "..................",
    "........r.........",
    "........rr........",
    ".......rfr........",
    ".......rffr.......",
    "......rfffr...r...",
    "......rfffrr..rr..",
    ".....rffFffr.rfr..",
    ".....rfFFffrrffr..",
    "....rffFFFffffr...",
    "....rfFFFFFfffr...",
    "...rffFFFFFFffr...",
    "...rfFFFFFFFFfr...",
    "...rfFFFFFFFFfr...",
    "...rffFFFFFFffr...",
    "....rffFFFFffr....",
    ".....rrffffrr.....",
    "..................",
]

# Geiger counter: yellow body with handle, scale with arrow, speaker, sensor on a twisted cord
GEIGER = [
    "................",
    ".............KK.",
    "............KMmK",
    "............KMmK",
    "...KKKKK....KMmK",
    "...K...K....KMmK",
    "KKKKKKKKKKK.KHHK",
    "KYYYYYYYYyK.KHHK",
    "KYDDDDDYYyK.KHHK",
    "KYDWWRDYYyK..KK.",
    "KYDWRWDYYyK..c..",
    "KYDDDDDYgyK.c.c.",
    "KYYYYYYYgyKc...c",
    "KYnYnYYYgyK.c.c.",
    "KyyyyyyyyyK..c..",
    ".KKKKKKKKK......",
]

# Remote control ammunition: all nose up (bomb down), outline K, no small details - read in inventory slot
SHAHED = [  # delta wing, fins at the ends, pusher propeller at the rear
    "................",
    ".......KK.......",
    "......KAAK......",
    "......KAAK......",
    ".....KAAAAK.....",
    ".....KAaaAK.....",
    "....KAAaaAAK....",
    "....KAAaaAAK....",
    "...KAAAaaAAAK...",
    "..KAAAAaaAAAAK..",
    ".KAAAAAaaAAAAAK.",
    "KaAAAAAaaAAAAAaK",
    "KaKKKKAaaAKKKKaK",
    ".K....KAAK....K.",
    "......KnnK......",
    ".......nn.......",
]
LANCET = [  # cylinder with two wing crosses, screw at the back
    "................",
    ".......KK.......",
    "......KOOK......",
    "......KOOK......",
    "...KKKKOOKKKK...",
    "..KoooKOOKoooK..",
    "...KKKKOOKKKK...",
    "......KOOK......",
    "......KOOK......",
    "......KOOK......",
    ".KKKKKKOOKKKKKK.",
    "KooooooOOooooooK",
    ".KKKKKKOOKKKKKK.",
    "......KOOK......",
    "......KnnK......",
    ".......nn.......",
]
CRUISE_MISSILE = [  # gray body, folded wings, tail, turbine flare
    "................",
    ".......KK.......",
    "......KMMK......",
    "......KMmK......",
    "......KMmK......",
    "......KMmK......",
    "....KKKMmKKK....",
    "...KmmmMmmmmK...",
    "....KKKMmKKK....",
    "......KMmK......",
    "......KMmK......",
    "......KMmK......",
    ".....KKMmKK.....",
    "....KmKMmKmK....",
    "....KK.FF.KK....",
    ".......ff.......",
]
GRAD_ROCKETS = [  # package of pipes in front: each contains a projectile head
    "................",
    ".KKKKKKKKKKKKKK.",
    ".KOOOOOOOOOOOOK.",
    ".KOMmOMmOMmOMmK.",
    ".KOmnOmnOmnOmnK.",
    ".KOOOOOOOOOOOOK.",
    ".KOMmOMmOMmOMmK.",
    ".KOmnOmnOmnOmnK.",
    ".KOOOOOOOOOOOOK.",
    ".KOMmOMmOMmOMmK.",
    ".KOmnOmnOmnOmnK.",
    ".KOOOOOOOOOOOOK.",
    ".KooooooooooooK.",
    ".KKKKKKKKKKKKKK.",
    "..Kn........nK..",
    "................",
]
BUNKER_BUSTER = [  # thick nose-down bomb: plumage, yellow belt, steel nose
    "....KK....KK....",
    "....KoK..KoK....",
    "....KooKKooK....",
    ".....KKOOKK.....",
    ".....KOOOOK.....",
    "....KOOOOOoK....",
    "....KYYYYYYK....",
    "....KOOOOOoK....",
    "....KOOOOOoK....",
    "....KOOOOOoK....",
    "....KOOOOOoK....",
    "....KOOOOOoK....",
    ".....KOOOoK.....",
    ".....KMMMmK.....",
    "......KMmK......",
    ".......KK.......",
]
ICBM = [  # white rocket with a black head, stages, stabilizers, torch
    ".......KK.......",
    "......KbbK......",
    "......KbbK......",
    ".....KbbbbK.....",
    ".....KWWWMK.....",
    ".....KWWWMK.....",
    ".....KmmmmK.....",
    ".....KWWWMK.....",
    ".....KWWWMK.....",
    ".....KWWWMK.....",
    ".....KWWWMK.....",
    "....KKWWWMKK....",
    "...KmKWWWMKmK...",
    "...KKKKKKKKKK...",
    "......FFFF......",
    ".......ff.......",
]
NUCLEAR_WARHEAD = [  # head part: cone with yellow and black mark
    "................",
    ".......KK.......",
    "......KMMK......",
    "......KMmK......",
    ".....KMMmmK.....",
    ".....KMMmmK.....",
    "....KMMBBmmK....",
    "....KMBbbBmK....",
    "...KMMBbbBmmK...",
    "...KMMMBBmmmK...",
    "..KMMMMMmmmmmK..",
    "..KMMMMMmmmmmK..",
    ".KMMMMMMmmmmmmK.",
    ".KmmmmmmmmmmmmK.",
    ".KKKKKKKKKKKKKK.",
    "................",
]
MUNITIONS = {"shahed": SHAHED, "lancet": LANCET, "cruise_missile": CRUISE_MISSILE, "grad_rockets": GRAD_ROCKETS,
             "bunker_buster": BUNKER_BUSTER, "icbm": ICBM, "nuclear_warhead": NUCLEAR_WARHEAD}


def save(img, name):
    path = os.path.join(OUT, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def paint(rows, name):
    size = len(rows)
    img = Image.new("RGBA", (size, size))
    for y, row in enumerate(rows):
        assert len(row) == size, (name, y)
        for x, c in enumerate(row):
            img.putpixel((x, y), PALETTE[c])
    save(img, name)


def rgba(rgb, a):
    """Arrays of color (h, w, 3) and transparency (h, w) in 0..1 → picture RGBA."""
    return Image.fromarray(np.uint8(np.clip(np.dstack([rgb, a]), 0, 1) * 255 + 0.5), "RGBA")


def pnoise(size, beta):
    """Periodic (seamless) noise 1/f^beta via FFT, in 0..1."""
    X = np.fft.fft2(rng.standard_normal((size, size)))
    k = np.fft.fftfreq(size) * size
    X /= np.maximum(np.hypot(*np.meshgrid(k, k)), 1) ** beta
    n = np.real(np.fft.ifft2(X))
    return (n - n.min()) / (n.max() - n.min())


def smooth(e0, e1, x):
    s = np.clip((x - e0) / (e1 - e0), 0, 1)
    return s * s * (3 - 2 * s)


def gradient(v, stops):
    """Color by reference points [(v, (r, g, b)), ...], v and components in 0..1."""
    return np.dstack([np.interp(v, [p for p, _ in stops], [c[i] for _, c in stops]) for i in range(3)])


def puff(N=64):
    """billows of smoke: main lump and several mounds, “boiling” noise inside, light from above; the edges are transparent."""
    u, v = np.meshgrid(np.linspace(-1, 1, N), np.linspace(-1, 1, N))
    d = np.exp(-(u ** 2 + v ** 2) / (2 * 0.42 ** 2))
    for _ in range(rng.integers(5, 10)):
        a, r = rng.uniform(0, 2 * np.pi), rng.uniform(0.2, 0.5)
        sg = rng.uniform(0.15, 0.3)
        d += rng.uniform(0.5, 0.9) * np.exp(-((u - r * np.cos(a)) ** 2 + (v - r * np.sin(a)) ** 2) / (2 * sg ** 2))
    d = d / d.max() * (0.7 + 0.6 * pnoise(N, 1.7)) * smooth(0.0, 0.3, 0.98 - np.hypot(u, v))
    alpha = smooth(0.05, 0.5, d) * 0.95
    shade = np.gradient(d, axis=0) * N / 4  # slopes turned upward (density increases downwards) are lighter
    lum = np.clip(0.8 + 0.25 * shade + 0.1 * (pnoise(N, 1.2) - 0.5), 0.6, 1.0)
    return rgba(np.dstack([lum] * 3), alpha)


def trefoil(N=18):
    """Radiation sickness icon: radiation sign (trefoil) on a yellow circle, pixels in polar coordinates."""
    img = Image.new("RGBA", (N, N))
    c = (N - 1) / 2
    for y in range(N):
        for x in range(N):
            dx, dy = x - c, c - y
            r = np.hypot(dx, dy)
            if r > c + 0.3:
                continue
            ang = np.degrees(np.arctan2(dy, dx)) % 360
            # petals with centers at 30°, 150° and 270°, according to 60°
            blade = any(abs((ang - a + 180) % 360 - 180) <= 30 for a in (30, 150, 270)) and 2.6 <= r <= c - 1.2
            dark = r <= 1.5 or blade
            img.putpixel((x, y), PALETTE["b"] if dark else PALETTE["B"])
    return img


def rain_sheet(w=64, h=256, drops=46):
    """Black rain: a sheet of thin drops, seamless vertically (the color is provided by the renderer - there are almost white strokes here)."""
    a = np.zeros((h, w))
    rgb = np.ones((h, w, 3)) * 0.9
    for _ in range(drops):
        x = int(rng.integers(0, w))
        y0 = int(rng.integers(0, h))
        length = int(rng.integers(10, 26))
        for k in range(length):
            f = k / length
            a[(y0 + k) % h, x] = max(a[(y0 + k) % h, x], 0.25 + 0.75 * f ** 1.5)
        # heavy oily drop head
        a[(y0 + length) % h, x] = 1.0
        if x + 1 < w:
            a[(y0 + length) % h, x + 1] = max(a[(y0 + length) % h, x + 1], 0.5)
    return rgba(rgb, a)


def trinitite(N=16):
    """Trinitite: glassy green-gray alloy of sand, bubbles with reflections, light and dark inclusions; seamless."""
    shades = np.array([(58, 74, 60), (72, 92, 72), (88, 110, 84), (104, 128, 96), (126, 150, 112)]) / 255
    idx = np.clip((pnoise(N, 1.2) + 0.25 * rng.random((N, N))) / 1.25 * len(shades), 0, len(shades) - 1).astype(int)
    rgb = shades[idx]
    for _ in range(4):  # bubble: dark dot, highlight on the top left (with transfer over the edge)
        x, y = rng.integers(0, N, 2)
        rgb[y, x] = (40 / 255, 52 / 255, 44 / 255)
        rgb[(y - 1) % N, (x - 1) % N] = (150 / 255, 176 / 255, 140 / 255)
    for _ in range(6):
        x, y = rng.integers(0, N, 2)
        rgb[y, x] = (170 / 255, 204 / 255, 150 / 255) if rng.random() < 0.6 else (34 / 255, 40 / 255, 34 / 255)
    return rgba(rgb, np.ones((N, N)))


# ---------------------------------------------------------------- substation (its own generator: the previous textures do not change)

srng = np.random.default_rng(11)
STEEL = np.array((104, 116, 106)) / 255     # painted steel tank (gray-green, like transformers)
CONCRETE = np.array((150, 148, 142)) / 255


def speckle(base, N=16, amount=0.06):
    """Smooth paint with small unevenness, seamless."""
    n = pnoise(N, 1.0) - 0.5
    return np.clip(base[None, None, :] * (1 + amount * 2 * n[..., None]) + amount * 0.3 * (srng.random((N, N, 1)) - 0.5), 0, 1)


def substation_side(N=16):
    """Tank wall: paint, stiffening ribs through 5 pixels, light edge on top, dark on bottom."""
    rgb = speckle(STEEL)
    for x in (2, 7, 12):
        rgb[:, x] *= 1.18
        rgb[:, x + 1] *= 0.8
    rgb[0] *= 1.25
    rgb[-1] *= 0.7
    return rgb


def substation_front(N=16):
    """Front: door with hinges and handle, yellow triangle “Caution, electrical voltage” with lightning."""
    rgb = speckle(STEEL)
    rgb[0] *= 1.25
    rgb[-1] *= 0.7
    rgb[2:15, 2] *= 0.75
    rgb[2:15, 13] *= 0.75
    rgb[2, 2:14] *= 0.75
    rgb[14, 2:14] *= 0.75
    rgb[4, 3] = rgb[11, 3] = (0.3, 0.32, 0.3)             # loops
    rgb[8:10, 12] = (0.2, 0.2, 0.2)                        # pen
    sign = ["....KK....",
            "....KK....",
            "...KYYK...",
            "...KYYK...",
            "..KYYKYK..",
            "..KYKKYK..",
            ".KYYYKYYK.",
            ".KYYKYYYK.",
            "KKKKKKKKKK"]
    colors = {"K": (0.08, 0.08, 0.08), "Y": (0.96, 0.8, 0.12)}
    for y, row in enumerate(sign):
        for x, c in enumerate(row):
            if c in colors:
                rgb[3 + y, 3 + x] = colors[c]
    return rgb


def substation_top(N=16):
    """Cover: paint, rivets around the edge."""
    rgb = speckle(STEEL * 1.08)
    for k in range(1, 16, 4):
        for y, x in ((1, k), (14, k), (k, 1), (k, 14)):
            rgb[y, x] = STEEL * 0.6
    return rgb


def substation_fins(N=16):
    """Radiator: frequent vertical fins with light and shadow."""
    rgb = speckle(STEEL, amount=0.04)
    for x in range(N):
        rgb[:, x] *= (1.2, 1.0, 0.72)[x % 3]
    return rgb


def substation_insulator(N=16):
    """Input insulator: brown glazed porcelain with skirts - light edge, shadow underneath; top contact."""
    rgb = np.zeros((N, N, 3))
    glaze = np.array((0.46, 0.2, 0.1))
    for y in range(N):
        rgb[y, :] = glaze * (1.35, 1.1, 0.8, 0.6)[y % 4]
    rgb[:, :] *= 1 + 0.05 * (srng.random((N, N, 1)) - 0.5)
    rgb[0:2] = (0.55, 0.55, 0.52)
    rgb[:, 0] *= 0.8
    rgb[:, -1] *= 0.8
    return np.clip(rgb, 0, 1)


def substation_base(N=16):
    """Concrete slab with pores."""
    rgb = speckle(CONCRETE, amount=0.08)
    for _ in range(8):
        x, y = srng.integers(0, N, 2)
        rgb[y, x] *= 0.75
    return rgb


def burnt(rgb, N=16):
    """Burnt out: soot in spots (black towards the bottom and towards the center of the explosion), swollen paint, rusty smudges."""
    soot = pnoise(N, 1.4)
    k = np.clip(0.25 + 0.55 * soot, 0, 1)[..., None]
    out = rgb * k
    rust = srng.random((N, N)) < 0.08
    out[rust] = (0.32, 0.14, 0.06)
    return np.clip(out, 0, 1)


def substation():
    for name, f in (("side", substation_side), ("front", substation_front), ("top", substation_top),
                    ("fins", substation_fins), ("insulator", substation_insulator)):
        rgb = f()
        save(rgba(rgb, np.ones(rgb.shape[:2])), f"block/substation_{name}")
        save(rgba(burnt(rgb), np.ones(rgb.shape[:2])), f"block/substation_{name}_burnt")
    rgb = substation_base()
    save(rgba(rgb, np.ones(rgb.shape[:2])), "block/substation_base")


# ---------------------------------------------------------------- SAM (own generator: old textures do not change)

OLIVE = np.array((86, 98, 58)) / 255         # protective paint, like the remote control
zrng = np.random.default_rng(13)


def olive(N=16, amount=0.06):
    """Protective paint with fine unevenness, seamless."""
    n = pnoise(N, 1.0) - 0.5
    return np.clip(OLIVE[None, None, :] * (1 + amount * 2 * n[..., None]) + amount * 0.3 * (zrng.random((N, N, 1)) - 0.5), 0, 1)


def sam_base(N=16):
    """Chassis: engine shutters (dark slits with a light edge), shadow and dirt below."""
    rgb = olive()
    for y in (3, 6, 9):
        rgb[y, 2:14] *= 0.45
        rgb[y + 1, 2:14] *= 1.2
    rgb[12:] *= np.linspace(0.95, 0.6, N - 12)[:, None, None]
    rgb[0] *= 1.2
    return np.clip(rgb, 0, 1)


def sam_side(N=16):
    """The side of the container package: longitudinal ribs, clamps, yellow stencil strip."""
    rgb = olive()
    for y in (0, 5, 10, 15):
        rgb[y] *= 0.7
    for x in (3, 12):
        rgb[:, x] *= 0.75
        rgb[:, x + 1] *= 1.15
    rgb[7, 5:11] = (0.78, 0.66, 0.16)
    return np.clip(rgb, 0, 1)


def sam_tubes(N=16):
    """The end of the package: four pipes 2×2 under the covers - a dark circle with a ring and a cross on the cover."""
    rgb = olive() * 0.9
    yy, xx = np.mgrid[0:N, 0:N] + 0.5
    for cy in (4.5, 11.5):
        for cx in (4.5, 11.5):
            r = np.hypot(xx - cx, yy - cy)
            rgb[r < 3.6] = OLIVE * 0.55
            rgb[(r >= 2.6) & (r < 3.6)] = OLIVE * 1.25
            rgb[(r < 2.6) & ((np.abs(xx - cx) < 0.6) | (np.abs(yy - cy) < 0.6))] = OLIVE * 0.75
    return np.clip(rgb, 0, 1)


def sam_top(N=16):
    """Top: hatch with hinges and rivets around the edge."""
    rgb = olive() * 1.05
    rgb[4:12, 4] *= 0.6
    rgb[4:12, 11] *= 0.6
    rgb[4, 4:12] *= 0.6
    rgb[11, 4:12] *= 0.6
    rgb[7:9, 10] = OLIVE * 0.4
    for k in range(1, 16, 4):
        for y, x in ((1, k), (14, k), (k, 1), (k, 14)):
            rgb[y, x] = OLIVE * 0.6
    return np.clip(rgb, 0, 1)


def sam_radar(N=16):
    """Radar canvas: array of emitters (dark squares in a light grid), frame."""
    base = np.array((70, 78, 66)) / 255
    rgb = np.clip(base[None, None, :] * (1 + 0.08 * (zrng.random((N, N, 1)) - 0.5)), 0, 1)
    for y in range(1, N - 1):
        for x in range(1, N - 1):
            if y % 2 == 1 and x % 2 == 1:
                rgb[y, x] *= 0.55
            else:
                rgb[y, x] *= 1.15
    rgb[0] = rgb[-1] = OLIVE * 0.8
    rgb[:, 0] = rgb[:, -1] = OLIVE * 0.8
    return np.clip(rgb, 0, 1)


# Anti-aircraft missile: light body obliquely, dark head, yellow stripe, cross rudders, nozzle
INTERCEPTOR = [
    "................",
    ".............DD.",
    "............DDD.",
    "...........WWD..",
    "..........WWW...",
    ".........YWW....",
    "....F...YYW.....",
    "...FF..WWY......",
    "....FFWWW.......",
    ".....WWW........",
    "....WWWF........",
    "...WWW.FF.......",
    "..BWW...F.......",
    ".OBB............",
    "OOO.............",
    ".O..............",
]
INTERCEPTOR_PALETTE = {
    ".": (0, 0, 0, 0),
    "W": (214, 216, 208, 255),   # body
    "D": (60, 62, 60, 255),      # head fairing
    "Y": (214, 178, 38, 255),    # warhead strip
    "F": (150, 152, 146, 255),   # steering wheels and stabilizers
    "B": (90, 88, 84, 255),      # nozzle
    "O": (255, 150, 50, 255),    # flame
}


def paint_with(rows, palette, name):
    img = Image.new("RGBA", (len(rows), len(rows)))
    for y, row in enumerate(rows):
        assert len(row) == len(rows), (name, y)
        for x, c in enumerate(row):
            img.putpixel((x, y), palette[c])
    save(img, name)


def sam():
    for name, f in (("base", sam_base), ("side", sam_side), ("tubes", sam_tubes), ("top", sam_top), ("radar", sam_radar)):
        rgb = f()
        save(rgba(rgb, np.ones(rgb.shape[:2])), f"block/sam_{name}")
    paint_with(INTERCEPTOR, INTERCEPTOR_PALETTE, "item/interceptor")


# ---------------------------------------------------------------- stationary launcher (latest: the previous ones do not change)

lrng = np.random.default_rng(17)


def launcher_side(N=16):
    """Cabinet: protective paint, service hatch with hinges, yellow stencil stripe, circle ring on top, dirt on bottom."""
    rgb = olive(amount=0.05) * 0.95
    rgb[0:2] = OLIVE * 0.55
    rgb[2] *= 1.15
    rgb[5:13, 4] *= 0.6
    rgb[5:13, 11] *= 0.6
    rgb[5, 4:12] *= 0.6
    rgb[12, 4:12] *= 0.6
    rgb[7, 3] = rgb[10, 3] = OLIVE * 0.45
    rgb[8:10, 10] = OLIVE * 0.4
    rgb[3, 2:14] = (0.78, 0.66, 0.16)
    for x in (1, 14):
        for y in (4, 8, 12):
            rgb[y, x] = OLIVE * 0.6
    rgb[13:] *= np.linspace(0.95, 0.65, N - 13)[:, None, None]
    rgb *= 1 + 0.04 * (lrng.random((N, N, 1)) - 0.5)
    return np.clip(rgb, 0, 1)


def launcher_top(N=16):
    """Top: turntable support ring (dark rim with bolts) on the plate."""
    rgb = olive(amount=0.05)
    yy, xx = np.mgrid[0:N, 0:N] + 0.5
    r = np.hypot(xx - N / 2, yy - N / 2)
    rgb[(r >= 5.2) & (r < 7.0)] = OLIVE * 0.5
    rgb[(r >= 4.4) & (r < 5.2)] = OLIVE * 1.2
    for a in np.arange(8) * np.pi / 4:
        x, y = int(N / 2 + 6.1 * np.cos(a)), int(N / 2 + 6.1 * np.sin(a))
        rgb[y, x] = OLIVE * 0.85
    rgb[0] = rgb[-1] = OLIVE * 0.7
    rgb[:, 0] = rgb[:, -1] = OLIVE * 0.7
    return np.clip(rgb, 0, 1)


def launcher_bottom(N=16):
    """Bottom: the same paint in the shade."""
    return np.clip(olive(amount=0.05) * 0.6, 0, 1)


def launcher():
    for name, f in (("side", launcher_side), ("top", launcher_top), ("bottom", launcher_bottom)):
        rgb = f()
        save(rgba(rgb, np.ones(rgb.shape[:2])), f"block/fixed_launcher_{name}")


if __name__ == "__main__":
    paint(DESIGNATOR, "item/strike_designator")
    paint(GEIGER, "item/geiger_counter")
    for name, rows in MUNITIONS.items():
        paint(rows, "item/" + name)
    save(trefoil(), "mob_effect/radiation_sickness")
    paint(BURNS_ICON, "mob_effect/burns")
    save(trinitite(), "block/trinitite")
    atlas = Image.new("RGBA", (256, 128))
    for i in range(8):
        atlas.paste(puff(), ((i % 4) * 64, (i // 4) * 64))
    save(atlas, "nuke/puffs")
    # here was the plasma of a nuclear ball (the ball is the volume of the mushroom in the pall passage): the same generator consumption - the drops are the same
    pnoise(128, 1.8)
    pnoise(128, 1.3)
    save(rain_sheet(), "nuke/rain")
    substation()
    sam()
    launcher()
    print("ok")
