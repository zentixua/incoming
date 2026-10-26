package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.nuclear.model.BlastModel;

/**
 * Sound physics without Minecraft (covered by unit tests): propagation delay and Doppler.
 * <p>
 * At {@code now}, the listener hears what the source emitted at {@code t}, where
 * |p(t) − L| = c·(now − t) — the “retarded time”. This gives both accurate Doppler (frequency rises while the source
 * approaches) and a distant listener still hearing the engine after the projectile has exploded, exactly until the front
 * arrives, as well as a supersonic projectile remaining inaudible until its Mach cone reaches the ear.
 */
public final class Acoustics {
    /** Speed of sound: 343 m/s at 20 °C (the table gives 343.2 — 2 ms over a kilometre) = 17.15 blocks per tick. */
    public static final double SPEED = BlastModel.SOUND_SPEED / 20;

    private Acoustics() {}

    /** Source trajectory: position at time t (ticks, fractional). */
    public interface Path {
        /** Position at time t; outside the known interval, the nearest endpoint. */
        void at(double t, double[] out);

        /** Earliest known time. */
        double start();

        /** Upper bound on the source's speed along the path, blocks/tick. */
        double maxSpeed();
    }

    /** Minimum root-search step, ticks: pairs of roots closer than this (Mach-cone tangency) are one instant. */
    private static final double MIN_STEP = 0.05;
    /** Maximum number of search steps. */
    private static final int MAX_STEPS = 400;

    /**
     * Emission times of what the listener at (lx, ly, lz) hears now: the roots of
     * f(t) = |p(t) − L| − c·(now − t) along the known path, latest first (at most {@code out.length}).
     * A subsonic source gives exactly one root (f increases: f′ = c − v<sub>r</sub> > 0) if its sound has arrived;
     * a supersonic source gives none outside the Mach cone and a pair inside: the late root (the source recedes,
     * pitch is lower) and the early root (sound emitted on approach arrives backwards). Search steps back from
     * {@code now}: f changes by at most c + v<sub>max</sub> per tick, so a step of |f| / (c + v<sub>max</sub>)
     * cannot skip a root; a sign change is resolved by bisection.
     *
     * @return number of roots found (0 — the sound has not arrived or the listener is outside the cone)
     */
    public static int emissions(Path path, double now, double lx, double ly, double lz, double[] out) {
        double[] p = new double[3];
        double k = path.maxSpeed() + SPEED, start = path.start();
        double t = now, f = distance(path, t, lx, ly, lz, p);
        int n = 0;
        for (int i = 0; i < MAX_STEPS && n < out.length && t > start; i++) {
            double t1 = Math.max(start, t - Math.max(Math.abs(f) / k, MIN_STEP));
            double f1 = f(path, now, lx, ly, lz, t1, p);
            if (f1 == 0 || f1 > 0 != f > 0) out[n++] = refine(path, now, lx, ly, lz, t1, f1, t, p);
            t = t1;
            f = f1;
        }
        // not enough steps (speed bound too high: path jumps between ticks) — continue in exact one-tick steps, same roots
        while (n < out.length && t > start) {
            double t1 = Math.max(start, t - 1);
            double f1 = f(path, now, lx, ly, lz, t1, p);
            if (f1 == 0 || f1 > 0 != f > 0) out[n++] = refine(path, now, lx, ly, lz, t1, f1, t, p);
            t = t1;
            f = f1;
        }
        return n;
    }

    /**
     * Root of f between a (f(a) = fa) and b: bisect to a thousandth of a tick, then use a chord (between ticks
     * the path is straight, f is nearly linear — the chord converges immediately).
     */
    private static double refine(Path path, double now, double lx, double ly, double lz, double a, double fa, double b, double[] p) {
        double fb = f(path, now, lx, ly, lz, b, p);
        for (int i = 0; i < 40; i++) {
            if (fa == 0) return a;
            if (fb == 0) return b;
            double m = b - a > 1.0e-3 ? (a + b) / 2 : b - fb * (b - a) / (fb - fa);
            if (b - a <= 1.0e-9) return m;
            double fm = f(path, now, lx, ly, lz, m, p);
            if (Math.abs(fm) < 1.0e-9) return m;
            if (fm > 0 == fa > 0) {
                a = m;
                fa = fm;
            } else {
                b = m;
                fb = fm;
            }
        }
        return (a + b) / 2;
    }

    private static double f(Path path, double now, double lx, double ly, double lz, double t, double[] p) {
        return distance(path, t, lx, ly, lz, p) - SPEED * (now - t);
    }

    /**
     * Emission time of what the listener hears now: the late root ({@link #emissions}). A subsonic source has
     * just one; for a supersonic source this is the receding source's sound (the early root, emitted on approach,
     * plays backwards — sound cannot be played that way). {@code NaN} means sound has not arrived: nothing plays before arrival.
     */
    public static double emissionTime(Path path, double now, double lx, double ly, double lz) {
        double[] roots = new double[1];
        return emissions(path, now, lx, ly, lz, roots) > 0 ? roots[0] : Double.NaN;
    }

    private static double distance(Path path, double t, double lx, double ly, double lz, double[] p) {
        path.at(t, p);
        return Math.sqrt(sq(p[0] - lx) + sq(p[1] - ly) + sq(p[2] - lz));
    }

    /**
     * Frequency multiplier for a moving source and a stationary listener: f' = f · c / (c − v·n),
     * where n is the unit vector from source to ear, v is source velocity (blocks/tick). Unbounded: at the late root
     * ({@link #emissionTime}), c − v·n > 0 always, and a receding supersonic source has a lower pitch; a negative value
     * is sound emitted while approaching faster than sound (backwards). Exactly on the Mach cone (c = v·n), infinity:
     * there the sound arrives all at once, as an N-wave ({@code SonicBoom}).
     */
    public static double doppler(double vx, double vy, double vz, double nx, double ny, double nz) {
        double radial = vx * nx + vy * ny + vz * nz;
        double gap = SPEED - radial;
        if (Math.abs(gap) < 1.0e-9) gap = Math.copySign(1.0e-9, gap);
        return SPEED / gap;
    }

    /** Smooth 0→1 step between a and b. */
    public static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static double sq(double x) {
        return x * x;
    }
}
