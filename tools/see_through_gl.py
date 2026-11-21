# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""Effects behind see-through blocks: the shipped shaders on Mesa llvmpipe (EGL, GL 3.3 core), no game, display or GPU.

    uv run tools/see_through_gl.py

Links every program the see-through path touches and counts its samplers (the pall composite must stay within 16, the
GL minimum); runs fx_depth on synthetic captures (a pane is looked through, an opaque or depth-only surface and what is
drawn later hide the layer as before), the half-resolution distance reduction (a pair from one frame pixel) and the pall
split with conventional smoke wholly in front of a far pane. Exit 1 on a failed check. Known limit printed, not checked:
two coplanar panes of different transmittance mix across half a half-resolution pixel."""
import ctypes
import sys
import json
import os
from pathlib import Path
import re

os.environ["__EGL_VENDOR_LIBRARY_FILENAMES"] = "/usr/share/glvnd/egl_vendor.d/50_mesa.json"
os.environ["EGL_PLATFORM"] = "surfaceless"
os.environ["PYOPENGL_PLATFORM"] = "egl"
os.environ["LIBGL_ALWAYS_SOFTWARE"] = "1"
os.environ["GALLIUM_DRIVER"] = "llvmpipe"
os.environ["MESA_GL_VERSION_OVERRIDE"] = "3.3"

import numpy as np

FAILED = []


def check(ok, what):
    print(("  ok      " if ok else "  FAILED  ") + what)
    if not ok:
        FAILED.append(what)

from OpenGL import EGL as e, GL as g
from OpenGL.GL.shaders import compileShader, compileProgram

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "mod/src/main/resources/assets/incoming/shaders"
CORE = SHADERS / "core"
INCLUDE = SHADERS / "include"


def context():
    def attrs(*values):
        return (e.EGLint * len(values))(*values)
    d = e.eglGetDisplay(e.EGL_DEFAULT_DISPLAY)
    e.eglInitialize(d, e.EGLint(), e.EGLint())
    e.eglBindAPI(e.EGL_OPENGL_API)
    cfg, n = e.EGLConfig(), e.EGLint()
    e.eglChooseConfig(d, attrs(e.EGL_SURFACE_TYPE, e.EGL_PBUFFER_BIT, e.EGL_RENDERABLE_TYPE,
                              e.EGL_OPENGL_BIT, e.EGL_NONE), ctypes.byref(cfg), 1, n)
    surface = e.eglCreatePbufferSurface(d, cfg, attrs(e.EGL_WIDTH, 8, e.EGL_HEIGHT, 8, e.EGL_NONE))
    ctx = e.eglCreateContext(d, cfg, e.EGL_NO_CONTEXT, attrs(0x3098, 3, 0x30FB, 3, 0x30FD, 1, e.EGL_NONE))
    e.eglMakeCurrent(d, surface, surface, ctx)
    renderer = g.glGetString(g.GL_RENDERER).decode()
    if "llvmpipe" not in renderer:
        sys.exit(f"not Mesa llvmpipe: {renderer}")
    print(g.glGetString(g.GL_VERSION).decode(), renderer)


def expand(source, seen=None):
    if seen is None:
        seen = set()

    def include(match):
        name = match[1]
        if name in seen:
            return ""
        seen.add(name)
        return expand((INCLUDE / name).read_text(), seen)

    return re.sub(r"^\s*#moj_import\s+<incoming:([\w./]+)>", include, source, flags=re.M)


def link(name):
    setup = json.loads((CORE / (name + ".json")).read_text())
    vertex = (CORE / (setup["vertex"].split(":")[-1] + ".vsh")).read_text()
    fragment = (CORE / (setup["fragment"].split(":")[-1] + ".fsh")).read_text()
    program = compileProgram(compileShader(expand(vertex), g.GL_VERTEX_SHADER),
                             compileShader(expand(fragment), g.GL_FRAGMENT_SHADER), validate=False)
    return program


def loc(program, name):
    return g.glGetUniformLocation(program, name)


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


def texture(internal, fmt, w, h, data=None):
    tex = g.glGenTextures(1)
    old = g.glGetIntegerv(g.GL_TEXTURE_BINDING_2D)
    g.glBindTexture(g.GL_TEXTURE_2D, tex)
    for param, value in ((g.GL_TEXTURE_MIN_FILTER, g.GL_NEAREST), (g.GL_TEXTURE_MAG_FILTER, g.GL_NEAREST),
                         (g.GL_TEXTURE_WRAP_S, g.GL_CLAMP_TO_EDGE), (g.GL_TEXTURE_WRAP_T, g.GL_CLAMP_TO_EDGE)):
        g.glTexParameteri(g.GL_TEXTURE_2D, param, value)
    if data is not None:
        data = np.asarray(data, dtype=np.float32)
    g.glTexImage2D(g.GL_TEXTURE_2D, 0, internal, w, h, 0, fmt, g.GL_FLOAT, data)
    g.glBindTexture(g.GL_TEXTURE_2D, old)
    return tex


def target(w, h, colours, depth=False):
    fbo = g.glGenFramebuffers(1)
    g.glBindFramebuffer(g.GL_FRAMEBUFFER, fbo)
    for i, (internal, fmt) in enumerate(colours):
        tex = texture(internal, fmt, w, h)
        g.glFramebufferTexture2D(g.GL_FRAMEBUFFER, g.GL_COLOR_ATTACHMENT0+i, g.GL_TEXTURE_2D, tex, 0)
    g.glDrawBuffers(len(colours), [g.GL_COLOR_ATTACHMENT0+i for i in range(len(colours))])
    if depth:
        tex = texture(g.GL_DEPTH_COMPONENT32F, g.GL_DEPTH_COMPONENT, w, h)
        g.glFramebufferTexture2D(g.GL_FRAMEBUFFER, g.GL_DEPTH_ATTACHMENT, g.GL_TEXTURE_2D, tex, 0)
    if g.glCheckFramebufferStatus(g.GL_FRAMEBUFFER) != g.GL_FRAMEBUFFER_COMPLETE:
        sys.exit("probe framebuffer incomplete")
    g.glViewport(0, 0, w, h)


def bind(program, i, tex):
    g.glActiveTexture(g.GL_TEXTURE0 + i)
    g.glBindTexture(g.GL_TEXTURE_2D, tex)
    g.glUniform1i(loc(program, f"Sampler{i}"), i)


def read(w, h, attachment=0, fmt=g.GL_RGBA):
    g.glReadBuffer(g.GL_COLOR_ATTACHMENT0+attachment)
    raw = g.glReadPixels(0, 0, w, h, fmt, g.GL_FLOAT)
    return np.frombuffer(raw, dtype=np.float32).reshape((h, w, -1))


def depth_cases(see, program):
    """fx_see after translucent terrain, then fx_depth at the layer: which pixels are looked through and which hide it."""
    # frame after translucent terrain (alpha: its coverage) and before it: a 50% pane; an opaque particle drawn before the
    # pane (coverage 1); a depth-only boat mask (coverage 0); the opaque world; a pane with a vanilla particle drawn later
    after = [(0.7, 0.7, 0.7, 0.5), (0.4, 0.4, 0.4, 1.0), (0.8, 0.8, 0.8, 0.0), (0.8, 0.8, 0.8, 0.0), (0.7, 0.7, 0.7, 0.5)]
    before = [(0.8, 0.8, 0.8, 0.0)] * 5
    target(5, 1, [(g.GL_RGBA8, g.GL_RGBA)])
    g.glClearColor(0, 0, 0, 0)
    g.glClear(g.GL_COLOR_BUFFER_BIT)
    g.glUseProgram(see)
    bind(see, 0, texture(g.GL_RGBA8, g.GL_RGBA, 5, 1, after))
    bind(see, 1, texture(g.GL_RGBA8, g.GL_RGBA, 5, 1, before))
    g.glUniform4f(loc(see, "See"), 1, 0, 0, 0)
    query = int(np.ravel(g.glGenQueries(1))[0])
    g.glBeginQuery(g.GL_SAMPLES_PASSED, query)
    quad(see)
    g.glEndQuery(g.GL_SAMPLES_PASSED)
    glass = read(5, 1)[0]
    samples = int(np.ravel(g.glGetQueryObjectuiv(query, g.GL_QUERY_RESULT))[0])
    glass_tex = g.glGetFramebufferAttachmentParameteriv(g.GL_FRAMEBUFFER, g.GL_COLOR_ATTACHMENT0, g.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
    check(samples == 2 and abs(glass[0, 3] - 0.5) < 0.01 and abs(glass[0, 0] - 0.3) < 0.01,
          f"fx_see writes only partly covered pixels, X = after - T*before ({samples} samples, X {glass[0, 0]:.3f}, T {glass[0, 3]:.3f})")

    target(5, 1, [(g.GL_RG32F, g.GL_RG)], depth=True)
    g.glUseProgram(program)
    bind(program, 0, texture(g.GL_R32F, g.GL_RED, 5, 1, [.9] * 5))
    bind(program, 2, texture(g.GL_R32F, g.GL_RED, 5, 1, [.2, .2, .2, .9, .1]))
    bind(program, 3, glass_tex)
    # Synthetic inverse projection with output depth in 0..100.
    inv = np.array([[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, -50, -50], [0, 0, 0, 1]], dtype=np.float32)
    g.glUniformMatrix4fv(loc(program, "InvProj"), 1, True, inv)
    g.glUniform4f(loc(program, "See"), 1, 0, 0, 0)
    g.glEnable(g.GL_DEPTH_TEST)
    g.glDepthFunc(g.GL_ALWAYS)
    quad(program)
    distances = read(5, 1)[0, :, :2]
    hard = np.frombuffer(g.glReadPixels(0, 0, 5, 1, g.GL_DEPTH_COMPONENT, g.GL_FLOAT), dtype=np.float32)
    labels = ["pane", "opaque particle before translucent terrain", "depth-only mask", "opaque world",
              "pane with a particle drawn later"]
    for i, label in enumerate(labels):
        print(f"{label}: r/g={distances[i].round(2)} hard={hard[i]:.3f}")
    check(np.isclose(hard[0], .9) and distances[0, 1] < distances[0, 0], "a pane is looked through")
    check(np.isclose(hard[1], .2), "a fully covered pixel hides the layer at the surface, as on main")
    check(np.isclose(hard[2], .2), "a depth-only surface (boat water mask) hides the layer, as on main")
    check(np.isclose(hard[3], .9), "the opaque world hides the layer")
    print(f"limit: a particle drawn after translucent terrain in front of a pane is looked through with the pane's tint (hard {hard[4]:.3f})")
    g.glDisable(g.GL_DEPTH_TEST)


def half_cases(depth, composite):
    g.glUseProgram(depth)
    target(1, 1, [(g.GL_RG32F, g.GL_RG)])
    # Stained glass in front of far terrain on the left, near opaque wall on the right.
    bind(depth, 1, texture(g.GL_RG32F, g.GL_RG, 2, 2, [(100, 2), (4, 4)] * 2))
    quad(depth)
    pair = read(1, 1)[0, 0, :2]
    print(f"half checkerboard: (100, 2) and (4, 4) -> {tuple(pair)}")
    check(np.allclose(pair, [4, 4]), "the half-resolution pair comes from one frame pixel")

    g.glUseProgram(composite)
    target(4, 2, [(g.GL_RGBA32F, g.GL_RGBA)])
    # Red-only light behind two coplanar surfaces with T=0.1 and T=0.9.
    # A constant surface depth makes all four taps compatible.
    bind(composite, 0, texture(g.GL_RGBA32F, g.GL_RGBA, 2, 1, [(0.1, 0, 0, 1), (0.9, 0, 0, 1)]))
    bind(composite, 1, texture(g.GL_RG32F, g.GL_RG, 2, 1, [(100, 2)]*2))
    bind(composite, 2, texture(g.GL_RG32F, g.GL_RG, 4, 2, [(100, 2)]*8))
    bind(composite, 3, texture(g.GL_R32F, g.GL_RED, 1, 1, [0]))
    g.glUniform1f(loc(composite, "HalfTile"), 64)
    quad(composite)
    out = read(4, 2)[0, :, 0]
    print(f"limit: half composite across a coplanar T edge {out.round(3)}, exact [0.1 0.1 0.9 0.9]")


def half_edges():
    """Half-resolution smoke at a window's edges: the reduced distances and the surface come from one frame pixel."""
    depth, composite = programs["fx_half_depth"], programs["fx_half_composite"]

    def draw(full, t):
        h, w, _ = full.shape
        hw, hh = w // 2, h // 2
        scene = texture(g.GL_RG32F, g.GL_RG, w, h, full)
        glass = texture(g.GL_RGBA32F, g.GL_RGBA, w, h, [(0, 0, 0, v) for v in t.flatten()])
        target(hw, hh, [(g.GL_RG32F, g.GL_RG), (g.GL_RGBA32F, g.GL_RGBA)])
        g.glUseProgram(depth)
        bind(depth, 1, scene)
        g.glUniform1i(loc(depth, "FxGlass"), 16)
        g.glActiveTexture(g.GL_TEXTURE0 + 16)
        g.glBindTexture(g.GL_TEXTURE_2D, glass)
        quad(depth)
        pair = read(hw, hh)[..., :2]
        low_t = read(hw, hh, attachment=1)[..., 3]
        low_depth = g.glGetFramebufferAttachmentParameteriv(g.GL_FRAMEBUFFER, g.GL_COLOR_ATTACHMENT0,
                                                            g.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
        # fx.fsh in the half pass: unit smoke light at 10 m, hidden by r, tinted by the half pixel's own surface
        low = np.where(pair[..., 0] > 10, np.where(pair[..., 1] < 10, low_t, 1.0), 0.0)
        light = np.stack((low, low * 0, low * 0, low * 0 + 1), axis=-1)
        target(w, h, [(g.GL_RGBA32F, g.GL_RGBA)])
        g.glUseProgram(composite)
        bind(composite, 0, texture(g.GL_RGBA32F, g.GL_RGBA, hw, hh, light))
        bind(composite, 1, low_depth)
        bind(composite, 2, scene)
        bind(composite, 3, texture(g.GL_R32F, g.GL_RED, 1, 1, [0]))
        g.glUniform1f(loc(composite, "HalfTile"), 64)
        quad(composite)
        return read(w, h)[..., 0]

    # stained glass (T 0.1 at 2 m) with a one-pixel slit onto open ground at 100 m
    full = np.tile([(100, 2), (100, 2), (100, 2), (100, 100), (100, 2), (100, 2)], (4, 1, 1)).astype(np.float32)
    light = draw(full, np.where(full[..., 1] == 2, 0.1, 1.0))
    print(f"half-resolution smoke through a one-pixel slit in glass: {light[1].round(3)}, exact [0.1 0.1 0.1 1 0.1 0.1]")
    # rows inside the frame (the last row has only one checkerboard parity below it, a frame-edge case)
    inner = light[:-1]
    check(np.abs(inner[:, [0, 1, 2, 4, 5]] - 0.1).max() < 0.05 and np.abs(inner[:, 3] - 1).max() < 0.05,
          "smoke behind glass beside a one-pixel slit keeps its tint, the slit none")
    # the same glass beside a wall at 4 m, smoke at 10 m behind both
    full = np.tile([(100, 2), (100, 2), (100, 2), (4, 4), (4, 4), (4, 4)], (4, 1, 1)).astype(np.float32)
    light = draw(full, np.where(full[..., 1] == 2, 0.1, 1.0))
    print(f"half-resolution smoke behind glass beside a wall: {light[1].round(3)}, exact [0.1 0.1 0.1 0 0 0]")
    check(np.abs(light[:, :3] - 0.1).max() < 0.05 and light[:, 3:].max() < 0.05,
          "smoke at a window's edge beside a wall takes the window's tint and stays hidden by the wall")


def far_smoke_in_front():
    source = expand((CORE / "fx_pall_composite.fsh").read_text())
    fragment = source[:source.index("void main() {")] + """
void main() {
    // Smoke at 280 m is wholly in front of a pane at 300 m.
    vec4 rays = vec4(0.3, 0.3, 0.3, 0.5);
    vec4 front = mediumTo(300.0, ivec2(0), vec3(0, 0, -1), 256.0, rays, rays);
    float frame = 0.1 + 0.5 * 0.6;
    float branch = seeMediumLight(rays.r, rays.a, front.r, front.a, 0.1, 0.5) + rays.a * frame;
    float exact = rays.r + rays.a * frame;
    fragColor = vec4(front.r, front.a, branch, exact);
}
"""
    program = compileProgram(compileShader((CORE / "fx_depth.vsh").read_text(), g.GL_VERTEX_SHADER),
                             compileShader(fragment, g.GL_FRAGMENT_SHADER), validate=False)
    target(1, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
    g.glUseProgram(program)
    bind(program, 11, texture(g.GL_RGBA32F, g.GL_RGBA, 8, 8, [(0, 0, 0, 1)] * 64))
    g.glUniform4f(loc(program, "PallFroxel"), 4, 1, 256, 0)
    g.glUniform3f(loc(program, "PallForward"), 0, 0, -1)
    g.glUniform1i(loc(program, "PallOpen"), 13)
    g.glActiveTexture(g.GL_TEXTURE0 + 13)
    t = g.glGenTextures(1)
    g.glBindTexture(g.GL_TEXTURE_3D, t)
    g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MIN_FILTER, g.GL_NEAREST)
    g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_RGBA32F, 1, 1, 1, 0, g.GL_RGBA, g.GL_FLOAT,
                   np.array([0, 0, 0, 0], dtype=np.float32))
    # every 3D sampler on its own unit with an empty texture (a 2D and a 3D sampler on one unit is invalid)
    unit = 20
    for i in range(g.glGetProgramiv(program, g.GL_ACTIVE_UNIFORMS)):
        name, size, kind = g.glGetActiveUniform(program, i)
        if kind == g.GL_SAMPLER_3D:
            g.glUniform1i(g.glGetUniformLocation(program, name), unit)
            g.glActiveTexture(g.GL_TEXTURE0 + unit)
            t3 = g.glGenTextures(1)
            g.glBindTexture(g.GL_TEXTURE_3D, t3)
            g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MIN_FILTER, g.GL_NEAREST)
            g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_RGBA32F, 1, 1, 1, 0, g.GL_RGBA, g.GL_FLOAT, np.zeros(4, dtype=np.float32))
            unit += 1
    # conventional smoke: one cascade box from 270 to 290 m ahead, density giving transmittance 0.5 over 20 m
    sigma = np.log(2.0) / 20.0
    g.glActiveTexture(g.GL_TEXTURE0 + unit)
    ts = g.glGenTextures(1)
    g.glBindTexture(g.GL_TEXTURE_3D, ts)
    g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MIN_FILTER, g.GL_NEAREST)
    g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MAG_FILTER, g.GL_NEAREST)
    g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_RGBA32F, 1, 1, 1, 0, g.GL_RGBA, g.GL_FLOAT, np.array([sigma, 0, 0, 0], dtype=np.float32))
    g.glUniform1i(loc(program, "SmokeGrid"), unit)
    cascade = np.zeros((4, 4), dtype=np.float32)
    cascade[0] = (-32, -32, -320, 1); cascade[1] = (-32, -32, -320, 1); cascade[2] = (-32, -32, -320, 1); cascade[3] = (1, 0, 0, 0)
    g.glUniformMatrix4fv(loc(program, "SmokeCascade"), 1, g.GL_FALSE, cascade)
    lo = np.zeros((4, 4), dtype=np.float32); hi = np.zeros((4, 4), dtype=np.float32)
    lo[0] = (-10, -10, -290, 1); hi[0] = (10, 10, -270, 0)
    g.glUniformMatrix4fv(loc(program, "SmokeLo"), 1, g.GL_FALSE, lo)
    g.glUniformMatrix4fv(loc(program, "SmokeHi"), 1, g.GL_FALSE, hi)
    g.glUniform3f(loc(program, "SmokeMin"), -10, -10, -290)
    g.glUniform3f(loc(program, "SmokeMax"), 10, 10, -270)
    quad(program)
    result = read(1, 1)[0, 0]
    print("near pall empty, smoke at 280 m and pane at 300 m: "
          f"estimated front light/T {result[0]:.2f}/{result[1]:.2f}, branch/reference {result[2]:.2f}/{result[3]:.2f}")
    check(abs(result[1] - 0.5) < 0.02 and abs(result[2] - result[3]) < 0.01, "smoke in front of a far pane is not tinted by it")


context()
programs = {}
for name in ("fx_see", "fx_depth", "fx_half_depth", "fx_half_composite", "fx_pall_composite", "fx", "fx_heat", "fx_probe", "fx_water"):
    p = link(name)
    programs[name] = p
    active = [g.glGetActiveUniform(p, i)[0].decode()
              for i in range(g.glGetProgramiv(p, g.GL_ACTIVE_UNIFORMS))
              if g.glGetActiveUniform(p, i)[2] in (g.GL_SAMPLER_2D, g.GL_SAMPLER_3D)]
    print(f"{name}: {len(active)} active samplers")
    if name == "fx_pall_composite":
        check(len(active) <= 16, "the pall composite stays within 16 fragment samplers")
depth_cases(programs["fx_see"], programs["fx_depth"])
half_cases(programs["fx_half_depth"], programs["fx_half_composite"])
half_edges()
far_smoke_in_front()
if FAILED:
    sys.exit(f"see-through GL check failed: {len(FAILED)}")
