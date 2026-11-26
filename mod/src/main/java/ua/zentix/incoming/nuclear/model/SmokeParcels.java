package ua.zentix.incoming.nuclear.model;

/**
 * Smoke parcels: the haze over and downwind of a nuclear fire zone ({@code docs/design/time.md} §8.3, rule 4). Pure law shared by
 * the server (black rain, {@link BlackRainModel}) and every client (the drawn haze, {@code client/nuclear/PallField}), derived
 * from the strike's clock anchors alone: no packet per parcel.
 * <ul>
 *     <li><b>Emission.</b> Each real second the fire zone emits a parcel at the smoke law's rate for the fire's strength at its
 *     strike clock S ({@link FireStormLaw#smoke} at the nominal peak). The fire never flares when the day compresses it: on a short
 *     day it emits less in total, because it burns for less real time.</li>
 *     <li><b>Motion.</b> A parcel lives on the motion age M (real time plus counted jumps): the wind carries the fire zone's disk of
 *     smoke downwind at {@link #wind}, and it widens by {@link #SPREAD} per metre travelled, its column thinning by the same
 *     factor (mass kept). At ground point (down, cross) from the epicentre (down along the wind) the haze is the newest parcel
 *     over it: over the zone the smoke leaving now, downwind of it the smoke that left the zone's downwind edge
 *     {@link #shift} / wind ago, across the wind the zone's smoke widened by that travel ({@link #axis}).</li>
 *     <li><b>Seen</b> while its vertical optical depth is at least {@link #GONE}, the pall's own visibility rule (95 % of the sun
 *     passes; {@link #law}), drawn fading out below it ({@link #carried}, {@link #FADE}). The picture draws the parcels at
 *     {@link #NODES} axis nodes ({@link #drawn}); black rain and its deposit fall only from a seen parcel, where the picture draws
 *     it.</li>
 * </ul>
 * Metres, seconds, wind toward (cos, sin) of {@code windDirRad}.
 *
 * @param fireRadiusM ground radius of the fire zone, m; 0 — no fires, no smoke
 */
public record SmokeParcels(double yieldKt, double fireRadiusM, double windDirRad, double windSpeedMps) {
    /** Visibility in smoky haze over the burning city at full strength, m. */
    public static final double HAZE_VISIBILITY_M = 900;
    /** Haze height over fires, m (at 15 kt; ∛yield): smoke rises above dust. */
    public static final double HAZE_TOP_M = 260;
    /** Koschmieder's contrast threshold: extinction = this / visibility. */
    static final double KOSCHMIEDER = 3.912;
    /** Vertical integral of the haze profile in units of its top ({@code PallField.column(0)}). */
    public static final double COLUMN = 0.548;
    /**
     * Widening per metre travelled downwind: the Hiroshima black-rain band, 5.5 km half-width 17 km beyond a ~2 km fire zone
     * (Uda 1953 via RERF).
     */
    public static final double SPREAD = 0.2;
    /** A parcel thinner than this vertical optical depth is not drawn (the pall's {@code GONE_DEPTH}: 95 % sun transmission). */
    public static final double GONE = 0.05;
    /** Calmer winds still carry smoke, m/s. */
    static final double MIN_WIND = 0.5;

    /** The parcels' clock, seconds since the burst: the motion age now and the strike clock at a past motion age. */
    public interface Clock {
        /** Motion age M now. */
        double motion();

        /** Strike clock S at the moment the motion age was {@code m} (m ≤ {@link #motion}). */
        double strikeAt(double m);

        /** Real time on both ages (no jumps): M = {@code seconds}, S at M from {@code strike} (seconds → seconds). */
        static Clock of(double seconds, java.util.function.DoubleUnaryOperator strike) {
            return new Clock() {
                @Override
                public double motion() {
                    return seconds;
                }

                @Override
                public double strikeAt(double m) {
                    return strike.applyAsDouble(m);
                }
            };
        }
    }

    /** Wind carrying the parcels, m/s. */
    public double wind() {
        return Math.max(windSpeedMps, MIN_WIND);
    }

    /** Haze top over the fires, m. */
    public double topM() {
        return HAZE_TOP_M * Math.cbrt(yieldKt / 15);
    }

    /** Vertical optical depth of full-strength smoke over the fire zone. */
    public double depth() {
        return KOSCHMIEDER / HAZE_VISIBILITY_M * topM() * COLUMN;
    }

    /** Widening of a parcel {@code s} metres downwind: its radius over the fire zone's, ≥ 1. */
    public double widen(double s) {
        return 1 + SPREAD * Math.max(s, 0) / Math.max(fireRadiusM, 1);
    }

    /**
     * Farthest travel at which a parcel of full strength is still seen ({@link #GONE}), m: the law's reach of what rains and
     * lands; 0 — no fires.
     */
    public double reach() {
        return reachAt(GONE);
    }

    /** Farthest travel at which a parcel of full strength is still drawn (its fade's end, {@link #GONE}/{@link #FADE}), m. */
    public double drawnReach() {
        return reachAt(GONE / FADE);
    }

    private double reachAt(double tau) {
        if (fireRadiusM <= 0) return 0;
        return Math.max(0, fireRadiusM * (depth() / tau - 1) / SPREAD);
    }

    /** Travel of the oldest drawn parcel now, m: as far as the first one has gone, no farther than any parcel is drawn. */
    public double length(Clock c) {
        return fireRadiusM <= 0 ? 0 : Math.min(wind() * Math.max(c.motion(), 0), drawnReach());
    }

    /**
     * The newest parcel over ground point {@code down} metres downwind of the epicentre: its travel past the fire zone's downwind
     * edge, m. Over the burning zone the smoke is the newest (0); downwind of it, the smoke that left the edge travel / wind ago.
     */
    public double shift(double down, Clock c) {
        return Math.max(0, Math.min(down - fireRadiusM, length(c)));
    }

    /**
     * Where the band's axis stands abreast of ground point {@code down}, m from the epicentre: the point itself along the wind, from
     * the epicentre to the oldest parcel's centre. The band across the wind is the fire zone's smoke widened by its parcel's travel.
     */
    public double axis(double down, Clock c) {
        return Math.max(0, Math.min(down, length(c)));
    }

    /** Emission strength (fraction of full fire) of the parcel at shift {@code s}: the fire's at its strike clock when it left. */
    public double emitted(double s, Clock c) {
        if (fireRadiusM <= 0) return 0;
        double left = c.motion() - Math.max(s, 0) / wind();
        return left <= 0 ? 0 : FireStormLaw.smoke(c.strikeAt(left), FireStormLaw.PEAK_S);
    }

    /** The law's column of the parcel that has travelled {@code s}, relative to full-strength smoke over the fire zone. */
    public double law(double s, Clock c) {
        return emitted(s, c) / widen(s);
    }

    /**
     * The drawn column of the parcel that has travelled {@code s}, relative to full-strength smoke over the fire zone: the law's
     * where it is seen ({@link #GONE} and thicker); below it, faded out to nothing at {@link #GONE}/{@link #FADE}.
     */
    public double carried(double s, Clock c) {
        double v = law(s, c), tau = v * depth(), low = GONE / FADE;
        if (tau >= GONE) return v;
        if (tau <= low) return 0;
        // presentation: cut at GONE, a parcel would vanish from a 0.05 column, a straight edge across the band where the fires'
        // strength or the widening crosses it; below GONE it fades out over its next halving instead (the law's column above)
        double f = (tau - low) / (GONE - low);
        return v * f * f * (3 - 2 * f);
    }

    /** A parcel thinner than {@link #GONE} fades out until it is this many times thinner (presentation). */
    public static final double FADE = 2;

    /** Nodes along the axis at which the parcels are drawn (the pall's haze row, {@code PallField.BINS}). */
    public static final int NODES = 128;

    /** Shift of axis node {@code j} of {@link #NODES} at axis length {@code length}, m. */
    public static double node(int j, double length) {
        return length * j / (NODES - 1);
    }

    /**
     * The parcels' strength as drawn at shift {@code s}: {@link #carried} at the axis nodes, linear between them (the pall's row,
     * read with linear filtering). What a client draws and where the server lets rain fall are this one function.
     */
    public double drawn(double s, Clock c) {
        double length = length(c);
        if (length <= 0) return carried(0, c);
        double x = Math.max(0, Math.min(1, s / length)) * (NODES - 1);
        int j = Math.min((int) x, NODES - 2);
        double a = carried(node(j, length), c), b = carried(node(j + 1, length), c);
        return a + (x - j) * (b - a);
    }

    /**
     * Whether ground point (x, z) metres from the epicentre lies under the band: within the fire zone's radius widened by its
     * parcel's travel ({@link #shift}) of the band's axis ({@link #axis}).
     */
    public boolean under(double x, double z, Clock c) {
        if (fireRadiusM <= 0) return false;
        double ux = Math.cos(windDirRad), uz = Math.sin(windDirRad);
        double down = x * ux + z * uz, g = axis(down, c);
        return Math.hypot(x - ux * g, z - uz * g) <= fireRadiusM * widen(shift(down, c));
    }
}
