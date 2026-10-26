package ua.zentix.incoming.nuclear.model;

/**
 * Burns from fireball light (Glasstone &amp; Dolan 1977, ch. 7 and 12): exposed-skin burn degree by thermal fluence,
 * clothing ignition, burn shock. Thresholds increase with yield: a larger burst's pulse is longer, letting skin shed heat.
 *
 * <p>Each person's burns differ: their threshold is the median times {@code exp(β·z)}, with their random {@code z}
 * (normal, truncated to ±{@link Fragility#TAIL}); the same {@code z} for every degree — the same skin.
 */
public final class BurnInjury {
    /** Anchor yields of fig. 12.64, kt. */
    private static final double[] SKIN_YIELDS = {1, 10, 100, 1000, 10000};
    /**
     * Thermal fluence, cal/cm², causing first-, second- and third-degree burns in half of people: fig. 12.64, moderately
     * pigmented skin, unshielded and without evasive action (read from the graph to ~0.05 precision).
     */
    private static final double[][] SKIN = {
            {2.05, 2.38, 2.78, 3.25, 4.15},
            {4.0, 4.32, 5.12, 6.28, 7.65},
            {6.18, 6.95, 8.35, 10.0, 11.8}};
    /**
     * Human threshold spread (log σ): §12.65 — at 1 Mt, 4.5–6 cal/cm² causes second-degree burns in 18 %; with median 6.28
     * this gives 0.18 (light/dark skin difference in fig. 12.64 — ±12 %, part of the same spread).
     */
    public static final double BETA = 0.18;
    /** Column yields of table 7.35, kt. */
    private static final double[] FABRIC_YIELDS = {35, 1400, 20000};
    /** Thin fabric ignition, cal/cm²: khaki cotton shirting, 3 oz/yd² (table 7.35). */
    private static final double[] THIN = {14, 21, 28};
    /** Heavy fabric ignition, cal/cm²: khaki cotton, 8 oz/yd² (table 7.35). */
    private static final double[] HEAVY = {20, 30, 39};
    /**
     * Burn shock (§12.62): third degree over 25 % of the body or second degree over 30 % — shock after 30 minutes–12 hours;
     * fatal without treatment (no treatment in the game).
     */
    public static final double SHOCK_THIRD = 25, SHOCK_SECOND = 30;
    /** Shock onset, game hours. */
    public static final double SHOCK_FROM_H = 0.5, SHOCK_TO_H = 12;
    /** Burn shock health-loss level ({@link #level}): {@link #SHOCK_LEFT} health remains. */
    public static final int SHOCK_LEVEL = 10;
    public static final double SHOCK_LEFT = 0.05;
    /**
     * Healing, game days: first degree — about a week, no scar (§12.53, like sunburn; duration — clinical estimate),
     * second degree — one to two weeks (§12.54), third degree — several weeks, with a scar (§12.55).
     */
    public static final double[] HEAL_DAYS = {7, 14, 35};

    /** Clothing on a body region. */
    public enum Cover {
        /** Exposed skin. */
        SKIN,
        /** Thin fabric: no light protection until it ignites (then flame burns the skin beneath). */
        THIN,
        /** Heavy fabric (leather armor). */
        HEAVY,
        /** Metal and all solid materials: blocks light, does not burn. */
        SOLID
    }

    private BurnInjury() {}

    /** Burn threshold for degree {@code degree} (1–3) in half of people, cal/cm². */
    public static double median(int degree, double yieldKt) {
        return interpolate(SKIN_YIELDS, SKIN[degree - 1], yieldKt);
    }

    /** Exposed-skin burn degree (0 — none) at fluence {@code q} cal/cm² for a person with random {@code z}. */
    public static int degree(double q, double yieldKt, double z) {
        for (int k = 3; k >= 1; k--) {
            if (Fragility.hit(q, median(k, yieldKt), BETA, z)) return k;
        }
        return 0;
    }

    /** Below this fluence (cal/cm²) nobody is burned: first degree for the most sensitive person. */
    public static double lowest(double yieldKt) {
        return median(1, yieldKt) * Math.exp(-BETA * Fragility.TAIL);
    }

    /** Whether clothing {@code cover} ignites at fluence {@code q} cal/cm². */
    public static boolean ignites(Cover cover, double q, double yieldKt) {
        return switch (cover) {
            case THIN -> q >= interpolate(FABRIC_YIELDS, THIN, yieldKt);
            case HEAVY -> q >= interpolate(FABRIC_YIELDS, HEAVY, yieldKt);
            default -> false;
        };
    }

    /**
     * Shock threshold fraction: second- and third-degree burns on {@code second} and {@code third} % of the body;
     * shock from 1 onward.
     */
    public static double shock(double second, double third) {
        return second / SHOCK_SECOND + third / SHOCK_THIRD;
    }

    /**
     * Health-loss level for burns at fraction {@code share} of shock threshold: tenths rounded up; 0.9 to shock gives
     * 9 (0.9); from 1 — shock, {@link #SHOCK_LEVEL}.
     */
    public static int level(double share) {
        return share >= 1 ? SHOCK_LEVEL : (int) Math.min(SHOCK_LEVEL - 1, Math.max(0, Math.ceil(share * 10 - 1e-9)));
    }

    /** Health share lost to burns of {@code level} (negative — no burns). */
    public static double lost(int level) {
        return level >= SHOCK_LEVEL ? 1 - SHOCK_LEFT : Math.max(0, level) / 10.0;
    }

    /**
     * Burn wound of {@code level} on top of previous {@code before} (−1 — none): share of unburned health corresponding to
     * the level increase; no worse than previous — zero (the same burns do not take health twice).
     */
    public static double wound(int before, int level) {
        return level <= before ? 0 : lost(level) - lost(before);
    }

    /** Game hours to shock for a person with random {@code u} 0..1 (uniform on the logarithm). */
    public static double shockHours(double u) {
        return SHOCK_FROM_H * Math.pow(SHOCK_TO_H / SHOCK_FROM_H, Math.max(0, Math.min(1, u)));
    }

    /**
     * Value from anchors: linear in log yield (as fig. 12.64 and table 7.35 are drawn); yield from 1 kt to the last anchor;
     * below the first anchor (35 kt for fabric), use the first segment's slope.
     */
    private static double interpolate(double[] yields, double[] values, double yieldKt) {
        double x = Math.log(Math.max(1, Math.min(yields[yields.length - 1], yieldKt)));
        int i = 0;
        while (i < yields.length - 2 && x > Math.log(yields[i + 1])) i++;
        double x0 = Math.log(yields[i]), x1 = Math.log(yields[i + 1]);
        return values[i] + (values[i + 1] - values[i]) * (x - x0) / (x1 - x0);
    }
}
