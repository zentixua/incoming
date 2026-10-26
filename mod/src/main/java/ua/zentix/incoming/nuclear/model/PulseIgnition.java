package ua.zentix.incoming.nuclear.model;

/**
 * What a short thermal pulse ignites (fuel fireball, {@link FuelFireball}): each fuel has a threshold — fluence at which
 * half the samples ignite and sustain burning. Thresholds from Glasstone &amp; Dolan, “The Effects of Nuclear Weapons”
 * (1977), 1 Mt ignition fluence table (reprinted by Collins, Melosh, Marcus, “Earth Impact Effects Program”,
 * Meteoritics &amp; Planetary Science 40, 2005, table 1; arXiv 2107.05988): foliage 0.25 MJ/m², newspaper 0.33,
 * dry grass 0.38, clothing 1.0. A 1 Mt pulse lasts seconds to tens of seconds, fuel ball 1–3 s: for equal energy a shorter
 * pulse ignites more easily (less heat diffuses inward), so these thresholds are upper estimates.
 * <p>
 * Thick wood (planks, logs, stairs, doors) does not sustain ignition from a seconds-long pulse: except for thin
 * materials, thermal damage stays in a thin surface layer (Glasstone, ch. 7); ignition energy grows with thickness
 * (DTIC AD0640428). Heated depth over time t is √(a·t): pine (a ≈ 1.7·10⁻⁷ m²/s) gives ~0.7 mm in 3 s; the surface flashes
 * while the ball shines and goes out without external heat (Drysdale, “An Introduction to Fire Dynamics”, ch. 6:
 * a single plank does not sustain burning). Burning fuel resting on it ignites it — minutes of flame, not a pulse.
 * <p>
 * Threshold scatter is lognormal, σ ln = {@value #SPREAD} (⚠ estimate: Glasstone's table thresholds for one material
 * vary by 1.5–2 times with moisture and color).
 */
public final class PulseIgnition {
    /** Calories per cm² in MJ/m²: 1 cal/cm² = 41 868 J/m². */
    private static final double CAL_CM2_PER_MJ_M2 = 1e6 / 4.1868e4;
    /** Threshold spread: σ of natural logarithm. */
    public static final double SPREAD = 0.3;
    /**
     * Fluence below which even a thousandth of the easiest fuel (foliage) does not ignite, cal/cm²: −3.1 σ from its threshold.
     * Beyond it the fuel ball ignites nothing — its air traversal ({@code Craters.ignite}) ends there.
     */
    public static final double FLOOR = 0.25 * 1e6 / 4.1868e4 * Math.exp(-3.1 * SPREAD);

    private PulseIgnition() {}

    /** Fuel by its thermal-pulse ignition response. */
    public enum Fuel {
        /** Foliage: 0.25 MJ/m² (6.0 cal/cm²). */
        LEAVES(0.25),
        /** Paper, books: newspaper 0.33 MJ/m² (7.9 cal/cm²). */
        PAPER(0.33),
        /** Dry grass, hay, shrubs, flowers, vines: 0.38 MJ/m² (9.1 cal/cm²). */
        GRASS(0.38),
        /** Fabric: wool, carpets, beds, banners — as clothing, 1.0 MJ/m² (24 cal/cm²). */
        FABRIC(1.0),
        /** Thick wood and all other combustibles: not ignited by the ball's pulse, only by burning fuel on them. */
        SOLID(Double.POSITIVE_INFINITY);

        /** Threshold, cal/cm². */
        public final double threshold;

        Fuel(double mjPerM2) {
            this.threshold = mjPerM2 * CAL_CM2_PER_MJ_M2;
        }
    }

    /** Share of {@code fuel} igniting under fluence {@code q} cal/cm². */
    public static double share(double q, Fuel fuel) {
        if (!(q > 0) || Double.isInfinite(fuel.threshold)) return 0;
        return normal(Math.log(q / fuel.threshold) / SPREAD);
    }

    /** Standard normal distribution function (Abramowitz, Stegun 26.2.17, error &lt; 7.5·10⁻⁸). */
    static double normal(double x) {
        double t = 1 / (1 + 0.2316419 * Math.abs(x));
        double d = 0.3989422804014327 * Math.exp(-0.5 * x * x);
        double p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return x >= 0 ? 1 - p : p;
    }
}
