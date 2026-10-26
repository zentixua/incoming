package ua.zentix.incoming.nuclear.model;

/**
 * Conventional charge shock front over time, without Minecraft (meters, seconds): front pressure — Kinney–Graham
 * ({@link BlastModel#overpressureKpa}: reflected hemisphere on the surface, free sphere in air), front speed —
 * Rankine–Hugoniot ({@link BlastModel#frontSpeed}), arrival at range R — t(R) = ∫₀ᴿ dr / U(p(r)). Positive-phase length
 * behind the front is front t₊ ({@link BlastModel#chemicalPhaseSeconds}) × front speed (⚠ phase tail travels at sound
 * speed, front faster: length overestimated by (U − c)/c — ~10 % at 3 psi; phase formula near the charge, Z below ~0.5,
 * is not realistic — the front is inside the fireball and invisible there). Nodes use a logarithmic grid from
 * {@link #FIRST_M}, linear interpolation between them; beyond the table, sound speed (front is already sound there).
 * <p>
 * ⚠ Kinney–Graham is an experimental fit; at the charge (Z below ~0.1 m/kg^⅓) it is an extrapolation, not product gas
 * dynamics: early milliseconds while the front is still inside the fireball and invisible are rough. An airburst uses
 * a free-air sphere even at the ground: no reflected wave or Mach stem is modeled for a conventional airburst.
 */
public final class ChemicalFront {
    private static final int NODES = 600;
    /** First node, m: closer in, one segment from the charge. */
    static final double FIRST_M = 0.05;

    public final double tntKg;
    public final boolean surface;
    private final double[] range, time;

    private ChemicalFront(double tntKg, boolean surface, double[] range, double[] time) {
        this.tntKg = tntKg;
        this.surface = surface;
        this.range = range;
        this.time = time;
    }

    /**
     * Front for charge {@code tntKg} kg TNT on the surface ({@code surface}) or in air — to {@code maxRangeM}
     * (beyond it, at sound speed).
     */
    public static ChemicalFront of(double tntKg, boolean surface, double maxRangeM) {
        double max = Math.max(maxRangeM, 2 * FIRST_M);
        double[] r = new double[NODES + 1], t = new double[NODES + 1];
        double step = Math.log(max / FIRST_M) / (NODES - 1);
        for (int i = 1; i <= NODES; i++) {
            r[i] = FIRST_M * Math.exp(step * (i - 1));
            // segment uses speed at its midpoint (midpoint rule: with 1.5 % grid spacing, error is fractions of a percent)
            double mid = 0.5 * (r[i - 1] + r[i]);
            t[i] = t[i - 1] + (r[i] - r[i - 1]) / BlastModel.frontSpeed(BlastModel.overpressureKpa(mid, tntKg, surface));
        }
        return new ChemicalFront(tntKg, surface, r, t);
    }

    /** Maximum tables in memory (one charge for all projectiles in a salvo). */
    private static final int CACHED = 16;
    private static final java.util.LinkedHashMap<Long, ChemicalFront> CACHE = new java.util.LinkedHashMap<>(CACHED, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, ChemicalFront> eldest) {
            return size() > CACHED;
        }
    };
    /** Range of {@link #cached} tables, m: beyond it the front is sound (450 kg at 2.5 km — density jump 10⁻⁴). */
    public static final double CACHED_RANGE_M = 2500;

    /** Charge front to {@link #CACHED_RANGE_M}, shared by identical charges (salvo, replay). */
    public static ChemicalFront cached(double tntKg, boolean surface) {
        long key = Double.doubleToLongBits(tntKg) * 31 + (surface ? 1 : 0);
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(key, k -> of(tntKg, surface, CACHED_RANGE_M));
        }
    }

    /** Front arrival time at range R, s. */
    public double arrivalSeconds(double rangeM) {
        if (rangeM <= 0) return 0;
        if (rangeM >= range[NODES]) return time[NODES] + (rangeM - range[NODES]) / BlastModel.SOUND_SPEED;
        return interpolate(range, time, rangeM);
    }

    /** Front radius at time t, m. */
    public double radiusAt(double seconds) {
        if (seconds <= 0) return 0;
        if (seconds >= time[NODES]) return range[NODES] + (seconds - time[NODES]) * BlastModel.SOUND_SPEED;
        return interpolate(time, range, seconds);
    }

    /** Positive-phase length behind a front of radius R, m: t₊ × front speed. */
    public double phaseLength(double rangeM) {
        return BlastModel.chemicalPhaseSeconds(rangeM, tntKg, surface) * BlastModel.frontSpeed(kpa(rangeM));
    }

    /** Pressure at a front of radius R, kPa. */
    public double kpa(double rangeM) {
        return BlastModel.overpressureKpa(rangeM, tntKg, surface);
    }

    private static double interpolate(double[] xs, double[] ys, double x) {
        int lo = 0, hi = xs.length - 1;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (xs[m] <= x) lo = m;
            else hi = m;
        }
        double f = (x - xs[lo]) / Math.max(xs[hi] - xs[lo], 1e-300);
        return ys[lo] + f * (ys[hi] - ys[lo]);
    }
}
