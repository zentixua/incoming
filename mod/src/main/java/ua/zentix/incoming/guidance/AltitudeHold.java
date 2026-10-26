package ua.zentix.incoming.guidance;

import ua.zentix.incoming.strike.WeaponSpec;

/**
 * Altitude hold: smooth desired-height filter (1/5 of the difference per tick) and pitch proportional to error
 * (1.2° per block, up to spec climb/descent angles, {@link WeaponSpec.Handling}), with angular speed and
 * acceleration limits ({@link FlightController}).
 */
public final class AltitudeHold {
    /** Smoothed desired altitude. */
    private double filter;

    /** Smoothed desired altitude (saved with the projectile). */
    public double filter() {
        return filter;
    }

    /** Start at {@code y} (launch, materialization, loading a save). */
    public void reset(double y) {
        filter = y;
    }

    /** Hold {@code desired} altitude at {@code y}, using flight leg {@code a}'s pitch turns. */
    public void hold(FlightController flight, double y, double desired, double gain, WeaponSpec.Handling h, WeaponSpec.Agility a) {
        filter += (desired - filter) / 5;
        double climb = Math.max(-h.descentAngle(), Math.min(h.climbAngle(), (filter - y) * 1.2));
        flight.holdPitch(-climb, gain, a.pitchRate(), a.pitchAccel());
    }
}
