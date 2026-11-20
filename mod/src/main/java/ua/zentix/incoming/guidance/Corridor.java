package ua.zentix.incoming.guidance;

import ua.zentix.incoming.util.GridWalk;
import ua.zentix.incoming.util.Relief;

/**
 * Corridor over a ground path: terrain height to clear in every block column touched by a strip
 * of given half-width along the path polyline. No column is missed, even thin ones (post, mast, lamp): traverse
 * parallel lines spaced less than a block apart ({@link #LANE}), each through all columns it
 * intersects ({@link GridWalk}); a column touched by the strip but by no line would have to fit wholly between
 * adjacent lines, but is wider than that gap. Pure code without a Minecraft world.
 */
public final class Corridor {
    /** Traversal-line spacing, blocks: smaller than column width. */
    static final double LANE = 0.9;

    private Corridor() {}

    /**
     * Projectile climb before a column ahead: first {@code reaction} blocks of travel without climb (only establishing
     * it), then {@code gradient} blocks of height per block traveled.
     */
    public record Climb(double reaction, double gradient) {
        /** No climb: column counts in full regardless of distance. */
        public static final Climb NONE = new Climb(0, 0);

        /** Height projectile can gain over {@code along} blocks of travel. */
        public double gain(double along) {
            return gradient * Math.max(0, along - reaction);
        }
    }

    /**
     * Altitude projectile needs now to clear all corridor terrain while climbing by {@code climb}:
     * maximum across columns of “terrain − climb before column”. With {@link Climb#NONE}, just maximum
     * terrain height beneath corridor. Distance to column: projection of its center onto polyline (at least 0).
     *
     * @param track     ground path: x0, z0, x1, z1, … (one point — square around it)
     * @param halfWidth corridor half-width, blocks
     */
    public static double highest(double[] track, double halfWidth, Climb climb, Relief relief) {
        return both(track, halfWidth, climb, relief)[1];
    }

    /**
     * {@link #highest} with and without the climb in one walk over the corridor, and the column that sets the altitude
     * needed now.
     *
     * @return {highest terrain, altitude needed now with {@code climb}, that column's centre x, z and terrain height}
     */
    public static double[] both(double[] track, double halfWidth, Climb climb, Relief relief) {
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, 0, Double.NEGATIVE_INFINITY};
        int lanes = Math.max(1, (int) Math.ceil(2 * halfWidth / LANE));
        double travelled = 0;
        for (int i = 0; i == 0 || i + 3 < track.length; i += 2) {
            double ax = track[i], az = track[i + 1];
            double bx = i + 3 < track.length ? track[i + 2] : ax, bz = i + 3 < track.length ? track[i + 3] : az;
            double dx = bx - ax, dz = bz - az, len = Math.hypot(dx, dz);
            boolean point = len < 1e-9;
            if (point) {
                // Point: segment across the corridor itself.
                az -= halfWidth;
                bz += halfWidth;
                dx = 0;
                dz = 1;
                len = 1;
            }
            double ux = dx / len, uz = dz / len, nx = -uz, nz = ux;
            double sx = ax, sz = az, base = travelled;
            GridWalk.Visitor visit = (x, z) -> {
                double along = point ? 0 : Math.max(0, base + (x + 0.5 - sx) * ux + (z + 0.5 - sz) * uz);
                double h = relief.at(x, z);
                max[0] = Math.max(max[0], h);
                double needed = h - climb.gain(along);
                if (needed > max[1]) {
                    max[1] = needed;
                    max[2] = x + 0.5;
                    max[3] = z + 0.5;
                    max[4] = h;
                }
                return true;
            };
            for (int k = 0; k <= lanes; k++) {
                double off = -halfWidth + 2 * halfWidth * k / lanes;
                GridWalk.walk(ax + nx * off, az + nz * off, bx + nx * off, bz + nz * off, 1, visit);
            }
            if (!point) travelled += len;
        }
        return max;
    }
}
