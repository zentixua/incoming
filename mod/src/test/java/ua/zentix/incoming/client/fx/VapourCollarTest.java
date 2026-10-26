package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The “collar” of steam is only near the speed of sound and in humid air. */
class VapourCollarTest {
    @Test
    void onlyTransonicAndHumid() {
        // sound by the sea - 340 m/s
        assertEquals(1, VapourCollar.mach(340.3, 0), 0.002);
        assertTrue(VapourCollar.forms(VapourCollar.mach(330, 0), 0.9));
        assertFalse(VapourCollar.forms(VapourCollar.mach(330, 0), 0.4), "dry");
        // bomb drop in the game: 12,5 block/tick - 250 m/s, M 0,73
        assertFalse(VapourCollar.forms(VapourCollar.mach(250, 0), 1), "subsonic");
        assertFalse(VapourCollar.forms(VapourCollar.mach(511, 0), 1), "supersonic");
    }
}
