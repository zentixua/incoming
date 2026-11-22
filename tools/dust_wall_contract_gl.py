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
    g.glUseProgram(program)
    g.glUniformMatrix4fv(loc(program, "InvViewProj"), 1, True, np.linalg.inv(projection).astype(np.float32))
    g.glUniform3f(loc(program, "PallForward"), 0, 0, -1)
    g.glUniform1f(loc(program, "DwDiv"), 4)
    g.glUniform4f(loc(program, "DwEye"), 100, 9, 0, 10)
    g.glUniform4f(loc(program, "PallYoung"), 0, 1, 0, 800)
    g.glUniform4f(loc(program, "DwRing"), 800, 0, 100, 800)
    g.glUniform4f(loc(program, "DwBall"), 0, 30, 0, 1)
    g.glUniform3f(loc(program, "DwSunDir"), 0, 1, 0)
    g.glUniform3f(loc(program, "DwSkyLight"), 1, 1, 1)
    g.glUniform3f(loc(program, "DwBulk"), 1, 1, 1)
    bind(program, 3, texture(*rgba, 1, 1, [(0, 0, 0, 0)]))
    for unit, name in ((12, "DwNoise"), (13, "PallOpen")):
        g.glActiveTexture(g.GL_TEXTURE0 + unit)
        g.glBindTexture(g.GL_TEXTURE_3D, g.glGenTextures(1))
        g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
        g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
        g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R32F, 1, 1, 1, 0, g.GL_RED, g.GL_FLOAT,
                       np.array([0.5], dtype=np.float32))
        g.glUniform1i(loc(program, name), unit)
    return program


def march(program, scene, frame):
    target(low, low, [rgba, rgba])
    g.glUseProgram(program)
    bind(program, 2, texture(*rgba, 128, 1, [(0, 20, 0.11, 0.3)] * 128))
    bind(program, 1, scene)
    g.glUniform2f(loc(program, "DwFrame"), frame, 128)
    quad(program)
    colour = g.glGetFragDataLocation(program, "fragColor")
    light, depth = read(low, low, colour).copy(), read(low, low, 1 - colour).copy()
    return light, depth


def composite(program, scene, samples, glass=None, cloud=None, rays=None, mushroom=None, layer_frame=False, young=True):
    # Run the shipped pack and resolve before the full-resolution pall composite.
    light, depth = samples
    pack = link("fx_dustwall_pack")
    target(2 * low, low, [rgba])
    g.glUseProgram(pack)
    bind(pack, 0, texture(*rgba, low, low, light))
    bind(pack, 1, texture(*rgba, low, low, depth))
    quad(pack)
    packed = texture(*rgba, 2 * low, low, read(2 * low, low))
    resolve = link("fx_dustwall_resolve")
    target(2 * low, low, [rgba])
    g.glUseProgram(resolve)
    bind(resolve, 0, packed)
    bind(resolve, 1, packed)
    bind(resolve, 2, scene)
    g.glUniform4f(loc(resolve, "DwReproject"), 0, 0, 0, 0)
    quad(resolve)
    atlas = texture(*rgba, 2 * low, low, read(2 * low, low))
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
    bind(program, 4, atlas)
    if mushroom is not None:
        bind(program, 7, texture(*rgba, 20, 4, mushroom))
    for name in ("SmokeGrid", "PallNoise", "PallOpen"):
        g.glUniform1i(loc(program, name), 13)
    g.glUniform4f(loc(program, "PallYoung"), 0, 1, 0, 800 if young else 0)
    g.glUniform4f(loc(program, "PallFrame"), 0, 0, 4, 0)
    g.glUniformMatrix4fv(loc(program, "InvViewProj"), 1, True, np.linalg.inv(projection).astype(np.float32))
    g.glUniform3f(loc(program, "PallForward"), 0, 0, -1)
    g.glUniform1f(loc(program, "DwDiv"), 4)
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

    # Jitter on a continuous slope marches to the sampled depth in both directions.
    for depths in (10 + .25 * np.arange(size), 11 - .25 * np.arange(size)):
        light, span = march(march_program, make_scene(depths), 1)
        last = span[1, 0, 1] / (1.0 - light[1, 0, 3])
        print(f"depth at lattice centre {depths[2]}, jittered depth {depths[3]}, last dusty point {last:.4f}")
        check(abs(last - depths[3]) < .001, "a continuous surface ends the jittered ray at its own depth")


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
    bind(program, 0, texture(*rgba, 2 * low, low, np.tile(current, (1, 2, 1))))
    bounds = history.copy()
    bounds[:, 2, :3] = (1 - far) * np.array([(here + far_depth) / 2, far_depth, far_depth])
    bind(program, 1, texture(*rgba, 2 * low, low, np.concatenate((history, bounds), axis=1)))
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


def pane_order(composite_program):
    clear = make_scene(200, 100)
    glass = texture(*rgba, size, size, [(0.4, 0.4, 0.4, 0.5)] * (size * size))
    for first, last, expected in ((20, 80, .2), (120, 180, .5), (50, 150, .3)):
        red = composite(composite_program, clear, slab(first, last, .25), glass)[6, 6, 0]
        print(f"dust at {first}–{last}, glass at 100: red {red:.5f}, expected {expected:.2f}")
        check(abs(red - expected) < .001, "wall and pane in depth order")


def mixed_depth(march_program, program):
    g.glUseProgram(march_program)
    g.glUniform4f(loc(march_program, "DwEye"), 0, 9, 600, 10)
    g.glUniform4f(loc(march_program, "PallYoung"), 0, 1, 0, 800)
    g.glUniform4f(loc(march_program, "DwRing"), 450, 0, 100, 800)
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
    slot[2, 2], slot[3, 2] = .25, .75  # soil and haze rows
    g.glUniformMatrix4fv(loc(program, "PallSlot0"), 1, True, slot)
    g.glUniformMatrix4fv(loc(program, "DwViewProj"), 1, True, projection)
    g.glUniform3f(loc(program, "PallMin"), -100, -100, -100)
    g.glUniform3f(loc(program, "PallMax"), 100, 100, 100)
    g.glUniform4f(loc(program, "PallAmbient"), 0, 0, 0, 1)
    g.glUniform1f(loc(program, "DwDiv"), 4)
    g.glUniform1i(loc(program, "PallOpen"), 13)
    bind(program, 0, make_scene(100))
    g.glUniform1i(loc(program, "Scene"), 0)
    bind(program, 5, texture(*rgba, 1, 2, [(.02, 10, 0, 0), (0, 10, 0, 0)]))
    bind(program, 10, texture(*rgba, low * 2, low, np.concatenate(slab(1, 15, .25), axis=1)))
    for point, visible in [((0, 0, -20), True), ((1, 0, -20), False), ((0, 0, 20), False)]:
        g.glUniform3f(loc(program, "Endpoint"), *point)
        values = []
        for reach in (0, 800):
            g.glUniform4f(loc(program, "PallYoung"), 0, 1, 0, reach)
            quad(program)
            values.append(read(1, 1)[0, 0, 0])
        field, active = values
        check(.5 < field < .8 and abs(active - (.25 if visible else field)) < .001,
              f"light/quad at {point}: field T={field:.4f}, active T={active:.4f}")


def ownership():
    # Exercise the actual head and field functions across both handoffs and the ball footprint.
    program = fragment_program("""#version 150
#moj_import <incoming:pall.glsl>
uniform vec4 Ring;
uniform float Radius;
uniform sampler2D Ground;
out vec4 fragColor;
void main() {
    mat4 S = mat4(0.0), V = mat4(0.0);
    V[0] = vec4(Radius + 10.0, 20.0, 10.0, 1.0);
    V[2].z = 1.0;
    V[3] = vec4(1.0);
    vec3 p = vec3(Radius, 1.0, 0.0);
    WallAt head = pallWallAt(Ground, S, V, vec4(0.0), vec4(0.0), p, 0.0, Ring);
    PallAt f;
    f.sigma = vec3(1.0, 0.375, 0.125);
    f.slab = vec3(1.0);
    f.column = vec3(1.0);
    pallHandoff(f, Ring, S, p);
    fragColor = vec4(wallShare(Ring, Radius), head.P.w, f.sigma.x, f.sigma.y + f.sigma.z);
}
""")
    target(1, 1, [rgba])
    g.glUseProgram(program)
    g.glUniform4f(loc(program, "Ring"), 100, 200, 0, 2435.89)
    edge = 2435.89
    cases = [(50, 0), (150, .5), (200, 1), (edge * (1 - .5 / 127), .5), (edge, 0), (2507.38, 0)]
    for radius, expected in cases:
        g.glUniform1f(loc(program, "Radius"), radius)
        quad(program)
        wall, head, soil, other = read(1, 1)[0, 0]
        check(np.allclose([wall, head, soil, other], [expected, 1 - expected, 1 - expected, .5], atol=2e-5),
              f"radius {radius:.2f}: wall {wall:.5f}, head {head:.5f}, soil {soil:.5f}, haze and mist {other:.3f}")
    g.glUniform4f(loc(program, "Ring"), 0, 1, 200, edge)
    for radius, expected in ((190, 0), (200, 0), (220, .5), (240, 1)):
        g.glUniform1f(loc(program, "Radius"), radius)
        quad(program)
        check(abs(read(1, 1)[0, 0, 0] - expected) < 1e-5, f"fireball footprint at {radius}")


def foreground_volume(march_program, program):
    g.glUseProgram(march_program)
    g.glUniform4f(loc(march_program, "DwEye"), 0, 9, 600, 10)
    g.glUniform4f(loc(march_program, "PallYoung"), 0, 1, 0, 800)
    g.glUniform4f(loc(march_program, "DwRing"), 450, 0, 100, 800)
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


def terrain_occluder(march_program):
    # Chunks end at 256, the relief map has a ridge ~600 blocks away,
    # and the only dust is on the far side in the valley behind it.
    g.glUseProgram(march_program)
    g.glUniform4f(loc(march_program, "DwEye"), 0, 12, 0, 10)
    g.glUniform4f(loc(march_program, "PallYoung"), 0, 1, 0, 1400)
    g.glUniform4f(loc(march_program, "DwRing"), 1400, 900, 100, 1400)
    g.glUniform2f(loc(march_program, "DwRelief"), 0, 32)
    slot = np.zeros((4, 4), dtype=np.float32)
    slot[:, 0] = (0, 0, -12, 1400)
    slot[3, 1] = 1
    g.glUniformMatrix4fv(loc(march_program, "PallSlot0"), 1, True, slot)
    g.glUniform4f(loc(march_program, "PallGround0"), 1600, 0, 32, 200)
    relief = [(32 if j == 4 else 0, 0, 0, 0) for j in range(16)]
    bind(march_program, 3, texture(*rgba, 1, 16, relief))
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
    absent = march(march_program, make_scene(1e9), 2)[0][1, 1, 3]
    drawn = march(march_program, make_scene(575), 2)[0][1, 1, 3]
    # Reset inputs for the other cases.
    g.glUniform4f(loc(march_program, "PallGround0"), 0, 0, 0, 0)
    g.glUniform2f(loc(march_program, "DwRelief"), 0, 0)
    print(f"ridge beyond draw distance: T={absent:.5f}, opaque ridge control T={drawn:.5f}")
    check(abs(absent - drawn) < 0.01 and drawn > 0.99, "coarse relief occludes dust when scene depth is sky")


def weather_haze(march_program, composite_program):
    g.glUseProgram(march_program)
    g.glUniform4f(loc(march_program, "DwEye"), 0, 9, 0, 10)
    g.glUniform4f(loc(march_program, "PallYoung"), 0, 1, 0, 3500)
    g.glUniform4f(loc(march_program, "DwRing"), 3500, 2500, 100, 3500)
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
    slot = np.zeros((4, 4), dtype=np.float32)
    slot[:, 0] = (0, -eye_z, -eye_y, 1024)
    slot[3, 1] = 1
    hi = max(heights.values(), default=0)
    g.glUniformMatrix4fv(loc(program, "PallSlot0"), 1, True, slot)
    g.glUniform4f(loc(program, "PallGround0"), 1024, 0, hi, 16)
    g.glUniform2f(loc(program, "DwRelief"), 0, hi)
    rows = [(heights.get(-1016 + 16 * j, 0), 0, 0, 0) for j in range(128)]
    bind(program, 3, texture(*rgba, 1, 128, rows))
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)


def relief_cases():
    program = march_setup()
    g.glUniform4f(loc(program, "DwEye"), 0, 9, 0, 10)
    bind_relief(program, 0, 9, {8: 32, -8: 32})
    # The near coarse cells describe nearby roofs. Scene depth describes the actual ray through a street/opening.
    coarse = march(program, make_scene(180), 2)[0][1, 1, 3]
    bind_relief(program, 0, 9, {})
    open_street = march(program, make_scene(180), 2)[0][1, 1, 3]
    print(f"drawn opening along a street, depth 180: coarse nearby roofs T={coarse:.5f}, open-ray T={open_street:.5f}")
    check(abs(coarse - open_street) < 0.01, "coarse map cannot stop a ray inside the drawn world")

    # Eye outside the ring. The ridge is past the drawn chunks but before the first possible dust.
    g.glUniform4f(loc(program, "DwEye"), 0, 9, 1000, 10)
    g.glUniform4f(loc(program, "PallYoung"), 0, 1, 0, 800)
    g.glUniform4f(loc(program, "DwRing"), 450, 0, 100, 800)
    bind_relief(program, 1000, 9, {584: 32, 600: 32, 616: 32})
    sky = march(program, make_scene(1e9), 2)[0][1, 1, 3]
    depth = march(program, make_scene(380), 2)[0][1, 1, 3]
    print(f"eye outside ring, ridge at ~400, first dust after 548: sky depth T={sky:.5f}, drawn ridge T={depth:.5f}")
    check(abs(sky - depth) < 0.01, "relief in front of the dust span still hides it")


def bright_air():
    mp = march_setup()
    comp = link("fx_pall_composite")
    g.glUseProgram(mp)
    g.glUniform4f(loc(mp, "DwEye"), 0, 9, 0, 10)
    g.glUniform4f(loc(mp, "PallYoung"), 0, 1, 0, 3500)
    g.glUniform4f(loc(mp, "DwRing"), 3500, 2500, 100, 3500)
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


def main():
    context()
    composite_program = link("fx_pall_composite")
    cases = {
        "ownership": ownership,
        "offscreen": offscreen_dust,
        "post": lambda: mixed_depth(march_setup(), composite_program),
        "volume": lambda: foreground_volume(march_setup(), composite_program),
        "jitter": lambda: jitter_occluder(march_setup(), composite_program),
        "history": history_taps,
        "pane": lambda: pane_order(composite_program),
        "terrain": lambda: terrain_occluder(march_setup()),
        "weather": lambda: weather_haze(march_setup(), composite_program),
        "history2": history_cases,
        "relief": relief_cases,
        "fog": bright_air,
    }
    for name in names or cases:
        cases[name]()
    return bool(namespace["FAILED"])


if __name__ == "__main__":
    sys.exit(main())
