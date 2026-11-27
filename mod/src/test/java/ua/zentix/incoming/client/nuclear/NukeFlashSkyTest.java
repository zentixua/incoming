package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NukeFlashSkyTest {
    /** Vanilla sky-flash share at a partial tick ({@code ClientLevel.getSkyColor}: min(1, timer − partial), × 0.45 there). */
    private static double vanillaShare(int timer, double partial) {
        return timer <= 0 ? 0 : Math.min(1, timer - partial);
    }

    @Test
    void skyFlashDoesNotRideThePartialTick() {
        // Renewed every frame while the screen is white: the share must not change with the frame's place in the tick
        // (a one-tick timer gave a 20 Hz sawtooth over the whole night sky, replay 10.11).
        for (double white : new double[]{NukeFlash.SKY_FLASH_COVER, 0.99, 1}) {
            int timer = NukeFlash.skyFlashTimer(white);
            for (int i = 0; i < 100; i++) {
                double partial = i / 100.0;
                assertEquals(1, vanillaShare(timer, partial), 1e-12, "white " + white + ", partial " + partial);
                // The first frame after a tick reads the fog color before this frame's renewal: one tick lower.
                assertEquals(1, vanillaShare(timer - 1, partial), 1e-12, "after a tick, white " + white + ", partial " + partial);
            }
        }
    }

    @Test
    void noSkyFlashBelowAScreenStep() {
        assertEquals(0, NukeFlash.skyFlashTimer(0));
        assertEquals(0, NukeFlash.skyFlashTimer(0.5 / 255));
    }

    /**
     * Vanilla's sky flash is a switch (white sky, full-day light map), so it may run only while the flash's white fill hides
     * the frame: its end must never show (Artem 27.11, 15 kt at night: the picture jumped within three frames ~1.85 s after
     * the burst, when the switch ended under a screen that was only 10 % white).
     */
    @Test
    void theSkyFlashSwitchEndsOnlyUnderAFullWhiteFill() {
        for (double x = 1; x < 1e6; x *= 1.1) {
            for (float strength : new float[]{0.3f, 0.7f, 1}) {
                float cover = NukeFlash.coverOf(strength, x);
                if (NukeFlash.skyFlashTimer(cover) > 0) assertTrue(cover >= 0.98, "x " + x + ", strength " + strength);
            }
        }
        // a weak or indirect flash never throws the switch: its white fill never hides the frame
        assertEquals(0, NukeFlash.skyFlashTimer(NukeFlash.coverOf(0.7f, 1e6)));
    }
}
