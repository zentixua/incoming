package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.util.Stability;

/**
 * Smoke above fire: continuous heat and soot source, Briggs plume, without Minecraft. Metres, seconds, watts.
 * <ul>
 * <li><b>Source.</b> Fire block is a burning ~1 m² face: (est.) {@value #BLOCK_WATTS} W (wood peak heat release
 * 100–250 kW/m², Babrauskas, SFPE Handbook, 5th ed., ch. 26, midpoint). Convection carries {@value #CONVECTIVE}
 * (radiation 0.3 is a model estimate (est.), not a fixed wood property). Soot yield 0.015 kg/kg and chemical heat
 * 12.4 MJ/kg: Tewarson and Haskell, “Fire hardening of composite systems”, NISTIR 5499 (1994), p. 118,
 * table 1, red-oak row. Applying that sample to all burning blocks is an estimate (est.).</li>
 * <li><b>Rise</b>: EPA, “User's Guide for the Industrial Source Complex (ISC3) Dispersion Models”, vol. II,
 * EPA-454/B-95-003b (September 1995), §1.1.4: buoyancy flux F = g·Q_c/(π·c_p·ρ·T);
 * rise path Δh(x) = 1.6·F^⅓·x^⅔/u until final rise: neutral/unstable 21.425·F^¾/u (F &lt; 55) or 38.71·F^⅗/u
 * (eqs. 1-14, 1-15), stable 2.6·(F/(u·s))^⅓, s = N². The calm cap 5.0·F^¼·s^(−⅜) and smooth interpolation
 * between classes D and E are model estimates (est.), not those ISC3 equations.</li>
 * <li><b>Spread</b>: Pasquill–Gifford along wind path ({@link Stability}), rise (σ = Δh/3.5, Pasquill 1976), and source
 * size, in quadrature.</li>
 * <li><b>Mass</b>: soot per metre of wind path ṁ/u. Plume visible while centre chord optical depth exceeds Koschmieder
 * threshold (0.02).</li>
 * <li><b>Temperature</b> near source: Heskestad axis excess, ΔT = 9.1·(T/(g·c_p²·ρ²))^⅓·Q_c^⅔·z^(−5/3).
 * Peacock et al., CFAST Technical Reference Guide, NIST TN 1889v1 (October 2015), §3.4, eq. 3.20,
 * doi:10.6028/NIST.TN.1889v1, uses z−z₀. Here z₀ = 0, the 0.1 m height floor and the flame cap
 * ({@link #FLAME_K}, 75 kW/m²) are near-source approximations (est.); the plume law applies above the flame.
 * Visible flame light comes from luminous soot ({@link #flameLight}).</li>
 * </ul>
 * Below wind {@value #CALM} m/s, plume formulas fail: use that value (as for trails).
 */
public final class FirePlume {
    /** Heat release per fire block, W (est.). */
    public static final double BLOCK_WATTS = 175e3;
    /** Heat fraction carried by convection. */
    public static final double CONVECTIVE = 0.7;
    /** Soot per joule of heat: 0.015 kg/kg / 12.4 MJ/kg. */
    public static final double SOOT_PER_JOULE = 0.015 / 12.4e6;
    /** Flame is black body with σT⁴ = 75 kW/m² (radiative flux, gas heating above source), K. */
    public static final double FLAME_K = Math.pow(75e3 / 5.670374e-8, 0.25);
    /**
     * Luminous hydrocarbon flame: 140 kW/m² (Mudan, Croce, SFPE Handbook, "Fire hazard calculations for large open
     * hydrocarbon fires": E = 140·e^(−0.12D) + 20·(1 − e^(−0.12D)) kW/m²; 20 is smoke over flame, producing average
     * {@link #FLAME_K}), σT⁴ at ~1250 K, K.
     */
    public static final double LUMINOUS_K = Math.pow(140e3 / 5.670374e-8, 0.25);
    /**
     * Luminous flame temperature fluctuation scale, K (est.; model value). Gritzo, Sivathanu and Gill,
     * “Transient Measurements of Radiative Properties, Soot Volume Fraction and Soot Temperature in a Large
     * Pool Fire”, Combust. Sci. Technol. 139 (1998), 113–136, doi:10.1080/00102209808952083, studies transient
     * pool-fire radiation; the previously claimed 200–300 K range could not be verified in the accessible record.
     * Visible brightness rises steeply with T (Wien ∝ e^(−c₂/λT)), so mean flame brightness is that of its hot regions.
     */
    public static final double FLUCTUATION_K = 200;
    /**
     * Visible flame light (linear r, g, b, luminance Y; noon white wall 1, as {@code BlackBody.light}): black-body mean
     * over {@link #FLUCTUATION_K} fluctuations about {@link #LUMINOUS_K}, like black body ~1450 K, ~0.1 white wall
     * (~3·10³ cd/m²; (est.) candle ~10⁴). Using only {@link #FLAME_K} made daytime flame black (3·10⁻⁴ wall),
     * and at night 300 m away a dark red point.
     */
    private static final double[] FLAME_LIGHT = visibleFlame();
    /** Visible flame luminance (noon white wall 1) and colour (sRGB, largest channel 255). */
    public static final double FLAME_LUMINANCE = FLAME_LIGHT[3];
    public static final int FLAME_COLOR = pack(FLAME_LIGHT);

    /** Visible flame light ({@link #FLAME_LIGHT}) into out: r, g, b, Y. */
    public static void flameLight(double[] out) {
        System.arraycopy(FLAME_LIGHT, 0, out, 0, 4);
    }

    private static double[] visibleFlame() {
        double[] sum = new double[4], l = new double[4];
        double weights = 0;
        for (int i = -40; i <= 40; i++) {
            double z = i / 10.0, w = Math.exp(-z * z / 2), t = LUMINOUS_K + FLUCTUATION_K * z;
            ua.zentix.incoming.nuclear.model.BlackBody.light(t, l);
            for (int k = 0; k < 4; k++) sum[k] += w * l[k];
            weights += w;
        }
        for (int k = 0; k < 4; k++) sum[k] /= weights;
        return sum;
    }

    private static int pack(double[] rgb) {
        double max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
        int out = 0;
        for (int i = 0; i < 3; i++) out = out << 8 | (int) Math.round(255 * ua.zentix.incoming.client.fx.plume.FlameModel.srgb(rgb[i] / max));
        return out;
    }
    /** Minimum wind for plume formulas, m/s. */
    public static final double CALM = 1;
    /** Optical depth visibility threshold. */
    static final double SEEN = CloudModel.SEEN;

    /** Heat W; buoyancy flux m⁴/s³; wind m/s; stability class; N² 1/s²; source half-size m; soot per J. */
    public final double watts, flux, wind, stability, s, size, sootPerJoule;
    /** Final rise, m, and wind path to it, m. */
    public final double finalRise, finalX;

    /**
     * Plume above blocks fire blocks in wind wind (m/s at source height), stability class stability (0 A … 5 F).
     */
    public FirePlume(double blocks, double wind, double stability) {
        // Source area ~ blocks m²: radius √(N/π).
        this(blocks * BLOCK_WATTS, Math.sqrt(blocks / Math.PI), SOOT_PER_JOULE, wind, stability);
    }

    /**
     * Plume above source of radius radius (m), heat release watts (W), soot per joule sootPerJoule (fuel soot yield
     * divided by heat of combustion): crater fuel pool ({@code CloudModel#poolWatts}).
     */
    public FirePlume(double watts, double radius, double sootPerJoule, double wind, double stability) {
        this.watts = watts;
        this.sootPerJoule = sootPerJoule;
        flux = CloudModel.G * CONVECTIVE * watts / (Math.PI * CloudModel.CP * CloudModel.RHO * CloudModel.T_AIR);
        this.wind = Math.max(CALM, wind);
        this.stability = stability;
        double n = Stability.brunt(stability);
        s = n * n;
        // Source σ is half its radius.
        size = 0.5 * radius;
        finalRise = finalRise(flux, this.wind, stability, s);
        finalX = Math.pow(finalRise * this.wind / (1.6 * Math.cbrt(flux)), 1.5);
    }

    /** Briggs final rise, m. */
    static double finalRise(double f, double u, double stability, double s) {
        double neutral = f < 55 ? 21.425 * Math.pow(f, 0.75) / u : 38.71 * Math.pow(f, 0.6) / u;
        double stable = Math.min(2.6 * Math.cbrt(f / (u * s)), 5.0 * Math.pow(f, 0.25) * Math.pow(s, -0.375));
        double k = Math.max(0, Math.min(1, stability - Stability.D));
        return neutral + (stable - neutral) * k;
    }

    /** Axis rise at wind path x, m. */
    public double rise(double x) {
        if (x >= finalX) return finalRise;
        return 1.6 * Math.cbrt(flux) * Math.pow(Math.max(0, x), 2.0 / 3) / wind;
    }

    /** Axis rise speed at path x, m/s (u·dΔh/dx). */
    public double riseSpeed(double x) {
        if (x >= finalX || x <= 0) return 0;
        return (2.0 / 3) * 1.6 * Math.cbrt(flux) * Math.pow(x, -1.0 / 3);
    }

    /** Horizontal crosswind σ at path x, m. */
    public double sigmaY(double x) {
        double b = rise(x) / 3.5, p = Stability.sigmaY(stability, x);
        return Math.sqrt(p * p + b * b + size * size);
    }

    /** Vertical σ at path x, m. */
    public double sigmaZ(double x) {
        double b = rise(x) / 3.5, p = Stability.sigmaZ(stability, x);
        return Math.sqrt(p * p + b * b + size * size);
    }

    /** Soot per metre of wind path, kg/m. */
    public double sootPerMetre() {
        return watts * sootPerJoule / wind;
    }

    /** Optical depth across plume centre at path x: σ_m·λ/(√(2π)·√(σ_y·σ_z)). */
    public double tau(double x) {
        return CloudModel.SOOT_K * sootPerMetre() / (Math.sqrt(2 * Math.PI) * Math.sqrt(sigmaY(x) * sigmaZ(x)));
    }

    /** Visible downwind plume reach, m (at most limit). */
    public double visible(double limit) {
        if (tau(limit) >= SEEN) return limit;
        double lo = 0, hi = limit;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            if (tau(mid) >= SEEN) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** Heskestad axis temperature excess at height z above source, K (capped at flame). */
    public double excess(double z) {
        double c = 9.1 * Math.cbrt(CloudModel.T_AIR / (CloudModel.G * CloudModel.CP * CloudModel.CP * CloudModel.RHO * CloudModel.RHO));
        double dt = c * Math.pow(CONVECTIVE * watts, 2.0 / 3) * Math.pow(Math.max(z, 0.1), -5.0 / 3);
        return Math.min(dt, FLAME_K - CloudModel.T_AIR);
    }

    /**
     * Heskestad axis gas speed at height z above source, m/s: u = 3.4·(g/(c_p·ρ·T))^⅓·Q_c^⅓·z^(−⅓).
     * NIST TN 1889v1 (October 2015), §3.4, eq. 3.21 (citation above). Setting virtual origin z₀ = 0 and
     * using a 0.1 m height floor instead of its flame-region speed cap are approximations (est.), as for {@link #excess}.
     */
    public double axisSpeed(double z) {
        double c = 3.4 * Math.cbrt(CloudModel.G / (CloudModel.CP * CloudModel.RHO * CloudModel.T_AIR));
        return c * Math.cbrt(CONVECTIVE * watts) * Math.pow(Math.max(z, 0.1), -1.0 / 3);
    }

    /** Heskestad flame height, m: L = 0.235·Q^⅖ − 1.02·D (Q kW, D source diameter); at least half a metre. */
    public double flameHeight() {
        return Math.max(0.5, 0.235 * Math.pow(watts / 1e3, 0.4) - 1.02 * 4 * size);
    }
}
