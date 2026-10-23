package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrontDustTest {
    @Test
    void dustPresentationStartsAtTheLocalArrivalAndFadesContinuously() {
        assertEquals(0, FrontDust.envelope(-0.01));
        assertEquals(0, FrontDust.envelope(0));
        assertTrue(FrontDust.envelope(0.02) > 0.5);
        assertEquals(0, FrontDust.envelope(0.32));
        assertEquals(0, FrontDust.envelope(1));
        assertEquals(0, FrontDust.envelope(1e-8), 1e-5);
        assertEquals(0, FrontDust.envelope(0.32 - 1e-8), 1e-10);
    }
}
