package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Thrust of the starting accelerator in time: no jumps, decline until separation, light and sound have the same law. */
class ThrustCurveTest {
    /** Boosters with launcher - Shahed and Tomahawk. */
    private static final WeaponType[] BOOSTED = {WeaponType.DRONE, WeaponType.MISSILE};

    @Test
    void tailOffIsRealAndEndsAtSeparation() {
        ThrustCurve missile = WeaponType.MISSILE.spec().airframe().thrust();
        assertNotNull(missile);
        // 5 % of real 12 s work Mk 106 - 0,6 s, 12 tick; separation - at the end of the acceleration phase
        assertEquals(12, missile.tail(), 1e-9);
        assertEquals(1, missile.share(FlightPhase.BOOST, missile.burn() - missile.tail()), 1e-12);
        assertEquals(0, missile.share(FlightPhase.BOOST, missile.burn()), 1e-12, "to the compartment the accelerator burned out");
        assertEquals(0, missile.share(FlightPhase.CLIMB, 0));
        // decline towards the end - with zero inclination: in the last tenth tick of traction is less than half a percent
        assertTrue(missile.share(FlightPhase.BOOST, missile.burn() - 0.1) < 0.005);
    }

    @Test
    void thrustHasNoSteps() {
        for (WeaponType w : BOOSTED) {
            ThrustCurve c = w.spec().airframe().thrust();
            assertNotNull(c, w.name());
            double prev = c.share(FlightPhase.IGNITION, 0), dt = 1e-3;
            // ignition until the mode is reached, then the entire acceleration to the compartment and beyond
            for (double a = dt; a <= c.rise(); a += dt) {
                double s = c.share(FlightPhase.IGNITION, a);
                assertTrue(Math.abs(s - prev) < 0.01, w + " arson " + a);
                prev = s;
            }
            assertEquals(1, prev, 1e-3);
            prev = 1;
            for (double a = 0; a <= c.burn() + 1; a += dt) {
                double s = c.share(FlightPhase.BOOST, a);
                assertTrue(Math.abs(s - prev) < 0.01, w + " acceleration " + a + ": " + prev + " → " + s);
                assertTrue(s <= prev + 1e-12, w + " traction does not increase during acceleration");
                prev = s;
            }
            assertEquals(0, prev);
        }
    }

    @Test
    void soundFollowsTheSameCurve() {
        for (WeaponType w : BOOSTED) {
            WeaponSpec.Airframe a = w.spec().airframe();
            Noise.Rocket motor = a.noise().motor();
            assertNotNull(motor);
            ThrustCurve c = a.thrust();
            assertEquals(c, motor.curve(), w + ": sound and flame - one curve");
            double full = motor.power(FlightPhase.BOOST, 0);
            for (double age = c.burn() - c.tail(); age < c.burn(); age += 0.25) {
                // jet power ∝ thrust at the same exhaust speed
                assertEquals(10 * Math.log10(c.share(FlightPhase.BOOST, age)), motor.power(FlightPhase.BOOST, age) - full, 1e-9);
            }
            assertEquals(Noise.SILENT, motor.power(FlightPhase.BOOST, c.burn()));
        }
    }
}
