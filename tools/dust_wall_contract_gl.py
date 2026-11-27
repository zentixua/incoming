# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""The young dust wall's render contract on Mesa GL 3.2 core with the shipped passes (independent review of #724, round 3):

    uv run tools/dust_wall_contract_gl.py [checkout] [case ...]

The cases exercise the march, temporal reprojection and shared pall composite,
including thin opaque edges, the near volume, panes, weather and coarse terrain.
Run by tools/dust_wall_gl.py."""
from functools import cache
from pathlib import Path
import sys

import numpy as np

args = [a for a in sys.argv[1:] if not a.isidentifier()]
root = Path(args[0]).resolve() if args else Path(__file__).resolve().parents[1]
names = [a for a in sys.argv[1:] if a.isidentifier()]
harness = root / "tools/see_through_gl.py"
namespace = {"__file__": str(harness)}
source = harness.read_text().split("\ncontext()\n", 1)[0]
source = source.replace('"3.3"', '"3.2"').replace("0x30FB, 3", "0x30FB, 2")
exec(compile(source, str(harness), "exec"), namespace)
g = namespace["g"]
context, link, target, texture, bind, quad, read, loc, check = (
    namespace[name] for name in ("context", "link", "target", "texture", "bind", "quad", "read", "loc", "check")
)
link = cache(link)
size, low = 12, 3
projection = np.array([[100, 0, 0, 0], [0, 100, 0, 0], [0, 0, -1.02, -0.2], [0, 0, -1, 0]], dtype=np.float32)
scene_format = (g.GL_RG32F, g.GL_RG)
rgba = (g.GL_RGBA32F, g.GL_RGBA)


def make_scene(opaque, near=None):
    opaque = np.broadcast_to(opaque, (size, size))
    near = opaque if near is None else np.broadcast_to(near, opaque.shape)
    return texture(*scene_format, size, size, np.stack((opaque, near), axis=-1))


def march_setup():
    program = link("fx_dustwall")
    # a new program starts from the default source (each case's inputs are its own)
    SOURCE.update(eye=(100, 9, 0, 10), ring=(800, 0, 100, 800), inner=0, relief=(0, 0), ground=(0, 0, 0, 0), law=None)
    GROUND["rows"] = None
    g.glUseProgram(program)
    g.glUniformMatrix4fv(loc(program, "InvViewProj"), 1, True, np.linalg.inv(projection).astype(np.float32))
    g.glUniform3f(loc(program, "PallForward"), 0, 0, -1)
    # every case: the drawn world reaches 168 blocks along the ground (12 chunks, NukePall.floorFrom; the relief search
    # starts there, as in the game), the horizon lift as without LODs (DustWallPass.HORIZON_LIFT)
    g.glUniform1f(loc(program, "PallFloorFrom"), 168)
    g.glUniform1f(loc(program, "DwHorizonLift"), 4)
    g.glUniform1f(loc(program, "DwDiv"), 4)
    g.glUniform1f(loc(program, "DwSourceCount"), 1)
    g.glUniform4f(loc(program, "DwSlotsA"), 0, 1, 2, 3)
    g.glUniform3f(loc(program, "DwSunDir"), 0, 1, 0)
    g.glUniform3f(loc(program, "DwSkyLight"), 1, 1, 1)
    g.glUniform3f(loc(program, "DwBulk"), 1, 1, 1)
    bind(program, 3, texture(*rgba, 1, 1, [(0, 0, 0, 0)]))
    bind(program, 4, texture(*rgba, 1, 1, [(0, 0, 0, 0)]))
    for unit, name in ((12, "DwNoise"), (13, "PallOpen")):
        g.glActiveTexture(g.GL_TEXTURE0 + unit)
        g.glBindTexture(g.GL_TEXTURE_3D, g.glGenTextures(1))
        g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
        g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
        g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R32F, 1, 1, 1, 0, g.GL_RED, g.GL_FLOAT,
                       np.array([0.5], dtype=np.float32))
        g.glUniform1i(loc(program, name), unit)
    return program


ROWS = 66  # dustwall.glsl DW_ROWS: row 0, 32 age rows, the spray row, 31 inside-sight rows, the frame inputs row


# the one source's frame inputs (dustwall.glsl dwLoad: its table's last row) and terrain tile (a ground atlas of one slot)
SOURCE = {"eye": (100, 9, 0, 10), "ring": (800, 0, 100, 800), "inner": 0, "relief": (0, 0), "ground": (0, 0, 0, 0)}
GROUND = {"rows": None}


def source(program, eye=None, ring=None, relief=None, ground=None, rows=None):
    """Set the source's eye (x, y, z, t), ring (front, -, envelope top, reach: its second number, the old inner radius, is
    the radius inside which no dust is: those nodes' front has not arrived), relief (low, high), terrain map (half side, bottom, top, cell) and its heights along z (rows: the map's v axis)."""
    if eye is not None:
        SOURCE["eye"] = tuple(eye)
    if ring is not None:
        # (the second number: no dust inside this radius, its nodes' front not yet arrived)
        SOURCE["ring"] = (ring[0], 0, ring[2], ring[3])
        SOURCE["inner"] = ring[1]
    if relief is not None:
        SOURCE["relief"] = tuple(relief)
    if ground is not None:
        SOURCE["ground"] = tuple(ground)
        GROUND["rows"] = rows
    g.glUseProgram(program)


def source_table():
    """One source in the dust wave's table: arrival 0 everywhere, a 20-block layer of optical depth 3 at every age, its frame
    inputs (SOURCE; fireball at 30 up)."""
    t = np.zeros((ROWS, 128, 4), np.float32)
    t[0, :, 1] = 1
    reach = SOURCE["ring"][3]
    t[0, :, 0] = np.where(np.arange(128) / 127 * reach < SOURCE["inner"], 1e6, 0)
    t[1:33, :, 0] = 20
    t[1:33, :, 1] = 2
    if SOURCE.get("law"):
        # a 1 Mt-like front (arrival 7.63 s at 4.5 km, r^1.25) and the settled layer: 68 blocks, optical depth 100
        arrive, at = SOURCE["law"]
        t[0, :, 0] = arrive * (np.arange(128) / 127 * reach / at) ** 1.25
        t[1:33, :, 0] = 68
        t[1:33, :, 1] = 100
    # the inside sight's rows (DW_SIGHT_ROW 34..64): the law's depth at both nodes of each interval (no camera share is set)
    t[34:65, :, 0] = t[1:32, :, 1]
    t[34:65, :, 2] = t[2:33, :, 1]
    p = t[ROWS - 1]
    p[0] = SOURCE["eye"]
    p[1] = SOURCE["ring"]
    p[2] = (1e4, 0.05, 0, 0)
    p[3] = (0, 30, 0, 1)
    p[6] = (*SOURCE["relief"], 0, 0)
    p[8] = SOURCE["ground"]
    return t.reshape(-1, 4)


def ground_tile():
    """The terrain atlas (DW_GROUND_SIDE² tile of slot 0): the heights along z resampled onto its 128 rows."""
    rows = GROUND["rows"]
    if rows is None:
        return texture(*rgba, 1, 1, [(0, 0, 0, 0)])
    h = np.interp((np.arange(128) + 0.5) / 128 * len(rows) - 0.5, np.arange(len(rows)), np.asarray(rows, np.float64))
    tile = np.zeros((128, 128, 4), np.float32)
    tile[:, :, 0] = h[:, None]
    tex = texture(*rgba, 128, 128, tile.reshape(-1, 4))
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
    return tex


def split_of(depth):
    """The interval split third (wall_layer.glsl) of one occupied interval: A is the whole span, B empty."""
    depth = np.asarray(depth, np.float32)
    split = np.zeros_like(depth)
    split[..., 0] = split[..., 1] = depth[..., 1]
    split[..., 2] = split[..., 3] = 1 - depth[..., 3]
    return split


def thirds(light, depth, split=None):
    """An atlas of light, depth span and interval split side by side (DustWallPass's packed layout)."""
    return np.concatenate((light, depth, split_of(depth) if split is None else split), axis=1)


def march(program, scene, frame):
    target(low, low, [rgba, rgba, rgba])
    g.glUseProgram(program)
    bind(program, 2, texture(*rgba, 128, ROWS, source_table()))
    bind(program, 3, ground_tile())
    bind(program, 1, scene)
    g.glUniform2f(loc(program, "DwFrame"), frame, 128)
    quad(program)
    names = ("fragColor", "wallDepth", "wallSplit")
    return tuple(read(low, low, g.glGetFragDataLocation(program, n)).copy() for n in names)


def composite(program, scene, samples, glass=None, cloud=None, rays=None, mushroom=None, layer_frame=False,
              young=True, haze=0, smoke_span=None, ground_origin=(100, 0)):
    # Run the shipped pack and resolve before the full-resolution pall composite.
    light, depth = samples[0], samples[1]
    split = samples[2] if len(samples) > 2 else split_of(depth)
    pack = link("fx_dustwall_pack")
    target(3 * low, low, [rgba])
    g.glUseProgram(pack)
    bind(pack, 0, texture(*rgba, low, low, light))
    bind(pack, 1, texture(*rgba, low, low, depth))
    bind(pack, 2, texture(*rgba, low, low, split))
    quad(pack)
    packed = texture(*rgba, 3 * low, low, read(3 * low, low))
    resolve = link("fx_dustwall_resolve")
    target(3 * low, low, [rgba])
    g.glUseProgram(resolve)
    bind(resolve, 0, packed)
    bind(resolve, 1, packed)
    bind(resolve, 2, scene)
    g.glUniform4f(loc(resolve, "DwReproject"), 0, 0, 0, 0)
    quad(resolve)
    atlas = texture(*rgba, 3 * low, low, read(3 * low, low))
    output = low if layer_frame else size
    target(output, output, [rgba])
    g.glUseProgram(program)
    clear = texture(*rgba, 1, 1, [(0, 0, 0, 0)])
    for unit in range(12):
        bind(program, unit, clear)
    g.glUniform1i(loc(program, "PallLayerFrame"), int(layer_frame))
    empty_ray = np.broadcast_to((0, 0, 0, 1), (low, low, 4))
    bind(program, 0, texture(*rgba, low, low, empty_ray if rays is None else rays))
    bind(program, 1, scene)
    bind(program, 6, atlas)
    slot = np.zeros((4, 4), dtype=np.float32)
    if haze:
        # The wall and the ground haze below its surface belong to the same dry burst.
        slot[:, 0] = (*ground_origin, 0, 800)
        slot[3, 1] = 1
        slot[2, 2], slot[3, 2] = .25, .75
        bind(program, 2, texture(*rgba, 1, 2, [(0, 10, 0, 0), (haze, 100, 0, 0)]))
    g.glUniformMatrix4fv(loc(program, "PallSlot0"), 1, True, slot)
    if mushroom is not None:
        bind(program, 7, texture(*rgba, 20, 4, mushroom))
    for name in ("SmokeGrid", "PallNoise", "PallOpen"):
        g.glUniform1i(loc(program, name), 13)
    cascade = np.zeros((4, 4), dtype=np.float32)
    if smoke_span is not None:
        near, far = smoke_span
        lo, hi = np.zeros_like(cascade), np.zeros_like(cascade)
        lo[:, 0], hi[:, 0] = (-20, -20, -far, 1), (20, 20, -near, 0)
        cascade[:, 0], cascade[0, 3] = (-20, -20, -far, 1), 1
        g.glUniform3f(loc(program, "SmokeMin"), *lo[:3, 0])
        g.glUniform3f(loc(program, "SmokeMax"), *hi[:3, 0])
        for name, matrix in (("SmokeLo", lo), ("SmokeHi", hi)):
            g.glUniformMatrix4fv(loc(program, name), 1, True, matrix)
        g.glUniform1i(loc(program, "SmokeGrid"), 14)
        g.glActiveTexture(g.GL_TEXTURE0 + 14)
        g.glBindTexture(g.GL_TEXTURE_3D, g.glGenTextures(1))
        g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MIN_FILTER, g.GL_NEAREST)
        g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R32F, 1, 1, 1, 0, g.GL_RED, g.GL_FLOAT,
                       np.ones(1, dtype=np.float32))
    g.glUniformMatrix4fv(loc(program, "SmokeCascade"), 1, True, cascade)
    g.glUniform4f(loc(program, "PallFrame"), 0, 0, 4, 0)
    g.glUniformMatrix4fv(loc(program, "InvViewProj"), 1, True, np.linalg.inv(projection).astype(np.float32))
    g.glUniform3f(loc(program, "PallForward"), 0, 0, -1)
    # the dust pass's divisor gates its insertion (0: no dust pass this frame)
    g.glUniform1f(loc(program, "DwDiv"), 4 if young else 0)
    g.glUniform4f(loc(program, "PallFroxel"), 16, 1, 256 if cloud is not None else 0, 0)
    if cloud is not None:
        bind(program, 11, texture(*rgba, 8, 8, cloud))
    g.glUniform1f(loc(program, "FxSee"), 0 if glass is None else 1)
    # FxGlass has a different texture target from the volume samplers.
    g.glUniform1i(loc(program, "FxGlass"), 16)
    if glass is not None:
        g.glActiveTexture(g.GL_TEXTURE0 + 16)
        g.glBindTexture(g.GL_TEXTURE_2D, glass)
        g.glClearColor(0.8, 0.8, 0.8, 1.0)
        g.glClear(g.GL_COLOR_BUFFER_BIT)
        g.glEnable(g.GL_BLEND)
        g.glBlendFuncSeparate(g.GL_ONE, g.GL_SRC_ALPHA, g.GL_ZERO, g.GL_ONE)
    quad(program)
    g.glDisable(g.GL_BLEND)
    return read(output, output).copy()


def slab(start, end, transmission):
    light = np.zeros((low, low, 4), dtype=np.float32)
    light[:, :, 3] = transmission
    depth = np.empty_like(light)
    depth[:] = ((1 - transmission) * start, (1 - transmission) * end, (1 - transmission) * (start + end) / 2, transmission)
    return light, depth


def pcg3(v):
    """fx_dustwall.fsh dwPcg3: three uniform values in [0, 1) of an integer triple (pcg3d, 32-bit unsigned arithmetic)."""
    m = 0xFFFFFFFF
    x, y, z = ((c * 1664525 + 1013904223) & m for c in v)
    x = (x + y * z) & m; y = (y + z * x) & m; z = (z + x * y) & m
    x ^= x >> 16; y ^= y >> 16; z ^= z >> 16
    x = (x + y * z) & m; y = (y + z * x) & m; z = (z + x * y) & m
    return tuple((c >> 8) / 16777216.0 for c in (x, y, z))


def jitter_occluder(march_program, composite_program):
    # A stationary opaque edge after column 2. The wall contributes along the
    # sky ray; a foreground end at two blocks allows only two blocks of dust.
    scene = make_scene(np.where(np.arange(size) < 3, 2.0, 100.0))
    samples_a = march(march_program, scene, 1)
    samples_b = march(march_program, scene, 3)
    a = composite(composite_program, scene, samples_a)[6, 2, 3]
    b = composite(composite_program, scene, samples_b)[6, 2, 3]
    print(f"stationary opaque edge: phase 1 T={a:.5f}, phase 3 T={b:.5f}")
    check(a > 0.8 and b > 0.8 and abs(a - b) < 0.03,
          "opaque pixel remains foreground at both R2 phases")

    # Jitter on a continuous slope marches to the sampled depth in both directions: march pixel (0, 1) at frame 1 samples
    # the full-resolution column its hashed phase and the R2 step put it in (fx_dustwall.fsh), at that column's depth.
    column = int(np.clip(0.5 * 4 + (np.modf(pcg3((0, 1, 12142))[0] + 0.7548777)[0] - 0.5) * 4, 0.5, size - 0.5))
    for depths in (10 + .25 * np.arange(size), 11 - .25 * np.arange(size)):
        light, span, _ = march(march_program, make_scene(depths), 1)
        last = span[1, 0, 1] / (1.0 - light[1, 0, 3])
        print(f"depth at lattice centre {depths[2]}, jittered depth {depths[column]} (column {column}), last dusty point {last:.4f}")
        check(abs(last - depths[column]) < .001, "a continuous surface ends the jittered ray at its own depth")


def history_occluder(far=0.10, here=20, far_depth=100, enabled=1, sparse=False):
    program = link("fx_dustwall_resolve")
    target(low, low, [rgba])
    g.glUseProgram(program)
    current = np.zeros((low, low, 4), dtype=np.float32)
    current[:, :, 3] = 0.25
    if sparse:
        current[1, :, 3] = 1.0
        current[:, 1, 3] = 1.0
    current[1, 1, 3] = 1.0
    history = np.zeros_like(current)
    history[:, :, 3] = 1.0
    history[:, 2, 3] = far
    bind(program, 0, texture(*rgba, 3 * low, low, thirds(current, current, np.zeros_like(current))))
    bounds = history.copy()
    bounds[:, 2, :3] = (1 - far) * np.array([(here + far_depth) / 2, far_depth, far_depth])
    bind(program, 1, texture(*rgba, 3 * low, low, thirds(history, bounds)))
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
    bind(program, 2, make_scene(here))
    bind(program, 3, make_scene(np.where(np.arange(size) < 8, here, far_depth)))
    prev = projection.copy()
    prev[0] += projection[3] * (1.96 * 2 / size)
    g.glUniformMatrix4fv(loc(program, "PrevViewProj"), 1, True, prev)
    g.glUniformMatrix4fv(loc(program, "InvViewProj"), 1, True, np.linalg.inv(projection).astype(np.float32))
    g.glUniform3f(loc(program, "PallForward"), 0, 0, -1)
    g.glUniform1f(loc(program, "DwDiv"), 4)
    g.glUniform1f(loc(program, "DwPrevDiv"), 4)
    g.glUniform4f(loc(program, "DwReproject"), 0, 0, 0, enabled)
    g.glUniform2f(loc(program, "DwHistory"), 0.1, 10)
    g.glUniform1f(loc(program, "DwPrevScene"), 1)
    quad(program)
    return read(low, low)[1, 1, 3]


def history_taps():
    # the same pixel with the far side's history sample dusty (T 0.10) or clear (T 1): equal when it contributes nothing
    dusty, clear = history_occluder(0.10), history_occluder(1.0)
    print(f"history reprojection at previous x=7.96 (foreground to x=8): T={dusty:.5f}, far tap clear T={clear:.5f}")
    check(abs(dusty - clear) < 1e-4, "a history sample whose previous depth is another place contributes nothing")


def motion_atlas(T, start, end):
    T = np.asarray(T, dtype=np.float32)
    light = np.zeros((low, low, 4), dtype=np.float32)
    light[:, :, 3] = T
    depth = np.zeros_like(light)
    c = 1 - T
    depth[:, :, 0], depth[:, :, 1], depth[:, :, 2], depth[:, :, 3] = c * start, c * end, c * (start + end) / 2, T
    return thirds(light, depth)


def motion_resolve(history_end, prev_depth=20.05, here=20.0, forward=0.05, yaw=0.0):
    p = link("fx_dustwall_resolve")
    target(3 * low, low, [rgba])
    g.glUseProgram(p)
    noise = np.where((np.add.outer(np.arange(low), np.arange(low)) % 2) == 0, 0.7, 0.5)
    bind(p, 0, texture(*rgba, 3 * low, low, motion_atlas(noise, 0.5, here)))
    bind(p, 1, texture(*rgba, 3 * low, low, motion_atlas(np.full((low, low), 0.4), 0.5, history_end)))
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
    bind(p, 2, make_scene(here))
    bind(p, 3, make_scene(prev_depth))
    projection = np.array([[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, -1.02, -0.2], [0, 0, -1, 0]], dtype=np.float32)
    rotation = np.eye(4, dtype=np.float32)
    rotation[0, 0] = rotation[2, 2] = np.cos(yaw)
    rotation[0, 2], rotation[2, 0] = np.sin(yaw), -np.sin(yaw)
    g.glUniformMatrix4fv(loc(p, "PrevViewProj"), 1, True, projection @ rotation)
    g.glUniformMatrix4fv(loc(p, "InvViewProj"), 1, True, np.linalg.inv(projection).astype(np.float32))
    g.glUniform3f(loc(p, "PallForward"), 0, 0, -1)
    g.glUniform1f(loc(p, "DwDiv"), 4)
    g.glUniform1f(loc(p, "DwPrevDiv"), 4)
    # this eye minus the previous eye: moved forward (−z) by `forward`
    g.glUniform4f(loc(p, "DwReproject"), 0, 0, -forward, 1)
    g.glUniform2f(loc(p, "DwHistory"), 0.1, 10)
    g.glUniform1f(loc(p, "DwPrevScene"), 1)
    quad(p)
    return read(3 * low, low)[1, 1, 3]


def motion_case():
    moving = motion_resolve(history_end=20.05)
    control = motion_resolve(history_end=19.9)
    # At the centre of the frame yaw changes previous view-axis depth by cos(yaw).
    yaw = np.deg2rad(25)
    previous = 20 * np.cos(yaw)
    turning = motion_resolve(previous, prev_depth=previous, forward=0, yaw=yaw)
    ceiling = motion_resolve(100, prev_depth=100)
    print(f"Current T=0.7000, moving={moving:.4f}, control={control:.4f}, turning={turning:.4f}, new ceiling={ceiling:.4f}")
    check(np.allclose([moving, control, turning, ceiling], [.4858, .4858, .4858, .7], atol=.001),
          "translation and rotation preserve dust history until a new ceiling occludes it")


def surface_rows(depths):
    light, span = slab(.5, 100, .5)
    # March centres are at rows 2, 6 and 10. Each ray contains dust up to its surface.
    end = depths[2::4, None]
    span[:, :, 1] = .5 * end
    span[:, :, 2] = .5 * (.5 + end) / 2
    result = composite(link("fx_pall_composite"), make_scene(depths[:, None]), (light, span))
    return result[:, 6, 3]


def edge_case():
    rows = np.arange(size, dtype=np.float32)
    plane = surface_rows(100 + .25 * rows)
    corner = surface_rows(100 + .25 * abs(rows - 4))
    print(f"Sloped ground T by row {np.round(plane, 4)}, convex corner T={corner[4]:.4f}")
    check(np.allclose(plane, .5, atol=.004) and np.allclose(corner, .5, atol=.004),
          "dust covers the sloped ground up to the frame edge and across the convex corner")


def sparse_taps(program):
    # Only the far diagonal tap of the 2 x 2 neighbourhood contains dust. Pixel (6,6) also supplies layer pixel (1,1).
    light, span = slab(0, 0, 1)
    dusty = slab(20, 80, .5)
    light[2, 2], span[2, 2] = dusty[0][2, 2], dusty[1][2, 2]
    depths = np.full((size, size), 400.0)
    # bilinear weights on the march lattice (pixel centre / 4 - 0.5)
    w = lambda f: f ** 2
    for layer in (False, True):
        buffer = composite(program, make_scene(depths), (light, span), layer_frame=layer)
        x = 1 if layer else 6
        expected = 1 - .5 * w(.125)
        check(abs(buffer[x, x, 3] - expected) < 1e-6, "a clear centre still sees the occupied diagonal tap")
    depths[10, 10] = 800
    result = composite(program, make_scene(depths), (light, span))
    check(result[6, 6, 3] == 1, "a valid clear centre excludes dust in another depth class")
    depths[7, 7] = 200
    result = composite(program, make_scene(depths), (light, span))
    expected = 1 - .5 * w(.375)
    check(abs(result[7, 7, 3] - expected) < 1e-6, "an unsampled depth class uses the whole neighbourhood")


def pane_order(composite_program):
    clear = make_scene(200, 100)
    glass = texture(*rgba, size, size, [(0.4, 0.4, 0.4, 0.5)] * (size * size))
    for first, last, expected in ((20, 80, .2), (120, 180, .5), (50, 150, .3)):
        red = composite(composite_program, clear, slab(first, last, .25), glass)[6, 6, 0]
        print(f"dust at {first}–{last}, glass at 100: red {red:.5f}, expected {expected:.2f}")
        check(abs(red - expected) < .001, "wall and pane in depth order")


def mixed_depth(march_program, program):
    g.glUseProgram(march_program)
    source(march_program, eye=(0, 9, 600, 10))
    source(march_program, ring=(450, 0, 100, 800))
    depths = np.full((size, size), 400.0)
    depths[:, :3] = 10
    depths[:, 4] = 20
    depths[:, 8] = .1
    scene = make_scene(depths)
    samples = march(march_program, scene, 2)
    result = composite(program, scene, samples)[6, :, 3]
    direct = march(march_program, make_scene(20), 2)[0][1, 1, 3]
    far = march(march_program, make_scene(400), 2)[0][1, 1, 3]
    print(f"post T={result[4]:.6f}, direct={direct:.6f}, terrain={far:.6f}, adjacent background={result[5]:.6f}")
    check(direct > .999 and far < .1 and abs(result[4] - direct) < .001, "thin post between near and far taps")
    check(result[8] > .999 and result[5] < .1, "clear hand and dusty first background pixel")
    depths[:] = 109
    depths[:, 4] = 100
    result = composite(program, make_scene(depths), slab(101, 109, .25))[6, 4, 3]
    check(result > .999, "dust nine blocks behind a foreground edge stays behind")
    result = composite(program, make_scene(depths), slab(91, 109, .25))[6, 4, 3]
    check(abs(result - .5) < .001, f"only the foreground half of a crossing tap contributes: T={result:.5f}")


def fragment_program(source):
    return namespace["compileProgram"](
        namespace["compileShader"]((namespace["CORE"] / "fx_dustwall.vsh").read_text(), g.GL_VERTEX_SHADER),
        namespace["compileShader"](namespace["expand"](source), g.GL_FRAGMENT_SHADER), validate=False)


def offscreen_dust():
    # Both the quad vertex shader and the light probe call this function for their endpoint.
    program = fragment_program("""#version 150
#moj_import <incoming:pall.glsl>
#moj_import <incoming:pall_front.glsl>
uniform vec3 Endpoint;
uniform sampler2D Scene;
out vec4 fragColor;
void main() {
    fragColor = vec4(pallThrough(Scene, normalize(Endpoint), length(Endpoint)));
}
""")
    target(1, 1, [rgba])
    g.glUseProgram(program)
    slot = np.zeros((4, 4), dtype=np.float32)
    slot[:, 0] = (100, 0, 0, 800)
    slot[3, 1] = 1
    slot[3, 2] = .75  # the haze row
    g.glUniformMatrix4fv(loc(program, "PallSlot0"), 1, True, slot)
    g.glUniformMatrix4fv(loc(program, "DwViewProj"), 1, True, projection)
    g.glUniform3f(loc(program, "PallMin"), -100, -100, -100)
    g.glUniform3f(loc(program, "PallMax"), 100, 100, 100)
    g.glUniform4f(loc(program, "PallAmbient"), 0, 0, 0, 1)
    g.glUniform1i(loc(program, "PallOpen"), 13)
    bind(program, 0, make_scene(100))
    g.glUniform1i(loc(program, "Scene"), 0)
    bind(program, 5, texture(*rgba, 1, 2, [(0, 10, 0, 0), (.02, 10, 0, 0)]))
    bind(program, 10, texture(*rgba, low * 3, low, thirds(*slab(1, 15, .25))))
    for point, visible in [((0, 0, -20), True), ((1, 0, -20), False), ((0, 0, 20), False)]:
        g.glUniform3f(loc(program, "Endpoint"), *point)
        values = []
        for div in (0, 4):
            g.glUniform1f(loc(program, "DwDiv"), div)
            quad(program)
            values.append(read(1, 1)[0, 0, 0])
        field, active = values
        # the pall's haze stays in front of the quad; the dust pass's atlas adds its own transmission where the point is in it
        check(.5 < field < .8 and abs(active - field * (.25 if visible else 1)) < .001,
              f"light/quad at {point}: field T={field:.4f}, active T={active:.4f}")


def foreground_volume(march_program, program):
    g.glUseProgram(march_program)
    source(march_program, eye=(0, 9, 600, 10))
    source(march_program, ring=(450, 0, 100, 800))
    scene = make_scene(400)
    samples = march(march_program, scene, 2)
    direct = march(march_program, make_scene(30), 2)[0][1, 1, 3]
    wall = samples[0][1, 1, 3]
    values = []
    controls = []
    for light in (.2, .8):
        cloud = np.zeros((64, 4), dtype=np.float32)
        cloud[:, 3] = 1
        cloud[256 ** (np.arange(1, 65) / 64) > 30] = (light, light, light, 0)
        values.append(composite(program, scene, samples, cloud=cloud)[6, 6, 0])
        controls.append(composite(program, scene, slab(0, 0, 1), cloud=cloud)[6, 6, 0])
    gain = (values[1] - values[0]) / (controls[1] - controls[0])
    print(f"cloud at 30: T to cloud={direct:.6f}, T to terrain={wall:.6f}, foreground contrast retained={gain:.6f}")
    check(direct > .999 and wall < .1 and controls[1] - controls[0] > .1, "opaque cloud fixture spans real contrast")
    check(abs(gain - 1) < .001 and np.allclose(values, controls, atol=.001), "wall behind an opaque foreground volume")
    behind = np.broadcast_to((.6, .6, .6, 0), (low, low, 4))
    dark = np.broadcast_to((0, 0, 0, 0), behind.shape)
    gain = (composite(program, scene, samples, rays=behind)[6, 6, 0]
            - composite(program, scene, samples, rays=dark)[6, 6, 0]) / .6
    check(abs(gain - wall) < .01, "distant volume behind the wall takes its transmission")

    # The far ray holds the outgoing colour of an opaque cap. Its actual field
    # separates cases with the cap wholly before or wholly beyond the dust.
    caps = np.zeros((4, 20, 4), dtype=np.float32)
    caps[0, 1] = (0, 0, 40, 30)        # drift, end height, bounding radius
    caps[0, 2] = (20, 20, 10, 40)      # cap height, radius, ring, upper bound
    caps[0, 3] = (10, 10, 20, 20)      # ring section and dome
    caps[0, 4] = (20, 2, 1, 1)         # dome height and cap extinction
    for distance, expected in ((70, 1.0), (300, wall)):
        caps[0, 0] = (0, -distance, -20, 1)
        bright = composite(program, scene, samples, rays=behind, mushroom=caps)[6, 6, 0]
        dim = composite(program, scene, samples, rays=dark, mushroom=caps)[6, 6, 0]
        layer = composite(program, scene, samples, rays=behind, mushroom=caps, layer_frame=True)[1, 1, 0]
        check(abs(bright - layer) < 1e-4, "layer quads and the display use the same ordered medium")
        glass = texture(*rgba, size, size, [(.4, .4, .4, .5)] * (size * size))
        red = [composite(program, make_scene(400, 100), slab(0, 0, 1), glass, rays=dark, mushroom=caps,
                         young=young)[6, 6, 0] for young in (True, False)]
        expected_red = 0 if distance < 100 else .4
        check(np.allclose(red, expected_red, atol=.001), f"cap at {distance} through glass: wall on/off {red}")
        gain = (bright - dim) / .6
        check(abs(gain - expected) < .01, f"cap at {distance}: contrast {gain:.6f}, expected {expected:.6f}")

    base = composite(program, scene, samples, rays=dark)[6, 6, 0]
    check(base > .05, f"wall over dark rays: {base:.5f}")

    # A smoke grid in front retains its measured near-volume light. Moving the cloud behind the wall exposes it.
    for span, expected in (((22, 38), 0.0), ((280, 300), base)):
        shown = composite(program, scene, samples, rays=dark, smoke_span=span)[6, 6, 0]
        check(abs(shown - expected) < .001, f"smoke at {span}: {shown:.5f} vs {expected:.5f}")


def crossing_volume(program):
    # A narrow, opaque slice moves along a black 100-block wall. Its outgoing
    # light reaches the eye through the part of the wall between it and the eye.
    scene = make_scene(400)
    samples = slab(100, 200, .25)
    rays = np.broadcast_to((.6, .6, .6, 0), (low, low, 4))
    for z in (95, 100, 110, 125, 150, 175, 190, 200):
        thickness = 8
        shown = composite(program, scene, samples, rays=rays, smoke_span=(z, z + thickness))[6, 6, 0]
        # The grid density is one per block. Integrate the two media together
        # on a fine CPU grid, including the zero-extinction regions at each end.
        dz = .01
        positions = z + (np.arange(thickness / dz) + .5) * dz
        wall_t = .25 ** np.clip((positions - 100) / 100, 0, 1)
        expected = .6 * np.sum(np.exp(-(positions - z)) * wall_t * dz)
        # Rays include the fully opaque remainder behind the smoke slice.
        expected += .6 * np.exp(-thickness) * .25
        check(abs(shown - expected) < .004,
              f"smoke crossing wall at {z}: red {shown:.5f}, integrated {expected:.5f}")

    # Both opaque volumes occupy the same narrow depth band. They can only
    # hide dust behind that band, including when they overlap each other.
    cap = np.zeros((4, 20, 4), dtype=np.float32)
    cap[0, 1] = (0, 0, 5, 3)
    cap[0, 2] = (2, 2, 1, 5)
    cap[0, 3] = (1, 1, 2, 2)
    cap[0, 4] = (2, 20, 1, 1)
    for z in (115, 150, 185):
        cap[0, 0] = (0, -z, -2, 1)
        near = .6 * .25 ** ((z - 3 - 100) / 100)
        far = .6 * .25 ** ((z + 3 - 100) / 100)
        for smoke in (None, (z - 2, z + 2)):
            red = composite(program, scene, samples, rays=rays, mushroom=cap, smoke_span=smoke)[6, 6, 0]
            check(far <= red <= near, f"cap at {z}, smoke {smoke}: {far:.5f} <= {red:.5f} <= {near:.5f}")

    # The pane truncates both the wall and the interval in which smoke covers
    # it. Smoke beyond the pane cannot obscure the pane's own light.
    glass = texture(*rgba, size, size, [(.4, .4, .4, .5)] * (size * size))
    for z in (120, 155):
        red = composite(program, make_scene(400, 150), samples, glass, rays=rays,
                        smoke_span=(z, z + 8))[6, 6, 0]
        smoke = composite(program, scene, samples, rays=rays, smoke_span=(z, z + 8))[6, 6, 0]
        expected = smoke if z < 150 else .5 * smoke + .4 * .5
        check(abs(red - expected) < .002, f"crossing smoke at {z}, pane at 150: {red:.5f} vs {expected:.5f}")


def terrain_occluder(march_program):
    # Chunks end at 256, the relief map has a ridge ~600 blocks away,
    # and the only dust is on the far side in the valley behind it.
    g.glUseProgram(march_program)
    source(march_program, eye=(0, 12, 0, 10))
    source(march_program, ring=(1400, 900, 100, 1400))
    source(march_program, relief=(0, 32))
    source(march_program, ground=(1600, 0, 32, 200), rows=[32 if j == 4 else 0 for j in range(16)])
    absent = march(march_program, make_scene(1e9), 2)[0][1, 1, 3]
    drawn = march(march_program, make_scene(575), 2)[0][1, 1, 3]
    # Reset inputs for the other cases.
    source(march_program, relief=(0, 0), ground=(0, 0, 0, 0))
    print(f"ridge beyond draw distance: T={absent:.5f}, opaque ridge control T={drawn:.5f}")
    check(abs(absent - drawn) < 0.01 and drawn > 0.99, "coarse relief occludes dust when scene depth is sky")


def weather_haze(march_program, composite_program):
    g.glUseProgram(march_program)
    source(march_program, eye=(0, 9, 0, 10))
    source(march_program, ring=(3500, 2500, 100, 3500))
    g.glUniform3f(loc(march_program, "DwSkyLight"), 0.2, 0.2, 0.2)
    g.glUniform4f(loc(march_program, "PallAir"), 0, 0, 3.912 / 1000, 1 / 1200)
    # the airlight: the sky's colour behind it (a hazed ray shows the airlight)
    g.glUniform4f(loc(march_program, "PallAirFog"), 0.55, 0.55, 0.55, 512)
    g.glUniform3f(loc(march_program, "PallAirSky"), 0.55, 0.55, 0.55)
    scene = make_scene(1e9)
    samples = march(march_program, scene, 2)
    shown = composite(composite_program, scene, samples)[6, 6]
    sky = 0.55
    actual = shown[0] + shown[3] * sky
    max_contrast = np.exp(-3.912 * 2450 / 1000)
    print(f"thunderstorm, dust >=2450 blocks away: red={actual:.5f} against sky={sky:.2f}, air T<={max_contrast:.6f}")
    check(abs(actual - sky) < 0.01, "distant dust fades into thunderstorm airlight")


# review round 4 (#724): one depth rule across the passes, the relief only where the scene has no depth, bright fog
def history_cases():
    def resolve(far=0.10, far_depth=109, enabled=1):
        return history_occluder(far, 100, far_depth, enabled, sparse=True)

    shipped = history_occluder(1.0)
    dusty = resolve()
    clear = resolve(far=1.0)
    incompatible = resolve(far_depth=120)
    current = resolve(enabled=0)
    close = resolve(far_depth=102)
    check(abs(close - clear) < 1e-4, f"history dust behind a surface in the same depth class: T={close:.5f}, expected {clear:.5f}")
    print(f"R3 fixture with all history clear: T={shipped:.5f} (current T=1)")
    print(f"100-block surface, history taps at 109: T={dusty:.5f}, clear far taps T={clear:.5f}, "
          f"rejected 120-block taps T={incompatible:.5f}, current only T={current:.5f}")
    check(abs(shipped - 1.0) < 1e-4, "a clear accepted history and clear current ray stay clear")
    check(abs(dusty - incompatible) < 1e-4, "history does not mix a depth class the composite rejects")


def bind_relief(program, eye_z, eye_y, heights):
    g.glUseProgram(program)
    hi = max(heights.values(), default=0)
    source(program, eye=(0, eye_y, eye_z, SOURCE["eye"][3]), relief=(0, hi), ground=(1024, 0, hi, 16),
           rows=[heights.get(-1016 + 16 * j, 0) for j in range(128)])


def relief_cases():
    program = march_setup()
    source(program, eye=(0, 9, 0, 10))
    bind_relief(program, 0, 9, {8: 32, -8: 32})
    # The near coarse cells describe nearby roofs. Scene depth describes the actual ray through a street/opening.
    coarse = march(program, make_scene(180), 2)[0][1, 1, 3]
    bind_relief(program, 0, 9, {})
    open_street = march(program, make_scene(180), 2)[0][1, 1, 3]
    print(f"drawn opening along a street, depth 180: coarse nearby roofs T={coarse:.5f}, open-ray T={open_street:.5f}")
    check(abs(coarse - open_street) < 0.01, "coarse map cannot stop a ray inside the drawn world")

    # Eye outside the ring. The ridge is past the drawn chunks but before the first possible dust.
    source(program, eye=(0, 9, 1000, 10))
    source(program, ring=(450, 0, 100, 800))
    bind_relief(program, 1000, 9, {584: 32, 600: 32, 616: 32})
    sky = march(program, make_scene(1e9), 2)[0][1, 1, 3]
    depth = march(program, make_scene(380), 2)[0][1, 1, 3]
    print(f"eye outside ring, ridge at ~400, first dust after 548: sky depth T={sky:.5f}, drawn ridge T={depth:.5f}")
    check(abs(sky - depth) < 0.01, "relief in front of the dust span still hides it")


def pitch_view(program, degrees):
    """The view pitched down by degrees (the march's rays and its forward axis)."""
    c, s_ = np.cos(np.radians(degrees)), np.sin(np.radians(-degrees))
    view = np.array([[1, 0, 0, 0], [0, c, s_, 0], [0, -s_, c, 0], [0, 0, 0, 1]], np.float32)
    g.glUseProgram(program)
    g.glUniformMatrix4fv(loc(program, "InvViewProj"), 1, True, np.linalg.inv(projection @ view).astype(np.float32))
    g.glUniform3f(loc(program, "PallForward"), 0, -s_, -c)


def horizon_cases():
    """Artem 27.11: a wave standing on the ground past the drawn chunks floated over a strip of sky. A ray with no world
    behind it that goes under the undrawn ground is raised toward that ground's horizon (dwRayFloor): flat ground, the drawn
    world to 168 blocks, the march's lower row 0.19-0.57° down (the strip: under the drawn edge's 0.55° and over the
    wall's foot), a 20-block layer whose near edge stands 400 and 2000 blocks out."""
    program = march_setup()
    flat = [0] * 16
    for wall in (400, 2000):
        source(program, eye=(0, 1.62, 800 + wall, 10), ring=(800, 0, 100, 800), relief=(0, 0), ground=(4096, 0, 0, 64),
               rows=flat)
        light = march(program, make_scene(1e9), 2)[0]
        strip, level = light[0, 1, 3], light[1, 1, 3]
        print(f"eye 1.62 up, wall {wall} out: the strip's row T={strip:.4f}, the level row T={level:.4f}")
        check(strip < 0.05 and level < 0.05, f"the wave {wall} blocks out stands on the drawn edge, no sky under it")
    # with LODs drawn this frame the far ground is there: no raise (a hole in the LOD terrain keeps the relief's end)
    g.glUseProgram(program)
    g.glUniform1f(loc(program, "DwHorizonLift"), 0)
    lod = march(program, make_scene(1e9), 2)[0][0, 1, 3]
    g.glUniform1f(loc(program, "DwHorizonLift"), 4)
    print(f"eye 1.62 up, wall 2000 out, LODs drawn: the strip's row T={lod:.4f}")
    check(lod > 0.99, "with LODs drawn a ray under the ground is not raised")
    # an eye 20 up, looking 4° down at a wall 2 km out: its ray meets the undrawn ground 286 blocks out, raised by the lift's
    # passes the drawn edge 8 m up, so it is not raised: the sky stays (vanilla's undrawn ground, as in any view from above;
    # raised to the horizon it would cross the layer 14 blocks up). More such eyes: tools/dust_wall_horizon_probe.py
    source(program, eye=(0, 20, 2800, 10))
    pitch_view(program, 4)
    high = march(program, make_scene(1e9), 2)[0][1, 1, 3]
    print(f"eye 20 up, 4° down at a wall 2 km out: T={high:.4f}")
    check(high > 0.99, "a high eye's ray down onto the undrawn ground is not raised past the lift")


def bright_air():
    mp = march_setup()
    comp = link("fx_pall_composite")
    g.glUseProgram(mp)
    source(mp, eye=(0, 9, 0, 10))
    source(mp, ring=(3500, 2500, 100, 3500))
    g.glUniform3f(loc(mp, "DwSkyLight"), 0.2, 0.2, 0.2)
    g.glUniform4f(loc(mp, "PallAir"), 0, 0, 3.912 / 1000, 1 / 1200)
    # Vanilla default biome fog at noon and 32 chunks, rain/thunder both 1, fully scaled Night Vision.
    fog = np.array([192 / 255 * 0.5 / 0.6, 216 / 255 * 0.5 / 0.6, 1.0])
    g.glUniform4f(loc(mp, "PallAirFog"), *fog, 512)
    g.glUniform3f(loc(mp, "PallAirSky"), *fog)
    scene = make_scene(1e9)
    samples = march(mp, scene, 2)
    laid = composite(comp, scene, samples)[6, 6]
    shown = laid[:3] + laid[3] * fog
    print(f"bright fog, weather air T<={np.exp(-3.912 * 2450 / 1000):.6f}, wall T={laid[3]:.5f}, "
          f"fog RGB={np.round(fog, 5)}, composite RGB={np.round(shown, 5)}")
    low = fog <= 0.9
    check(np.max(abs(fog - shown)[low]) < 0.01, "fully hazed dust blends to the fog (channels up to 0.9)")
    check(np.all(shown[~low] > 0.9), "a fog channel over 0.9 shows over 0.9 (the film shoulder's limit: not exact)")


def ray_limit():
    # The pall rays' end at the dust wave (fx_dustwall_limit): the world distance where the dust lets 2 % or more through,
    # the depth where it lets exactly 2 % through where it is denser; pixels the rays do not read this frame keep their value
    program = link("fx_dustwall_limit")
    g.glUseProgram(program)
    g.glUniform1f(loc(program, "DwDiv"), 4)
    for transmission, expected, label in ((0.5, 400.0, "thin dust keeps the world distance"),
                                          (0.001, 20 + 60 * np.log(0.02) / np.log(0.001), "opaque dust ends the rays where 2 % is left")):
        light, depth = slab(20, 80, transmission)
        atlas = texture(*rgba, 3 * low, low, thirds(light, depth))
        target(size, size, [rgba])
        g.glClearColor(-7, -7, -7, -7)
        g.glClear(g.GL_COLOR_BUFFER_BIT)
        g.glUseProgram(program)
        g.glUniform4f(loc(program, "PallLimit"), 1, 1, 0, 0)
        bind(program, 0, atlas)
        bind(program, 1, make_scene(400.0))
        quad(program)
        out = read(size, size)[..., 0]
        print(f"ray limit, dust transmission {transmission}: {out.min():.3f}..{out.max():.3f} (expected {expected:.3f})")
        check(np.allclose(out, expected, atol=0.05), label)
    # the rays' own pixels only (factor 2, interleave 2, offset (1, 0)): the rest stays as it was
    target(size, size, [rgba])
    g.glClearColor(-7, -7, -7, -7)
    g.glClear(g.GL_COLOR_BUFFER_BIT)
    g.glUseProgram(program)
    g.glUniform4f(loc(program, "PallLimit"), 2, 2, 1, 0)
    light, depth = slab(20, 80, 0.5)
    bind(program, 0, texture(*rgba, 3 * low, low, thirds(light, depth)))
    bind(program, 1, make_scene(400.0))
    quad(program)
    out = read(size, size)[..., 0]
    written = np.argwhere(out != -7)
    want = [(y, x) for y in range(size) for x in range(size) if x % 2 == 1 and y % 2 == 1 and (x // 2) % 2 == 1 and (y // 2) % 2 == 0]
    check(sorted(map(tuple, written.tolist())) == sorted(want), "only the pixels the rays read this frame are written")
    g.glClearColor(0, 0, 0, 0)


def skin_case():
    """The march reaches the wall's dense dust cell by cell after a multi-cell thin span (the veil over the billowed top, thin
    dust whose span grew): a ground-level eye 120 blocks outside a 1 Mt-like front, the real noise texture, sun from the
    side. For every ray (the probe: its k-th occupied sample's extinction, span and depth in the layer), the first dense
    sample (extinction over 0.05) never follows a span longer than one cell while lying more than 8 blocks deep in the layer:
    a span that lands there has skipped the lit skin (a dark speck in the picture, 3:1 against the skin's light in game).
    Without the march's back-up the fixture has 1-6 such rays per frame."""
    global low, size, projection
    saved = low, size, projection
    low, size = 64, 256
    projection = np.array([[1.2, 0, 0, 0], [0, 2.1, 0, 0], [0, 0, -1.02, -0.2], [0, 0, -1, 0]], dtype=np.float32)
    try:
        program = march_setup()
        reach, arrive = 9000.0, 7.63
        t = arrive + 1.0
        front = 4500 * (t / arrive) ** 0.8
        SOURCE["law"] = (arrive, 4500.0)
        source(program, eye=(0, 2, front + 120, t), ring=(front, 0, 400, reach))
        g.glActiveTexture(g.GL_TEXTURE0 + 12)
        g.glBindTexture(g.GL_TEXTURE_3D, g.glGenTextures(1))
        for f in (g.GL_TEXTURE_MIN_FILTER, g.GL_TEXTURE_MAG_FILTER):
            g.glTexParameteri(g.GL_TEXTURE_3D, f, g.GL_LINEAR)
        for f in (g.GL_TEXTURE_WRAP_S, g.GL_TEXTURE_WRAP_T, g.GL_TEXTURE_WRAP_R):
            g.glTexParameteri(g.GL_TEXTURE_3D, f, g.GL_REPEAT)
        g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R32F, 128, 128, 128, 0, g.GL_RED, g.GL_FLOAT,
                       np.random.default_rng(7).random((128, 128, 128), dtype=np.float32))
        g.glUniform1i(loc(program, "DwNoise"), 12)
        g.glUniform3f(loc(program, "DwSunLight"), 1.0, 0.95, 0.9)
        g.glUniform3f(loc(program, "DwSunDir"), 0.2, 0.75, 0.63)
        g.glUniform3f(loc(program, "DwSkyLight"), 0.2, 0.22, 0.25)
        scene = make_scene(1e9)
        for frame in (0, 5, 11):
            records = []
            for k in range(24):
                g.glUseProgram(program)
                g.glUniform1f(loc(program, "DwProbe"), k + 1)
                records.append(march(program, scene, frame))
            count = records[0][2][..., 0]
            rays = skipped = 0
            for y, x in np.ndindex(count.shape):
                before = None
                for k in range(min(int(count[y, x]), 24)):
                    sample, span = records[k][0][y, x], records[k][1][y, x]
                    if sample[0] > 0.05:
                        rays += 1
                        skipped += bool(span[3] > 8 and before is not None and before > 3.6)
                        break
                    before = span[0]
            check(rays > 1000 and skipped == 0,
                  f"frame {frame}: {rays} rays reach dense dust, {skipped} of them past the skin after a multi-cell span")
        g.glUseProgram(program)
        g.glUniform1f(loc(program, "DwProbe"), 0)
    finally:
        low, size, projection = saved
        SOURCE["law"] = None


def haze_only_cases(composite_program):
    """The foreground-order contracts again with the composite's haze-only path on (DwHazeOnly 1: only the haze level is
    left). Their dust starts 20-200 blocks from the eye, so the haze does not surround the eye there and smoke, mushroom
    caps, near-volume clouds and panes in front of it still hide it (an eye above the layer: a hill, a tower, a craft)."""
    global quad
    plain = quad

    def haze_quad(program):
        if program == composite_program:
            g.glUniform1f(loc(program, "DwHazeOnly"), 1.0)
        plain(program)

    quad = haze_quad
    try:
        foreground_volume(march_setup(), composite_program)
        pane_order(composite_program)
        crossing_volume(composite_program)
        weather_haze(march_setup(), composite_program)
    finally:
        quad = plain
        g.glUseProgram(composite_program)
        g.glUniform1f(loc(composite_program, "DwHazeOnly"), 0.0)


def main():
    context()
    composite_program = link("fx_pall_composite")
    cases = {
        "sparse": lambda: sparse_taps(composite_program),
        "offscreen": offscreen_dust,
        "post": lambda: mixed_depth(march_setup(), composite_program),
        "crossing": lambda: crossing_volume(composite_program),
        "volume": lambda: foreground_volume(march_setup(), composite_program),
        "jitter": lambda: jitter_occluder(march_setup(), composite_program),
        "history": history_taps,
        "motion": motion_case,
        "edge": edge_case,
        "pane": lambda: pane_order(composite_program),
        "terrain": lambda: terrain_occluder(march_setup()),
        "weather": lambda: weather_haze(march_setup(), composite_program),
        "history2": history_cases,
        "relief": relief_cases,
        "horizon": horizon_cases,
        "fog": bright_air,
        "limit": ray_limit,
        "skin": skin_case,
        "hazeonly": lambda: haze_only_cases(composite_program),
    }
    for name in names or cases:
        cases[name]()
    return bool(namespace["FAILED"])


if __name__ == "__main__":
    sys.exit(main())
