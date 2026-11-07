package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceleration of ICBM ({@link WeaponSpec.Ascent} spec {@link WeaponSpec#NUKE}) by tick coincides with the formula of the rocket
 *  constant thrust: a(t) = T / (m₀ − ṁ·t) − g. Without peace and without pitch - vertical.
 */
class IcbmBoostTest {
    private static final WeaponSpec.Ascent A = WeaponSpec.NUKE.ascent();
    private static final double G = 9.81;

    /** Height according to the formula in terms of {@code t} s, m: h = (T/ṁ)·(t − (m₁/ṁ)·ln(m₀/m₁)) − g·t²/2, m₁ = m₀ − ṁ·t. */
    private static double height(double t) {
        double mdot = A.propellant() / A.burnSeconds(), m1 = A.launchMass() - mdot * t;
        return A.thrust() / mdot * (t - m1 / mdot * Math.log(A.launchMass() / m1)) - G * t * t / 2;
    }

    /** Speed according to the formula in terms of {@code t} s, m/s: v = (T/ṁ)·ln(m₀/m₁) − g·t. */
    private static double velocity(double t) {
        double mdot = A.propellant() / A.burnSeconds(), m1 = A.launchMass() - mdot * t;
        return A.thrust() / mdot * Math.log(A.launchMass() / m1) - G * t;
    }

    @Test
    void liftOffIsAboutOneAndHalfG() {
        double expected = A.thrust() / A.launchMass() - G;
        assertEquals(expected, A.accel(0), expected * 0.05);
        double g = A.accel(0) / G;
        assertTrue(g >= 1.2 && g <= 1.8, "on the break " + g + " g");
    }

    @Test
    void accelerationGrowsAsFuelBurns() {
        double prev = A.accel(0);
        for (int tick = 1; tick <= 200; tick++) {
            double a = A.accel(tick / 20.0);
            assertTrue(a > prev, "acceleration by " + tick + "-m tick did not grow: " + a + " after " + prev);
            prev = a;
        }
    }

    @Test
    void tenSecondsMatchTheFormula() {
        double speed = 0, y = 0, cruise = WeaponSpec.NUKE.airframe().cruiseSpeed();
        for (int tick = 1; tick <= 200; tick++) {
            speed = Math.min(cruise, A.step(speed, tick));
            y += speed;
        }
        // block = meter, tick = 0,05 s: blocks/tick × 20 = m/s
        assertEquals(height(10), y, height(10) * 0.05, "height after 10 s");
        assertEquals(velocity(10) / 20, speed, velocity(10) / 20 * 0.05, "speed after 10 s");
        assertEquals(8.2, speed, 8.2 * 0.05, "speed after 10 s, blocks/tick");
        assertTrue(speed < cruise, "spec limit reached in 10 s");
    }

    @Test
    void gravityTurnBendsThePathToAboutSixtyFiveDegreesAtBurnout() {
        float[] angles = A.pathAngles();
        assertEquals(A.burnTicks(), angles.length - 1);
        assertEquals(1220, A.burnTicks());
        for (int tick = 0; tick <= A.pitchOverTick(); tick++) assertEquals(90, angles[tick]);
        assertTrue(angles[A.pitchOverTick() + 1] <= A.climbPitch() && angles[A.pitchOverTick() + 1] > A.climbPitch() - 0.5);
        for (int tick = A.pitchOverTick() + 2; tick < angles.length; tick++) assertTrue(angles[tick] < angles[tick - 1], "path rose at " + tick);
        float end = angles[angles.length - 1];
        assertTrue(end > 60 && end < 70, "burnout path angle " + end);
    }

    /** Game climb: speed capped at the game limit, path angle real; the burn ends tens of kilometers up, a few downrange. */
    @Test
    void wholeBurnClimbsAboutTwentyKilometers() {
        float[] angles = A.pathAngles();
        double cap = WeaponSpec.NUKE.airframe().gameCruise(), speed = 0, y = 0, x = 0;
        for (int tick = 1; tick <= A.burnTicks(); tick++) {
            speed = Math.min(cap, A.step(speed, tick));
            double g = Math.toRadians(angles[tick]);
            y += speed * Math.sin(g);
            x += speed * Math.cos(g);
        }
        assertTrue(y > 18_000 && y < 26_000, "height at burnout " + y);
        assertTrue(x > 5_000 && x < 11_000, "downrange at burnout " + x);
        assertTrue(WeaponSpec.NUKE.airframe().range() >= A.burnTicks() * cap, "range ends the boost before burnout");
    }
}
