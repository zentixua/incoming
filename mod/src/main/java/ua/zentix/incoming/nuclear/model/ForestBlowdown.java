package ua.zentix.incoming.nuclear.model;

/**
 * Trees blown down by the blast wind (S. Glasstone, P. Dolan, "The Effects of Nuclear Weapons", 1977, §5.149–5.150,
 * table 5.149, fig. 5.146). Trees respond to drag, so the damage range grows faster than ∛W (longer positive phase at
 * higher yield): a fixed pressure threshold felled every tree near 5 psi at every yield. At 250 kt on the ground that
 * left oak and birch forests standing to 3.3 km, where Glasstone has 30–90 % of them down to 4.2 km.
 * <ul>
 * <li>Severe damage: "up to 90 percent of trees blown down"; moderate: "about 30 percent" (table 5.149). The fraction
 * between them is lognormal in peak overpressure ({@link Fragility}) through those two points.</li>
 * <li>Ranges: fig. 5.146 at the optimum burst height, item 9 (unimproved coniferous stand) and item 10 (average
 * deciduous stand), as {@code D = D₁·W^b}; for a surface burst one half of that (the figure's rule for items 9 and
 * 10). The constants are read off the nomogram; the same reading gives its worked example (item 3, 10 kt: 1 389 and
 * 1 535 ft against 1 400 and 1 600). The figure's stated accuracy is ±20 %.</li>
 * <li>A range becomes a pressure through {@link HobBlast} for a surface burst, so a surface burst fells trees exactly
 * to the figure's ranges. An airburst uses the same pressures (est.): the figure gives only the optimum height for
 * each item, not which one, and its airburst ranges (twice the surface ones) are not a pressure in the Mach region of
 * a 240·∛W m burst.</li>
 * </ul>
 */
public final class ForestBlowdown {
    private ForestBlowdown() {
    }

    /** Fig. 5.146 at optimum burst height: range at 1 kt, ft, and its yield exponent; conifer then deciduous, severe then moderate. */
    private static final double[] D1_FT = {1862, 2428, 2689, 3520};
    private static final double[] EXPONENT = {0.3964, 0.3919, 0.3876, 0.3710};
    /** Fig. 5.146: "For a surface burst multiply the distance … by one-half for Items 9 and 10". */
    static final double SURFACE = 0.5;
    /** Table 5.149: severe damage blows down up to 90 % of trees, moderate about 30 %. */
    static final double SEVERE = 0.9, MODERATE = 0.3;
    private static final double FT = 0.3048;

    /** Fraction of trees down at peak overpressure {@code psi}: {@code Φ(ln(psi/median)/beta)}. */
    public record Curve(double median, double beta) {
        public double probability(double psi) {
            return Fragility.probability(psi, median, beta);
        }

        /** Whether the tree with local randomness {@code z} (standard normal) falls at {@code psi}. */
        public boolean falls(double psi, double z) {
            return Fragility.hit(psi, median, beta, z);
        }
    }

    /** Surface-burst ground range of severe (90 % down) or moderate (30 % down) damage, m. */
    public static double surfaceRange(double yieldKt, boolean conifer, boolean severe) {
        int i = (conifer ? 0 : 2) + (severe ? 0 : 1);
        return SURFACE * D1_FT[i] * Math.pow(yieldKt, EXPONENT[i]) * FT;
    }

    /** Blowdown curve in peak overpressure for {@code yieldKt}. */
    public static Curve curve(double yieldKt, boolean conifer) {
        double severe = HobBlast.psi(surfaceRange(yieldKt, conifer, true), 0, yieldKt);
        double moderate = HobBlast.psi(surfaceRange(yieldKt, conifer, false), 0, yieldKt);
        double zs = Fragility.inverse(SEVERE), zm = Fragility.inverse(MODERATE);
        double beta = Math.log(severe / moderate) / (zs - zm);
        return new Curve(moderate * Math.exp(-zm * beta), beta);
    }
}
