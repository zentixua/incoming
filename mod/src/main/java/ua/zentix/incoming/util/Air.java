package ua.zentix.incoming.util;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Current world air shared by server and client: world wind ({@link Wind}, same as nuclear), stability class
 * ({@link Stability}) from surface wind, sun and cloud cover, and humidity. Used by projectile flight (drift), smoke,
 * blast clouds and trails. Minecraft has no humidity: rain and thunderstorms saturate air (humidity over water 1),
 * clear weather 0.5 (⚠ estimate: mean upper-troposphere humidity is tens of percent).
 *
 * @param wind      world wind
 * @param stability stability class, 0 (A) .. 5 (F)
 * @param humidity  humidity over water, 0..1
 */
public record Air(Wind wind, double stability, double humidity) {
    /**
     * Clear-weather surface humidity, 0..1: mean relative humidity near land surface is 70–75 % (HadISDH, Willett et al. 2014,
     * ⚠ verify); 70 % is also the ISO 9613-1 reference condition used to build distant sound recordings ({@code sound.Air}).
     */
    public static final double SURFACE_CLEAR = 0.7;
    /** World sea level, blocks (as {@code WaterDecal.SEA_LEVEL}): air heights measured above it. */
    public static final int SEA = 63;
    /** Surface wind height in the Pasquill table, m. */
    static final double U10 = 10;
    /** Calm neutral air: skyless dimension, checks. */
    public static final Air CALM = new Air(new Wind(0, 0), Stability.D, 0.5);

    /**
     * Air at gameTime with sun elevation sine sun, rain and thunderstorm 0..1. Surface wind depends on class (profile),
     * class on surface wind: two steps starting from a neutral profile.
     */
    public static Air of(long gameTime, double sun, double rain, double thunder) {
        Wind w = Wind.at(gameTime);
        double overcast = Math.max(rain, thunder), c = Stability.D;
        for (int i = 0; i < 2; i++) c = Stability.of(w.at(U10, Stability.alpha(c)), sun, overcast);
        return new Air(w, c, 0.5 + 0.5 * Math.min(1, overcast));
    }

    /** Current air in world {@code level}: game time, sun, rain and thunderstorm (identical on server and client). */
    public static Air of(Level level) {
        return of(level.getGameTime(), sun(level), level.getRainLevel(1), level.getThunderLevel(1));
    }

    /**
     * Sun elevation sine in world {@code level}: no sky or fixed time means no sun (−1, night). One day/night function
     * for air, smoke and sound ({@code sound.Propagation.Weather}).
     */
    public static double sun(Level level) {
        var type = level.dimensionType();
        return type.hasSkyLight() && !type.hasFixedTime() ? Mth.cos(level.getTimeOfDay(1) * Mth.TWO_PI) : -1;
    }

    /**
     * Surface humidity, 0..1, for near-ground sound ({@code sound.Propagation}): {@link #SURFACE_CLEAR} in clear weather,
     * saturated in rain and thunderstorms as aloft (rain uses the same share that increased {@link #humidity}).
     */
    public double surfaceHumidity() {
        double overcast = Math.max(0, Math.min(1, 2 * humidity - 1));
        return SURFACE_CLEAR + (1 - SURFACE_CLEAR) * overcast;
    }

    /** Wind speed at world y, m/s (ground-relative height measured above sea level). */
    public double speed(double y) {
        return wind.at(y - SEA, Stability.alpha(stability));
    }

    /** Wind x component at world y, m/s. */
    public double windX(double y) {
        return speed(y) * wind.x();
    }

    /** Wind z component at world y, m/s. */
    public double windZ(double y) {
        return speed(y) * wind.z();
    }

    /** Brunt–Väisälä frequency, 1/s. */
    public double brunt() {
        return Stability.brunt(stability);
    }
}
