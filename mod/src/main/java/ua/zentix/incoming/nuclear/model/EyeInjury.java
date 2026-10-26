package ua.zentix.incoming.nuclear.model;

/**
 * Eyes and flash (Glasstone &amp; Dolan 1977, §12.83–12.88): flash blindness — temporary bleaching of the whole visual
 * field; retinal burn — a permanent blind spot where the ball was imaged on the retina.
 *
 * <p>Reference: fig. 12.88a (clear day) and 12.88b (night): ground-observer ranges for a burst at 10 000 ft, within which
 * blindness lasts about 10 s (unable to read instruments in dim light) and a person looking at the ball suffers retinal
 * burn (0.25 s blink already included: protects only against large yields). Night ranges are longer: wider pupil and
 * stronger bleaching of a dark-adapted eye (§12.84). The mod's bursts are below 10 000 ft, with more air on the path to a
 * distant eye: figure ranges are upper estimates for them.
 *
 * <p>Bleaching increases with eye fluence: 10 s at the figure's range, longer closer in, logarithmically with fluence
 * (like recovery after bleaching), capped by recovery time after complete pigment bleaching: daytime — about a minute
 * (cones; clinical photostress test), nighttime — about 10 minutes (cones reach the dark-adaptation plateau and see
 * moonlit terrain again; rods need up to half an hour for starlight). Full-field bleaching occurs at retinal exposure
 * {@link #FULL_BLEACH_TD_S} from flash-lit ground in front of the eye (est.). G&amp;D gives “a few seconds to minutes”
 * for blindness (§12.84).
 */
public final class EyeInjury {
    /** Anchor yields of fig. 12.88, kt. */
    private static final double[] YIELDS = {1, 10, 100, 1000, 10000};
    /** 10 s flash blindness, miles from epicenter: day, night (burst at 10 000 ft, read from figure ±1 mile). */
    private static final double[] FLASH_DAY_MI = {5.7, 12.7, 19.8, 15.1, 27.8};
    private static final double[] FLASH_NIGHT_MI = {40.3, 60, 67, 53.7, 74};
    /** Retinal burn, miles from epicenter: day, night. */
    private static final double[] RETINA_DAY_MI = {11.0, 18.5, 23.9, 32.7, 54.9};
    private static final double[] RETINA_NIGHT_MI = {19.3, 30.5, 42.7, 56.5, 84};
    private static final double MILE_M = 1609.344, FIGURE_HOB_M = 3048;
    /** Visibility used for the figure: clear (same as “clear” for thermal light, {@link ThermalModel#VISIBILITY_CLEAR}). */
    private static final double FIGURE_VISIBILITY = ThermalModel.VISIBILITY_CLEAR;

    /** Blindness at the figure's range limit, s. */
    public static final double EDGE_S = 10;
    /** Eye recovery after full pigment bleaching, s: day, night (cone dark-adaptation plateau). */
    public static final double FULL_DAY_S = 60, FULL_NIGHT_S = 600;
    /**
     * Retinal exposure bleaching almost all pigment (95 %), td·s: three cone bleaching constants
     * (~5·10⁶ td·s, Rushton) — estimate.
     */
    public static final double FULL_BLEACH_TD_S = 1.5e7;
    /** Ground albedo in front of the eye, fireball light luminous efficacy (~6000 K), lm/W. */
    private static final double GROUND_ALBEDO = 0.2, LUMENS_PER_WATT = 93;
    /** How many light levels darker the night sky is than daytime ({@code Level.updateSkyBrightness}). */
    private static final double NIGHT_DARKEN = 11;
    /** Pupil, mm: daytime and dark-adapted. */
    private static final double PUPIL_DAY_MM = 3, PUPIL_NIGHT_MM = 7;

    private EyeInjury() {}

    /**
     * Eye dark adaptation (0 — day, 1 — night) at eye light 0..15 (maximum of sky and block): night sky is 11 levels darker
     * than day, giving skylight 4 under open sky; darker still (basement, cave) is also night.
     */
    public static double darkness(int light) {
        return clamp((15 - light) / NIGHT_DARKEN);
    }

    /**
     * Seconds the eye is blinded (field bleached enough to obscure dim objects) at eye fluence {@code q} cal/cm².
     *
     * @param dark eye dark adaptation at flash time: 0 — day, 1 — night without lights
     */
    public static double dazzleSeconds(double q, double yieldKt, double dark) {
        if (q <= 0) return 0;
        double edge = edgeFluence(yieldKt, dark), full = Math.max(edge * 1.01, fullBleachFluence(dark));
        double longest = mixLog(FULL_DAY_S, FULL_NIGHT_S, dark);
        double t = EDGE_S + (longest - EDGE_S) * Math.log(q / edge) / Math.log(full / edge);
        return Math.max(0, Math.min(longest, t));
    }

    /** Eye fluence causing {@link #EDGE_S} blindness: at the figure's range, cal/cm². */
    static double edgeFluence(double yieldKt, double dark) {
        double day = fluenceAt(FLASH_DAY_MI, yieldKt), night = fluenceAt(FLASH_NIGHT_MI, yieldKt);
        return Math.exp(Math.log(day) + (Math.log(night) - Math.log(day)) * clamp(dark));
    }

    /**
     * Eye fluence bleaching the whole field (light from illuminated ground ahead), cal/cm². Ground here receives the full
     * fluence like a surface facing the ball: a low ball gives less direct light on flat ground (fraction is its elevation
     * sine), but the sky around it glows with scattered light — upper estimate.
     */
    static double fullBleachFluence(double dark) {
        double pupil = PUPIL_DAY_MM + (PUPIL_NIGHT_MM - PUPIL_DAY_MM) * clamp(dark);
        double area = Math.PI * pupil * pupil / 4;
        // cal/cm² → J/m² → lm·s/m² → cd·s/m² of Lambertian ground → td·s
        double tdPerCal = 4.184e4 * LUMENS_PER_WATT * GROUND_ALBEDO / Math.PI * area;
        return FULL_BLEACH_TD_S / tdPerCal;
    }

    /**
     * Slant range to retinal burn for a person looking at the ball at visibility {@code visibilityM}, m: energy per image
     * area is independent of range, attenuated only by air (§12.86), so the boundary has the same air transmittance as the
     * figure's clear-weather range. Figure is for an airburst; a surface burst's ball is dimmer (light fraction and larger
     * ball), but no retinal data exists for it — same range, upper estimate.
     */
    public static double retinaRange(double yieldKt, double dark, double visibilityM) {
        double day = slant(at(RETINA_DAY_MI, yieldKt)), night = slant(at(RETINA_NIGHT_MI, yieldKt));
        return (day + (night - day) * clamp(dark)) * visibilityM / FIGURE_VISIBILITY;
    }

    /** Fluence at figure range {@code miles} in clear weather (airburst), cal/cm². */
    private static double fluenceAt(double[] miles, double yieldKt) {
        return ThermalModel.fluenceCalPerCm2(slant(at(miles, yieldKt)), yieldKt, false, FIGURE_VISIBILITY);
    }

    /** Slant range to a burst at the figure's height, m. */
    private static double slant(double groundM) {
        return Math.hypot(groundM, FIGURE_HOB_M);
    }

    /** Figure range, m: linear in log yield; yield clamped to the figure's limits. */
    private static double at(double[] miles, double yieldKt) {
        double x = Math.log10(Math.max(YIELDS[0], Math.min(YIELDS[YIELDS.length - 1], yieldKt)));
        int i = Math.min(YIELDS.length - 2, (int) Math.floor(x));
        double f = x - i;
        return (miles[i] + (miles[i + 1] - miles[i]) * f) * MILE_M;
    }

    private static double mixLog(double day, double night, double dark) {
        return Math.exp(Math.log(day) + (Math.log(night) - Math.log(day)) * clamp(dark));
    }

    private static double clamp(double dark) {
        return Math.max(0, Math.min(1, dark));
    }
}
