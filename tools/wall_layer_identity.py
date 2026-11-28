# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""Compare the shipped wall filter with the 7de91b6d1 algorithm (its gather the shipped four-tap one, its atlas three parts) on
identical llvmpipe inputs: light and depths; the clipped transmission follows the two-interval law the contract harness checks."""
from pathlib import Path

import numpy as np

import dust_wall_contract_gl as h

SOURCE = """#version 150
#moj_import <incoming:pall.glsl>
#moj_import <incoming:wall_layer.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform float Div;
uniform vec2 Offset;
uniform int Mode;
out vec4 fragColor;
void main() {
    ivec2 full = ivec2(gl_FragCoord.xy);
    float here = pallScene(texelFetch(Sampler1, full, 0).r);
    WallLayer w = wallLayer(Sampler0, Sampler1, gl_FragCoord.xy + Offset, here, Div);
    if (Mode == 0) fragColor = w.light;
    else fragColor = vec4(w.depth, wallTo(w, 0.7 * min(here, 1e4) + 1.0).a);
}
"""


def fixture(rng, width, height, div, extra, density):
    low_width = max(int(np.ceil(width / div)) + extra, 1)
    low_height = max(int(np.ceil(height / div)) + extra, 1)
    shape = (low_height, low_width)
    light = np.zeros((*shape, 4), np.float32)
    if density == "sparse":
        occupied = rng.random(shape) < 0.08
    elif density == "edge":
        occupied = np.zeros(shape, bool)
        rng.random()  # Preserve the reviewer's seed sequence through the edge fixtures.
        occupied[0, :] = occupied[-1, :] = occupied[:, 0] = occupied[:, -1] = True
        occupied &= rng.random(shape) < 0.5
    else:
        occupied = rng.random(shape) < 0.4
    transmission = np.where(occupied, rng.choice([0.0, 0.3, 0.9, 0.999999, 1.0 - 2 ** -20], shape), 1.0)
    light[..., 3] = transmission
    light[..., :3] = np.where(occupied[..., None], rng.random((*shape, 3)) * 2, 0)
    # transmitting texels that still carry light (emission-like, fireball over-white)
    glow = (~occupied) & (rng.random(shape) < 0.1)
    light[glow, :3] = rng.random((glow.sum(), 3)) * 5
    z0 = rng.uniform(5, 600, shape).astype(np.float32)
    z1 = z0 + rng.uniform(0, 200, shape).astype(np.float32)
    zm = (z0 + z1) / 2
    cover = 1 - light[..., 3]
    span = np.stack((z0 * cover, z1 * cover, zm * cover, light[..., 3]), -1).astype(np.float32)
    # inconsistent span alpha on some transmitting texels
    odd = (~occupied) & (rng.random(shape) < 0.05)
    span[odd, 3] = 0.5
    span[odd, :3] = 100
    if density == "bad":
        for values in (light, span):
            invalid = rng.random(shape) < 0.05
            values[invalid, rng.integers(0, 4)] = rng.choice([np.nan, np.inf, -np.inf, -1.0, 1.5, 7e4])
    # the split third (wall_layer.glsl): one interval, A whole (its exit and B's entry at the span's end, A's opacity the whole
    # opacity, A's share of the light all of it), so the shipped thirds filter reduces to the reference's homogeneous layer
    split = np.stack((z1 * cover, z1 * cover, cover, cover), -1).astype(np.float32)
    atlas = np.concatenate((light, span, split), axis=1)
    depth = np.where(rng.random((height, width)) < 0.5, 50.0, 400.0).astype(np.float32)
    depth[:, : width // 3] = rng.uniform(1, 700, (height, width // 3))
    special = rng.random((height, width))
    depth[special < 0.03] = 1e9
    depth[(special >= 0.03) & (special < 0.05)] = 0.05
    depth[(special >= 0.05) & (special < 0.06)] = np.nan
    depth[(special >= 0.06) & (special < 0.07)] = -1.0
    depth[(special >= 0.07) & (special < 0.08)] = np.inf
    return atlas, depth


def draw(program, width, height, atlas, scene, div, offset, mode):
    g = h.g
    g.glUseProgram(program)
    h.bind(program, 0, atlas)
    h.bind(program, 1, scene)
    g.glUniform1f(h.loc(program, "Div"), div)
    g.glUniform2f(h.loc(program, "Offset"), *offset)
    g.glUniform1i(h.loc(program, "Mode"), mode)
    h.quad(program)
    return h.read(width, height)


def main():
    h.context()
    reference = Path(__file__).with_name("fixtures") / "wall_layer_7de91b6d1.glsl"
    base = h.fragment_program(SOURCE.replace("#moj_import <incoming:wall_layer.glsl>", reference.read_text()))
    current = h.fragment_program(SOURCE)
    rng = np.random.default_rng(732)
    cases = total = signed_zeros = 0
    for div in (1, 2, 3, 4, 5, 6, 8):
        for width, height in ((37, 23), (64, 48)):
            h.target(width, height, [h.rgba])
            for extra in (0, 1, -1):
                for density in ("sparse", "edge", "mixed", "bad"):
                    atlas, depth = fixture(rng, width, height, div, extra, density)
                    tex = h.texture(*h.rgba, atlas.shape[1], atlas.shape[0], atlas)
                    scene = h.texture(*h.scene_format, width, height, np.stack((depth, depth), -1))
                    for offset in ((0, 0), (0.37, -0.21), (-0.5, -0.5), (0.25, 0.25)):
                        for mode in (0, 1):
                            args = (width, height, tex, scene, div, offset, mode)
                            expected, actual = draw(base, *args), draw(current, *args)
                            # (within float rounding: the shipped clip and split compute the same law in another order)
                            bits_differ = (expected.view(np.uint32) != actual.view(np.uint32)) & ~(np.abs(expected - actual) <= 2e-6 + 4e-7 * np.abs(expected))
                            zeros = (expected == 0) & (actual == 0)
                            bad = bits_differ & ~zeros
                            if mode == 1:
                                # the clipped transmission is the two-interval law (the split third), not the reference's one
                                # layer: tools/dust_wall_contract_gl.py checks it (volume, crossing, sparse taps)
                                bad[..., 3] = False
                            if mode == 0:
                                # a fully transmitting result: consumers ignore its light (WallLayer), and the shipped filter
                                # returns an empty neighbourhood early
                                clear = (expected[..., 3] == 1) & (actual[..., 3] == 1)
                                bad[..., :3] &= ~clear[..., None]
                            if bad.any():
                                pos = tuple(np.argwhere(bad)[0])
                                raise AssertionError(f"case {cases}, {div=}, {density=}, {offset=}, {mode=}, {pos}: "
                                                     f"expected {expected[pos]}, got {actual[pos]}; pixel {expected[pos[:2]]} / {actual[pos[:2]]}")
                            signed_zeros += np.count_nonzero(bits_differ & zeros)
                            total += expected.size
                        cases += 1
                    h.g.glDeleteTextures([tex, scene])
    print(f"wallLayer: {cases} cases, {total} values match ({signed_zeros} signed-zero differences)")
    cut_once(current)


def cut_once(current):
    """The composite's coarse rungs cut the filtered layer once (gWallCutOnce): where the taps of the pixel's class carry the
    same dust it equals the four cuts; on the random fixtures above its transmission at the pixel's depth differs by the
    printed amount (taps of one class with different spans)."""
    once = h.fragment_program(SOURCE.replace("void main() {", "void main() {\n    gWallCutOnce = true;"))
    rng = np.random.default_rng(733)
    worst_uniform = 0.0
    diffs = []
    for div in (2, 4, 6):
        width, height = 48, 32
        h.target(width, height, [h.rgba])
        for density in ("mixed", "uniform"):
            atlas, depth = fixture(rng, width, height, div, 1, "mixed")
            if density == "uniform":
                third = atlas.shape[1] // 3
                for k in range(3):
                    atlas[:, k * third:(k + 1) * third] = atlas[atlas.shape[0] // 2, k * third + third // 2]
                depth[:] = rng.uniform(5, 900, depth.shape)
            tex = h.texture(*h.rgba, atlas.shape[1], atlas.shape[0], atlas)
            scene = h.texture(*h.scene_format, width, height, np.stack((depth, depth), -1))
            args = (width, height, tex, scene, div, (0.25, 0.25), 0)
            four, one = draw(current, *args), draw(once, *args)
            ok = np.isfinite(four).all(-1) & np.isfinite(one).all(-1)
            d = np.abs(four[..., 3] - one[..., 3])[ok]
            if density == "uniform":
                worst_uniform = max(worst_uniform, float(d.max()))
            else:
                diffs.append(d)
            h.g.glDeleteTextures([tex, scene])
    d = np.concatenate(diffs)
    print(f"cut once: uniform taps max |dT| {worst_uniform:.2e}; random taps |dT| median {np.median(d):.3f}, p90 {np.percentile(d, 90):.3f}")
    if worst_uniform > 1e-4:
        raise AssertionError(f"cut once differs from the four cuts on uniform taps: {worst_uniform}")


if __name__ == "__main__":
    main()
