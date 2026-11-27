package ua.zentix.incoming.util;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import ua.zentix.incoming.sound.Propagation;

/**
 * Current world air shared by server and client: world wind ({@link Wind}, same as nuclear), stability class
 * ({@link Stability}) from surface wind, sun and cloud cover, and humidity. Used by projectile flight (drift), smoke,
 * blast clouds and trails. Minecraft has no humidity: rain and thunderstorms saturate air (humidity 1),
 * clear weather 0.5 (⚠ estimate: mean upper-troposphere humidity is tens of percent).
 *
 * @param wind      world wind
 * @param stability stability class, 0 (A) .. 5 (F)
 * @param humidity  humidity aloft and of the air a blast shell expands into, 0..1 (near the ground: {@link #surfaceHumidity})
 * @param sea       the world's sea level, blocks ({@link Terrain#sea}): heights above sea level start here
 */
public record Air(Wind wind, double stability, double humidity, int sea) {
    /**
     * Clear-weather surface humidity, 0..1: fixed 70 % (est.), shared with the reference air used to build distant
     * sound recordings ({@code sound.Air}). HadISDH describes spatially and temporally varying land humidity,
     * not a universal clear-weather mean: Willett et al., Clim. Past 10 (2014), 1983–2006,
     * doi:10.5194/cp-10-1983-2014. No global 70–75 % mean is assumed from that dataset.
     */
    public static final double SURFACE_CLEAR = ua.zentix.incoming.sound.Air.HUMIDITY / 100;
    /**
     * Reference sea level, blocks ({@link Terrain#VANILLA_SEA}), only for laws evaluated without a world: spec tables
     * ({@code WeaponSpec.reach}) and the game ballistic drag that serves them ({@code Ballistics.Drag}), tests. Those read
     * the standard atmosphere, whose scale is kilometres: the sea levels of common generators (−63 flat to a few hundred)
     * shift its density by at most a few percent there, which never reaches the screen. In a world, every height
     * above sea level reads {@link #sea} ({@code SeaLevelTest} guards the uses).
     */
    public static final int SEA = Terrain.VANILLA_SEA;
    /** Surface wind height in the Pasquill table, m. */
    static final double U10 = 10;
    /** Calm neutral air: skyless dimension, checks. */
    public static final Air CALM = new Air(new Wind(0, 0), Stability.D, 0.5);

    /** Air over the reference sea level ({@link #SEA}): level-free laws and tests. */
    public Air(Wind wind, double stability, double humidity) {
        this(wind, stability, humidity, SEA);
    }

    /**
     * Air at gameTime with sun elevation sine sun, rain and thunderstorm 0..1. Surface wind depends on class (profile),
     * class on surface wind: two steps starting from a neutral profile.
     */
    public static Air of(long gameTime, double sun, double rain, double thunder) {
        return of(gameTime, sun, rain, thunder, SEA);
    }

    /** As {@link #of(long, double, double, double)}, over sea level {@code sea}. */
    public static Air of(long gameTime, double sun, double rain, double thunder, int sea) {
        Wind w = Wind.at(gameTime);
        double overcast = Math.max(rain, thunder), c = Stability.D;
        for (int i = 0; i < 2; i++) c = Stability.of(w.at(U10, Stability.alpha(c)), sun, overcast);
        return new Air(w, c, 0.5 + 0.5 * Math.min(1, overcast), sea);
    }

    /** Current air in world {@code level}: game time, sun, rain and thunderstorm (identical on server and client). */
    public static Air of(Level level) {
        return of(level.getGameTime(), sun(level), level.getRainLevel(1), level.getThunderLevel(1), Terrain.sea(level));
    }

    /** Sound weather from the same world air as flight and smoke; skyless dimensions have no shadow zone. */
    public static Propagation.Weather weather(Level level) {
        Air air = of(level);
        return new Propagation.Weather(Propagation.day(sun(level)), level.getRainLevel(1), level.getThunderLevel(1),
                level.dimensionType().hasSkyLight() ? Propagation.refraction(air.stability()) : 0, air.surfaceHumidity());
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

    /** Wind speed at world y, m/s (ground-relative height measured above the world's sea level). */
    public double speed(double y) {
        return wind.at(y - sea, Stability.alpha(stability));
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
