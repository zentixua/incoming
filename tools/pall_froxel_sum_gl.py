# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""The near volume's sums (fx_pall_froxel_sum) in two draws (the blocks, then the cells from them) against one draw over every
slice in front, on Mesa llvmpipe with a random medium volume, no game or GPU:

    uv run tools/pall_froxel_sum_gl.py

Gate (exit 1): every cell's light and share within 1e-4 (relative) of the one-draw sums. Run by dust_wall_gl.py."""
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
harness = ROOT / "tools/see_through_gl.py"
gl = {"__file__": str(harness)}
exec(compile(harness.read_text().split("\ncontext()\n", 1)[0], str(harness), "exec"), gl)
g = gl["g"]
context, link, target, texture, bind, quad, read, loc, check = (
    gl[n] for n in ("context", "link", "target", "texture", "bind", "quad", "read", "loc", "check"))
rgba = (g.GL_RGBA32F, g.GL_RGBA)

context()
program = link("fx_pall_froxel_sum")
g.glUseProgram(program)
tile_px, fw, fh = 8, 96, 64
tw, th = fw // tile_px, fh // tile_px
vw, vh = tw * 8, th * 8
projection = np.array([[1.2, 0, 0, 0], [0, 1.8, 0, 0], [0, 0, -1.02, -0.2], [0, 0, -1, 0]], dtype=np.float32)
g.glUniformMatrix4fv(loc(program, "InvViewProj"), 1, True, np.linalg.inv(projection).astype(np.float32))
g.glUniform3f(loc(program, "PallForward"), 0, 0, -1)
# a hazy frame air (pallAir): extinction and its airlight, so each slice's air term counts
g.glUniform4f(loc(program, "PallAir"), 0.004, 0.0, 0.0, 0.0)
g.glUniform3f(loc(program, "PallAirSky"), 0.5, 0.6, 0.7)
g.glUniform4f(loc(program, "PallAirFog"), 0.6, 0.6, 0.6, 0.0)
g.glUniform4f(loc(program, "PallFroxel"), tile_px, 1.0, 256.0, 0.0)
g.glUniform4f(loc(program, "PallFroxelFrame"), fw, fh, 0.1, 0.0)
rng = np.random.default_rng(3)
medium = np.zeros((vh, vw, 4), dtype=np.float32)
medium[..., 3] = rng.random((vh, vw)) ** 3 * 0.08
medium[..., :3] = medium[..., 3:4] * rng.random((vh, vw, 3)) * 2.0
volume = texture(*rgba, vw, vh, medium)


def draw(mode, w, h, blocks=None):
    target(w, h, [rgba])
    g.glUseProgram(program)
    bind(program, 0, volume)
    if blocks is not None:
        bind(program, 1, blocks)
    g.glUniform1f(loc(program, "PallSumBlocks"), mode)
    quad(program)
    return read(w, h)


one = draw(0, vw, vh)
fbo_blocks = draw(1, vw, th)
blocks = texture(*rgba, vw, th, fbo_blocks)
two = draw(2, vw, vh, blocks)
err = np.abs(two - one) / np.maximum(np.abs(one), 1e-3)
print(f"near-volume sums, {tw}×{th} cells × 64 slices: share {one[..., 3].min():.3f}..{one[..., 3].max():.3f}, "
      f"largest relative difference two draws against one {err.max():.2e}")
check(err.max() < 1e-4, "the sums in two draws (blocks, then cells) equal one draw over every slice in front")
if gl["FAILED"]:
    sys.exit(1)
