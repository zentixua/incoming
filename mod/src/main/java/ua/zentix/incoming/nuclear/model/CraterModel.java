package ua.zentix.incoming.nuclear.model;

/**
 * Apparent surface-burst crater: radius 23·Y^0.3 m, depth 7.7·Y^0.3 m (dry soil),
 * paraboloid profile with an ejecta rim around it.
 */
public final class CraterModel {
    /**
     * Soil beneath the epicenter. Moisture is water saturation, not soil type: dry sand is the reference medium for crater
     * curves (TM 23-200: dry sand 1.0, saturated soil 1.5, granite and sandstone 0.8); Glasstone and Dolan 1977, §6.09:
     * “in water-saturated soil, the radius is noticeably larger”.
     */
    public enum Soil {
        /** Dry soil: earth, sand, gravel, rubble (loose and granular material above water level). */
        DRY(1.0),
        /** Rock. */
        ROCK(0.7),
        /** Water-saturated soil (underwater bottom). */
        WET(1.3);

        final double factor;

        Soil(double factor) {
            this.factor = factor;
        }
    }

    /** Charge crater diameter per ∛W, m/kg^⅓, and depth as a diameter fraction ({@link #chargeRadius}). */
    public static final double CHARGE_D = 0.65, CHARGE_DEPTH = 0.25;
    /** Rim height at the edge as a fraction of depth. */
    private static final double RIM = 0.25;
    /** Inner rim slope starts at this radius fraction. */
    private static final double RIM_INNER = 0.8;

    private CraterModel() {
    }

    public static double radius(double yieldKt, Soil soil) {
        return 23 * Math.pow(yieldKt, 0.3) * soil.factor;
    }

    public static double depth(double yieldKt, Soil soil) {
        return 7.7 * Math.pow(yieldKt, 0.3) * soil.factor;
    }

    /** Bowl depth at horizontal distance r from the epicenter, m (0 beyond the radius). */
    public static double profileDepth(double r, double yieldKt, Soil soil) {
        return bowl(r, radius(yieldKt, soil), depth(yieldKt, soil));
    }

    /**
     * Rim height above the original surface, m: rises from 0.8 radius to 0.25 depth at the edge,
     * then falls quadratically to zero at two radii. Resulting surface = rim − bowl.
     */
    public static double rimHeight(double r, double yieldKt, Soil soil) {
        return rim(r, radius(yieldKt, soil), depth(yieldKt, soil));
    }

    /** Paraboloid bowl of radius {@code rc} and depth {@code depth}: depth at distance r, m (0 beyond the radius). */
    public static double bowl(double r, double rc, double depth) {
        if (r >= rc) return 0;
        double u = r / rc;
        return depth * (1 - u * u);
    }

    /** Rim around a bowl of radius {@code rc} and depth {@code depth}, m: same law for nuclear and conventional craters. */
    public static double rim(double r, double rc, double depth) {
        double peak = RIM * depth;
        if (r <= RIM_INNER * rc || r >= 2 * rc) return 0;
        double u = r < rc ? (r - RIM_INNER * rc) / ((1 - RIM_INNER) * rc) : (2 * rc - r) / rc;
        return peak * u * u;
    }

    /**
     * Apparent crater for charge {@code tntKg} kg TNT on the soil surface (contact explosion): diameter {@link #CHARGE_D}·∛W m,
     * depth one quarter of diameter. Data (Ambrosini, Luccioni, Danesi, “Craters produced by explosions on the soil surface”,
     * Mecánica Computacional XXII, 2003): EMRTC experiment — 250 kg TNT on ground, Ø 3.8 m (0.60·∛W); calculation with charge
     * center at ground level — 0.61·∛W, charge resting on ground — 0.42·∛W; Kinney and Graham statistics of ~200 surface blasts —
     * 0.8·∛W ± 30 %; “depth usually about a quarter of diameter” (ibid.). A buried charge (delayed bomb) digs more —
     * {@code BuriedBlast}. Soil uses the same multipliers as nuclear craters ({@link Soil}). Body impact (Shahed ~1 MJ versus
     * 200 MJ charge) does not change the crater.
     */
    public static double chargeRadius(double tntKg, Soil soil) {
        return tntKg <= 0 ? 0 : 0.5 * CHARGE_D * Math.cbrt(tntKg) * soil.factor;
    }

    /** Charge crater depth, m: {@link #CHARGE_DEPTH} of diameter. */
    public static double chargeDepth(double tntKg, Soil soil) {
        return 2 * CHARGE_DEPTH * chargeRadius(tntKg, soil);
    }

    /** Charge crater bowl volume (paraboloid π/2·r²·h), m³: this much soil is ejected. */
    public static double chargeVolume(double tntKg, Soil soil) {
        double r = chargeRadius(tntKg, soil);
        return Math.PI / 2 * r * r * chargeDepth(tntKg, soil);
    }

    /** Whether a crater forms: burst no higher than 0.1 of the surface-burst fireball's maximum radius. */
    public static boolean formsCrater(double hobM, double yieldKt) {
        return hobM <= 0.1 * FireballModel.maxRadius(yieldKt, true);
    }
}
