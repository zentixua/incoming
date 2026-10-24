package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrontDustTest {
    @Test
    void dustPresentationStartsAtTheLocalArrivalAndFadesContinuously() {
        double phase = 0.015;
        assertEquals(0, FrontDust.envelope(-0.01, phase));
        assertEquals(0, FrontDust.envelope(0, phase));
        assertTrue(FrontDust.envelope(0.5 * phase, phase) > 0.5);
        assertEquals(0, FrontDust.envelope(8 * phase, phase));
        assertEquals(0, FrontDust.envelope(1, phase));
        assertEquals(0, FrontDust.envelope(1e-8, phase), 1e-5);
        assertEquals(0, FrontDust.envelope(8 * phase - 1e-8, phase), 1e-10);
        assertEquals(FrontDust.envelope(phase, phase), FrontDust.envelope(2 * phase, 2 * phase), 1e-12);

    }
}
