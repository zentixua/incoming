package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exact pre-cleanup entity values; trajectory coverage lives in the scenario sweep. */
class FlightProfileRegressionTest {
    @Test
    void loiterKeepsItsOrbitAndDiveSettings() {
        assertEquals(new WeaponSpec.Loiter(40, 140, 25, 20, 0.8, 0.4, 30, 15, 0.12, 0.08, 0.15, 40, 30, 15, 40),
                WeaponSpec.LOITER.airframe().handling().loiter());
    }

    @Test
    void rocketKeepsMotorTimingAndTrajectoryLimits() {
        assertEquals(new WeaponSpec.RocketFlight(40, 3, 50, 100, 0.05, 64, 200, 64),
                WeaponSpec.ROCKET.airframe().ballistic().rocket());
    }

    @Test
    void bomberKeepsItsLevelChangeReleaseAndDeparture() {
        assertEquals(new WeaponSpec.Bombing(20, 6, 0.15, 400, -6, 0.05, 60, 20, 0.08, 0.1, 40, 0.1),
                WeaponSpec.BUNKER.airframe().handling().bombing());
    }
}
