# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""Time the shipped pall ray shader on llvmpipe and optionally save its RGBA32F readbacks.

    uv run tools/dust_wall_ray_cost.py <checkout> [readbacks.npz]

The 128x128 rays face a synthetic dust and haze field from 400 blocks away. The off and fully
owned rings isolate the handoff. The narrower ring puts both transitions and the fireball
footprint in the same view. The haze-free and wet cases exercise independent media.
"""
import argparse
from pathlib import Path
from statistics import median
import sys
from time import perf_counter

import numpy as np

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    parser.add_argument("readbacks", type=Path, nargs="?")
    args = parser.parse_args()
    # The GL contract harness resolves shader includes in this checkout at import.
    sys.argv[1:] = [str(args.checkout.resolve())]
    import dust_wall_contract_gl as h
    from nuclear_volume_warmup_gl import defaults, volume

    h.context()
    g = h.g
    program = h.link("fx_pall")
    g.glUseProgram(program)
    defaults(program, "fx_pall")

    def uniform(name, *values):
        getattr(g, f"glUniform{len(values)}f")(h.loc(program, name), *values)

    def matrix(name, columns):
        g.glUniformMatrix4fv(h.loc(program, name), 1, False, np.array(columns, np.float32))

    size = 128
    h.size = 2 * size
    empty = h.texture(*h.rgba, 32, 4, np.zeros((4, 32, 4), np.float32))
    for unit in range(12):
        h.bind(program, unit, empty)
    for unit, name in enumerate(("PallNoise", "PallOpen", "SmokeGrid", "PallBall"), 12):
        volume(program, unit, name, 32 if unit == 12 else 1)
    h.bind(program, 0, h.make_scene(np.float32(680)))
    jitter = np.random.default_rng(730).random((64, 64), dtype=np.float32)
    h.bind(program, 2, h.texture(g.GL_R32F, g.GL_RED, 64, 64, jitter))
    h.bind(program, 5, h.texture(*h.rgba, 1, 1, [(2, .3, .2, .1)]))
    h.target(size, size, [h.rgba])
    projection = h.projection.copy()
    projection[0, 0], projection[1, 1] = 1.5, 2
    matrix("InvViewProj", np.linalg.inv(projection).T)
    uniform("PallFrame", 3, 48, 2, 0)
    uniform("PallMin", -1200, -210, -1000)
    uniform("PallMax", 1200, 600, 300)
    uniform("PallAirFog", .4, .45, .5, 600)
    uniform("PallAirSky", .45, .5, .57)
    uniform("PallSunTop", 1, 1, 1, 1)
    uniform("PallEyeLofted", .0005, .1, .1)
    uniform("PallEyeTau", .2, .15)
    uniform("PallGround0", 1000, -10, 10, 5)
    matrix("PallSlot0", [(0, -400, -80, 1200), (2, 1, 50, 2), (1, 0, 1/6, 5/6), (.03, 100, 4, .5)])
    matrix("PallWall0", [(440, 90, 70, .02), (0, 10, 1, .02), (0, 1, 1, 50), (1, 1, 1, 1)])

    rim = (70, 145, 80, 470)
    images = {}
    for label, ring, haze, wet in (
        ("off", (0, 0, 0, 0), .004, False),
        ("owned", (0, 0, 0, 1200), .004, False),
        ("rim", rim, .004, False),
        ("dry_empty", rim, 0, False),
        ("wet", rim, .004, True),
    ):
        table = np.zeros((3, 128, 4), np.float32)
        table[0] = (.009, 140, .1, .2)
        table[1] = (.004, 80, .65, 0)
        table[2] = (haze, 160, 0, .45)
        h.bind(program, 1, h.texture(*h.rgba, 128, 3, table))
        uniform("PallWater0", 300, 70, .003 if wet else 0, int(wet))
        uniform("PallYoung", *ring)
        h.quad(program)
        g.glFinish()
        image = h.read(size, size).copy()
        transmission = image[:, :, 3]
        assert np.isfinite(image).all() and transmission.min() >= 0 and np.ptp(transmission) > .05, label
        images[label] = image
        times = []
        for _ in range(5):
            start = perf_counter()
            g.glDrawArrays(g.GL_TRIANGLE_STRIP, 0, 4)
            g.glFinish()
            times.append(1000 * (perf_counter() - start))
        print(f"{label}: {median(times):.3f} ms; T {transmission.min():.5f}..{transmission.max():.5f}", flush=True)
    assert not np.array_equal(images["off"], images["owned"])
    assert not np.array_equal(images["rim"], images["wet"])
    assert g.glGetError() == g.GL_NO_ERROR
    if args.readbacks is not None:
        np.savez(args.readbacks, **images)


if __name__ == "__main__":
    main()
