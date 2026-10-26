package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.util.Relief;

/**
 * Guided craft as seen by the autopilot ({@link Autopilot}): sensors (position, speed, phase,
 * terrain) and controls (orientation, speed, phase). No Minecraft world: projectile uses its entity,
 * unit tests use a model over model terrain.
 */
public interface Craft {
    Vec3 position();

    FlightController flight();

    AltitudeHold altitude();

    /** Speed, blocks/tick. */
    double speed();

    void setSpeed(double speed);

    FlightPhase phase();

    /** Ticks elapsed in current phase. */
    int phaseAge();

    void setPhase(FlightPhase phase);

    /**
     * Surface height (first air above obstacles) at a world column ({@link Relief}), both in-world and off-world:
     * projectiles use ready chunks, then the background disk/generator survey. Unknown terrain returns the world's bottom.
     */
    double relief(int x, int z);

    /** A fresh relief view for one measurement; projectiles reuse chunks and columns only within that measurement. */
    default Relief reliefSurvey() {
        return this::relief;
    }

    /**
     * Blocks of unobstructed straight line to {@code to}: distance to first blocked cell, or
     * {@link Double#POSITIVE_INFINITY} if the whole line is clear except last {@code margin} blocks at the point
     * (fuze reaches the target there). Where blocks cannot be read (outside-world flight, unready chunk along path), use terrain top
     * ({@link #relief}); where that is still unknown, clear: sensor has no reading.
     */
    double clearAlong(Vec3 to, double margin);

    /** Hold {@code desired} altitude ({@link AltitudeHold}) with flight-phase turns {@code a}. */
    default void holdAltitude(double desired, double gain, WeaponSpec.Handling h, WeaponSpec.Agility a) {
        altitude().hold(flight(), position().y, desired, gain, h, a);
    }

    /**
     * Per-tick speed in our flight model ({@link WeaponSpec.Airframe#airspeed}): motor pulls toward {@code target},
     * climb subtracts speed, descent adds it.
     */
    default void throttle(WeaponSpec.Airframe air, double target) {
        setSpeed(air.airspeed(speed(), target, flight().forward().y));
    }
}
