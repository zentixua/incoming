# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""The young nuclear dust wall's shipped shaders on Mesa llvmpipe (tools/see_through_gl.py's harness), no game or GPU:

    uv run tools/dust_wall_gl.py

Links fx_dustwall, fx_dustwall_resolve and fx_dustwall_composite and checks (exit 1 on a failure): the composite keeps a
thin foreground and the first-person hand its low-resolution taps missed, and lays dust behind a see-through pane through
it, and lays no dust from behind a foreground surface; the resolve drops history at a new occluder, behind a ceiling that
moved in, with no history, and reads the previous frame on the march lattice when the frame width is not a multiple of the
divisor (its last column too); the march stops at the world behind the pixel, a straight-down ray sees the layer as a
slightly tilted one does, the layer lies on the local ground (a valley), a ray over rising ground does not skip it, and no
dust lies inside the fireball's ground footprint. Optional arguments: a Java 21
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


def image(w, h, alphas):
    return texture(g.GL_RGBA16F, g.GL_RGBA, w, h, [(0, 0, 0, a) for a in alphas])


def scene(w, h, depths):
    return texture(g.GL_RG32F, g.GL_RG, w, h, [(d, d) for d in depths])


def composite_cases(p):
    w, h = 12, 12
    target(w, h, [(g.GL_RGBA32F, g.GL_RGBA)])
    g.glUseProgram(p)
    bind(p, 0, image(3, 3, [0.25] * 9))
    data = [100.0] * (w * h)
    data[4 * w + 4] = 2.0
    data[4 * w + 5] = 0.10
    bind(p, 1, scene(w, h, data))
    g.glUniform1f(loc(p, "DwDiv"), 4)
    quad(p)
    result = read(w, h)
    check(result[4, 4, 3] > 0.999, f"opaque foreground missed by low-res taps: T={result[4, 4, 3]:.4f}, expected 1")
    check(result[4, 5, 3] > 0.999, f"Iris hand missed by low-res taps: T={result[4, 5, 3]:.4f}, expected 1")
    # a foreground surface 100 blocks away, every tap behind it at 109 (dust in those 9 blocks): not laid over it
    depth = [109.0] * (w * h)
    depth[4 * w + 4] = 100.0
    bind(p, 1, scene(w, h, depth))
    quad(p)
    r100 = read(w, h)[4, 4, 3]
    check(r100 > 0.999, f"foreground at 100 blocks, taps at 109: T={r100:.4f}, expected 1")

    # The r/g contract describes a pane one block away, with the dust behind it.
    # Incoming's pane representation is X + Tpane*background, here .4 + .5*.8.
    bind(p, 1, texture(g.GL_RG32F, g.GL_RG, w, h, [(100, 1)] * (w * h)))
    g.glActiveTexture(g.GL_TEXTURE0 + 13)
    glass = texture(g.GL_RGBA16F, g.GL_RGBA, w, h, [(0.4, 0.4, 0.4, 0.5)] * (w * h))
    g.glActiveTexture(g.GL_TEXTURE0 + 13)
    g.glBindTexture(g.GL_TEXTURE_2D, glass)
    g.glUniform1i(loc(p, "FxGlass"), 13)
    g.glUniform1f(loc(p, "FxSee"), 1)
    g.glClearColor(0.8, 0.8, 0.8, 1.0)
    g.glClear(g.GL_COLOR_BUFFER_BIT)
    g.glEnable(g.GL_BLEND)
    g.glBlendFuncSeparate(g.GL_ONE, g.GL_SRC_ALPHA, g.GL_ZERO, g.GL_ONE)
    quad(p)
    g.glDisable(g.GL_BLEND)
    red = read(w, h)[4, 4, 0]
    check(abs(red - 0.5) < 1e-3, f"dust behind a pane: RGB {red:.4f}, expected X + Tpane*Twall*background = 0.5")


def resolve(p, w, h, current, past, depths, history=1, before=None):
    size = ((w + 3) // 4, (h + 3) // 4)
    target(*size, [(g.GL_RGBA32F, g.GL_RGBA)])
    g.glUseProgram(p)
    bind(p, 0, image(*size, current))
    bind(p, 1, image(*size, past))
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
    bind(p, 2, scene(w, h, depths))
    proj = np.array([[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, -1.02, -0.2], [0, 0, -1, 0]], dtype=np.float32)
    g.glUniformMatrix4fv(loc(p, "PrevViewProj"), 1, True, proj)
    g.glUniformMatrix4fv(loc(p, "InvViewProj"), 1, True, np.linalg.inv(proj).astype(np.float32))
    g.glUniform3f(loc(p, "PallForward"), 0, 0, -1)
    g.glUniform1f(loc(p, "DwDiv"), 4)
    g.glUniform4f(loc(p, "DwReproject"), 0, 0, 0, history)
    g.glUniform2f(loc(p, "DwHistory"), 0.1, 10)
    # the previous frame's world distances (SceneDepth.previous); none given: the same as now
    bind(p, 3, scene(w, h, before if before is not None else depths))
    g.glUniform1f(loc(p, "DwPrevScene"), 1)
    quad(p)
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
// the ground: Floor, or Shelf.y blocks from Shelf.x blocks ahead of the eye on x (a rise)
float dwFloorEye(vec2 xz) { return Shelf.y > 0.0 && xz.x > Shelf.x ? Shelf.y : Floor; }
float dwOpenEye(vec3 p) { return 1.0; }
vec2 dwSoilAt(vec2 xz) { return vec2(0.0); }
#moj_import <incoming:dustwall.glsl>
uniform vec3 Dir;
uniform float End;
out vec4 fragColor;
void main() {
    vec4 wall = dwMarch(normalize(Dir), End, 0.8, 0.001, 128);
    fragColor = vec4(wall.rgb, wall.a);
}
""")
    p = gl["compileProgram"](gl["compileShader"]((core / "fx_dustwall.vsh").read_text(), g.GL_VERTEX_SHADER),
                             gl["compileShader"](src, g.GL_FRAGMENT_SHADER), validate=False)
    target(1, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
    g.glUseProgram(p)
    bind(p, 2, texture(g.GL_RGBA32F, g.GL_RGBA, 128, 1, [(0, 20, 0.11, 0)] * 128))
    g.glActiveTexture(g.GL_TEXTURE0 + 12)
    noise = g.glGenTextures(1)
    g.glBindTexture(g.GL_TEXTURE_3D, noise)
    g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
    g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R32F, 1, 1, 1, 0, g.GL_RED, g.GL_FLOAT, np.array([0.5], dtype=np.float32))
    g.glUniform1i(loc(p, "DwNoise"), 12)
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
programs = {name: link("fx_dustwall" + name) for name in ("", "_resolve", "_composite")}
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
composite_cases(programs["_composite"])
resolve_cases(programs["_resolve"])
march_cases()
sys.exit(bool(gl["FAILED"]))
