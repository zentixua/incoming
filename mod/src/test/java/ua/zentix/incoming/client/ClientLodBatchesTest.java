package ua.zentix.incoming.client;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;

import static org.junit.jupiter.api.Assertions.*;

class ClientLodBatchesTest {
    @Test
    void acknowledgementWaitsForTheBackgroundIngestQueueWithoutBlocking() {
        ClientLodBatches receiver = new ClientLodBatches();
        receiver.begin(7);
        assertNull(receiver.acknowledge(false), "finish marker required");
        assertFalse(receiver.end(new S2C.LodBatchFinished(6, 4)));
        assertTrue(receiver.end(new S2C.LodBatchFinished(7, 4)));
        for (int poll = 0; poll < 1200; poll++) assertNull(receiver.acknowledge(true), "queued work is not completed ingestion");
        var ack = receiver.acknowledge(false);
        assertNotNull(ack);
        assertEquals(7, ack.id());
        assertTrue(Float.isFinite(ack.desiredChunksPerTick()) && ack.desiredChunksPerTick() > 0);
        assertNull(receiver.acknowledge(false), "exactly one acknowledgement");
        receiver.begin(8);
        assertFalse(receiver.end(new S2C.LodBatchFinished(7, 4)), "previous batch cannot finish a new batch");
        assertTrue(receiver.end(new S2C.LodBatchFinished(8, 2)));
        assertEquals(8, receiver.acknowledge(false).id());
    }
}
