package ua.zentix.incoming.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhQueueTest {
    private static final long MS = 1_000_000L;

    /** Lowest delay DH 3.3.3 - 250 ms queue, 1 s before writing the section and 100 ms pause of the recording flow: 27 tick. */
    @Test
    void leadIsDhMinimumLatency() {
        assertEquals(27, DhQueue.LEAD);
        assertTrue(DhQueue.LEAD * 50 >= DhQueue.DEBOUNCE_MS + DhQueue.SAVE_DELAY_MS + DhQueue.SAVE_PAUSE_MS, "before the minimum delay LOD would change to the front");
        assertTrue((DhQueue.LEAD - 1) * 50 < DhQueue.DEBOUNCE_MS + DhQueue.SAVE_DELAY_MS + DhQueue.SAVE_PAUSE_MS, "margin in excess of the smallest delay - delay");
    }

    /** The window is half of the smallest queue DH: 1000 for the thread of its parsing; We don’t know the flows—one. */
    @Test
    void windowIsHalfOfSmallestQueue() {
        assertEquals(500, DhQueue.window(1));
        assertEquals(2000, DhQueue.window(4));
        assertEquals(500, DhQueue.window(0));
    }

    /** The submitted copy is awaiting confirmation; the confirmed one goes out of the window, the delay counts; someone else's confirmation does not count. */
    @Test
    void ackFreesWindow() {
        DhQueue q = new DhQueue();
        q.sent(1, 0);
        q.sent(2, 0);
        assertEquals(2, q.waiting());
        assertFalse(q.ack(3, 10 * MS), "your update DH is not our copy");
        assertTrue(q.ack(1, 1500 * MS));
        assertFalse(q.ack(1, 1600 * MS), "second confirmation of the same chunk");
        assertEquals(1, q.waiting());
        assertTrue(q.ack(2, 2500 * MS));
        assertArrayEquals(new long[] {2, 2, 0, 2000, 2500, 0}, q.take());
        assertArrayEquals(new long[] {0, 0, 0, 0, 0, 0}, q.take(), "count - from last call");
    }

    /** Without confirmation (DH only reads, unchanged chunk), the copy leaves the window on time, not earlier. */
    @Test
    void unconfirmedLeaveAfterTimeout() {
        DhQueue q = new DhQueue();
        q.sent(1, 0);
        q.sent(2, DhQueue.TIMEOUT_NANOS / 2);
        q.expire(DhQueue.TIMEOUT_NANOS - 1);
        assertEquals(2, q.waiting());
        q.expire(DhQueue.TIMEOUT_NANOS);
        assertEquals(1, q.waiting());
        assertFalse(q.ack(1, DhQueue.TIMEOUT_NANOS + 1), "expired is not confirmed");
        q.expire(DhQueue.TIMEOUT_NANOS * 2);
        assertEquals(0, q.waiting());
        assertEquals(2, q.take()[2]);
    }
}
