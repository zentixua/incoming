package ua.zentix.incoming.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkDeliveryTest {
    /** A chunk goes whole exactly where its deltas would cost the client more than the whole chunk. */
    @Test
    void wholeFromWhereDeltasCostMore() {
        int n = ChunkDelivery.WHOLE_FROM;
        assertTrue((long) n * ChunkDelivery.POSITION_NANOS >= ChunkDelivery.CHUNK_NANOS, n + " positions cost less than a chunk");
        assertTrue((long) (n - 1) * ChunkDelivery.POSITION_NANOS < ChunkDelivery.CHUNK_NANOS, (n - 1) + " positions already cost a chunk");
        assertFalse(ChunkDelivery.whole(n - 1));
        assertTrue(ChunkDelivery.whole(n));
        assertFalse(ChunkDelivery.whole(1), "a lone broken pane stays a delta");
    }

    /** The GameTest seam restores the threshold it replaced. */
    @Test
    void seamRestores() {
        int was = ChunkDelivery.wholeFrom(Integer.MAX_VALUE);
        try {
            assertFalse(ChunkDelivery.whole(100_000));
        } finally {
            ChunkDelivery.wholeFrom(was);
        }
        assertTrue(ChunkDelivery.whole(ChunkDelivery.WHOLE_FROM));
    }
}
