package ua.zentix.incoming.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodBatchFlowTest {
    @Test
    void severalBatchesStayInFlightUpToTheWindowAndAcknowledgementsAreCumulative() {
        LodBatchFlow flow = new LodBatchFlow(100, 30);
        assertFalse(flow.acknowledge(0), "nothing marked");
        assertFalse(flow.markDue(true), "no open batch");
        flow.chunkSent(20);
        assertFalse(flow.markDue(true), "batch not full, more coming");
        assertTrue(flow.markDue(false), "nothing more waiting: mark now");
        flow.chunkSent(20);
        assertTrue(flow.markDue(true), "batch full");
        int first = flow.mark();
        assertEquals(2, flow.markedChunks());
        flow.chunkSent(40);
        int second = flow.mark();
        assertTrue(flow.hasRoom(), "80 of 100 in flight: a second batch went before any acknowledgement");
        flow.chunkSent(40);
        assertFalse(flow.hasRoom());
        assertTrue(flow.markDue(true), "closed window marks the open batch");
        int third = flow.mark();
        assertFalse(flow.acknowledge(third + 1), "an unmarked batch cannot be acknowledged");
        assertTrue(flow.acknowledge(second), "covers the first batch too");
        assertEquals(40, flow.inFlight());
        assertFalse(flow.acknowledge(first), "older than the last acknowledgement");
        assertFalse(flow.acknowledge(second), "duplicate");
        assertTrue(flow.hasRoom());
        assertTrue(flow.acknowledge(third));
        assertEquals(0, flow.inFlight());
    }

    @Test
    void aChunkLargerThanTheWindowGoesAlone() {
        LodBatchFlow flow = new LodBatchFlow(100, 30);
        assertTrue(flow.hasRoom());
        flow.chunkSent(250);
        assertFalse(flow.hasRoom());
        int id = flow.mark();
        assertTrue(flow.acknowledge(id));
        assertTrue(flow.hasRoom());
    }
}
