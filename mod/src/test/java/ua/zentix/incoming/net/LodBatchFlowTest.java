package ua.zentix.incoming.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodBatchFlowTest {
    private static final long MS = 1_000_000;

    @Test
    void severalBatchesStayInFlightAndAcknowledgementsAreCumulative() {
        LodBatchFlow flow = new LodBatchFlow(100, 100, 30, 25 * MS);
        assertFalse(flow.acknowledge(0, 0), "nothing marked");
        assertFalse(flow.markDue(true), "no open batch");
        flow.chunkSent(20);
        assertFalse(flow.markDue(true), "batch not full, more coming");
        assertTrue(flow.markDue(false), "nothing more waiting: mark now");
        flow.chunkSent(20);
        assertTrue(flow.markDue(true), "batch full");
        int first = flow.mark(0);
        assertEquals(2, flow.markedChunks());
        flow.chunkSent(40);
        int second = flow.mark(0);
        assertTrue(flow.hasRoom(), "80 of 100 in flight: a second batch went before any acknowledgement");
        flow.chunkSent(40);
        assertFalse(flow.hasRoom());
        assertTrue(flow.markDue(true), "closed window marks the open batch");
        int third = flow.mark(0);
        assertFalse(flow.acknowledge(third + 1, MS), "an unmarked batch cannot be acknowledged");
        assertTrue(flow.acknowledge(second, MS), "covers the first batch too");
        assertEquals(40, flow.inFlight());
        assertFalse(flow.acknowledge(first, MS), "older than the last acknowledgement");
        assertFalse(flow.acknowledge(second, MS), "duplicate");
        assertTrue(flow.acknowledge(third, MS));
        assertEquals(0, flow.inFlight());
    }

    @Test
    void aChunkLargerThanTheWindowGoesAlone() {
        LodBatchFlow flow = new LodBatchFlow(100, 100, 30, 25 * MS);
        assertTrue(flow.hasRoom());
        flow.chunkSent(250);
        assertFalse(flow.hasRoom());
        assertTrue(flow.acknowledge(flow.mark(0), MS));
        assertTrue(flow.hasRoom());
    }

    /**
     * Pipelined delivery for {@code seconds}: the window is kept full of one-batch chunks, each acknowledged
     * {@code delay} after its mark. The window at the end.
     */
    private static long run(LodBatchFlow flow, long[] clock, long delay, double seconds) {
        java.util.ArrayDeque<long[]> pending = new java.util.ArrayDeque<>();
        long end = clock[0] + (long) (seconds * 1e9);
        while (clock[0] < end) {
            while (flow.hasRoom()) {
                flow.chunkSent(10);
                pending.add(new long[]{flow.mark(clock[0]), clock[0] + delay});
            }
            long[] next = pending.poll();
            clock[0] = Math.max(clock[0], next[1]);
            assertTrue(flow.acknowledge((int) next[0], clock[0]));
        }
        return flow.window();
    }

    @Test
    void windowOpensToTheCeilingAndDoesNotYieldToJitterOrAForeignQueue() {
        LodBatchFlow flow = new LodBatchFlow(100, 1000, 10, 25 * MS);
        long[] clock = {0};
        assertEquals(100, flow.window(), "starts at the floor");
        assertEquals(1000, run(flow, clock, 54 * MS, 5), "a line wider than the ceiling: open to it");
        assertEquals(1000, run(flow, clock, 54 * MS + 150 * MS, 30), "another sender's queue or the line's jitter: the rate maximum holds");
    }

    @Test
    void aTrickleKeepsTheWindowNearWhatIsInFlight() {
        LodBatchFlow flow = new LodBatchFlow(100, 1000, 10, 25 * MS);
        long[] clock = {0};
        run(flow, clock, 54 * MS, 5);
        for (int i = 0; i < 100; i++) { // one chunk at a time, at base delay
            flow.chunkSent(10);
            int id = flow.mark(clock[0]);
            clock[0] += 54 * MS;
            assertTrue(flow.acknowledge(id, clock[0]));
        }
        assertEquals(100, flow.window(), "RFC 6817 max_allowed_cwnd: a window nobody fills closes to the floor");
    }

    @Test
    void aLongerRouteBecomesTheBaseAfterTheBaseHistory() {
        LodBatchFlow flow = new LodBatchFlow(100, 1000, 10, 25 * MS);
        long[] clock = {0};
        run(flow, clock, 10 * MS, 5);
        long shrunk = run(flow, clock, 80 * MS, 5 * 60); // the old rate expired, the old base not yet: too small a window
        assertTrue(shrunk < 1000, "window " + shrunk);
        assertEquals(1000, run(flow, clock, 80 * MS, 6 * 60), "the new route's delay became the base");
    }
}
