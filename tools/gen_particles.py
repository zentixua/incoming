#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "pillow>=10",
# ]
# ///
"""Effect particle textures (smoke, fire, sparks, flash, shock ring) drawn in code; fixed seed.

  uv run tools/gen_particles.py   → mod/src/main/resources/assets/incoming/textures/fx/particle/…

  smoke_00…15 — 4 smoke billows × 4 dissipation stages (billow "melts" at the edges), nearly white: particle supplies color;
  fire_00…07  — flame billows: white core, yellow and orange body, dark red tongues at the edge;
  spark       — 16×16 light point (spark, stretched along velocity), flash — soft 64×64 flash (core and halo, no rays:
                rays are a lens defect, not seen by the eye), ring — 128×128 shock-wave ring (dust strip, ragged edges);
  ball_00…15 — 128×128 fireball over its lifetime (old, procedural: bomb breakout and fireball particles — interceptor
                burst; ordinary explosion fireball is baked, tools/bake_fx): from flash (small white ball) through sooty orange
                to black smoke with embers. Volumetric render: a ball with periodic-noise bumps, core brightness as in
                the distant view (flash, then cooling (1 − u)³), color by blackbody temperature (Planck × CIE 1931,
                in linear sRGB), soot where cooled below ~1450 K and at the edge; channel tone mapping 1 − e⁻ˣ, above white
                mixed with white (CROSS: the eye saturates in all channels, the flash is whitish rather than lemon yellow),
                sRGB gamma. Ball radius is 0.7 frame half-size (client/far/FarSprites.FIREBALL); separate seed.
  ball.json — on-screen fire for each frame (mean over the ball circle, excluding soot): surrounding ball light is no brighter
                than the ball itself (client/fx/layer/FxAtlas.ballLight).
  ../halo — 64×64 halo; color premultiplied by opacity, drawn additively. (The effects layer renders the exhaust jet
  with model light, render/PlumeRenderer: it has no image.)
  flake, paper — 16×16 ash flakes and a charred paper scrap (ash after the nuclear wave, client/nuclear/NukeAsh):
                uneven spot and rectangle with a dark ragged edge; separate generator (old files unchanged).
                Only these: `uv run tools/gen_particles.py ash`.
  ../../far/disc — 32×32 soft-edged disc (projectile body, distant fireball and flash), ../../far/glow — 64×64 Gaussian
  halo (glint and veil around the flash and fireball, glow on clouds: broad soft light
  without a core; trail ribbon uses the middle row); white, vertex supplies color and brightness.
The effects layer puts particles, disc, halo and nuke/puffs billows on its own atlas with smoothing and mipmaps
(client/fx/layer/FxAtlas).
Requires Pillow and numpy.
"""
import json
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "incoming", "textures", "fx", "particle")
rng = np.random.default_rng(1945)


def save(arr_rgb, alpha, name):
    img = Image.fromarray(np.uint8(np.clip(np.dstack([arr_rgb, alpha]), 0, 1) * 255 + 0.5), "RGBA")
    path = os.path.normpath(os.path.join(OUT, name + ".png"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def fbm(n, octaves=5, base=4):
    """Seamless fractal noise: sum of octaves of smooth noise (random lattice interpolation)."""
    out = np.zeros((n, n))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = base * 2 ** o
        grid = rng.random((cells + 1, cells + 1))
        grid[-1, :] = grid[0, :]
        grid[:, -1] = grid[:, 0]
        x = np.linspace(0, cells, n, endpoint=False)
        i = x.astype(int)
        f = x - i
        f = f * f * (3 - 2 * f)
        a = grid[np.ix_(i, i)]
        b = grid[np.ix_(i, i + 1)]
        c = grid[np.ix_(i + 1, i)]
        d = grid[np.ix_(i + 1, i + 1)]
        fy, fx = np.meshgrid(f, f, indexing="ij")
        out += amp * ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy)
        total += amp
        amp *= 0.5
    return out / total


def smooth(e0, e1, x):
    s = np.clip((x - e0) / (e1 - e0), 0, 1)
    return s * s * (3 - 2 * s)


def blobs(n, count, spread, size):
    """Cauliflower: the main lump and the bumps around it are the density of billows."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    d = np.exp(-(u ** 2 + v ** 2) / (2 * 0.38 ** 2))
    for _ in range(count):
        a, r = rng.uniform(0, 2 * np.pi), rng.uniform(0.15, spread)
        sg = rng.uniform(size * 0.6, size)
        d += rng.uniform(0.45, 0.9) * np.exp(-((u - r * np.cos(a)) ** 2 + (v - r * np.sin(a)) ** 2) / (2 * sg ** 2))
    edge = smooth(0.0, 0.25, 0.97 - np.hypot(u, v))
    return d / d.max() * edge, u, v


def blur(a, radius):
    """Gaussian blur (via Pillow) - removes the “seed” of small noise."""
    img = Image.fromarray(np.uint8(np.clip(a, 0, 1) * 255))
    return np.asarray(img.filter(ImageFilter.GaussianBlur(radius)), dtype=float) / 255


def smoke(n=64):
    """billows of smoke and 4 stage: illuminated from above-left, shadow below, edges in shreds; towards the last stage there is a sparse haze."""
    d, u, v = blobs(n, rng.integers(7, 12), 0.5, 0.26)
    detail = fbm(n, 4, 3)
    dens = blur(d * (0.7 + 0.45 * detail), 1.2)
    gy, gx = np.gradient(blur(dens, 1.6))
    # light from above-left: slopes facing the light are brighter; bottom billows in its own shadow
    lit = (gy * 0.8 + gx * 0.45) * n / 3
    # internal "heads of cabbage" billows: average noise in brightness, without small seeds
    lum = blur(np.clip(0.8 + 0.3 * lit - 0.14 * np.clip(v, 0, 1) + 0.28 * (blur(detail, 1.5) - 0.5), 0.45, 1.0), 0.8)
    frames = []
    for k in range(4):
        thr = 0.04 + 0.09 * k
        erode = dens - thr * (0.6 + 0.8 * fbm(n, 3, 4))
        alpha = blur(smooth(0.0, 0.5 + 0.1 * k, erode), 0.8) * (0.95 - 0.1 * k)
        frames.append((np.dstack([lum] * 3), alpha))
    return frames


def fire(n=64):
    """billows of flame: temperature by density - white core, yellow, orange, red torn edges."""
    d, u, v = blobs(n, rng.integers(6, 10), 0.45, 0.24)
    turb = fbm(n, 5, 4)
    t = np.clip(d * (0.4 + 0.9 * turb) * 1.25, 0, 1)
    stops = [(0.0, (0.35, 0.04, 0.0)), (0.25, (0.75, 0.16, 0.02)), (0.5, (1.0, 0.45, 0.06)),
             (0.72, (1.0, 0.75, 0.25)), (0.9, (1.0, 0.95, 0.7)), (1.0, (1.0, 1.0, 0.95))]
    rgb = np.dstack([np.interp(t, [p for p, _ in stops], [c[i] for _, c in stops]) for i in range(3)])
    alpha = smooth(0.08, 0.35, t)
    return rgb, alpha


def spark(n=16):
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    a = np.clip(np.exp(-(r / 0.28) ** 2) + 0.35 * np.exp(-(r / 0.7) ** 2), 0, 1) * smooth(0, 0.15, 1 - r)
    return np.ones((n, n, 3)), a / a.max()


def flash(n=64):
    """Flash: blinding core and halo, towards the edge - zero without a step."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    a = (0.75 * np.exp(-(r / 0.12) ** 2) + 0.45 * np.exp(-(r / 0.45) ** 2)) * smooth(0, 0.2, 1 - r)
    return np.ones((n, n, 3)), np.clip(a / a.max(), 0, 1)


def ring(n=128):
    """Shock wave ring: a dusty strip around the circumference, the inner edge is clear, the outer edge is torn."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    noise = fbm(n, 5, 6)
    band = smooth(0.62, 0.8, r + 0.08 * (noise - 0.5)) * smooth(0.0, 0.14, 0.98 - r - 0.1 * noise)
    alpha = band * (0.55 + 0.6 * noise)
    lum = 0.82 + 0.18 * noise
    return np.dstack([lum] * 3), np.clip(alpha, 0, 1)


def halo(n=64):
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    a = (0.7 * np.exp(-(r / 0.2) ** 2) + 0.3 * np.exp(-(r / 0.55) ** 2)) * smooth(0, 0.2, 1 - r)
    a = a / a.max()
    return np.dstack([a] * 3), a


def far_disc(n=32):
    """Circle: solid up to half-size 0,85, edge up to 1 (smooth edge a pixel or two at any size)."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    return np.ones((n, n, 3)), smooth(0, 0.15, 1 - r)


def far_glow(n=64):
    """Halo: exp(−(r/0,42)²), to the edge of the square - zero without a step."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    a = np.exp(-(r / 0.42) ** 2) * smooth(0, 0.25, 1 - r)
    return np.ones((n, n, 3)), a / a.max()


# ---------------------------------------------------------------- ash and paper (own generator: old files do not change)

ash_rng = np.random.default_rng(1946)


def flake(n=16):
    """Ash flakes: an uneven gray spot with a ragged edge, lighter in the middle."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    ang = np.arctan2(v, u)
    wobble = sum(ash_rng.uniform(0.05, 0.14) * np.cos(k * ang + ash_rng.uniform(0, 2 * np.pi)) for k in (2, 3, 5))
    r = np.hypot(u * 1.15, v) / (0.72 + wobble)
    alpha = smooth(1.0, 0.75, r)
    lum = 0.78 + 0.2 * smooth(1.0, 0.0, r) + 0.06 * (ash_rng.random((n, n)) - 0.5)
    return np.dstack([lum] * 3), alpha


def paper(n=16):
    """A piece of paper: a light rectangle, the edge burned - darker and torn."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    edge = np.maximum(np.abs(u) / 0.9, np.abs(v) / 0.62) + 0.12 * (ash_rng.random((n, n)) - 0.5)
    alpha = smooth(1.02, 0.9, edge)
    burnt = smooth(0.62, 0.95, edge)
    lum = 0.95 - 0.7 * burnt
    rgb = np.dstack([lum, lum * 0.97, lum * 0.9])
    return rgb, alpha


# ---------------------------------------------------------------- fireball

def _cmf(x, mu, s1, s2):
    s = np.where(x < mu, s1, s2)
    return np.exp(-0.5 * ((x - mu) / s) ** 2)


def blackbody():
    """Black body 700–4000 K: temperature, ln brightness against 2000 K, color in linear sRGB per unit brightness.
     Color addition functions CIE 1931 - multi-leaf approximation Wyman, Sloan, Shirley (2013)."""
    lam = np.arange(380, 781, 2.0)
    xb = 1.056 * _cmf(lam, 599.8, 37.9, 31.0) + 0.362 * _cmf(lam, 442.0, 16.0, 26.7) - 0.065 * _cmf(lam, 501.1, 20.4, 26.2)
    yb = 0.821 * _cmf(lam, 568.8, 46.9, 40.5) + 0.286 * _cmf(lam, 530.9, 16.3, 31.1)
    zb = 1.217 * _cmf(lam, 437.0, 11.8, 36.0) + 0.681 * _cmf(lam, 459.0, 26.0, 13.8)
    to_rgb = np.array([[3.2406, -1.5372, -0.4986], [-0.9689, 1.8758, 0.0415], [0.0557, -0.2040, 1.0570]])
    temps = np.linspace(700, 4000, 331)
    lum, rgb = [], []
    for t in temps:
        b = 1 / ((lam * 1e-9) ** 5 * np.expm1(1.4388e-2 / (lam * 1e-9 * t)))
        xyz = np.array([(b * xb).sum(), (b * yb).sum(), (b * zb).sum()])
        lum.append(xyz[1])
        rgb.append(np.clip(to_rgb @ xyz, 0, None) / xyz[1])
    lum = np.array(lum)
    return temps, np.log(lum / np.interp(2000, temps, lum)), np.array(rgb)


BB_T, BB_LOG, BB_RGB = blackbody()
# the brightness of the ball in 2000 K against a white screen during the day (like the distant picture: tens of times brighter than the sky)
BB_DAY = 30.0
# linear brightness sRGB (Rec. 709); admixture of white per unit of brightness above white - to white brighter than white (fireball)
LUMA = np.array([0.2126, 0.7152, 0.0722])
CROSS = 0.15


def glow_rgb(e):
    """Linear color of a hot gas of brightness e (against a white screen): temperature in brightness, color - black body."""
    t = np.interp(np.log(np.maximum(e, 1e-9) / BB_DAY), BB_LOG, BB_T)
    c = np.stack([np.interp(t, BB_T, BB_RGB[:, i]) for i in range(3)], -1)
    y = c @ LUMA
    return c * (e / np.maximum(y, 1e-12))[..., None]


def noise_volume(n, seed, beta=3.6):
    """Periodic noise n³ spectrum k^(−β/2), without the smallest waves: billows without shear seams."""
    w = np.random.default_rng(seed).standard_normal((n, n, n))
    f = np.fft.fftfreq(n)
    kx, ky, kz = np.meshgrid(f, f, f, indexing="ij")
    k = np.sqrt(kx ** 2 + ky ** 2 + kz ** 2)
    k[0, 0, 0] = 1
    amp = k ** (-beta / 2) * (k < 0.45)
    amp[0, 0, 0] = 0
    v = np.real(np.fft.ifftn(np.fft.fftn(w) * amp))
    return (v - v.mean()) / v.std()


def trilinear(vol, x, y, z):
    """Noise in points (fractions of a period), trilinear, with repetition."""
    n = vol.shape[0]
    x, y, z = x * n, y * n, z * n
    x0, y0, z0 = np.floor(x).astype(int), np.floor(y).astype(int), np.floor(z).astype(int)
    fx, fy, fz = x - x0, y - y0, z - z0
    out = 0
    for xi, wx in ((x0, 1 - fx), (x0 + 1, fx)):
        for yi, wy in ((y0, 1 - fy), (y0 + 1, fy)):
            for zi, wz in ((z0, 1 - fz), (z0 + 1, fz)):
                out = out + vol[xi % n, yi % n, zi % n] * wx * wy * wz
    return out


def fireball(u, vol, n=128, steps=96, cross=CROSS):
    """Frame of the sphere on the life share u: ray along the view through the volume, emission and absorption (gas and soot)."""
    # radius in fractions of the half-size of the frame: for the first percent of life - almost all of it, then slowly grows
    radius = 0.70 * (0.30 + 0.70 * (1 - np.exp(-u / 0.05))) * (1 + 0.06 * u)
    # core brightness against white screen: flash, then cool down
    core = 60 * max(0.0, 1 - (u / 0.14) ** 2) + 16 * (1 - u) ** 3 + 0.02
    lin = np.linspace(-1 + 1 / n, 1 - 1 / n, n)
    zs = np.linspace(-1, 1, steps)
    dz = zs[1] - zs[0]
    yy, xx = np.meshgrid(-lin, lin, indexing="ij")
    light = np.zeros((n, n, 3))
    fire = np.zeros((n, n, 3))
    clear = np.ones((n, n))
    q = 1 / radius
    for z in zs:
        r = np.sqrt(xx ** 2 + yy ** 2 + z ** 2)
        # the bumps grow along with the ball (noise at a point normalized to the radius) and boil over time
        big = trilinear(vol, xx * q * 0.17, yy * q * 0.17, z * q * 0.17 + 0.3 * u)
        fine = trilinear(vol, xx * q * 0.55 + 0.5, yy * q * 0.55 + 0.3, z * q * 0.55 + 0.9 * u)
        edge = radius * (1 + (0.16 + 0.14 * u) * np.tanh(0.6 * big + 0.3 * fine))
        soft = 0.03 + 0.09 * u * u
        dens = smooth(-soft, soft, edge - r) * (1 - 0.45 * u * u * smooth(0.6, 1.0, r / edge))
        if not dens.any():
            continue
        x = np.clip(r / edge, 0, 1)
        heat = core * (1 - 0.9 * x ** 2) * np.exp(0.7 * np.clip(fine, -2, 2) - 0.25 * big) * (1 - 0.35 * u * x)
        # soot: where it has cooled (brightness < ~0,5 - below ~1450 K) and at the edge that cools first
        soot = np.maximum(smooth(0.5, 0.05, heat), 0.8 * smooth(0.82, 1.0, x) * smooth(0.08, 0.35, u)) * (0.3 + 0.7 * smooth(0.1, 0.6, u))
        gas, smoke = 22.0 * dens, 40.0 * dens * soot
        total = gas + smoke
        a = 1 - np.exp(-total * dz)
        # smoke shines only with its color (light from above, convex is lighter): the ball is drawn without the light of the world
        shade = np.clip(0.75 + 0.3 * np.clip(yy * q, -1, 1) + 0.35 * np.tanh(big - 0.6 * fine), 0.25, 1.4)
        w = np.where(total > 0, gas / np.maximum(total, 1e-9), 0)[..., None]
        glow = glow_rgb(heat * (1 - soot)) * w
        src = glow + np.array([0.055, 0.05, 0.046]) * shade[..., None] * (1 - w)
        light += (clear * a)[..., None] * src
        fire += (clear * a)[..., None] * glow
        clear *= 1 - a
    alpha = 1 - clear
    # color without opacity - average light along the beam; tone on channels 1 − e⁻ˣ, gamma sRGB. Brighter than white - with an admixture of
    #  white (cross): the eye and film are saturated in all channels, and very bright things of any color are visible as white; without it, the blue
    #  channel 2000 K (zero) remains zero, and the flash comes out purely lemon
    lin = light / np.maximum(alpha[..., None], 1e-4)
    lin = lin + cross * np.maximum(0, lin @ LUMA - 1)[..., None]
    tone = 1 - np.exp(-lin)
    srgb = np.where(tone <= 0.0031308, 12.92 * tone, 1.055 * np.power(tone, 1 / 2.4) - 0.055)
    srgb = np.where(alpha[..., None] > 1e-4, srgb, 0)
    # fire of a ball on the screen: the share of fire in the light pixel × pixel (with opacity), the average over the circle of the radius of the ball -
    #  soot only appears to be its color (the ball is drawn without the light of the world), it does not illuminate anything
    share = (fire @ LUMA) / np.maximum(light @ LUMA, 1e-9)
    shown = float(((srgb @ LUMA) * alpha * share).sum() / (np.pi * (radius * n / 2) ** 2))
    return srgb, alpha, shown


if __name__ == "__main__":
    if sys.argv[1:] == ["ash"]:
        save(*flake(), "flake")
        save(*paper(), "paper")
        print("ok")
        sys.exit(0)
    for i in range(4):
        for k, (rgb, a) in enumerate(smoke()):
            save(rgb, a, f"smoke_{i * 4 + k:02d}")
    for i in range(8):
        save(*fire(), f"fire_{i:02d}")
    save(*spark(), "spark")
    save(*flash(), "flash")
    save(*ring(), "ring")
    save(*halo(), "../halo")
    save(*far_disc(), "../../far/disc")
    save(*far_glow(), "../../far/glow")
    volume = noise_volume(64, 1945)
    shown = []
    for i in range(16):
        rgb, a, f = fireball((i + 0.5) / 16, volume)
        save(rgb, a, f"ball_{i:02d}")
        shown.append(round(f, 4))
    with open(os.path.join(OUT, "ball.json"), "w", encoding="utf-8") as out:
        json.dump({"fire": shown}, out)
        out.write("\n")
    save(*flake(), "flake")
    save(*paper(), "paper")
    print("ok")
