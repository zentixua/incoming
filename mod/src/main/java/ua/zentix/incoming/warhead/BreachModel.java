package ua.zentix.incoming.warhead;

/**
 * Breaching an obstacle with a contact charge, without Minecraft. Law: FM 5-250 “Explosives and Demolitions”
 * (1992), app. B, formula B-7 (metric form of 3-6): K = R³·M·C, K kg TNT, R breach radius m, M material factor
 * (table B-3), C tamping factor (fig. 3-15). Breach radius is “distance through which a charge must penetrate to
 * displace or destroy an obstacle” (B-5a): external charge uses obstacle thickness (3-12b), destroying everything
 * within it. Applies only with free rear face (3-15b(1): “free reflection surface … allows spalling”): charge
 * breaches a slab over void (bridge deck, room roof, cave roof), not ground half-space, where a crater forms
 * ({@link ua.zentix.incoming.nuclear.model.CraterModel#chargeRadius}).
 * <p>
 * Approximation: formula is a demolition charge rule ensuring breach (FM rounds R up to ¼ m, recommends stronger
 * material when uncertain), so real breach of same charge is likely wider; FM does not say how much. Formula
 * inversion (thickness breached by given charge) is ours: M table is stepped in R; choose largest R with
 * sufficient FM charge ({@link Material#radius}). FM does not combine material layers (uses strongest); here
 * divide each layer path by its material radius, breach while sum ≤ 1 (estimate: homogeneous slab exactly matches
 * FM formula).
 */
public final class BreachModel {
    /**
     * External untamped contact charge, “elevated untamped”, fig. 3-15 (C = 1.8): warhead exploding on struck
     * surface. Ground charge at wall base uses 3.6 (energy enters ground), but for a slab struck from above, face
     * under charge is the obstacle.
     */
    public static final double UNTAMPED = 1.8;
    /** Charge within material, “placed in center of mass”, fig. 3-15, C = 1.0: bomb penetrating ground. */
    public static final double TAMPED = 1.0;
    /** Cast TNT density, kg/m³ (reference 1.65 g/cm³): charge size. */
    static final double TNT_DENSITY = 1650;

    private BreachModel() {}

    /**
     * Material groups table B-3 (FM 5-250, app. B): M by breach-radius steps. Step boundaries in m (“0.3 m and
     * less” includes 0.3; others “from … to less than …”).
     */
    public enum Material {
        /** Earth: soil, sand, gravel, clay, snow, other loose material. */
        EARTH(false, new double[]{}, 1.12),
        /** Poor masonry, shale, hardpan, good timber, earth construction: wood, shale, dried clay (terracotta). */
        POOR(false, new double[]{1.5}, 5.13, 4.64),
        /** Good masonry, concrete block, rock: stone, brick, ordinary masonry, unreinforced concrete. */
        ROCK(true, new double[]{0.3, 1, 1.5, 2}, 14.09, 7.69, 6.41, 5.13, 4.32),
        /** Dense concrete, first-class masonry: dense dressed-stone masonry. */
        DENSE(true, new double[]{0.3, 1, 1.5, 2}, 18.26, 9.93, 8.33, 6.57, 5.61),
        /**
         * Reinforced concrete (reinforcement not cut): strongest table group, prescribed by FM for unidentified
         * material (3-12c), solid blocks without entries.
         */
        REINFORCED(true, new double[]{0.3, 1, 1.5, 2}, 28.19, 15.38, 12.81, 10.09, 8.65);

        /** R step boundaries, m (one more step than boundaries). */
        private final double[] bounds;
        /**
         * First step includes boundary: concrete/rock “0.3 m and less”; weak masonry/wood “less than 1.5 m”, so
         * 1.5 starts second.
         */
        private final boolean firstInclusive;
        /** M per step. */
        private final double[] factor;

        Material(boolean firstInclusive, double[] bounds, double... factor) {
            if (factor.length != bounds.length + 1) throw new IllegalArgumentException("step count does not match factor count");
            this.bounds = bounds;
            this.factor = factor;
            this.firstInclusive = firstInclusive;
        }

        /** M at breach radius {@code r}, m (table B-3). */
        public double factor(double r) {
            int i = 0;
            // “0.3 m and less”: first step inclusive, then “from … to less than …”.
            while (i < bounds.length && (i == 0 && firstInclusive ? r > bounds[i] : r >= bounds[i])) i++;
            return factor[i];
        }

        /** FM charge breaching {@code r} m with tamping {@code c}, kg TNT (formula B-7). */
        public double charge(double r, double c) {
            return r * r * r * factor(r) * c;
        }

        /**
         * Breach radius of {@code tnt} kg TNT with tamping {@code c}, m: largest R supported by FM charge. Each
         * step gives R = ∛(K/(M·C)) if within step, otherwise upper boundary if charge supports it; choose
         * maximum (M falls between steps, so R grows without backward jumps).
         */
        public double radius(double tnt, double c) {
            if (!(tnt > 0) || !(c > 0)) return 0;
            double best = 0;
            for (int i = 0; i < factor.length; i++) {
                double lo = i == 0 ? 0 : bounds[i - 1], hi = i < bounds.length ? bounds[i] : Double.POSITIVE_INFINITY;
                double r = Math.cbrt(tnt / (factor[i] * c));
                if (r < lo) continue;
                best = Math.max(best, Math.min(r, hi));
            }
            return best;
        }
    }

    /**
     * Charge sphere radius for {@code tnt} kg TNT, m: farther from face is not contact; FM gives no standoff
     * breaching law (air blast applies, {@link StagedExplosion}).
     */
    public static double chargeRadius(double tnt) {
        return Math.cbrt(3 * Math.max(tnt, 0) / (4 * Math.PI * TNT_DENSITY));
    }
}
