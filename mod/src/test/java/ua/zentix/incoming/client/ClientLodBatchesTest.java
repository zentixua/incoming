package ua.zentix.incoming.client;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;

import static org.junit.jupiter.api.Assertions.*;

class ClientLodBatchesTest {
    @Test
    void oneAcknowledgementCoversEveryMarkSinceTheLastAndWaitsWhileVoxyIsBehind() {
        ClientLodBatches receiver = new ClientLodBatches();
        assertNull(receiver.acknowledge(false), "nothing marked");
        assertTrue(receiver.mark(new S2C.LodBatchFinished(1, 2)));
        assertEquals(1, receiver.acknowledge(false).id());
        assertNull(receiver.acknowledge(false), "exactly one acknowledgement per mark");
        assertTrue(receiver.mark(new S2C.LodBatchFinished(2, 2)));
        assertTrue(receiver.mark(new S2C.LodBatchFinished(3, 1)), "the next batch arrives before the previous is acknowledged");
        for (int poll = 0; poll < 1200; poll++) assertNull(receiver.acknowledge(true), "queued work is not completed ingestion");
        assertFalse(receiver.mark(new S2C.LodBatchFinished(3, 1)), "repeated mark");
        assertFalse(receiver.mark(new S2C.LodBatchFinished(2, 1)), "stale mark");
        assertEquals(3, receiver.acknowledge(false).id(), "cumulative");
        assertNull(receiver.acknowledge(false));
    }

    @Test
    void voxyQueueBelowTheLssHaltMarkDoesNotHoldTheAcknowledgement() {
        // LSS keeps Voxy's queue in the thousands while it streams: that alone must not stall Incoming's batches
        assertFalse(VoxyIngest.backlogged(0));
        assertFalse(VoxyIngest.backlogged(3072), "LSS rearm level");
        assertFalse(VoxyIngest.backlogged(VoxyIngest.INGEST_HALT_SECTIONS));
        assertTrue(VoxyIngest.backlogged(VoxyIngest.INGEST_HALT_SECTIONS + 1), "behind even by LSS's measure");
    }
}
