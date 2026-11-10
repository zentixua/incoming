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
        for (double white : new double[]{1.0 / 255, 0.01, 0.3, 1}) {
            int timer = NukeFlash.skyFlashTimer(white);
            for (int i = 0; i < 100; i++) {
                double partial = i / 100.0;
                assertEquals(1, vanillaShare(timer, partial), 1e-12, "white " + white + ", partial " + partial);
            }
        }
    }

    @Test
    void noSkyFlashBelowAScreenStep() {
        assertEquals(0, NukeFlash.skyFlashTimer(0));
        assertEquals(0, NukeFlash.skyFlashTimer(0.5 / 255));
    }
}
