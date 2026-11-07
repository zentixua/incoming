package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReentryGlowTest {
    private static final double ENTRY = 7200, STEEP = 0.94;

    /** Apparent visual magnitude of a source of intensity {@code cd} at {@code d} m: E₀ = 2.54·10⁻⁶ lx at magnitude 0. */
    private static double magnitude(double cd, double d) {
        return -2.5 * Math.log10(cd / (d * d) / 2.54e-6);
    }

    @Test void highBetaWarheadKeepsMostOfItsSpeedToTheGround() {
        assertEquals(ENTRY, ReentryGlow.speed(ENTRY, 60_000, STEEP), ENTRY * 0.05); // 20 km cap of the atmosphere table
        double ground = ReentryGlow.speed(ENTRY, 0, STEEP) / ENTRY;
        assertTrue(ground > 0.6 && ground < 0.8, "sea level " + ground);
        // shallower entry passes more air: slower at the same height
        assertTrue(ReentryGlow.speed(ENTRY, 0, 0.38) < ReentryGlow.speed(ENTRY, 0, STEEP));
    }

    @Test void headIsABolideVisibleByDay() {
        for (double h : new double[]{1_000, 5_000, 14_000}) {
            double cd = ReentryGlow.intensity(ENTRY, h, STEEP);
            double m = magnitude(cd, 10_000);
            // bolide class (−10…−15); a daytime star needs about −4 (Venus), a noticeable daytime point −8
            assertTrue(m < -9 && m > -16, h + " m: magnitude " + m);
        }
        // head disc: tens of noon white walls by day, so overexposed with a small glare, not a speck of sky color
        double day = ReentryGlow.luminance(ENTRY, 5_000, STEEP);
        assertTrue(day > 10 && day < 1_000, "luminance " + day);
    }

    @Test void wakeIsHundredsOfMetersLowAndKilometersHigh() {
        double low = ReentryGlow.wakeLength(ENTRY, 1_000, STEEP), high = ReentryGlow.wakeLength(ENTRY, 14_000, STEEP);
        assertTrue(low > 100 && low < 600, "1 km: " + low);
        assertTrue(high > 1_000 && high < 4_000, "14 km: " + high);
    }
}
