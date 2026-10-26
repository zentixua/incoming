package ua.zentix.incoming.nuclear.model;

import java.util.concurrent.ConcurrentHashMap;

/**
 * What a conventional charge's blast breaks: an equal-damage curve in pressure–impulse coordinates using Jarrett's law,
 * and explosion gas pressure in an enclosed room. Charge in kg TNT, pressure in kPa, impulse in Pa·s; surface explosion
 * ({@link BlastModel#surfaceOverpressureKpa}, {@link BlastModel#chemicalImpulse}).
 *
 * <p><b>Jarrett</b> (D. E. Jarrett, “Derivation of the British explosives safety distances”, Annals of the New York Academy
 * of Sciences 152, 1968): bomb damage-level radius for brick houses R = K·∛W / (1 + (3175/W)²)^⅙ (W — kg TNT, R — m;
 * K: A — nearly complete demolition 3.8, B — demolition of 50–75 % of exterior walls 5.6, Cb — uninhabitable 9.6,
 * Ca — moderate 28, D — windows and roof tiles 56). For a large charge, pressure causes damage: R = K·∛W, and K is the
 * scaled range where blast pressure falls to the threshold. For a small charge, impulse dominates: the blast is shorter
 * than the wall's response time, and radius grows as W^⅔.
 *
 * <p><b>Block curve.</b> The block pressure threshold P_c is the nuclear blast threshold ({@code BlockResponse}): K is the
 * scaled range where a large surface charge's pressure equals P_c (12 psi masonry — 3.6, 5 psi wood — 5.6, like Jarrett's
 * A and B). Blast points (P, I) at Jarrett's radius for that K, for charges from 1 kg to 10⁷ kg, form the equal-damage
 * curve: a block breaks if its (P, I) point lies above and right of the curve. The hyperbola (P − P_c)(I − I_c) = k does
 * not reproduce this relation: for 3–2400 kg charges its error against Jarrett is 30–60 %, so the curve is tabulated from
 * the formula itself. Approximations: Jarrett was derived for brick houses and 50–1000 kg bombs; the same 3175 kg
 * transition is used for all blocks; the curve is not extended below 1 kg (requires at least the 1 kg impulse — estimate).
 * Nuclear blast impulse is orders of magnitude above the curve, giving a pressure-only response, as for ruins
 * ({@code BlockResponseTest}).
 *
 * <p><b>Indoor gases</b> (UFC 3-340-02, 2008, fig. 2-152; power fit — estimate, ±30 % in the graph's middle):
 * P_qs ≈ 2.25·(W/V)^0.72 MPa, V — free volume, m³. Pressure lasts until gases escape through openings: decay time
 * τ = V / (C_d·A·c), C_d 0.6, c — 340 m/s (estimate: gas escaping an opening at sound speed); gas impulse is P_qs·τ.
 * Without openings τ is infinite — walls bear pressure alone.
 */
public final class BlastDamage {
    /** Jarrett's impulse-to-pressure transition, kg TNT. */
    public static final double JARRETT_W = 3175;
    /** Jarrett levels: K, m/kg^⅓. */
    public static final double JARRETT_A = 3.8, JARRETT_B = 5.6, JARRETT_CB = 9.6, JARRETT_CA = 28, JARRETT_D = 56;
    /** Charge grid of the curve: 1 kg to 10⁷ kg, 20 nodes per decade. */
    private static final int PER_DECADE = 20, NODES = 7 * PER_DECADE + 1;
    /** “Infinitely large” charge — used to derive threshold K. */
    private static final double HUGE = 1e12;
    /** Opening discharge coefficient and gas speed through it, m/s (est.). */
    private static final double VENT_CD = 0.6, VENT_SPEED = 340;

    private static final ConcurrentHashMap<Double, Curve> CURVES = new ConcurrentHashMap<>();

    private BlastDamage() {}

    /** Jarrett damage-level radius, m. */
    public static double jarrett(double k, double tntKg) {
        if (tntKg <= 0) return 0;
        double x = JARRETT_W / tntKg;
        return k * Math.cbrt(tntKg) / Math.pow(1 + x * x, 1 / 6.0);
    }

    /** Scaled range, m/kg^⅓, where a large surface charge's pressure falls to {@code pcKpa}. */
    public static double scaledRange(double pcKpa) {
        return BlastModel.rangeForSurfaceOverpressure(pcKpa, HUGE) / Math.cbrt(HUGE);
    }

    /** Radius where charge {@code tntKg} breaks a block of threshold {@code pcKpa} in an open field, m (Jarrett). */
    public static double breakRange(double pcKpa, double tntKg) {
        return jarrett(scaledRange(pcKpa), tntKg);
    }

    /**
     * Impulse, Pa·s, at which a blast of pressure {@code pKpa} breaks a block of threshold {@code pcKpa}; infinity — pressure
     * below threshold (will not break it however long it lasts).
     */
    public static double impulseToBreak(double pKpa, double pcKpa) {
        if (pKpa <= pcKpa) return Double.POSITIVE_INFINITY;
        Curve c = CURVES.computeIfAbsent(pcKpa, Curve::new);
        double[] p = c.p, i = c.i;
        if (pKpa >= p[0]) return i[0];
        int last = p.length - 1;
        if (pKpa <= p[last]) return i[last] * (p[last] - pcKpa) / (pKpa - pcKpa);
        int lo = 0, hi = last;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (p[m] >= pKpa) lo = m;
            else hi = m;
        }
        double u = (Math.log(p[lo]) - Math.log(pKpa)) / (Math.log(p[lo]) - Math.log(p[hi]));
        return Math.exp(Math.log(i[lo]) + u * (Math.log(i[hi]) - Math.log(i[lo])));
    }

    /** Whether the blast (pressure {@code pKpa}, impulse {@code iPas}) breaks a block of threshold {@code pcKpa}. */
    public static boolean breaks(double pKpa, double iPas, double pcKpa) {
        return iPas >= impulseToBreak(pKpa, pcKpa);
    }

    /**
     * Share of the blast passing through a broken block: the blast loses the impulse spent breaking it (on the curve at
     * this pressure); the rest passes through. A block exactly at threshold absorbs it entirely; one deep in the zone barely
     * impedes it. Estimate: no diffraction or reflection from debris.
     */
    public static double passes(double pKpa, double iPas, double pcKpa) {
        double need = impulseToBreak(pKpa, pcKpa);
        return need >= iPas ? 0 : 1 - need / iPas;
    }

    /** Explosion gas pressure for {@code tntKg} in enclosed volume {@code volumeM3}, kPa (UFC 3-340-02, fit — estimate). */
    public static double quasiStaticKpa(double tntKg, double volumeM3) {
        if (tntKg <= 0 || volumeM3 <= 0) return 0;
        return 2250 * Math.pow(tntKg / volumeM3, 0.72);
    }

    /** Gas pressure duration in volume {@code volumeM3} with openings {@code ventM2}, s (infinity — no openings). */
    public static double ventSeconds(double volumeM3, double ventM2) {
        return ventM2 <= 0 ? Double.POSITIVE_INFINITY : volumeM3 / (VENT_CD * ventM2 * VENT_SPEED);
    }

    /** Equal-damage curve for threshold P_c: pressure decreases, impulse increases with charge. */
    private static final class Curve {
        final double[] p = new double[NODES], i = new double[NODES];

        Curve(double pcKpa) {
            double k = scaledRange(pcKpa);
            for (int j = 0; j < NODES; j++) {
                double w = Math.pow(10, (double) j / PER_DECADE);
                double r = jarrett(k, w);
                p[j] = BlastModel.surfaceOverpressureKpa(r, w);
                i[j] = BlastModel.chemicalImpulse(r, w, true);
            }
        }
    }
}
