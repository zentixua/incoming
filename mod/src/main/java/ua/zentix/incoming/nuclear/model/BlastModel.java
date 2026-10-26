package ua.zentix.incoming.nuclear.model;

/**
 * Blast wave: post-front Rankine–Hugoniot relations (dynamic pressure, wind, front speed) and Kinney–Graham surface blast
 * pressure for a conventional charge. Nuclear burst pressure is {@link HobBlast} (by height of burst).
 * Slant range in meters (= blocks); charge in kg TNT.
 */
public final class BlastModel {
    /** Atmospheric pressure, kPa. */
    public static final double P0 = 101.325;
    /** Sound speed, m/s: the distant front approaches it. */
    public static final double SOUND_SPEED = 343.0;
    /** kPa per psi. */
    public static final double KPA_PER_PSI = 6.895;
    /** Surface blast enhancement by ground reflection (some energy goes into crater and soil — less than double). */
    private static final double GROUND_REFLECTION = 1.8;

    private BlastModel() {
    }

    /**
     * Surface blast overpressure of a conventional charge {@code tntKg} kg TNT at slant range R, kPa: the wave reflects
     * from the ground ({@link #GROUND_REFLECTION}).
     */
    public static double surfaceOverpressureKpa(double slantRangeM, double tntKg) {
        return kinneyGraham(slantRangeM, tntKg * GROUND_REFLECTION);
    }

    /** Overpressure of charge {@code tntKg} kg TNT in free air (airburst, no ground reflection), kPa. */
    public static double freeAirOverpressureKpa(double slantRangeM, double tntKg) {
        return kinneyGraham(slantRangeM, tntKg);
    }

    /** Conventional charge pressure at slant range, kPa: surface ({@link #surfaceOverpressureKpa}) or air. */
    public static double overpressureKpa(double slantRangeM, double tntKg, boolean surface) {
        return surface ? surfaceOverpressureKpa(slantRangeM, tntKg) : freeAirOverpressureKpa(slantRangeM, tntKg);
    }

    /**
     * Positive blast phase of a conventional charge {@code tntKg} kg TNT at slant range R, s: Kinney–Graham for chemical HE
     * (Kinney &amp; Graham 1985, “Explosive Shocks in Air”; formula from handoff ref/formulas.md §3, not checked against the
     * book in this work): t₊/∛W = 980·(1 + (Z/0.54)¹⁰) / ((1 + (Z/0.02)³)·(1 + (Z/0.74)⁶)·√(1 + (Z/6.9)²)) ms/kg^⅓,
     * Z = R/∛W; on the surface — W × {@link #GROUND_REFLECTION}, as for pressure. At long range the formula approaches
     * 4.2 ms·∛W (50 kg on the surface — 19 ms), near the burst — milliseconds: the whole phase is audible for a conventional
     * blast, unlike a distant nuclear blast.
     */
    public static double chemicalPhaseSeconds(double slantRangeM, double tntKg, boolean surface) {
        double w = Math.cbrt(Math.max(1e-9, surface ? tntKg * GROUND_REFLECTION : tntKg));
        double z = Math.max(1e-6, slantRangeM / w);
        double a = z / 0.02, b = z / 0.74;
        return 1e-3 * w * 980 * (1 + Math.pow(z / 0.54, 10)) / ((1 + a * a * a) * (1 + Math.pow(b, 6)) * Math.sqrt(1 + (z / 6.9) * (z / 6.9)));
    }

    /**
     * Positive-phase impulse of a conventional charge {@code tntKg} kg TNT at slant range R, Pa·s: Kinney–Graham (ibid.):
     * I/∛W = 0.067·√(1 + (Z/0.23)⁴) / (Z²·∛(1 + (Z/1.55)³)) bar·ms/kg^⅓, Z = R/∛W;
     * on the surface — W × {@link #GROUND_REFLECTION}.
     * Check against UFC 3-340-02 (fig. 2-7, incident impulse): Z = 1 — 117 Pa·s/kg^⅓ versus ~134, Z = 10 — 20 versus ~18
     * (within 15 %).
     */
    public static double chemicalImpulse(double slantRangeM, double tntKg, boolean surface) {
        double w = Math.cbrt(Math.max(1e-9, surface ? tntKg * GROUND_REFLECTION : tntKg));
        double z = Math.max(1e-3, slantRangeM / w);
        double a = z / 0.23, b = z / 1.55;
        return 100 * w * 0.067 * Math.sqrt(1 + a * a * a * a) / (z * z * Math.cbrt(1 + b * b * b));
    }

    /** Range where surface blast pressure of charge {@code tntKg} falls to {@code kpa} (0 — no charge or pressure). */
    public static double rangeForSurfaceOverpressure(double kpa, double tntKg) {
        if (tntKg <= 0 || kpa >= surfaceOverpressureKpa(0, tntKg)) return 0;
        return Solve.decreasingRoot(r -> surfaceOverpressureKpa(r, tntKg), kpa, 1e-3, 1e8);
    }

    /** Kinney–Graham: pressure at range R from charge W kg TNT in free air, kPa (scaled Z = R / ∛W). */
    private static double kinneyGraham(double rangeM, double chargeKg) {
        double z = Math.max(0, rangeM) / Math.cbrt(chargeKg);
        double a = z / 4.5, b = z / 0.048, c = z / 0.32, d = z / 1.35;
        return P0 * 808 * (1 + a * a) / Math.sqrt((1 + b * b) * (1 + c * c) * (1 + d * d));
    }

    public static double psi(double kpa) {
        return kpa / KPA_PER_PSI;
    }

    public static double kpa(double psi) {
        return psi * KPA_PER_PSI;
    }

    /** Post-front dynamic pressure, kPa: q = 2.5·P² / (7·P0 + P). */
    public static double dynamicPressureKpa(double overpressureKpa) {
        return 2.5 * overpressureKpa * overpressureKpa / (7 * P0 + overpressureKpa);
    }

    /** Rankine–Hugoniot front speed, m/s: U = 343·√(1 + 6P/(7·P0)). */
    public static double frontSpeed(double overpressureKpa) {
        return SOUND_SPEED * Math.sqrt(1 + 6 * Math.max(0, overpressureKpa) / (7 * P0));
    }

    /**
     * Travel time of a chemical blast: integrate dr/U(P(r)) using the same pressure and Rankine–Hugoniot front speed.
     * Midpoint quadrature, 64 intervals; spherical propagation with the existing surface reflection approximation.
     */
    public static double chemicalArrivalSeconds(double rangeM, double tntKg, boolean surface) {
        if (!(rangeM > 0)) return 0;
        double dr = rangeM / 64, seconds = 0;
        for (int i = 0; i < 64; i++) seconds += dr / frontSpeed(overpressureKpa((i + 0.5) * dr, tntKg, surface));
        return seconds;
    }

    /**
     * Rankine–Hugoniot air speed immediately behind the front, m/s: u = 5P/(7·P0) · 343 / √(1 + 6P/(7·P0)) — 5 psi ≈ 72 m/s,
     * 1 psi ≈ 17 m/s.
     */
    public static double windSpeed(double overpressureKpa) {
        double x = Math.max(0, overpressureKpa) / P0;
        return SOUND_SPEED * 5 * x / 7 / Math.sqrt(1 + 6 * x / 7);
    }

    /**
     * Positive-phase duration at a listener at slant range R, s: Kinney–Graham for nuclear explosions
     * (KG85 NE, table XI; formula and table check — Garcés 2019, Infrasound Monitoring for Atmospheric Studies, ch. 8, app. 4:
     * 2 kt at 8 km — 0.67 s, 1 t at 1 km — 0.057 s), ground reflection as for pressure ({@link #GROUND_REFLECTION}):
     * W = 1.8·Y kt, Z = R / ∛W, m/kt^⅓. Table from Z = 35 (closer — its edge value) to 5000; beyond that the formula approaches
     * 0.77·∛W (as ANSI S2.20: 1 kt at 10 km — 0.52 s, at 100 km — 0.93).
     */
    public static double positivePhaseSeconds(double slantRangeM, double yieldKt) {
        double w = Math.cbrt(GROUND_REFLECTION * Math.max(1e-6, yieldKt));
        double z = Math.max(35, slantRangeM / w);
        double a = z / 100, b = z / 285;
        return w * 0.18 * Math.sqrt(1 + a * a * a) / (Math.sqrt(1 + z / 40) * Math.pow(1 + Math.pow(b, 5), 1 / 6.0)
                * Math.pow(1 + z / 50_000, 1 / 6.0));
    }
}
