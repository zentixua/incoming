#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "pillow>=10",
#     "scipy>=1.11",
#     "soundfile>=0.12",
# ]
# ///
"""Edit a trailer from tools/trailer/record.sh recordings: shots (60 fps frames in game time) are cut and joined
according to the EDIT list on the music beat, with color grading, letterboxing and titles; audio is rebuilt from
the game sound log (the same mod files, volume by camera distance, panning and Doppler from the log) under the music.

    uv run tools/trailer/edit.py [--lang en|ru] [--draft] [--rec FOLDER …] [--out FILE]
    --lang   — on-screen words: en (default, Modrinth and YouTube) or ru
    --draft  — quick 960×540 draft (check the edit)
    --rec    — recording folder; with several, shots from later recordings (retakes) replace matching names; a shot
               with game text in frame comes from a recording in the video language (en_us or ru_ru, record.sh: INCOMING_LANG)
    --jobs   — render processes (default min(cores, 6))
    output: dist/incoming-trailer.mp4 (H.264, 60 fps, AAC), …-lite.mp4 (up to 4 Mbit/s, for messengers)
    and …-credits.txt — music and sound attribution lines for the video description

Music and fonts are downloaded once to tools/.trailer-cache (sha256 verification). Python packages are in the script
block above (PEP 723, uv run installs them); ffmpeg is the system version or from the imageio-ffmpeg package.

1080p memory (measured): render process ~0.3 GB and main process ~0.3 GB throughout the edit (no more than
2×jobs frames queued); x264 slow encoder 2.5 GB on 4 cores, increasing with thread count; --jobs 6 peaks at ~5–6 GB.
"""
import argparse
import bisect
import collections
import hashlib
import json
import math
import multiprocessing
import os
import shutil
import subprocess
import sys
import urllib.request
from dataclasses import dataclass, field

import numpy as np
import soundfile as sf
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy.signal import butter, resample_poly, sosfilt

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from paths import DIST, MOD, ROOT  # noqa: E402

REC = os.path.join(MOD, "run", "scenario", "trailer")
CACHE = os.path.join(ROOT, "tools", ".trailer-cache")
MOD_ASSETS = os.path.join(MOD, "src", "main", "resources", "assets")
FPS = 60
SR = 48000
TPS = 20

# ---------------------------------------------------------------- music and fonts (licenses in the titles and CREDITS)

MUSIC = {
    "url": "https://archive.org/download/Kevin-MacLeod_Impact_2014_FullAlbum/Impact%2FKevin%20MacLeod%20-%2006%20-%20Prelude.mp3",
    "sha256": "fe69ca096bbf6b2c3fe7db94049dbf786f3d9c2bdfd3a3d62ed85c1463baacef",
    "file": "impact-prelude.mp3",
    # downbeat of the loud section: 96.02 s; bar length 3 s (80 bpm, 4 beats of 0.75 s)
    "drop": 96.02,
    "bar": 3.0,
    "credit": "\u201cImpact Prelude\u201d by Kevin MacLeod (incompetech.com), CC BY 4.0",
}
FONTS = {
    "title": ("https://raw.githubusercontent.com/google/fonts/main/ofl/russoone/RussoOne-Regular.ttf",
              "bc0abcc660bd8b7ad3000ecb2898a27c58a29a50f7ec81652fa12e75148d09df", "RussoOne-Regular.ttf"),
    "text": ("https://raw.githubusercontent.com/google/fonts/main/ofl/oswald/Oswald%5Bwght%5D.ttf",
             "5b38c246e255a12f5712d640d56bcced0472466fc68983d2d0410ec0457c2817", "Oswald.ttf"),
}


def fetch(url, sha256, name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        print(f"downloading {name}")
        req = urllib.request.Request(url, headers={"User-Agent": "zentixua/incoming trailer"})
        with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(path + ".part", path)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if digest != sha256:
        raise SystemExit(f"{name}: sha256 {digest} does not match — remove the file from {CACHE}")
    return path


def ffmpeg():
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        raise SystemExit("ffmpeg is required (apt install ffmpeg or pip install imageio-ffmpeg)")


# ---------------------------------------------------------------- recording

@dataclass
class Shot:
    name: str
    speed: float
    frames: dict = field(default_factory=dict)      # k → PNG path
    cams: dict = field(default_factory=dict)        # k → (x, y, z, yaw, pitch)
    sounds: list = field(default_factory=list)
    updates: dict = field(default_factory=dict)     # id → [(t, x, y, z, volume, pitch)]
    stops: dict = field(default_factory=dict)       # id → t
    marks: list = field(default_factory=list)       # (t, what)
    count: int = 0
    hud: bool = False       # game interface text in frame
    lang: str = "ru_ru"     # game language when recorded (recordings before this field are ru_ru only)

    def sec(self, ticks):
        """Shot time in video seconds based on game ticks."""
        return ticks / (TPS * self.speed)

    def frame(self, k):
        """Frame k or the nearest earlier captured frame (if the client skipped it)."""
        keys = self.keys
        i = bisect.bisect_right(keys, k) - 1
        return self.frames[keys[max(i, 0)]]

    def cam(self, k):
        keys = self.keys
        i = min(max(bisect.bisect_right(keys, k) - 1, 0), len(keys) - 1)
        return self.cams[keys[i]]

    @property
    def duration(self):
        return self.count / FPS


def load_recording(rec=REC):
    shots = {}
    with open(os.path.join(rec, "timeline.jsonl"), encoding="utf-8") as f:
        for line in f:
            try:
                e = json.loads(line)
            except json.JSONDecodeError:  # recording cut off mid-line
                continue
            kind, name = e["type"], e["shot"]
            if kind == "shot":
                shots[name] = Shot(name, e["speed"], hud=e.get("hud", False), lang=e.get("lang", "ru_ru"))
                continue
            s = shots[name]
            if kind == "frame":
                path = os.path.join(rec, "frames", name, f"{e['k']:05d}.png")
                if os.path.exists(path):
                    s.frames[e["k"]] = path
                    s.cams[e["k"]] = tuple(e["cam"])
                s.count = max(s.count, e["k"] + 1)
            elif kind == "sound":
                s.sounds.append(e)
            elif kind == "update":
                s.updates.setdefault(e["id"], []).append((e["t"], *e["pos"], e["volume"], e["pitch"], *e.get("lp", (1, 1))))
            elif kind == "stop":
                s.stops[e["id"]] = e["t"]
            elif kind == "mark":
                # "gone" at the very start: in recordings before the fix, projectiles that disappeared between shots
                if not (e["what"].startswith("gone:") and e["t"] <= 0):
                    s.marks.append((e["t"], e["what"]))
    for s in shots.values():
        s.keys = sorted(s.frames)
    return {n: s for n, s in shots.items() if s.frames}


def pick_takes(recordings, lang):
    """Take of each shot from recordings (--rec order): the last one; with game text in frame, the last one
in the video language (en_us/ru_ru), or the last available take with a warning if none matches."""
    takes = {}
    for rec in recordings:
        for name, s in rec.items():
            takes.setdefault(name, []).append(s)
    shots = {}
    for name, cands in takes.items():
        own = [s for s in cands if s.lang == lang]
        if any(s.hud for s in cands):
            if not own:
                print(f"  ! {name}: no take in {lang}, text in frame is {cands[-1].lang}")
            shots[name] = (own or cands)[-1]
        else:
            shots[name] = cands[-1]
    return shots


def anchor(shot, spec):
    """Shot moment in seconds: a number, "end", "mark:what" or "sound:name fragment" (#n is the n-th match, #-1 is
the last), with a ±offset."""
    if isinstance(spec, (int, float)):
        return float(spec)
    off = 0.0
    for sign in "+-":
        if sign in spec[1:]:
            head, tail = spec.rsplit(sign, 1)
            try:
                off = float(tail) * (1 if sign == "+" else -1)
                spec = head
                break
            except ValueError:
                pass
    if spec == "end":
        return shot.duration + off
    kind, what = spec.split(":", 1)
    nth = 1
    if "#" in what:  # "sound:blast#3" is the third match
        what, n = what.rsplit("#", 1)
        nth = int(n)
    hits = []
    if kind == "mark":
        hits = [t for t, m in shot.marks if what in m]
    elif kind == "sound":
        hits = [e["t"] for e in shot.sounds if what in e["event"]]
    if len(hits) >= abs(nth):
        return shot.sec(hits[nth - 1 if nth > 0 else nth]) + off
    print(f"  ! {shot.name}: no \"{spec}\", using the shot start")
    return off


# ---------------------------------------------------------------- edit list

@dataclass
class Clip:
    shot: str
    at: object          # starting point in the shot (seconds or an anchor, see anchor)
    dur: float          # duration in the trailer, s
    rate: float = 1.0   # playback speed (2 means twice as fast)
    xfade: float = 0.0  # dissolve from the previous shot, s
    flash: bool = False  # white flash at the cut
    zoom: tuple = (1.0, 1.0)  # zoom: scale at the start and end
    sfx: float = 1.0    # game sound volume
    frame_y: float = 0.5  # shot strip visible in the 2:1 window: 0 is the top (impact list at upper right), 0.5 is the middle
    start: float = 0.0  # populated: start in the trailer
    src: float = 0.0    # populated: start in the shot, s


@dataclass
class Black:
    dur: float
    start: float = 0.0


@dataclass
class Text:
    at: float           # from the start of the trailer, s
    dur: float
    lines: tuple
    style: str = "card"  # card: large, centered; caption: lower-left text; title: logo
    fade: float = 0.35


@dataclass
class Hit:
    """Trailer audio overlay: impact (boom), build-up (riser), cut swish (whoosh), music silence (mute)."""
    at: float
    kind: str
    dur: float = 1.0
    gain: float = 1.0


BAR = MUSIC["bar"]

# for the video description (Modrinth, YouTube): CC BY music requires the author, title, source and license
CREDITS = """Music: "Impact Prelude" by Kevin MacLeod (incompetech.com)
Licensed under Creative Commons: By Attribution 4.0 License
http://creativecommons.org/licenses/by/4.0/
Sound effects: Freesound contributors (CC0 / CC BY 4.0), full list: https://github.com/zentixua/incoming/blob/main/SOUND-CREDITS.md
Fonts: Russo One, Oswald (SIL Open Font License)

Музыка: «Impact Prelude», Kevin MacLeod (incompetech.com), лицензия Creative Commons Attribution 4.0
http://creativecommons.org/licenses/by/4.0/
Звуки: авторы Freesound (CC0 / CC BY 4.0), список: https://github.com/zentixua/incoming/blob/main/SOUND-CREDITS.md
"""

# on-screen words: English for Modrinth and YouTube, Russian for our audience (--lang ru)
GAME_LANG = {"en": "en_us", "ru": "ru_ru"}  # game language for shots with interface text in frame (record.sh: INCOMING_LANG)
WORDS = {
    "en": {"presents": "presents", "aim": "AIM", "launch": "LAUNCH", "shahed": "SHAHED-136", "missile": "CRUISE MISSILE",
           "lancet": "LANCET", "grad": "GRAD MLRS", "last": "LAST RESORT", "icbm": "ICBM",
           "platform": "Minecraft 1.21.1 · NeoForge · Create Aeronautics",
           "music": "Music: ", "sounds": "Sound effects: Freesound contributors (CC0 / CC BY), see SOUND-CREDITS.md"},
    "ru": {"presents": "представляет", "aim": "НАВЕДИ", "launch": "ЗАПУСТИ", "shahed": "ШАХЕД-136", "missile": "КРЫЛАТАЯ РАКЕТА",
           "lancet": "«ЛАНЦЕТ»", "grad": "«ГРАД»", "last": "ПОСЛЕДНИЙ ДОВОД", "icbm": "МБР",
           "platform": "Minecraft 1.21.1 · NeoForge · Create Aeronautics",
           "music": "Музыка: ", "sounds": "Звуки: авторы Freesound (CC0 / CC BY), список в SOUND-CREDITS.md"},
}


def build_edit(lang="en"):
    """
    Trailer ~2:36 set to "Impact Prelude". Quiet section: dawn, gunner, launcher; the music downbeat (drop) is
the first Shahed ignition; then cuts on bars (3 s) and half-bars, building up to the ICBM. The nuclear flash
lands on the last music hit (192 s into the track); no music afterward: blast rumble, mushroom, black rain, logo.
    """
    D = 30.0                                   # drop in the trailer
    FLASH = D + (192.0 - MUSIC["drop"]) + 0.02  # flash = last hit of the track (32 bars after the drop)
    edit = [
        Black(4.0),
        Clip("dawn", 1.2, 8.5, xfade=0.8),
        Clip("remote", 0.3, 3.0, xfade=0.4),
        Clip("operator", 0.3, 5.5, xfade=0.3),
        Clip("scope", 0.0, 4.0, xfade=0.2),
        Clip("launch_drone", "sound:launch.booster-5.0", 5.0),
        # drop: Shahed ignition and launch
        Clip("launch_drone", "sound:launch.booster-0.15", 3.0, flash=True),
        Clip("boost", 0.0, 4.5),
        Clip("cruise", 1.0, 3.0),
        # through the gunner eyes: Shaheds pass overhead toward targets, numbered target markers and dotted lines
        Clip("targets", 0.0, 3.0, frame_y=0.0),
        Clip("impact_drone", "mark:gone#-1-5.0", 6.0),
        Clip("launch_missile", "sound:launch.booster-0.4", 4.5, flash=True),
        # V camera: operator map while the missile is beyond render distance, automatically switches to onboard video — climb, dive
        Clip("missile_camera", "mark:video-1.5", 1.5, frame_y=0.0),  # map: 2:1 window aligned to the top, full rows
        # onboard video: last 150 blocks before the explosion; farther away, shaderpack atmospheric fog washes the frame white
        Clip("missile_camera", "mark:close", 3.0),
        Clip("impact_missile", "mark:gone-1.5", 6.0),
        # Lancet: catapult surge, circle over the target, dive
        Clip("loiter_launch", "sound:loiter.launch-0.5", 3.0, flash=True),
        Clip("loiter_strike", "mark:gone-3.0", 4.5),
        Clip("rocket_launch", "sound:rocket.launch-1.5", 4.5, flash=True),
        Clip("rocket_impact", "sound:blast-1.0", 4.5),
        # B-2: doors under the belly, bomb drops, turn; from the ground: underground explosion
        Clip("bomb_bay", "mark:release-2.5", 4.5, flash=True),
        Clip("bomber", "mark:gone-0.2", 4.5),
        Clip("salvo", "mark:gone-2.5", 6.0, flash=True),
        Clip("salvo_missiles", "mark:gone-1.5", 6.0),
        # explosion montage on half-bars
        Clip("impact_drone", "mark:gone#-2+0.3", 1.5, rate=0.8),
        Clip("rocket_impact", "sound:blast#6-0.2", 1.5),
        Clip("salvo", "mark:gone#4-0.3", 1.5),
        Black(4.5),
        Clip("icbm", 0.0, 5.0, flash=True),
        # over the gunner shoulder: countdown, alarm, flash, fireball, front reaches the tower
        Clip("nuke", "mark:detonation-10.0", 16.0, frame_y=0.0),
        Clip("mushroom", 0.0, 9.0, rate=1.5, xfade=0.6),
        Clip("fallout", 0.5, 6.0, xfade=1.0),
        Black(9.0),
    ]
    t = 0.0
    for c in edit:
        c.start = t
        t += c.dur
    total = t
    nuke = next(c for c in edit if isinstance(c, Clip) and c.shot == "nuke")
    assert abs(nuke.start + 10.0 - FLASH) < 0.05, "the flash in the nuke shot must land on the last music hit"
    def start(shot, n=1):
        """Start of the n-th shot with this name in the trailer (captions belong to their shots, not to numbers)."""
        return [c.start for c in edit if getattr(c, "shot", None) == shot][n - 1]

    card = next(c for c in edit if isinstance(c, Black) and c.start > D)  # black frame before the ICBM
    L = WORDS[lang]
    texts = [
        Text(0.6, 3.0, ("ZENTIX UA", L["presents"]), "card"),
        Text(start("scope") + 0.5, 2.2, (L["aim"],), "word"),
        Text(D - 2.4, 1.9, (L["launch"],), "word"),
        # weapon in one word; the picture says the rest
        Text(start("boost") + 0.3, 2.6, (L["shahed"],), "caption"),
        Text(start("launch_missile") + 0.5, 2.6, (L["missile"],), "caption"),
        Text(start("loiter_launch") + 0.3, 2.6, (L["lancet"],), "caption"),
        Text(start("rocket_launch") + 0.3, 2.6, (L["grad"],), "caption"),
        Text(start("bomb_bay") + 0.4, 2.6, ("B-2 SPIRIT",), "caption"),
        Text(card.start + 0.3, 3.6, (L["last"],), "word"),
        Text(start("icbm") + 0.5, 2.6, (L["icbm"],), "caption"),
        Text(total - 9.0 + 0.6, 5.2, ("INCOMING",), "title"),
        Text(total - 9.0 + 2.0, 3.8, (L["platform"], "github.com/zentixua/incoming"), "sub"),
        Text(total - 3.0, 3.0, (L["music"] + MUSIC["credit"], L["sounds"]), "credits"),
    ]
    hits = [
        # impact at each flash cut, swish at the montage cuts
        *[Hit(c.start, "boom", 2.0, 0.45) for c in edit if isinstance(c, Clip) and c.flash and c.start > D + 1],
        *[Hit(c.start - 0.25, "whoosh", 0.5, 0.5) for c in edit if isinstance(c, Clip) and c.dur <= 1.5],
        Hit(D - 4.0, "riser", 4.0, 0.8),
        Hit(D, "boom", 3.0, 1.0),
        Hit(card.start, "boom", 3.0, 0.9),
        Hit(FLASH - 5.0, "riser", 5.0, 0.7),
        Hit(total - 9.0 + 0.6, "boom", 5.0, 1.2),
    ]
    return edit, texts, hits, total, D, FLASH


# ---------------------------------------------------------------- picture

class Look:
    """Color: soft cinematic curve, warm highlights and cool shadows, vignette, 2:1 letterbox."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        x = np.linspace(0, 1, 256)
        curve = x + 0.18 * np.sin(2 * np.pi * (x - 0.5)) * x * (1 - x) * 2  # S-curve
        curve = np.clip(curve * 1.02, 0, 1)
        lut = np.stack([np.clip(curve * 1.04 + 0.01 * (1 - x), 0, 1),
                        np.clip(curve * 1.0, 0, 1),
                        np.clip(curve * 0.94 + 0.04 * (1 - x), 0, 1)], axis=1)
        self.lut = (lut * 255).astype(np.uint8)
        yy, xx = np.mgrid[0:h, 0:w]
        r = np.sqrt(((xx - w / 2) / (w / 2)) ** 2 + ((yy - h / 2) / (h / 2)) ** 2)
        self.vignette = np.clip(1.0 - 0.28 * np.clip(r - 0.55, 0, None) ** 1.6, 0, 1)[..., None].astype(np.float32)
        self.bar = int(round((h - w / 2.0) / 2))

    def apply(self, img):
        a = np.asarray(img, dtype=np.uint8)
        a = np.stack([self.lut[a[..., i], i] for i in range(3)], axis=-1).astype(np.float32)
        grey = a.mean(axis=2, keepdims=True)
        a = grey + (a - grey) * 1.12
        a *= self.vignette
        a = np.clip(a, 0, 255)
        if self.bar > 0:
            a[: self.bar] = 0
            a[-self.bar:] = 0
        return a


class Titles:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.fonts = {k: fetch(*v) for k, v in FONTS.items()}
        self.cache = {}

    def font(self, kind, size, weight=None):
        f = ImageFont.truetype(self.fonts[kind], size)
        if weight is not None:
            try:
                f.set_variation_by_axes([weight])
            except (OSError, AttributeError):
                pass
        return f

    def render(self, text):
        """Full-frame RGBA title image (cached)."""
        key = (text.lines, text.style)
        if key in self.cache:
            return self.cache[key]
        w, h = self.w, self.h
        s = h / 1080
        layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        if text.style in ("card", "word", "title", "sub"):
            sizes = {"card": [int(64 * s), int(40 * s)], "word": [int(150 * s)], "title": [int(190 * s)], "sub": [int(38 * s), int(30 * s)]}[text.style]
            kinds = {"card": ["title", "text"], "word": ["title"], "title": ["title"], "sub": ["text", "text"]}[text.style]
            lines = [(ln, self.font(kinds[min(i, len(kinds) - 1)], sizes[min(i, len(sizes) - 1)], 500)) for i, ln in enumerate(text.lines)]
            heights = [d.textbbox((0, 0), ln, font=f)[3] - d.textbbox((0, 0), ln, font=f)[1] for ln, f in lines]
            gap = int(22 * s)
            y = (h - sum(heights) - gap * (len(lines) - 1)) / 2
            if text.style == "sub":
                y += int(170 * s)
            for (ln, f), hh in zip(lines, heights):
                bb = d.textbbox((0, 0), ln, font=f)
                x = (w - (bb[2] - bb[0])) / 2 - bb[0]
                self._glow_text(layer, (x, y - bb[1]), ln, f, text.style)
                y += hh + gap
        elif text.style == "caption":
            f1, f2 = self.font("title", int(56 * s)), self.font("text", int(30 * s), 400)
            x, y = int(96 * s), h - int(250 * s)
            d.rectangle([x - int(22 * s), y + int(4 * s), x - int(14 * s), y + int(118 * s)], fill=(255, 150, 40, 255))
            self._glow_text(layer, (x, y), text.lines[0], f1, "caption")
            if len(text.lines) > 1:
                d.text((x + 2, y + int(76 * s)), text.lines[1].upper(), font=f2, fill=(235, 235, 235, 235))
        elif text.style == "credits":
            f = self.font("text", int(24 * s), 400)
            y = h - int(150 * s)
            for ln in text.lines:
                bb = d.textbbox((0, 0), ln, font=f)
                d.text(((w - (bb[2] - bb[0])) / 2, y), ln, font=f, fill=(200, 200, 200, 230))
                y += int(36 * s)
        self.cache[key] = layer
        return layer

    def _glow_text(self, layer, xy, line, font, style):
        glow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        warm = style in ("title", "word")
        ImageDraw.Draw(glow).text(xy, line, font=font, fill=(255, 120, 30, 200) if warm else (0, 0, 0, 200))
        glow = glow.filter(ImageFilter.GaussianBlur(radius=max(4, font.size // 9)))
        layer.alpha_composite(glow)
        ImageDraw.Draw(layer).text(xy, line, font=font, fill=(255, 244, 228, 255) if warm else (255, 255, 255, 255))


# render worker state
_W = {}


def _init_worker(edit, texts, shots, size):
    _W.update(edit=edit, texts=texts, shots=shots, size=size, look=Look(*size), titles=Titles(*size))


def _clip_at(t):
    for c in reversed(_W["edit"]):
        if c.start <= t + 1e-9:
            return c
    return _W["edit"][0]


def _source(c, t):
    """Shot frame at trailer time t (with zoom), float32 HxWx3."""
    w, h = _W["size"]
    if isinstance(c, Black):
        return np.zeros((h, w, 3), np.float32)
    s = _W["shots"][c.shot]
    k = int(round((c.src + (t - c.start) * c.rate) * FPS))
    img = Image.open(s.frame(max(0, min(k, s.count - 1)))).convert("RGB")
    if img.size != (w, h):
        img = img.resize((w, h), Image.LANCZOS)
    z0, z1 = c.zoom
    z = z0 + (z1 - z0) * min(1.0, max(0.0, (t - c.start) / c.dur))
    if abs(z - 1) > 1e-3:
        cw, ch = w / z, h / z
        img = img.resize((w, h), Image.BICUBIC, box=((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2))
    bar = _W["look"].bar
    if bar > 0 and c.frame_y != 0.5:
        # 2:1 window shifts within the shot so letterboxing does not crop the interface near the edge
        top = int(round(2 * bar * c.frame_y))
        window = img.crop((0, top, w, top + h - 2 * bar))
        img = Image.new("RGB", (w, h))
        img.paste(window, (0, bar))
    return _W["look"].apply(img)


def _render(i):
    t = i / FPS
    c = _clip_at(t)
    a = _source(c, t)
    if isinstance(c, Clip):
        idx = _W["edit"].index(c)
        if c.xfade > 0 and t - c.start < c.xfade and idx > 0:
            p = (t - c.start) / c.xfade
            a = a * p + _source(_W["edit"][idx - 1], t) * (1 - p)
        if c.flash and t - c.start < 0.35:
            a = a + (255 - a) * (1 - (t - c.start) / 0.35) ** 2
    for tx in _W["texts"]:
        if tx.at <= t < tx.at + tx.dur:
            fade = min(1.0, (t - tx.at) / tx.fade, (tx.at + tx.dur - t) / tx.fade)
            layer = np.asarray(_W["titles"].render(tx), dtype=np.float32)
            alpha = layer[..., 3:4] / 255 * fade
            a = a * (1 - alpha) + layer[..., :3] * alpha
    return np.clip(a, 0, 255).astype(np.uint8).tobytes()


def render_frames(n, jobs, init):
    """Trailer frames in order. No more than 2×jobs frames running or queued: Pool.imap does not wait for the consumer
and accumulates finished frames (6 MB at 1080p) while the x264 encoder is slower than rendering; 16 processes would use over 8 GB."""
    with multiprocessing.Pool(jobs, _init_worker, init) as pool:
        pending = collections.deque()
        for i in range(n):
            pending.append(pool.apply_async(_render, (i,)))
            if len(pending) >= 2 * jobs:
                yield pending.popleft().get()
        while pending:
            yield pending.popleft().get()


# ---------------------------------------------------------------- sound

class SoundBank:
    def __init__(self):
        props = os.path.join(MOD, "build", "moddev", "minecraft_assets.properties")
        conf = dict(line.strip().split("=", 1) for line in open(props) if "=" in line and not line.startswith("#"))
        root = conf["assets_root"]
        index = json.load(open(os.path.join(root, "indexes", conf["asset_index"] + ".json")))["objects"]
        self.vanilla = {k: os.path.join(root, "objects", v["hash"][:2], v["hash"]) for k, v in index.items()}
        self.cache = {}

    def load(self, loc):
        """Sound at the file path from the log ("namespace:sounds/….ogg") → mono float32 at SR."""
        if loc in self.cache:
            return self.cache[loc]
        ns, path = loc.split(":", 1)
        f = os.path.join(MOD_ASSETS, ns, path)
        if not os.path.exists(f):
            f = self.vanilla.get(f"{ns}/{path}")
        if f is None or not os.path.exists(f):
            self.cache[loc] = None
            return None
        x, sr = sf.read(f, dtype="float32", always_2d=True)
        x = x.mean(axis=1)
        if sr != SR:
            g = math.gcd(sr, SR)
            x = resample_poly(x, SR // g, sr // g).astype(np.float32)
        self.cache[loc] = x
        return x


def _gain_pan(cam, pos, volume, rng, relative, linear):
    """Volume and panning as in the engine: linear attenuation up to range; without attenuation (mod motors, wave impact),
volume is used as is, but direction is accurate."""
    x, y, z, yaw, _ = cam
    if relative:
        return min(volume, 1.0), 0.0, 0.0
    d = np.array(pos) - np.array([x, y, z])
    dist = float(np.linalg.norm(d))
    g = min(volume, 1.0) * (max(0.0, 1.0 - dist / max(rng, 1e-3)) if linear else 1.0)
    yr = math.radians(yaw)
    right = np.array([-math.cos(yr), 0.0, -math.sin(yr)])
    pan = float(np.dot(d, right) / dist) if dist > 1e-3 else 0.0
    return g, pan, dist


# world ambience that only gets in the way in the trailer (cave rumble, underground trial spawner)
SKIP_SOUNDS = ("minecraft:ambient.", "minecraft:block.trial_spawner", "minecraft:music")


def render_sfx(edit, shots, total, bank):
    """Game audio following the edit: every shot sound included in a cut, with camera-based volume and panning."""
    out = np.zeros((int(total * SR) + SR, 2), np.float32)
    for c in edit:
        if not isinstance(c, Clip) or c.sfx <= 0:
            continue
        s = shots[c.shot]
        lo, hi = c.src, c.src + c.dur * c.rate
        slow = s.speed if s.speed < 1 else 1.0
        stretch = slow ** -0.35  # slowed shot: lower-pitched, longer sound, as in film
        for e in s.sounds:
            if e["event"].startswith(SKIP_SOUNDS):
                continue
            data = bank.load(e["file"])
            if data is None:
                continue
            t0 = s.sec(e["t"])
            stop = s.sec(s.stops[e["id"]]) if e["id"] in s.stops else None
            base_pitch = max(0.5, min(2.0, e["pitch"])) / stretch
            length = (stop - t0) if stop is not None else len(data) / SR / base_pitch * (1 if not e["loop"] else 1e9)
            if t0 + length < lo - 0.05 or t0 > hi:
                continue
            seg_start = max(t0, lo)
            seg_end = min(hi, t0 + length)
            if seg_end <= seg_start:
                continue
            n = int((seg_end - seg_start) / c.rate * SR)
            if n <= 0:
                continue
            ts = seg_start + np.arange(n) / SR * c.rate          # shot time for each sample
            # volume, pitch, position by frame (the engine logs them for changing sounds)
            ups = s.updates.get(e["id"], [])
            ut = np.array([s.sec(u[0]) for u in ups]) if ups else None
            kf = np.arange(int(seg_start * FPS), int(seg_end * FPS) + 2)
            gains, pans, pitches, dists, highs = [], [], [], [], []
            for k in kf:
                tk = k / FPS
                if ut is not None and len(ut):
                    j = min(max(np.searchsorted(ut, tk) - 1, 0), len(ups) - 1)
                    _, px, py, pz, vol, pit, lpg, lph = ups[j]
                    pos, pitch = (px, py, pz), pit
                else:
                    pos, vol, pitch = e["pos"], e["volume"], e["pitch"]
                    lpg, lph = e.get("lp", (1, 1))
                g, p, dist = _gain_pan(s.cam(k), pos, vol, e["range"], e["relative"], e["linear"])
                gains.append(g * lpg)
                highs.append(lph)
                pans.append(p)
                pitches.append(max(0.5, min(2.0, pitch)) / stretch)
                dists.append(dist)
            ft = kf / FPS
            g = np.interp(ts, ft, gains)
            pan = np.interp(ts, ft, pans) * 0.75
            pr = np.interp(ts, ft, pitches)
            # position in the file: pitch is read speed; sound runs at its own pace even if the shot is sped up
            pos_samples = (seg_start - t0) * SR * base_pitch + np.cumsum(pr) - pr[0]
            if e["loop"]:
                pos_samples = np.mod(pos_samples, len(data) - 1)
            valid = pos_samples < len(data) - 1
            y = np.interp(np.where(valid, pos_samples, 0), np.arange(len(data)), data) * valid
            dist = float(np.median(dists)) if dists else 0.0
            hf = float(np.median(highs)) if highs else 1.0
            if hf < 0.98:  # mod filter (air, hill or house in between): treble fraction → cutoff
                y = sosfilt(butter(2, max(500.0, 20000.0 * hf ** 1.6), "low", fs=SR, output="sos"), y)
            elif dist > 40 and not e["event"].startswith("incoming:"):  # distant vanilla sounds: air attenuates treble
                cutoff = max(900.0, 16000.0 / (1 + dist / 120))
                y = sosfilt(butter(2, cutoff, "low", fs=SR, output="sos"), y)
            y = y * g * c.sfx
            # cut: short fade-in and fade-out to avoid clicks
            ramp = min(n, int(0.012 * SR))
            if ramp > 1:
                y[:ramp] *= np.linspace(0, 1, ramp)
                y[-ramp:] *= np.linspace(1, 0, ramp)
            left = np.sqrt(np.clip(0.5 - pan / 2, 0, 1))
            right = np.sqrt(np.clip(0.5 + pan / 2, 0, 1))
            i0 = int((c.start + (seg_start - lo) / c.rate) * SR)
            out[i0:i0 + n, 0] += y * left
            out[i0:i0 + n, 1] += y * right
    return out


def synth_hit(kind, dur, rng):
    """Edit sounds: low impact (boom), rising noise (riser), swish (whoosh)."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    if kind == "boom":
        f = 55 * np.exp(-t * 1.6) + 28
        body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 1.3)
        noise = sosfilt(butter(2, 180, "low", fs=SR, output="sos"), rng.standard_normal(n)) * np.exp(-t * 3) * 0.6
        y = np.tanh((body + noise) * 1.8)
        return np.stack([y, y], 1)
    if kind == "riser":
        noise = rng.standard_normal(n)
        out = np.zeros(n)
        for a, b in zip(range(0, n, 2048), range(2048, n + 2048, 2048)):
            fc = 300 + 6000 * (a / n) ** 2
            seg = sosfilt(butter(2, [fc, min(fc * 2.5, SR / 2 - 100)], "band", fs=SR, output="sos"), noise[a:b])
            out[a:b] = seg
        env = (t / dur) ** 2.2
        y = out * env * 0.8
        return np.stack([y * 0.9, y], 1)
    if kind == "whoosh":
        noise = sosfilt(butter(2, [400, 3000], "band", fs=SR, output="sos"), rng.standard_normal(n))
        env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 2
        y = noise * env * 0.6
        return np.stack([y, y], 1)
    raise ValueError(kind)


def music_track(total, drop_at):
    """Music: track downbeat matches drop_at; fade in at the start, fade out at the end."""
    path = fetch(MUSIC["url"], MUSIC["sha256"], MUSIC["file"])
    wav = os.path.join(CACHE, "impact-prelude.wav")
    if not os.path.exists(wav):
        subprocess.run([ffmpeg(), "-loglevel", "error", "-y", "-i", path, "-ar", str(SR), "-ac", "2", wav], check=True)
    x, _ = sf.read(wav, dtype="float32", always_2d=True)
    offset = MUSIC["drop"] - drop_at            # track time at the start of the trailer
    out = np.zeros((int(total * SR) + SR, 2), np.float32)
    a = int(max(0, offset) * SR)
    b = int(-min(0, offset) * SR)
    n = min(len(x) - a, len(out) - b)
    out[b:b + n] = x[a:a + n]
    fade = int(3.0 * SR)
    out[b:b + fade] *= np.linspace(0, 1, fade)[:, None]
    return out


def mix(edit, texts, hits, shots, total, drop_at, flash):
    bank = SoundBank()
    sfx = render_sfx(edit, shots, total, bank)
    music = music_track(total, drop_at)
    rng = np.random.default_rng(20260928)
    extra = np.zeros_like(music)
    for h in hits:
        i = int(h.at * SR)
        if h.kind == "mute":
            continue
        y = synth_hit(h.kind, h.dur, rng) * h.gain
        if h.kind == "riser":  # build-up ends at the impact
            i = max(0, i - len(y))
        extra[i:i + len(y)] += y[: len(extra) - i]
    # music silence after the nuclear flash (the last track hit has finished): only game rumble and wind
    i = int(flash * SR)
    k = int(0.25 * SR)
    music[i:i + k] *= np.linspace(1, 0, k)[:, None]
    music[i + k:] = 0
    out = music * 0.55 + sfx * 0.9 + extra * 0.6
    # soft limiter
    peak = np.max(np.abs(out)) + 1e-9
    out = np.tanh(out * (1.4 / max(1.0, peak * 0.7))) * 0.95
    return out


# ---------------------------------------------------------------- assembly

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--draft", action="store_true")
    ap.add_argument("--lang", choices=sorted(WORDS), default="en", help="on-screen words: en for Modrinth and YouTube, ru for our audience")
    ap.add_argument("--out", help="file (default dist/incoming-trailer.mp4; …-ru.mp4 for ru)")
    ap.add_argument("--rec", action="append", help="recording folder (default mod/run/scenario/trailer); multiple allowed — "
                    "shots from later recordings (retakes) replace matching names")
    ap.add_argument("--preset", default="slow", help="x264 preset for the final version (slow for quality, medium for speed)")
    ap.add_argument("--jobs", type=int, default=min(os.cpu_count() or 1, 6),
                    help="render processes (default no more than 6: each ~0.3 GB at 1080p)")
    args = ap.parse_args()
    if args.out is None:
        args.out = os.path.join(DIST, "incoming-trailer.mp4" if args.lang == "en" else f"incoming-trailer-{args.lang}.mp4")
    shots = pick_takes([load_recording(rec) for rec in args.rec or [REC]], GAME_LANG[args.lang])
    for v in FONTS.values():  # download before starting workers
        fetch(*v)
    print("shots:", ", ".join(f"{s.name} {s.duration:.1f}s" for s in shots.values()))
    edit, texts, hits, total, drop_at, flash = build_edit(args.lang)
    missing = {c.shot for c in edit if isinstance(c, Clip) and c.shot not in shots}
    if missing:
        # missing shot: give its time to the previous shot (slower playback), without black gaps
        print("  ! missing shots:", ", ".join(sorted(missing)), "— their time goes to neighboring shots")
        kept = []
        for c in edit:
            if isinstance(c, Clip) and c.shot in missing and kept and isinstance(kept[-1], Clip):
                kept[-1].dur += c.dur
            elif not (isinstance(c, Clip) and c.shot in missing):
                kept.append(c)
        edit = kept
    for c in edit:
        if isinstance(c, Clip):
            c.src = max(0.0, anchor(shots[c.shot], c.at))
            # projectile camera returned to the player ("exit"): the cut ends earlier
            exits = [shots[c.shot].sec(t) for t, m in shots[c.shot].marks if m == "exit"]
            if exits and exits[0] > c.src and c.src + c.dur * c.rate > exits[0] - 0.1:
                c.src = max(0.0, exits[0] - 0.1 - c.dur * c.rate)
            left = shots[c.shot].duration - c.src
            if c.dur * c.rate > left > 0:  # not enough frames: slower, but through to the end of the shot
                c.rate = left / c.dur
    t = 0.0
    for c in edit:
        c.start = t
        t += c.dur
    first = next(iter(shots.values()))
    w0, h0 = Image.open(first.frame(0)).size
    if w0 * 9 != h0 * 16:
        raise SystemExit(f"frames {w0}×{h0}, but 16:9 (1920×1080) is required — record again: the client window had the wrong size")
    size = (960, 540) if args.draft else (w0, h0)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    audio = os.path.join(CACHE, "trailer-audio.wav")
    print("audio…")
    sf.write(audio, mix(edit, texts, hits, shots, total, drop_at, flash), SR, subtype="PCM_16")
    print(f"picture: {int(total * FPS)} frames {size[0]}×{size[1]}…")
    cmd = [ffmpeg(), "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{size[0]}x{size[1]}", "-r", str(FPS),
           "-i", "-", "-i", audio, "-c:v", "libx264", "-preset", "veryfast" if args.draft else args.preset, "-crf", "23" if args.draft else "15",
           "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-c:a", "aac", "-b:a", "256k", "-shortest", args.out]
    enc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    n = int(total * FPS)
    for i, buf in enumerate(render_frames(n, args.jobs, (edit, texts, shots, size))):
        enc.stdin.write(buf)
        if i % 600 == 0:
            print(f"  {i / FPS:.0f} s")
    enc.stdin.close()
    if enc.wait() != 0:
        raise SystemExit("ffmpeg: encoding failed")
    print("done:", args.out)
    if not args.draft:
        # light version for messengers: same edit, stream no higher than 4 Mbit/s (~80 MB for 2.5 min)
        lite = os.path.splitext(args.out)[0] + "-lite.mp4"
        subprocess.run([ffmpeg(), "-loglevel", "error", "-y", "-i", args.out, "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                        "-maxrate", "4M", "-bufsize", "8M", "-pix_fmt", "yuv420p", "-r", str(FPS), "-c:a", "aac", "-b:a", "192k",
                        "-movflags", "+faststart", lite], check=True)
        print("light version:", lite)
        credits = os.path.splitext(args.out)[0] + "-credits.txt"
        with open(credits, "w", encoding="utf-8") as f:
            f.write(CREDITS)
        print("description lines (Modrinth, YouTube):", credits)


if __name__ == "__main__":
    main()
