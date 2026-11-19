package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuinDeliveryTest {
    /** A chunk goes whole exactly where its deltas would cost the client more than the whole chunk. */
    @Test
    void wholeFromWhereDeltasCostMore() {
        int n = RuinDelivery.WHOLE_FROM;
        assertTrue((long) n * RuinDelivery.POSITION_NANOS >= RuinDelivery.CHUNK_NANOS, n + " positions cost less than a chunk");
        assertTrue((long) (n - 1) * RuinDelivery.POSITION_NANOS < RuinDelivery.CHUNK_NANOS, (n - 1) + " positions already cost a chunk");
        assertFalse(RuinDelivery.whole(n - 1));
        assertTrue(RuinDelivery.whole(n));
        assertFalse(RuinDelivery.whole(1), "a lone broken pane stays a delta");
    }

    /** The GameTest seam restores the threshold it replaced. */
    @Test
    void seamRestores() {
        int was = RuinDelivery.wholeFrom(Integer.MAX_VALUE);
        try {
            assertFalse(RuinDelivery.whole(100_000));
        } finally {
            RuinDelivery.wholeFrom(was);
        }
        assertTrue(RuinDelivery.whole(RuinDelivery.WHOLE_FROM));
    }
}
