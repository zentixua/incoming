# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""Measure a batch ('batch'), individual loading frames (default), or an unprepared first burst ('cold').
Each run starts in a fresh llvmpipe process with the driver cache disabled.

The Java submission order, allocation, reload and watchdog checks live in NuclearVolumeBudgetTest.
This timing fixture uses a 3x3 empty foot atlas region, a 3x3 march/history and a 12x12 composite.
Linking and resource allocation are outside the measured interval. No game or hardware GPU.
"""
import json
import os
import sys
from time import perf_counter

os.environ["MESA_SHADER_CACHE_DISABLE"] = "true"
os.environ["LP_NUM_THREADS"] = "2"

import numpy as np
import dust_wall_contract_gl as h

g = h.g


def framebuffer(width, fmt):
    h.target(width, width, [(fmt, g.GL_RGBA)])
    name = g.glGetIntegerv(g.GL_DRAW_FRAMEBUFFER_BINDING)
    colour = g.glGetFramebufferAttachmentParameteriv(g.GL_FRAMEBUFFER, g.GL_COLOR_ATTACHMENT0,
                                                      g.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
    return name, colour


def volume(p, unit, name, side):
    g.glActiveTexture(g.GL_TEXTURE0 + unit)
    texture = g.glGenTextures(1)
    g.glBindTexture(g.GL_TEXTURE_3D, texture)
    g.glTexParameteri(g.GL_TEXTURE_3D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    data = np.random.default_rng(0xd057).integers(0, 256, side**3, dtype=np.uint8)
    g.glTexImage3D(g.GL_TEXTURE_3D, 0, g.GL_R8, side, side, side, 0, g.GL_RED, g.GL_UNSIGNED_BYTE, data)
    g.glUniform1i(h.loc(p, name), unit)
    return texture


PALL = ("fx_mush_shadow", "fx_pall_foot", "fx_mush_taus", "fx_pall_froxel", "fx_pall_froxel_sum",
        "fx_pall", "fx_pall_resolve", "fx_pall_mix", "fx_pall_composite")
WALL = ("fx_dustwall", "fx_dustwall_resolve", "fx_dustwall_composite")


def defaults(program, name):
    setup = json.loads((h.namespace["CORE"] / (name + ".json")).read_text())
    for uniform in setup["uniforms"]:
        location = h.loc(program, uniform["name"])
        if location < 0:
            continue
        values = uniform["values"]
        if uniform["type"] == "matrix4x4":
            g.glUniformMatrix4fv(location, 1, False, np.array(values, np.float32))
        elif uniform["type"] == "int":
            g.glUniform1i(location, values[0])
        else:
            getattr(g, f"glUniform{len(values)}f")(location, *values)


def main(mode):
    h.context()
    programs = {name: h.link(name) for name in PALL}
    programs.update(zip(WALL, (h.march_setup(), h.link(WALL[1]), h.link(WALL[2]))))
    scene = h.make_scene(100)
    empty = h.texture(*h.rgba, 128, 4, np.zeros((4, 128, 4), np.float32))
    dusty = h.texture(*h.rgba, 128, 1, [(0, 20, .11, .3)] * 128)
    screen = framebuffer(h.size, g.GL_RGBA8)
    targets = {}
    for name in programs:
        if name.endswith("composite"):
            targets[name] = screen
        else:
            fmt = g.GL_RGBA32F if name == "fx_pall_foot" else g.GL_RGBA16F
            targets[name] = framebuffer(h.low, fmt)
    previous = h.texture(g.GL_RGBA16F, g.GL_RGBA, h.low, h.low)
    # The same offscreen pipeline and formats in every schedule. The matrices and sizes are explicit
    # so that the froxels and the empty mushroom execute a valid, bounded first draw.
    march = programs[WALL[0]]
    g.glUseProgram(march)
    wall_noise = volume(march, 12, "DwNoise", 128)
    for unit, sampler in enumerate(("PallNoise", "PallOpen", "SmokeGrid", "PallBall"), 12):
        texture = volume(march, unit, sampler, 1)
        if unit == 12:
            pall_noise = texture
    for name, program in programs.items():
        g.glUseProgram(program)
        if name in PALL:
            defaults(program, name)
        for unit, sampler in enumerate(("PallNoise", "PallOpen", "SmokeGrid", "PallBall"), 12):
            g.glUniform1i(h.loc(program, sampler), unit)
        g.glUniform1i(h.loc(program, "FxGlass"), 16)
        g.glUniform1f(h.loc(program, "DwDiv"), 4)
        g.glUniform1f(h.loc(program, "DwPrevDiv"), 4)
        g.glUniform4f(h.loc(program, "PallFroxel"), 8, 1, 256, 0)
        g.glUniform4f(h.loc(program, "PallFroxelFrame"), h.size, h.size, .1, 0)
    g.glActiveTexture(g.GL_TEXTURE0 + 16)
    g.glBindTexture(g.GL_TEXTURE_2D, empty)

    def draw(name, preparing):
        program = programs[name]
        g.glUseProgram(program)
        composite = name.endswith("composite")
        width = h.size if composite else h.low
        g.glBindFramebuffer(g.GL_FRAMEBUFFER, targets[name][0])
        g.glViewport(0, 0, width, width)
        if composite:
            g.glEnable(g.GL_BLEND)
            g.glBlendFuncSeparate(g.GL_ONE, g.GL_SRC_ALPHA, g.GL_ZERO, g.GL_ONE)
        else:
            g.glDisable(g.GL_BLEND)
        g.glActiveTexture(g.GL_TEXTURE0 + 12)
        g.glBindTexture(g.GL_TEXTURE_3D, wall_noise if name == WALL[0] else pall_noise)
        if name == WALL[0]:
            g.glUniform4f(h.loc(program, "DwRing"), *((0, 0, 0, 1) if preparing else (800, 0, 100, 800)))
            g.glUniform2f(h.loc(program, "DwFrame"), 1, 128)
            h.bind(program, 1, scene)
            h.bind(program, 2, empty if preparing else dusty)
            h.bind(program, 3, empty)
        elif name == WALL[1]:
            for unit, tex in enumerate((targets[WALL[0]][1], previous, scene, scene)):
                h.bind(program, unit, tex)
        elif name == WALL[2]:
            h.bind(program, 0, targets[WALL[1]][1])
            h.bind(program, 1, scene)
        else:
            for unit in range(12):
                h.bind(program, unit, empty)
            if name in ("fx_pall", "fx_pall_froxel", "fx_pall_foot"):
                h.bind(program, 0, scene)
            if name in ("fx_pall", "fx_pall_froxel"):
                h.bind(program, 9, targets["fx_pall_foot"][1])
                h.bind(program, 10, targets["fx_mush_taus"][1])
            if name in ("fx_pall_froxel_sum", "fx_pall_resolve", "fx_pall_mix", "fx_pall_composite"):
                source = {"fx_pall_froxel_sum": "fx_pall_froxel", "fx_pall_resolve": "fx_pall",
                          "fx_pall_mix": "fx_pall_resolve", "fx_pall_composite": "fx_pall_resolve"}
                h.bind(program, 0, targets[source[name]][1])
        h.quad(program)

    def frame(names, preparing):
        start = perf_counter()
        for name in names:
            draw(name, preparing)
        g.glFinish()
        return (perf_counter() - start) * 1000

    g.glFinish()
    preparation = []
    if mode == "batch":
        preparation.append(frame(programs, True))
    elif mode != "cold":
        for name in programs:
            preparation.append(frame((name,), True))
            print(f"  loading frame {name}: {preparation[-1]:.2f} ms", flush=True)
    first = frame(programs, False)
    print(f"{mode}: longest preparation frame {max(preparation, default=0):.2f} ms, "
          f"first eligible volume frame {first:.2f} ms", flush=True)
    assert g.glGetError() == g.GL_NO_ERROR
    g.glBindFramebuffer(g.GL_FRAMEBUFFER, targets[WALL[1]][0])
    assert h.read(h.low, h.low)[1, 1, 3] < .99, "eligible frame must actually produce dust"
    if preparation:
        # Relative to this process's cold compiler. Even one omitted costly first use must be visible.
        # Wall composite is the cheapest of the four previously missed passes.
        limit = min(preparation[-3:]) if mode == "staged" else preparation[0]
        assert first < limit / 2, f"first eligible frame still pays cold first-use cost: {first:.2f} ms"


if __name__ == "__main__":
    main(next((arg for arg in sys.argv[1:] if arg in ("cold", "batch")), "staged"))
