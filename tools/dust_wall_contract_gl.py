# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""The young dust wall's render contract on Mesa GL 3.2 core with the shipped passes (independent review of #724, round 3):

    uv run tools/dust_wall_contract_gl.py [checkout] [case ...]

Cases: jitter (the march's in-pixel offset keeps an opaque foreground pixel foreground), history (a history sample whose
previous depth is another place contributes nothing), terrain (the relief stops the march where the scene has no depth),
weather (distant dust fades into the airlight in a thunderstorm), history2 (one depth rule: a
history sample the composite would reject contributes nothing; a clear pixel stays clear), relief (the relief map stops no
ray inside the drawn world, and hides dust behind a ridge past it), fog (a hazed wall shows the fog's colour), pane (dust in front of a see-through surface: a known
limit, reported, not checked). Run by tools/dust_wall_gl.py."""
import inspect
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
    target(low, low, [rgba])
    g.glUseProgram(program)
    bind(program, 2, texture(*rgba, 128, 1, [(0, 20, 0.11, 0.3)] * 128))
    bind(program, 1, scene)
    g.glUniform2f(loc(program, "DwFrame"), frame, 128)
    quad(program)
    return read(low, low).copy()


def composite(program, scene, samples, glass=None):
    target(size, size, [rgba])
    g.glUseProgram(program)
    bind(program, 0, texture(*rgba, low, low, samples))
    bind(program, 1, scene)
    g.glUniform1f(loc(program, "DwDiv"), 4)
    g.glUniform1f(loc(program, "FxSee"), 0 if glass is None else 1)
    if glass is not None:
        g.glActiveTexture(g.GL_TEXTURE0 + 13)
        g.glBindTexture(g.GL_TEXTURE_2D, glass)
        g.glUniform1i(loc(program, "FxGlass"), 13)
        g.glClearColor(0.8, 0.8, 0.8, 1.0)
        g.glClear(g.GL_COLOR_BUFFER_BIT)
        g.glEnable(g.GL_BLEND)
        g.glBlendFuncSeparate(g.GL_ONE, g.GL_SRC_ALPHA, g.GL_ZERO, g.GL_ONE)
    quad(program)
    g.glDisable(g.GL_BLEND)
    return read(size, size).copy()


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


def history_occluder(far=0.10):
    program = link("fx_dustwall_resolve")
    target(low, low, [rgba])
    g.glUseProgram(program)
    current = np.zeros((low, low, 4), dtype=np.float32)
    current[:, :, 3] = 0.25
    current[1, 1, 3] = 1.0
    history = np.zeros_like(current)
    history[:, :, 3] = 1.0
    history[:, 2, 3] = far
    bind(program, 0, texture(*rgba, low, low, current))
    bind(program, 1, texture(*rgba, low, low, history))
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
    bind(program, 2, make_scene(20.0))
    bind(program, 3, make_scene(np.where(np.arange(size) < 8, 20.0, 100.0)))
    prev = projection.copy()
    prev[0] += projection[3] * (1.96 * 2 / size)
    g.glUniformMatrix4fv(loc(program, "PrevViewProj"), 1, True, prev)
    g.glUniformMatrix4fv(loc(program, "InvViewProj"), 1, True, np.linalg.inv(projection).astype(np.float32))
    g.glUniform3f(loc(program, "PallForward"), 0, 0, -1)
    g.glUniform1f(loc(program, "DwDiv"), 4)
    g.glUniform4f(loc(program, "DwReproject"), 0, 0, 0, 1)
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
    dark_dust = np.zeros((low, low, 4), dtype=np.float32)
    dark_dust[:, :, 3] = 0.25
    red = composite(composite_program, clear, dark_dust, glass)[6, 6, 0]
    print(f"dust completely in front of glass: red {red:.5f}, expected 0.2")
    print("  limit   dust in front of a see-through surface is laid as if behind it (fx_dustwall_composite: a known limit)")




def terrain_occluder(march_program):
    # Chunks end at 256, the relief map has a ridge ~600 blocks away,
    # and the only dust is on the far side in the valley behind it.
    g.glUseProgram(march_program)
    g.glUniform4f(loc(march_program, "DwEye"), 0, 12, 0, 10)
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
    absent = march(march_program, make_scene(1e9), 2)[1, 1, 3]
    drawn = march(march_program, make_scene(575), 2)[1, 1, 3]
    # Reset inputs for the other cases.
    g.glUniform4f(loc(march_program, "PallGround0"), 0, 0, 0, 0)
    g.glUniform2f(loc(march_program, "DwRelief"), 0, 0)
    print(f"ridge beyond draw distance: T={absent:.5f}, opaque ridge control T={drawn:.5f}")
    check(abs(absent - drawn) < 0.01 and drawn > 0.99, "coarse relief occludes dust when scene depth is sky")


def weather_haze(march_program, composite_program):
    g.glUseProgram(march_program)
    g.glUniform4f(loc(march_program, "DwEye"), 0, 9, 0, 10)
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
    source = inspect.getsource(history_occluder)
    source = source.replace("def history_occluder(far=0.10):", "def resolve_variant(far=0.10, far_depth=109, enabled=1):")
    source = source.replace("current[:, :, 3] = 0.25", "current[::2, ::2, 3] = 0.25\n    current[1, :, 3] = 1.0\n    current[::2, 1, 3] = 1.0")
    source = source.replace("make_scene(20.0)", "make_scene(100.0)").replace("20.0, 100.0", "100.0, far_depth")
    source = source.replace('"DwReproject"), 0, 0, 0, 1', '"DwReproject"), 0, 0, 0, enabled')
    adapted = globals().copy()
    exec(source, adapted)
    resolve = adapted["resolve_variant"]
    shipped = history_occluder(1.0)
    dusty = resolve()
    clear = resolve(far=1.0)
    incompatible = resolve(far_depth=120)
    current = resolve(enabled=0)
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
    coarse = march(program, make_scene(180), 2)[1, 1, 3]
    bind_relief(program, 0, 9, {})
    open_street = march(program, make_scene(180), 2)[1, 1, 3]
    print(f"drawn opening along a street, depth 180: coarse nearby roofs T={coarse:.5f}, open-ray T={open_street:.5f}")
    check(abs(coarse - open_street) < 0.01, "coarse map cannot stop a ray inside the drawn world")

    # Eye outside the ring. The ridge is past the drawn chunks but before the first possible dust.
    g.glUniform4f(loc(program, "DwEye"), 0, 9, 1000, 10)
    g.glUniform4f(loc(program, "DwRing"), 450, 0, 100, 800)
    bind_relief(program, 1000, 9, {584: 32, 600: 32, 616: 32})
    sky = march(program, make_scene(1e9), 2)[1, 1, 3]
    depth = march(program, make_scene(380), 2)[1, 1, 3]
    print(f"eye outside ring, ridge at ~400, first dust after 548: sky depth T={sky:.5f}, drawn ridge T={depth:.5f}")
    check(abs(sky - depth) < 0.01, "relief in front of the dust span still hides it")


def bright_air():
    mp = march_setup()
    comp = link("fx_dustwall_composite")
    g.glUseProgram(mp)
    g.glUniform4f(loc(mp, "DwEye"), 0, 9, 0, 10)
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
    composite_program = link("fx_dustwall_composite")
    cases = {
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
