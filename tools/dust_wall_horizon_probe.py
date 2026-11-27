# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""The dust wave's horizon lift (dwRayFloor raising rays with no world behind them) where it must not act (review of #797; run
by tools/dust_wall_gl.py):

    uv run tools/dust_wall_horizon_probe.py [checkout]

Runs the shipped fx_dustwall march of [checkout] on Mesa through tools/dust_wall_contract_gl.py's harness. Each case is a
pixel with no world behind it whose real ray meets the undrawn ground in front of the wave, so it has no dust; a check
fails where the march draws the wave there anyway:
- hill: an eye 30 blocks up, 12 chunks (the drawn world to 168 blocks), a 20-block layer whose near edge is 600 blocks
  out, the pixel 3.5° down: its ray meets the ground 490 blocks out, 110 in front of the wave, and passes the drawn edge
  20 blocks over the ground there (no strip to close);
- low render distance: the same at 6 chunks (PallFloorFrom 72), the pixel 5.5° down
  (ground 312 blocks out);
- LOD hole: an eye 30 up with LODs drawn (DwHorizonLift 0, as DustWallPass sets it), one pixel 3° down where the LOD terrain has a hole (scene depth: none); its ray
  meets the ground 572 blocks out, the wave's near edge stands 800 out: the LOD ground around the hole hides the wave.
Also prints the eye-above-the-haze case (eye 30 up over a thin layer, the pixel 8° down): the transmittance and the ray's
end, so the slant path through the layer can be compared between checkouts."""
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
FLAT = [0] * 16
h["context"]()


def pixel(program, eye, wall_from_eye, pitch, floor_from=168.0, scene=1e9, lift=4.0):
    source(program, eye=eye, ring=(800, 0, 100, 800), relief=(0, 0), ground=(4096, 0, 0, 64), rows=FLAT)
    g.glUseProgram(program)
    g.glUniform1f(loc(program, "PallFloorFrom"), floor_from)
    g.glUniform1f(loc(program, "DwHorizonLift"), lift)
    pitch_view(program, pitch)
    light = march(program, make_scene(scene), 2)[0][1, 1, 3]
    g.glUniform1f(loc(program, "DwProbe"), -2)
    ends = march(program, make_scene(scene), 2)
    g.glUniform1f(loc(program, "DwProbe"), 0)
    end, end_y = ends[0][1, 1, 0], ends[1][1, 1, 0]
    return light, end, end_y


program = march_setup()
ground_at = 30 / np.tan(np.radians(3.5))
t, end, end_y = pixel(program, (0, 30, 800 + 600, 10), 600, 3.5)
print(f"hill: eye 30 up, 12 chunks, 3.5° down, ground {ground_at:.0f} out, wave from 600: T={t:.4f}, ray end {end:.0f} "
      f"at height {end_y:.1f}")
check(t > 0.99, "an eye on a hill sees no wave below the wave's own foot (12 chunks)")

ground_at = 30 / np.tan(np.radians(5.5))
t, end, end_y = pixel(program, (0, 30, 800 + 600, 10), 600, 5.5, floor_from=72.0)
print(f"6 chunks: eye 30 up, 5.5° down, ground {ground_at:.0f} out, wave from 600: T={t:.4f}, ray end {end:.0f}")
check(t > 0.99, "at 6 chunks an eye on a hill sees no wave below the wave's own foot")

ground_at = 30 / np.tan(np.radians(3.0))
t, end, end_y = pixel(program, (0, 30, 800 + 800, 10), 800, 3.0, lift=0.0)
lod = ground_at * np.cos(np.radians(3.0))
t_lod, _, _ = pixel(program, (0, 30, 800 + 800, 10), 800, 3.0, scene=lod, lift=0.0)
print(f"LOD hole: eye 30 up, 3° down, LOD ground {ground_at:.0f} out, wave from 800: hole T={t:.4f} (ray end {end:.0f}), "
      f"the LOD pixel beside it T={t_lod:.4f}")
check(abs(t - t_lod) < 0.05, "a hole in the LOD terrain shows no dust the LOD ground around it hides")

# eye above the layer, inside the ring (dust under it): the slant path through it, informational
t, end, end_y = pixel(program, (0, 30, 400, 10), 0, 8.0)
print(f"eye 30 up over the layer, 8° down (ground {30 / np.tan(np.radians(8)):.0f} out): T={t:.4f}, ray end {end:.0f}")
sys.exit(bool(h["namespace"]["FAILED"]))
