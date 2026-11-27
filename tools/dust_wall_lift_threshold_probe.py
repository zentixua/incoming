# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""The dust wave's horizon raise (dwRayFloor) as the eye's height over the floor at the drawn edge crosses the lift (review 2
of #797):

    uv run tools/dust_wall_lift_threshold_probe.py [checkout]

Runs the shipped fx_dustwall march of [checkout] on Mesa through tools/dust_wall_contract_gl.py's harness. Flat ground, the
drawn world to 168 blocks (12 chunks), a 20-block layer whose near edge stands 2000 blocks out, no world behind the pixel.
The band's pixels (rays passing the drawn edge 0.25-3.75 m over the ground there) are sampled row by row. The eye
stands h over the ground at the drawn edge; h sweeps across 4.4 m.

The raise brings every ray that passes the edge under the lift to the lift (4 m) at the edge, so one ray serves the whole
strip; that ray meets the ground 168 h / (h - 4) out, crossing the wave's near edge at h = 4 * 2000 / 1832 = 4.37 m. Across
that height the strip's whole band (4/168 rad, 1.36 deg, 21 px at 1080p and 70 deg) flips from dust to sky. Bearings along
the horizon whose drawn edge lies on either side of 4.37 m under the eye then draw a 21 px step in the wave's foot; a jump,
sneaking or walking over a rise moves it. The check fails where a 10 cm change of eye height moves the band's mean
transmittance by more than 0.5 (main: no raise, T=1 throughout; a fixed raise angle: each row flips at its own height, the
foot moves with the eye)."""
import runpy
import sys
from pathlib import Path

import numpy as np

checkout = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
contract = Path(__file__).resolve().parent / "dust_wall_contract_gl.py"
sys.argv = [str(contract), str(checkout)]
h = runpy.run_path(str(contract), run_name="probe")
g, loc, check = h["g"], h["loc"], h["check"]
march_setup, source, march, make_scene, pitch_view = (h[n] for n in ("march_setup", "source", "march", "make_scene",
                                                                     "pitch_view"))
h["context"]()
program = march_setup()
WALL, EDGE = 2000.0, 168.0
# the band's rows: each pixel's ray passes the drawn edge this many metres over the ground there (the lift is 4 m)
PASSES = (0.25, 1.0, 2.0, 3.0, 3.75)
rows = []
for eye in (1.62, 3.0, 4.0, 4.2, 4.3, 4.35, 4.4, 4.45, 4.5, 5.0, 6.0, 8.0):
    source(program, eye=(0, eye, 800 + WALL, 10), ring=(800, 0, 100, 800), relief=(0, 0), ground=(4096, 0, 0, 64),
           rows=[0] * 16)
    band = []
    for c in PASSES:
        g.glUseProgram(program)
        g.glUniform1f(loc(program, "PallFloorFrom"), EDGE)
        pitch_view(program, float(np.degrees(np.arctan((eye - c) / EDGE))))
        band.append(march(program, make_scene(1e9), 2)[0][1, 1, 3])
    rows.append((eye, float(np.mean(band))))
    print(f"eye {eye:.2f} over the edge's ground, wave from {WALL:.0f}: band rows T " + " ".join(f"{t:.3f}" for t in band)
          + f", mean {np.mean(band):.3f}")
jump = max(abs(b[1] - a[1]) / (b[0] - a[0]) * 0.1 for a, b in zip(rows, rows[1:]))
print(f"largest change of the band's mean T per 10 cm of eye height: {jump:.2f}")
check(jump < 0.5, "the band under the wave does not flip between dust and sky within 10 cm of eye height")
sys.exit(bool(h["namespace"]["FAILED"]))
