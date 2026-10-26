package ua.zentix.incoming.client.nuclear;

/**
 * Light in the pall ("cinema-level nuke" plan, step 7): how dust, haze and mist scatter sunlight, sky light, ball glow and
 * fire light. The shader ({@code fx_pall.glsl}, functions {@code pallDiffuse}, {@code pallK} in {@code pall.glsl}) computes the same
 * as this code (checked by PallGlslTest); this copy serves tests and frame light ({@link NukePall}).
 * <p>
 * <b>Matter.</b> A particle scatters fraction ω of incident light (single-scattering albedo), with a Henyey–Greenstein
 * phase function of asymmetry g. The sharp forward peak (dust g ≈ 0.6, drops ≈ 0.85) under delta-Eddington (Joseph,
 * Wiscombe, Weinman, 1976) — fraction f = g² — is treated as unscattered light: extinction σ' = (1 − ωf)σ, albedo
 * ω' = (1 − f)ω / (1 − ωf), residual phase g' = g / (1 + g). Pall table extinction ({@link PallField}, based on visibility
 * seen by the eye) is σ': the view ray and light reaching the point use one optical depth, while scattering uses ω' and g' (no separate
 * "transport" optical depth for mist). ω' comes from thick-layer color R (dust-wall and spray color, {@link CloudPuffs}, in
 * linear space): two-stream reflectance of a semi-infinite layer R∞ = (1 − u)/(1 + u), u = 2k/(3q); inverse — {@link #albedo}.
 * Wall-colored dust (0.6, 0.53, 0.45 on screen) yields ω' 0.87–0.73 (before delta-Eddington ω ≈ 0.91–0.81, like
 * desert dust, darker in blue); mist — 0.995–0.997; smoke above fires — about 0.5, with k > 1.
 * <p>
 * <b>Light field</b> — Eddington two-stream approximation in a plane layer (Shettle, Weinman, 1970; Meador, Weaver, 1980): radiance
 * I(τ, μ) = I₀(τ) + μ·I₁(τ) (μ — cosine to vertical, positive upward), τ — optical depth downward from the top, τc for the whole layer.
 * Sources: two parallel beams from above (sun and ball glow: strength F is what a white wall facing the beam
 * reflects, cosine μ to vertical), diffuse sky light from above and fire light from below (upward radiance), ground of albedo
 * A beneath the layer. Solution uses decaying exponentials ({@code e^(−kτ)} and {@code e^(−k(τc − τ))}), k² = 3(1 − ω)(1 − ωg):
 * in dense dust light decays as e^(−kτ), not e^(−τ) — brown twilight; nonabsorbing fog gives bright haze, not a
 * white wall: a thin layer glows according to optical depth (scatters incident light), a thick one is no brighter than its own color.
 * Light scattered from a point toward the eye (per unit extinction): {@code ω'·(F/4·p(Θ)·e^(−τ/μ) + I₀ + g'·μv·I₁)}, μv is
 * the cosine of the path to the eye.
 * <p>
 * <b>Light and screen.</b> Radiative transfer is linear in radiance: all the above uses linear fractions (a white horizontal wall under noon
 * sun and sky = 1). The Minecraft frame uses screen values (textures, lightmap and sky are sRGB, without conversion):
 * the pall converts its color to these ({@link #display}, γ = {@value #GAMMA}), and converts screen colors — dust-wall and spray color,
 * sky — to linear ({@link #linear}). Without this, midtones were darker (0.3 linear is 0.58 on screen),
 * thin fog was dirty gray, and twilight in dense dust was black without billows. Pure power rather than the piecewise sRGB curve:
 * converting a product equals the product of conversions; on-screen flux from a thick layer of color R in light L is exactly screen R·L
 * (a thick layer is brighter at a grazing angle to the eye, like a real cloud).
 * Fire glow on dust specified on screen above sky light ({@link CloudPuffs#glowOnSmoke}) becomes a difference of conversions
 * in linear space ({@link #lift}); fire light is capped at the flame itself ({@link #flame}). Ball light is already linear: black body
 * ({@link CloudPuffs#ball}).
 * <p>
 * <b>Layer and billows</b> (shader only). The layer floor follows terrain below the point ({@link PallGround} map): in a valley the pall above it
 * is also a layer, with fires and ground at its bottom, rather than at ground-zero elevation (a valley point used to be always "at the bottom",
 * in full fire light, with no height variation). Billow shadow is the deviation of optical depth toward light from the mean, summed over noise-cascade
 * scales: large billows (per tile) and small ones (per quarter tile); in dense dust, large billows vary optical depth by
 * several units: bright and dark masses visible tens of blocks away.
 */
public final class PallLight {
    /** Scattering asymmetry of dust and haze (micron-scale particles), mist drops (tens of microns), soot. */
    static final double DUST_G = 0.6, MIST_G = 0.85, SOOT_G = 0.5;
    /** Ground albedo beneath the pall: land (grass, stone, roofs) and water. */
    static final double LAND = 0.2, SEA = 0.06;
    /**
     * Sky light on the pall is this fraction of the linear sky color drawn by Minecraft: at noon (sky 0.48, 0.64, 1 on
     * screen), about a fifth of daylight, like clear-sky diffuse light. Sun supplies the rest up to white
     * ({@code NukePall.light}): scattering removes sky blue from its beam; together they make white daylight that lights
     * blocks (top of a white block by day = 1).
     */
    static final double SKY = 0.5;
    /** Screen exponent: screen value v is linear v^γ ({@link #linear}, {@link #display}). */
    static final double GAMMA = 2.2;
    /** Band around kμ = 1 where the particular-solution beam is shifted ({@link #beamMu}). */
    static final double BEAM_BAND = 0.05;
    /** Brighter than white moves toward white: fraction of brightness above 1 added to every channel ({@code CROSS} in {@code fx_pall}). */
    static final double CROSS = 0.15;

    private PallLight() {}

    /** Screen value → linear ({@code pow(v, 2.2)}). */
    static double linear(double display) {
        return Math.pow(Math.max(display, 0), GAMMA);
    }

    /** Linear → screen value ({@code pallDisplay}). */
    static double display(double linear) {
        return Math.pow(Math.max(linear, 0), 1 / GAMMA);
    }

    /**
     * Light of opaque matter {@code linear} (r, g, b) on screen, into {@code into}, as output by the pall ({@code fx_pall},
     * end of {@code main}): brighter than white moves toward white ({@link #CROSS}: incandescent white-yellow, not saturated orange), then to
     * screen values ({@link #display}), with a shoulder above 0.6 based on the brightest channel ({@code shoulder}).
     */
    static void screen(double[] linear, float[] into) {
        double lum = 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2], white = CROSS * Math.max(lum - 1, 0);
        double[] d = new double[3];
        for (int c = 0; c < 3; c++) d[c] = display(linear[c] + white);
        double m = Math.max(d[0], Math.max(d[1], d[2])), k = m <= 0.6 ? 1 : (0.6 + 0.4 * (1 - Math.exp(-(m - 0.6) / 0.4))) / m;
        for (int c = 0; c < 3; c++) into[c] = (float) Math.min(1, d[c] * k);
    }

    /**
     * Light that adds {@code x} to a white wall on screen above sky light {@code base} (also on screen), in linear space
     * ({@code pallLift}): (base + x)^γ − base^γ. Thus wall-dust reflection and fire glow specified on screen (where they add
     * to sky light) illuminate the pall with linear light, and its thick layer has the same screen color.
     */
    static double lift(double base, double x) {
        return linear(base + x) - linear(base);
    }

    /**
     * Fire light (fires, storm, whirl) that adds {@code x} to a white wall on screen above sky light {@code base}, in
     * linear space ({@code pallFlame}): {@link #lift}, capped at the flame itself, whose screen color is {@code flame}
     * (radiance — {@link #linear}{@code (flame)}). Scattering matter is no brighter than its source, per channel:
     * a white wall completely on fire has the fire color. Glow that already reached the flame color on screen (dust-wall billows above
     * fires at night), inverted through dust color, yielded light three times brighter than fire, making ground-level dust brighter than 1 on screen —
     * uniform.
     */
    static double flame(double base, double x, double flame) {
        return Math.min(lift(base, x), linear(flame));
    }

    /** Asymmetry after delta-Eddington: forward peak f = g² becomes unscattered light, residual g' = (g − f)/(1 − f). */
    static double truncated(double g) {
        return g / (1 + g);
    }

    /** Single-scattering albedo ω' from linear thick-layer color R (0..1) at asymmetry g' — inverse two-stream R∞. */
    static double albedo(double r, double g) {
        double u = (1 - r) / (1 + r), v = 0.75 * u * u;
        return (1 - v) / (1 - g * v);
    }

    /** Diffuse-light decay with optical depth, k² = 3(1 − ω)(1 − ωg) ({@code pallK}); at least 10⁻³ for a nonabsorbing medium. */
    static double k(double w, double g) {
        return Math.sqrt(Math.max(3 * (1 - w) * (1 - w * g), 1e-6));
    }

    /** Semi-infinite-layer reflectance in diffuse light, R∞ — thick pall color. */
    static double reflectance(double w, double g) {
        double u = 2 * k(w, g) / (3 * (1 - w * g));
        return (1 - u) / (1 + u);
    }

    /**
     * Beam cosine in the particular solution ({@code pallBeamMu}): for a beam decaying like diffuse light (kμ = 1),
     * the particular-solution denominator k²μ² − 1 vanishes. Within |k²μ² − 1| &lt; {@value #BEAM_BAND}, the entire beam is shifted
     * to the band edge: in the particular solution, its own transmittance and at ground. The solution still solves the equation, with cosine
     * within 2.5%; clamping the denominator with unchanged μ was not a solution: near noon in smoke (k &gt; 1), layer light
     * was wrong by multiples.
     */
    static double beamMu(double k, double mu) {
        double d = k * k * mu * mu - 1;
        if (Math.abs(d) >= BEAM_BAND) return mu;
        return Math.sqrt(d < 0 ? 1 - BEAM_BAND : 1 + BEAM_BAND) / k;
    }

    /**
     * Diffuse light in the layer ({@code pallDiffuse}): I₀ + c·I₁ at depth {@code tau} of a layer with optical depth {@code tc}. c = g'·μv is
     * scattering toward the eye (without ω'); c = ±2/3 is upward or downward flux divided by π (on a white wall, its light).
     *
     * @param w      albedo ω'
     * @param g      asymmetry g'
     * @param ground ground albedo beneath the layer
     * @param mu1    first beam cosine to vertical (from above), f1 is its strength
     * @param mu2    second beam cosine, f2 is its strength
     * @param top    sky light from above (downward radiance)
     * @param bottom light from below (upward radiance from ground, beyond reflection)
     */
    static double diffuse(double w, double g, double tau, double tc, double ground, double mu1, double f1, double mu2, double f2,
                          double top, double bottom, double c) {
        double q = 1 - w * g;
        double k = k(w, g);
        double p = 2.0 / 3 * k / q;
        double e = Math.exp(-k * tc);
        // a beam decaying like diffuse light (kμ = 1) uses a shifted cosine (beamMu)
        double m1 = beamMu(k, mu1), m2 = beamMu(k, mu2);
        double a1 = 0.75 * w * m1 * m1 * f1 * (1 + g * (1 - w)) / (k * k * m1 * m1 - 1);
        double b1 = 0.75 * w * m1 * f1 - 3 * m1 * (1 - w) * a1;
        double a2 = 0.75 * w * m2 * m2 * f2 * (1 + g * (1 - w)) / (k * k * m2 * m2 - 1);
        double b2 = 0.75 * w * m2 * f2 - 3 * m2 * (1 - w) * a2;
        double e1 = Math.exp(-tc / m1), e2 = Math.exp(-tc / m2);
        double r1 = top - a1 - a2 + 2.0 / 3 * (b1 + b2);
        double r2 = bottom + ground * (m1 * f1 * e1 + m2 * f2 * e2) - ((1 - ground) * a1 + 2.0 / 3 * (1 + ground) * b1) * e1
                - ((1 - ground) * a2 + 2.0 / 3 * (1 + ground) * b2) * e2;
        double m11 = 1 + p, m12 = e * (1 - p), m21 = e * (1 - ground - (1 + ground) * p), m22 = 1 - ground + (1 + ground) * p;
        double det = m11 * m22 - m12 * m21;
        double c1 = (r1 * m22 - m12 * r2) / det, c2 = (m11 * r2 - m21 * r1) / det;
        double x = Math.exp(-k * tau), y = Math.exp(-k * (tc - tau));
        double t1 = Math.exp(-tau / m1), t2 = Math.exp(-tau / m2);
        double i0 = c1 * x + c2 * y + a1 * t1 + a2 * t2;
        double i1 = k / q * (c2 * y - c1 * x) + b1 * t1 + b2 * t2;
        return i0 + c * i1;
    }

    /**
     * Light scattered toward the eye by thin pall, averaged over directions (per unit extinction): sun beam with mean phase
     * 1 (¼ strength), sky from above and sun plus sky from the ground at one half each. Used by effects-layer quads with
     * almost no pall in front of them ({@code PallAmbient}).
     */
    static double thin(double w, double sun, double sky, double mu, double ground) {
        return w * (0.25 * sun + 0.5 * sky + 0.5 * ground * (mu * sun + sky));
    }
}
