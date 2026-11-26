# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""The one-tap light (MEDIUM, LOW) where the brighter light changes between the fireball and the sun: the light must be
continuous there. At every sample the fireball's light is set so that the two lights' sums sit on the switch (x (1 +- 1e-3)),
and the light is evaluated on both sides; a continuous light changes by ~1e-3 there. A hard switch drew an arc that swept over
the dust face as the ball cooled (review step 8: 12.9/255 at the median). HIGH (two taps, no choice) is the control.

    uv run tools/uw_probe/onetap_flip.py [--repo DIR]     (DIR: a checkout with mod/build/uw-probe/law-1000kt.json; default
                                                          this checkout; probe_gl.py writes the law file)
Exit 1 when a one-tap p90 step exceeds 1/255.
"""
import sys
from pathlib import Path

import numpy as np

REPO = Path(sys.argv[sys.argv.index("--repo") + 1]) if "--repo" in sys.argv else Path(__file__).resolve().parents[2]
probe = REPO / "tools/uw_probe/probe_gl.py"
src = probe.read_text().split("\nlaws = {", 1)[0]
ns = {"__file__": str(probe), "__name__": "probe"}
sys.argv = [str(probe)]
exec(compile(src, str(probe), "exec"), ns)
g, gl, loc, target, quad, read, expand, CORE = (ns[n] for n in ("g", "gl", "loc", "target", "quad", "read", "expand", "CORE"))
gl["context"]()

BODY = """
uniform float Frac, Eps, Away;
uniform vec3 BallHue;
out vec4 fragColor;
void main() {
    float i = gl_FragCoord.x;
    float phi = radians(-10.0 + 20.0 * fract(i * 0.618034));
    float rr = 4300.0 + 400.0 * fract(i * 0.4142);
    vec2 xz = rr * vec2(cos(phi), sin(phi));
    float H = dwLaw(rr, DwEye.w - dwArrival(rr)).x;
    vec3 p = vec3(xz.x, Frac * H, xz.y);
    vec3 rd = normalize(vec3(-cos(radians(6.0)) * (1.0 - 2.0 * Away), -sin(radians(6.0)), 0.0));
    gDwForceF = 1.0;
    float s = dwDensity(p, false);
    // the ball's light at its surface such that the choice sits on the switch at p
    vec3 d = DwBall.xyz - p;
    float fall = min(DwBall.w * DwBall.w / max(dot(d, d), 1.0), 1.0);
    DwBallLight = BallHue * (dot(DwSunLight, vec3(1.0)) / dot(BallHue, vec3(1.0)) / fall * (1.0 + Eps));
    vec3 c = dwTexturedLight(p, rd);
    fragColor = vec4(c, s);
}
"""
VARIANTS = {"one tap": (REPO / "mod/src/main/resources/assets/incoming/shaders/include/dustwall.glsl").read_text()}
UNIFORMS = ns["NEW_UNIFORMS"].replace("uniform vec3 DwBallLight;", "vec3 DwBallLight = vec3(0.0);")


def program(model):
    s = expand(ns["STUBS"] + UNIFORMS + model + BODY)
    vs = (CORE / "fx_dustwall.vsh").read_text()
    return gl["compileProgram"](gl["compileShader"](vs, g.GL_VERTEX_SHADER), gl["compileShader"](s, g.GL_FRAGMENT_SHADER), validate=False)


law = ns["Law"]("1000")
R = 4500.0
lum = np.array([0.2126, 0.7152, 0.0722])
worst = {}
for vname, model in VARIANTS.items():
    p = program(model)
    g.glUseProgram(p); ns["noise"](p)
    for age in (4, 20):
        t = law.arrival_at(R) + age
        ns["bind_new"](p, law, t, (R + 600, 120.0, 0.0))
        ns["scene_light"](p, age)
        hue = ns["SCENE_LIGHT"]["ball"][4 if age < 12 else 20][1]
        g.glUniform3f(loc(p, "BallHue"), *[max(h, 1e-3) for h in hue])
        for det, dname in ((0.0, "HIGH"), (1.0, "MEDIUM"), (2.0, "LOW")):
            g.glUniform1f(loc(p, "DwDetail"), det)
            for away in (0.0, 1.0):
                g.glUniform1f(loc(p, "Away"), away)
                steps, rel = [], []
                for frac in (0.3, 0.6, 0.9):
                    g.glUniform1f(loc(p, "Frac"), frac)
                    out = []
                    for eps in (1e-3, -1e-3):
                        g.glUniform1f(loc(p, "Eps"), eps)
                        target(256, 1, [(g.GL_RGBA32F, g.GL_RGBA)])
                        quad(p)
                        out.append(read(256, 1)[0].astype(np.float64))
                    a, b = out
                    on = (a[:, 3] > 1e-4)
                    la, lb_ = a[:, :3] @ lum, b[:, :3] @ lum
                    # screen (gamma 2.2) luma step in 1/255 of opaque dust, and the linear relative step
                    da = np.clip(a[:, :3], 0, None) ** (1 / 2.2) @ lum * 255
                    db = np.clip(b[:, :3], 0, None) ** (1 / 2.2) @ lum * 255
                    steps.append(np.abs(da - db)[on])
                    rel.append((np.abs(la - lb_) / np.maximum(0.5 * (la + lb_), 1e-9))[on])
                st, rl = np.concatenate(steps), np.concatenate(rel)
                view = "toward the sun" if away < 0.5 else "away from the sun"
                print(f"{vname:28s} +{age:2d} s {dname:6s} {view:17s}: luma step at the switch median {np.median(st):5.2f}/255, "
                      f"p90 {np.percentile(st, 90):5.2f}, max {st.max():5.2f}; linear median {100 * np.median(rl):5.1f} %, p90 {100 * np.percentile(rl, 90):5.1f} %")
                worst[(vname, age, dname, view)] = float(np.percentile(st, 90))
one = max(v for k, v in worst.items() if k[2] != "HIGH")
high = max(v for k, v in worst.items() if k[2] == "HIGH")
print(f"worst p90 luma step at the switch: one tap {one:.2f}/255, HIGH control {high:.2f}/255")
sys.exit(1 if one > 1.0 else 0)
