package ua.zentix.incoming.client.far;

import ua.zentix.incoming.util.Visibility;

/**
 * Distant visibility without Minecraft: Koschmieder haze and bright points conserving luminous flux.
 * <ul>
 * <li>Air attenuates light as exp(−σ·d), σ = 3.912 / V, where V is visibility range: dark-on-sky contrast
 * falls to the eye's 0.02 threshold there (Koschmieder, 1924). Haze, rain and thunderstorm coefficients add.</li>
 * <li>Dark and bright distant objects: L = L₀·t + Lh·(1 − t), where Lh is horizon haze, the sky behind it. Thus everything
 * draws its own light with opacity × t over what is behind ({@link FarSprites}), rather than blending with fog
 * color: vanilla rain fog turns blue, which would make distant smoke blue.</li>
 * <li>Everything has its real angular size, with no minimum: subpixel bodies draw as a {@link #MIN_PIXELS} spot with the same
 * sky coverage and light ({@link #body}, {@link #light}); brighter than screen white becomes glare and veil.</li>
 * </ul>
 */
public final class Sight {
    /** −ln 0.02: eye contrast threshold defining visibility range. */
    public static final double KOSCHMIEDER = 3.912;
    /** Visibility range, blocks (= meters): clear, rain, thunderstorm; same as flash light ({@link Visibility}). */
    public static final double CLEAR = Visibility.CLEAR, RAIN = Visibility.RAIN, THUNDER = Visibility.THUNDER;
    /**
     * Minimum drawn spot diameter: one and a half pixels, retaining light and coverage of the real angle, proportionally
     * fainter (like stars in engines); a one-pixel spot would flicker as it crosses pixels.
     */
    public static final double MIN_PIXELS = 1.5;
    /** Light shares below this are invisible (Koschmieder threshold). */
    public static final double THRESHOLD = 0.02;
    /** presentation: compressed day/night eye adaptation as a power ({@link #adapted}), not source power. */
    public static final double ADAPT = 4;
    /**
     * presentation: glare ({@link #light}), halo width grows with log overexposure, as in a photograph: 10 gives 2.4 cores. Peak
     * is SCATTER of body brightness, no more than HALO of white: scattered light at the disk edge is fractions of a percent of
     * source brightness (CIE spread function: L/Lsource ≈ 10·π·α²/θ², angles in degrees), up to a percent in a photo. By day a ball
     * 2–8 times brighter than the sky thus stays orange without a halo; a flash glows slightly; at night glare reaches HALO.
     */
    public static final double GLARE = 0.6, HALO = 0.6, SCATTER = 0.01;
    /**
     * Veil ({@link #light}): Stiles–Holladay constant with a noticeable-over-background threshold, chosen from estimates: at night
     * a Shahed at 2 km gives ~10°, at 8 km ~1.5°, a missile twice as wide; by day no wider than glare (absent). Capped at VEIL_MAX
     * rad (10°); peak VEIL_PEAK of white: veil at the edge is twice the night background, as the threshold intends.
     */
    public static final double VEIL = 0.7, VEIL_MAX = 0.17, VEIL_PEAK = 0.22;
    /** A core this overexposed is white: saturated bright color moves to white, retaining color in glare (as in film and AgX). */
    public static final double WHITE = 32;

    private Sight() {}

    /** Visibility range from world weather: rain/thunderstorm 0..1, as in {@code Level.getRainLevel}/{@code getThunderLevel}. */
    public static double range(float rain, float thunder) {
        double sigma = 1 / CLEAR + rain * (1 / RAIN - 1 / CLEAR) + thunder * (1 / THUNDER - 1 / RAIN);
        return 1 / sigma;
    }

    /** Share of light (and dark-object intrinsic contrast) passing through d blocks of air along ground at sea level. */
    public static double transmittance(double d, double range) {
        return Math.exp(-KOSCHMIEDER * d / range);
    }

    /**
     * Clean-air extinction (Rayleigh scattering) at sea level, 1/m: 550 nm (0.0116 km⁻¹: Wallner et al., MNRAS 494,
     * 2020, eq. 15); visibility without haze is 340 km.
     * Remaining extinction from visibility range is haze (aerosol and droplets).
     */
    public static final double RAYLEIGH = 1.16e-5;
    /**
     * Height scales, m: clear-day haze occupies the lowest kilometers (boundary layer; Elterman 1968, like engine skies:
     * Nishita 1993), clean air follows atmospheric density. Visibility range is at ground level: a ray to a mushroom cap
     * 5 km high passes above almost all haze; uniform-height haze would hide it three times too strongly.
     */
    public static final double HAZE_HEIGHT = 1200, AIR_HEIGHT = 8000;

    /**
     * Air from the eye: eye-local extinction, 1/m, for clear-day {@code haze}, {@code clean} air and {@code weather}:
     * rain and thunderstorm beyond clear-day extinction ({@link #at}).
     */
    public record Air(double haze, double clean, double weather) {
        /**
         * At an eye {@code eye} m above sea level (below uses sea level): total at sea level is 3.912 / range. Weather does not
         * thin with height: drops fall from clouds, and above cloud base the ray enters clouds; a cap above them is invisible in rain.
         */
        public static Air at(double eye, double range) {
            double h = Math.max(0, eye), clear = KOSCHMIEDER / CLEAR, air = Math.min(RAYLEIGH, clear);
            return new Air((clear - air) * Math.exp(-h / HAZE_HEIGHT), air * Math.exp(-h / AIR_HEIGHT),
                    Math.max(0, KOSCHMIEDER / range - clear));
        }

        /**
         * Share of light passing through d m to a point {@code rise} m above the eye (negative below): haze and clean air
         * thin with altitude ({@link Sight#HAZE_HEIGHT}, {@link Sight#AIR_HEIGHT}), weather does not; along sea-level ground, as in
         * {@link Sight#transmittance(double, double)}.
         */
        public double transmittance(double d, double rise) {
            return Math.exp(-d * (haze * along(rise / HAZE_HEIGHT) + clean * along(rise / AIR_HEIGHT) + weather));
        }
    }

    /**
     * Display channel wavelengths, nm: dominant wavelengths of the sRGB red, green and blue primaries (≈ 611, 549, 464 nm), rounded.
     * Light passing through air is computed at these.
     */
    static final double[] CHANNEL_NM = {610, 550, 465};
    /**
     * Wavelength exponent of Rayleigh optical depth in the visible: τ ∝ λ^−4.08 with the refractive index and King factor
     * dispersion (Bucholtz 1995, Appl. Opt. 34, table 3 fit for 0.5–1 µm: 4.08 at sea level).
     */
    static final double RAYLEIGH_EXPONENT = 4.08;
    /** Ångström exponent of haze optical depth, τ ∝ λ^−α: 1.3 for continental aerosol (Ångström 1964; Iqbal 1983, §6.13). */
    static final double ANGSTROM = 1.3;
    /** Wavelength of {@link #RAYLEIGH} and of visibility range (Koschmieder: eye's peak sensitivity), nm. */
    static final double REFERENCE_NM = 550;

    /**
     * Relative optical air mass toward a body at elevation with sine {@code sinElevation} (Kasten &amp; Young 1989, Appl. Opt. 28,
     * 4735): m = 1 / (sin h + 0.50572·(h + 6.07995°)^−1.6364), 1 overhead, ≈ 3 at 19.3°, 38 at the horizon; below it as at the
     * horizon (the sun is then under the hills of the world's own sky, and its light fades with {@code day}).
     */
    public static double airMass(double sinElevation) {
        double h = Math.toDegrees(Math.asin(Math.max(0, Math.min(1, sinElevation))));
        return 1 / (Math.sin(Math.toRadians(h)) + 0.50572 * Math.pow(h + 6.07995, -1.6364));
    }

    /**
     * Vertical optical depth of the clear-day air at wavelength {@code nm}: clean air (Rayleigh, {@link #RAYLEIGH} × {@link #AIR_HEIGHT}
     * at 550 nm: 0.093) and haze of the clear-day visibility range ({@link Visibility#CLEAR}: Koschmieder extinction less clean air,
     * × {@link #HAZE_HEIGHT}: 0.22 at 550 nm), each with its own wavelength law — the same air as {@link Air#at}.
     */
    static double verticalDepth(double nm) {
        double x = REFERENCE_NM / nm, clear = KOSCHMIEDER / CLEAR, clean = Math.min(RAYLEIGH, clear);
        return clean * AIR_HEIGHT * Math.pow(x, RAYLEIGH_EXPONENT) + (clear - clean) * HAZE_HEIGHT * Math.pow(x, ANGSTROM);
    }

    /**
     * Direct sunlight through the air at sun elevation sine {@code sinElevation}, per display channel relative to the overhead sun, into
     * {@code into}: exp(−(m − 1)·τ_c) (Beer–Lambert along the air mass {@link #airMass}, {@link #verticalDepth} per channel). Overhead —
     * 1, 1, 1: the frame's daylight is white at noon (the eye is adapted to it); toward the horizon the long path removes blue
     * first — at m = 3 red over blue ×1.5 relative to noon (×1.85 absolute), the sun 0.53 as bright in green. One law at every
     * elevation, no threshold.
     */
    public static void sunThroughAir(double sinElevation, double[] into) {
        // relative to the formula's own overhead value (0.9997): exactly white at noon
        double m = airMass(sinElevation) - airMass(1);
        for (int c = 0; c < 3; c++) into[c] = Math.exp(-m * verticalDepth(CHANNEL_NM[c]));
    }

    /** Segment-mean fraction of initial extinction when it rises by x height scales: (1 − e^−x) / x. */
    static double along(double x) {
        return Math.abs(x) < 1e-4 ? 1 - 0.5 * x : -Math.expm1(-x) / x;
    }

    /**
     * Drawing a bright or small point: light, brightness × solid angle, is conserved.
     * Larger than {@link #MIN_PIXELS} and no brighter than white: unchanged; smaller: a spot this size, proportionally fainter;
     * brighter than screen white: wider, like glare in the eye or a photo, instead of brighter than white.
     *
     * @param radius     real radius, blocks
     * @param brightness brightness relative to screen white (dark body: 1, its pixel coverage)
     * @param t          light share passing through air ({@link #transmittance}); dark body: 1
     * @param d          distance, blocks
     * @param pixel      screen pixel angle, rad
     * @param out        {drawing radius at distance d, blocks; opacity 0..1}
     */
    public static void point(double radius, double brightness, double t, double d, double pixel, double[] out) {
        double r = radius / Math.max(d, 1e-6);
        double flux = brightness * t * r * r;
        double drawn = Math.max(Math.max(r, 0.5 * MIN_PIXELS * pixel), Math.sqrt(flux));
        out[0] = drawn * d;
        out[1] = Math.min(1, flux / (drawn * drawn));
    }

    /**
     * Dark body of radius radius at distance d (projectile body): a disk of real angular size; smaller than a
     * {@link #MIN_PIXELS} spot becomes a spot proportionally fainter than its area increase (covers the same sky).
     *
     * @param out {disk drawing radius at distance d, blocks; opacity 0..1}
     */
    public static void body(double radius, double d, double pixel, double[] out) {
        double angle = radius / Math.max(d, 1e-6), drawn = Math.max(angle, 0.5 * MIN_PIXELS * pixel);
        out[0] = drawn * d;
        out[1] = (angle / drawn) * (angle / drawn);
    }

    /**
     * Bright body (flash, fireball, plume) of radius radius at distance d, brightness seen relative to screen white
     * (with eye adaptation and air: {@link #adapted} × t).
     * <ul>
     * <li>Core: a disk of real size; smaller than a {@link #MIN_PIXELS} spot becomes a spot with the same light
     * ({@code seen·angle²}), fainter; above screen white, saturated and toward white.</li>
     * <li>Glare: halo in a photo and eye, core radius × (1 + {@link #GLARE}·ln(1 + seen)).</li>
     * <li>Veil: point light scattered in the eye and haze (Stiles–Holladay: veil brightness ∝ E/θ², eye illuminance
     * E ∝ seen·angle²), visible over background to angle {@code angle·√(VEIL·seen)}. Daytime sky background gives less than two
     * core radii (no veil); at night it spans degrees, capped at {@link #VEIL_MAX}.</li>
     * </ul>
     *
     * @param out {core radius, blocks; core-disk share covered by body; on-screen core brightness relative to white
     *             (above 1: overexposure); glare radius; veil radius, 0 unless wider than glare}, at distance d
     */
    public static void light(double radius, double seen, double d, double pixel, double[] out) {
        double angle = radius / Math.max(d, 1e-6), drawn = Math.max(angle, 0.5 * MIN_PIXELS * pixel), cover = (angle / drawn) * (angle / drawn);
        double core = drawn * d, glare = core * (1 + GLARE * Math.log1p(Math.max(0, seen)));
        double veil = Math.min(angle * Math.sqrt(VEIL * Math.max(0, seen)), VEIL_MAX) * d;
        out[0] = core;
        out[1] = cover;
        out[2] = seen * cover;
        out[3] = glare;
        out[4] = veil > glare ? veil : 0;
    }

    /**
     * Apparent light brightness multiplier at skylight ambient ({@link FarView#ambient}: 1 by day, 0.17 at night, 0.12 on a
     * stormy night): eye and exposure adapt to background. Real day/night sky difference is 10⁶–10⁷; power
     * {@link #ADAPT} gives ~1200 at night: a flash kilometers away dazzles and lights the sky without filling the screen.
     */
    public static double adapted(double brightness, double ambient) {
        return brightness / Math.pow(ambient, ADAPT);
    }

    /**
     * One frame eye adaptation for all conventional-weapon light (fire body, glare/veil, light on smoke,
     * particles and world): eye and exposure adapt to surrounding skylight ({@link #adapted}: ambient^{@value #ADAPT}
     * relative to day) and fire light at the eye together, fire (white-wall illuminance at the eye from fireballs, plumes
     * and fires; noon white wall = 1). Without fire, {@link #adapted}. Apparent brightness multiplier for light of brightness 1.
     */
    public static double exposure(double ambient, double fire) {
        return 1 / (Math.pow(ambient, ADAPT) + Math.max(0, fire));
    }

    /**
     * On-screen light of an emissive rgb body (sRGB, largest channel 255), brightness lum relative to screen white (with
     * eye adaptation): full strength above white, overexposure whitens toward {@link #WHITE} (like {@link #light} core);
     * dimmer is darker in linear light, converted to sRGB for screen (0.1 white is 0.35 screen, not 0.1); × share. sRGB 0..1.
     */
    public static float[] onScreen(int rgb, double lum, float share) {
        return onScreen((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, lum, share);
    }

    /** Same for (r, g, b) color in sRGB 0..1. */
    public static float[] onScreen(float r, float g, float b, double lum, float share) {
        double k = Math.min(1, Math.max(0, lum)), white = lum > 1 ? Math.min(1, Math.log(lum) / Math.log(WHITE)) : 0;
        float[] in = {r, g, b}, out = new float[3];
        for (int i = 0; i < 3; i++) {
            double c = Math.max(0, Math.min(1, in[i])), l = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
            out[i] = (float) ua.zentix.incoming.client.fx.plume.FlameModel.srgb((l + (1 - l) * white) * k) * share;
        }
        return out;
    }
}
