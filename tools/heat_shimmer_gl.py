# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""Heat shimmer on a still camera: the shipped fx_heat shader on Mesa llvmpipe (EGL, GL 3.3 core), no game or GPU.

    uv run tools/heat_shimmer_gl.py [--checkout DIR] [--size WxH]

The frame copy holds each pixel's own coordinates, so the pass's output is its exact image shift per pixel. Two frames
1/30 s apart give the frame-to-frame motion of every pixel; light and fronts are off, only hot gas acts. Scenes rebuild
the sources the survey logged (`heat lights ... [r h dT px]`): a 1 Mt city at 3 km, +180 s (camera 10 m above the ground
inside the firestorm zone, two small fires, the cap's hot gas), and four r 2.5 fires of the conventional 100-strike bench.
Each source kind is switched on alone (A/B). A pixel moves if it shifts > 0.06 px between frames; the report gives the
share of moving pixels inside and outside the screen area of the source's own hot gas, and the median motion.
Exit 1 on a failed check (no moving pixel outside a source's own air).

`--checkout DIR` runs another checkout's shaders (e.g. a worktree of origin/main) for before/after numbers."""
import argparse
import ctypes
import json
import math
import os
import re
import sys
from pathlib import Path

os.environ["__EGL_VENDOR_LIBRARY_FILENAMES"] = "/usr/share/glvnd/egl_vendor.d/50_mesa.json"
os.environ["EGL_PLATFORM"] = "surfaceless"
os.environ["PYOPENGL_PLATFORM"] = "egl"
os.environ["LIBGL_ALWAYS_SOFTWARE"] = "1"
os.environ["GALLIUM_DRIVER"] = "llvmpipe"
os.environ["MESA_GL_VERSION_OVERRIDE"] = "3.3"

import numpy as np
from OpenGL import EGL as e, GL as g
from OpenGL.GL.shaders import compileProgram, compileShader

ap = argparse.ArgumentParser()
ap.add_argument("--checkout", default=str(Path(__file__).resolve().parents[1]))
ap.add_argument("--size", default="640x400")
ap.add_argument("--still", type=float, default=0.06, help="largest frame-to-frame motion of still air, px")
args = ap.parse_args()
SHADERS = Path(args.checkout) / "mod/src/main/resources/assets/incoming/shaders"
W, H = map(int, args.size.split("x"))
FAILED = []

# ---------------------------------------------------------------- laws copied from Java for the scene's sources
T_AIR, G, RHO, CP = 288.15, 9.80665, 1.225, 1005.0  # CloudModel
BLOCK_WATTS, CONVECTIVE = 175e3, 0.7  # FirePlume
FLAME_K = (75e3 / 5.670374e-8) ** 0.25
N0, TURBULENCE, EDDY, COOL = 2.77e-4, 0.4, 0.2, 20.0  # HeatPass


def excess(watts, z):
    c = 9.1 * (T_AIR / (G * CP * CP * RHO * RHO)) ** (1 / 3)
    return min(c * (CONVECTIVE * watts) ** (2 / 3) * max(z, 0.1) ** (-5 / 3), FLAME_K - T_AIR)


def axis_speed(watts, z):
    return 3.4 * (G / (CP * RHO * T_AIR)) ** (1 / 3) * (CONVECTIVE * watts) ** (1 / 3) * max(z, 0.1) ** (-1 / 3)


def plume(x, y, z, height, radius, across=0.0):
    """HeatPass.Frame.plumes for a fire whose hot gas reaches `height` (excess falls to COOL there), half-size from `radius`
    of the logged column (r = max(2·size, 0.12·h)); across — field radius (firestorm zone)."""
    watts = (COOL / (9.1 * (T_AIR / (G * CP * CP * RHO * RHO)) ** (1 / 3) * height ** (-5 / 3))) ** 1.5 / CONVECTIVE
    blocks = watts / BLOCK_WATTS
    size = 0.5 * math.sqrt(blocks / math.pi)
    r = max(2 * size, 0.12 * height)
    return dict(base=(x, y, z), r0=max(r, across), axis=(0, 1, 0), grow=0.0, length=height, eddy=max(0.05, EDDY * r),
                speed=max(0.1, axis_speed(watts, 0.5 * height)), amp=N0 * TURBULENCE,
                excess=excess(watts, 0.5 * height), hot_to=1e30, fast_to=1e30)


# ---------------------------------------------------------------- GL
def context():
    def attrs(*values):
        return (e.EGLint * len(values))(*values)
    d = e.eglGetDisplay(e.EGL_DEFAULT_DISPLAY)
    e.eglInitialize(d, e.EGLint(), e.EGLint())
    e.eglBindAPI(e.EGL_OPENGL_API)
    cfg, n = e.EGLConfig(), e.EGLint()
    e.eglChooseConfig(d, attrs(e.EGL_SURFACE_TYPE, e.EGL_PBUFFER_BIT, e.EGL_RENDERABLE_TYPE, e.EGL_OPENGL_BIT, e.EGL_NONE),
                      ctypes.byref(cfg), 1, n)
    surface = e.eglCreatePbufferSurface(d, cfg, attrs(e.EGL_WIDTH, 8, e.EGL_HEIGHT, 8, e.EGL_NONE))
    ctx = e.eglCreateContext(d, cfg, e.EGL_NO_CONTEXT, attrs(0x3098, 3, 0x30FB, 3, 0x30FD, 1, e.EGL_NONE))
    e.eglMakeCurrent(d, surface, surface, ctx)
    renderer = g.glGetString(g.GL_RENDERER).decode()
    if "llvmpipe" not in renderer:
        sys.exit(f"not Mesa llvmpipe: {renderer}")


def expand(source, seen=None):
    seen = set() if seen is None else seen

    def include(match):
        if match[1] in seen:
            return ""
        seen.add(match[1])
        return expand((SHADERS / "include" / match[1]).read_text(), seen)
    return re.sub(r"^\s*#moj_import\s+<incoming:([\w./]+)>", include, source, flags=re.M)


def link(name):
    setup = json.loads((SHADERS / "core" / (name + ".json")).read_text())
    vertex = (SHADERS / "core" / (setup["vertex"].split(":")[-1] + ".vsh")).read_text()
    fragment = (SHADERS / "core" / (setup["fragment"].split(":")[-1] + ".fsh")).read_text()
    return compileProgram(compileShader(expand(vertex), g.GL_VERTEX_SHADER),
                          compileShader(expand(fragment), g.GL_FRAGMENT_SHADER), validate=False)


def texture(internal, fmt, w, h, data, linear=False, mips=False):
    tex = g.glGenTextures(1)
    g.glBindTexture(g.GL_TEXTURE_2D, tex)
    mn = (g.GL_LINEAR_MIPMAP_LINEAR if mips else g.GL_LINEAR) if linear else g.GL_NEAREST
    for p, v in ((g.GL_TEXTURE_MIN_FILTER, mn), (g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR if linear else g.GL_NEAREST),
                 (g.GL_TEXTURE_WRAP_S, g.GL_CLAMP_TO_EDGE), (g.GL_TEXTURE_WRAP_T, g.GL_CLAMP_TO_EDGE)):
        g.glTexParameteri(g.GL_TEXTURE_2D, p, v)
    g.glTexImage2D(g.GL_TEXTURE_2D, 0, internal, w, h, 0, fmt, g.GL_FLOAT, np.ascontiguousarray(data, dtype=np.float32))
    if mips:
        g.glGenerateMipmap(g.GL_TEXTURE_2D)
    return tex


def quad(program):
    pos = g.glGetAttribLocation(program, "Position")
    vao = g.glGenVertexArrays(1)
    g.glBindVertexArray(vao)
    vbo = g.glGenBuffers(1)
    g.glBindBuffer(g.GL_ARRAY_BUFFER, vbo)
    verts = np.array([[-1, -1, 0], [1, -1, 0], [-1, 1, 0], [1, 1, 0]], dtype=np.float32)
    g.glBufferData(g.GL_ARRAY_BUFFER, verts.nbytes, verts, g.GL_STATIC_DRAW)
    g.glVertexAttribPointer(pos, 3, g.GL_FLOAT, False, 0, None)
    g.glEnableVertexAttribArray(pos)
    g.glDrawArrays(g.GL_TRIANGLE_STRIP, 0, 4)


# ---------------------------------------------------------------- camera
def camera(yaw, pitch, fov=70.0):
    """Minecraft look (yaw 0 = +z, 90 = −x; pitch > 0 down): view, projection, forward, rays per pixel."""
    y, p = math.radians(yaw), math.radians(pitch)
    fwd = np.array([-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p)])
    right = np.cross(fwd, [0, 1, 0])
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    view = np.eye(4)
    view[0, :3], view[1, :3], view[2, :3] = right, up, -fwd
    f = 1 / math.tan(math.radians(fov) / 2)
    near, far = 0.05, 1e5
    proj = np.zeros((4, 4))
    proj[0, 0], proj[1, 1] = f / (W / H), f
    proj[2, 2], proj[2, 3], proj[3, 2] = (far + near) / (near - far), 2 * far * near / (near - far), -1
    inv = np.linalg.inv(proj @ view)
    xs = (np.arange(W) + 0.5) / W * 2 - 1
    ys = (np.arange(H) + 0.5) / H * 2 - 1
    nx, ny = np.meshgrid(xs, ys)
    clip = np.stack([nx, ny, np.ones_like(nx), np.ones_like(nx)], -1) @ inv.T
    dirs = clip[..., :3] / clip[..., 3:4]
    dirs /= np.linalg.norm(dirs, axis=-1, keepdims=True)
    pixel = 2 * math.tan(math.radians(fov) / 2) / H
    return inv, fwd, dirs, pixel


def columns(sources):
    hot, shape, axis, gas = (np.zeros((4, 4), np.float32) for _ in range(4))
    for i, s in enumerate(sources[:4]):
        hot[i] = (*s["base"], s["r0"])
        shape[i] = (s["length"], s["eddy"], s["speed"], s["amp"])
        axis[i] = (*s["axis"], s["grow"])
        gas[i] = (s["excess"], s["hot_to"], s["fast_to"], 0)
    return hot, shape, axis, gas


def inside(s, dirs, dist):
    """Pixels whose ray to the world passes through source s's own hot gas (vertical cylinder from its base), exactly."""
    bx, by, bz = s["base"]
    r, top = s["r0"], by + s["length"]
    dx, dy, dz = dirs[..., 0], dirs[..., 1], dirs[..., 2]
    a = dx * dx + dz * dz
    b = dx * bx + dz * bz
    c = bx * bx + bz * bz - r * r
    disc = b * b - a * c
    with np.errstate(divide="ignore", invalid="ignore"):
        root = np.sqrt(np.maximum(disc, 0))
        t0 = np.where(a > 1e-12, (b - root) / a, -np.inf)
        t1 = np.where(a > 1e-12, (b + root) / a, np.inf)
        # vertical span of the slab by
        y0 = np.where(np.abs(dy) > 1e-12, by / dy, -np.inf)
        y1 = np.where(np.abs(dy) > 1e-12, top / dy, np.inf)
    ylo, yhi = np.minimum(y0, y1), np.maximum(y0, y1)
    flat = np.abs(dy) <= 1e-12
    ylo = np.where(flat, np.where((by < 0) & (top > 0), -np.inf, np.inf), ylo)
    yhi = np.where(flat, np.where((by < 0) & (top > 0), np.inf, -np.inf), yhi)
    lo = np.maximum(np.maximum(t0, ylo), 0)
    hi = np.minimum(np.minimum(t1, yhi), dist)
    return (disc > 0) & (hi > lo)


def run(program, scene):
    inv, fwd, dirs, pixel = camera(scene["yaw"], scene["pitch"])
    ground = scene["ground"]
    with np.errstate(divide="ignore", invalid="ignore"):
        t = np.where(dirs[..., 1] < -1e-6, ground / dirs[..., 1], np.inf)
    dist = np.where(np.isfinite(t), t, 1e9)
    depth = np.where(np.isfinite(t), t * (dirs @ fwd), 1e9).astype(np.float32)
    coords = np.zeros((H, W, 4), np.float32)
    coords[..., 0] = (np.arange(W) + 0.5)[None, :] / W
    coords[..., 1] = (np.arange(H) + 0.5)[:, None] / H
    out = []
    g.glUseProgram(program)
    fbo = g.glGenFramebuffers(1)
    g.glBindFramebuffer(g.GL_FRAMEBUFFER, fbo)
    target = texture(g.GL_RGBA32F, g.GL_RGBA, W, H, np.zeros((H, W, 4)))
    g.glFramebufferTexture2D(g.GL_FRAMEBUFFER, g.GL_COLOR_ATTACHMENT0, g.GL_TEXTURE_2D, target, 0)
    g.glViewport(0, 0, W, H)
    units = {"Sampler0": texture(g.GL_RGBA32F, g.GL_RGBA, W, H, coords, linear=True),
             "Sampler1": texture(g.GL_RG32F, g.GL_RG, W, H, np.stack([depth, depth], -1)),
             "Sampler2": texture(g.GL_RGBA32F, g.GL_RGBA, 1, 1, np.zeros(4))}
    for extra, (side, data) in scene.get("textures", {}).items():
        units[extra] = texture(g.GL_RGBA16F, g.GL_RGBA, side, side, data, linear=True, mips=True)
    for i, (name, tex) in enumerate(units.items()):
        g.glActiveTexture(g.GL_TEXTURE0 + i)
        g.glBindTexture(g.GL_TEXTURE_2D, tex)
        g.glUniform1i(g.glGetUniformLocation(program, name), i)
    hot, shape, axis, gas = columns(scene["sources"])

    def u(name):
        return g.glGetUniformLocation(program, name)
    g.glUniformMatrix4fv(u("InvViewProj"), 1, True, inv.astype(np.float32))
    g.glUniform3f(u("PallForward"), *fwd)
    g.glUniform1f(u("FxSee"), 0)
    g.glUniform4f(u("Haze"), 0, 1e9, 2e9, 0)
    for name, m in (("HeatHot", hot), ("HeatShape", shape), ("HeatAxis", axis), ("HeatGas", gas)):
        g.glUniformMatrix4fv(u(name), 1, False, m)
    for name, value in scene.get("uniforms", {}).items():
        if isinstance(value, np.ndarray):
            g.glUniformMatrix4fv(u(name), 1, False, value.astype(np.float32))
        else:
            getattr(g, f"glUniform{len(value)}f")(u(name), *value)
    g.glUniform4f(u("HeatFrame"), 0, pixel, 0, 1 if scene["sources"] or "storm" in scene else 0)
    g.glUniform4f(u("HeatShadow"), 0, 0, 0, 0)
    g.glUniform1f(u("HeatReach"), 1e9)
    for seconds in (100.0, 100.0 + 1 / 30):
        cycles, half = seconds / 2.0, seconds / 2.0 + 0.5
        g.glUniform4f(u("HeatClock"), cycles % 1, math.floor(cycles) % 8, half % 1, math.floor(half) % 8 + 0.5)
        quad(program)
        raw = g.glReadPixels(0, 0, W, H, g.GL_RGBA, g.GL_FLOAT)
        img = np.frombuffer(raw, np.float32).reshape(H, W, 4)
        out.append(np.stack([img[..., 0] * W - (np.arange(W) + 0.5)[None, :], img[..., 1] * H - (np.arange(H) + 0.5)[:, None]], -1))
    motion = np.linalg.norm(out[1] - out[0], axis=-1)
    own = np.zeros((H, W), bool)
    for s in scene["sources"]:
        if s.get("local", True):
            own |= inside(s, dirs, dist)
    if "still" in scene:
        centre, half, top = scene["storm"]
        own |= ~clear_rays(dirs, dist, centre[1], top, scene["still"] - 2 * 90.0)
    return motion, own, np.linalg.norm(out[0], axis=-1)


def report(name, motion, own, shift, still):
    moving = motion > still
    outside = moving & ~own
    print(f"{name:58s} moving {100 * moving.mean():5.1f}%  outside own air {100 * outside.mean():5.1f}% ({outside.sum()} px)  "
          f"median motion {np.median(motion):.3f} px  p99 {np.percentile(motion, 99):.3f}  max shift {shift.max():.2f} px")
    return outside.mean()


def mt3k(kinds):
    """1 Mt city at 3 km, +180 s, logged: camera (3241, 79, 809) yaw 107 pitch -2, ground 10 m below; firestorm zone
    r 10159 h 41 around ground zero (315, 69, -67); small fires [r 2.5 h 21] (placed in view 40 and 70 m ahead); cap gas
    [r 7834 h 6278 dT 492] from the cap bottom (est. 2 km up)."""
    gz = (315 - 3241, 69 - 79, -67 - 809)
    src = []
    if "storm" in kinds:
        s = plume(*gz, 41, 2.5, across=10159.3)
        s["local"] = False
        src.append(s)
    if "fires" in kinds:
        y = math.radians(107)
        for d, side in ((40, -6), (70, 10)):
            fx, fz = -math.sin(y) * d + math.cos(y) * side, math.cos(y) * d + math.sin(y) * side
            src.append(plume(fx, -10, fz, 21, 2.5))
    return dict(yaw=107, pitch=-2, ground=-10, sources=src)


def storm_top(f, spacing):
    """Height where a source of burning share f (f·spacing² fire blocks) cools to COOL (Heskestad, FirePlume.excess)."""
    q = f * spacing * spacing * BLOCK_WATTS * CONVECTIVE
    c = 9.1 * (T_AIR / (G * CP * CP * RHO * RHO)) ** (1 / 3)
    return (c * q ** (2 / 3) / COOL) ** 0.6


def storm_field(centre, half, cell, flames, gate=1.0):
    """HeatPass.Frame.storm: map centre on the ground (eye-relative), half-side, map cell, burning share per cell (side²)."""
    side = flames.shape[0]
    spacing = min(32.0, cell)
    top = storm_top(max(flames.max() * gate, 1e-6), spacing)
    m = np.zeros((4, 4), np.float32)
    m[0] = (*centre, half)
    m[2] = (cell, spacing, top, gate)
    rgba = np.zeros((side, side, 4), np.float32)
    rgba[..., 0] = flames
    return dict(uniforms={"HeatStorm": m}, textures={"Sampler3": (side, rgba)}, storm=(centre, half, top))


def clear_rays(dirs, dist, centre_y, top, radius):
    """Pixels whose ray stays within `radius` blocks of the eye (horizontally) while inside the hot layer."""
    flat = np.linalg.norm(dirs[..., [0, 2]], axis=-1)
    dy = dirs[..., 1]
    with np.errstate(divide="ignore", invalid="ignore"):
        ta, tb = centre_y / dy, (centre_y + top) / dy
    lo = np.where(np.abs(dy) > 1e-9, np.minimum(ta, tb), np.where((centre_y < 0) & (centre_y + top > 0), -np.inf, np.inf))
    hi = np.where(np.abs(dy) > 1e-9, np.maximum(ta, tb), np.where((centre_y < 0) & (centre_y + top > 0), np.inf, -np.inf))
    lo, hi = np.maximum(lo, 0), np.minimum(hi, dist)
    return (hi <= lo) | (hi * flat < radius)


def mt3k_storm(clear=600.0, seed=7):
    """1 Mt +180 s on the fire map: the zone (r 10159, 90-block cells, side 256) holds 134 burning cells at full strength,
    none within `clear` blocks of the camera (the log's "model here 0")."""
    gz = np.array([315 - 3241, 69 - 79, -67 - 809], float)
    side, cell = 256, 90.0
    half = side * cell / 2
    rng = np.random.default_rng(seed)
    flames = np.zeros((side, side), np.float32)
    xs = (np.arange(side) + 0.5) * cell - half + gz[0]
    zs = (np.arange(side) + 0.5) * cell - half + gz[2]
    X, Z = np.meshgrid(xs, zs)
    ok = (np.hypot(X - gz[0], Z - gz[2]) < 10159) & (np.hypot(X, Z) > clear + cell)
    idx = rng.choice(np.flatnonzero(ok), 134, replace=False)
    flames.flat[idx] = 1.0
    scene = dict(yaw=107, pitch=-2, ground=-10, sources=[])
    scene.update(storm_field(tuple(gz), half, cell, flames))
    scene["still"] = clear
    return scene


def zone(f, cell=32.0, side=128, new=True):
    """A uniformly burning zone at share f around the eye (camera 10 m up): new — the fire map, else the old field column
    (one column as wide as the zone at the mean source's plume, HeatPass.Frame.plumes)."""
    half = side * cell / 2
    if not new:
        watts = f * cell * cell * BLOCK_WATTS
        lo, hi = 0.5, 400.0
        for _ in range(30):
            mid = 0.5 * (lo + hi)
            lo, hi = (mid, hi) if excess(watts, mid) > COOL else (lo, mid)
        height = lo
        size = 0.5 * math.sqrt(f * cell * cell / math.pi)
        r = max(2 * size, 0.12 * height)
        src = dict(base=(0, -10, 0), r0=max(r, half), axis=(0, 1, 0), grow=0.0, length=height, eddy=max(0.05, EDDY * r),
                   speed=max(0.1, axis_speed(watts, 0.5 * height)), amp=N0 * TURBULENCE, excess=excess(watts, 0.5 * height),
                   hot_to=1e30, fast_to=1e30, local=False)
        return dict(yaw=107, pitch=10, ground=-10, sources=[src])
    scene = dict(yaw=107, pitch=10, ground=-10, sources=[])
    scene.update(storm_field((0.0, -10.0, 0.0), half, cell, np.full((side, side), f, np.float32)))
    return scene


def bench():
    """Conventional 100-strike bench, edge camera: four craters' fires [r 2.5 h 21] 30–120 m ahead, camera 12 m up."""
    y = math.radians(180)
    src = []
    for d, side in ((30, 8), (55, -20), (80, 35), (120, -5)):
        src.append(plume(-math.sin(y) * d + math.cos(y) * side, -12, math.cos(y) * d + math.sin(y) * side, 21, 2.5))
    return dict(yaw=180, pitch=10, ground=-12, sources=src)


def main():
    context()
    program = link("fx_heat")
    storms = "HeatStorm" in (SHADERS / "core" / "fx_heat.fsh").read_text()
    print(f"shaders: {SHADERS}  frame {W}x{H}, still air moves at most {args.still} px between frames 1/30 s apart")
    worst = 0.0
    scenes = [("1 Mt +180 s: two small fires only", mt3k({"fires"})),
              ("100-strike bench: four crater fires", bench())]
    if storms:
        scenes += [("1 Mt +180 s: firestorm map, nothing burns within 600 m", mt3k_storm()),
                   ("1 Mt +180 s: firestorm map and the two small fires", dict(mt3k_storm(), sources=mt3k({"fires"})["sources"]))]
    else:
        scenes += [("1 Mt +180 s: firestorm zone column (as logged)", mt3k({"storm"}))]
    for name, scene in scenes:
        motion, own, shift = run(program, scene)
        worst = max(worst, report(name, motion, own, shift, args.still))
    print("strength over a uniformly burning zone (all of it is the fire's own air), looking 10° down:")
    for f in (0.05, 0.3, 1.0):
        motion, own, shift = run(program, zone(f, new=storms))
        print(f"  burning share {f:4.2f}: median motion {np.median(motion):.3f} px, p90 {np.percentile(motion, 90):.3f}, "
              f"median shift {np.median(shift):.3f} px")
    ok = worst == 0
    print(("  ok      " if ok else "  FAILED  ") + "no pixel outside a source's own hot air moves between frames")
    sys.exit(0 if ok else 1)

main()
