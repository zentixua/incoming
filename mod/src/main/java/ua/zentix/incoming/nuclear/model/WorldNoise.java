package ua.zentix.incoming.nuclear.model;

import ua.zentix.incoming.util.noise.FastNoiseLite;

/**
 * Smooth random terrain field for damage curves ({@link Fragility}): why grass burned on one slope but
 * not its neighbor (moisture, density, peat, wind). OpenSimplex2 fBm (4 octaves: patches from ~90 m to ~10 m) with domain
 * warping (ragged patch edges, not round), normalized to mean 0 and spread 1, like {@code z ~ N(0, 1)}.
 * Coordinates are world blocks, patch sizes are model meters ({@code blocksPerMetre}), but large patches are at least
 * {@link #MIN_PATCH_BLOCKS} blocks: otherwise a small strike scale would shrink patches to grain. The field depends only on seed and
 * position: a chunk's ruin plan is identical in any order and on any thread. An instance is read-only after construction
 * (FastNoiseLite has no mutable state during reads), shared by ruin threads.
 */
public final class WorldNoise {
    /** Measured spread of OpenSimplex2 fBm (4 octaves, gain 0.5) after warping ({@code WorldNoiseTest}). */
    static final double STD = 0.333;
    /** Largest patch size, m. */
    static final double PATCH_M = 90;
    /** Large patches are at least this many blocks across. */
    static final double MIN_PATCH_BLOCKS = 12;
    /** Warping: displacement up to ~30 m, wavelength ~150 m. */
    private static final double WARP_M = 30, WARP_WAVE_M = 150;

    private final FastNoiseLite noise = new FastNoiseLite(), warp = new FastNoiseLite();
    private final double metresPerBlock;

    public WorldNoise(long seed, double blocksPerMetre) {
        this.metresPerBlock = Math.min(1 / blocksPerMetre, PATCH_M / MIN_PATCH_BLOCKS);
        int s = (int) (seed ^ seed >>> 32);
        noise.SetSeed(s);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency((float) (1 / PATCH_M));
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(4);
        noise.SetFractalLacunarity(2f);
        noise.SetFractalGain(0.5f);
        warp.SetSeed(s * 31 + 7);
        warp.SetDomainWarpType(FastNoiseLite.DomainWarpType.OpenSimplex2);
        warp.SetDomainWarpAmp((float) WARP_M);
        warp.SetFrequency((float) (1 / WARP_WAVE_M));
    }

    /** Field at a world position (x, z in blocks), ~N(0, 1); {@code layer} is independent (different consequences have different patches). */
    public double at(double x, double z, int layer) {
        FastNoiseLite.Vector2 p = new FastNoiseLite.Vector2(x * metresPerBlock + layer * 7919.0, z * metresPerBlock - layer * 6271.0);
        warp.DomainWarp(p);
        return noise.GetNoise(p.x, p.y) / STD;
    }

    /**
     * The same in volume (x, y, z in blocks): patches on walls rather than columns through the full building height. The 3D noise spread is
     * of the same order as the planar field ({@code WorldNoiseTest}), ~N(0, 1) to within tens of percent: sufficient for
     * scorch patches.
     */
    public double at(double x, double y, double z, int layer) {
        FastNoiseLite.Vector3 p = new FastNoiseLite.Vector3(x * metresPerBlock + layer * 7919.0, y * metresPerBlock + layer * 3301.0,
                z * metresPerBlock - layer * 6271.0);
        warp.DomainWarp(p);
        return noise.GetNoise(p.x, p.y, p.z) / STD;
    }
}
