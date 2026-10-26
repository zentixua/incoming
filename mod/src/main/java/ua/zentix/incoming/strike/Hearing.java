package ua.zentix.incoming.strike;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;

/**
 * Whether a projectile is audible: current noise (craft sound spec {@link WeaponSpec.Airframe#noise}, actual
 * entity speed) against background at the ear, by octave, after open-air propagation ({@link Propagation}). Same
 * calculation that gives client sound-layer loudness ({@code client.sound.EngineSound}), so server sends path
 * ({@link FarFlights}) exactly to those who can hear it, and client is silent beyond. Server ignores obstacles:
 * client attenuates behind hills itself; what it hears always fits transmitted coverage.
 */
public final class Hearing {
    /**
     * Maximum mod sound travel time to ear, ticks: client remembers path this long (60 s: B-2 rumble from 12 km,
     * Grad salvo roar from 20 km). Beyond {@link #RANGE} sound is inaudible: model limit, not physical; at night
     * ICBM roar would travel farther.
     */
    public static final int MAX_DELAY = 1200;
    /** Maximum sound travel distance, blocks (= meters): sound path over {@link #MAX_DELAY}. */
    public static final double RANGE = MAX_DELAY * Noise.SOUND / 20;

    private Hearing() {}

    /** Source height above ground beneath listener, m: ear at {@link Propagation#EAR} above it. */
    public static double height(Vec3 source, Vec3 ear) {
        return Math.max(0, source.y - ear.y + Propagation.EAR);
    }

    /** How much projectile currently exceeds background at {@code ear} over open path, dB (below zero inaudible). */
    public static double margin(StrikeProjectile p, Vec3 ear, Propagation.Weather weather) {
        Vec3 at = p.position();
        double d = at.distanceTo(ear);
        if (d > RANGE) return Double.NEGATIVE_INFINITY;
        Propagation.Path path = Propagation.Path.over(d, height(at, ear), weather);
        return p.airframe().noise().margin(p.flightPhase(), p.phaseAge(), p.velocity().length(), drilling(p), path);
    }

    /** Whether projectile is audible now at {@code ear}. */
    public static boolean audible(StrikeProjectile p, Vec3 ear, Propagation.Weather weather) {
        return margin(p, ear, weather) > 0;
    }

    /**
     * Current audible range, blocks, for ears {@code below} blocks lower on flat meadow (0 inaudible). For tests
     * and descriptions; server uses {@link #audible}.
     */
    public static double range(StrikeProjectile p, double below, Propagation.Weather weather) {
        return range(p.airframe().noise(), p.flightPhase(), p.phaseAge(), p.velocity().length(), drilling(p), below, weather);
    }

    /**
     * Audible range of {@code noise} in phase {@code ph} at {@code speed} (blocks/tick), blocks (0 inaudible,
     * capped at {@link #RANGE}): ears {@code below} blocks below source on flat meadow (nearby source height
     * capped by source distance).
     */
    public static double range(Noise noise, FlightPhase ph, double age, double speed, boolean drilling, double below, Propagation.Weather w) {
        double lo = 1, hi = RANGE;
        if (!heard(noise, ph, age, speed, drilling, lo, below, w)) return 0;
        if (heard(noise, ph, age, speed, drilling, hi, below, w)) return hi;
        for (int i = 0; i < 40; i++) {
            double mid = Math.sqrt(lo * hi);
            if (heard(noise, ph, age, speed, drilling, mid, below, w)) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    private static boolean heard(Noise noise, FlightPhase ph, double age, double v, boolean drilling, double d, double below, Propagation.Weather w) {
        Propagation.Path path = Propagation.Path.over(d, Math.min(d, below) + Propagation.EAR, w);
        return noise.margin(ph, age, v, drilling, path) > 0;
    }

    private static boolean drilling(StrikeProjectile p) {
        return p instanceof BunkerBusterEntity b && b.isDrilling();
    }
}
