package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #656: a nuke froze the picture on an AMD card while sound went on (a driver reset after GPU work past 2 s). The watchdog
 * must step in on one frame far past any budget, never on frames inside the budgets, and never on a CPU rasterizer.
 */
class GpuWatchTest {
    private static final String GPU = "NVIDIA GeForce RTX 4060 Laptop GPU/PCIe/SSE2";

    /** Frames the RTX 4060 laptop measured in the #656 scenario (longest pall frame 13.8 ms) and HIGH at 2560×1600 never act. */
    @Test
    void framesWithinBudgetsDoNothing() {
        GpuWatch w = new GpuWatch(GPU);
        for (double ms : new double[]{0, 7.9, 13.8, 19.2, 41.7, PallPass.Quality.HIGH.budget * 4, 249.9, Double.NaN}) assertNull(w.sample(ms), "at " + ms);
        assertEquals(GpuWatch.State.WATCHING, w.state());
    }

    /** One frame ten times the HIGH budget holds the floor; a later frame near the driver's limit turns the volume off, once. */
    @Test
    void slowFrameFloorsThenStallTurnsOff() {
        assertTrue(GpuWatch.FLOOR_MS >= 10 * PallPass.Quality.HIGH.budget && GpuWatch.OFF_MS <= 1000, "thresholds");
        GpuWatch w = new GpuWatch(GPU);
        assertEquals(GpuWatch.State.FLOOR, w.sample(300));
        assertEquals(300, w.cause());
        assertNull(w.sample(400), "floor is reported once");
        assertNull(w.sample(5), "the floor holds for the session");
        assertEquals(GpuWatch.State.OFF, w.sample(1200));
        assertNull(w.sample(5000), "off is reported once");
        assertEquals(GpuWatch.State.OFF, w.state());
    }

    /** A frame already past the off threshold goes straight to off: the floor would not help the next one. */
    @Test
    void stallGoesStraightOff() {
        GpuWatch w = new GpuWatch(GPU);
        assertEquals(GpuWatch.State.OFF, w.sample(1500));
    }

    /** CPU rasterizers (cloud scenarios on llvmpipe) take seconds per frame by design and have no driver reset. */
    @Test
    void softwareRenderersAreNotWatched() {
        for (String r : new String[]{"llvmpipe (LLVM 23.1.4, 256 bits)", "softpipe", "Google SwiftShader", "Software Rasterizer"}) {
            GpuWatch w = new GpuWatch(r);
            assertFalse(w.watched(), r);
            assertNull(w.sample(10_000), r);
        }
        assertTrue(new GpuWatch("AMD Radeon RX 6600 XT").watched());
        assertTrue(new GpuWatch(null).watched());
    }
}
