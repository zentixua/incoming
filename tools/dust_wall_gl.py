# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""The young nuclear dust wall's shipped shaders on Mesa llvmpipe (tools/see_through_gl.py's harness), no game or GPU:

    uv run tools/dust_wall_gl.py

Links fx_dustwall, fx_dustwall_resolve and fx_dustwall_pack and checks (exit 1 on a failure): the composite keeps a
thin foreground and the first-person hand its low-resolution taps missed, and lays dust behind a see-through pane through
it, and lays no dust from behind a foreground surface; the resolve drops history at a new occluder, behind a ceiling that
moved in, with no history, and reads the previous frame on the march lattice when the frame width is not a multiple of the
divisor (its last column too); the march stops at the world behind the pixel, a straight-down ray sees the layer as a
slightly tilted one does, the layer lies on the local ground (a valley), a ray over rising ground does not skip it, no
dust lies inside the fireball's ground footprint, seen from above the settled haze draws the law's whole column (over soil
and hard ground, held at the march's exit depth) while a camera inside sees its sight on land and at a shore, and the head
does not turn around ground zero nor ride out with the front,
and the wall's edges along the front do not repeat. Optional arguments: a Java 21
executable and the Veil parser classpath to link the programs after Veil's parse and print as well (VeilPrintProbe.java
beside this script is not shipped; ShaderSourceTest covers Veil in the build)."""
import json
from pathlib import Path
import subprocess
import sys
import numpy as np

harness = Path(__file__).resolve().with_name("see_through_gl.py")
gl = {"__file__": str(harness)}
exec(compile(harness.read_text().split("\ncontext()\n", 1)[0], str(harness), "exec"), gl)
g = gl["g"]
context, link, target, texture, bind, quad, read, loc, check = (
    gl[n] for n in ("context", "link", "target", "texture", "bind", "quad", "read", "loc", "check")
)


AGES = 32


SIGHT_ROW, TABLE_ROWS = 2 + AGES, 2 * AGES + 1


def lifetime(p, nodes, end=1e4, seen=None):
    """The whole-lifetime table (PallField.lifetime: row 0 arrival and paved, rows 1.. top and depth per log age, slopes 0,
    the inside sight's rows from SIGHT_ROW: its depth, the law's unless seen) from per-node (arrival, layer top, law depth,
    paved), the same at every age; binds it on unit 2 with DwLife."""
    data = np.zeros((TABLE_ROWS, len(nodes), 4), np.float32)
    for i, (arrival, top, depth, paved) in enumerate(nodes):
        data[0, i] = (arrival, paved, 0, 0)
        data[1:1 + AGES, i] = (top, depth, 0, 0)
        sight = depth if seen is None else seen
        data[SIGHT_ROW:, i] = (sight, 0, sight, 0)
    bind(p, 2, texture(g.GL_RGBA32F, g.GL_RGBA, len(nodes), TABLE_ROWS, data.reshape(-1, 4)))
    g.glUniform4f(loc(p, "DwLife"), end, 0.05, 0, 0)


def scene(w, h, depths):
    return texture(g.GL_RG32F, g.GL_RG, w, h, [(d, d) for d in depths])


def resolve(p, w, h, current, past, depths, history=1, before=None, previous_div=4, read_span=False):
    size = ((w + 3) // 4, (h + 3) // 4)
    past_size = ((w + previous_div - 1) // previous_div, (h + previous_div - 1) // previous_div)
    target(3 * size[0], size[1], [(g.GL_RGBA32F, g.GL_RGBA)])
    g.glUseProgram(p)

    def atlas(shape, values):
        # the march's three thirds on one lattice (DustWallPass): light, depth span, interval split (one interval: A is the
        # whole span, its exit the span's end moment and its opacity the ray's, as dust_wall_contract_gl.split_of)
        tile = np.asarray(values).reshape(shape[1], shape[0])
        light = np.zeros((*tile.shape, 4), np.float32)
        light[:, :, 3] = tile
        span = light.copy()
        span[:, :, :3] = (1 - tile[:, :, None]) * np.array([2, 6, 4])
        split = np.zeros_like(light)
        split[:, :, 0] = split[:, :, 1] = span[:, :, 1]
        split[:, :, 2] = split[:, :, 3] = 1 - tile
        return texture(g.GL_RGBA16F, g.GL_RGBA, 3 * shape[0], shape[1], np.concatenate((light, span, split), axis=1))

    bind(p, 0, atlas(size, current))
    bind(p, 1, atlas(past_size, past))
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
    bind(p, 2, scene(w, h, depths))
    proj = np.array([[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, -1.02, -0.2], [0, 0, -1, 0]], dtype=np.float32)
    g.glUniformMatrix4fv(loc(p, "PrevViewProj"), 1, True, proj)
    g.glUniformMatrix4fv(loc(p, "InvViewProj"), 1, True, np.linalg.inv(proj).astype(np.float32))
    g.glUniform3f(loc(p, "PallForward"), 0, 0, -1)
    g.glUniform1f(loc(p, "DwDiv"), 4)
    g.glUniform1f(loc(p, "DwPrevDiv"), previous_div)
    g.glUniform4f(loc(p, "DwReproject"), 0, 0, 0, history)
    g.glUniform2f(loc(p, "DwHistory"), 0.1, 10)
    # the previous frame's world distances (SceneDepth.previous); none given: the same as now
    bind(p, 3, scene(w, h, before if before is not None else depths))
    g.glUniform1f(loc(p, "DwPrevScene"), 1)
    quad(p)
    if read_span:
        return read(3 * size[0], size[1])[:, size[0]:2 * size[0]]
    return read(*size)[:, :, 3]


def resolve_cases(p):
    # Newly occluded low-res centre, surrounded by background at unchanged depth.
    depths = [100.0] * 144
    depths[6 * 12 + 6] = 2.0
    cur = [0.25] * 9
    cur[4] = 1.0
    result = resolve(p, 12, 12, cur, [0.25] * 9, depths)
    check(result[1, 1] > 0.999, f"resolve after a new foreground occluder: T={result[1, 1]:.4f}, expected 1")
    first = resolve(p, 12, 12, cur, [0.25] * 9, depths, 0)
    check(first[1, 1] > 0.999, f"resolve with history invalid: T={first[1, 1]:.4f}, expected 1")
    # Fixed view. Full-width 9 means centres at x=2, 6, 8.5 for a 3-column low target.
    result = resolve(p, 9, 4, [0, 0.5, 1], [0, 0.5, 1], [100.0] * 36)
    check(abs(result[0, 1] - 0.5) < 1e-5, f"stationary 9-pixel view reprojection: T={result[0, 1]:.4f}, expected 0.5")
    check(abs(result[0, 2] - 1) < 1e-5, f"stationary 9-pixel view, last column (sample at 8.5): T={result[0, 2]:.4f}, expected 1")
    # 13 full pixels: three old cells at divisor 5, four new at divisor 4. At x=6 the old
    # centres at 2.5 and 7.5 contribute 30/70. The depth moments live three columns after light.
    for span in (False, True):
        result = resolve(p, 13, 4, [0, 1, 0, 1], [0, 1, 0], [100.0] * 52, previous_div=5, read_span=span)
        expected = (.54, 1.62, 1.08, .73) if span else .73
        check(np.allclose(result[0, 1], expected, atol=1e-4),
              f"divisor change keeps {'depth moments' if span else 'light'}: {result[0, 1]}, expected {expected}")
    # a ceiling moved in front of all nine samples (now 20 blocks, before 100): no dust from behind it
    cur = [0.25] * 9
    cur[4] = 1.0
    result = resolve(p, 12, 12, cur, [0.25] * 9, [20.0] * 144, 1, [100.0] * 144)
    check(result[1, 1] > 0.999, f"history behind a new ceiling: T={result[1, 1]:.4f}, expected 1")


def march_cases():
    core, expand = gl["CORE"], gl["expand"]
    src = expand("""#version 150
uniform float Floor;
uniform vec2 Shelf;
// the source ground's water share and hard share (the material tiles' stand-ins)
uniform float Water;
uniform float Hard;
// the ground: Floor, or Shelf.y blocks from Shelf.x blocks ahead of the eye on x (a rise)
uniform vec4 DwEye; uniform vec4 DwRing; uniform vec4 DwLife; uniform vec4 DwBall; uniform vec3 DwBallLight; uniform float DwCut;
uniform vec3 DwFire; uniform vec2 DwRelief; uniform vec4 DwSurge;
float dwFloorEye(vec2 xz) { return Shelf.y > 0.0 && xz.x > Shelf.x ? Shelf.y : Floor; }
float dwOpenEye(vec3 p) { return 1.0; }
vec2 dwSoilAt(vec2 xz) { return vec2(Hard, 0.0); }
float dwAirT(float t, float dy) { return 1.0; }
vec3 dwAirLight(vec3 rd) { return vec3(0.0); }
#moj_import <incoming:dustwall.glsl>
uniform vec3 Dir;
uniform float End;
// the camera's share of the inside sight (the pass loads it per source: dwLoad)
uniform float Sight;
// the sample offset along the ray (the pass moves it every frame; 0.8 unless set)
uniform float Jitter;
out vec4 fragColor;
void main() {
    DwSight = Sight;
    vec4 wall = dwMarch(normalize(Dir), End, Jitter, 0.001, 128);
    fragColor = vec4(wall.rgb, wall.a);
}
""")
    stub = "vec2 soil = dwSoilAt(xz);\n    return vec3(0.0, soil.y, soil.x);"
    assert stub in src, "the harness's material stub moved"
    src = src.replace(stub, "vec2 soil = dwSoilAt(xz);\n    return vec3(Water, soil.y, soil.x);")
    p = gl["compileProgram"](gl["compileShader"]((core / "fx_dustwall.vsh").read_text(), g.GL_VERTEX_SHADER),
                             gl["compileShader"](src, g.GL_FRAGMENT_SHADER), validate=False)
    target(1, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
    g.glUseProgram(p)
    lifetime(p, [(0, 20, 10.0, 0)] * 128)
    g.glActiveTexture(g.GL_TEXTURE0 + 12)
    noise = g.glGenTextures(1)
    g.glBindTexture(g.GL_TEXTURE_3D, noise)
    g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
    g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R32F, 1, 1, 1, 0, g.GL_RED, g.GL_FLOAT, np.array([0.5], dtype=np.float32))
    g.glUniform1i(loc(p, "DwNoise"), 12)
    g.glUniform1f(loc(p, "Jitter"), 0.8)
    g.glUniform4f(loc(p, "PallYoung"), 0, 1, 0, 300)
    g.glUniform4f(loc(p, "DwRing"), 300, 0, 65, 300)
    g.glUniform4f(loc(p, "DwBall"), 0, 10, 0, 1)
    g.glUniform3f(loc(p, "DwSunDir"), 0, 1, 0)
    g.glUniform3f(loc(p, "DwSkyLight"), 1, 1, 1)
    g.glUniform3f(loc(p, "DwBulk"), 1, 1, 1)

    def march(y, direction, stop, floor=0.0):
        g.glUniform1f(loc(p, "Floor"), floor)
        g.glUniform2f(loc(p, "DwRelief"), floor, floor)
        g.glUniform4f(loc(p, "DwEye"), 100, y, 0, 10)
        g.glUniform3f(loc(p, "Dir"), *direction)
        g.glUniform1f(loc(p, "End"), stop)
        quad(p)
        return read(1, 1)[0, 0]

    r = march(1, (1, 0, 0), 0.25)
    check(r[3] > 0.9, f"opaque world at 0.25 blocks: T={r[3]:.4f}, expected the dust of 0.25 blocks at most")
    r = march(1, (1, 0, 0), 0)
    check(abs(r[3] - 1) < 1e-5, f"zero ray length: T={r[3]:.4f}, expected 1")
    vertical = march(30, (0, -1, 0), 30)
    tilted = march(30, (0.0001, -1, 0), 30)
    check(abs(vertical[3] - tilted[3]) < 0.01,
          f"downward through a 20-block layer: exact vertical T={vertical[3]:.4f}, slight tilt T={tilted[3]:.4f}")
    # Both eyes are 2 blocks above their local ground. The second ground is
    # 12 blocks below the epicentre; the horizontal ray does not hit terrain.
    plain = march(2, (1, 0, 0), 30)
    valley = march(-10, (1, 0, 0), 30, -12.0)
    check(abs(plain[3] - valley[3]) < 0.01,
          f"2 blocks above local ground: plain T={plain[3]:.4f}, 12-block valley T={valley[3]:.4f}")

    # a shallow ray from 78 blocks over a valley floor reaches a 64-block shelf 32 blocks ahead, 12 blocks over it (inside
    # the 20-block layer): the march over the valley must not skip it (the same ray started at the shelf)
    d = np.array([1, -0.05, 0]) / np.sqrt(1.0025)
    g.glUniform2f(loc(p, "Shelf"), 32, 64)
    g.glUniform1f(loc(p, "Floor"), 0)
    g.glUniform2f(loc(p, "DwRelief"), 0, 64)
    g.glUniform4f(loc(p, "DwEye"), 100, 78, 0, 10)
    g.glUniform3f(loc(p, "Dir"), *d)
    g.glUniform1f(loc(p, "End"), 52)
    quad(p)
    full = read(1, 1)[0, 0, 3]
    t32 = 32 / d[0]
    g.glUniform2f(loc(p, "Shelf"), 0, 64)
    g.glUniform4f(loc(p, "DwEye"), 132, 78 + t32 * d[1], 0, 10)
    g.glUniform1f(loc(p, "End"), 52 - t32)
    quad(p)
    shelf = read(1, 1)[0, 0, 3]
    check(shelf < 0.9 and abs(full - shelf) < 0.1, f"shallow ray over a rise: full path T={full:.4f}, from the shelf T={shelf:.4f}")
    g.glUniform2f(loc(p, "Shelf"), 0, 0)
    g.glUniform2f(loc(p, "DwRelief"), 0, 0)
    # no dust where the fireball touches the ground, between table nodes too
    g.glUniform1f(loc(p, "DwCut"), 250)
    cut = march(2, (1, 0, 0), 20)
    g.glUniform1f(loc(p, "DwCut"), 0)
    check(abs(cut[3] - 1) < 1e-5, f"ray inside the fireball's ground footprint: T={cut[3]:.4f}, expected 1")

    # the camera's inside sight (PallField.sighted, the table's sight rows): straight down through old dust (2 min, the haze
    # level), the drawn depth moves from the law's (3, held under the march's exit depth: PallField.hazeDepth) to the transfer
    # of the law moved toward the inside sight's (1.2) by the camera's share, by that share (PallField.column, dwHaze)
    def sighted(w):
        g.glUniform1f(loc(p, "Sight"), w)
        g.glUniform1f(loc(p, "Floor"), 0)
        g.glUniform2f(loc(p, "DwRelief"), 0, 0)
        g.glUniform4f(loc(p, "DwEye"), 100, 40, 0, 120)
        g.glUniform3f(loc(p, "Dir"), 0, -1, 0)
        g.glUniform1f(loc(p, "End"), 40)
        quad(p)
        return -np.log(read(1, 1)[0, 0, 3])

    def haze(tau):
        # PallField.hazeDepth: the law's depth held under the march's exit depth, ln 100
        return tau / (1 + (tau / np.log(100)) ** 8) ** 0.125

    def transfer(tau):
        return tau if tau <= 1 else 1 + 0.073 * (tau - 1) + (1 - 0.073) * 0.5 * (1 - np.exp(-(tau - 1) / 0.5))
    lifetime(p, [(0, 20, 3.0, 0)] * 128, seen=1.2)
    law = sighted(0)
    for w in (0.5, 1.0):
        want = (haze(3) + (transfer(3 + (1.2 - 3) * w) - haze(3)) * w) / haze(3)
        got = sighted(w) / law
        check(abs(got - want) < 0.02, f"inside sight {w}: drawn depth {got:.4f} of the law's, expected {want:.4f}")
    g.glUniform1f(loc(p, "Sight"), 0)

    # from above the layer (400 m up, 70° down, 2 km from a 15 kt surface burst, 70 s and two minutes):
    # the haze level's drawn depth is the law's along the ray (τ/sin of the pitch; PallField.hazeDepth holds it within 1 % to
    # τ 3.3), as the history averages the march over its sample offsets (eight here; one offset alone errs by up to 5 %), and a
    # thick column (law τ 8, drawn 4.6: the march's exit depth) is opaque. The transfer alone draws the law's 2 as 1.47 (74 %
    # of it) and passes 12 % of the light through the law's 8 at 70°
    def above(tau, up, pitch):
        lifetime(p, [(0, 20, tau, 0)] * 128)
        down = np.radians(pitch)
        g.glUniform1f(loc(p, "Floor"), 0)
        g.glUniform2f(loc(p, "DwRelief"), 0, 0)
        g.glUniform4f(loc(p, "DwEye"), 250, up, 0, 120)
        g.glUniform3f(loc(p, "Dir"), -np.cos(down), -np.sin(down), 0)
        g.glUniform1f(loc(p, "End"), up / np.sin(down))
        ts = []
        for j in range(8):
            g.glUniform1f(loc(p, "Jitter"), (j + 0.5) / 8)
            quad(p)
            ts.append(read(1, 1)[0, 0, 3])
        g.glUniform1f(loc(p, "Jitter"), 0.8)
        return float(np.mean(ts)), tau / np.sin(down)
    for up, pitch in ((400, 89.9), (400, 70), (400, 45), (170, 60)):
        t, law = above(2.0, up, pitch)
        check(abs(-np.log(t) / law - 1) < 0.03,
              f"from {up} blocks up, {pitch}° down, law τ 2: drawn depth {-np.log(t):.4f} along the ray, the law's {law:.4f}")
        t, law = above(8.0, up, pitch)
        check(t < 0.015, f"from {up} blocks up, {pitch}° down, law τ 8: T={t:.4f}, expected opaque (the law's {np.exp(-law):.1e})")
    # a camera inside old dust (share 1) straight down through a shore point (water share w, no spray: the table's spray row
    # is 0): the column holds the soil's share of the inside sight, never denser than over land; hard ground (paved share
    # 0.3 of the soil's dust) seen from above, outside: the hold of the law's column there, not 0.3 of the soil's hold
    # (review probe shore_inside_gl.py)
    def straight(law, seen, water, sight, hard=0.0, paved=0.0):
        lifetime(p, [(0, 20, law, paved)] * 128, seen=seen)
        g.glUniform1f(loc(p, "Hard"), hard)
        g.glUniform1f(loc(p, "Water"), water)
        g.glUniform1f(loc(p, "Sight"), sight)
        g.glUniform1f(loc(p, "Floor"), 0)
        g.glUniform2f(loc(p, "DwRelief"), 0, 0)
        g.glUniform4f(loc(p, "DwEye"), 100, 40, 0, 120)
        g.glUniform3f(loc(p, "Dir"), 0, -1, 0)
        g.glUniform1f(loc(p, "End"), 40)
        g.glUniform1f(loc(p, "Jitter"), 0.5)
        quad(p)
        return float(-np.log(max(read(1, 1)[0, 0, 3], 1e-30)))
    for law in (3.0, 10.0):
        land = straight(law, 1.2, 0.0, 1.0)
        for w in (0.25, 0.5, 0.75):
            shore = straight(law, 1.2, w, 1.0)
            check(shore <= land * 1.02, f"inside the dust, law τ {law}, a shore point (water {w}) draws {shore:.3f}, at most land's {land:.3f}")
    for law in (10.0, 15.0):
        hard = straight(law, law, 0.0, 0.0, hard=1.0, paved=0.3)
        check(abs(hard / haze(0.3 * law) - 1) < 0.1,
              f"hard ground from above, paved 0.3, law τ {law}: drawn {hard:.3f}, the hold of the law there {haze(0.3 * law):.3f}")
    g.glUniform1f(loc(p, "Hard"), 0)
    g.glUniform1f(loc(p, "Water"), 0)
    g.glUniform1f(loc(p, "Sight"), 0)
    g.glUniform1f(loc(p, "Jitter"), 0.8)
    lifetime(p, [(0, 20, 10.0, 0)] * 128)


def java_bytes(seed, n):
    """java.util.Random(seed).nextBytes of n bytes (DustNoise.values: the march's lattice)."""
    mask = (1 << 48) - 1
    s = (seed ^ 0x5DEECE66D) & mask
    out = bytearray(n)
    for i in range(0, n, 4):
        s = (s * 0x5DEECE66D + 0xB) & mask
        r = s >> 16
        for k in range(min(4, n - i)):
            out[i + k] = (r >> (8 * k)) & 0xFF
    return bytes(out)


def noise_case():
    """The haze level's swell field the camera's inside share reads (DustNoise.swell) is the shader's dwSwellField on the
    uploaded lattice: the points and values of DustNoiseTest, within the texture filter's 8-bit weights (up to 2.4e-3 here)."""
    core, expand = gl["CORE"], gl["expand"]
    src = expand("""#version 150
uniform vec4 DwEye; uniform vec4 DwRing; uniform vec4 DwLife; uniform vec4 DwBall; uniform vec3 DwBallLight; uniform float DwCut;
uniform vec3 DwFire; uniform vec2 DwRelief; uniform vec4 DwSurge;
float dwFloorEye(vec2 xz) { return 0.0; }
float dwOpenEye(vec3 p) { return 1.0; }
vec2 dwSoilAt(vec2 xz) { return vec2(0.0); }
float dwAirT(float t, float dy) { return 1.0; }
vec3 dwAirLight(vec3 rd) { return vec3(0.0); }
#moj_import <incoming:dustwall.glsl>
uniform vec3 At;
out vec4 fragColor;
void main() {
    float L;
    float s = dwSwellField(At.xy, At.z, L);
    fragColor = vec4(s, L, 0.0, 1.0);
}
""")
    p = gl["compileProgram"](gl["compileShader"]((core / "fx_dustwall.vsh").read_text(), g.GL_VERTEX_SHADER),
                             gl["compileShader"](src, g.GL_FRAGMENT_SHADER), validate=False)
    target(1, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
    g.glUseProgram(p)
    side = 128
    values = java_bytes(0xD057, side ** 3)
    g.glActiveTexture(g.GL_TEXTURE0 + 12)
    noise = g.glGenTextures(1)
    g.glBindTexture(g.GL_TEXTURE_3D, noise)
    for k, v in ((g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR), (g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR), (g.GL_TEXTURE_WRAP_S, g.GL_REPEAT),
                 (g.GL_TEXTURE_WRAP_T, g.GL_REPEAT), (g.GL_TEXTURE_WRAP_R, g.GL_REPEAT)):
        g.glTexParameteri(g.GL_TEXTURE_3D, k, v)
    g.glPixelStorei(g.GL_UNPACK_ALIGNMENT, 1)
    g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R8, side, side, side, 0, g.GL_RED, g.GL_UNSIGNED_BYTE, values)
    g.glUniform1i(loc(p, "DwNoise"), 12)
    # (x, z, age; swell, L) as DustNoiseTest.POINTS
    for x, z, a, swell, big in ((1564, 0, 300, 0.177846, 0.647493), (1500, 120, 60, 0.247608, 0.750244),
                                (-800, 2300, 600, -0.156478, 0.375046), (3100, -410, 30, 0.166536, 0.635245),
                                (220, -75, 1200, -0.048085, 0.465325), (0, 0, 5, 0.042303, 0.530606),
                                (777, 777, 120, -0.411438, 0.491826), (-2500, -1300, 3000, -0.059747, 0.456688)):
        g.glUniform3f(loc(p, "At"), x, z, a)
        quad(p)
        r = read(1, 1)[0, 0]
        check(abs(r[0] - swell) < 4e-3 and abs(r[1] - big) < 4e-3,
              f"swell field at ({x}, {z}) {a} s: shader {r[0]:.6f} L {r[1]:.6f}, Java {swell:.6f} L {big:.6f}")


def turning_case():
    """No turning around ground zero: the head's dense extinction along an arc on the ground 80 blocks behind the front
    (bearing -110..-70 deg, 40 blocks up), 0.02 s apart while the front runs out at 470 m/s, matches itself with no shift along the
    arc. Features placed by bearing x the front's current radius slid along it at bearing x the front's speed (~15 m here)."""
    core, expand = gl["CORE"], gl["expand"]
    src = expand("""#version 150
uniform vec4 DwEye; uniform vec4 DwRing; uniform vec4 DwLife; uniform vec4 DwBall; uniform vec3 DwBallLight; uniform float DwCut;
uniform vec3 DwFire; uniform vec2 DwRelief; uniform vec4 DwSurge;
float dwFloorEye(vec2 xz) { return 0.0; }
float dwOpenEye(vec3 p) { return 1.0; }
vec2 dwSoilAt(vec2 xz) { return vec2(0.0); }
float dwAirT(float t, float dy) { return 1.0; }
vec3 dwAirLight(vec3 rd) { return vec3(0.0); }
#moj_import <incoming:dustwall.glsl>
uniform float Radius;
uniform float Radial;
out vec4 fragColor;
void main() {
    float f = gl_FragCoord.x / 2048.0;
    // an arc 80 blocks behind the front, or a radial line through the head 0-300 blocks behind it, 40 blocks up
    float b = Radial > 0.5 ? radians(-90.0) : radians(-110.0 + 40.0 * f), rr = Radial > 0.5 ? Radius - 300.0 * f : Radius;
    fragColor = vec4(dwDensity(vec3(rr * cos(b), 40.0, rr * sin(b)), false), 0.0, 0.0, 1.0);
}
""")
    p = gl["compileProgram"](gl["compileShader"]((core / "fx_dustwall.vsh").read_text(), g.GL_VERTEX_SHADER),
                             gl["compileShader"](src, g.GL_FRAGMENT_SHADER), validate=False)
    target(2048, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
    g.glUseProgram(p)
    reach, arrive = 9000.0, 7.63
    nodes = [(arrive * (i / 127 * reach / 4500.0) ** 1.25, 68.0, 100.0, 0.0) for i in range(128)]
    lifetime(p, nodes)
    g.glActiveTexture(g.GL_TEXTURE0 + 12)
    noise = g.glGenTextures(1)
    g.glBindTexture(g.GL_TEXTURE_3D, noise)
    for f in (g.GL_TEXTURE_MIN_FILTER, g.GL_TEXTURE_MAG_FILTER):
        g.glTexParameteri(g.GL_TEXTURE_3D, f, g.GL_LINEAR)
    for f in (g.GL_TEXTURE_WRAP_S, g.GL_TEXTURE_WRAP_T, g.GL_TEXTURE_WRAP_R):
        g.glTexParameteri(g.GL_TEXTURE_3D, f, g.GL_REPEAT)
    lattice = np.random.default_rng(7).random((128, 128, 128), dtype=np.float32)
    g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R32F, 128, 128, 128, 0, g.GL_RED, g.GL_FLOAT, lattice)
    g.glUniform1i(loc(p, "DwNoise"), 12)
    g.glUniform4f(loc(p, "DwBall"), 0, 1200, 0, 1700)
    arcs, lines = [], []
    for t in (arrive, arrive + 0.02):
        front = 4500 * (t / arrive) ** 0.8
        # the eye far off (the head's own texture is drawn away from the eye)
        g.glUniform4f(loc(p, "DwEye"), 0, 120, 4500, t)
        g.glUniform4f(loc(p, "PallYoung"), 0, 1, 0, reach)
        g.glUniform4f(loc(p, "DwRing"), front, 0, 400, reach)
        # a fixed arc on the ground, 80 blocks behind the front at the first instant (the dust is anchored where it was born)
        g.glUniform1f(loc(p, "Radius"), 4500 - 80)
        g.glUniform1f(loc(p, "Radial"), 0)
        quad(p)
        arcs.append(read(2048, 1)[0, :, 0].copy())
        # the radial line at a fixed place on the ground (the front at the first instant)
        g.glUniform1f(loc(p, "Radius"), 4500.0)
        g.glUniform1f(loc(p, "Radial"), 1)
        quad(p)
        lines.append(read(2048, 1)[0, :, 0].copy())
    a, b = (x - x.mean() for x in arcs)
    shifts = range(-40, 41)
    corr = [np.corrcoef(a[100:-100], np.roll(b, -k)[100:-100])[0, 1] for k in shifts]
    best = shifts[int(np.argmax(corr))]
    metres = best * np.radians(40.0) * (4500 - 80) / 2048
    print(f"turning: the head's arc moves {metres:+.1f} m along the front in 0.02 s (correlation {max(corr):.3f})")
    check(max(corr) > 0.4, f"the head's arc carries texture enough to measure turning (correlation {max(corr):.3f} > 0.4)")
    check(abs(metres) < 2.0, f"the head turns around ground zero: {metres:+.1f} m along the front in 0.02 s, expected ~0")
    # LOOK Trait 0: the head's dust stays where it was torn up (a small outward push), nothing rides out with the front
    a, b = (x - x.mean() for x in lines)
    corr = [np.corrcoef(a[100:-100], np.roll(b, -k)[100:-100])[0, 1] for k in shifts]
    out = -shifts[int(np.argmax(corr))] * 300.0 / 2048 / 0.02
    print(f"births: the head's dust 40 blocks up moves {out:+.0f} m/s outward (correlation {max(corr):.3f})")
    check(abs(out) < 60.0, f"the head's dust rides out with the front: {out:+.0f} m/s, expected tens of m/s at most")


def period_case():
    """No staircase along the wall: its lower edge (where the dust 3 blocks up begins behind the front: the birth line) and
    its top (the highest dust 100 blocks behind the front) along a 15 degree arc of the front at 4.95 km, seen from 400 m
    inside it (the seeds' variation fades to their mean 2-3 km from the eye, where it is under a pixel), vary (std over 1 m)
    and have no flat runs (under a quarter of the steps between neighbouring samples below 1 mm; one value per noise cell
    gives 0.96, the control below); the birth line's slope is near-normal (kurtosis under 6). The top's slope there is
    heavy-tailed without any flat run (where a higher billow lobe begins, the highest dusty point jumps), so its kurtosis is
    checked where the eye sees the far wall: a 60 degree arc seen from 9.4 km, where a lobe is under a sample. A smooth
    periodic profile is not caught here: the seeds' noise has no period."""
    core, expand = gl["CORE"], gl["expand"]
    src = expand("""#version 150
uniform vec4 DwEye; uniform vec4 DwRing; uniform vec4 DwLife; uniform vec4 DwBall; uniform vec3 DwBallLight; uniform float DwCut;
uniform vec3 DwFire; uniform vec2 DwRelief; uniform vec4 DwSurge;
float dwFloorEye(vec2 xz) { return 0.0; }
float dwOpenEye(vec3 p) { return 1.0; }
vec2 dwSoilAt(vec2 xz) { return vec2(0.0); }
float dwAirT(float t, float dy) { return 1.0; }
vec3 dwAirLight(vec3 rd) { return vec3(0.0); }
#moj_import <incoming:dustwall.glsl>
uniform float Front;
uniform float Upper;
uniform vec2 Arc;
out vec4 fragColor;
void main() {
    float b = radians(Arc.x + Arc.y * gl_FragCoord.x / 2048.0);
    vec2 d = vec2(cos(b), sin(b));
    float edge = 0.0;
    if (Upper > 0.5) {
        for (int i = 0; i < 240; i++) {
            float y = 240.0 - float(i);
            if (dwDensity(vec3(d.x * (Front - 100.0), y, d.y * (Front - 100.0)), false) > 2e-3) {
                // the crossing between this metre and the one above, by bisection (a whole-metre edge is itself a staircase)
                float lo = y, hi = y + 1.0;
                for (int j = 0; j < 12; j++) {
                    float m = 0.5 * (lo + hi);
                    if (dwDensity(vec3(d.x * (Front - 100.0), m, d.y * (Front - 100.0)), false) > 2e-3) lo = m; else hi = m;
                }
                edge = lo;
                break;
            }
        }
    } else {
        for (int i = 0; i < 300; i++) {
            float r = Front + 5.0 - float(i);
            if (dwDensity(vec3(d.x * r, 3.0, d.y * r), false) > 2e-3) {
                float lo = r, hi = r + 1.0;
                for (int j = 0; j < 12; j++) {
                    float m = 0.5 * (lo + hi);
                    if (dwDensity(vec3(d.x * m, 3.0, d.y * m), false) > 2e-3) lo = m; else hi = m;
                }
                edge = lo - Front;
                break;
            }
        }
    }
    fragColor = vec4(edge, 0.0, 0.0, 1.0);
}
""")
    p = gl["compileProgram"](gl["compileShader"]((core / "fx_dustwall.vsh").read_text(), g.GL_VERTEX_SHADER),
                             gl["compileShader"](src, g.GL_FRAGMENT_SHADER), validate=False)
    target(2048, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
    g.glUseProgram(p)
    reach, arrive = 9000.0, 7.63
    nodes = [(arrive * (i / 127 * reach / 4500.0) ** 1.25, 68.0, 100.0, 0.0) for i in range(128)]
    lifetime(p, nodes)
    g.glActiveTexture(g.GL_TEXTURE0 + 12)
    noise = g.glGenTextures(1)
    g.glBindTexture(g.GL_TEXTURE_3D, noise)
    for f in (g.GL_TEXTURE_MIN_FILTER, g.GL_TEXTURE_MAG_FILTER):
        g.glTexParameteri(g.GL_TEXTURE_3D, f, g.GL_LINEAR)
    for f in (g.GL_TEXTURE_WRAP_S, g.GL_TEXTURE_WRAP_T, g.GL_TEXTURE_WRAP_R):
        g.glTexParameteri(g.GL_TEXTURE_3D, f, g.GL_REPEAT)
    lattice = np.random.default_rng(7).random((128, 128, 128), dtype=np.float32)
    g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R32F, 128, 128, 128, 0, g.GL_RED, g.GL_FLOAT, lattice)
    g.glUniform1i(loc(p, "DwNoise"), 12)
    g.glUniform4f(loc(p, "DwBall"), 0, 1200, 0, 1700)
    t = arrive + 1.0
    front = 4500 * (t / arrive) ** 0.8
    g.glUniform4f(loc(p, "PallYoung"), 0, 1, 0, reach)
    g.glUniform4f(loc(p, "DwRing"), front, 0, 400, reach)
    g.glUniform1f(loc(p, "Front"), front)

    def edge(upper, near):
        # near: 15 degrees seen from 400 m inside the front; far: 60 degrees seen from 9.4 km (the eye across ground zero)
        g.glUniform4f(loc(p, "DwEye"), 0, 120, -(front - 400.0) if near else 4500.0, t)
        g.glUniform2f(loc(p, "Arc"), *((-97.5, 15.0) if near else (-120.0, 60.0)))
        g.glUniform1f(loc(p, "Upper"), upper)
        quad(p)
        return read(2048, 1)[0, :, 0].astype(np.float64)

    def kurtosis(e):
        # a staircase (one value per noise cell, flat inside, a step to the next) has a heavy-tailed slope: flat runs and
        # sudden steps; smooth multi-scale noise has a near-normal slope (kurtosis ~3)
        sl = np.diff(np.convolve(e, np.ones(3) / 3, "valid"))
        sl = sl - sl.mean()
        return float((sl ** 4).mean() / max((sl ** 2).mean() ** 2, 1e-12))

    def flat(e):
        return float((np.abs(np.diff(e)) < 1e-3).mean())

    # the control: one value per 60 m cell along the near arc, flat inside with a step to the next
    step = np.radians(15.0) * front / 2048
    control = np.sin(np.floor(np.arange(2048) * step / 60.0) * 12.9898) * 4.0
    check(flat(control) > 0.25 and kurtosis(control) > 6.0,
          f"the staircase control has flat runs (share {flat(control):.2f}) and a heavy-tailed slope ({kurtosis(control):.0f})")
    for upper, name in ((0, "lower edge"), (1, "top")):
        e = edge(upper, True)
        print(f"period: the wall's {name} (std {e.std():.1f} m) slope kurtosis {kurtosis(e):.1f}, flat share {flat(e):.3f} over the near arc")
        check(e.std() > 1.0, f"the wall's {name} varies along the front (std {e.std():.2f} m > 1)")
        check(flat(e) < 0.25, f"the wall's {name} has flat runs like a staircase (share {flat(e):.3f}), expected under 0.25")
        if not upper:
            check(kurtosis(e) < 6.0, f"the wall's {name} steps like a staircase (slope kurtosis {kurtosis(e):.1f}), expected smooth noise (< 6)")
    far = edge(1, False)
    print(f"period: the far wall's top (std {far.std():.1f} m) slope kurtosis {kurtosis(far):.1f} over the far arc")
    check(far.std() > 1.0 and kurtosis(far) < 6.0,
          f"the far wall's top varies (std {far.std():.2f} m > 1) without steps (slope kurtosis {kurtosis(far):.1f} < 6)")


def link_after_veil(name, java, classpath):
    core = gl["CORE"]
    setup = json.loads((core / f"{name}.json").read_text())
    printer = Path(__file__).resolve().with_name("VeilPrintProbe.java")
    shaders = []
    for stage, suffix, kind in (("vertex", ".vsh", g.GL_VERTEX_SHADER),
                                ("fragment", ".fsh", g.GL_FRAGMENT_SHADER)):
        shader = core / (setup[stage].split(":")[-1] + suffix)
        expanded = gl["expand"](shader.read_text())
        result = subprocess.run([java, "--class-path", classpath, str(printer)], input=expanded,
                                text=True, capture_output=True, check=True)
        shaders.append(gl["compileShader"](result.stdout, kind))
    return gl["compileProgram"](*shaders, validate=False)


context()
programs = {name: link("fx_dustwall" + name) for name in ("", "_resolve", "_pack")}
for name, p in programs.items():
    print(f"Linked fx_dustwall{name}, {g.glGetProgramiv(p, g.GL_ACTIVE_UNIFORMS)} active uniforms")
    if len(sys.argv) == 3:
        try:
            link_after_veil(f"fx_dustwall{name}", sys.argv[1], sys.argv[2])
        except subprocess.CalledProcessError as err:
            check(False, f"Veil round trip for fx_dustwall{name}: {err.stderr}")
        except Exception as err:
            check(False, f"Veil round trip for fx_dustwall{name}: {str(err)[:400]}")
        else:
            print(f"Linked fx_dustwall{name} after the Veil parser round trip")
resolve_cases(programs["_resolve"])
march_cases()
noise_case()
turning_case()
period_case()
# The full pass contract and its camera-motion and screen-edge regressions use llvmpipe.
failed = bool(gl["FAILED"])
for script in ("dust_wall_contract_gl.py", "dust_wall_horizon_probe.py", "dust_wall_lift_threshold_probe.py", "wall_layer_identity.py", "nuclear_volume_warmup_gl.py",
               "pall_single_gl.py", "dust_wall_split_probe.py"):
    run = subprocess.run([sys.executable, str(Path(__file__).with_name(script))])
    failed |= run.returncode != 0
# the one-tap light (MEDIUM, LOW) has no step where the brighter light changes between the fireball and the sun; it reads the
# law's table that DustLawDumpProbe writes
if (Path(__file__).resolve().parents[1] / "mod/build/uw-probe/law-1000kt.json").exists():
    failed |= subprocess.run([sys.executable, str(Path(__file__).with_name("uw_probe") / "onetap_flip.py")]).returncode != 0
else:
    print("FAIL    onetap_flip: no law table; run (cd mod && JAVA_TOOL_OPTIONS=-Duw.probe=true ./gradlew test "
          "--tests '*DustLawDumpProbe' --rerun) first (without the property the probe skips itself)")
    failed = True
sys.exit(failed)
