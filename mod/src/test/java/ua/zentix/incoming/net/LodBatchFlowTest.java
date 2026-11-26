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

    /** One window per round trip, acknowledged after {@code delay}: the window after {@code rounds} of them. */
    private static long rounds(LodBatchFlow flow, int rounds, long[] clock, long delay) {
        for (int r = 0; r < rounds; r++) {
            while (flow.hasRoom()) flow.chunkSent(10);
            int id = flow.mark(clock[0]);
            clock[0] += delay;
            assertTrue(flow.acknowledge(id, clock[0]));
        }
        return flow.window();
    }

    @Test
    void windowGrowsWithoutQueueingAndShrinksToTheFloorAboveTheTarget() {
        LodBatchFlow flow = new LodBatchFlow(100, 1000, 10, 25 * MS);
        long[] clock = {0};
        assertEquals(100, flow.window(), "starts at the floor");
        assertEquals(1000, rounds(flow, 40, clock, 54 * MS), "no queueing delay: up to the ceiling");
        long held = rounds(flow, 5, clock, 54 * MS + 20 * MS);
        assertTrue(held > 900, "queue under the target keeps growing slowly or holds: " + held);
        assertEquals(100, rounds(flow, 30, clock, 54 * MS + 80 * MS), "a queue three times the target closes it to the floor");
        assertEquals(1000, rounds(flow, 40, clock, 54 * MS), "the queue drained: open again");
    }

    @Test
    void baseDelayForgetsAMinuteOldLowRoundTrip() {
        LodBatchFlow flow = new LodBatchFlow(100, 1000, 10, 25 * MS);
        long[] clock = {0};
        rounds(flow, 40, clock, 10 * MS);
        clock[0] += 2 * LodBatchFlow.BASE_BUCKET_NANOS;
        rounds(flow, 3, clock, 80 * MS); // a longer route from now on: one bucket roll measures it as queue
        clock[0] += LodBatchFlow.BASE_BUCKET_NANOS;
        assertEquals(1000, rounds(flow, 40, clock, 80 * MS), "the new route's delay became the base");
    }
}
