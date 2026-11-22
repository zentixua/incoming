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
slightly tilted one does, the layer lies on the local ground (a valley), a ray over rising ground does not skip it, no
dust lies inside the fireball's ground footprint, and the head does not turn around ground zero
nor ride out with the front,
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


def image(w, h, alphas):
    return texture(g.GL_RGBA16F, g.GL_RGBA, w, h, [(0, 0, 0, a) for a in alphas])


def scene(w, h, depths):
    return texture(g.GL_RG32F, g.GL_RG, w, h, [(d, d) for d in depths])


def resolve(p, w, h, current, past, depths, history=1, before=None):
    size = ((w + 3) // 4, (h + 3) // 4)
    target(*size, [(g.GL_RGBA32F, g.GL_RGBA)])
    g.glUseProgram(p)
    def atlas(values):
        tile = np.asarray(values).reshape(size[1], size[0])
        return image(2 * size[0], size[1], np.tile(tile, (1, 2)).ravel())
    bind(p, 0, atlas(current))
    bind(p, 1, atlas(past))
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
float dwAirT(float t, float dy) { return 1.0; }
vec3 dwAirLight(vec3 rd) { return vec3(0.0); }
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


def turning_case():
    """No turning around ground zero: the head's dense extinction along an arc on the ground 80 blocks behind the front
    (bearing -110..-70 deg, 40 blocks up), 0.02 s apart while the front runs out at 470 m/s, matches itself with no shift along the
    arc. Features placed by bearing x the front's current radius slid along it at bearing x the front's speed (~15 m here)."""
    core, expand = gl["CORE"], gl["expand"]
    src = expand("""#version 150
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
    nodes = [(arrive * (i / 127 * reach / 4500.0) ** 1.25, 68.0, 0.11, 0.0) for i in range(128)]
    bind(p, 2, texture(g.GL_RGBA32F, g.GL_RGBA, 128, 1, nodes))
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
    """No staircase along the wall: its lower edge (where the dust 3 blocks up begins behind the front) and its top (the
    highest dust 100 blocks behind the front) along a 60 degree arc at 4.5 km vary (std over 1 m) and their slope is
    near-normal (kurtosis under 6): one value per noise cell, flat inside with a step to the next, gives a heavy-tailed
    slope (flat runs and sudden steps). A smooth periodic profile is not caught here: the seeds' noise has no period."""
    core, expand = gl["CORE"], gl["expand"]
    src = expand("""#version 150
float dwFloorEye(vec2 xz) { return 0.0; }
float dwOpenEye(vec3 p) { return 1.0; }
vec2 dwSoilAt(vec2 xz) { return vec2(0.0); }
float dwAirT(float t, float dy) { return 1.0; }
vec3 dwAirLight(vec3 rd) { return vec3(0.0); }
#moj_import <incoming:dustwall.glsl>
uniform float Front;
uniform float Upper;
out vec4 fragColor;
void main() {
    float b = radians(-120.0 + 60.0 * gl_FragCoord.x / 2048.0);
    vec2 d = vec2(cos(b), sin(b));
    float edge = 0.0;
    if (Upper > 0.5) {
        for (int i = 0; i < 240; i++) {
            float y = 240.0 - float(i);
            if (dwDensity(vec3(d.x * (Front - 100.0), y, d.y * (Front - 100.0)), false) > 2e-3) { edge = y; break; }
        }
    } else {
        for (int i = 0; i < 300; i++) {
            float r = Front + 5.0 - float(i);
            if (dwDensity(vec3(d.x * r, 3.0, d.y * r), false) > 2e-3) { edge = r - Front; break; }
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
    nodes = [(arrive * (i / 127 * reach / 4500.0) ** 1.25, 68.0, 0.11, 0.0) for i in range(128)]
    bind(p, 2, texture(g.GL_RGBA32F, g.GL_RGBA, 128, 1, nodes))
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
    g.glUniform4f(loc(p, "DwEye"), 0, 120, 4500, t)
    g.glUniform4f(loc(p, "PallYoung"), 0, 1, 0, reach)
    g.glUniform4f(loc(p, "DwRing"), front, 0, 400, reach)
    g.glUniform1f(loc(p, "Front"), front)
    step = np.radians(60.0) * front / 2048
    for upper, name in ((0, "lower edge"), (1, "top")):
        g.glUniform1f(loc(p, "Upper"), upper)
        quad(p)
        e = read(2048, 1)[0, :, 0].astype(np.float64)
        # a staircase (one value per noise cell, flat inside, a step to the next) has a heavy-tailed slope: flat runs and
        # sudden steps; smooth multi-scale noise has a near-normal slope (kurtosis ~3)
        sl = np.diff(np.convolve(e, np.ones(3) / 3, "valid"))
        sl = sl - sl.mean()
        kurt = float((sl ** 4).mean() / max((sl ** 2).mean() ** 2, 1e-12))
        print(f"period: the wall's {name} (std {e.std():.1f} m) slope kurtosis {kurt:.1f} over the arc")
        check(e.std() > 1.0, f"the wall's {name} varies along the front (std {e.std():.2f} m > 1)")
        check(kurt < 6.0, f"the wall's {name} steps like a staircase (slope kurtosis {kurt:.1f}), expected smooth noise (< 6)")


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
resolve_cases(programs["_resolve"])
march_cases()
turning_case()
period_case()
# the render contract (march, resolve, composite together): its own script, the same exit rule
contract = subprocess.run([sys.executable, str(Path(__file__).resolve().with_name("dust_wall_contract_gl.py"))])
sys.exit(bool(gl["FAILED"]) or contract.returncode != 0)
