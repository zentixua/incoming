package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Blast blowout of flash fires (Martin et al. 1980): all below 2 psi, (8 − P)/6 at 2–5 psi, half above 5 psi; continuous, never rising. */
class BlowoutTest {
    @Test
    void martinShares() {
        assertEquals(1, Blowout.keep(0), 0);
        assertEquals(1, Blowout.keep(1.99), 0);
        assertEquals(1, Blowout.keep(2), 1e-12);
        assertEquals(5.0 / 6, Blowout.keep(3), 1e-12);
        assertEquals(4.0 / 6, Blowout.keep(4), 1e-12);
        assertEquals(0.5, Blowout.keep(5), 1e-12);
        assertEquals(0.5, Blowout.keep(20), 0);
        assertEquals(0.5, Blowout.keep(500), 0);
        double last = 1;
        for (double p = 0; p < 30; p += 0.01) {
            double k = Blowout.keep(p);
            assertTrue(k <= last + 1e-12 && last - k < 0.01, "continuous and falling at " + p);
            last = k;
        }
    }
}
