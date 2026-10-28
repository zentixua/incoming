package ua.zentix.incoming.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodBatchFlowTest {
    @Test
    void onlyMatchingFinishedBatchCanBeAcknowledgedAndRateIsBounded() {
        LodBatchFlow flow = new LodBatchFlow();
        assertFalse(flow.acknowledge(0, 64));
        assertTrue(flow.begin());
        assertFalse(flow.acknowledge(flow.id(), 64));
        flow.chunkSent();
        flow.finish();
        assertFalse(flow.acknowledge(flow.id() + 1, 64));
        assertFalse(flow.begin());
        assertTrue(flow.acknowledge(flow.id(), Float.MAX_VALUE));
        assertTrue(flow.begin());
        int chunks = 0;
        while (flow.hasRoom()) {
            flow.chunkSent();
            chunks++;
        }
        assertEquals(64, chunks);
        flow.finish();
        assertTrue(flow.acknowledge(flow.id(), Float.NaN));
        int ticks = 0;
        while (!flow.begin() && ticks < 101) ticks++;
        assertTrue(ticks >= 99 && ticks <= 100, "invalid rate chooses 0.01 chunks/tick");
        assertTrue(flow.hasRoom());
        flow.chunkSent();
        assertFalse(flow.hasRoom());
    }
}
