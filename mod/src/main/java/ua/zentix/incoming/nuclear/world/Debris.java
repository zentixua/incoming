package ua.zentix.incoming.nuclear.world;

/**
 * Where broken and collapsed material lands. A block removed by the wave or by collapse falls from its height above
 * the post-ruin ground while the blast wind behind the front drags it away from the burst; it lands as the same
 * material ({@link #SOLID_SHARE} of the blocks), so a building ends as a pile of its own blocks at its base, shifted
 * downwind.
 * <p>
 * Drag on a fragment: a = ½·ρ_air·C_d·(u − v)²·A/m; for a cube fragment of side s and density ρ, A/m = 1/(ρ·s)
 * (ρ_air 1.2 kg/m³, C_d 1). Peak wind u₀ behind the front is Rankine–Hugoniot
 * ({@link ua.zentix.incoming.nuclear.model.BlastModel#windSpeed}) and decays over the positive phase; the fragment flies
 * for the fall time √(2h/g) plus a short skid along the ground. A Minecraft block is a cubic metre,
 * real debris is fragments: drift uses the fragment size, the landing block stays the whole block.
 * <p>
 * Local spread: a collapsing storey does not fall straight down; its material spreads around the footprint, more for a
 * taller fall. Displacements beyond {@link #REACH} leave the chunk window: that material is exported, and
 * {@link #importFraction} brings the matching share from upstream (see there).
 */
final class Debris {
    /** Farthest displacement (Chebyshev, blocks) resolved block by block: the exact collapse ring around a chunk. */
    static final int REACH = Collapse.C;
    /**
     * Share of a fallen block that lands as a rubble block (est.). A Minecraft wall or floor is a metre of solid material
     * where a real wall or slab is 0.15–0.3 m, and rubble swells ×1.3–1.5 when broken: 0.12–0.3 of the block; the low end,
     * since part of a collapsing building turns to dust and fines. A 15-block house column leaves about 2 blocks.
     */
    static final double SOLID_SHARE = 0.12;
    /** Typical fragment size, m (est.: broken masonry and concrete, 10–30 cm pieces). */
    static final double FRAGMENT_M = 0.2;
    /** Fragment density, kg/m³: masonry/concrete (est. 1800–2400) and wood (est. 500–700). */
    static final double MASONRY = 2000, WOOD = 600;
    /** ½·ρ_air·C_d: air 1.2 kg/m³, blunt fragment C_d ≈ 1. */
    private static final double HALF_RHO_CD = 0.6;
    private static final double G = 9.81;
    /** Slide and bounce after landing, s (est.). */
    static final double SKID_S = 0.3;
    /** Spread of a falling storey around its column, blocks: σ = 0.15·h + 0.5, at most 4 (est.: debris field ≈ footprint plus a few metres). */
    static final double SPREAD_PER_BLOCK = 0.15, SPREAD_MIN = 0.5, SPREAD_MAX = 4;

    private Debris() {}

    /** Integration step, s. */
    private static final double DT = 0.02;

    /**
     * Horizontal drift away from the burst, m. The wind behind the front decays over the positive phase,
     * u(t) = u₀·(1 − t/t₊)·e^(−t/t₊) (Friedlander form); drag acts on the fragment's speed relative to the air.
     *
     * @param heightM  fall height above the landing ground, m
     * @param windMs   wind behind the front, m/s
     * @param phaseS   positive phase (wind duration), s
     * @param density  fragment density, kg/m³
     */
    static double driftMetres(double heightM, double windMs, double phaseS, double density) {
        if (!(windMs > 0) || !(phaseS > 0)) return 0;
        double fall = Math.sqrt(2 * Math.max(0, heightM) / G) + SKID_S, k = HALF_RHO_CD / (density * FRAGMENT_M);
        double v = 0, x = 0;
        for (double t = 0; t < fall; t += DT) {
            double step = Math.min(DT, fall - t), tau = t / phaseS;
            double u = tau < 1 ? windMs * (1 - tau) * Math.exp(-tau) : 0, rel = u - v;
            v += k * rel * Math.abs(rel) * step;
            x += v * step;
        }
        return x;
    }

    /** Spread σ of a fall from {@code heightBlocks}, blocks. */
    static double spread(double heightBlocks) {
        return Math.min(SPREAD_MAX, SPREAD_PER_BLOCK * Math.max(0, heightBlocks) + SPREAD_MIN);
    }

    /**
     * Share of exported material that a chunk receives from upstream. Material leaving radius r' by drift L lands at
     * r = r' + L; a ring at r is wider than the ring it came from by r'/r, so with the city around similar to this chunk
     * the chunk gets back (r − L)/r of what it exported: near the epicentre (r < L) the ground is swept, farther the
     * flows balance. Local material stands in for the upstream material (statistical, no cross-chunk record).
     */
    static double importFraction(double radiusBlocks, double driftBlocks) {
        if (!(radiusBlocks > 0)) return 0;
        return Math.max(0, Math.min(1, (radiusBlocks - driftBlocks) / radiusBlocks));
    }

    /**
     * Where landed material comes to rest. Rubble is loose: a block cannot stand more than one block above a neighboring
     * column (angle of repose about 45°, broken masonry 35–45°), so a block landing on rubble that would make a steeper
     * step rolls to the lowest lower neighbor (4-neighbors in this chunk; chunk edges hold, no cross-chunk record) until
     * it rests on bare ground or in a hollow. Only the pile rolls: a block landing on bare ground stays there, so terrain
     * slopes do not carry material down to the chunk edge. Without this, everything landing on one column (a block of
     * flats with a chest on every floor at the same spot) stood as a one-block tower. A column whose ground is covered
     * ({@code open} false: a standing trunk, a surviving block on the ground, the fireball) takes no rolling material;
     * material landing on it rolls to its lowest open neighbor. Entries are taken in {@link Collapse#landed} order (lower
     * sources first), so the result is the same whoever plans the chunk.
     *
     * @param ground post-ruin ground per column ({@link Integer#MIN_VALUE} none: material landing there is dropped)
     * @param open   columns whose pile can take material, null all with ground
     * @param from   filled: first index into the returned order for each resting column, {@code from[256]} the end
     * @return landed entry indices grouped by resting column
     */
    static int[] repose(int[] ground, @org.jetbrains.annotations.Nullable boolean[] open, it.unimi.dsi.fastutil.longs.LongArrayList landed, int[] from) {
        int n = landed.size();
        int[] top = ground.clone(), rest = new int[n], count = new int[257];
        for (int k = 0; k < n; k++) {
            int c = (int) (landed.getLong(k) >>> 40);
            if (open != null && !open[c] && ground[c] != Integer.MIN_VALUE) {
                int best = lowest(top, open, c, Integer.MAX_VALUE);
                if (best >= 0) c = best;
            }
            if (top[c] != Integer.MIN_VALUE && (open == null || open[c])) {
                for (int best; top[c] > ground[c] && (best = lowest(top, open, c, top[c])) >= 0; ) c = best;
                top[c]++;
            }
            rest[k] = c;
            count[c + 1]++;
        }
        for (int c = 0; c < 256; c++) count[c + 1] += count[c];
        System.arraycopy(count, 0, from, 0, 257);
        int[] order = new int[n];
        for (int k = 0; k < n; k++) order[count[rest[k]]++] = k;
        return order;
    }

    /** Lowest open 4-neighbor of column {@code c} in the chunk with pile top below {@code below}, or -1. */
    private static int lowest(int[] top, @org.jetbrains.annotations.Nullable boolean[] open, int c, int below) {
        int lx = c & 15, lz = c >> 4, best = -1;
        for (int dir = 0; dir < 4; dir++) {
            int nx = lx + (dir == 0 ? 1 : dir == 1 ? -1 : 0), nz = lz + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
            int nc = nz << 4 | nx;
            if (top[nc] == Integer.MIN_VALUE || open != null && !open[nc] || top[nc] >= below) continue;
            if (best < 0 || top[nc] < top[best]) best = nc;
        }
        return best;
    }
}
