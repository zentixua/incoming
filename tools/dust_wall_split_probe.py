# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""The dust wave's two occupied intervals (dustwall.glsl dwInterval/dwKnot) against the readers' prefix (wall_layer.glsl
wallPrefixT) on Mesa llvmpipe, analytic media, no game or GPU:

    uv run tools/dust_wall_split_probe.py

The "spires" (hotfix-pall, Artem's Zearth scene 28.11): an eye inside thin haze looks at a dense dust wall 850 blocks out.
A ray that leaves the haze's top before the wall has a clear gap and two intervals; its neighbour that stays in the haze has
none. With a split at a clear gap alone the second ray was one homogeneous slab, and a reader at 700 blocks (a smoke quad,
the pall rays' end) took the wall's opacity in front of it: 0.36 of transmission against 0.88 on the gap ray, a jagged
grey edge along the haze's top that flipped with the sample jitter. Gates (exit 1 on a failure):
  - a reader in the haze in front of the wall sees within 0.1 of the exact transmission, with and without a gap;
  - the gap ray and the no-gap ray differ there by under 0.1, and the transmission moves by under 0.1 as the haze's end
    slides across the wall's face (no step where the gap opens);
  - control: the split at a clear gap alone (DwProbe below −5, DustWallDiag.split false) shows the defect (over 0.4).
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
// thin haze (Haze.y 1/block) from the eye to Haze.z, a dense wall (Haze.x 1/block) from Haze.w for 2000 blocks
uniform vec4 Haze;
float dwAnalytic(vec3 g) {
    float x = g.x, h = g.y;
    if (h < 0.0 || h > 50.0 || x < 0.0) return 0.0;
    float s = x < Haze.z ? Haze.y : 0.0;
    if (x >= Haze.w && x < Haze.w + 2000.0) s += Haze.x;
    return s;
}
float dwAnalyticBound(vec3 g) { return 60.0; }
"""
# the readers' prefix, the shipped function
LAYER = (SHADERS / "include/wall_layer.glsl").read_text()
PREFIX = LAYER[LAYER.index("float wallPrefixT("):LAYER.index("// Share of the dust's light")]
BODY = PREFIX + """
uniform float End, Steps, Foot, Jit, At, Knots;
out vec4 fragColor;
void main() {
    gDwKnotsOn = Knots > 0.5;
    float j = fract(Jit + 0.618034 * gl_FragCoord.x);
    vec4 w = dwMarch(vec3(1.0, 0.0, 0.0), End, j, Foot, int(Steps));
    // as fx_dustwall writes the split (one interval: A to the last occupied depth) and the readers take it
    if (!gDwInB && gDwSplit.y == 0.0) gDwSplit = vec4(gDwLastEnd, gDwLastEnd, w.a, 0.0);
    fragColor = vec4(wallPrefixT(At, gDwDepth.x, gDwSplit.x, gDwSplit.z, gDwSplit.y, gDwDepth.y, w.a), w.a, gDwSplit.x, gDwSplit.y);
}
"""


def program():
    src = expand(STUBS + (SHADERS / "include/dustwall.glsl").read_text() + BODY)
    vs = (CORE / "fx_dustwall.vsh").read_text()
    return gl["compileProgram"](gl["compileShader"](vs, g.GL_VERTEX_SHADER), gl["compileShader"](src, g.GL_FRAGMENT_SHADER),
                                validate=False)


def exact(haze, haze_end, wall, wall_from, at):
    return float(np.exp(-(haze * min(at, haze_end) + wall * max(0.0, min(at, wall_from + 2000) - wall_from))))


context()
p = program()
g.glUseProgram(p)
g.glUniform4f(loc(p, "DwEye"), 0, 25, 0, 100)
g.glUniform4f(loc(p, "DwRing"), 1e6, 0, 100, 1e6)
g.glUniform4f(loc(p, "DwLife"), 1e4, 0.05, 0, 0)
g.glUniform2f(loc(p, "DwRelief"), 0, 0)
target(64, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
HAZE, WALL, WALL_FROM, AT = 2e-4, 2e-3, 850.0, 700.0


def reader(haze_end, knots, rung=(96, 0.0027, 1.0)):
    steps, foot, detail = rung
    g.glUseProgram(p)
    g.glUniform4f(loc(p, "Haze"), WALL, HAZE, haze_end, WALL_FROM)
    g.glUniform1f(loc(p, "End"), 4000)
    g.glUniform1f(loc(p, "Steps"), steps)
    g.glUniform1f(loc(p, "DwDetail"), detail)
    g.glUniform1f(loc(p, "Foot"), foot)
    g.glUniform1f(loc(p, "At"), AT)
    g.glUniform1f(loc(p, "Knots"), 1.0 if knots else 0.0)
    out = []
    for jit in (0.11, 0.37, 0.63, 0.89):
        g.glUniform1f(loc(p, "Jit"), jit)
        quad(p)
        out.append(read(64, 1)[0].astype(np.float64))
    v = np.concatenate(out)
    return float(v[:, 0].mean()), float(v[:, 1].mean())


print("dust wave intervals: a reader 700 blocks into thin haze in front of a dense wall at 850 (tau 0.17 haze, 4 wall)")
for name, rung in {"HIGH": (128, 0.0022, 0.0), "MEDIUM": (96, 0.0027, 1.0), "LOW": (64, 0.0033, 2.0)}.items():
    stays, _ = reader(WALL_FROM, True, rung)
    gap, _ = reader(WALL_FROM - 200, True, rung)
    want_stays, want_gap = exact(HAZE, WALL_FROM, WALL, WALL_FROM, AT), exact(HAZE, WALL_FROM - 200, WALL, WALL_FROM, AT)
    print(f"  {name}: no gap {stays:.3f} (exact {want_stays:.3f}), 200-block gap {gap:.3f} (exact {want_gap:.3f})")
    check(abs(stays - want_stays) < 0.1 and abs(gap - want_gap) < 0.1, f"{name}: the reader sees the haze in front of it, not the wall")
    check(abs(stays - gap) < 0.1, f"{name}: the gap ray and its neighbour agree within 0.1 ({abs(stays - gap):.3f})")
# the haze's end slides across the wall's face: no step where the gap opens
ends = np.arange(500.0, 851.0, 25.0)
seen = [reader(e, True)[0] for e in ends]
jumps = np.abs(np.diff(seen))
print("  haze end " + " ".join(f"{e:.0f}:{s:.3f}" for e, s in zip(ends, seen)))
check(jumps.max() < 0.1, f"the reader's transmission moves smoothly as the haze's top crosses the wall (largest step {jumps.max():.3f})")
# control: the split at a clear gap alone
old_stays, _ = reader(WALL_FROM, False)
old_gap, _ = reader(WALL_FROM - 200, False)
print(f"  control, split at a clear gap alone: no gap {old_stays:.3f}, gap {old_gap:.3f}")
check(abs(old_stays - old_gap) > 0.4, "control: the gap-only split shows the spires' step")
if gl["FAILED"]:
    sys.exit(f"dust wall split probe failed: {len(gl['FAILED'])}")
