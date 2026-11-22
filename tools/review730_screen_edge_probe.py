# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""Dust upsampling at the screen edge and a convex corner on continuous terrain (#730)."""
import sys

import numpy as np

from dust_wall_contract_gl import composite, context, link, make_scene, size, slab


def surface_rows(depths):
    light, span = slab(.5, 100, .5)
    # March centres are at rows 2, 6 and 10. Each ray contains dust up to its surface.
    end = depths[2::4, None]
    span[:, :, 1] = .5 * end
    span[:, :, 2] = .5 * (.5 + end) / 2
    result = composite(link("fx_pall_composite"), make_scene(depths[:, None]), (light, span))
    return result[:, 6, 3]


def main():
    context()
    rows = np.arange(size, dtype=np.float32)
    plane = surface_rows(100 + .25 * rows)
    corner = surface_rows(100 + .25 * abs(rows - 4))
    print(f"Sloped ground T by row {np.round(plane, 4)}, convex corner T={corner[4]:.4f}")
    return not (np.allclose(plane, .5, atol=.004) and np.allclose(corner, .5, atol=.004))


if __name__ == "__main__":
    sys.exit(main())
