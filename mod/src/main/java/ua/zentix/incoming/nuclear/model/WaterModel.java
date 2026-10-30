package ua.zentix.incoming.nuclear.model;

/**
 * Water under a nuclear detonation and its blast wave (DESIGN-nuke §6, “Water”): ripples and foam under the wind behind
 * the front (a dark ring on the water), spray off the crests, and for a burst on or low above water — a hollow column
 * of water with a spray crown on top and a base surge. Meters, seconds, kilotons; wind — m/s
 * ({@link BlastModel#windSpeed}).
 * <p>
 * Numbers — from the Beaufort scale (ripples from “cat's paws” at 1.5 m/s, spray tearing off the crests from force 7–8,
 * foam in streaks from 10) and from Glasstone, Dolan 1977, §2.47–2.65: “Baker” (Crossroads, 1946, 23 kt at 27 m depth)
 * — a column ~600 m across and 1.8 km high, walls ~90 m, base surge — from ~10 s, when the column's water starts to
 * fall, at first ~25–30 m/s, by 4 minutes — about 2.5–3 km in radius and ~550 m high; a shallow underwater 100 kt burst
 * — a column above 1.5 km in 2 s, by 12 s 1 km across with walls 150 m, by 20 s it subsides and narrows to 600 m. Time
 * and sizes — by the cube root of the yield from “Baker”. Condensation cloud (a fog dome for a second or two) — a
 * Wilson cloud behind the front, as for any burst in humid air ({@code BlastWall}). A burst on or above the surface
 * throws up less water than an underwater one: the column is lower ({@link #SURFACE_COLUMN}), the wave smaller
 * ({@link #SURFACE_SURGE}); here — a surface burst (the mod has no underwater ones), and for it Glasstone, Dolan has no
 * numbers of its own: the fractions are estimates.
 */
public final class WaterModel {
    /** Ripples: from what wind the water darkens, and from what wind it cannot get darker, m/s. */
    static final double RIPPLE_FROM = 1.5, RIPPLE_FULL = 18;
    /**
     * Spray: from what wind it tears off the crests, and from what wind a solid wall of spray stands over the water,
     * m/s.
     */
    static final double SPRAY_FROM = 14, SPRAY_FULL = 45;
    /** Foam: from what wind the water whitens in streaks, and from what wind it is all white, m/s. */
    static final double FOAM_FROM = 25, FOAM_FULL = 65;
    /** Height of the spray wall, m: at weak tearing and at the strongest wind. */
    static final double SPRAY_LOW = 6, SPRAY_HIGH = 45;
    /**
     * How many seconds the spray wall lags behind the front: the wind behind the blast wave tears off the crests at
     * once, but the droplet cloud takes its height (tens of m, droplets rise at tens of m/s) in fractions of a second —
     * the dark ring of ripples runs ahead of the white wall.
     */
    public static final double SPRAY_LAG = 0.5;
    /** “Baker” scale, kt: the column and the wave grow as the cube root of the yield. */
    private static final double BAKER_KT = 23;
    /**
     * The “Baker” column: outer radius (m; 2000 ft across), height (m), rise constant (s; chosen so that
     * a shallow underwater 100 kt column is above 5000 ft in 2 s: the initial speed by this law is ~3700 ft
     * per second; the “Baker” spray dome in Glasstone, Dolan is 2500); for a surface burst — a fraction of its height.
     */
    static final double COLUMN_RADIUS = 300, COLUMN_TOP = 1800, COLUMN_RISE = 1.6, SURFACE_COLUMN = 0.6;
    /** Wall thickness of the column — a fraction of its outer radius (for 100 kt — 500 ft at 3300 ft across). */
    static final double COLUMN_WALL = 0.3;
    /** When the column's water starts to fall, s (for “Baker”), and how long it takes to fall. */
    static final double COLUMN_FALL = 10, COLUMN_FALL_SPAN = 25;
    /**
     * The column narrows as it settles: for 100 kt — from 3300 ft across at the 12th second to 2000 ft at the 20th (the
     * outer spray curtain falls first and goes into the base surge); radius fraction and time for “Baker”, s.
     */
    static final double COLUMN_SHRINK = 0.4, SHRINK_FROM = 7.4, SHRINK_TO = 12.3;
    /**
     * The crown — the top of the column, where the jets stop and open into spray: radius — a fraction of the column's
     * outer radius, half-height — a fraction of its radius before narrowing (estimate: for “Baker” the “cauliflower” on
     * top is three times wider than the column, but most of it is steam and bubble gas that left through the hollow
     * column — for a surface burst this is the mushroom cap). The crown — the outermost spray; it falls with the outer
     * curtain ({@link #COLUMN_SHRINK}): the settling column has none.
     */
    static final double CROWN_R = 1.4, CROWN_H = 0.35;
    /** “Baker” base surge: start (s), radius limit (m), constant (s), height (m), time to fade (s, e-folding). */
    static final double SURGE_START = 8, SURGE_RADIUS = 3000, SURGE_TAU = 100, SURGE_HEIGHT = 550, SURGE_FADE = 240;
    /**
     * Base surge of a surface burst — a fraction of the “Baker” one (radius growth and height): over deep water a
     * surface burst throws up less water, and its wave is smaller than an underwater one (Glasstone, Dolan, ch. II);
     * the fraction is an estimate, like {@link #SURFACE_COLUMN} for the column.
     */
    static final double SURFACE_SURGE = 0.7;
    /** A burst above this fraction of the largest fireball radius is no longer “water”: there is no column or wave. */
    static final double WET_FULL = 0.15, WET_NONE = 0.5;

    private WaterModel() {}

    /** How much the water under the wind darkens with ripples, 0..1. */
    public static double ripple(double windMs) {
        return smooth(RIPPLE_FROM, RIPPLE_FULL, windMs);
    }

    /** Spray tearing off the crests, 0..1. */
    public static double spray(double windMs) {
        return smooth(SPRAY_FROM, SPRAY_FULL, windMs);
    }

    /** Foam on the water, 0..1. */
    public static double foam(double windMs) {
        return smooth(FOAM_FROM, FOAM_FULL, windMs);
    }

    /** Height of the spray wall above the water, m (0 — the wind does not tear off spray). */
    public static double sprayHeight(double windMs) {
        double s = spray(windMs);
        return s <= 0 ? 0 : SPRAY_LOW + (SPRAY_HIGH - SPRAY_LOW) * s;
    }

    /**
     * The dark ring of the front: how much darker the water is (0..1 fraction of its light) where the front passed
     * {@code since} game ticks ago, and the wind behind it was {@code windMs}. At the front itself — a strip of ripples
     * (the air behind the front blows while the positive phase runs: {@code band} game ticks), behind it the water
     * stays rough for a long time ({@code calm} ticks), ahead of the front — smooth.
     */
    public static double darkness(double windMs, double since, double band, double calm) {
        if (since < 0) return 0;
        return 0.6 * ripple(windMs) * (0.3 + 0.7 * Math.exp(-since / band)) * Math.exp(-since / calm);
    }

    /** Foam behind the front, 0..1: appears within a few ticks, drains over {@code calm} / 4. */
    public static double whitecaps(double windMs, double since, double calm) {
        if (since < 0) return 0;
        return 0.7 * foam(windMs) * smooth(0, 8, since) * Math.exp(-since / (calm / 4));
    }

    // ---------------------------------------------------------------- detonation on water

    /**
     * How much of a “water” detonation it is, 0..1: on water or below 0.15 of the largest fireball radius — all of it,
     * above half the radius — none (the water ball barely touches it). For 15 kt: up to ~40 m — fully; by ~130 m it
     * fades to nothing.
     */
    public static double wetBurst(double hobM, double yieldKt) {
        double rf = FireballModel.maxRadius(yieldKt, true);
        return 1 - smooth(WET_FULL * rf, WET_NONE * rf, hobM);
    }

    /** “Baker” scale for yield: cube root. */
    static double bakerScale(double yieldKt) {
        // and for an ordinary charge (kilograms — millionths of a kiloton, WaterBlast): zero does not give division by
        // zero
        return Math.cbrt(Math.max(yieldKt, 1e-12) / BAKER_KT);
    }

    /** Outer radius of the hollow water column before narrowing, m. */
    public static double columnRadius(double yieldKt) {
        return COLUMN_RADIUS * bakerScale(yieldKt);
    }

    /**
     * Largest radius of the surface-burst base surge beyond the column wall, m (an ordinary explosion on water — the
     * same law).
     */
    public static double surgeReach(double yieldKt) {
        return SURFACE_SURGE * SURGE_RADIUS * bakerScale(yieldKt);
    }

    /** Height of the surface-burst base surge, m. */
    public static double surgeTop(double yieldKt) {
        return SURFACE_SURGE * SURGE_HEIGHT * bakerScale(yieldKt);
    }

    /** Column wall thickness, m. */
    public static double columnWall(double yieldKt) {
        return COLUMN_WALL * columnRadius(yieldKt);
    }

    /**
     * Top of the column at time t, m: grows toward {@link #SURFACE_COLUMN} of the “Baker” height (a surface burst
     * throws up less water than an underwater one) with the constant {@link #COLUMN_RISE} s, falling — it settles back
     * to the water.
     */
    public static double columnTop(double t, double yieldKt) {
        return columnRise(t, yieldKt) * (1 - columnFall(t, yieldKt));
    }

    /** How high the column's water would have risen by time t without falling, m. */
    private static double columnRise(double t, double yieldKt) {
        if (t <= 0) return 0;
        double s = bakerScale(yieldKt);
        return COLUMN_TOP * SURFACE_COLUMN * s * (1 - Math.exp(-t / (COLUMN_RISE * s)));
    }

    /** How much of the column's water has already fallen, 0..1. */
    public static double columnFall(double t, double yieldKt) {
        double s = bakerScale(yieldKt);
        return smooth(COLUMN_FALL * s, (COLUMN_FALL + COLUMN_FALL_SPAN) * s, t);
    }

    /**
     * The column at time t, m: the middle of the wall and its half-thickness in radius, the top of the wall (middle of
     * the crown), the outer edge of the crown and its half-height, and the fraction of water not yet fallen. The wall —
     * from the water to the middle of the crown; the crown — a ring on top of the wall: in section an ellipse centred
     * on the middle of the wall (opened wider than the column, it also narrows its cavity from above).
     */
    public record Column(double mid, double half, double wallTop, double crownR, double crownH, double water) {
        /** Top of the crown above the water, m. */
        public double top() {
            return wallTop + crownH;
        }

        /** Radius semi-axis of the crown's section, m: from the middle of the wall to its outer edge. */
        public double crownA() {
            return crownR - mid;
        }

        /** Outer edge of the crown or wall, m. */
        public double outer() {
            return Math.max(mid + half, crownR);
        }
    }

    /**
     * The column of water at time t; null — not yet risen or already fallen. The crown opens when the jets slow down
     * (fraction of the rise), and falls with the outer curtain while the column narrows ({@link #COLUMN_SHRINK}); after
     * that the top of the wall is a rounded edge as thick as the wall (the crown's section is a circle as thick as the
     * wall). While the column is below the crown (the first fractions of a second, the end of the fall), its middle is
     * under water: above water — its top. The wall thickness is the same.
     */
    public static Column column(double t, double yieldKt) {
        double top = columnTop(t, yieldKt), fall = columnFall(t, yieldKt);
        if (top <= 0 || fall >= 1) return null;
        double s = bakerScale(yieldKt), r = columnRadius(yieldKt), half = 0.5 * columnWall(yieldKt);
        double shrink = smooth(SHRINK_FROM * s, SHRINK_TO * s, t), outer = r * (1 - COLUMN_SHRINK * shrink);
        double open = (1 - Math.exp(-t / (COLUMN_RISE * s))) * (1 - shrink);
        double crownH = Math.max(CROWN_H * r * open, half), crownR = outer * (1 + (CROWN_R - 1) * open);
        return new Column(outer - half, half, top - crownH, crownR, crownH, 1 - fall);
    }

    /**
     * How much of the column's water is visible as spray, 0..1: while the fireball shines, the water in it and at it is
     * steam (it cannot be covered), the column shows through as the fireball cools — over 4–20 t_max of its second
     * maximum, like the stem dust ({@link MushroomModel}): for 15 kt — over 0.5–2.5 s.
     */
    public static double columnShow(double t, double yieldKt) {
        return smooth(4, 20, t / FireballModel.secondMaximumSeconds(yieldKt));
    }

    /** When the base surge appears, s. */
    public static double surgeStart(double yieldKt) {
        return SURGE_START * bakerScale(yieldKt);
    }

    /**
     * Radius of the surface-burst base surge at time t, m (0 — not yet risen): from the column wall, at first ~20 m/s
     * ({@link #SURFACE_SURGE} of the “Baker” ~30 m/s).
     */
    public static double surgeRadius(double t, double yieldKt) {
        double s = bakerScale(yieldKt), since = t - surgeStart(yieldKt);
        if (since <= 0) return 0;
        return columnRadius(yieldKt) * 1.2 + SURFACE_SURGE * SURGE_RADIUS * s * (1 - Math.exp(-since / (SURGE_TAU * s)));
    }

    /** Height of the surface-burst base surge at time t, m ({@link #SURFACE_SURGE} of “Baker”). */
    public static double surgeHeight(double t, double yieldKt) {
        double s = bakerScale(yieldKt), since = t - surgeStart(yieldKt);
        if (since <= 0) return 0;
        return SURFACE_SURGE * SURGE_HEIGHT * s * (0.25 + 0.75 * smooth(0, 60 * s, since));
    }

    /** Density of the base surge 0..1: appears within a few seconds, fades over minutes. */
    public static double surgeDensity(double t, double yieldKt) {
        double s = bakerScale(yieldKt), since = t - surgeStart(yieldKt);
        if (since <= 0) return 0;
        return smooth(0, 4 * s, since) * Math.exp(-since / (SURGE_FADE * s));
    }

    static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }
}
