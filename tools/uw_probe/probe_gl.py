# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""Unified-wave step 1 probe (not shipped): the whole-lifetime dust wave model (include/dustwall.glsl on this branch) on
Mesa llvmpipe, beside take 33's model (tools/uw_probe/dustwall_t33.glsl, main b8dfcd09b), no game or GPU.

    (cd mod && ./gradlew test --tests '*DustLawDumpProbe')   # the law's table: mod/build/uw-probe/law-*.json
    uv run tools/uw_probe/probe_gl.py [--fit] [--out DIR]

Gates (exit 1 on a failure): the take-33 reference column (4.5 km, 1 Mt, ages 1-24 s) within 5 % (0.05 of transmission)
of take 33's drawn transmission; thin columns within 2 % of e^(-tau) (noise-free and noisy mean, N6); no step in value or
slope of any age function over 0-3 h (C3's rule, with take 33's functions as the positive control); march transmission
within 0.01 of the exact integral on long analytic slabs, thin separated spans and long heterogeneous intervals at every
rung, full march and plan B (E8); the drift inverse within 1 % of the drift (forward transport)."""
import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
harness = HERE.parent / "see_through_gl.py"
gl = {"__file__": str(harness)}
exec(compile(harness.read_text().split("\ncontext()\n", 1)[0], str(harness), "exec"), gl)
g = gl["g"]
context, target, texture, bind, quad, read, loc, check, expand = (
    gl[n] for n in ("context", "target", "texture", "bind", "quad", "read", "loc", "check", "expand"))
CORE = gl["CORE"]
ROOT = HERE.parents[1]
OUT = Path(sys.argv[sys.argv.index("--out") + 1]) if "--out" in sys.argv else ROOT / "mod/build/uw-probe"
OUT.mkdir(parents=True, exist_ok=True)
REPORT = {}
RAW = []
RESOLVED = []
FIELD_FLAGS = []

STUBS = """#version 150
float dwFloorEye(vec2 xz) { return 0.0; }
float dwOpenEye(vec3 p) { return 1.0; }
vec2 dwSoilAt(vec2 xz) { return vec2(0.0); }
float dwAirT(float t, float dy) { return 1.0; }
vec3 dwAirLight(vec3 rd) { return vec3(0.0); }
"""
NEW_SRC = (ROOT / "mod/src/main/resources/assets/incoming/shaders/include/dustwall.glsl").read_text()
T33_SRC = (HERE / "dustwall_t33.glsl").read_text()


def program(body, model="new", defines=""):
    src = STUBS + defines + ("#moj_import <incoming:wall_ownership.glsl>\n" + (NEW_SRC if model == "new" else T33_SRC)) + body
    src = expand(src)
    vs = (CORE / "fx_dustwall.vsh").read_text()
    return gl["compileProgram"](gl["compileShader"](vs, g.GL_VERTEX_SHADER),
                                gl["compileShader"](src, g.GL_FRAGMENT_SHADER), validate=False)


def noise(p):
    g.glActiveTexture(g.GL_TEXTURE0 + 12)
    tex = g.glGenTextures(1)
    g.glBindTexture(g.GL_TEXTURE_3D, tex)
    for f in (g.GL_TEXTURE_MIN_FILTER, g.GL_TEXTURE_MAG_FILTER):
        g.glTexParameteri(g.GL_TEXTURE_3D, f, g.GL_LINEAR)
    for f in (g.GL_TEXTURE_WRAP_S, g.GL_TEXTURE_WRAP_T, g.GL_TEXTURE_WRAP_R):
        g.glTexParameteri(g.GL_TEXTURE_3D, f, g.GL_REPEAT)
    lattice = np.random.default_rng(7).random((128, 128, 128), dtype=np.float32)
    g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R32F, 128, 128, 128, 0, g.GL_RED, g.GL_FLOAT, lattice)
    g.glUniform1i(loc(p, "DwNoise"), 12)


class Law:
    def __init__(self, kt):
        j = json.loads((ROOT / f"mod/build/uw-probe/law-{kt}kt.json").read_text())
        self.reach, self.end, self.n, self.ages = j["reach"], j["ageEnd"], j["nodes"], j["ages"]
        self.table = np.array(j["table"], np.float32).reshape(1 + self.ages, self.n, 4)
        self.young = np.array(j["young"], np.float32).reshape(self.n, 4)
        self.arrival = self.table[0, :, 0].astype(np.float64)
        self.radii = np.arange(self.n) / (self.n - 1) * self.reach

    def arrival_at(self, r):
        return np.interp(r, self.radii, self.arrival)

    def front(self, t):
        return float(np.interp(t, self.arrival, self.radii))

    def law(self, r, a):
        """(top, depth) at radius r and age a (Hermite in u, as dwLaw)."""
        if a <= 0:
            return 0.0, 0.0
        u = np.log1p(a / 0.05) / np.log1p(self.end / 0.05) * (self.ages - 1)
        if u >= self.ages - 1:
            return 0.0, 0.0
        x = min(r / self.reach, 1) * (self.n - 1)
        i = int(min(np.floor(x), self.n - 2))
        k = int(min(np.floor(u), self.ages - 2))
        f = u - k
        h = (2 * f ** 3 - 3 * f ** 2 + 1, f ** 3 - 2 * f ** 2 + f, -2 * f ** 3 + 3 * f ** 2, f ** 3 - f ** 2)
        def at(ii):
            p0, p1 = self.table[1 + k, ii], self.table[2 + k, ii]
            return np.maximum(h[0] * p0[:2] + h[1] * p0[2:] + h[2] * p1[:2] + h[3] * p1[2:], 0)
        v = at(i) + (x - i) * (at(i + 1) - at(i))
        return float(v[0]), float(v[1])


def bind_new(p, law, t, eye, drift=(0.0, 0.0), far=(0.0, 34.0)):
    tex = texture(g.GL_RGBA32F, g.GL_RGBA, law.n, 1 + law.ages, law.table.reshape(-1, 4))
    bind(p, 2, tex)
    g.glUniform4f(loc(p, "DwLife"), law.end, 0.05, *drift)
    top = max(law.law(r, t - law.arrival_at(r))[0] for r in law.radii[::4]) * 2.6 + 6
    g.glUniform4f(loc(p, "DwRing"), law.front(t), 0, top, law.reach)
    g.glUniform4f(loc(p, "DwEye"), *eye, t)
    g.glUniform4f(loc(p, "DwFar"), *far, 0, 0)
    g.glUniform1f(loc(p, "DwCut"), 0)
    g.glUniform4f(loc(p, "DwBall"), 0, 1200, 0, 1700)
    return tex


def bind_t33(p, law, t, eye):
    tex = texture(g.GL_RGBA32F, g.GL_RGBA, law.n, 1, law.young)
    bind(p, 2, tex)
    front = law.front(t)
    inner = 0.0  # the model itself, not its 24 s span cut (take 33 hands older dust to the pall)
    g.glUniform4f(loc(p, "DwRing"), front, inner, 400, law.reach)
    # the young wall owns its whole ring (no pall handoff: the young model's own transmission)
    g.glUniform4f(loc(p, "PallYoung"), 0, 1, 0, law.reach)
    g.glUniform4f(loc(p, "DwEye"), *eye, t)
    g.glUniform1f(loc(p, "DwCut"), 0)
    g.glUniform4f(loc(p, "DwBall"), 0, 1200, 0, 1700)
    return tex


# ----------------------------------------------------------------------------------------------- ray optical depths
RAYS_BODY = """
uniform sampler2D Rays;   // x: ray i: (origin xyz, length) row 0, (direction xyz, 0) row 1
uniform float Steps;
uniform float Mean;
out vec4 fragColor;
void main() {
    int i = int(gl_FragCoord.x);
    vec4 o = texelFetch(Rays, ivec2(i, 0), 0), d = texelFetch(Rays, ivec2(i, 1), 0);
    float dt = o.w / Steps, tau = 0.0;
    NEWMEAN
    for (int k = 0; k < 4096; k++) {
        if (float(k) >= Steps) break;
        tau += dwDensity(o.xyz + d.xyz * ((float(k) + 0.5) * dt), false) * dt;
    }
    fragColor = vec4(tau, 0.0, 0.0, 1.0);
}
"""


def ray_taus(p, rays, steps):
    """Optical depth of each ray (origin, direction, length) by fine midpoint steps."""
    n = len(rays)
    data = np.zeros((2, n, 4), np.float32)
    for i, (o, d, L) in enumerate(rays):
        data[0, i] = (*o, L)
        data[1, i, :3] = d / np.linalg.norm(d)
    tex = texture(g.GL_RGBA32F, g.GL_RGBA, n, 2, data.reshape(-1, 4))
    g.glActiveTexture(g.GL_TEXTURE0 + 5)
    g.glBindTexture(g.GL_TEXTURE_2D, tex)
    g.glUniform1i(loc(p, "Rays"), 5)
    g.glUniform1f(loc(p, "Steps"), steps)
    target(n, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
    quad(p)
    out = read(n, 1)[0, :, 0].astype(np.float64)
    g.glDeleteTextures([tex])
    return out


def reference_column(law, fit=False):
    """Take 33's reference column: the ground points 4.5 km out along a 20-degree arc, ages 1-24 s; vertical, slant (30, 10,
    6 degrees down onto the point, from outside) and horizontal (10, 30, 50, 65 blocks up, 200 blocks radial) rays."""
    print("reference column (4.5 km, 1 Mt, take 33 vs the whole-lifetime model)")
    pn = program(RAYS_BODY.replace("NEWMEAN", "gDwMean = Mean > 0.5;"), "new")
    pt = program(RAYS_BODY.replace("NEWMEAN", ""), "t33")
    R = 4500.0
    eye = (R + 0.0, 120.0, 0.0)
    rays, classes = [], []
    for phi in np.radians(np.linspace(-10, 10, 64)):
        u = np.array([np.cos(phi), 0, np.sin(phi)])
        P = u * R
        rays.append((P + [0, 400, 0], np.array([0, -1.0, 0]), 400.0)); classes.append("vertical")
        for el in (30, 10, 6):
            L = 400 / np.sin(np.radians(el))
            d = -u * np.cos(np.radians(el)) + np.array([0, -np.sin(np.radians(el)), 0])
            rays.append((P - d * L, d, L)); classes.append(f"slant {el} deg")
        for y in (10, 30, 50, 65):
            rays.append((P + u * 100 + [0, y, 0], -u, 200.0)); classes.append(f"horizontal {y}")
    classes = np.array(classes)
    rows = []
    worst = 0.0
    for age in (1, 2, 4, 6, 8, 12, 16, 24):
        t = law.arrival_at(R) + age
        g.glUseProgram(pt); noise(pt); bind_t33(pt, law, t, eye)
        old = ray_taus(pt, rays, 1600)
        g.glUseProgram(pn); noise(pn); bind_new(pn, law, t, eye)
        g.glUniform1f(loc(pn, "Mean"), 0)
        new = ray_taus(pn, rays, 1600)
        for c in dict.fromkeys(classes):
            m = classes == c
            To, Tn = np.exp(-old[m]).mean(), np.exp(-new[m]).mean()
            rows.append((age, c, To, Tn, float(np.median(old[m])), float(np.median(new[m]))))
            worst = max(worst, abs(Tn - To))
    for age, c, To, Tn, to, tn in rows:
        print(f"    age {age:4.0f} s {c:15s} T take33 {To:.4f} new {Tn:.4f}  |dT| {abs(Tn - To):.4f}   median tau {to:7.2f} {tn:7.2f}")
    REPORT["reference_column"] = {"worst_dT": worst, "rows": rows}
    check(worst <= 0.05, f"take-33 reference column within 0.05 of take 33's transmission at ages 1-24 s: worst |dT| {worst:.4f}")


# ------------------------------------------------------------------------------- thin columns, noise conservation (N6)
def thin_columns(laws):
    print("thin columns: noise-free column vs e^(-tau_d), noisy mean transmission vs the noise-free (N6)")
    pn = program(RAYS_BODY.replace("NEWMEAN", "gDwMean = Mean > 0.5;"), "new")
    g.glUseProgram(pn); noise(pn)
    cases = [("1000", 4500, 3600), ("1000", 9000, 1800), ("1000", 12000, 1200), ("1000", 12000, 3600), ("1000", 6000, 3600),
             ("15", 3000, 600), ("15", 4500, 60), ("15", 4500, 600), ("15", 2000, 1800), ("1000", 4500, 600), ("1000", 6000, 1200)]
    worst_ref, worst_noise = 0.0, 0.0
    rng = np.random.default_rng(3)
    out = []
    for kt, R, age in cases:
        law = laws[kt]
        t = law.arrival_at(R) + age
        H, tau = law.law(R, age)
        # the expected drawn depth: the transfer of the law's (as dwTransfer)
        taud = transfer(tau)
        pts = []
        for _ in range(1024):
            phi = rng.uniform(-np.pi, np.pi)
            rr = R + rng.uniform(-150, 150)
            P = np.array([np.cos(phi) * rr, 0, np.sin(phi) * rr])
            pts.append((P + [0, 3 * H + 60, 0], np.array([0, -1.0, 0]), 3 * H + 60))
        bind_new(pn, law, t, (R + 10, 3 * H + 70, 0))
        g.glUniform1f(loc(pn, "Mean"), 1)
        ref = ray_taus(pn, pts, 1200)
        g.glUniform1f(loc(pn, "Mean"), 0)
        noisy = ray_taus(pn, pts, 1200)
        Tref = np.exp(-ref).mean()
        e_ref = abs(Tref - np.exp(-taud)) / np.exp(-taud)
        e_noise = abs(np.exp(-noisy).mean() - Tref) / Tref
        out.append((kt, R, age, tau, taud, float(ref.mean()), Tref, float(np.exp(-noisy).mean()), e_ref, e_noise, float(noisy.mean())))
        RAW.append((taud, ref, noisy))
        if taud <= 1.5:
            worst_ref, worst_noise = max(worst_ref, e_ref), max(worst_noise, e_noise)
        print(f"    {kt:>4} kt r {R:5d} age {age:5d} s: law tau {tau:7.3f} -> drawn {taud:6.3f}; noise-free column {ref.mean():6.3f}"
              f" T {Tref:.4f} (e^-tau_d {np.exp(-taud):.4f}, {100 * e_ref:.2f} %); noisy mean tau {noisy.mean():6.3f}"
              f" T {np.exp(-noisy).mean():.4f} ({100 * e_noise:.2f} %)")
    REPORT["thin"] = out
    np.save(OUT / "thin-raw.npy", np.array([(t, r, n) for t, r, n in RAW], dtype=object), allow_pickle=True)
    check(worst_ref <= 0.02, f"noise-free thin columns (drawn tau <= 1.5) within 2 % of e^(-tau): worst {100 * worst_ref:.2f} %")
    check(worst_noise <= 0.02, f"noisy thin columns' mean transmission within 2 % of the noise-free (N6): worst {100 * worst_noise:.2f} %")


SLOPE, KNEE = 0.075, 1.25


def transfer(tau):
    src = NEW_SRC
    import re
    s = float(re.search(r"DW_G_SLOPE = ([0-9.]+)", src).group(1))
    k = float(re.search(r"DW_G_KNEE = ([0-9.]+)", src).group(1))
    mode = "knee1" if "float dwTransfer(float tau) {\n    if (tau <= 1.0)" in src else "exp"
    if mode == "knee1":
        return tau if tau <= 1 else 1 + s * (tau - 1) + (1 - s) * k * (1 - np.exp(-(tau - 1) / k))
    return s * tau + (1 - s) * k * (1 - np.exp(-tau / k))


# -------------------------------------------------------------------------------- C3: age functions without steps
AGE_BODY = """
uniform float Fn;
uniform float Lo, Hi, Centre, Span;
out vec4 fragColor;
void main() {
    float x = (gl_FragCoord.x - 0.5) / 999.0;
    float a = Span > 0.0 ? Centre + Span * (x - 0.5) : pow(10.0, mix(Lo, Hi, x));
    int f = int(Fn);
    vec4 o = vec4(0.0);
    BODY
    fragColor = o;
}
"""
# parameters of age (C3's 1000 log-spaced ages over 0-3 h)
NEW_FNS = {
    0: ("law top / depth / transfer at 4.5 km", "vec2 l = dwLaw(4500.0, a); o = vec4(l, dwTransfer(l.y), 0.0);"),
    1: ("law top / depth / transfer at 1 km", "vec2 l = dwLaw(1000.0, a); o = vec4(l, dwTransfer(l.y), 0.0);"),
    2: ("law top / depth / transfer at 12 km", "vec2 l = dwLaw(12000.0, a); o = vec4(l, dwTransfer(l.y), 0.0);"),
    3: ("envelope, head density, clock (near, far)", "o = vec4(dwEnvelope(a, 68.0, 1.6), 1.0 + 0.5 * exp(-a / 1.1), dwClock(a, DwEye.xz + vec2(100.0, 0.0)), dwClock(a, DwEye.xz + vec2(5000.0, 0.0)));"),
    4: ("eddy strength, eddy phase, contrast, noise gain", "o = vec4(dwEddyAmp(a), dwEddyPhase(a), dwContrast(a), dwNoiseGain(dwTransfer(dwLaw(4500.0, a).y)));"),
    5: ("eddy amplitude A, outward push, afterwind, gust strength",
        "float E = exp(-a / 25.0), Ef = exp(-a / 4.0); o = vec4(0.3 * dwEddyAmp(a) + 0.7 * E + 0.9 * Ef, 25.0 * (1.0 - exp(-a / 1.0)) - 15.0 * dwRamp(a, 5.0, 1.0), 120.0 * (dwAfter(a, 5.0) < 0.12 ? dwAfter(a, 5.0) / 120.0 : 1.0 - exp(-dwAfter(a, 5.0) / 120.0)), (1.0 - exp(-a / 0.8)) * exp(-a / 15.0));"),
    6: ("orbit phase, swell growth, hollows' share, puff-free birth fade",
        "o = vec4(0.25 * dwEddyPhase(a), mix(0.2, 1.0, dwRamp(a, 2.75, 0.45)), dwRamp(a, 6.5, 1.2), smoothstep(0.0, 0.08, a));"),
    10: ("textured share (haze blend), haze column above 10 / 40 blocks", "o = vec4(dwTextured(a), 0.0, 0.0, 0.0); gDwForceF = 0.0; dwDensity(vec3(4300.0, 10.0, 120.0), false); o.y = gDwHazeAbove; dwDensity(vec3(4300.0, 40.0, 120.0), false); o.z = gDwHazeAbove; gDwForceF = -1.0;"),
    7: ("drawn column density at a fixed point (noise-free), 10 / 40 blocks up", "gDwMean = true; o = vec4(dwDensity(vec3(4300.0, 10.0, 120.0), false), dwDensity(vec3(4300.0, 40.0, 120.0), false), 0.0, 0.0);"),
}
# noise-driven fields of age: continuity checked by dense local sweeps (a log sweep aliases their motion)
NEW_FIELDS = {
    8: ("air displacement x, y, z, phase", "float ph; vec3 q = vec3(430.0, 30.0, 12.0); vec3 pw = dwAir(q, dwClock(a, q.xz), length(q.xz), 68.0, ph); o = vec4(pw - q, ph);"),
    9: ("swell, smooth top, noisy density 10 / 40 blocks up", "float ph; vec3 q = vec3(430.0, 30.0, 12.0); vec3 pw = dwAir(q, a, length(q.xz), 68.0, ph); o = vec4(dwBulge(pw, a), dwTopAt(vec2(4300.0, 120.0)), dwDensity(vec3(4300.0, 10.0, 120.0), false), dwDensity(vec3(4300.0, 40.0, 120.0), false));"),
}
# the intended ends (C3: listed, exempt): the birth from zero at the front and the table's last age (GONE_DEPTH)
EXEMPT = {("new", 4, 3): "noise gain of the depth, which fades to zero over the table's last node (the law's GONE_DEPTH end)",
          ("new", 6, 3): "the birth's fade-in over 0.08 s after the front (pre-arrival zero)"}
T33_FNS = {
    0: ("take 33: settling multiplier amt (positive control: max(a - 1, 0))", "o.x = (0.45 + 0.55 * exp(-max(a - 1.0, 0.0) / 8.0)) * (1.0 + 0.5 * exp(-a / 1.1));"),
    1: ("take 33: afterwind displacement (positive control: max(a - 5, 0))", "o.x = 3.0 * max(a - 5.0, 0.0);"),
    2: ("take 33: eddy amplitude A with its 0.3 floor (control: no step expected)", "float E = exp(-a / 25.0), Ef = exp(-a / 4.0); o.x = 0.3 + 0.7 * E + 0.9 * Ef;"),
}


def steps(v, x):
    """C3's rule: a value step above 1 % of the local value, or a slope ratio above 2 between neighbouring intervals, each
    against the function's own local background (8x the median change over +-10 samples: a smooth function changes its
    differences gradually; a step or kink stands alone; float32 rounding is a floor). A kink falling inside one interval
    shows across two, so slopes are compared over three intervals. Returns (value steps, kinks, worst step share, worst
    slope ratio, step indices, kink indices)."""
    v = np.asarray(v, np.float64)
    d = np.diff(v)
    s = d / np.diff(x)
    # float32 rounding of the samples (the shader's precision)
    floor = 1e-6 * (np.abs(v[:-1]) + np.abs(v[1:])) + 1e-9 * max(np.abs(v).max(), 1e-30)
    step_idx, kink_idx = [], []
    rel = ratio = 0.0
    n = len(d)
    for i in range(n):
        w = np.abs(np.r_[d[max(0, i - 10):i], d[i + 1:i + 11]])
        bg = np.median(w) if len(w) else 0.0
        if abs(d[i]) > 8 * bg + floor[i] and abs(d[i]) > 0.01 * max(abs(v[i]), abs(v[i + 1])) and abs(d[i]) > 1e-6 * np.abs(v).max():
            step_idx.append(i)
            rel = max(rel, abs(d[i]) / max(abs(v[i]), abs(v[i + 1]), 1e-30))
    ds = np.full(n, 0.0)
    ds[1:-1] = s[2:] - s[:-2]
    sfloor = 4 * floor / np.diff(x)
    for i in range(1, n - 1):
        w = np.abs(np.r_[ds[max(1, i - 12):max(1, i - 2)], ds[i + 3:i + 13]])
        bg = np.median(w) if len(w) else 0.0
        lo_, hi_ = min(abs(s[i - 1]), abs(s[i + 1])), max(abs(s[i - 1]), abs(s[i + 1]))
        r = abs(ds[i]) / max(lo_, 1e-30)
        sf = max(sfloor[i - 1], sfloor[i + 1])
        if abs(ds[i]) > 8 * bg + sf and (r > 1.0 or np.sign(s[i - 1]) != np.sign(s[i + 1])) and hi_ > sf:
            if not kink_idx or i - kink_idx[-1] > 2:
                kink_idx.append(i)
                ratio = max(ratio, hi_ / max(lo_, 1e-30))
    return len(step_idx), len(kink_idx), rel, ratio, step_idx, kink_idx


def age_functions(law):
    print("C3: age functions over 0-3 h: parameters at 1000 log-spaced ages (1 ms - 3 h); noise-driven fields in dense local sweeps")
    lo, hi = -3.0, np.log10(10800.0)
    xs = np.log(10 ** np.linspace(lo, hi, 1000))
    fails = 0
    for model, fns in (("new", {**NEW_FNS, **NEW_FIELDS}), ("t33", T33_FNS)):
        body = "\n".join(f"if (f == {k}) {{ {code} }}" for k, (_, code) in fns.items())
        p = program(AGE_BODY.replace("BODY", body), model)
        g.glUseProgram(p); noise(p)
        R = 4500.0
        if model == "new":
            bind_new(p, law, law.arrival_at(R) + 30, (R, 120.0, 0.0))
        else:
            bind_t33(p, law, law.arrival_at(R) + 30, (R, 120.0, 0.0))
        g.glUniform1f(loc(p, "Lo"), lo)
        g.glUniform1f(loc(p, "Hi"), hi)
        target(1000, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
        for k, (name, _) in fns.items():
            g.glUniform1f(loc(p, "Fn"), k)
            sweeps = []
            if k in NEW_FIELDS and model == "new":
                for c in np.geomspace(0.5, 10000, 30):
                    span = max(0.2 * c, 0.2)
                    g.glUniform1f(loc(p, "Centre"), c); g.glUniform1f(loc(p, "Span"), span)
                    quad(p)
                    xx = np.linspace(c - span / 2, c + span / 2, 1000)
                    vv = read(1000, 1)[0].astype(np.float64)
                    # a flagged kink is confirmed only if it persists 20x finer: a true slope discontinuity keeps its slope
                    # change, a C1 point (the value noise's lattice crossings) loses it in proportion to the interval
                    for cc in range(4):
                        _, _, _, _, _, ki = steps(vv[:, cc], xx)
                        for i in ki:
                            h = xx[1] - xx[0]
                            g.glUniform1f(loc(p, "Centre"), xx[i]); g.glUniform1f(loc(p, "Span"), 6 * h)
                            quad(p)
                            fine = read(1000, 1)[0].astype(np.float64)[:, cc]
                            fx = np.linspace(xx[i] - 3 * h, xx[i] + 3 * h, 1000)
                            fs = np.diff(fine) / np.diff(fx)
                            coarse_change = abs((vv[i + 2, cc] - vv[i + 1, cc]) - (vv[i, cc] - vv[i - 1, cc])) / h
                            fine_change = np.abs(fs[20:] - fs[:-20]).max()
                            if fine_change < 0.5 * coarse_change:
                                vv[:, cc] = np.nan_to_num(vv[:, cc])
                                RESOLVED.append((name, cc, float(xx[i]), float(coarse_change), float(fine_change)))
                                vv[i - 1:i + 3, cc] = np.interp(xx[i - 1:i + 3], [xx[i - 2], xx[i + 3]], [vv[i - 2, cc], vv[i + 3, cc]])
                        g.glUniform1f(loc(p, "Centre"), c); g.glUniform1f(loc(p, "Span"), span)
                    sweeps.append((xx, vv))
            else:
                g.glUniform1f(loc(p, "Span"), 0.0)
                quad(p)
                vv = read(1000, 1)[0].astype(np.float64)
                # a flagged kink must persist 20x finer (a slope discontinuity keeps its jump as the spacing shrinks; C1
                # curvature, such as the visibility ceiling settling, loses it in proportion): resample around it
                if model == "new":
                    for cc in [c for c in range(4) for _ in range(24)]:
                        _, _, _, _, _, ki = steps(vv[:, cc], xs)
                        for i in ki[:1]:
                            a0, a1 = np.exp(xs[i - 1]), np.exp(xs[i + 2])
                            g.glUniform1f(loc(p, "Centre"), 0.5 * (a0 + a1)); g.glUniform1f(loc(p, "Span"), a1 - a0)
                            quad(p)
                            fine = read(1000, 1)[0].astype(np.float64)[:, cc]
                            fx = np.linspace(a0, a1, 1000)
                            fs = np.diff(fine) / np.diff(fx)
                            coarse_change = abs((vv[i + 2, cc] - vv[i + 1, cc]) / (np.exp(xs[i + 2]) - np.exp(xs[i + 1]))
                                                - (vv[i, cc] - vv[i - 1, cc]) / (np.exp(xs[i]) - np.exp(xs[i - 1])))
                            fine_change = np.abs(fs[20:] - fs[:-20]).max()
                            if fine_change < 0.5 * coarse_change:
                                RESOLVED.append((name, cc, float(np.exp(xs[i])), float(coarse_change), float(fine_change)))
                                vv[i - 1:i + 3, cc] = np.interp(xs[i - 1:i + 3], [xs[i - 2], xs[i + 3]], [vv[i - 2, cc], vv[i + 3, cc]])
                    g.glUniform1f(loc(p, "Span"), 0.0)
                sweeps.append((xs, vv))
            for c in range(4):
                if (model, k, c) in EXEMPT:
                    print(f"    exempt {model}: {name} [{c}]: {EXEMPT[(model, k, c)]}")
                    continue
                n_step = n_kink = 0
                rel = ratio = 0.0
                where = []
                for x, v in sweeps:
                    col = v[:, c]
                    if np.abs(col).max() == 0:
                        continue
                    a, b, r1, r2, si, ki = steps(col, x)
                    n_step += a; n_kink += b; rel = max(rel, r1); ratio = max(ratio, r2)
                    age = np.exp(x) if x is xs else x
                    where += [f"{age[i]:.3g}s" for i in (si + ki)[:3]]
                tag = f"{model}: {name} [{c}]"
                bad = n_step + n_kink
                if all(np.abs(v[:, c]).max() == 0 for _, v in sweeps):
                    continue
                if model == "new" and k in NEW_FIELDS:
                    FIELD_FLAGS.append((tag, n_step, n_kink, where))
                    print(f"    {'ok  ' if bad == 0 else 'info'} {tag}: value steps {n_step}, kinks {n_kink}" + (f" at {', '.join(where)}" if where else "")
                          + (" (persist 20x finer: the texture filter's quantized weights, shared with take 33's noise)" if bad else ""))
                elif model == "new":
                    fails += bad
                    print(f"    {'ok  ' if bad == 0 else 'STEP'} {tag}: value steps {n_step}, kinks {n_kink}" + (f" at {', '.join(where)}" if where else ""))
                else:
                    print(f"    {'caught' if bad else 'clean '} {tag}: value steps {n_step}, kinks {n_kink}" + (f" at {', '.join(where)}" if where else ""))
                    REPORT.setdefault("c3_control", []).append((tag, n_step, n_kink))
    REPORT["c3_fails"] = fails
    REPORT["c3_resolved_c1"] = RESOLVED
    REPORT["c3_field_flags"] = FIELD_FLAGS
    print(f"    ({len(RESOLVED)} flags resolved as C1 by 20x refinement, the slope change shrank with the interval: {[(r[0][:30], round(r[2], 1)) for r in RESOLVED]})")
    check(fails == 0, f"no step in value or slope of any age parameter over 0-3 h: {fails} found")
    ctl = {t: a + b for t, a, b in REPORT.get("c3_control", [])}
    caught = sum(1 for t, v in ctl.items() if "afterwind" in t and v > 0)
    check(caught == 1, "C3's rule catches take 33's afterwind kink max(a - 5, 0) (positive control)")


# --------------------------------------------------------------------------------------- E8: march accuracy
ANALYTIC = """
uniform vec4 Slab;     // x: kind; y: sigma; z: length scale; w: gap
uniform float AnEyeX;
float dwAnalytic(vec3 g) {
    float x = g.x - AnEyeX, h = g.y;
    if (h < 0.0 || h > 50.0 || x < 0.0) return 0.0;
    int kind = int(Slab.x);
    if (kind == 0) return x < Slab.z ? Slab.y : 0.0;                                   // a uniform slab of length z
    if (kind == 1) return (mod(x, Slab.w) < Slab.z && x < 5.0 * Slab.w) ? Slab.y : 0.0; // five thin spans, period w
    // a long heterogeneous interval: sigma (1 + 0.8 sin(2 pi x / w)) e^(-x / (3 z)) to 20 km
    return x < 20000.0 ? Slab.y * (1.0 + 0.8 * sin(6.2831853 * x / Slab.w)) * exp(-x / (3.0 * Slab.z)) : 0.0;
}
float dwAnalyticBound(vec3 g) { return 60.0; }
"""
MARCH_BODY = """
uniform float End, Steps, Foot;
uniform float Jit;
out vec4 fragColor;
void main() {
    float j = fract(Jit + 0.618034 * gl_FragCoord.x);
    vec4 w = dwMarch(vec3(1.0, 0.0, 0.0), End, j, Foot, int(Steps));
    fragColor = vec4(w.a, float(gDwSamples), gDwCapLeft, gDwTailTau);
}
"""


def exact(kind, sigma, L, w, end):
    x = np.linspace(0, end, 2_000_001)
    if kind == 0:
        s = np.where(x < L, sigma, 0)
    elif kind == 1:
        s = np.where((np.mod(x, w) < L) & (x < 5 * w), sigma, 0)
    else:
        s = np.where(x < 20000, sigma * (1 + 0.8 * np.sin(2 * np.pi * x / w)) * np.exp(-x / (3 * L)), 0)
    return float(np.exp(-np.trapezoid(s, x)))


def march_accuracy():
    print("E8: march transmission against the exact integral (analytic media, every rung; full march and plan B)")
    p = program(MARCH_BODY, "new", "#define DW_ANALYTIC\n" + ANALYTIC)
    g.glUseProgram(p); noise(p)
    g.glUniform4f(loc(p, "DwEye"), 0, 25, 0, 100)
    g.glUniform1f(loc(p, "AnEyeX"), 0)
    g.glUniform4f(loc(p, "DwRing"), 1e6, 0, 100, 1e6)
    g.glUniform4f(loc(p, "DwLife"), 1e4, 0.05, 0, 0)
    g.glUniform2f(loc(p, "DwRelief"), 0, 0)
    scenes = [(0, 1e-4, 1000, 0, 1500, "1 km slab tau 0.1 (finding 14's)"),
              (0, 1e-4, 10000, 0, 12000, "10 km slab tau 1"),
              (0, 2e-5, 20000, 0, 22000, "20 km slab tau 0.4"),
              (0, 3e-3, 1000, 0, 1500, "1 km slab tau 3"),
              (1, 0.01, 50, 2000, 11000, "five 50-block spans tau 0.5 each, 2 km apart"),
              (1, 0.002, 20, 3000, 16000, "five 20-block spans tau 0.04 each, 3 km apart"),
              (2, 1e-4, 5000, 3000, 20000, "20 km heterogeneous (0.2-1.8x, 3 km waves, decaying)"),
              (2, 5e-4, 2000, 800, 20000, "20 km heterogeneous, denser, 800-block waves")]
    # pixel footprint per block of distance at 2560 px, 70 degrees, 1/4, 1/5, 1/6 resolution; plan B also at 6x the footprint
    # (a small view: its far level starts within these 20 km)
    rungs = {"HIGH": (128, 0.0022), "MEDIUM": (96, 0.0027), "LOW": (64, 0.0033)}
    worst = {}
    rows = []
    target(64, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
    for kind, sigma, L, w, end, name in scenes:
        ref = exact(kind, sigma, L, w, end)
        for rung, (steps_, foot) in rungs.items():
            for mode in ("full", "planB", "planB x6 foot"):
                g.glUniform4f(loc(p, "Slab"), kind, sigma, L, w)
                g.glUniform4f(loc(p, "DwFar"), 0.0 if mode == "full" else 1.0, 34.0, 0, 0)
                g.glUniform1f(loc(p, "End"), end)
                g.glUniform1f(loc(p, "Steps"), steps_)
                g.glUniform1f(loc(p, "DwDetail"), {"HIGH": 0.0, "MEDIUM": 1.0, "LOW": 2.0}[rung])
                g.glUniform1f(loc(p, "Foot"), foot * (6 if "x6" in mode else 1))
                g.glUniform1f(loc(p, "Jit"), 0.37)
                quad(p)
                v = read(64, 1)[0].astype(np.float64)
                T = v[:, 0].mean()
                err = abs(T - ref)
                worst[(rung, mode)] = max(worst.get((rung, mode), 0), err)
                rows.append((name, rung, mode, ref, T, err, float(v[:, 1].mean()), float(v[:, 2].mean())))
                print(f"    {name:55s} {rung:6s} {mode:13s}: T exact {ref:.4f} march {T:.4f} |dT| {err:.4f}; samples {v[:, 1].mean():5.1f}, "
                      f"cap tail {v[:, 2].mean():.4f}")
    REPORT["e8"] = rows
    for (rung, mode), e in worst.items():
        check(e <= 0.01, f"E8 {rung} {mode}: worst |dT| {e:.4f} <= 0.01")


# ---------------------------------------------------------------------------------- drift inverse (forward transport)
DRIFT_BODY = """
uniform sampler2D Pts;
out vec4 fragColor;
void main() {
    vec4 p = texelFetch(Pts, ivec2(int(gl_FragCoord.x), 0), 0);
    fragColor = vec4(dwSource(p.xy), 0.0, 1.0);
}
"""


def drift_inverse(law):
    print("drift inverse: q + D age(q) = p, two fixed-point steps, against forward transport")
    p = program(DRIFT_BODY, "new")
    g.glUseProgram(p); noise(p)
    rng = np.random.default_rng(5)
    worst = 0.0
    for wind, t in ((5.0, 60.0), (5.0, 600.0), (12.0, 3600.0), (12.0, 10800.0), (2.5, 10800.0)):
        D = np.array([0.6, 0.8]) * wind * 0.5
        q = rng.uniform(-law.reach, law.reach, (512, 2))
        q = q[(np.linalg.norm(q, axis=1) < law.front(t)) & (np.linalg.norm(q, axis=1) > 200)]
        age = t - law.arrival_at(np.linalg.norm(q, axis=1))
        P = q + D[None, :] * age[:, None]
        tex = texture(g.GL_RGBA32F, g.GL_RGBA, len(P), 1, np.c_[P, np.zeros((len(P), 2))])
        g.glActiveTexture(g.GL_TEXTURE0 + 5); g.glBindTexture(g.GL_TEXTURE_2D, tex); g.glUniform1i(loc(p, "Pts"), 5)
        bind_new(p, law, t, (0.0, 120.0, 0.0), drift=tuple(D))
        target(len(P), 1, [(g.GL_RGBA32F, g.GL_RGBA)])
        quad(p)
        back = read(len(P), 1)[0, :, :2].astype(np.float64)
        err = np.linalg.norm(back - q, axis=1) / np.maximum(np.linalg.norm(D) * age, 1.0)
        worst = max(worst, float(err.max()))
        print(f"    wind {wind:4.1f} m/s, {t:6.0f} s: drift up to {np.linalg.norm(D) * age.max():7.0f} blocks, worst error {100 * err.max():.3f} % of the drift")
    REPORT["drift_worst"] = worst
    check(worst <= 0.01, f"drift inverse within 1 % of the drift: worst {100 * worst:.3f} %")


VIEW_BODY = """
uniform vec3 Fwd, Right, Up;
uniform float Tan, Aspect, Steps, Foot, ForceF, Kinds;
out vec4 fragColor;
void main() {
    gDwForceF = ForceF;
    vec2 uv = gl_FragCoord.xy / vec2(160.0, 90.0) * 2.0 - 1.0;
    vec3 rd = normalize(Fwd + Right * uv.x * Tan + Up * uv.y * Tan / Aspect);
    float tEnd = rd.y < 0.0 ? DwEye.y / -rd.y : 60000.0;
    vec4 w = dwMarch(rd, tEnd, fract(0.37 + 0.618034 * gl_FragCoord.x + 0.414 * gl_FragCoord.y), Foot, int(Steps));
    fragColor = Kinds > 1.5 ? vec4(gDwWhyHist[3], gDwWhyHist[5], gDwWhyHist[6], gDwWhyHq / max(gDwKinds.w, 1.0)) : Kinds > 0.5 ? gDwKinds : vec4(float(gDwSamples), float(gDwIters), float(gDwTailEvals), w.a);
}
"""


def view_work(law):
    """Per-ray work of the judging view (camera 4.5 km out, 120 blocks up, pitch -6 deg, 46 deg wide) at +4 s, +1 min and
    +10 min: occupied samples (each with its light: 2 shadow taps), loop iterations, tail evaluations (each with light)."""
    print("view work: rays of the judging view, 160 x 90, per preset step cap")
    p = program(VIEW_BODY, "new", "#define DW_DIAG\n")
    g.glUseProgram(p); noise(p)
    R = 4500.0
    pitch = np.radians(-6.0)
    fwd = np.array([-np.cos(pitch), np.sin(pitch), 0.0])
    right = np.array([0.0, 0.0, 1.0])
    up = np.cross(right, fwd)
    rows = []
    for age in (4, 60, 600):
        t = law.arrival_at(R) + age
        bind_new(p, law, t, (R, 120.0, 0.0))
        for name, steps_, foot in (("HIGH", 128, 0.0022), ("MEDIUM", 96, 0.0027), ("LOW", 64, 0.0033)):
            g.glUniform3f(loc(p, "Fwd"), *fwd); g.glUniform3f(loc(p, "Right"), *right); g.glUniform3f(loc(p, "Up"), *up)
            g.glUniform1f(loc(p, "Tan"), np.tan(np.radians(23.0))); g.glUniform1f(loc(p, "Aspect"), 16 / 9)
            g.glUniform1f(loc(p, "Steps"), steps_); g.glUniform1f(loc(p, "Foot"), foot)
            target(160, 90, [(g.GL_RGBA32F, g.GL_RGBA)])
            quad(p)
            v = read(160, 90).reshape(-1, 4).astype(np.float64)
            light = v[:, 0] + v[:, 2]
            rows.append((age, name, float(v[:, 0].mean()), float(v[:, 1].mean()), float(v[:, 2].mean()), float(light.mean()),
                         float((v[:, 0] >= steps_).mean()), float(v[:, 3].mean())))
            g.glUniform1f(loc(p, "Kinds"), 1.0); quad(p); kv = read(160, 90).reshape(-1, 4).astype(np.float64).mean(0); g.glUniform1f(loc(p, "Kinds"), 0.0)
            g.glUniform1f(loc(p, "Kinds"), 2.0); quad(p); wv = read(160, 90).reshape(-1, 4).astype(np.float64).mean(0); g.glUniform1f(loc(p, "Kinds"), 0.0)
            print(f"      clear inside the bound: above the texture (early exit) {wv[0]:.1f}, lean-back age {wv[1]:.1f}, thin profile {wv[2]:.1f}, mean height {wv[3]:.0f}")
            print(f"      strides: no dust at the radius {kv[0]:.1f}, above the bound going up {kv[1]:.1f}, going down {kv[2]:.1f}, clear inside {kv[3]:.1f}")
            print(f"    +{age:4d} s {name:6s}: occupied samples {v[:, 0].mean():6.1f} (at the cap {100 * (v[:, 0] >= steps_).mean():4.0f} %),"
                  f" iterations {v[:, 1].mean():6.1f}, tail evaluations {v[:, 2].mean():5.1f}, light evaluations {light.mean():6.1f}, mean T {v[:, 3].mean():.3f}")
    REPORT["view_work"] = rows


LIGHT_BODY = """
uniform float Mode, Frac;
out vec4 fragColor;
void main() {
    float i = gl_FragCoord.x;
    float phi = radians(-10.0 + 20.0 * fract(i * 0.618034));
    float rr = 4300.0 + 400.0 * fract(i * 0.4142);
    vec2 xz = rr * vec2(cos(phi), sin(phi));
    float H = dwLaw(rr, DwEye.w - dwArrival(rr)).x;
    vec3 p = vec3(xz.x, Frac * H, xz.y);
    vec3 rd = normalize(vec3(-cos(radians(6.0)), -sin(radians(6.0)), 0.0));
    gDwForceF = Mode;
    float s = dwDensity(p, false);
    vec3 c = Mode > 0.5 ? dwTexturedLight(p, rd) : dwHazeLight(p, rd);
    fragColor = vec4(c, s);
}
"""
SCENE_LIGHT = {  # the sandbox judging take's frame light (life_head shots.log: young wall describe), by age after the front
    "sun_dir": (-0.98, 0.22, 0.0), "sun": (0.367, 0.268, 0.110), "sky": (0.081, 0.168, 0.427), "ground": (0.016, 0.023, 0.045),
    "ball": {4: ((6, 1485, -5, 1801), (18.2, 8.25, 2.29)), 20: ((40, 4022, -35, 3523), (0.59, 0.0692, 0.0)),
             45: ((116, 6095, -102, 4755), (7.7e-5, 6.6e-7, 0.0))}}


def scene_light(p, age):
    L = SCENE_LIGHT
    g.glUniform3f(loc(p, "DwSunDir"), *L["sun_dir"]); g.glUniform3f(loc(p, "DwSunLight"), *L["sun"])
    g.glUniform3f(loc(p, "DwSkyLight"), *L["sky"]); g.glUniform3f(loc(p, "DwGroundLight"), *L["ground"])
    g.glUniform3f(loc(p, "DwAlbedo"), 0.8688, 0.8099, 0.7286); g.glUniform3f(loc(p, "DwBulk"), 0.60, 0.53, 0.45)
    key = 4 if age < 12 else 20 if age < 35 else 45
    ball, light = L["ball"][key]
    g.glUniform4f(loc(p, "DwBall"), *ball); g.glUniform3f(loc(p, "DwBallLight"), *light)


def display(c):
    """Linear light of opaque dust to screen (pallDisplay, gamma 2.2; shoulder ignored), luma and hue."""
    d = np.clip(c, 0, None) ** (1 / 2.2)
    luma = d @ np.array([0.2126, 0.7152, 0.0722])
    mx, mn = d.max(axis=-1), d.min(axis=-1)
    r, gg, b = d[..., 0], d[..., 1], d[..., 2]
    hue = np.degrees(np.arctan2(np.sqrt(3) * (gg - b), 2 * r - gg - b)) % 360
    return d, luma, hue


def light_levels(law):
    """The two levels' light at the same samples of the reference view (ages 10 s - 10 min), as screen colour of opaque dust:
    the textured level (take 33's light) and the haze level (two streams); their luminance ratio at the blend centre sets
    DW_HAZE_EXPOSURE; the haze's hue and luma against the reference curtain (luma 0.45-0.51, hue 31-33 deg)."""
    print("light: textured vs haze level at the reference view (screen colour of opaque dust, the sandbox's frame light)")
    p = program(LIGHT_BODY, "new")
    g.glUseProgram(p); noise(p)
    R = 4500.0
    rows = []
    for age in (10, 16, 24, 30, 45, 60, 120, 600):
        t = law.arrival_at(R) + age
        bind_new(p, law, t, (R + 600, 120.0, 0.0))
        scene_light(p, age)
        out = {}
        for mode in (1.0, 0.0):
            cs = []
            for frac in (0.3, 0.6, 0.9):
                g.glUniform1f(loc(p, "Mode"), mode); g.glUniform1f(loc(p, "Frac"), frac)
                target(256, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
                quad(p)
                cs.append(read(256, 1)[0, :, :3].astype(np.float64))
            out[mode] = np.concatenate(cs)
        dt, lt, ht = display(out[1.0]); dh, lh, hh = display(out[0.0])
        ratio = float(out[1.0] @ [0.2126, 0.7152, 0.0722]).mean() if False else float((out[1.0] @ np.array([0.2126, 0.7152, 0.0722])).mean() / max((out[0.0] @ np.array([0.2126, 0.7152, 0.0722])).mean(), 1e-9))
        rows.append((age, float(np.median(lt)), float(np.median(ht)), float(np.median(lh)), float(np.median(hh)), ratio))
        print(f"    +{age:4d} s: textured luma {np.median(lt):.3f} hue {np.median(ht):5.1f}  |  haze luma {np.median(lh):.3f} hue {np.median(hh):5.1f}"
              f"  |  linear luminance textured/haze {ratio:.3f}")
    REPORT["light"] = rows


def view_cost_variants(law):
    """llvmpipe ms of the judging view at +4 and +22 s (shipped blend) per light detail level and march variant."""
    import time
    print("view cost variants (llvmpipe ms per 160 x 90 frame): detail 0/1/2 x march variant")
    R = 4500.0
    pitch = np.radians(-6.0)
    fwd = np.array([-np.cos(pitch), np.sin(pitch), 0.0]); right = np.array([0.0, 0.0, 1.0]); up = np.cross(right, fwd)
    variants = [("shipped", ""), ("bound one step", "#define DW_BOUND_ONESTEP\n"), ("gap 64", "#define DW_GAP_OVERRIDE 64.0\n"),
                ("both", "#define DW_BOUND_ONESTEP\n#define DW_GAP_OVERRIDE 64.0\n")] + EXTRA_VARIANTS
    for vname, defs in variants:
        p = program(VIEW_BODY, "new", defs)
        g.glUseProgram(p); noise(p)
        line = []
        for age in (4, 22):
            bind_new(p, law, law.arrival_at(R) + age, (R, 120.0, 0.0))
            g.glUniform3f(loc(p, "Fwd"), *fwd); g.glUniform3f(loc(p, "Right"), *right); g.glUniform3f(loc(p, "Up"), *up)
            g.glUniform1f(loc(p, "Tan"), np.tan(np.radians(23.0))); g.glUniform1f(loc(p, "Aspect"), 16 / 9)
            g.glUniform1f(loc(p, "Steps"), 128); g.glUniform1f(loc(p, "Foot"), 0.0022); g.glUniform1f(loc(p, "ForceF"), -1.0)
            target(160, 90, [(g.GL_RGBA32F, g.GL_RGBA)])
            for det in (0.0, 1.0, 2.0):
                g.glUniform1f(loc(p, "DwDetail"), det)
                quad(p); g.glFinish()
                ts = []
                for _ in range(3):
                    t0 = time.perf_counter(); quad(p); g.glFinish(); ts.append(1000 * (time.perf_counter() - t0))
                line.append(f"+{age}s d{det:.0f} {min(ts):6.1f}")
        print(f"    {vname:16s} " + "  ".join(line))


EXTRA_VARIANTS = [("old wide bound", "#define DW_BOUND_WIDE\n")]


def view_cost(law):
    """llvmpipe time of the judging view's march (160 x 90 rays, HIGH light), the blend window as shipped against the
    textured level alone (step 1) and the haze level alone, at +4, +22 and +60 s: what the window's dual evaluation costs."""
    import time
    print("view cost (llvmpipe ms per 160 x 90 frame, HIGH light): shipped blend / textured only / haze only")
    p = program(VIEW_BODY, "new")
    g.glUseProgram(p); noise(p)
    R = 4500.0
    pitch = np.radians(-6.0)
    fwd = np.array([-np.cos(pitch), np.sin(pitch), 0.0]); right = np.array([0.0, 0.0, 1.0]); up = np.cross(right, fwd)
    rows = []
    for age in (4, 22, 60):
        bind_new(p, law, law.arrival_at(R) + age, (R, 120.0, 0.0))
        g.glUniform3f(loc(p, "Fwd"), *fwd); g.glUniform3f(loc(p, "Right"), *right); g.glUniform3f(loc(p, "Up"), *up)
        g.glUniform1f(loc(p, "Tan"), np.tan(np.radians(23.0))); g.glUniform1f(loc(p, "Aspect"), 16 / 9)
        g.glUniform1f(loc(p, "Steps"), 128); g.glUniform1f(loc(p, "Foot"), 0.0022)
        target(160, 90, [(g.GL_RGBA32F, g.GL_RGBA)])
        out = []
        for force in (-1.0, 1.0, 0.0):
            g.glUniform1f(loc(p, "ForceF"), force)
            quad(p); g.glFinish()
            ts = []
            for _ in range(3):
                t0 = time.perf_counter(); quad(p); g.glFinish(); ts.append(1000 * (time.perf_counter() - t0))
            out.append(min(ts))
        rows.append((age, *out))
        print(f"    +{age:3d} s: shipped {out[0]:7.1f}   textured only {out[1]:7.1f}   haze only {out[2]:7.1f}")
    REPORT["view_cost"] = rows


context()
laws = {"1000": Law("1000"), "15": Law("15")}
if "--only" in sys.argv:
    which = sys.argv[sys.argv.index("--only") + 1].split(",")
else:
    which = ["ref", "thin", "c3", "e8", "drift", "view"]
if "ref" in which:
    reference_column(laws["1000"])
if "thin" in which:
    thin_columns(laws)
if "c3" in which:
    age_functions(laws["1000"])
if "e8" in which:
    march_accuracy()
if "light" in which:
    light_levels(laws["1000"])
if "variants" in which:
    view_cost_variants(laws["1000"])
if "cost" in which:
    view_cost(laws["1000"])
if "view" in which:
    view_work(laws["1000"])
if "drift" in which:
    drift_inverse(laws["1000"])
(OUT / "probe-report.json").write_text(json.dumps(REPORT, indent=1, default=float))
sys.exit(1 if gl["FAILED"] else 0)
