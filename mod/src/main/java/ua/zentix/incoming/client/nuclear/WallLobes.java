package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;

/**
 * Dust-wall head as an explicit field of billow lobes (copy of the {@code pallLobe*} functions in {@code pall.glsl}): everything that
 * reads the head reads these lobes — the march draws them, the layer quads, source probes and composite take their optical depth
 * ({@code pallWallTau}), and the eye's own dust ({@link NukePall}) their density. A lobe is a parcel of dust lofted at the front: a cell of a
 * lattice in the ground's ring coordinates (where the front threw it up), carried outward by the wind behind the front at its height share
 * ({@link BlastWall#origin}'s law, inverted) and riding its share of the local top ({@link BlastWall#top}, {@link BlastWall#heated}), so it
 * is thrown up at the front, lags behind it and rises ever slower: the roll-back is the lobes' own motion. Its mass is the law's head
 * column above the ground at its place ({@link BlastWall#mean} integrated over height) times its cell's area there, so the lobes carry
 * the law's head mass and its handoff to the pall ({@link BlastWall#handoff}). A lobe's density is A·(1 − q²)² with q its normalized
 * distance (forward, back, across and up radii): the chord is closed-form, so rays and probes sum the same optical depths.
 *
 * <p>Presentation (marked in the shader too): the spacing ({@link #LOBE}, Silo S3E09: crests 1.5–2 band heights wide), radii, centre
 * height shares, jitter and size spread. Where and how much dust there is stays the law's.
 */
final class WallLobes {
    /** Lobe spacing, wedge-end tops (presentation). */
    static final double LOBE = 1.25;
    /** Radius across the ring and backward, spacings; up, local tops (presentation). */
    static final double WIDE = 0.75, TALL = 0.6;
    /** Centres off the lattice, spacings; centre height share (from, range); size spread (presentation). */
    static final double JITTER = 0.6, LOW = 0.25, RISE = 0.2, SIZE = 0.3;
    /** Walk samples per span at most; a walk stops behind this optical depth of lobes already passed. */
    static final int WALK = 40;
    static final double OPAQUE = 9;
    /** ∫ of (1 − q²)² over the unit half-ball: a half-lobe's mass is this × its three radii × A. */
    static final double HALF = 16 * Math.PI / 105;

    private WallLobes() {
    }

    /** Ground under the head: at a point relative to ground zero (blocks) — its terrain above ground zero, water, hard and sand shares. */
    interface Ground {
        Ground FLAT = (x, z, out) -> java.util.Arrays.fill(out, 0, 4, 0);

        void at(double relX, double relZ, double[] out);

        /** The pall map of a detonation at ground zero (bx, bz), as the shader reads it (water, soil only where the map has them). */
        static Ground of(PallGround g, double bx, double bz) {
            boolean water = g.hasWater(), soil = g.hasHard() || g.hasSand();
            return (x, z, out) -> {
                double wx = bx + x, wz = bz + z;
                out[0] = g.at(wx, wz);
                out[1] = water ? Mth.clamp(g.water(wx, wz), 0, 1) : 0;
                out[2] = soil ? Mth.clamp(g.hard(wx, wz), 0, 1) : 0;
                out[3] = soil ? Mth.clamp(g.sand(wx, wz), 0, 1) : 0;
            };
        }
    }

    /**
     * A lobe: centre relative to ground zero (x, y above ground zero's ground, z), outward unit (ex, ez), radii forward, back, across, up
     * (blocks), peak extinction {@code a} (1/block; 0 — none), ground material under it and its wedge share.
     */
    record Lobe(double x, double y, double z, double ex, double ez, double front, double back, double across, double up, double a,
                double wet, double hard, double sand, double u, double mass) {
        static final Lobe NONE = new Lobe(0, 0, 0, 1, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0);
    }

    /** Lattice of a wall slot: spacing (blocks, a power of two) and the next octave's share. Copy {@code pallLobeTile}. */
    static double[] tile(float[] slot) {
        double k = Math.log(Math.max(LOBE * slot[2], 1)) / Math.log(2) + 0.5, k0 = Math.floor(k);
        return new double[] {Math.pow(2, k0), BlastWall.smooth(0.85, 1, k - k0)};
    }

    /** Cells around the ring in lattice row j. Copy {@code pallLobeRow}. */
    static double row(int j) {
        return Math.max(Math.floor(2 * Math.PI * (j + 0.5) + 0.5), 3);
    }

    /** pcg4d hash of cell (i, j) at octave level: four numbers in [0, 1). Copy {@code pallLobeHash}. */
    static double[] hash(int i, int j, int level) {
        int x = i * 1664525 + 1013904223, y = j * 1664525 + 1013904223, z = level * 1664525 + 1013904223;
        int w = (i ^ (j << 12)) * 1664525 + 1013904223;
        x += y * w;
        y += z * x;
        z += x * y;
        w += y * z;
        x ^= x >>> 16;
        y ^= y >>> 16;
        z ^= z >>> 16;
        w ^= w >>> 16;
        x += y * w;
        y += z * x;
        z += x * y;
        w += y * z;
        return new double[] {(x >>> 8) / 16777216.0, (y >>> 8) / 16777216.0, (z >>> 8) / 16777216.0, (w >>> 8) / 16777216.0};
    }

    /** Share of a lobe's mass above a plane ζ vertical radii above its centre. Copy {@code pallLobeAbove}. */
    static double above(double zeta) {
        double y = Mth.clamp(zeta, -1, 1), y2 = y * y;
        return (16.0 / 35 - y * (1 - y2 + y2 * (0.6 * y2 - y2 * y2 / 7))) / (32.0 / 35);
    }

    /** Lobe of cell (i, j) in the lattice of spacing D and share weight. Copy {@code pallLobe}. */
    static Lobe lobe(float[] slot, Ground ground, int i, int j, double D, double weight) {
        if (j < 0 || weight <= 0 || slot[3] <= 0) return Lobe.NONE;
        double n = row(j);
        double[] h = hash(i, j, (int) Math.round(Math.log(D) / Math.log(2)));
        double ro = (j + 0.5 + JITTER * (h[0] - 0.5)) * D, angle = (i + 0.5 + JITTER * (h[1] - 0.5)) * 2 * Math.PI / n;
        double zr = LOW + RISE * h[2], size = 1 + SIZE * (h[3] - 0.5);
        double len = Math.max(slot[1], 1e-3), carry = slot[4] * Math.pow(zr, 1.0 / 7), r = ro;
        for (int k = 0; k < 3; k++) r = ro + carry * BlastWall.carriedShare((slot[0] - r) / len * slot[8]);
        double ex = Math.cos(angle), ez = Math.sin(angle);
        double[] g = new double[4];
        ground.at(ex * r, ez * r, g);
        double wet = g[1], hard = g[2], sand = g[3];
        double s = slot[0] - r - slot[5] * wet, u = s / len;
        if (u <= 0 || u >= BlastWall.HANDOFF_TO) return Lobe.NONE;
        double ph = u * slot[8], top = slot[2] * (1 + (slot[6] - 1) * wet), rise = BlastWall.top(u, slot[8]);
        double local = Math.max(top * rise * BlastWall.heated(ph, slot[11]), 0.5);
        double column = BlastWall.density(slot, s, wet, hard) * BlastWall.shape(u, slot[8]) * BlastWall.handoff(u) * top * rise
                * PallField.column(0);
        if (column <= 0) return Lobe.NONE;
        // a young lobe flat against the curving ring would reach past it at its sides: across² ≤ edge·s and nose ≤ s keep it behind
        double morph = Math.cbrt(weight), wide = WIDE * D * size * morph, across = Math.min(wide, Math.sqrt(Math.max((r + s) * s, 0.25)));
        double front = Mth.clamp(s, 0.5, wide), up = Math.max(TALL * local * size * morph, 0.5);
        double zc = zr * local;
        double stretch = 1 / Math.max(1 + carry * (1 - ph) * Math.exp(1 - ph) * slot[8] / len, 0.2);
        double area = D * 2 * Math.PI * r / n * stretch;
        double mass = column * area * weight / Math.max(above(-zc / up), 0.05);
        double a = mass / (HALF * across * up * (front + wide));
        return new Lobe(ex * r, g[0] + zc, ez * r, ex, ez, front, wide, across, up, a, wet, hard, sand, u, mass);
    }

    /** ∫(c0 − B·x²)² over the part of [lo, hi] inside one half-lobe. Copy {@code pallLobePart}. */
    private static double part(double ax, double ay, double az, double bx, double by, double bz, double lo, double hi) {
        double B = bx * bx + by * by + bz * bz;
        if (B < 1e-12 || hi <= lo) return 0;
        double ts = -(ax * bx + ay * by + az * bz) / B, c0 = 1 - (ax * ax + ay * ay + az * az) + B * ts * ts;
        if (c0 <= 0) return 0;
        double w = Math.sqrt(c0 / B), x0 = Math.max(lo, ts - w) - ts, x1 = Math.min(hi, ts + w) - ts;
        if (x1 <= x0) return 0;
        return g(x1, c0, B) - g(x0, c0, B);
    }

    private static double g(double x, double c0, double B) {
        return x * (c0 * c0 + B * x * x * (0.2 * B * x * x - 2.0 / 3 * c0));
    }

    /** Optical depth of a lobe along o + t·d (relative to ground zero) over [ta, tb]. Copy {@code pallLobeTau}. */
    static double tau(Lobe l, double ox, double oy, double oz, double dx, double dy, double dz, double ta, double tb) {
        if (l.a <= 0 || tb <= ta) return 0;
        double qx = ox - l.x, qy = oy - l.y, qz = oz - l.z;
        double ar = qx * l.ex + qz * l.ez, at = -qx * l.ez + qz * l.ex, br = dx * l.ex + dz * l.ez, bt = -dx * l.ez + dz * l.ex;
        double cut = Math.abs(br) > 1e-9 ? -ar / br : ar >= 0 ? -1e30 : 1e30;
        double aheadLo, aheadHi, behindLo, behindHi;
        if (br > 1e-9) {
            aheadLo = cut;
            aheadHi = 1e30;
            behindLo = -1e30;
            behindHi = cut;
        } else if (br < -1e-9) {
            aheadLo = -1e30;
            aheadHi = cut;
            behindLo = cut;
            behindHi = 1e30;
        } else if (ar >= 0) {
            aheadLo = -1e30;
            aheadHi = 1e30;
            behindLo = 1e30;
            behindHi = -1e30;
        } else {
            aheadLo = 1e30;
            aheadHi = -1e30;
            behindLo = -1e30;
            behindHi = 1e30;
        }
        return l.a * (part(ar / l.front, at / l.across, qy / l.up, br / l.front, bt / l.across, dy / l.up, Math.max(ta, aheadLo), Math.min(tb, aheadHi))
                + part(ar / l.back, at / l.across, qy / l.up, br / l.back, bt / l.across, dy / l.up, Math.max(ta, behindLo), Math.min(tb, behindHi)));
    }

    /** Density of a lobe at a point relative to ground zero, 1/block. Copy {@code pallLobeAt}. */
    static double at(Lobe l, double x, double y, double z) {
        if (l.a <= 0) return 0;
        double qx = x - l.x, qy = y - l.y, qz = z - l.z, r = qx * l.ex + qz * l.ez, t = -qx * l.ez + qz * l.ex;
        double nr = r / (r >= 0 ? l.front : l.back), nt = t / l.across, ny = qy / l.up;
        double c = Math.max(1 - nr * nr - nt * nt - ny * ny, 0);
        return l.a * c * c;
    }

    /** Highest a lobe reaches above its terrain, blocks. Copy {@code pallLobeCeiling}. */
    static double ceiling(float[] slot) {
        double p = BlastWall.LOFT_GAIN * Math.max(slot[8], 1e-3);
        return slot[2] / (p >= 1 ? ua.zentix.incoming.nuclear.model.DustModel.loftTop(p) : 1) * BlastWall.heated(BlastWall.HANDOFF_TO * slot[8], slot[11])
                * (LOW + RISE + TALL * (1 + 0.5 * SIZE));
    }

    /** Farthest a lobe reaches behind its centre, blocks. Copy {@code pallLobeReach}. */
    static double reach(float[] slot) {
        return 2 * tile(slot)[0] * WIDE * (1 + 0.5 * SIZE);
    }

    /**
     * Lobes near the ray o + t·d (relative to ground zero) on [ta, tb]: their optical depth there, as {@code pallLobeWalk} (samples half a
     * spacing apart, 3×3 cells around each sample's parcel cell, both lattices, stopping once opaque behind).
     */
    static double walk(float[] slot, Ground ground, double ox, double oy, double oz, double dx, double dy, double dz, double ta, double tb) {
        if (slot[3] <= 0 || tb <= ta) return 0;
        double[] tile = tile(slot);
        double tau = 0, behind = 0, len = Math.max(slot[1], 1e-3), end = tb;
        double carry = slot[4] * Math.pow(LOW + 0.5 * RISE, 1.0 / 7);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int lv = 0; lv < 2; lv++) {
            double weight = lv == 0 ? 1 - tile[1] : tile[1];
            if (weight <= 0) continue;
            double D = lv == 0 ? tile[0] : 2 * tile[0];
            int samples = (int) Mth.clamp(Math.ceil((tb - ta) / (0.5 * D)), 1, WALK);
            double ds = (tb - ta) / samples;
            seen.clear();
            for (int m = 0; m < samples; m++) {
                double t = ta + (m + 0.5) * ds;
                if (t > end + ds) break;
                if (behind > OPAQUE) {
                    end = Math.min(end, t);
                    break;
                }
                double rx = ox + dx * t, rz = oz + dz * t, r = Math.hypot(rx, rz), a = Math.atan2(rz, rx);
                if (a < 0) a += 2 * Math.PI;
                double ro = r - carry * BlastWall.carriedShare((slot[0] - r) / len * slot[8]);
                int j0 = (int) Math.floor(ro / D);
                for (int dj = -1; dj <= 1; dj++) {
                    int j = j0 + dj;
                    if (j < 0) continue;
                    double n = row(j);
                    int i0 = (int) Math.floor(a / (2 * Math.PI) * n);
                    for (int di = -1; di <= 1; di++) {
                        int i = (int) (((i0 + di) % n + n) % n);
                        if (!seen.add((long) j << 32 | i)) continue;
                        Lobe l = lobe(slot, ground, i, j, D, weight);
                        double lt = tau(l, ox, oy, oz, dx, dy, dz, ta, tb);
                        if (lt <= 1e-4) continue;
                        tau += lt;
                        double tc = (l.x - ox) * dx + (l.y - oy) * dy + (l.z - oz) * dz;
                        if (tc < t) behind += lt;
                    }
                }
            }
        }
        return tau;
    }

    /**
     * Head optical depth up from a point relative to ground zero (y above ground zero's ground) to the lobes' highest reach, and down from it
     * to the terrain: {up, down}.
     */
    static double[] vertical(float[] slot, Ground ground, double x, double y, double z) {
        double[] g = new double[4];
        ground.at(x, z, g);
        double high = g[0] + ceiling(slot);
        return new double[] {walk(slot, ground, x, y, z, 0, 1, 0, 0, Math.max(high - y, 0)), walk(slot, ground, x, g[0], z, 0, 1, 0, 0, Math.max(y - g[0], 0))};
    }

    /** Head extinction at a point relative to ground zero (y above ground zero's ground), 1/block: the lobes around it. */
    static double sigma(float[] slot, Ground ground, double x, double y, double z) {
        if (slot[3] <= 0) return 0;
        double[] tile = tile(slot);
        double len = Math.max(slot[1], 1e-3), r = Math.hypot(x, z), a = Math.atan2(z, x), sum = 0;
        if (a < 0) a += 2 * Math.PI;
        double carry = slot[4] * Math.pow(LOW + 0.5 * RISE, 1.0 / 7);
        double ro = r - carry * BlastWall.carriedShare((slot[0] - r) / len * slot[8]);
        for (int lv = 0; lv < 2; lv++) {
            double weight = lv == 0 ? 1 - tile[1] : tile[1];
            if (weight <= 0) continue;
            double D = lv == 0 ? tile[0] : 2 * tile[0];
            int j0 = (int) Math.floor(ro / D);
            for (int j = Math.max(j0 - 1, 0); j <= j0 + 1; j++) {
                double n = row(j);
                int i0 = (int) Math.floor(a / (2 * Math.PI) * n);
                for (int di = -1; di <= 1; di++) sum += at(lobe(slot, ground, (int) (((i0 + di) % n + n) % n), j, D, weight), x, y, z);
            }
        }
        return sum;
    }
}
