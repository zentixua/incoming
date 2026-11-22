# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""Measure the four draws missed by an empty pall frame. Run with 'cold' for the unprepared control.

The Java submission order, allocation, reload and watchdog checks live in NuclearVolumeBudgetTest.
This timing fixture uses a 3x3 empty foot atlas region, a 3x3 march/history and a 12x12 composite.
Linking and resource allocation are outside the measured interval. No game or hardware GPU.
"""
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


def main(cold):
    h.context()
    foot, resolve, composite = (h.link(name) for name in ("fx_pall_foot", "fx_dustwall_resolve", "fx_dustwall_composite"))
    march = h.march_setup()
    scene = h.make_scene(100)
    empty = h.texture(*h.rgba, 128, 4, np.zeros((4, 128, 4), np.float32))
    dusty = h.texture(*h.rgba, 128, 1, [(0, 20, .11, .3)] * 128)
    atlas, _ = framebuffer(h.low, g.GL_RGBA32F)
    samples, rays = framebuffer(h.low, g.GL_RGBA16F)
    history, resolved = framebuffer(h.low, g.GL_RGBA16F)
    previous_history = h.texture(g.GL_RGBA16F, g.GL_RGBA, h.low, h.low)
    screen, _ = framebuffer(h.size, g.GL_RGBA8)
    g.glUseProgram(march)
    wall_noise = volume(march, 12, "DwNoise", 128)
    g.glUseProgram(foot)
    foot_noise = volume(foot, 12, "PallNoise", 1)
    volume(foot, 13, "PallOpen", 1)
    for program in (resolve, composite):
        g.glUseProgram(program)
        g.glUniform1f(h.loc(program, "DwDiv"), 4)

    # Keep complete, distinct samplers bound; the foot row stays blank to isolate driver first use.
    def frame(preparing):
        intervals = []
        for program, fbo, width in ((foot, atlas, h.low), (march, samples, h.low),
                                    (resolve, history, h.low), (composite, screen, h.size)):
            g.glUseProgram(program)
            g.glBindFramebuffer(g.GL_FRAMEBUFFER, fbo)
            g.glViewport(0, 0, width, width)
            if program == foot:
                g.glDisable(g.GL_BLEND)
                g.glActiveTexture(g.GL_TEXTURE0 + 12)
                g.glBindTexture(g.GL_TEXTURE_3D, foot_noise)
                for unit, sampler in enumerate((1, 3, 4, 5, 6, 7, 8, 11)):
                    g.glActiveTexture(g.GL_TEXTURE0 + unit)
                    g.glBindTexture(g.GL_TEXTURE_2D, empty)
                    g.glUniform1i(h.loc(foot, f"Sampler{sampler}"), unit)
            elif program == march:
                g.glActiveTexture(g.GL_TEXTURE0 + 12)
                g.glBindTexture(g.GL_TEXTURE_3D, wall_noise)
                ring = (0, 0, 0, 1) if preparing else (800, 0, 100, 800)
                g.glUniform4f(h.loc(march, "DwRing"), *ring)
                h.bind(march, 1, scene)
                h.bind(march, 2, empty if preparing else dusty)
                h.bind(march, 3, empty)
                g.glUniform2f(h.loc(march, "DwFrame"), 1, 128)
            elif program == resolve:
                for unit, tex in enumerate((rays, previous_history, scene, scene)):
                    h.bind(resolve, unit, tex)
            else:
                h.bind(composite, 0, resolved)
                h.bind(composite, 1, scene)
                g.glEnable(g.GL_BLEND)
                g.glBlendFuncSeparate(g.GL_ONE, g.GL_SRC_ALPHA, g.GL_ZERO, g.GL_ONE)
            start = perf_counter()
            h.quad(program)
            g.glFinish()
            intervals.append((perf_counter() - start) * 1000)
        assert g.glGetError() == g.GL_NO_ERROR
        return intervals

    g.glFinish()
    if not cold:
        frame(True)
    times = frame(False)
    print("First eligible draws with " + ("cold programs" if cold else "prepared programs"))
    print("  " + ", ".join(f"{name} {ms:.2f} ms" for name, ms in zip(("foot", "march", "resolve", "composite"), times))
          + f", sum {sum(times):.2f} ms")
    g.glBindFramebuffer(g.GL_FRAMEBUFFER, history)
    assert h.read(h.low, h.low)[1, 1, 3] < .99, "eligible frame must actually produce dust"


if __name__ == "__main__":
    main("cold" in sys.argv[1:])
