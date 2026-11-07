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
        assertEquals(ENTRY, ReentryGlow.speed(ENTRY, 60_000, STEEP), ENTRY * 0.01);
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

    @Test void entryBrightensFromFaintHighUp() {
        // air above the 20 km standard-atmosphere table thins on: at REAL speed the window starts up to 80 km high
        double l20 = ReentryGlow.luminance(ENTRY, 20_000, STEEP), l40 = ReentryGlow.luminance(ENTRY, 40_000, STEEP);
        double l60 = ReentryGlow.luminance(ENTRY, 60_000, STEEP), l80 = ReentryGlow.luminance(ENTRY, 80_000, STEEP);
        assertTrue(l80 < l60 && l60 < l40 && l40 < l20);
        assertTrue(l60 < l20 / 100, "60 km " + l60 + " vs 20 km " + l20);
        // US 1976: 4.0e-3 kg/m³ at 40 km, 3.1e-4 at 60 km; isothermal continuation within a factor of 3
        assertEquals(Math.log(4.0e-3), Math.log(ReentryGlow.density(40_000)), Math.log(3));
        assertEquals(Math.log(3.1e-4), Math.log(ReentryGlow.density(60_000)), Math.log(3));
    }

    @Test void wakeIsHundredsOfMetersLowAndKilometersHigh() {
        double low = ReentryGlow.wakeLength(ENTRY, 1_000, STEEP), high = ReentryGlow.wakeLength(ENTRY, 14_000, STEEP);
        assertTrue(low > 100 && low < 600, "1 km: " + low);
        assertTrue(high > 1_000 && high < 4_000, "14 km: " + high);
    }
}
