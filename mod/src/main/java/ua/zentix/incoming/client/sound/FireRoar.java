package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectrum;

import java.util.Arrays;

/**
 * Fire roar at the ear from source law, without Minecraft (covered by a unit test): each fire has heat release,
 * W; fraction {@link Sources#FLAME_EFFICIENCY} becomes sound (turbulent flame noise, {@link Sources#flame}); fires
 * at different sites are incoherent, so powers add. Path to ear is ISO 9613 ({@link Propagation}), volume is
 * {@link Loudness}.
 * <p>
 * A burning city has tens of thousands of fires (storm cells and fire in local chunks), so paths are not computed
 * individually: fires go into {@link #QUADRANTS} quarters around the ear (cos²/sin² between neighbours, like roar
 * loops), and logarithmic distance rings ({@link #RINGS_PER_OCTAVE} per doubling). A ring sums spreading exactly,
 * Σ P/(4πd²); air, ground and shadow use distance weighted by arriving strength: within a ring they vary by less
 * than a tenth of a decibel per hundred metres at the highest octave.
 */
public final class FireRoar {
    public static final int QUADRANTS = 4;
    /** Rings per distance doubling. */
    static final int RINGS_PER_OCTAVE = 4;
    /** Rings from one metre to 2¹⁶ m (65 km, beyond {@code Hearing.RANGE}). */
    static final int RINGS = 16 * RINGS_PER_OCTAVE + 1;
    /** Closer counts as this many metres: a fire is a burning face near the ear, not a point. */
    static final double NEAR = 1;

    /** Ring sum: P/d² (W/m²·4π), the same × d and × fire height above ear. */
    private final double[] power = new double[QUADRANTS * RINGS], dist = new double[QUADRANTS * RINGS], height = new double[QUADRANTS * RINGS];
    private final double[] bands = new double[Propagation.BANDS], ring = new double[Propagation.BANDS];
    private final double[] level = new double[QUADRANTS], margin = new double[QUADRANTS], distance = new double[QUADRANTS];

    public void clear() {
        Arrays.fill(power, 0);
        Arrays.fill(dist, 0);
        Arrays.fill(height, 0);
    }

    /** Fire with heat release {@code watts}, W, at (dx, dy, dz) from ear, m. */
    public void add(double dx, double dy, double dz, double watts) {
        if (!(watts > 0)) return;
        double d = Math.max(NEAR, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double p = Sources.FLAME_EFFICIENCY * watts / (d * d);
        int r = Math.min(RINGS - 1, (int) Math.floor(RINGS_PER_OCTAVE * Math.log(d) / Math.log(2)));
        double az = Math.atan2(dz, dx), sector = 2 * Math.PI / QUADRANTS;
        double u = (az < 0 ? az + 2 * Math.PI : az) / sector;
        int q0 = (int) Math.floor(u) % QUADRANTS, q1 = (q0 + 1) % QUADRANTS;
        double frac = u - Math.floor(u), c = Math.cos(frac * Math.PI / 2), s = Math.sin(frac * Math.PI / 2);
        put(q0 * RINGS + r, p * c * c, d, dy);
        put(q1 * RINGS + r, p * s * s, d, dy);
    }

    private void put(int k, double p, double d, double dy) {
        power[k] += p;
        dist[k] += p * d;
        height[k] += p * dy;
    }

    /**
     * Quarter ear levels for a recording with {@code shape} in {@code weather}: A-level ({@link #level}), headroom
     * above background in the best octave ({@link #margin}) and strength-weighted distance ({@link #distance}).
     */
    public void finish(Spectrum shape, Propagation.Weather weather) {
        for (int q = 0; q < QUADRANTS; q++) {
            Arrays.fill(bands, 0);
            double all = 0, far = 0;
            for (int r = 0; r < RINGS; r++) {
                int k = q * RINGS + r;
                if (power[k] <= 0) continue;
                double d = dist[k] / power[k], hs = Math.max(0, height[k] / power[k] + Propagation.EAR);
                // power with the same spreading at distance d as the ring's summed fires
                double lw = Sources.lw(power[k] * d * d);
                Propagation.level(lw, shape, Propagation.Path.over(d, hs, weather), ring);
                for (int b = 0; b < bands.length; b++) bands[b] += Math.pow(10, ring[b] / 10);
                all += power[k];
                far += dist[k];
            }
            if (all <= 0) {
                level[q] = Double.NEGATIVE_INFINITY;
                margin[q] = Double.NEGATIVE_INFINITY;
                distance[q] = 0;
                continue;
            }
            double sum = 0;
            for (int b = 0; b < bands.length; b++) {
                sum += bands[b];
                ring[b] = 10 * Math.log10(Math.max(bands[b], 1e-30));
            }
            level[q] = 10 * Math.log10(Math.max(sum, 1e-30));
            margin[q] = Propagation.margin(ring, weather);
            distance[q] = far / all;
        }
    }

    /** A-level at ear in quarter {@code q} after {@link #finish}, dBA. */
    public double level(int q) {
        return level[q];
    }

    public double margin(int q) {
        return margin[q];
    }

    /** Strength-weighted distance to the quarter's fires, m. */
    public double distance(int q) {
        return distance[q];
    }

    /** Game volume of quarter {@code q} for a {@code fileLufs} file, ceiling {@code ceilingLufs}. */
    public float volume(int q, double fileLufs, double ceilingLufs) {
        return Loudness.volume(Loudness.gain(level[q], margin[q], ceilingLufs), fileLufs);
    }
}
