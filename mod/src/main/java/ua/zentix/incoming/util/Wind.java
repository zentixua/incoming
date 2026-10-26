package ua.zentix.incoming.util;

/**
 * World wind shared by everything: nuclear cloud/fallout drift ({@code NuclearWarhead}), client smoke, dust and plumes,
 * projectile flight drift. Depends only on game time, so server and client compute identically without a packet.
 * Each game day has its own wind: uniform direction, speed 5–15 m/s (same choice as nuclear per-burst wind previously);
 * between days, smoothly turns and changes strength as a vector (reversal passes through calm).
 * <p>
 * Wind is above the surface layer at height {@link #REFERENCE}, carrying the nuclear cloud. Below it, weakens by
 * u(z) = U·(z/{@link #REFERENCE})^p ({@link #at(double, double)}); p from stability class ({@link Stability#alpha}:
 * 0.07 by day to 0.55 on clear nights, EPA via Irwin 1979). Profile measured in surface layer, extended here to 1 km
 * reference height, the usual daytime mixing-layer upper bound (estimate): at ground level (10 m), neutral wind is half,
 * clear calm night less than a tenth.
 *
 * @param direction direction, rad: blows toward (cos, sin) in the x–z plane
 * @param speed     speed at {@link #REFERENCE}, m/s
 */
public record Wind(double direction, double speed) {
    /** Speed reference height above ground, m. */
    public static final double REFERENCE = 1000;
    /** Profile not extended below this height above ground (grass/shrub roughness), m. */
    static final double FLOOR = 1;
    /** Game day, ticks. */
    static final long DAY = 24_000;
    /** Speed: SLOWEST to SLOWEST + SPREAD, m/s. */
    static final double SLOWEST = 5, SPREAD = 10;

    /** Wind at game tick gameTime. */
    public static Wind at(long gameTime) {
        long day = Math.floorDiv(gameTime, DAY);
        double f = (double) (gameTime - day * DAY) / DAY, k = f * f * (3 - 2 * f);
        double a0 = angle(day), a1 = angle(day + 1), s0 = speed(day), s1 = speed(day + 1);
        double x = s0 * Math.cos(a0) * (1 - k) + s1 * Math.cos(a1) * k, z = s0 * Math.sin(a0) * (1 - k) + s1 * Math.sin(a1) * k;
        return new Wind(Math.atan2(z, x), Math.sqrt(x * x + z * z));
    }

    /** Speed at height meters above ground with profile exponent alpha, m/s (above reference, reference value). */
    public double at(double height, double alpha) {
        double z = Math.max(FLOOR, Math.min(height, REFERENCE));
        return speed * Math.pow(z / REFERENCE, alpha);
    }

    /** Unit direction x component. */
    public double x() {
        return Math.cos(direction);
    }

    /** Unit direction z component. */
    public double z() {
        return Math.sin(direction);
    }

    private static double angle(long day) {
        return unit(mix(day, 0x9E3779B97F4A7C15L)) * 2 * Math.PI;
    }

    private static double speed(long day) {
        return SLOWEST + SPREAD * unit(mix(day, 0xD1B54A32D192ED03L));
    }

    /** Number 0..1 from the upper 53 bits. */
    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** SplitMix64 (Steele, Lea, Flood 2014) from day and salt. */
    private static long mix(long day, long salt) {
        long z = day * 0xBF58476D1CE4E5B9L + salt;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
