package ua.zentix.incoming.client.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Map shock-front look: the contour colours at their pressures, redder and thicker as pressure rises. */
class FrontLookTest {
    @Test
    void contourColoursAtTheirPressures() {
        assertEquals(0xFFFF8050, FrontLook.color(5));
        assertEquals(0xFFFFD0A0, FrontLook.color(1));
        assertEquals(0xFFFF3030, FrontLook.color(100));
        assertEquals(0xFFE8E8E8, FrontLook.color(0.1));
    }

    @Test
    void strongerIsRedderAndThicker() {
        int prevGreen = 256, prevWidth = 0;
        for (double psi = 0.3; psi < 40; psi *= 1.1) {
            int green = FrontLook.color(psi) >>> 8 & 0xFF;
            assertTrue(green <= prevGreen, "green falls as pressure rises at " + psi);
            assertTrue(FrontLook.width(psi) >= prevWidth);
            assertEquals(0x50, FrontLook.wake(psi) >>> 24);
            prevGreen = green;
            prevWidth = FrontLook.width(psi);
        }
    }
}
