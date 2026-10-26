#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "scipy>=1.11",
#     "soundfile>=0.12",
# ]
# ///
"""Build all Incoming mod sounds from real recordings and synthesis (numpy + scipy + soundfile).

  uv run tools/build_sounds.py           → mod/src/main/resources/assets/incoming/sounds/*.ogg,
                                            assets/incoming/sounds.json, SOUND-CREDITS.md
  uv run tools/build_sounds.py blasts    → rebuild only these sections' files (leave other files unchanged;
                                            sounds.json and credits cover all sections)
  uv run tools/build_sounds.py --alone after → only these sections, without other recordings (synthesis when Freesound
                                            is unavailable): retain other sounds.json events and credits
  uv run tools/build_sounds.py --relevel  → bring built motor and launch files (MOTOR) to MOTOR_LUFS
                                            (loudness changed, Freesound recordings unavailable)
  uv run tools/build_sounds.py --measure  → write only source-sound spectra and loudness (SOURCES_AT) from built
                                            files to Spectra.java
  uv run tools/build_sounds.py --previews drone → no Freesound login (cloud): previews instead of originals
                                            (lossy ogg); originals at home give the same numbers with cleaner sound

Recordings must permit redistribution: CC0 (public domain) or CC BY (attribution in SOUND-CREDITS.md at the root
and credits.txt beside sounds in the jar). List: SOURCES below. Freesound previews (lossy ogg) are cached in
tools/.sound-cache/; lossless originals (src(…, orig=True); Freesound login required, see tools/freesound.py)
in tools/.sound-cache/orig/.

How each sound is built:
- motor loops (Shahed, missile, B-2, bomb whistle, wind, rain): a stationary recording segment looped seamlessly
  (tail crossfaded into the start); the client loops it indefinitely and controls volume, pitch and Doppler;
- explosions (blasts section): originals in near, mid, far and low-hit perspectives; the client mixes by pressure
  and air at the ear, pitch by charge (client/sound/BlastMix), terrain echoes (client/sound/BlastEchoes). No soft limiter:
  EBU R128 momentary loudness (master); a transparent peak limiter at most 6 dB deep; impact from the first file sample;
- sounds that cannot be recorded (bunker-buster drilling, tinnitus, target lock): synthesis from synth_mod_sounds.py.
Sounds are mono (Minecraft spatializes only mono).
Each section has its own random generator (from its name): editing one does not change the others.
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
from scipy.signal import butter, lfilter, resample_poly, sosfiltfilt, welch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import freesound  # noqa: E402
import synth_mod_sounds as syn  # noqa: E402

SR = 44100
syn.SR = SR  # synthesis uses the same sample rate
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "incoming")
OUT = os.path.join(ASSETS, "sounds")
CACHE = os.path.join(ROOT, "tools", ".sound-cache")
rng = np.random.default_rng(7)
PREVIEWS = False  # --previews: originals unavailable, use previews instead

# ---------------------------------------------------------------- sources

CC0 = "CC0 1.0"
BY4 = "CC BY 4.0"
BY3 = "CC BY 3.0"

# Freesound id → (author, title, license, preview)
SOURCES = {
    # Shahed: two-stroke propeller engine at rated power, approaching powered paraglider (firing ~127 Hz; MD-550 shaft 125 Hz)
    114628: ("dobroide", "20110212_paraglider.02.wav", BY4, "114/114628_8043"),
    # cruise missile: stationary jet engine, steady whine and roar (no flyby: no pitch drift)
    437910: ("craigsmith", "G09-18-Constant Jet Noise.wav", CC0, "437/437910_2524442"),
    437917: ("craigsmith", "G10-02-Constant Jet Noise.wav", CC0, "437/437917_2524442"),
    # launch: boosters (US Department of Defense recordings), rocket roar
    184274: ("qubodup", "Launching Anti-Tank Missiles.flac [US DoD]", CC0, "184/184274_71257"),
    182794: ("qubodup", "Rocket Launch.flac", CC0, "182/182794_71257"),
    774270: ("TheLittleCrow", "Rocket Launch Boost and Burning (Version B)", CC0, "774/774270_5672786"),
    613855: ("felix.blume", "Rocket Launch Rumble at Canaveral", CC0, "613/613855_1661766"),
    675750: ("craigsmith", "S32-27 Titan missile launch; long.wav", CC0, "675/675750_2524442"),
    # MLRS: rocket launch (HIMARS, US Department of Defense), incoming rocket whine, bursts
    854476: ("qubodup", "M142 HIMARS Rocket Launch 8 [US DoD]", CC0, "854/854476_71257"),
    241837: ("Zagge28", "Incoming mortar 4", CC0, "241/241837_4404989"),
    241838: ("Zagge28", "Incoming mortar 3", CC0, "241/241838_4404989"),
    241839: ("Zagge28", "Incoming mortar 2", CC0, "241/241839_4404989"),
    241840: ("Zagge28", "Incoming mortar 1", CC0, "241/241840_4404989"),
    674897: ("craigsmith", "S18-01 Incoming shells; explosions.wav", CC0, "674/674897_2524442"),
    # loitering munition: electric propeller motors (quadcopter), catapult
    854352: ("qubodup", "Quadcopter Drone Flyby", CC0, "854/854352_71257"),
    479922: ("craigsmith", "R01-04-Catapult Launch.wav", CC0, "479/479922_2524442"),
    # B-2 and bomb
    152567: ("minian89", "four_jet_engines.wav", CC0, "152/152567_2467357"),
    162417: ("qubodup", "Jet Plane Wind Noise Loop of a KC-135 Stratotanker 1.flac", CC0, "162/162417_71257"),
    483296: ("craigsmith", "R30-12-Large Gun Shells Fly By and Explode.wav", CC0, "483/483296_2524442"),
    # explosions (blasts section, lossless originals)
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
    # crater-fire roar (fire section): previews, since Freesound requires login for originals; 483323 supplies loop audio,
    # 483304 supplies its spectrum (accepted roar of the former loop)
    483323: ("craigsmith", "R30-09-Fires Burns Wood and Metal Crackling.wav", CC0, "483/483323_2524442"),
    483304: ("craigsmith", "R09-58-Large Fire with Debris.wav", CC0, "483/483304_2524442"),
    675967: ("craigsmith", "S10-19 Falling wooden beam; big interior crash; house collapses; long.wav", CC0, "675/675967_2524442"),
    # sirens: Kyiv (CC BY) and KVV wartime siren (CC0)
    676589: ("Romaner66", "Air raid siren in Kyiv", BY4, "676/676589_8949254"),
    727588: ("KVV_Audio", "ALRMMisc_Air Raid Siren In Time Of War_KVV AUDIO_FREE", CC0, "727/727588_12846320"),
    # nuclear strike: thunder, wind, glass, rain, Geiger counter
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
    # burning city: real house fires, a burning house (Yle archive, 1978, Nagra tape) and a wooden house ignited
    # for filming (bass roar, crackling and breaking inside)
    257371: ("YleArkisto", "Tulipalo, talo palaa / Big fire, a house burning, flames humming and crackling", BY4, "257/257371_4415905"),
    871765: ("skowix", "house on fire", BY4, "871/871765_7081328"),
    # grid: substation failure, arc, transformer hum, district going dark and relighting
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
    """Download recording fid preview to cache: some Freesound recordings no longer have ogg previews; use mp3 of the same quality.
    CDN sometimes returns 403 for frequent requests; retry after 2, 4, 8 s."""
    for kind in ("ogg", "mp3"):
        url = f"https://cdn.freesound.org/previews/{SOURCES[fid][3]}-hq.{kind}"
        path = os.path.join(CACHE, f"{fid}.{kind}")
        print("  downloading", url)
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
    sys.exit(f"preview of recording {fid} could not be downloaded")


def src(fid, orig=False, stereo=False):
    """Recording at SR, float64: mono, or two channels (n×2) for stereo. orig uses a lossless original (tools/freesound.py),
    otherwise a Freesound preview (ogg); cache: tools/.sound-cache."""
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


# ---------------------------------------------------------------- utilities

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


# Maximum recording speed shift (varipitch), ratio: speeding an engine recording up threefold does not sound like another engine at
# a different RPM, but the same recording fast-forwarded: body and exhaust resonances shift with pitch (Shahed 3.5.0 sounded like
# "flies", probe P 21.10.2026: loop 232 Hz versus 84 Hz in 3.4.1). Artem's decision 21.10: source recordings play at native
# speed, with at most ±25 % shift; a different pitch needs another recording.
SHIFT_MAX = 1.25
# Sections allowing no justification (Artem's decision 22.10, N4): slowing an unrelated recording cannot make a nuke "bigger".
# Sachs scaling from the recorded charge to kilotons would shift tens of times; 0.62 and 0.7 were chosen by eye. Nuke scale comes from
# client laws: pressure jump at the ear with the modeled positive phase (BlastPulse), terrain echo, post-front wind, glass and
# debris removed by the wave (Clatter), storm roar from its heat.
NATIVE = {"sirens", "nuke", "after"}
SECTION = None  # section currently being built


def speed(x, r, why=None):
    """Varipitch, as on tape: r > 1 is higher and shorter, r < 1 lower, longer and "bigger". Beyond SHIFT_MAX requires
    a why justification: a law (Sachs: explosion time ∝ ∛W) or a "not physical" deviation; without one the build fails,
    and in NATIVE (the nuke) it fails even with one."""
    if max(r, 1 / r) > SHIFT_MAX + 1e-9 and (not why or SECTION in NATIVE):
        sys.exit(f"recording sped up {r:.2f} times: beyond ±{SHIFT_MAX - 1:.0%}, use a recording at the required pitch (SHIFT_MAX"
                 + (f", section {SECTION}: no justifications accepted" if SECTION in NATIVE else "") + ")")
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
    """Mix of (signal, volume in RMS shares), padded to the longest signal."""
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
    """Trim the tail where the recording falls into noise, with a smooth fade."""
    db = env_db(x)
    loud = np.nonzero(db > floor_db)[0]
    end = min(len(x), int(((loud[-1] if len(loud) else len(db)) + 1) * 0.01 * SR) + int(fo * SR))
    return fade(x[:end], 0.002, fo)


def gate_hiss(x, lo_cut=None):
    """Remove steady recording background (street/wind noise) from the tail with a soft expander."""
    db = env_db(x)
    g = np.clip((db + 55) / 15, 0.08, 1)
    g = np.repeat(g, int(0.01 * SR))[:len(x)]
    g = np.convolve(g, np.ones(int(0.05 * SR)) / int(0.05 * SR), "same")
    return pad(x * np.pad(g, (0, max(0, len(x) - len(g))), constant_values=g[-1]), len(x))


def punch(x, db=4, t=0.02):
    """Emphasize attack: louder first t seconds (previews lose the transient)."""
    n = int(0.25 * SR)
    g = np.ones(len(x))
    tt = np.arange(min(n, len(x))) / SR
    g[:len(tt)] = 1 + (10 ** (db / 20) - 1) * np.exp(-tt / t)
    return x * g


def loop(x, seconds, xf=0.6):
    """Seamless loop: equal-power crossfade from tail into start."""
    return syn.loop(x, seconds, xf)


def join(a, b, xf=1.0):
    """Join two segments with an equal-power crossfade."""
    k = int(xf * SR)
    w = np.linspace(0, np.pi / 2, k)
    return np.concatenate([a[:-k], a[-k:] * np.cos(w) + b[:k] * np.sin(w), b[k:]])


def align(x, db=-20, pre=0.03):
    """Move start toward impact: first instant above (peak − 20 dB) lies pre seconds from the start."""
    e = env_db(x, 0.002)
    k = max(0, int((np.argmax(e > db) * 0.002 - pre) * SR))
    return fade(x[k:], 0.002, 0.0)


def sub_thump(dur, f0=48, sweep=10, tau=0.45):
    """Sub-bass "chest hit": recording previews cut lows below ~40 Hz."""
    t = np.arange(int(dur * SR)) / SR
    return np.sin(2 * np.pi * (f0 * t - sweep * t * t)) * np.exp(-t / tau) * np.minimum(1, t / 0.003)


# ---------------------------------------------------------------- undistorted loudness (explosions)

def k_weight(x):
    """ITU-R BS.1770 K-weighting: +4 dB shelf above ~1.7 kHz, cut below ~38 Hz, modeling perceived loudness."""
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
    """Maximum momentary loudness (400 ms window, 100 ms step; EBU R128), LUFS; sum channels for stereo."""
    y = k_weight(x) ** 2
    y = y if y.ndim == 1 else y.sum(axis=1)
    w, h = int(0.4 * SR), int(0.1 * SR)
    c = np.concatenate([[0], np.cumsum(y)])
    ms = [(c[i + w] - c[i]) / w for i in range(0, max(1, len(y) - w), h)]
    return -0.691 + 10 * np.log10(max(ms) + 1e-15)


def low_level(x):
    """Low-frequency loudness (ground hit): maximum mean square over 400 ms without K-weighting, dB; the K-filter cuts
    exactly what the chest feels."""
    y = x ** 2 if x.ndim == 1 else (x ** 2).sum(axis=1)
    w = int(0.4 * SR)
    c = np.concatenate([[0], np.cumsum(y)])
    return 10 * np.log10(max((c[i + w] - c[i]) / w for i in range(0, max(1, len(y) - w), int(0.1 * SR))) + 1e-15)


def limit(x, thr, attack=0.002, release=0.08):
    """Lookahead peak limiter: gain falls smoothly during attack before the peak, returning over release,
    without waveform distortion, unlike soft saturation (tanh)."""
    a = np.abs(x) if x.ndim == 1 else np.max(np.abs(x), axis=1)
    r = np.minimum(1, thr / np.maximum(a, 1e-12))
    w = 2 * int(attack * SR) + 1
    s = uniform_filter1d(minimum_filter1d(r, w), w)  # no more than required at the peak: mean window stays inside minimum window
    k = 1 - np.exp(-1 / (release * SR))
    g = np.empty_like(s)
    cur = 1.0
    for i, v in enumerate(s):
        cur = v if v < cur else cur + (v - cur) * k
        g[i] = cur
    return x * (g if x.ndim == 1 else g[:, None])


def master(x, lufs, peak_db=-1.0, max_gr=6.0, meter=None):
    """Undistorted explosion loudness: momentary loudness lufs; a transparent limiter handles peaks above peak_db,
    no deeper than max_gr dB (if insufficient, reduce the whole signal). The limiter itself lowers loudness slightly;
    find the gain over several passes."""
    meter = meter or loudness
    thr = 10 ** (peak_db / 20)
    top = thr * 10 ** (max_gr / 20) / np.max(np.abs(x))
    g = min(top, 10 ** ((lufs - meter(x)) / 20))
    for _ in range(4):
        y = limit(x * g, thr * 0.995)
        g = min(top, g * 10 ** ((lufs - meter(y)) / 20))
    return limit(x * g, thr * 0.995)


def onset(x, db=-26, pre=0.0005):
    """File starts with impact: first sample above peak − 26 dB, pre seconds before it (the client plays the explosion
    on front-arrival tick; initial file silence would create a gap between approach and explosion)."""
    m = np.abs(x if x.ndim == 1 else x.mean(axis=1))
    k = max(0, int(np.argmax(m > m.max() * 10 ** (db / 20))) - int(pre * SR))
    y = x[k:].copy()
    n = int(pre * SR)
    if n:
        ramp = np.linspace(0, 1, n)
        y[:n] *= ramp if y.ndim == 1 else ramp[:, None]
    return y


def chans(fn, x, *a, **kw):
    """Apply a mono function to each stereo channel."""
    return fn(x, *a, **kw) if x.ndim == 1 else np.stack([fn(x[:, c], *a, **kw) for c in range(x.shape[1])], axis=1)


# Air absorption, dB/km, ISO 9613-1 (20 °C, humidity 70 %), octaves 63 Hz … 8 kHz
AIR_F = [63, 125, 250, 500, 1000, 2000, 4000, 8000]
AIR_DB_KM = [0.1, 0.3, 1.1, 2.8, 5.0, 9.0, 22.9, 76.6]


def air(x, metres):
    """Air over a path of metres meters: attenuate highs by ISO 9613-1 (far perspective from a near recording)."""
    X = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    db = np.interp(np.log2(np.maximum(f, 1)), np.log2(AIR_F), AIR_DB_KM) * metres / 1000
    H = 10 ** (-db / 20)
    return np.fft.irfft(X * (H if x.ndim == 1 else H[:, None]), len(x), axis=0)


def friedlander(dur, positive, b=1.2):
    """Shock-wave pressure at a point (Friedlander shape): instant jump, decay through zero over positive seconds,
    then underpressure, producing a nearby explosion hit; distant recordings lose it."""
    t = np.arange(int(dur * SR)) / SR
    return (1 - t / positive) * np.exp(-b * t / positive)


# ---------------------------------------------------------------- motor loops

# In-game loudness reference, LUFS (maximum momentary): motor sound with Loudness.REF at the ear
# (client/sound/Loudness: source power from sound spec and path, half the level decibels).
ENGINE_LUFS = -19.0
# Loudness of all motor loops and launch one-shots, LUFS (maximum momentary, like explosions): one shared value,
# the in-game ceiling, near explosion perspective (NEAR_LUFS, 1 psi; Loudness.CEILING_LUFS). In-game volume follows
# level at the ear; the file only sets timbre. The client drives volume from ENGINE_LUFS to the ceiling, and a file
# at ceiling does not clip it early: in 3.5.0 files were at ENGINE_LUFS, so anything above Loudness.REF (≈126 dBA: missile
# ignition hit within ~45 m of the launcher, booster roar within ~20 m) sounded identical.
MOTOR_LUFS = -13.5
# MLRS launch rail exit is a short hissing hit: cannot reach ceiling without crushing (limiter at most 6 dB deep),
# but 2 dB below is possible; it reaches file ceiling at ~133 dBA at the ear (within ~25 m of the Grad launcher).
ROCKET_LAUNCH_LUFS = MOTOR_LUFS - 2
# Events built at MOTOR_LUFS (SoundAssetsTest measures their files; --relevel remasters built files). Booster separation
# pop and catapult use ENGINE_LUFS: the hit cannot reach ceiling without crushing (limiter at most
# 6 dB deep), and neither exceeds Loudness.REF even at a meter (~125 and ~120 dBA sound power: 117 and 112 dBA; LoudnessTest).
MOTOR = {"drone.engine", "drone.engine.far", "missile.engine", "missile.engine.rear", "missile.dive", "missile.engine.far",
         "missile.whistle", "launch.booster", "booster.engine", "bomber.engine", "bomber.engine.far", "bomb.fall",
         "bomb.fall.far", "bomb.drill", "rocket.launch", "rocket.incoming", "loiter.engine", "loiter.engine.far", "loiter.dive"}
# Far loops are near loops through this many meters of air (client/sound/EngineSound.FAR_AIR): at a distance, a near recording
# with EFX no longer resembles distant sound; EFX cuts highs with one shelf, air attenuates by octave.
FAR_M = 500
# A steady loop's momentary loudness varies by at most this many dB (standard deviation over 400 ms windows): steady loops
# give 0.2–0.7, flyby splices 2–2.5
STEADY_DB = 1.0


def momentary(x):
    """Circular loop momentary loudness (400 ms windows every 100 ms, including the seam), LUFS."""
    w, h = int(0.4 * SR), int(0.1 * SR)
    y = k_weight(np.concatenate([x[-SR:], x, x[:w]]))[SR:] ** 2  # warm the filter with the loop tail: no seam jump
    c = np.concatenate([[0], np.cumsum(y)])
    return -0.691 + 10 * np.log10(np.array([(c[i + w] - c[i]) / w for i in range(0, len(x), h)]) + 1e-15)


def engine(x, lufs, name, steady=True):
    """Motor loop: loudness lufs (master, no soft limiter) and steadiness check. Loudness variation in a distant loop
    sounds like trembling or "rustle" (short-segment splice, field-recording swells), so the build fails.
    steady=False marks a flyby loop not yet replaced with a steady recording (loitering munition)."""
    y = master(x, lufs, meter=lambda s: np.max(momentary(s)))
    sd = np.std(momentary(y))
    if steady and sd > STEADY_DB:
        fail(f"{name}: momentary loudness varies by {sd:.1f} dB (standard deviation), a steady loop must stay within {STEADY_DB}")
    return y


def noise_loop(sigs, seconds):
    """Steady loop with the timbre of unsteady recordings (projectile flyby: drifting pitch, growing volume):
    noise with their mean spectrum (Welch) and random phases; inverse FFT of loop length is periodic, without a seam."""
    f, psd = None, 0
    for s in sigs:
        f, p = welch(s / max(rms(s), 1e-9), SR, nperseg=4096)
        psd = psd + p / len(sigs)
    n = int(seconds * SR)
    mag = np.sqrt(np.interp(np.fft.rfftfreq(n, 1 / SR), f, psd))
    mag[0] = 0
    return np.fft.irfft(mag * np.exp(2j * np.pi * rng.random(len(mag))), n)


# ---------------------------------------------------------------- writing

EVENTS = {}  # event name → (subtitle, [files])
PRELOAD = set()  # events decoded on resource load rather than first playback
WRITING = True  # whether to write current section files (python3 build_sounds.py <sections> writes only those)


def fail(msg):
    """Recording check failed: fail the build. For a section whose files are not written (building other sections),
    only log it: the section runs for events and credits, its files stay unchanged, and recordings may be previews (--previews)."""
    if WRITING:
        sys.exit(msg)
    print("  not written, check failed:", msg)


def write(name, x, event=None, subtitle=None, level=0.35):
    """File sounds/<name>.ogg (mono vector, stereo n×2); event is the sounds.json event containing this variant;
    level is libsndfile Vorbis compression (0 is best quality, 0.35 ≈ q6.5)."""
    x = np.clip(x, -1, 1).astype(np.float32)
    if event:
        sub, files = EVENTS.setdefault(event, (subtitle, []))
        files.append(name)
    if not WRITING:
        return
    if event in SPECTRA or event in SOURCES_AT:
        SPECTRA_GOT.setdefault(event, []).append(octaves(x))
    if event in SOURCES_AT:
        LUFS_GOT.setdefault(event, []).append(source_lufs(event, x))
    os.makedirs(OUT, exist_ok=True)
    channels = 1 if x.ndim == 1 else x.shape[1]
    # chunked: libsndfile crashes if given a long Vorbis file in one block
    with sf.SoundFile(os.path.join(OUT, name + ".ogg"), "w", SR, channels, format="OGG", subtype="VORBIS",
                      compression_level=level) as fh:
        for i in range(0, len(x), 8192):
            fh.write(x[i:i + 8192])
    print(f"  {name:26} {len(x) / SR:5.1f} s  RMS {20 * np.log10(rms(x) + 1e-9):6.1f} dB  {channels} ch.")


# Events whose octave spectra the sound spec needs (ua.zentix.incoming.sound.Spectra): the server computes projectile
# audibility and the client volume from them; rebuilding a recording automatically rewrites its spectrum there.
SPECTRA = {"drone.engine", "loiter.engine", "loiter.dive", "missile.engine", "missile.engine.rear", "bomber.engine",
           "bomb.fall", "booster.engine", "rocket.incoming", "bomb.drill", "launch.booster", "rocket.launch",
           "loiter.launch", "booster.separate"}
SPECTRA_GOT = {}
# World one-shots and loops with volume from the source law (client/sound/Sources: spec sound power, ISO 9613 path,
# client/sound/Loudness), rather than manual range: Spectra.java stores their spectrum and file loudness
# (maximum momentary, LUFS), used by the client to normalize to ENGINE_LUFS, the shared reference for all source sounds.
# No file rebuild needed: --measure measures built files.
SOURCES_AT = {"siren", "nuke.alarm", "nuke.launch", "grid.fail", "grid.spark", "grid.hum", "grid.power_down",
              "grid.power_up", "nuke.collapse", "debris.fall", "blast.fire", "nuke.glass", "nuke.fire"}
LUFS_GOT = {}
# Loops whose source law gives mean power (fire roar: Sources.flame is a fraction of flame heat release) use
# mean loop loudness rather than maximum momentary: a crackling recording's loudest 400 ms exceeds its mean by 3–7 dB,
# so normalizing to the law by that window would play equally far below it (former steady 483304: 1.7 dB). Other
# source sounds use maximum momentary: each has its own maximum-to-mean gap (sirens 7.6 and 4.9 dB, but their
# spec is horn-axis level; substation hum 1.5 dB, motors 0.1–2 dB; list in review #456, round 5); their spec meaning
# and measure are separate
LOOPS_BY_MEAN = {"blast.fire"}


def loop_loudness(x):
    """Whole circular loop loudness (filter warmed with the tail): mean power after K-filtering, LUFS."""
    return -0.691 + 10 * np.log10(np.mean(k_weight(np.concatenate([x[-SR:], x]))[SR:] ** 2))


def source_lufs(event, x):
    """Source-file loudness used by the client to normalize to the source law (Spectra.*_LUFS)."""
    return loop_loudness(x) if event in LOOPS_BY_MEAN else loudness(x)
SPECTRA_JAVA = os.path.join(ROOT, "mod", "src", "main", "java", "ua", "zentix", "incoming", "sound", "Spectra.java")
OCTAVES = [63, 125, 250, 500, 1000, 2000, 4000, 8000]


def octaves(x):
    """Recording power in 63 Hz – 8 kHz octaves (Welch), shares."""
    f, p = welch(x, SR, nperseg=8192)
    return np.array([p[(f >= c / np.sqrt(2)) & (f < c * np.sqrt(2))].sum() for c in OCTAVES])


def write_spectra():
    """Built-event spectra to Spectra.java (dB relative to the strongest octave; power-mean across variants);
    other file lines remain unchanged."""
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
        print(f"  spectrum {ev}: {' '.join(f'{v:.0f}' for v in db)}")
        if ev in LUFS_GOT:
            # power-mean across variants: client chooses by seed; variant differences belong to the recordings
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
            print(f"  loudness {ev}: {lufs:.1f} LUFS")
    with open(SPECTRA_JAVA, "w", encoding="utf-8") as fh:
        fh.write(java)


def variants(event, subtitle, name, sigs, **kw):
    for i, s in enumerate(sigs, 1):
        write(f"{name}_{i}" if len(sigs) > 1 else name, s, event, subtitle, **kw)


# ================================================================ Shahed

# Shahed: MD-550 copies the Limbach L550E, two-stroke four-cylinder, 7500 rpm, two-blade propeller directly on the shaft
# (WeaponSpec.DRONE_NOISE sound spec): shaft n/60 = 125 Hz, blades 250 Hz, firing (each cylinder once per
# revolution) 500 Hz (ref/formulas.md §6). Loop plays at this RPM; client controls pitch by propeller RPM (constant
# propeller advance ratio: RPM ∝ airspeed).
DRONE_RPM = 7500


def peak(x, lo, hi):
    """Strongest recording tone between lo and hi, Hz (Welch, ~0.74 s window)."""
    f, p = welch(x, SR, nperseg=1 << 15)
    m = (f >= lo) & (f <= hi)
    return f[m][np.argmax(p[m])]


def tone_at(x, f0, name, lo=40, hi=1200, tol=0.03):
    """Check: strongest loop tone is f0 (±tol)."""
    got = peak(x, lo, hi)
    if abs(got / f0 - 1) > tol:
        fail(f"{name}: strongest tone {got:.0f} Hz, real engine {f0:.0f} Hz")
    print(f"  {name}: strongest tone {got:.0f} Hz (engine {f0:.0f} Hz)")


def steadiest(x, seconds, step=0.25):
    """Steadiest recording segment of length seconds: least variation in momentary loudness (taxiing, throttle)."""
    w = int(seconds * SR)
    best, at = None, 0
    for a in range(0, max(1, len(x) - w), int(step * SR)):
        sd = np.std(momentary(x[a:a + w]))
        if best is None or sd < best:
            best, at = sd, a
    return x[at:at + w]


def drone():
    """Two-stroke propeller engine ("moped"): approaching powered paraglider at native speed. Single-cylinder two-stroke
    at ~7600 rpm (firing 127 Hz, propeller through 1:3 gearing at 42.7 Hz): firing matches MD-550 shaft frequency
    (125 Hz), shifting less than 3 % (SHIFT_MAX). Cut tones below shaft rate: Shahed propeller is directly on the shaft,
    without gearbox tones. Missing: MD-550 has four cylinders, stronger 500 Hz firing than a single-cylinder engine
    (⚠ no CC0/CC BY recording of a four-cylinder two-stroke propeller engine found). Far: same through FAR_M air."""
    shaft = DRONE_RPM / 60
    x = cut(src(114628, orig=True), 15, 31)  # approach: steady tone (127 ± 1.5 Hz), no throttle changes; flyby follows
    x = steadiest(speed(x, shaft / peak(x, 90, 200)), 4.7)
    body = F(x, lo=100, hi=12000)
    near = loop(body, 4.0)
    tone_at(near, shaft, "drone_engine")
    write("drone_engine", engine(near, MOTOR_LUFS, "drone_engine"), "drone.engine", "subtitles.incoming.drone")
    write("drone_engine_far", engine(loop(air(body, FAR_M), 4.0), MOTOR_LUFS, "drone_engine_far"), "drone.engine.far",
          "subtitles.incoming.drone")


# ================================================================ cruise missile

def missile():
    """Turbofan (Kh-101 uses TRDD-50): fan and compressor whine in front, jet roar behind; higher pitch and flow noise
    in a dive; far away, same engine through half a kilometer of air, steady muted rumble; approach whine audible
    from afar. All from steady stationary jet-engine recordings (G09-18 whine, G10-02 roar): flyby recordings have
    Doppler pitch drift, so a loop jumps back in pitch every few seconds; the client computes Doppler itself.
    """
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
    # far ahead: forward-carried fan whine through FAR_M air; client controls pitch (Doppler, RPM)
    whistle = air(F(whine, lo=500, hi=12000), FAR_M)
    write("missile_whistle", engine(loop(whistle, 12.0), MOTOR_LUFS, "missile_whistle"), "missile.whistle",
          "subtitles.incoming.missile.whistle")


# ================================================================ launch

def launch():
    """Rail launch: ignition hit, booster roar, receding into distance (US Department of Defense missile-launch
    recordings). Booster loop is steady crackling roar while burning (client applies Doppler)."""
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

    # booster separation: squib pop and metal latch clank
    sep = []
    for r, f0 in [(1.7, 620), (1.9, 740)]:
        pop = speed(cut(src(182431), 0, 1.4), r, "⚠ not physical: squib pop from an explosion recording, pitch raised by eye "
                                                   "— replace with a squib recording (S8)")
        n = int(1.2 * SR)
        tt = np.arange(n) / SR
        clang = sum(a * np.sin(2 * np.pi * f0 * k * tt) * np.exp(-tt / (0.25 / k ** 0.5))
                    for k, a in [(1, 1), (1.51, 0.7), (2.23, 0.5), (3.07, 0.35), (4.2, 0.2)])
        clang *= np.minimum(1, tt / 0.001)
        sep.append(master(fade(mix((pad(pop, n), 1.0), (clang, 0.5)), 0.001, 0.3), ENGINE_LUFS))
    variants("booster.separate", "subtitles.incoming.launch.separate", "booster_separate", level(sep, ENGINE_LUFS))


# ================================================================ Grad MLRS

def rocket():
    """Multiple-launch rocket system: each rocket leaves its tube with a sharp crackling "fsh-sh" (HIMARS launch,
    higher and shorter for the smaller 122 mm caliber); half-second launch intervals merge into a continuous roar.
    Incoming rockets howl (mortar rounds and projectiles overhead). Bursts are in the blasts section."""
    hm = src(854476)
    decay = cut(hm, 4.1, 5.85)
    sigs = []
    for a, r in [(0.0, 1.32), (0.9, 1.45), (1.8, 1.38), (2.6, 1.52), (3.1, 1.28)]:
        body = join(cut(hm, a, a + 1.6), decay, 0.7)
        n = len(body)
        tt = np.arange(n) / SR
        # ignition pop: preview loses the attack; restore a short crackle and thump
        crack = F(rng.standard_normal(n), lo=900, hi=12000) * np.exp(-tt / 0.018)
        x = mix((body, 1.0), (crack, 0.5), (sub_thump(n / SR, 60, 20, 0.12), 0.3))
        sigs.append(master(fade(speed(x, r, "⚠ not physical: HIMARS launch (227 mm) pitch raised for Grad (122 mm) by "
                                       "eye; replace with an unshifted launch recording (S8)"), 0.001, 0.4), ROCKET_LAUNCH_LUFS))
    variants("rocket.launch", "subtitles.incoming.rocket", "rocket_launch", level(sigs, ROCKET_LAUNCH_LUFS))

    # approach howl: steady noise with the spectrum of overhead projectiles (middle of recordings, without start or burst);
    # recordings themselves have Doppler pitch drift and their spliced segments tremble; client controls pitch
    grains = [cut(src(g, orig=True), a, b) for g, a, b in
              [(241840, 0.25, 1.2), (241838, 0.35, 1.5), (241837, 0.25, 1.1), (241839, 0.25, 1.15), (674897, 13.9, 15.3)]]
    tex = noise_loop([F(g, lo=250, hi=11000) for g in grains], 10.0)
    write("rocket_incoming", engine(tex, MOTOR_LUFS, "rocket_incoming"), "rocket.incoming", "subtitles.incoming.rocket.incoming")


# ================================================================ loitering munition

# Lancet: electric motor with a two-blade pusher propeller, ~9000 rpm (WeaponSpec.LOITER_NOISE sound spec):
# blades 300 Hz; 14-pole (7-pair) outrunner motor winding tone n/60 × 7 = 1050 Hz ((est.): motors of this class have
# 12–14 poles).
LOITER_RPM, LOITER_BLADES, LOITER_POLE_PAIRS = 9000, 2, 7


def loiter():
    """Lancet: electric propeller motor. No steady electric-aircraft propeller recording available, so the loop follows
    unsteady recordings (noise_loop): broad quadcopter propeller/motor noise (mean spectrum without flyby pitch),
    plus RPM tones: blade harmonics (small-UAV propellers have the strongest first harmonic, then 1/k) and motor
    winding tone. Dive: cutting air (KC-135 wind); catapult launch: hit and whistle without fire."""
    quad = cut(src(854352, orig=True), 0.3, 4.4)
    broad = noise_loop([F(quad, lo=150, hi=12000)], 3.6)
    n = len(broad)
    tt = np.arange(n) / SR
    rev = LOITER_RPM / 60
    # whole number of revolutions in the loop: no tone jump at the seam
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
    # catapult: piston hit, rail whistle
    cat = src(479922)
    sigs = []
    for a in (1.25, 5.07, 13.53):
        x = align(cut(cat, a - 0.05, a + 1.6), -20, 0.01)
        x = mix((x, 1.0), (pad(sub_thump(0.5, 70, 25, 0.08), len(x)), 0.4))
        sigs.append(master(punch(trim_tail(x, -46, 0.3), 4), ENGINE_LUFS))
    variants("loiter.launch", "subtitles.incoming.loiter.launch", "loiter_launch", level(sigs, ENGINE_LUFS))


# ================================================================ B-2 and bomb

def bomber():
    """Four B-2 turbofans (recording of four jets); far away, the same through FAR_M air."""
    fj = cut(src(152567), 15, 85)
    near = mix((F(fj, lo=30, hi=9000), 1.0), (syn.jet(len(fj) / SR)[:len(fj)], 0.15))
    write("bomber_engine", engine(loop(near, 10.0, 1.0), MOTOR_LUFS, "bomber_engine"), "bomber.engine", "subtitles.incoming.bomber")
    write("bomber_engine_far", engine(loop(air(near, FAR_M), 10.0, 1.0), MOTOR_LUFS, "bomber_engine_far"), "bomber.engine.far",
          "subtitles.incoming.bomber")
    # falling bomb: tearing air (flow) plus overhead projectile howl
    wind = np.tile(cut(src(162417), 0, 6.2), 3)
    sh = cut(src(483296), 1.6, 18.3)
    sh = pad(np.tile(sh, 2), len(wind))
    fall = mix((F(wind, lo=200, hi=12000, bells=[(1100, 6, 0.4)]), 1.0), (F(sh, lo=400, hi=9000), 0.7),
               (syn.bomb_fall(len(wind) / SR)[:len(wind)], 0.35))
    # wind/projectile-flyby splice varies by ~1.2 dB in loudness (no steady falling-bomb recording)
    write("bomb_fall", engine(loop(fall, 8.0), MOTOR_LUFS, "bomb_fall", False), "bomb.fall", "subtitles.incoming.bomb.fall")
    write("bomb_fall_far", engine(loop(air(fall, FAR_M), 8.0), MOTOR_LUFS, "bomb_fall_far", False), "bomb.fall.far",
          "subtitles.incoming.bomb.fall")
    # concrete drilling: synthesized grinding plus real breaking-floor crackle, muffled (bomb underground)
    crash = F(cut(src(675967), 10, 30), lo=40, hi=1600)
    dr = syn.drill(20.0)
    write("bomb_drill", engine(loop(mix((dr, 1.0), (pad(crash, len(dr)), 0.55)), 7.0), MOTOR_LUFS, "bomb_drill", False),
          "bomb.drill", "subtitles.incoming.bomb.drill")


# ================================================================ explosions

# Explosion perspective loudness, LUFS (maximum momentary, EBU R128; low hit uses unweighted dB): every variant
# mastered exactly to it (level); client mixes perspectives by distance using the same numbers (client/sound/BlastMix).
NEAR_LUFS, MID_LUFS, FAR_LUFS, SUB_DB = -13.5, -13.5, -15.0, -10.0

# Explosion recordings: (id, start, end, channel); three perspectives of the same explosion from each: near, mid
# and far. Client chooses a variant's perspectives with one seed (equal variant counts), so where perspectives
# overlap (distance transition), they form one explosion, not two out of sync. Channel 1 is right only
# (475780 left channel overloads on impact).
BLASTS = [(251401, 0.0, 9.5, None), (165808, 2.1, 4.6, None), (811474, 5.1, 15.0, None), (125937, 10.0, 18.0, None),
          (866989, 181.0, 189.0, None), (475780, 0.1, 14.0, 1)]


def level(sigs, lufs, meter=None):
    """All perspective variants share one loudness: client mixes perspectives by distance (BlastMix), treating each as constant.
    A variant falling short (limiter at most 6 dB deep) fails the build: choose another recording or lower perspective loudness."""
    for i, x in enumerate(sigs, 1):
        got = (meter or loudness)(x)
        if abs(got - lufs) > 0.3:
            fail(f"variant {i}: {got:.1f} LUFS, perspective {lufs} LUFS")
    return sigs


def blasts():
    """Explosion perspectives from lossless originals, without overloaded recordings. Client mixes by pressure and air
    at the ear (BlastMix): near hit and explosion body, plus low "chest hit"; farther, mid perspective with echoes
    from slopes and houses; far, a roll without highs. Explosion seed chooses the variant."""
    PRELOAD.update(["blast.near", "blast.mid", "blast.far", "blast.sub"])
    near, mid, far = [], [], []
    for fid, a, b, ch in BLASTS:
        x = src(fid, orig=True) if ch is None else src(fid, orig=True, stereo=True)[:, ch]
        x = F(onset(cut(x, a, b), -30), lo=35)
        t = np.arange(len(x)) / SR
        # near: impact and body, echo 10 dB quieter over a second (surrounding slopes supply it in client echo); shock is the
        # Friedlander shape lost by distant recordings (positive phase ~8 ms: ~50 kg charge at two dozen meters)
        n = x[:int(4.2 * SR)] * 10 ** (-10 * np.clip(t[:int(4.2 * SR)] - 0.35, 0, None) / 20)
        shock = F(friedlander(0.15, 0.008), hi=9000)
        n[:len(shock)] += shock * 0.4 * np.max(np.abs(n)) / np.max(np.abs(shock))
        # blast.sub supplies near lows below 60 Hz; without them the near perspective has peak headroom for the impact
        near.append(master(fade(F(n, lo=60), 0, 0.5), NEAR_LUFS))
        # mid distance (hundreds of meters): unchanged recording, impact and slope/house/forest echo
        mid.append(master(trim_tail(gate_hiss(x), -50, 0.6), MID_LUFS))
        # far (about a kilometer): same explosion through air, highs attenuated by ISO 9613-1
        far.append(master(trim_tail(gate_hiss(air(x, 1000)), -50, 0.8), FAR_LUFS))
    variants("blast.near", "subtitles.incoming.blast", "blast_near", level(near, NEAR_LUFS), level=0.2)
    variants("blast.mid", "subtitles.incoming.blast", "blast_mid", level(mid, MID_LUFS), level=0.2)
    variants("blast.far", "subtitles.incoming.blast", "blast_far", level(far, FAR_LUFS), level=0.2)

    # "underfoot": ground/air hit below 200 Hz from real large blasts (70 t in a quarry, rock blast in a quarry one
    # octave lower) plus synthesis: decaying 50 → 35 Hz tone and low rumble
    subs = []
    for fid, a, b, ch, r in [(475780, 0.1, 6.0, 1, 1.0), (82682, 6.9, 10.4, None, 0.6)]:
        x = src(fid, orig=True) if ch is None else src(fid, orig=True, stereo=True)[:, ch]
        s = F(speed(onset(cut(x, a, b), -30), r, "Sachs: low end of a larger-charge detonation (⚠ multiplier chosen by eye)"), lo=20, hi=200)
        n = len(s)
        t = np.arange(n) / SR
        thump = np.sin(2 * np.pi * (50 * t - 3.75 * t * t)) * np.exp(-t / 0.4) * np.minimum(1, t / 0.002)
        rumble = F(rng.standard_normal(n), lo=20, hi=160) * np.exp(-t / 1.2)
        x = mix((s, 1.0), (thump, 0.8), (rumble, 0.4))
        subs.append(master(fade(x, 0, 1.0), SUB_DB, meter=low_level))
    variants("blast.sub", "subtitles.incoming.blast", "blast_sub", level(subs, SUB_DB, low_level), level=0.2)

    # debris: rocks and soil fall onto nearby bystanders
    deb = []
    x = src(550342)
    for a, b in [(0.0, 3.8), (4.85, 9.0), (15.17, 23.9), (24.95, 27.0)]:
        deb.append(norm(trim_tail(cut(x, a, b), -45, 0.4), -16, 0.9))
    variants("debris.fall", "subtitles.incoming.debris", "debris_fall", deb)


# Client crater-fire roar sums fire powers into four loops around the ear (client/sound/Quarters), but
# powers add only for incoherent sounds. One recording in two loops started on the same tick adds by
# amplitude: a fire between them is 2 dB louder than the power sum (review R8, OpenAL loopback). Offsets of one recording fail:
# game loop starts are arbitrary, and when their difference matches variant offsets, the sound is identical again. Hence four loops
# use non-overlapping segments of one long large-fire recording (craigsmith 483323, 73 s): each quarter has its own segment
# of real fire, independent at every start difference, with recorded crackling and the same timbre across quarters. The former
# 483304 recording is 14 s, with a low layer repeating every 11.03 s: no independent segments can be cut (synthesis under its
# envelope softened crackling, review round 4); 483313 ("Building on Fire", 42.7 s) also repeats lows every 13.62 s; segments
# correlated up to 0.21; 483323 has no repeat (≤ 0.03 at every offset).
FIRE_LOOP_S, FIRE_XF_S = 12.0, 1.0
# 483323 segments (starts, s): 13 s each (loop and crossfade), no overlap. Avoid metal ringing (narrow tone 5–7 dB above
# spectrum: 1.5 kHz in first 3 s, 4.1 kHz at 31–35 s) and loudness swells at 0–4 and 35–42 s: segment momentary loudness
# varies by 0.9–1.5 dB (standard deviation), 483304 by 1.0
FIRE_CUTS = (5.0, 18.0, 46.0, 59.5)
# Loop spectrum matches the accepted 483304 roar (the former loop segment, 0.2–14.2 s): client computes fire A-level
# from it (Sources.flame, Spectra.BLAST_FIRE); with its own darker spectrum, A correction −5.6 dB versus −0.2, 483323 would play
# 3.5–5 dB quieter at the same power. Correction applies between these frequencies (spec octaves 63 Hz – 8 kHz), flat beyond:
# previews contain almost nothing above, so nothing can be raised there
FIRE_EQ_LO, FIRE_EQ_HI = 30, 14000
# Loops have mean loudness at the game ceiling (Loudness.CEILING_LUFS), like motor loops: Minecraft cannot play louder
# than the file (volume at most 1); a quieter quarter loop would clip the sum of many fires before ceiling
# (peak-normalized to −1 dBFS without a limiter, −21.6 LUFS: 300 fires of 2 MW at 30 m at night lost 5.1 dB, review
# #456, round 5). Crackling clicks exceed RMS by 20–22 dB; a −1 dBFS peak file supports at most 12.5 at ceiling, so
# a lookahead limiter cuts them; release 10 ms (clicks last milliseconds): deeper than 1 dB for 4–5 % of the time;
# 80 ms would suppress roar after every click (21–24 %). A loud sum of many fires also does this: independent fire clicks
# blend, and the kurtosis of N equal fires is 3 + (κ − 3)/N
FIRE_LUFS, FIRE_RELEASE = -13.5, 0.01
# Crackling is no softer than the former 483304 loop, using review round 4 measures (crackle): 10 ms envelope peak above band RMS,
# dB, and kurtosis, in 2–4 and 4–8 kHz
FIRE_CRACKLE = ((6.3, 3.8), (8.5, 5.6))
# Loop sounds are incoherent: |ρ| between loops at simultaneous start and its maximum across all circular start offsets. The spectrum
# itself sets the limit: two independent 12 s noises with the 483323 spectrum have maximum |ρ| over all offsets 0.042 (median
# of 40 pairs), up to 0.048 (energy in 80–400 Hz, few independent samples); an offset copy of one recording gives 1
FIRE_RHO_START, FIRE_RHO_ANY = 0.02, 0.05


def crackle(x):
    """Fire crackling as measured by the round-4 reviewer: in 2–4 and 4–8 kHz bands (6th-order Butterworth, zero phase,
    circular), 10 ms envelope peak above band RMS, dB, and kurtosis (Gaussian noise: 3)."""
    out = []
    for lo, hi in ((2000, 4000), (4000, 8000)):
        sos = butter(6, [lo, hi], btype="bandpass", fs=SR, output="sos")
        y = sosfiltfilt(sos, np.concatenate([x[-SR:], x, x[:SR]]))[SR:-SR]
        p, n = np.mean(y * y), SR // 100
        e = np.sqrt(np.mean(y[:len(y) // n * n].reshape(-1, n) ** 2, axis=1))
        out.append((10 * np.log10(np.max(e) ** 2 / p), np.mean(y ** 4) / p ** 2))
    return out


def fire():
    """Crater-fire roar: four loops, one per quarter around the ear (client/sound/Quarters), non-overlapping segments
    of recording 483323 at native speed, each looped (tail crossfaded into start). Spectrum: 483304 (FIRE_EQ_LO…HI);
    correction smoothed over 1/12 octave, linear and circular, preserving crackling and the loop seam. Four passes:
    octave differences reach 14 dB, one pass cannot align octave edges. All four loops share one loudness (equal powers
    in Quarters): mean at ceiling (FIRE_LUFS), −1 dBFS peaks via click limiter (FIRE_RELEASE); client normalizes to the
    source law using measured loop mean loudness (Spectra.BLAST_FIRE_LUFS, LOOPS_BY_MEAN). Client fuel sets the fire
    envelope and duration, not the recording. No pitch shift (Sachs concerns detonation, not debris crackling). Freesound
    originals require login, so built from previews (ogg ~98 and ~88 kbit/s, 48 kHz): 483304 preview/original crackling
    matches within 0.2 dB by these measures; octaves within 0.4 dB."""
    rec, ref = src(483323), cut(src(483304), 0.2, 14.2)
    fw, target = welch(ref, SR, nperseg=8192)
    lf = np.log2(np.maximum(fw, 1))
    smooth = np.exp(-0.5 * ((lf[:, None] - lf[None, :]) / (1 / 24)) ** 2)
    inside = (fw >= FIRE_EQ_LO) & (fw <= FIRE_EQ_HI)

    def eq(p):  # correction toward 483304 using smoothed spectra, flat beyond the edges
        g = np.sqrt((smooth @ target) / np.maximum(smooth @ p, 1e-30))
        return np.interp(fw, fw[inside], g[inside])

    loop_lufs = loop_loudness
    out = []
    for a in FIRE_CUTS:
        z = loop(cut(rec, a, a + FIRE_LOOP_S + FIRE_XF_S), FIRE_LOOP_S, FIRE_XF_S)
        f = np.fft.rfftfreq(len(z), 1 / SR)
        thr = 10 ** (-1 / 20) * 0.995
        for r in range(4):  # limiter-cut clicks carry highs; subsequent correction rounds restore the spectrum
            y = z
            for _ in range(4):
                y = np.fft.irfft(np.fft.rfft(y) * np.interp(f, fw, eq(welch(y, SR, nperseg=8192)[1])), len(y))
            g = 10 ** ((FIRE_LUFS - loop_lufs(y)) / 20)
            for _ in range(6):  # the limiter itself lowers loudness; find gain over several passes
                g *= 10 ** ((FIRE_LUFS - loop_lufs(limit(y * g, thr, release=FIRE_RELEASE))) / 20)
            z = limit(y * g, thr, release=FIRE_RELEASE)
            if r == 0:
                gr = 20 * np.log10(np.maximum(np.abs(z), 1e-12) / np.maximum(np.abs(y * g), 1e-12))[np.abs(y * g) > 1e-4]
                print(f"  segment {a} s: limiter up to {-gr.min():.1f} dB, deeper than 1 dB for {100 * np.mean(gr < -1):.1f} % of time")
        out.append(z)
    o0 = octaves(ref)
    for k, y in enumerate(out, 1):
        if abs(loop_lufs(y) - FIRE_LUFS) > 0.2:
            fail(f"blast_fire_{k}: mean loudness {loop_lufs(y):.1f} LUFS, required {FIRE_LUFS}")
        d = 10 * np.log10(octaves(y) / np.sum(octaves(y)) / (o0 / np.sum(o0)))
        if np.max(np.abs(d)) > 0.5:
            fail(f"blast_fire_{k}: spectrum differs from 483304 by {np.max(np.abs(d)):.1f} dB")
        for (e, kurt), (e0, k0), band in zip(crackle(y), FIRE_CRACKLE, ("2–4", "4–8")):
            if e < e0 or kurt < k0:
                fail(f"blast_fire_{k}: {band} kHz crackling softer than 483304: envelope peak {e:.1f} versus {e0} dB, kurtosis {kurt:.1f} versus {k0}")
    n = len(out[0])
    for i in range(len(out)):
        for j in range(i + 1, len(out)):
            a, b = out[i], out[j]
            r = np.fft.irfft(np.fft.rfft(a) * np.conj(np.fft.rfft(b)), n) / np.sqrt(np.sum(a ** 2) * np.sum(b ** 2))
            if abs(r[0]) > FIRE_RHO_START or np.max(np.abs(r)) > FIRE_RHO_ANY:
                fail(f"blast_fire_{i + 1} and {j + 1} are coherent: ρ {r[0]:+.3f} at simultaneous start, up to {np.max(np.abs(r)):.3f}")
    variants("blast.fire", "subtitles.incoming.fire", "blast_fire", out, level=0.2)


# Explosion-rumble loops (client/sound/Rumble) are at ceiling, like motor loops: client mix sets rumble loudness
# (BlastSounds.RUMBLE_LUFS); file sets only timbre; one variant per ear quarter, with incoherent quarter noise.
RUMBLE_LUFS = MOTOR_LUFS
RUMBLE_VARIANTS = 4
RUMBLE_SECONDS = 8.0


def rumble():
    """Explosion rumble without engine voices (client/sound/Rumble): a hundred-strike raid sounds as a sum in four loops
    around the ear rather than a hundred one-shots. Timbre: far-perspective tails (roll through a kilometer of air),
    no attacks; noise with their mean spectrum (Welch) and random phases gives a steady seamless loop, like motor
    noise_loop. Client members set the envelope each tick, not the file. No recordings: build from built blast_far files (--alone rumble)."""
    tails = []
    with open(os.path.join(ASSETS, "sounds.json"), encoding="utf-8") as fh:
        far = [f["name"].split(":")[1] for f in json.load(fh)["blast.far"]["sounds"]]
    for name in far:
        x, sr = sf.read(os.path.join(OUT, name + ".ogg"), dtype="float64")
        if sr != SR or x.ndim != 1:
            sys.exit(f"{name}: {sr} Hz, {x.ndim} ch., build requires {SR} Hz mono")
        # tail after the attack: from the end of the 400 ms window starting at the loudest point (attack and its momentary-loudness window)
        a = int((np.argmax(env_db(x)) * 0.01 + 0.4) * SR)
        tails.append(x[a:])
    for i in range(1, RUMBLE_VARIANTS + 1):
        y = engine(noise_loop(tails, RUMBLE_SECONDS), RUMBLE_LUFS, f"blast_rumble_{i}")
        write(f"blast_rumble_{i}", y, "blast.rumble", "subtitles.incoming.blast", level=0.2)


def bunker():
    """Bunker-buster: ground impact, underground blast, gas vent, cavity collapse, ground shake."""
    # no impact crack: the supersonic bomb's flight plays its N-wave (Booms); absent at subsonic speed
    # ground impact: heavy muffled hit (large explosion at half speed, without highs) plus debris
    imp = F(speed(cut(src(486018), 16.2, 21.1), 0.6, "Sachs: bomb charge larger than recorded (⚠ multiplier chosen by eye, S5)"), lo=20, hi=1800)
    imp = mix((imp, 1.0), (pad(syn.bomb_impact(), len(imp)), 0.8), (pad(cut(src(550342), 25.0, 27.0), len(imp)), 0.25))
    write("bomb_impact", norm(fade(imp, 0.001, 0.6), -12.9), "bomb.impact", "subtitles.incoming.bomb.impact")
    # underground blast: muffled, as heard from underground
    deep = []
    for fid, a, b, r in [(741174, 0, 8, 0.6), (486018, 45.15, 50.0, 0.5)]:
        s = F(speed(cut(src(fid), a, b), r, "Sachs: bomb charge larger than recorded (⚠ multiplier chosen by eye, S5)"), lo=18, hi=320)
        s = mix((syn.reverb(s, t60=3.0, mix=0.4, hi=400), 1.0), (pad(syn.bomb_deep(), len(s)), 0.8))
        deep.append(norm(fade(s, 0.002, 1.0), -12.3))
    variants("bomb.deep", "subtitles.incoming.blast", "bomb_deep", deep)
    # gas vent from crater: rumbling gust (short booster roar)
    vent = F(cut(src(613855), 30, 34.5), lo=40, hi=3000)
    vent = mix((vent * np.exp(-np.arange(len(vent)) / SR / 1.6), 1.0), (pad(syn.bomb_vent(), len(vent)), 0.6))
    write("bomb_vent", norm(fade(vent, 0.05, 1.8), -11.3), "bomb.vent", "subtitles.incoming.bomb.vent")
    # cavity collapse: resonant underground hit and falling debris
    cav = cut(src(675967), 0, 9)
    cav = mix((syn.reverb(F(cav, lo=30, hi=2500), t60=3.5, mix=0.55, hi=2000), 1.0),
              (pad(syn.bomb_cave(), len(cav)), 0.7))
    write("bomb_cave", norm(fade(cav, 0.002, 1.2), -12.8), "bomb.cave", "subtitles.incoming.blast")
    # ground shake: low thunder, almost infrasound
    q = F(speed(cut(src(267551), 1.2, 5.2), 0.5, "⚠ not physical: thunder one octave lower for ground shake (S5)"), lo=15, hi=140)
    q = mix((q, 1.0), (pad(syn.bomb_quake(), len(q)), 0.9))
    write("bomb_quake", norm(fade(q, 0.05, 1.5), -10.9), "bomb.quake", "subtitles.incoming.bomb.quake")


# ================================================================ sirens

def sirens():
    """Air alert: real Kyiv siren (spin-up, wail, spin-down, street echoes); nuclear alert: wartime siren
    (lower steady wail), looped throughout the detonation countdown."""
    kv = src(676589)
    s = cut(kv, 20.5, 47.0)
    write("siren", norm(fade(gate_hiss(s), 0.3, 3.0), -11.7, 0.85), "siren", "subtitles.incoming.siren")
    # recording 25–78.5 s: siren already at speed, not yet switched off (steady wail, loudness varies with horn rotation);
    # seam where loudness matches on both sides. Game pitch handles spin-up and spin-down (SirenSound). Maximum
    # momentary loudness −7 LUFS
    al = src(727588)
    a = master(loop(cut(al, 25, 78.5), 52, 1.5), -7.0, meter=lambda x: np.max(momentary(x)))
    write("nuke_alarm", a, "nuke.alarm", "subtitles.incoming.nuke.alarm")


# ================================================================ nuclear strike

def nuke():
    """Nuclear strike. Near: front hit (louder than thunder, overloaded), hurricane behind it, glass ringing; far: muffled
    hit of an enormous explosion (terrain rolls are in-game echo, NukeEchoes). ICBM launch: Titan from its pad."""
    t = src(675750)
    ln = align(cut(t, 3.0, 42))
    ln = mix((ln, 1.0), (pad(sub_thump(2.5, 34, 5, 0.8), len(ln)), 0.25))
    write("nuke_launch", norm(fade(ln, 0.01, 6.0), -14.6, 0.8), "nuke.launch", "subtitles.incoming.nuke.launch")

    # front hit: enormous explosion plus point-blank thunder plus synthesized clang, all recordings at native speed (NATIVE); the client
    # plays the pressure-jump lows with the modeled phase (BlastPulse)
    big = cut(src(741175), 0, 9)
    th = cut(src(350506), 0.9, 12)
    n = int(9.0 * SR)
    crack = mix((pad(big, n), 1.0), (pad(F(th, lo=30), n), 0.6), (pad(syn.nuke_crack(9.0), n), 0.9))
    tt = np.arange(n) / SR
    crack *= 0.35 + 0.65 * np.exp(-tt / 1.2)  # impact, then decaying rumble
    crack = crack / np.max(np.abs(crack[:int(0.5 * SR)]))
    slam = np.clip(2.5 * crack, -1, 1) * np.exp(-tt / 0.25) + np.clip(crack, -1, 1) * (1 - np.exp(-tt / 0.25))
    write("nuke_crack", fade(F(slam, hi=9000) * 0.92, 0.001, 1.5), "nuke.crack", "subtitles.incoming.nuke.blast")

    # distant detonation: far explosion at native speed, followed by terrain-rolling thunder
    far = []
    for fid, a, b in [(438538, 47.81, 59.3), (438693, 72.38, 81.4)]:
        s = F(align(gate_hiss(cut(src(fid), a, b))), lo=20, hi=1500)
        roll = F(cut(src(243782), 4.0, 20.0), lo=20, hi=900)
        x = mix((pad(s, int(16 * SR)), 1.0), (pad(np.pad(roll, (int(1.2 * SR), 0)), int(16 * SR)), 0.7),
                (pad(syn.nuke_boom_far(16.0), int(16 * SR)), 0.6))
        far.append(norm(fade(x, 0.002, 3.0), -12.8))
    variants("nuke.boom_far", "subtitles.incoming.nuke.blast", "nuke_boom_far", far)

    # hurricane wind (loop): post-front wind, volume from speed at the listener (NukeSounds)
    w = mix((F(cut(src(438880), 10.5, 43), lo=40), 1.0), (F(cut(src(438346), 20, 52.5), lo=60), 0.8),
            (syn.hurricane(32.5)[:int(32.5 * SR)], 0.35))
    write("nuke_wind", norm(loop(w, 10.0, 1.0), -11.5), "nuke.wind", "subtitles.incoming.nuke.wind")

    # glass: windows shatter along the street, many shards out of sync
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

    # Geiger clicks: real, one at a time
    g = src(674113)
    db = env_db(g, 0.001)
    peaks = [i for i in range(2, len(db) - 2) if db[i] > -12 and db[i] >= db[i - 2:i + 3].max()]
    clicks = []
    for p in peaks[::max(1, len(peaks) // 12)][:4]:
        k = int(p * 0.001 * SR) - int(0.002 * SR)
        clicks.append(norm(fade(cut(g, k / SR, k / SR + 0.03), 0.0005, 0.01), -18.8, 0.85))
    variants("geiger.click", "subtitles.incoming.geiger", "geiger_click", clicks)


def after():
    """After a nuclear strike: distant front hit (model synthesis; recording as fallback without own OpenAL sources),
    tinnitus (synthesized loop), burning-city roar from real house-fire recordings at native speed (loops; volume
    from fire heat, Sources.NUKE_FIRE), ruin collapses (synthesis). Inflow wind uses hurricane loop nuke_wind
    (nuke section) under its own subtitle."""
    # 2 kt at 5 km (0.3 psi, 0.73 s phase) and 15 kt at 25 km (0.12 psi, 1.66 s): BlastModel
    cracks = [syn.nuke_crack_far(4.0, t_plus=tp, pa=pa) for tp, pa in [(0.73, 2060.0), (1.66, 800.0)]]
    variants("nuke.crack_far", "subtitles.incoming.nuke.blast", "nuke_crack_far", cracks)
    PRELOAD.add("nuke.crack_far")
    write("nuke_tinnitus", syn.nuke_tinnitus(), "nuke.tinnitus")
    # steadiest 13 s of each recording (momentary loudness varies by less than 1 dB): steady roar, client controls volume
    fires = [norm(loop(F(cut(src(fid), a, a + 13.0), lo=25), 12.0, 1.0), -15.5) for fid, a in [(257371, 33.0), (871765, 0.0)]]
    variants("nuke.fire", "subtitles.incoming.nuke.fire", "nuke_fire", fires)
    EVENTS["nuke.fire_wind"] = ("subtitles.incoming.nuke.fire_wind", ["nuke_wind"])
    variants("nuke.collapse", "subtitles.incoming.nuke.collapse", "nuke_collapse", [syn.nuke_collapse(4.5) for _ in range(3)])


def misc():
    write("designator_lock", syn.lock_beep(), "designator.lock", "subtitles.incoming.designator.lock")
    write("silent", np.zeros(int(0.05 * SR)), "silent")


# ================================================================ grid and blackout

def spin_down(x, seconds, low=0.35):
    """Dying hum: RPM (pitch) falls to low and volume to zero over seconds, as with a stopped transformer
    and district motors."""
    n = int(seconds * SR)
    rate = low + (1 - low) * (1 - np.linspace(0, 1, n)) ** 1.6
    pos = np.cumsum(rate)
    out = np.interp(pos, np.arange(len(x)), x)
    return out * (1 - np.linspace(0, 1, n)) ** 1.3


def grid():
    """Grid. Substation failure: breakdown pop (nearby lightning hit plus synthesized bass), then crackling arc that
    dies out. Disabled-substation sparks: real 20 kV arc. Running hum: transformer at an EDF substation
    (100 Hz with odd harmonics). District goes dark: breaker click and dying hum;
    relights: switch and lamp hum."""
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


# ================================================================ N-wave

# Recording N-wave durations, s (client/sound/Booms: CRACK, BOOM): short for a projectile, long for a large body;
# client pitch (0.5…2) stretches the recording to Carlson wave duration (sound/SonicBoom).
CRACK_S, BOOM_S = 0.004, 0.032


def sonic():
    """N-wave of a supersonic body: upward jump, linear decay to equal underpressure, jump back
    (G. B. Whitham, 1952; H. W. Carlson, NASA TP-1122). Shock rise at the ear is not instantaneous: hundredths of
    a millisecond for a projectile, milliseconds for a large aircraft (turbulence and absorption); here, 2.5 % of
    duration (est.). Ground beneath the ear reflects in phase, already included in pressure (K_R = 2); local echo is
    a short tail. Peak −1 dB full scale: in-game loudness follows shock pressure, like explosions."""
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


# ---------------------------------------------------------------- sounds.json and credits

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
    """sounds.json from EVENTS; old is the previous sounds.json (--alone), supplying events from sections not built."""
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
    txt += [f"{a} - \"{t}\" - {lic} - {u} - {lic_url[lic]}" for a, t, lic, u in rows]
    with open(os.path.join(OUT, "credits.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(txt) + "\n")


def measure():
    """Source-sound spectra and loudness (SOURCES_AT) from built sounds.json files, without Freesound recordings."""
    with open(os.path.join(ASSETS, "sounds.json"), encoding="utf-8") as fh:
        data = json.load(fh)
    for ev in sorted(SOURCES_AT):
        for f in data[ev]["sounds"]:
            x, sr = sf.read(os.path.join(OUT, f["name"].split(":")[1] + ".ogg"), dtype="float64")
            if sr != SR:
                sys.exit(f"{f['name']}: {sr} Hz, build requires {SR}")
            x = x if x.ndim == 1 else x.mean(axis=1)
            SPECTRA_GOT.setdefault(ev, []).append(octaves(x))
            LUFS_GOT.setdefault(ev, []).append(source_lufs(ev, x))
    write_spectra()


def relevel():
    """Bring MOTOR event files to MOTOR_LUFS using built files (without Freesound recordings): the same master() as
    during building, gain plus transparent peak limiter; loops use circular measurement (engine), one-shots linear
    measurement (loudness). Building from recordings with the same numbers gives the same loudness without another Vorbis encode."""
    with open(os.path.join(ASSETS, "sounds.json"), encoding="utf-8") as fh:
        data = json.load(fh)
    for ev in sorted(MOTOR):
        for f in data[ev]["sounds"]:
            name = f["name"].split(":")[1]
            x, sr = sf.read(os.path.join(OUT, name + ".ogg"), dtype="float64")
            if sr != SR or x.ndim != 1:
                sys.exit(f"{name}: {sr} Hz, {x.ndim} ch., build requires {SR} Hz mono")
            loop = ev not in {"launch.booster", "rocket.launch"}
            lufs = ROCKET_LAUNCH_LUFS if ev == "rocket.launch" else MOTOR_LUFS
            y = engine(x, lufs, name, steady=False) if loop else level([master(x, lufs)], lufs)[0]
            write(name, y)


if __name__ == "__main__":
    PARTS = (drone, missile, launch, bomber, blasts, fire, rumble, bunker, sirens, nuke, after, misc, rocket, loiter, grid, sonic)
    args = sys.argv[1:]
    if args == ["--measure"]:
        measure()
        sys.exit(0)
    if args == ["--relevel"]:
        relevel()
        sys.exit(0)
    PREVIEWS = "--previews" in args
    args = [a for a in args if a != "--previews"]
    # --alone: only named sections (no Freesound recordings, e.g. synthesis in cloud); retain other
    # sounds.json events and credits
    alone = "--alone" in args
    only = set(a for a in args if a != "--alone")
    unknown = only - {p.__name__ for p in PARTS}
    if unknown:
        sys.exit(f"unknown sections: {', '.join(sorted(unknown))}; available: {', '.join(p.__name__ for p in PARTS)}")
    if alone and not only:
        sys.exit("--alone requires sections")
    old = None
    if alone:
        with open(os.path.join(ASSETS, "sounds.json"), encoding="utf-8") as fh:
            old = json.load(fh)
    for part in PARTS:
        if alone and part.__name__ not in only:
            continue
        print(part.__name__ + ("" if not only or part.__name__ in only else " (files not written)"))
        WRITING = not only or part.__name__ in only
        SECTION = part.__name__
        # each section has its own generator: editing one does not shift random numbers for others
        rng = np.random.default_rng(zlib.crc32(part.__name__.encode()))
        syn.rng = np.random.default_rng(zlib.crc32(("syn." + part.__name__).encode()))
        part()
    if alone:
        if USED:
            sys.exit("--alone: sections use recordings, which would change SOUND-CREDITS.md attribution; build without --alone")
        stale = {f["name"].split(":")[1] + ".ogg" for ev in EVENTS if ev in old for f in old[ev]["sounds"]}
        old = {ev: entry for ev, entry in old.items() if ev not in EVENTS}
        write_json(old)
        used = {f + ".ogg" for _, files in EVENTS.values() for f in files}
        used |= {f["name"].split(":")[1] + ".ogg" for entry in old.values() for f in entry["sounds"]}
        for f in stale - used:  # previous files of these events no longer used
            if os.path.exists(os.path.join(OUT, f)):
                os.remove(os.path.join(OUT, f))
        write_spectra()
        print("done:", len(EVENTS), "events rebuilt")
        sys.exit(0)
    write_json()
    write_credits()
    write_spectra()
    used = {f + ".ogg" for _, files in EVENTS.values() for f in files}
    for f in os.listdir(OUT):  # previous files no longer included in the build
        if f.endswith(".ogg") and f not in used:
            os.remove(os.path.join(OUT, f))
    print("done:", len(EVENTS), "events")
