# /// script
# dependencies = ["numpy", "Pillow", "opencv-python-headless", "scipy"]
# ///
"""Baked frames (tools/bake_fx/bake.py) → effects layer textures (mod/src/main/resources/assets/incoming/textures/fx/particle).

  uv run tools/bake_fx/pack.py <bake folder> [<texture folder>]

Fireball: fireball_00…47 (256×256, straight alpha: the layer atlas premultiplies it), their motion vectors fireballv_00…47
(128×128: R, G = 0.5 + displacement/(2·{FIREBALL_FLOW}) in 256-frame pixels to the next frame; A is 1), and fireball.json:
- frames: {BURN_FRAMES} evenly spaced in simulation burn time (0…t, last at the very end of burning); the rest are cooling,
  evenly spaced in simulation time up to the last frame with luminous flame; each frame has time in burn fractions ("burn")
  and flame color temperature ("kelvin", smoothly and strictly decreasing while cooling). The mod selects a frame by its
  ball model state (burning by time, cooling by temperature, FxAtlas.fireballFrame); blend along vectors between frames;
- color: render is Kirchhoff emission (brightness relative to a 1490 K blackbody, white wall by day is 1); atlas presentation:
  exposure for fire (film_exposure) and per-channel film curve (film), exponent 1/2.2;
- frame uses one scale for the entire animation: ball radius (circle with the opaque area) during burning is the model ball
  radius; during cooling, the end-of-burning scale. The frame contains all material (edge by density, EDGE_ALPHA), is at least
  {FIREBALL} ball radii wide and does not shrink; column from the ground under the ball extends up to {DOWN} radii down;
  "half" is frame half-size in ball radii;
- "fire" is on-screen frame fire: mean brightness over the ball circle (fraction of white, FxAtlas.fireLight).
Frame temperature comes from the brightest flame brightness in that frame: in the render it is e^(c₂/λ·(1/1490 − 1/T))
(Wien at 555 nm), giving T. Bake color uses the same temperature: output checks surface emission during burning is of the
order of CCPS 275 kW/m².

Billows from other ready-made simulations (bake.py clubs-vdb --kind <name>): <name>6a/b/v_* and <name>6.json in the same
order as the smoke billow, with maps on its scale; json also has "life", the simulation lifetime fraction for each frame
(the mod selects a frame by its own phenomenon lifetime fraction), source frames and how they were captured.

Smoke billow: puff6a_00…15 (R is light from the right, G from above, B from behind, A is opacity), puff6b_00…15 (left, below,
front; same A), puff6v_00…15 (motion vectors to the next frame: R, G = 0.5 + displacement/(2·{FLOW}) in frame pixels; A is 1)
and puff6.json: material edge growth relative to the first frame ("growth", mod selects a frame by its cloud model growth,
FxAtlas.puffFrame), displacement limit ("flow"), frame size and map count. Maps share one scale (brightest across all frames
and sides is 1, without clipping); "front" and "diffuse" in puff6.json are mean front light and mean six-sided light over the
billow (divided by opacity, weighted by opacity): shader normalizes maps to these (white wall is 1); atlas uses exponent
1/2.2 (shader sums light linearly). Frame follows the material edge
(opacity 0.05) with a 4% margin: the billow quad in the mod is this edge (SmokePuffs.edge). "detail" is fine frame structure
(light-map variation minus their 7×7-pixel smoothing, in the opaque part; most billowing frame is 1): simulation is a finite
billow that dissipates and loses billows near its end, whereas a real growing cloud stays turbulent (a thermal is self-similar:
billows grow with it, Scorer 1957), and the mod selects only billowing-phase frames (FxAtlas.puffFrame). Recompute "detail"
from finished atlas frames without baking:
  uv run tools/bake_fx/pack.py --detail [<texture folder>]

Nuclear fireball surface: nukeball.bin next to the atlas (textures/fx; read by client/nuclear/NukeBallSurface):
each baked frame has a directional map from the ball center (octahedral, {BALL_N}², y axis up); each texel holds pattern p:
how many times the surface temperature excess here exceeds the mean. The surface is what is seen looking radially at the
ball from outside: ball gas emission by Kirchhoff's law along the ray toward the center (attenuation ∝ gas fraction,
{BALL_TAU} optical depths per pure-gas radius — ⚠ estimate: hot air is opaque, G&D 2.117), brightness by Wien at 555 nm,
converted back to temperature. Gas temperature is a mixture: T = T_air + c·ΔT, with ΔT from the mod at 15 kt at this instant
(FireballModel.temperature; instant by the frame rise path, CloudModel at 15 kt). Frame pattern is normalized by
Stefan–Boltzmann: mean p⁴ over the sphere is 1; the ball radiates model power (brightness and color remain its blackbody;
air relative to the ball is negligible: T_air⁴ relative to T⁴ is up to ~1% by the end of roll-up).
Format (little-endian): int32 N, int32 frames, float32 frame rise path in ball radii × frames, uint16 p/{BALL_P}·65535
× frames × N × N (row is v, u within a row).
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
# fireball: frames of everything and burning; motion vector maps - 128², offset up to {FIREBALL_FLOW} frame pixels 256
FIREBALL_FRAMES = 48
BURN_FRAMES = 32
FIREBALL_FLOW_TILE = 128
FIREBALL_FLOW = 16.0
# bottom of the ball frame: the simulation column from the ground under the ball - no further than the radius of the ball from its middle (below - the ground
#  for any weapon: the middle of the model ball - 0,45–1,5 radius above it), to the edge - smoothly on the last {DOWN_SOFT}
DOWN, DOWN_SOFT = 1.5, 0.3
# frame of the ball frame - no less than so many radii of the ball (as in the previous frame, FarSprites.FIREBALL)
FIREBALL = 1.3
# edge of the ball's substance - in density: thinner than this opacity haze fade to nothing smoothly
EDGE_ALPHA = 0.05
FLOW = 32.0
DRAPER = 798.0
C2, LAMBDA, T_REF = 1.4388e-2, 555e-9, 1490.0
SIGMA = 5.670374e-8
# ball frame exposure (film_exposure): fraction of the brightness of the burning ball and where it falls on the curve
FILM_KEY, FILM_TOP = 95, 0.60
DARK_START = 0.02


def load(path):
    return np.load(path).astype(np.float64)


def crop(img, cx, cy, half, tile=TILE):
    """A square with a center (cx, cy) and half-size half pixels → tile×tile (empty beyond the edge), area."""
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
    """Direct alpha, 8 bit: color - already on the screen (with gamma)."""
    a = np.clip(alpha, 0, 1)
    arr = np.concatenate([np.clip(rgb, 0, 1), a[..., None]], -1)
    Image.fromarray(np.clip(np.nan_to_num(arr) * 255 + 0.5, 0, 255).astype(np.uint8), "RGBA").save(path, optimize=True)


def film(x):
    """Film curve by channels (ACES, approximation Narkowicz 2016, CC0, selected from CC0/MIT from the author): the toe keeps the soot dark, the shoulder keeps the core without a cut
     flat white; the hot goes through the channels into yellow and white, like the film and the eye."""
    x = np.maximum(x, 0)
    return np.clip(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14), 0, 1)


def film_exposure(burning):
    """Exposure to the ball is a presentation, not physics: the camera in the cinema places it on the fire, and not on the white wall. The brightness of the ball
     against a white wall is dozens of times (black body ~1700 K against daylight), and the frame against the white wall cut the entire
     ball into an even yellow disk - no folds, tongues or soot are visible. Here the {FILM_KEY}-th share of the brightness of the burning ball (on
     opaque, in all frames of combustion) stands in the shoulder of the curve, on {FILM_TOP} white: brighter - whitens, dimmer in
    10–100 times (cooled folds, soot) - dark red and black. One exposure for all frames: as it cools down it gets darker,
     just like in life. Only over white - smooth discoloration of the core (connection of channels) so that the zero blue
     of the black body does not leave overexposure lemon-yellow. The light of the ball in the model is still a black body, but the light around it (flare, veil, glow, light on the smoke) is not
     brighter than the fire of the frame on the screen (“fire”, FxAtlas.fireLightAt) - and it follows this exposure: ~0,37 white, and not ~0,87."""
    lit = np.concatenate([d[2][d[1] > 0.5] for d in burning]) if burning else np.ones(1)
    key = float(np.percentile(lit, FILM_KEY)) if lit.size else 1.0
    # film(x) = FILM_TOP - solution to the quadratic equation of a curve
    a, b, c, d, e = 2.51, 0.03, 2.43, 0.59, 0.14
    t = FILM_TOP
    qa, qb, qc = a - t * c, b - t * d, -t * e
    x = (-qb + math.sqrt(qb * qb - 4 * qa * qc)) / (2 * qa)
    return x / max(key, 1e-9)


def kelvin_of(lum):
    """Flame temperature by brightness against the black body 1490 K (Vin on 555 nm)."""
    lum = max(lum, 1e-12)
    inv = 1 / T_REF - math.log(lum) * LAMBDA / C2
    return 1 / inv if inv > 0 else 1e5


def smooth(values, sigma):
    """Smooth across frames (Gaussian, edges - reflection): the size and middle of the frame without shaking from frame to frame."""
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
        # the brightest flame in the frame is the top tenth brightest for the opaque
        top = float(np.percentile(lit, 90)) if lit.size else 0.0
        # surface radiation: σT⁴ pixel by its brightness (Vin), average by opaque
        sep = float(np.mean(SIGMA * np.vectorize(kelvin_of)(lit) ** 4)) if lit.size else 0.0
        data.append((rgb, alpha, lum, top, sep))
    times = [(int(f[2:5]) - 1) / fps for f in files]
    # the finished simulation (bake.py fireball-vdb) begins with frames before ignition: empty, then a cloud of vapor and smoke from the source,
    #  under which the flame flares up. The fuel ball according to CCPS glows completely from the first moment: time - from the first frame, where
    #  the flame of the frame is no less than half the brightest and darkest (dimmer than a tenth of a white wall) - no more than {DARK_START}
    # opaque
    peak = max(d[3] for d in data)

    def dark(d):
        m = d[1] > 0.5
        return float((d[2][m] < 0.1).mean()) if m.any() else 1.0

    first = next((i for i, d in enumerate(data) if d[3] >= 0.5 * peak and dark(d) <= DARK_START), 0)
    data, times, files = data[first:], [t - times[first] for t in times[first:]], files[first:]
    burn_s -= (int(files[0][2:5]) - 1) / fps
    kel = [kelvin_of(d[3]) if d[3] > 0 else 0 for d in data]
    # burning: {BURN_FRAMES} frames are equal in time from the flash to the end of burning (the last one is the end itself); between them in the
    #  game there is a mixture along the motion vectors, and there should be so many frames that the mixture is movement and not an influx of
    #  still pictures (16 frames on 2–3 s burning - ~0,2 s per frame: the silhouette was standing, review #426). Frames are different
    #  renders, not repeats: otherwise the game will again have a freeze frame
    burn_idx = [int(np.argmin([abs(t - i * burn_s / (BURN_FRAMES - 1)) for t in times])) for i in range(BURN_FRAMES)]
    if len(set(burn_idx)) < BURN_FRAMES:
        raise ValueError(f"combustion needs {BURN_FRAMES} different renders (bake.py --frames without gaps), and not repetitions")
    end = burn_idx[-1]
    # cooling: frames equal in simulation time from the end of combustion to the last, where the flame is still glowing - movement
    #  without jerks (mixture along the motion vectors); the frame has the color temperature of its flame (not higher than the previous frame):
    # mod takes a cooling frame based on the temperature of the ball of its model
    last = max((i for i in range(end, len(data)) if kel[i] > 0), default=end)
    n_cool = FIREBALL_FRAMES - BURN_FRAMES
    cool = [int(round(end + (last - end) * j / n_cool)) for j in range(1, n_cool + 1)]
    if len(set(cool)) < n_cool or cool[0] <= end:
        raise ValueError(f"needs cooling {n_cool} different renderings after the end of combustion (bake.py --frames before the flame goes out)")
    picks = burn_idx + cool
    # the temperature of the cooling frames is smooth in time and straight down: the brightness of the flame of the frame is noisy, and the mod goes by frame
    #  by temperature, and the site in the table would scroll through several frames in an instant
    cooled = smooth([kel[end]] + [kel[i] for i in cool], 1.0)
    for j, i in enumerate(cool):
        kel[i] = min(float(cooled[j + 1]), kel[picks[BURN_FRAMES + j - 1]] - 1.0)
    exposure = film_exposure([data[i] for i in burn_idx])
    # Framing is one measure for the entire animation, without jumps. Scale: for combustion frames, the radius of the ball (a circle of area
    # , opaque, smooth across the frames) is the radius of the model ball; cooling down, the simulation ball melts into smoke and there is less opaque
    # , and the measure is that at the end of combustion (soot grows in the frame itself, as in the simulation). The middle is the middle of
    # , which is opaque in combustion, then beyond the middle of the whole substance. The frame is the entire visible substance of the frame, and not the circle of the ball:
    #  the circle cut off the cooling soot with a disk (review #426); the frame is not less than {FIREBALL} the radius of the ball and does not narrow.
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
        # presentation: below the shoulder the fire is orange, not all yellow. Only over white - channel connection:
        #  camera/eye loses overexposure saturation, hot cores turn white even with zero blue in the render.
        exposed = rgb * exposure
        disp = film(exposed)
        white = 0.35 * (1 - np.exp(-np.maximum(exposed.max(axis=-1) - 1, 0)))
        disp += (1 - disp) * white[..., None]
        # Under ONE, ONE_MINUS_SRC_ALPHA the leaf color is multiplied by alpha. A bright thin flame should not be a translucent yellow film on top of the sky/soot: its alpha is not lower than the screen brightness.
        a = np.maximum(alpha, disp ** (1 / 2.2) @ np.array([0.2126, 0.7152, 0.0722]))
        # edge - in density: a rare haze is thinner than {EDGE_ALPHA} opacity fade to no smoothly (there is also the rest of the noise
        # render), and not a circle along the radius of the frame
        u = np.clip(a / EDGE_ALPHA, 0, 1)
        a = a * u * u * (3 - 2 * u)
        rows = np.arange(a.shape[0])[:, None]
        u = np.clip((cys[k] + DOWN * scale[k] - rows) / (DOWN_SOFT * scale[k]), 0, 1)
        a = a * u * u * (3 - 2 * u)
        shown.append((disp, a))
    # frame: the entire frame substance (opaque than 1/255) around the middle, with a margin not narrower than the previous and {FIREBALL} ball radii
    halves = []
    for k, (disp, a) in enumerate(shown):
        ys, xs = np.nonzero(a > 1 / 255)
        extent = max(float(np.max(np.abs(xs - cxs[k]))), float(np.max(np.abs(ys - cys[k])))) if xs.size else 0.0
        w = max(1.03 * extent + 2, FIREBALL * scale[k], halves[-1] if halves else 0)
        halves.append(w)
    tiles = []
    for k, (disp, a) in enumerate(shown):
        # frame - with multiplied alpha: when reduced, the hot color does not spread into almost empty soot
        tile = crop(np.concatenate([disp * a[..., None], a[..., None]], -1), cxs[k], cys[k], halves[k], FIREBALL_TILE)
        pa = np.clip(tile[..., 3], 0, 1)
        # frame edge: two pixels to zero (the substance does not reach it; insurance against sampling an adjacent place on the sheet)
        side = np.minimum(np.arange(FIREBALL_TILE), np.arange(FIREBALL_TILE)[::-1])
        pa = pa * np.clip((np.minimum(side[None, :], side[:, None]) + 0.5) / 2, 0, 1)
        col = np.clip(tile[..., :3], 0, None) / np.maximum(tile[..., 3:4], 1e-6)
        # Cubic sampling, when enlarged, gives small negative emissions at a sharp edge: before the gamma, not after NaN.
        col = np.clip(col, 0, 1)
        screen = col ** (1 / 2.2)
        # The border is also part of the fire: as it thins out, it turns red and darkens, rather than turning yellow against a black/blue background.
        thin = np.clip(pa / 0.85, 0, 1)
        screen[..., 1] *= thin
        screen[..., 2] *= thin * thin
        luminance = screen @ np.array([0.2126, 0.7152, 0.0722])
        screen *= np.minimum(1, pa / np.maximum(luminance, 1e-9))[..., None]
        tiles.append((screen, pa))
        save_rgba(screen, pa, os.path.join(out, f"fireball_{k:02d}.png"))
    # motion vectors to the next frame (like billows of smoke): Farneback across the frame on a gray background - both flame and soot; the mixture of
    #  frames in the shader goes along them, and the fire flows instead of appearing double. Different frames of adjacent frames are also movement
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
        # fire on the screen - to the extent of the screen (degree 1/2,2), like the previous ball (gen_particles: ball.json)
        lit = screen * pa[..., None]
        fire.append(round(float((lit @ np.array([0.2126, 0.7152, 0.0722]))[disc].mean()), 4))
        burn.append(round(times[i] / burn_s, 4))
        kelv.append(round(kel[i], 1))
    half = [round(float(halves[k] / scale[k]), 4) for k in range(len(picks))]
    # surface radiation during combustion according to the color temperature of combustion frames - against CCPS 200–350 kW/m²
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
    """Fine frame structure billows: tiles - (opacity, light maps rgb a and b) frames; at the most swirling one - 1."""
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
    """“detail” in puff6.json and spray6.json - by sheet frames (maps - degree 1/2,2, back to linear)."""
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
    """billows (prefix is the name of the frames on the sheet). like - puff6.json smoke: splash maps are reduced to its “diffuse” (the layer has one
     map measure for all billows, FxSixLight), their own measure is in “own”."""
    meta = json.load(open(os.path.join(src, "meta.json")))
    picks = meta["picks"][-FRAMES:]
    maps = []
    for i in picks:
        m = {s: load(os.path.join(src, f"{s}_{i:03d}.npy")) for s in SIDES_A + SIDES_B}
        alpha = m["front"][..., 3]
        light = {s: m[s][..., :3].mean(-1) / np.maximum(m[s][..., 3], 1e-4) for s in m}
        maps.append((alpha, light))
    # cards - in the same measure for all frames and sides: the brightest (back edge clearance) - 1, without cutting
    lit = np.concatenate([l[x][a > 0.05].ravel() for a, l in maps for x in SIDES_A + SIDES_B])
    norm = 1 / max(np.percentile(lit, 99.5), 1e-6)
    # what the shader normalizes the maps to (white wall): the average light of billows on its opacity in the light from the front and the average on
    #  six sides - a typical place of billows in the light of the sky comes out as a thick layer of its substance (color - from the mod)
    num_f = sum(float(np.sum(l["front"] * a)) for a, l in maps)
    num_d = sum(float(np.sum(np.mean([l[x] for x in SIDES_A + SIDES_B], 0) * a)) for a, l in maps)
    den = sum(float(np.sum(a * a)) for a, l in maps)
    front, diffuse = num_f / den * norm, num_d / den * norm
    own = diffuse
    if like is not None:
        norm *= like["diffuse"] / diffuse
        front, diffuse = front * like["diffuse"] / diffuse, like["diffuse"]
    # render of the finished simulation (bake.py puff-vdb) - with your camera per frame: height - according to the scene (voxels per pixel)
    scale = meta.get("scale", [1.0] * len(meta["picks"]))[-FRAMES:]
    tiles, growth, r0 = [], [], None
    for (alpha, light), k in zip(maps, scale):
        cx, cy, _, extent = centre(alpha, 0.05)
        half = max(extent * 1.04, 8)
        r0 = r0 or half * k
        growth.append(round(float(half * k / r0), 4))
        a = crop(alpha, cx, cy, half)
        rgb = {name: crop(np.stack([np.clip(light[s] * norm, 0, 1) for s in sides], -1), cx, cy, half) for name, sides in (("a", SIDES_A), ("b", SIDES_B))}
        # edge of the frame - to zero (the last 8 % of the radius): the frame is along the edge of the substance 0,05, and on thick billows the cut at the edge of the square
        #  was a straight line
        yy, xx = np.mgrid[0:TILE, 0:TILE]
        rr = np.hypot(xx - TILE / 2 + 0.5, yy - TILE / 2 + 0.5) / (TILE / 2)
        a = a * np.clip((1 - rr) / 0.08, 0, 1)
        tiles.append((a, rgb))
    # growth - does not decrease: the mod takes a frame according to the growth of its cloud (FxAtlas.puffFrame - reverse search according to an increasing table),
    #  and the edge of an uneven cap can retreat by a percentage per frame or two
    growth = [round(float(g), 4) for g in np.maximum.accumulate(growth)]
    for k, (a, rgb) in enumerate(tiles):
        for name in ("a", "b"):
            save_rgba(np.clip(rgb[name], 0, 1) ** (1 / 2.2), a, os.path.join(out, f"{prefix}{name}_{k:02d}.png"))
        # motion vectors to the next frame: by opacity (shape), Farneback
        if k + 1 < len(tiles):
            prev = np.clip(a * 255, 0, 255).astype(np.uint8)
            nxt = np.clip(tiles[k + 1][0] * 255, 0, 255).astype(np.uint8)
            flow = cv2.calcOpticalFlowFarneback(prev, nxt, None, 0.5, 3, 15, 3, 5, 1.2, 0)
        else:
            flow = np.zeros((TILE, TILE, 2), np.float32)
        flow = np.clip(flow, -FLOW, FLOW)
        enc = np.stack([0.5 + flow[..., 0] / (2 * FLOW), 0.5 + flow[..., 1] / (2 * FLOW), np.full((TILE, TILE), 0.5)], -1)
        save_rgba(enc, np.ones((TILE, TILE)), os.path.join(out, f"{prefix}v_{k:02d}.png"))
    data = {"tile": TILE, "frames": FRAMES, "maps": "abv", "flow": FLOW, "front": round(front, 4), "diffuse": round(diffuse, 4),
            "own": round(own, 4), "growth": [float(g) for g in growth], "detail": detail(tiles)}
    if "view" in meta and prefix not in ("puff6", "spray6"):
        # billows from the finished simulation (bake.py clubs-vdb): frame - the share of the simulation’s life from the first taken to the last
        span = max(picks[-1] - picks[0], 1)
        data.update({"life": [round((i - picks[0]) / span, 4) for i in picks], "source_frames": picks, "view": meta["view"],
                     "mirror": meta.get("mirror", False), "round": meta.get("round", False)})
    with open(os.path.join(out, f"{prefix}.json"), "w", encoding="utf-8") as f:
        json.dump(data, f)
    print("puff frames", picks, "norm", round(norm, 3), "front", round(front, 3), "diffuse", round(diffuse, 3), "growth", growth)


def remeasure(dest, prefix, like):
    """Billows cards prefix on the sheet - to the extent of new smoke (like - his puff6.json): the layer has one map scale for all billows
    (FxSixLight), and billows of smoke is baked again without splashes. Light - linear (on the sheet - degree 1/2,2), the alpha is the same."""
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
# 15 kt in the air (mod): ball radius, second maximum, center rise (CloudModel: (9840 − 1,3·R)·(1 − e^(−t/121)) m)
BALL_R_M = 70.0 * 15 ** 0.4
BALL_TMAX = 0.032 * 15 ** 0.5
BALL_RISE_M, BALL_RISE_TAU = 9840 - 1.3 * BALL_R_M, 121.0
T_AIR, T_PEAK, COOLING = 288.0, 7700.0, 0.35


def octa_dirs(n):
    """The texel directions of the octahedral map are n×n (middle), the y axis is up; and texel solid angle."""
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
    """Texel solid angle: area of a spherical quadrilateral at the corners (two triangles, Van Oosterom)."""
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
    """The apparent temperature of the ball is 15 kt, K, when its center rose by rise radii after the second maximum
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
    # the gas fraction is in fractions of its average in the middle of the ball of the first frame: the noise grid Mantaflow (noise) scales the density
    #  in its own way (for the ball - up to ×2), and the attenuation and temperature are calculated from the pure gas fraction of the ball
    first = meta["picks"][0]
    c0 = np.load(os.path.join(src, f"c_{first['frame']:04d}.npy")).astype(np.float64)
    near = np.sqrt(((np.indices(c0.shape) - np.array(first["centre"])[:, None, None, None]) ** 2).sum(0)) * first["voxel_m"] < 0.6 * r
    unit = float(c0[near].mean())
    for pick in meta["picks"]:
        c = np.load(os.path.join(src, f"c_{pick['frame']:04d}.npy")).astype(np.float32) / unit
        # the ball's ladder along the grid cells would give rings along the latitude on the map: the account has no details smaller than the cells
        c = ndimage.gaussian_filter(np.clip(c, 0, None), 1.0)
        vox = pick["voxel_m"]
        # axes Blender x, y, z (z - up) → mod x, z, y
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
    assert maps.max() < BALL_P, f"pattern above {BALL_P}: {maps.max()}"
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
    # billows from other ready-made simulations (bake.py clubs-vdb --kind <name>): frames <name>6a/b/v_* and <name>6.json, maps - to a limited extent smoke
    for kind in sorted(os.listdir(bake)):
        if kind in ("fireball", "puff", "spray", "nukeball") or not os.path.exists(os.path.join(bake, kind, "meta.json")):
            continue
        if "view" in json.load(open(os.path.join(bake, kind, "meta.json"))):
            puff(os.path.join(bake, kind), dest, kind + "6", json.load(open(os.path.join(dest, "puff6.json"))))
    if os.path.isdir(os.path.join(bake, "spray")):
        puff(os.path.join(bake, "spray"), dest, "spray6", json.load(open(os.path.join(dest, "puff6.json"))))
    elif os.path.isdir(os.path.join(bake, "puff")) and os.path.exists(os.path.join(dest, "spray6.json")):
        remeasure(dest, "spray6", json.load(open(os.path.join(dest, "puff6.json"))))
