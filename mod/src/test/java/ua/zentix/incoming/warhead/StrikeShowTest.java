package ua.zentix.incoming.warhead;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Stability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conventional strike's show (time.md §8.3 rule 5): the cloud's apex π/N (1.7–5 min) and, only when its crater fuel
 * lights, the fuel's burn-out (Shahed 2.5 min, missile 16 min). A missile in water has the apex alone.
 */
class StrikeShowTest {
    @Test
    void apexAndFuel() {
        double apex = StrikeShow.seconds(Stability.STANDARD_N, 0);
        assertEquals(Math.PI / Stability.STANDARD_N, apex, 1e-9);
        assertTrue(apex > 290 && apex < 305, "standard air's apex " + apex);
        assertTrue(StrikeShow.seconds(0.03, 0) > 100, "stable air's apex");
        // no fuel lit: no fuel term
        assertEquals(0, StrikeShow.fuelTicks(WeaponType.MISSILE.spec(), 0));
        int missile = StrikeShow.fuelTicks(WeaponType.MISSILE.spec(), WeaponType.MISSILE.spec().pool().cells());
        assertEquals(16, missile / 20.0 / 60, 1.0, "missile fuel burn-out, minutes");
        int shahed = StrikeShow.fuelTicks(WeaponType.DRONE.spec(), WeaponType.DRONE.spec().pool().cells());
        assertEquals(2.5, shahed / 20.0 / 60, 0.5, "Shahed fuel burn-out, minutes");
        // the show is the longer of the two
        assertEquals(missile / 20.0, StrikeShow.seconds(Stability.STANDARD_N, missile / 20.0), 1e-9);
    }
}
