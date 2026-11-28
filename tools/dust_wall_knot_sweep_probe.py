# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""Review probe (PR #812 round 1): the dust march's knot (dustwall.glsl dwKnot) over a family of analytic profiles, on Mesa
llvmpipe, no game or GPU:

    uv run tools/dust_wall_knot_sweep_probe.py [--gap-only]

--gap-only: the split at a clear gap alone (main's march, DustWallDiag.split false), for comparison.

dust_wall_split_probe.py checks one reader (700 blocks) in one haze/wall pair. Here the haze density, the wall's density and
the depth of its face vary, with readers at several depths in front of and inside the wall, and two orders: thin haze in
front of a dense wall ("haze-wall") and a dense near layer in front of thin haze ("near-haze", an eye in young dust looking
out). For each case it prints the worst |T - exact| of a reader and the worst step of a reader's T between neighbouring
profiles (the wall's face 10 blocks further: a candidate swap moves the knot by a whole level and shows as a step that the
spires' gate, 0.1, was meant to forbid). Gates (exit 1): worst error under 0.1 and worst step under 0.1.
"""
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
harness = ROOT / "tools/see_through_gl.py"
gl = {"__file__": str(harness)}
exec(compile(harness.read_text().split("\ncontext()\n", 1)[0], str(harness), "exec"), gl)
g = gl["g"]
context, target, quad, read, loc, check, expand = (gl[n] for n in ("context", "target", "quad", "read", "loc", "check", "expand"))
CORE = gl["CORE"]
SHADERS = ROOT / "mod/src/main/resources/assets/incoming/shaders"

STUBS = """#version 150
float dwFloorEye(vec2 xz) { return 0.0; }
float dwOpenEye(vec3 p) { return 1.0; }
vec2 dwSoilAt(vec2 xz) { return vec2(0.0); }
float dwAirT(float t, float dy) { return 1.0; }
vec3 dwAirLight(vec3 rd) { return vec3(0.0); }
uniform vec4 DwEye; uniform vec4 DwRing; uniform vec4 DwLife; uniform vec4 DwBall; uniform vec3 DwBallLight; uniform float DwCut;
uniform vec3 DwFire; uniform vec2 DwRelief; uniform vec4 DwSurge;
#define DW_ANALYTIC
// Prof: (first layer's density from the eye to Prof.z, second layer's density from Prof.z for Prof.w blocks)
uniform vec4 Prof;
float dwAnalytic(vec3 g) {
    float x = g.x, h = g.y;
    if (h < 0.0 || h > 50.0 || x < 0.0) return 0.0;
    if (x < Prof.z) return Prof.x;
    if (x < Prof.z + Prof.w) return Prof.y;
    return 0.0;
}
float dwAnalyticBound(vec3 g) { return 60.0; }
"""
LAYER = (SHADERS / "include/wall_layer.glsl").read_text()
PREFIX = LAYER[LAYER.index("float wallPrefixT("):LAYER.index("// Share of the dust's light")]
BODY = PREFIX + """
uniform float End, Steps, Foot, Jit, Knots;
uniform vec4 At;
out vec4 fragColor;
void main() {
    gDwKnotsOn = Knots > 0.5;
    float j = fract(Jit + 0.618034 * gl_FragCoord.x);
    vec4 w = dwMarch(vec3(1.0, 0.0, 0.0), End, j, Foot, int(Steps));
    if (!gDwInB && gDwSplit.y == 0.0) gDwSplit = vec4(gDwLastEnd, gDwLastEnd, w.a, 0.0);
    fragColor = vec4(wallPrefixT(At.x, gDwDepth.x, gDwSplit.x, gDwSplit.z, gDwSplit.y, gDwDepth.y, w.a),
                     wallPrefixT(At.y, gDwDepth.x, gDwSplit.x, gDwSplit.z, gDwSplit.y, gDwDepth.y, w.a),
                     wallPrefixT(At.z, gDwDepth.x, gDwSplit.x, gDwSplit.z, gDwSplit.y, gDwDepth.y, w.a),
                     wallPrefixT(At.w, gDwDepth.x, gDwSplit.x, gDwSplit.z, gDwSplit.y, gDwDepth.y, w.a));
}
"""

context()
vs = (CORE / "fx_dustwall.vsh").read_text()
p = gl["compileProgram"](gl["compileShader"](vs, g.GL_VERTEX_SHADER),
                         gl["compileShader"](expand(STUBS + (SHADERS / "include/dustwall.glsl").read_text() + BODY), g.GL_FRAGMENT_SHADER),
                         validate=False)
g.glUseProgram(p)
g.glUniform4f(loc(p, "DwEye"), 0, 25, 0, 100)
g.glUniform4f(loc(p, "DwRing"), 1e6, 0, 100, 1e6)
g.glUniform4f(loc(p, "DwLife"), 1e4, 0.05, 0, 0)
g.glUniform2f(loc(p, "DwRelief"), 0, 0)
g.glUniform1f(loc(p, "End"), 4000)
g.glUniform1f(loc(p, "Steps"), 96)
g.glUniform1f(loc(p, "DwDetail"), 1.0)
g.glUniform1f(loc(p, "Foot"), 0.0027)
target(64, 1, [(g.GL_RGBA32F, g.GL_RGBA)])


KNOTS = "--gap-only" not in sys.argv


def readers(a, b, face, depth, at, knots=None):
    knots = KNOTS if knots is None else knots
    g.glUniform4f(loc(p, "Prof"), a, b, face, depth)
    g.glUniform4f(loc(p, "At"), *at)
    g.glUniform1f(loc(p, "Knots"), 1.0 if knots else 0.0)
    out = []
    for jit in (0.11, 0.37, 0.63, 0.89):
        g.glUniform1f(loc(p, "Jit"), jit)
        quad(p)
        out.append(read(64, 1)[0].astype(np.float64))
    return np.concatenate(out).mean(0)


def exact(a, b, face, depth, z):
    return float(np.exp(-(a * min(z, face) + b * max(0.0, min(z, face + depth) - face))))


worst = {"err": (0.0, None), "step": (0.0, None)}
rows = []
for order in ("haze-wall", "near-haze"):
    for a in ((1e-4, 2e-4, 4e-4) if order == "haze-wall" else (4e-3, 8e-3)):
        for b in ((1e-3, 2e-3, 5e-3) if order == "haze-wall" else (1e-4, 3e-4)):
            depth = 2000.0 if order == "haze-wall" else 3000.0
            faces = np.arange(400.0, 1001.0, 10.0) if order == "haze-wall" else np.arange(60.0, 301.0, 5.0)
            prev = None
            case_err = case_step = 0.0
            case_where = ""
            for face in faces:
                at = (0.5 * face, 0.85 * face, face + 0.1 / b, face + 0.4 / b) if order == "haze-wall" else \
                    (0.5 * face, face + 50, face + 300, face + 900)
                got = readers(a, b, face, depth, at)
                want = np.array([exact(a, b, face, depth, z) for z in at])
                err = np.abs(got - want)
                if err.max() > worst["err"][0]:
                    worst["err"] = (float(err.max()), (order, a, b, face, at[int(err.argmax())], got[int(err.argmax())], want[int(err.argmax())]))
                case_err = max(case_err, float(err.max()))
                if prev is not None:
                    # the readers' exact transmission moves too: the step is the reconstruction's own jump
                    st = np.abs((got - want) - (prev[0] - prev[1]))
                    if st.max() > worst["step"][0]:
                        worst["step"] = (float(st.max()), (order, a, b, face, at[int(st.argmax())]))
                    if st.max() > case_step:
                        k = int(st.argmax())
                        case_where = f"face {face - (faces[1] - faces[0]):.0f} -> {face:.0f}, reader {at[k]:.0f}: T {prev[0][k]:.3f} -> {got[k]:.3f} (exact {prev[1][k]:.3f} -> {want[k]:.3f})"
                    case_step = max(case_step, float(st.max()))
                prev = (got, want)
            rows.append((order, a, b, case_err, case_step))
            print(f"  {order:9s} first {a:.0e}/block, second {b:.0e}/block: worst |T - exact| {case_err:.3f}, worst step {case_step:.3f} {case_where}")
print("worst error", worst["err"])
print("worst step (error change between neighbouring faces)", worst["step"])
check(worst["err"][0] < 0.1, f"every reader within 0.1 of the exact transmission ({worst['err'][0]:.3f})")
check(worst["step"][0] < 0.1, f"no reader's error jumps by 0.1 between neighbouring profiles ({worst['step'][0]:.3f})")
if gl["FAILED"]:
    sys.exit(f"knot sweep probe failed: {len(gl['FAILED'])}")
