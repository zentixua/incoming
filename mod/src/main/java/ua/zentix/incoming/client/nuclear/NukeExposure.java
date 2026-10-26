package ua.zentix.incoming.client.nuclear;

/**
 * Nuclear fireball exposure and frame exposure during the flash — presentation, not physics: ball brightness (black body at its temperature),
 * size, time, light pulse and energy belong to the models ({@code FireballModel}, {@code MushroomModel}); this only controls
 * how the frame shows them.
 *
 * <p><b>Ball like a conventional one</b> ({@code tools/bake_fx/pack.py}: {@code film_exposure}, {@code film}; #426): fire-based
 * exposure and a per-channel film curve. Relative to a white wall, the ball is hundreds or thousands of times brighter than white (15 kt core
 * ~300 at 3 s, ~5 000 at 1 s), so the frame clipped it to a uniform disk without folds or pattern. A conventional ball uses one fire
 * exposure for its entire burn; a nuclear core dims by ~4 000 times over 0.5–10 s, so one exposure would produce a white disk
 * at the start or a black one at the end. Thus the camera is set for fire and follows it partially ({@link #gain}): the visible
 * ball surface (cap-edge temperature) of brightness L (noon white walls, without eye adaptation) lies on the curve K·L^β
 * ({@link #KEY}, {@link #FOLLOW}): for 15 kt ~1.4 at the second maximum, ~1 at 0.5 s, ~0.8 at 1 s, ~0.55 at
 * 3 s, ~0.4 at 5 s — hotter is brighter and whiter; cooling makes it more orange and darker, while the surface temperature variation
 * (ball pattern, billow tongues) remains visible; identical by day and night: the camera is set for fire, not the sky. Dimmer
 * glow remains in frame units with eye adaptation to the sky, as before; smooth between them (x = E·(1 + (E/(K·L^β))²)^−½,
 * E = L × eye adaptation). Reference is the surface, not the inner core: as the cap rolls into a ring (from ~3 s for 15 kt),
 * its outside cools by mixing with air, the frame returns to scene exposure, and the hot interior between billows glows
 * as before (the cap's red glow lasts longer at night). Same curve as a conventional ball: per-channel ACES
 * (Narkowicz 2016 approximation, CC0), with channel coupling above white ({@link #WHITE}). K and β were tuned from sandbox frames,
 * not derived.
 *
 * <p><b>Ball light around it</b> (stem, dust, pall, world, particles) remains the model black body in frame units: haze and
 * dust wall in its light stay at full strength at night, as before. The on-screen ball brightness cap ({@link #cap}, as for a conventional ball:
 * {@code FxAtlas.fireLightAt}) applies only to what wraps around it and would otherwise merge with it into a single white disk: Wilson-cloud
 * shell ({@code wilsonLight} in {@code fx_pall}) and glare with veiling light ({@code NukeRenderer.fireballs}).
 *
 * <p><b>Frame during the flash</b> ({@link #overexposure}): the camera is set for what it saw before the flash and catches up with local
 * flash light using constant {@link #ADAPT_S} (in log brightness, like auto-exposure). Frame overexposure relative to the
 * set exposure is X = K^(e^(−u/τ)); K is the ratio of flash light at the camera at the second maximum to the light the frame
 * is set for; u is time after maximum: before and at maximum the frame is white; after ~0.25 s for 15 kt at 1.4 km, overexposure is
 * ~50 (dark silhouettes emerge), after 0.5 s ~3, after 0.75 s ~1.4, after 1 s as before. Previously the screen stayed white for the full pulse (1.2 s for
 * 15 kt), then faded for another 2–4 s. Player eye glare ({@code NukeFlash}: veil, retinal burns, afterimage) follows eye physics and
 * is unaffected.
 */
public final class NukeExposure {
    /** A ball surface as bright as one noon white wall maps to K on the curve (camera set for fire). */
    public static final double KEY = 0.233;
    /** How much curve input for a bright ball follows its brightness (in log space): 0 — constant ball brightness; 1 — relative to a white wall. */
    public static final double FOLLOW = 0.15;
    /** Overexposure above white: channel-coupling share, as for a conventional ball ({@code pack.py}: white). */
    public static final double WHITE = 0.35;
    /** Time constant for frame exposure catching up with the flash, s (presentation: camera auto-exposure). */
    public static final double ADAPT_S = 0.2;
    /**
     * Ring fractions ({@code MushroomModel.rollUp}) over which glow moves from the film curve to the previous path ({@link #filmShare};
     * ~5 to ~7 s for 15 kt, ~2.5 to ~3.7 s for 1 kt).
     */
    public static final double ROLL_FROM = 0.15, ROLL_TO = 0.45;

    private NukeExposure() {}

    /**
     * How many times darker ball exposure is than the frame when its surface emits {@code key} (linear, white wall = 1, with
     * eye adaptation to the sky {@code adapt}: 1 by day, ~1 200 at night).
     */
    public static double gain(double key, double adapt) {
        double r = Math.pow(Math.max(key, 0), 1 - FOLLOW) * Math.pow(Math.max(adapt, 1e-9), FOLLOW) / KEY;
        return 1 / Math.sqrt(1 + r * r);
    }

    /**
     * Share of mushroom glow on the film curve at ring fraction {@code roll} ({@code mushFilmShare}): all of it while the ball is a ball;
     * rolled cap follows the previous path, with the pall (overexposure into pixel coverage in one hue, {@link #pixel}): the per-channel
     * curve whitened its orange interior at night and changed it through haze (review #441); the mushroom at 7–10 s stays as before.
     */
    public static double filmShare(double roll) {
        double u = Math.max(0, Math.min(1, (roll - ROLL_FROM) / (ROLL_TO - ROLL_FROM)));
        return 1 - u * u * (3 - 2 * u);
    }

    /** Per-channel film curve (ACES, Narkowicz 2016 approximation): input is exposed light, output linear 0..1. */
    public static double film(double x) {
        double v = Math.max(x, 0);
        return Math.min(1, v * (2.51 * v + 0.03) / (v * (2.43 * v + 0.59) + 0.14));
    }

    /** Channel coupling above white: share toward white for a pixel whose brightest exposed channel is {@code top}. */
    public static double white(double top) {
        return WHITE * (1 - Math.exp(-Math.max(top - 1, 0)));
    }

    /**
     * Light {@code rgb} (linear, white wall = 1, with eye adaptation) on screen at ball exposure {@code gain}:
     * linear 0..1 per channel, into {@code out}.
     */
    public static void screen(double r, double g, double b, double gain, double[] out) {
        double xr = r * gain, xg = g * gain, xb = b * gain, w = white(Math.max(xr, Math.max(xg, xb)));
        out[0] = film(xr) + (1 - film(xr)) * w;
        out[1] = film(xg) + (1 - film(xg)) * w;
        out[2] = film(xb) + (1 - film(xb)) * w;
    }

    /**
     * On-screen ball and its light cap on what wraps around it ({@code mushBallCap} in {@code mushroom.glsl}): the ball
     * surface with light {@code rgb} (eye adaptation {@code adapt}) at exposure set by that surface — linear, per channel, into
     * {@code out}.
     */
    public static void cap(double r, double g, double b, double adapt, double[] out) {
        screen(r, g, b, gain(0.2126 * r + 0.7152 * g + 0.0722 * b, adapt), out);
    }

    /** {@link #cap} on screen — fraction of white (screen units, power 1/2.2), like conventional ball fire ({@code FxAtlas.fireLightAt}). */
    public static double capShown(double r, double g, double b, double adapt) {
        double[] c = new double[3];
        cap(r, g, b, adapt, c);
        return Math.pow(Math.min(1, 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]), 1 / 2.2);
    }

    /**
     * On-screen pall pixel — copy of the end of {@code main} in {@code fx_pall}: {@code light} is scattered light (linear, sum
     * along the ray with attenuation); {@code heat} is raw mushroom glow; {@code emitted} is the same at ball exposure (with the same
     * attenuation); {@code film} is the film-curve share ({@link #filmShare}); {@code cover} is pixel coverage by
     * matter (not haze); {@code out} receives screen color premultiplied by coverage (rgb). Previous path — glow with light
     * ({@link #shown}); curve — glow at film-curve exposure over light, in linear space; between them — blend
     * the finished images.
     */
    static void pixel(double[] light, double[] heat, double[] emitted, double film, double cover, double[] out) {
        double[] sum = new double[3];
        for (int c = 0; c < 3; c++) sum[c] = light[c] + heat[c];
        shown(sum, cover, out);
        if (film <= 0) return;
        double[] base = new double[3], f = new double[3];
        shown(light, cover, base);
        screen(emitted[0], emitted[1], emitted[2], 1, f);
        for (int c = 0; c < 3; c++) {
            double curve = Math.min(PallLight.display(PallLight.linear(base[c]) + f[c]), 1);
            out[c] += (curve - out[c]) * film;
        }
    }

    /**
     * Previous path ({@code pallShown} in {@code fx_pall}): light as matter (light / cover), above-white light goes into pixel coverage in the
     * same hue, toward white by {@code CROSS}, screen, shoulder above 0.6 in the brightest channel; × pixel coverage.
     */
    static void shown(double[] light, double cover, double[] out) {
        double shownCover = cover;
        double[] medium = new double[3];
        for (int c = 0; c < 3; c++) medium[c] = light[c] / Math.max(cover, 1e-4);
        double lum = 0.2126 * medium[0] + 0.7152 * medium[1] + 0.0722 * medium[2];
        if (lum > 1 && cover > 1e-4) {
            shownCover = Math.min(cover * lum, 1);
            for (int c = 0; c < 3; c++) medium[c] *= cover / shownCover;
        }
        double white = PallLight.CROSS * Math.max(0.2126 * medium[0] + 0.7152 * medium[1] + 0.0722 * medium[2] - 1, 0);
        double[] d = new double[3];
        for (int c = 0; c < 3; c++) d[c] = PallLight.display(medium[c] + white);
        double m = Math.max(d[0], Math.max(d[1], d[2])), k = m <= 0.6 ? 1 : (0.6 + 0.4 * (1 - Math.exp(-(m - 0.6) / 0.4))) / m;
        for (int c = 0; c < 3; c++) out[c] = d[c] * k * shownCover;
    }

    /**
     * Frame overexposure relative to the set exposure (≥ 1): {@code peak} is the ratio of camera flash light at the second maximum
     * to the light the frame is set for (≥ 1); {@code after} is game seconds after maximum (negative before it).
     */
    public static double overexposure(double peak, double after) {
        double k = Math.max(peak, 1);
        if (after <= 0) return k;
        return Math.pow(k, Math.exp(-after / ADAPT_S));
    }

    /** Seconds after maximum until frame overexposure {@link #overexposure} falls to {@code x} (> 1). */
    public static double settled(double peak, double x) {
        if (peak <= x) return 0;
        return ADAPT_S * Math.log(Math.log(peak) / Math.log(x));
    }
}
