package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.Condensation;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.util.Wind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blast at the water expands into the air over it, not into the saturated film at the surface: a cruise missile at the
 * waterline on a clear day drew a 40 m Wilson cloud for 0.21 s, a white burst over the frame that vanished in one step
 * (rc-video raid clip, 5.85 s).
 */
class WaterBlastAirTest {
    private static final Air CLEAR = new Air(new Wind(3, 0), Stability.D, 0.5), RAIN = new Air(new Wind(3, 0), Stability.D, 1);

    @Test
    void theAirOverWaterIsTheNearSurfaceAir() {
        assertEquals(CLEAR.surfaceHumidity(), FarBlasts.humidity(CLEAR, true), 0);
        assertTrue(FarBlasts.humidity(CLEAR, true) < 1, "clear weather over a lake is not saturated");
        assertEquals(1, FarBlasts.humidity(RAIN, true), 0, "rain saturates it");
        assertEquals(CLEAR.humidity(), FarBlasts.humidity(CLEAR, false), 0, "land keeps the world air");
    }

    @Test
    void aMissileAtTheWaterlineOnAClearDayHasNoFrameWideWilsonCloud() {
        double kg = WeaponType.MISSILE.spec().charge();
        Condensation clear = new Condensation(kg, true, FarBlasts.humidity(CLEAR, true)), saturated = new Condensation(kg, true, 1);
        assertTrue(saturated.reach > 35, "saturated: " + saturated.reach + " m");
        assertTrue(clear.reach < 25 && clear.life < 0.13, "clear day at the water: " + clear.reach + " m, " + clear.life + " s");
    }
}
