package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** «Воротник» пара — только около скорости звука и во влажном воздухе. */
class VapourCollarTest {
    @Test
    void onlyTransonicAndHumid() {
        // у моря звук — 340 м/с
        assertEquals(1, VapourCollar.mach(340.3, 0), 0.002);
        assertTrue(VapourCollar.forms(VapourCollar.mach(330, 0), 0.9));
        assertFalse(VapourCollar.forms(VapourCollar.mach(330, 0), 0.4), "сухо");
        // падение бомбы в игре: 12,5 блока/тик — 250 м/с, М 0,73
        assertFalse(VapourCollar.forms(VapourCollar.mach(250, 0), 1), "дозвук");
        assertFalse(VapourCollar.forms(VapourCollar.mach(511, 0), 1), "сверхзвук");
    }
}
