package ua.zentix.incoming.client;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.LodBatchFlow;
import ua.zentix.incoming.net.S2C;

import static org.junit.jupiter.api.Assertions.*;

class ClientLodBatchesTest {
    @Test
    void oneAcknowledgementCoversEveryMarkSinceTheLastAndWaitsWhileVoxyIsBehind() {
        ClientLodBatches receiver = new ClientLodBatches();
        assertNull(receiver.acknowledge(false), "nothing marked");
        receiver.mark(new S2C.LodBatchFinished(1, 2));
        assertEquals(1, receiver.acknowledge(false).id());
        assertNull(receiver.acknowledge(false), "exactly one acknowledgement per mark");
        receiver.mark(new S2C.LodBatchFinished(2, 2));
        receiver.mark(new S2C.LodBatchFinished(3, 1));
        for (int poll = 0; poll < 1200; poll++) assertNull(receiver.acknowledge(true), "queued work is not completed ingestion");
        assertEquals(3, receiver.acknowledge(false).id(), "cumulative");
        assertNull(receiver.acknowledge(false));
    }

    /** One server session over the client state: chunks until the window closes for good, marks acknowledged. */
    private static long session(ClientLodBatches client, int chunks) {
        int window = 3 << 19, batch = 64 * 1024, chunk = 40 * 1024;
        LodBatchFlow server = new LodBatchFlow(window, window, batch, 25_000_000);
        long delivered = 0, clock = 0;
        int left = chunks;
        while (left > 0 && server.hasRoom()) {
            server.chunkSent(chunk);
            delivered += chunk;
            left--;
            if (server.markDue(left > 0)) {
                int id = server.mark(clock);
                client.mark(new S2C.LodBatchFinished(id, server.markedChunks()));
                C2S.LodBatchReceived ack = client.acknowledge(false);
                clock += 54_000_000;
                if (ack != null) server.acknowledge(ack.id(), clock);
            }
        }
        return delivered;
    }

    /** Review probe of PR #774: behind a proxy a backend switch keeps the client channel; the new sender starts at 1. */
    @Test
    void aNewServerSessionOnTheSameClientChannelIsAcknowledged() {
        ClientLodBatches client = new ClientLodBatches();
        assertEquals(400L * 40 * 1024, session(client, 400), "first backend: every chunk delivered");
        assertEquals(400L * 40 * 1024, session(client, 400), "second backend: its window never reopened");
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
