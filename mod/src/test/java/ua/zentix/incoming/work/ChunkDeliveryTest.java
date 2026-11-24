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

    /** A viewer gets as many whole chunks a tick as vanilla's 7 ms client chunk budget holds, and waits at most 1 s. */
    @Test
    void perTickFillsTheClientChunkBudget() {
        assertTrue(ChunkDelivery.PER_TICK * ChunkDelivery.CHUNK_NANOS <= ChunkDelivery.CLIENT_TICK_NANOS);
        assertTrue((ChunkDelivery.PER_TICK + 1) * ChunkDelivery.CHUNK_NANOS > ChunkDelivery.CLIENT_TICK_NANOS);
        assertEquals(12, ChunkDelivery.PER_TICK);
        assertEquals(20, ChunkDelivery.READY_LIMIT, "ruins in view: p99 1 s behind their wave");
        assertTrue(ChunkDelivery.READY_LIMIT < ChunkDelivery.HOLD_LIMIT, "a ready chunk must go while its light broadcast is still held");
        assertEquals(2 * ChunkDelivery.PER_TICK, ChunkDelivery.LATE_PER_TICK, "late chunks: twice the budget, 14 ms of client time");
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
