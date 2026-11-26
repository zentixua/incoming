# /// script
# dependencies = ["numpy", "pyopengl"]
# ///
"""Compare the shipped far-ray fragment programs on a CPU-only GL 3.2 context."""
import json
import numpy as np

import dust_wall_contract_gl as h
from nuclear_volume_warmup_gl import defaults, volume

g = h.g
h.context()
size = h.size
h.target(size, size, [h.rgba])
scene = h.make_scene(160)
empty = h.texture(*h.rgba, 64, 64, np.zeros((64, 64, 4), np.float32))
fire = h.texture(*h.rgba, 8, 8, [(0.2, 0.8, 0.8, 0)] * 64)
programs = []
for name in ("fx_pall", "fx_pall_single"):
    program = h.link(name)
    programs.append(program)
    g.glUseProgram(program)
    defaults(program, name)
    for unit in range(12):
        h.bind(program, unit, empty)
    for unit, sampler in enumerate(("PallNoise", "PallOpen", "SmokeGrid", "PallBall"), 12):
        volume(program, unit, sampler, 1)
    h.bind(program, 0, scene)
    h.bind(program, 3, fire)
    g.glUniformMatrix4fv(h.loc(program, "InvViewProj"), 1, True, np.linalg.inv(h.projection))
    g.glUniform4f(h.loc(program, "PallFrame"), 2, 48, 1, 0)
    g.glUniform4f(h.loc(program, "PallFroxel"), 8, 1, 0, 0)
    g.glUniform3f(h.loc(program, "PallMin"), -1000, -20, -200)
    g.glUniform3f(h.loc(program, "PallMax"), 1000, 200, 0)
    g.glUniform3f(h.loc(program, "PallFireMin"), -1000, -20, -200)
    g.glUniform3f(h.loc(program, "PallFireMax"), 1000, 200, 0)
    manifest = json.loads((h.namespace["CORE"] / (name + ".json")).read_text())
    unused = [u["name"] for u in manifest["samplers"] + manifest["uniforms"] if h.loc(program, u["name"]) < 0]
    if name == "fx_pall_single":
        assert not unused, f"Inactive single-burst bindings: {unused}"


def draw(program, *, offset=180, haze=0, soot=0, wilson=0, body=0, clear_uniform=False):
    # Shift the active cap across the narrow field of view between draws.
    table = np.zeros((5, 256, 4), np.float32)
    cap = table[0]
    cap[0] = (offset, -80, -20, 1)
    cap[1] = (0, 0, 40, 30)
    cap[2] = (20, 20, 10, 40)
    cap[3] = (10, 10, 20, 20)
    cap[4] = (20, body, 8, 2)
    cap[5] = (0.7, 0.6, 0.5, 0.3)
    cap[10] = (300, 0, 8, 8)
    cap[16] = (1, 1, 0, 0)
    g.glUseProgram(program)
    textures = [h.texture(*h.rgba, 256, 5, table),
                h.texture(*h.rgba, 1, 2, [(.03, 60, 0, 1), (haze, 60, 0, 0)])]
    h.bind(program, 7, textures[0])
    h.bind(program, 1, textures[1])
    if program == programs[1]:
        location = h.loc(program, "PallMushBody[0]")
        assert location >= 0
        g.glUniform4fv(location, 20, np.zeros_like(cap[:20]) if clear_uniform else cap[:20])
    slot = np.array([(offset, -80, -20, 800), (0, 0, 0, 1), (1, 0, .25, .75),
                     (0.03, 64, 0, 0)], np.float32)
    g.glUniformMatrix4fv(h.loc(program, "PallSlot0"), 1, False, slot)
    g.glUniform4f(h.loc(program, "PallWilson0"), 45, 4, wilson, 20)
    storm = np.array([(128 if soot else 0, 32, 120, 90), (0, 0, 2, 0),
                      (0, soot, 4, 0), (1, .4, .15, 0)], np.float32)
    g.glUniformMatrix4fv(h.loc(program, "PallFire0"), 1, False, storm)
    h.quad(program)
    result = h.read(size, size)
    g.glDeleteTextures(textures)
    return result


for name, params in [
    # the table's first row is ground dust the pall never reads (the dust pass draws it): an empty pall
    ("ground dust only", {}),
    ("haze only", {"haze": .012}),
    ("Wilson crossings", {"offset": 0, "wilson": .022}),
    ("fire-column soot", {"soot": .04}),
    ("cap on ray", {"offset": 0, "body": .08}),
    ("cap, Wilson and haze", {"offset": 0, "body": .08, "wilson": .022, "haze": .012}),
]:
    full, single = [draw(p, **params) for p in programs]
    delta = np.abs(full - single).max()
    t = float(np.median(full[..., 3]))
    print(f"{name}: median T {t:.6f}, max absolute RGBA difference {delta:.6g}", flush=True)
    assert np.all(np.isfinite(full)) and full[..., 3].min() >= 0 and full[..., 3].max() <= 1
    assert t == 1 if not params else t < .98, name
    np.testing.assert_allclose(full, single, atol=1e-4, rtol=0)
    if name == "cap on ray":
        missing = draw(programs[1], **params, clear_uniform=True)
        effect = abs(missing[..., 3] - full[..., 3]).max()
        print(f"zeroed uniform array, maximum transmittance change {effect:.6f}", flush=True)
        assert effect > .1
assert g.glGetError() == g.GL_NO_ERROR
