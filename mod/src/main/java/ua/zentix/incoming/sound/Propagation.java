package ua.zentix.incoming.sound;

/**
 * Outdoor engine sound propagation and audibility: ISO 9613-2:1996, octave bands from 63 Hz to 8 kHz.
 * The caller supplies weather; the same calculation serves server flight selection and client sound levels.
 * <ul>
 * <li>Ear band level: L = L<sub>W</sub> − A<sub>div</sub> − A<sub>atm</sub> − A<sub>gr</sub> − shadow (eqs. 3, 4):
 * spreading 20·lg d + 11 (eq. 7), air α·d ({@link Air}, world surface humidity), ground (table 3), daytime shadow zone
 * (upward refraction).</li>
 * <li>Ground is meadow ({@link #MEADOW}, G = 1: grass, forest, farmland — most of Minecraft); source, receiver and middle
 * regions use height-dependent formulas a′–d′ of table 3.</li>
 * <li>Daytime shadow zone uses the distant-blast law ({@code client.sound.Outdoor}): rays touch ground at
 * r = √(2R)·(√hs + √hr), reaching 10 dB depth at 63 Hz and 20 dB from 2 kHz at 4r (even octave interpolation).</li>
 * <li>Audible while ear sound exceeds background in at least one octave ({@link #margin}): noise masks only within its
 * band (Fletcher critical bands, 1940; narrower than octaves above 500 Hz, so octave threshold is conservative), making
 * a tonal engine audible even below background's total A-level. Quiet rural background 34 dBA at night, 40 dBA by day
 * (ANSI/ASA S12.9-2013 part 3, table C.1, category 6 “very quiet rural”, from memory — ⚠ verify; rural home at night
 * 30–35 dBA — SANS 10103 via Eskom report; quiet nighttime area 32–35 dBA — Alaska DNR). Equal A-energy per octave
 * 125 Hz–4 kHz, 63 Hz and 8 kHz 10 dB lower (estimate: leaves, insects, distant road); rain and thunderstorm +3 dB each,
 * as distant blasts.</li>
 * </ul>
 * Obstacles (hill, house) not modeled here: server does not know them; client muffles its path itself (occlusion,
 * {@code SoundFilters}) — client audibility always lies within what the server sends.
 */
public final class Propagation {
    /** Octave center frequencies, Hz. */
    public static final double[] OCTAVES = {63, 125, 250, 500, 1000, 2000, 4000, 8000};
    public static final int BANDS = OCTAVES.length;
    /** Octave A-weighting, dB (IEC 61672-1, table 3). */
    static final double[] A_WEIGHT = {-26.2, -16.1, -8.6, -3.2, 0, 1.2, 1.0, -1.1};
    /**
     * Octave air absorption, dB/m, humidity step {@link #HUMIDITY_STEPS} (1 %): rows are humidity 0..1, path uses nearest
     * ({@link #alpha}); within 1 % of the formula over world surface humidity (70–100 %).
     */
    private static final int HUMIDITY_STEPS = 100;
    private static final double[][] ALPHA = new double[HUMIDITY_STEPS + 1][BANDS];
    /** Octave background relative to total background, dB (A): equal 125 Hz–4 kHz, edges 10 dB quieter. */
    static final double[] BACKGROUND = new double[BANDS];
    /** ISO 9613-2 ground: G = 1 — porous (grass, forest, farmland). */
    public static final double MEADOW = 1;
    /** Ear height above ground, m (as distant blasts). */
    public static final double EAR = 1.7;
    /** Night/day background, dBA; rain and thunderstorm increase it, dB per level 0..1 (⚠ estimate: no condition-specific background measurements). */
    public static final double NIGHT = 34, DAY = 40, RAIN = 3, THUNDER = 3;
    /** Sound smoothly sinks into background over the last decibels above it (presentation: partial masking, as distant blasts). */
    public static final double FADE = 6;
    /**
     * Daytime shadow zone: √(2R), m^½ — a ray from a 2 m source to a 1.7 m ear touches ground exactly at 1 km (near-ground
     * temperature falls nearly at the dry adiabatic lapse rate, ~0.9 °C per 100 m); full depth by {@link #SHADOW_DEEP} times
     * the touch range; low (63 Hz) and high (from 2 kHz) depths, dB. ⚠ Estimate: no c(z) profile or measurements;
     * ISO 9613-2 does not provide this.
     */
    public static final double SHADOW_ROOT = 1000 / (Math.sqrt(2) + Math.sqrt(1.7)), SHADOW_DEEP = 4, SHADOW_LOW = 10, SHADOW_HIGH = 20;

    static {
        double sum = 0;
        for (int h = 0; h <= HUMIDITY_STEPS; h++) {
            // zero humidity is outside ISO 9613-1 formula's domain (oxygen relaxation frequency tends to zero): start at 1 %
            for (int b = 0; b < BANDS; b++) ALPHA[h][b] = Air.alphaAt(OCTAVES[b], Math.max(1, h) / (double) HUMIDITY_STEPS);
        }
        for (int b = 0; b < BANDS; b++) {
            BACKGROUND[b] = b == 0 || b == BANDS - 1 ? -10 : 0;
            sum += Math.pow(10, BACKGROUND[b] / 10);
        }
        for (int b = 0; b < BANDS; b++) BACKGROUND[b] -= 10 * Math.log10(sum);
    }

    private Propagation() {}

    /**
     * Weather for refraction, background and air absorption.
     *
     * @param day      0 — night, 1 — high sun ({@link #day}): louder daytime background
     * @param rain     rain 0..1
     * @param thunder  thunderstorm 0..1
     * @param shadow   0..1 — upward sound refraction into the shadow zone ({@link #refraction})
     * @param humidity surface relative humidity 0..1 ({@code util.Air#surfaceHumidity}): air absorbs highs
     */
    public record Weather(double day, double rain, double thunder, double shadow, double humidity) {
        public static final Weather NIGHT = new Weather(0, 0, 0, 0), NOON = new Weather(1, 0, 0, 1);

        /** Clear-weather humidity equals the recording reference conditions. */
        public Weather(double day, double rain, double thunder, double shadow) {
            this(day, rain, thunder, shadow, Air.HUMIDITY / 100);
        }

        /** Same weather under recording conditions ({@link Air#HUMIDITY}): air already baked into the distant recording. */
        public Weather recorded() {
            return new Weather(day, rain, thunder, shadow, Air.HUMIDITY / 100);
        }
    }

    /** Air absorption in octave b at humidity {@code humidity} (0..1), dB/m. */
    public static double alpha(int b, double humidity) {
        return ALPHA[(int) Math.round(Math.max(0, Math.min(1, humidity)) * HUMIDITY_STEPS)][b];
    }

    /**
     * Upward refraction into the shadow zone by Pasquill stability class: while temperature falls with height (unstable
     * and neutral A–D: standard atmosphere 6.5 K/km), sound speed decreases aloft, rays bend upward — full shadow; inversion
     * (E–F, clear night) bends them down — no shadow; smooth between D and E. Wind (upwind shadow, none downwind) ignored.
     */
    public static double refraction(double stability) {
        return Math.max(0, Math.min(1, ua.zentix.incoming.util.Stability.E - stability));
    }

    /**
     * Source-to-ear path.
     *
     * @param d  distance, m (= blocks)
     * @param hs source height above ground, m
     * @param hr ear height above ground, m
     * @param g  ground porosity 0..1
     */
    public record Path(double d, double hs, double hr, double g, Weather weather) {
        /** Meadow path: source at {@code hs} above ground under listener, ear at {@link #EAR}. */
        public static Path over(double d, double hs, Weather weather) {
            return new Path(d, Math.max(0, hs), EAR, MEADOW, weather);
        }
    }

    /** Current daylight for background: sun elevation sine — 0 at/below horizon, 1 by 30°. */
    public static double day(double sunSine) {
        return smoothstep(0, 0.5, sunSine);
    }

    /** Background, dBA. */
    public static double background(Weather w) {
        return NIGHT + (DAY - NIGHT) * w.day + RAIN * w.rain + THUNDER * w.thunder;
    }

    /** Octave b background, dB (A). */
    public static double background(int b, Weather w) {
        return background(w) + BACKGROUND[b];
    }

    /** Daytime shadow onset, m: source-to-ear ray touches ground there. */
    public static double shadowFrom(double hs, double hr) {
        return SHADOW_ROOT * (Math.sqrt(Math.max(0, hs)) + Math.sqrt(Math.max(0, hr)));
    }

    /** Shadow depth at frequency f, dB: 10 dB at 63 Hz, 20 from 2 kHz, even octave interpolation between. */
    public static double shadow(double f, double d, double hs, double hr, double day) {
        double from = shadowFrom(hs, hr);
        double k = Math.max(0, Math.min(1, Math.log(f / 63) / Math.log(2000.0 / 63)));
        return day * smoothstep(from, SHADOW_DEEP * from, d) * (SHADOW_LOW + (SHADOW_HIGH - SHADOW_LOW) * k);
    }

    /**
     * Octave b ground effect, dB (ISO 9613-2, table 3): negative — reflection adds sound.
     *
     * @param dp ground distance, m
     */
    static double ground(int b, double dp, double hs, double hr, double g) {
        double q = dp <= 30 * (hs + hr) ? 0 : 1 - 30 * (hs + hr) / dp;
        if (b == 0) return -1.5 - 1.5 - 3 * q;
        return region(b, hs, dp, g) + region(b, hr, dp, g) - 3 * q * (1 - g);
    }

    /** Source or receiver region at height h: As or Ar (table 3). */
    private static double region(int b, double h, double dp, double g) {
        double near = 1 - Math.exp(-dp / 50);
        double k = switch (b) {
            case 1 -> 1.5 + 3.0 * Math.exp(-0.12 * sq(h - 5)) * near + 5.7 * Math.exp(-0.09 * h * h) * (1 - Math.exp(-2.8e-6 * dp * dp));
            case 2 -> 1.5 + 8.6 * Math.exp(-0.09 * h * h) * near;
            case 3 -> 1.5 + 14.0 * Math.exp(-0.46 * h * h) * near;
            case 4 -> 1.5 + 5.0 * Math.exp(-0.9 * h * h) * near;
            default -> 0;
        };
        return b <= 4 ? -1.5 + g * k : -1.5 * (1 - g);
    }

    /** Maximum path gain, dB: ground at 63 Hz (−6 dB attenuation) and A-weighting (+1.2 dB at 2 kHz). */
    static final double MAX_GAIN = 6 + 1.2;

    /** Spherical spreading, dB (eq. 7). */
    public static double divergence(double d) {
        return 20 * Math.log10(Math.max(1, d)) + 11;
    }

    /** Background in the quietest octave, dB (A). */
    static double quietest(Weather w) {
        double min = Double.POSITIVE_INFINITY;
        for (double b : BACKGROUND) min = Math.min(min, b);
        return background(w) + min;
    }

    /** Path attenuation in octave b (spreading, air, ground, shadow), dB. */
    public static double attenuation(int b, Path p) {
        double d = Math.max(1, p.d);
        double dp = Math.sqrt(Math.max(1, d * d - sq(p.hs - p.hr)));
        return divergence(d) + alpha(b, p.weather.humidity) * d + ground(b, dp, p.hs, p.hr, p.g)
                + shadow(OCTAVES[b], d, p.hs, p.hr, p.weather.shadow);
    }

    /**
     * Ear level from a source of power {@code lw} and octave spectrum {@code shape}: total (A) and each octave
     * (A, in {@code out}, which may be {@code null}), dB.
     */
    public static double level(double lw, Spectrum shape, Path p, double[] out) {
        double sum = 0;
        for (int b = 0; b < BANDS; b++) {
            double l = lw + shape.z(b) + A_WEIGHT[b] - attenuation(b, p);
            if (out != null) out[b] = l;
            sum += Math.pow(10, l / 10);
        }
        return 10 * Math.log10(Math.max(sum, 1e-30));
    }

    /**
     * Sound octaves {@code bands} (dB, A, as written by {@link #level}) above background in the most favorable octave, dB
     * (negative — inaudible).
     */
    public static double margin(double[] bands, Weather w) {
        double best = Double.NEGATIVE_INFINITY;
        for (int b = 0; b < BANDS; b++) best = Math.max(best, bands[b] - background(b, w));
        return best;
    }

    /**
     * Audible source range, m: where every ear octave sinks into background (at most {@code limit}). Level only decreases
     * with distance (ground and shadow vary more slowly than spreading), hence bisection in log distance.
     */
    public static double range(double lw, Spectrum shape, double hs, Weather w, double limit) {
        double[] bands = new double[BANDS];
        if (audible(lw, shape, Path.over(limit, hs, w), bands)) return limit;
        double lo = 1, hi = limit;
        if (!audible(lw, shape, Path.over(lo, hs, w), bands)) return 0;
        for (int i = 0; i < 40; i++) {
            double mid = Math.sqrt(lo * hi);
            if (audible(lw, shape, Path.over(mid, hs, w), bands)) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    private static boolean audible(double lw, Spectrum shape, Path p, double[] bands) {
        level(lw, shape, p, bands);
        return margin(bands, p.weather) > 0;
    }

    /** Octave b A-weighting, dB. */
    public static double aWeight(int b) {
        return A_WEIGHT[b];
    }

    /** Smooth 0→1 step between a and b. */
    public static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static double sq(double x) {
        return x * x;
    }
}
