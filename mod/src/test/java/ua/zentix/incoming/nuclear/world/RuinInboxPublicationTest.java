package ua.zentix.incoming.nuclear.world;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuinInboxPublicationTest {
    @Test void readySlotFundsPublicationAtCapacityAndSurvivesInvalidation() {
        var bytes = new AtomicLong();
        var inbox = new RuinInbox<String>(bytes::addAndGet, 4096);
        inbox.expect().accept("actual detached payload");
        var publication = inbox.takeForPublication(inbox.peek(), 3000, 0);
        assertNotNull(publication);
        assertEquals(3000, bytes.get());
        assertEquals(1, inbox.pending());
        inbox.invalidate();
        assertEquals(3000, bytes.get(), "invalidating the queue must not release the active transition");
        publication.releaseToCountedOwner(2000);
        assertEquals(1000, bytes.get());
        publication.close(); publication.close();
        assertEquals(0, bytes.get());
        assertEquals(0, inbox.pending());
    }

    @Test void refusedGrowthKeepsTheSameHeadAndLateCancelledCallbackStaysOwned() {
        var bytes = new AtomicLong();
        var inbox = new RuinInbox<String>(bytes::addAndGet, 4096);
        inbox.expect().accept("large source");
        assertNull(inbox.takeForPublication(inbox.peek(), 5000, 903));
        assertEquals("large source", inbox.peek());
        assertEquals(4096, bytes.get());
        var publication = inbox.takeForPublication(inbox.peek(), 5000, 904);
        assertNotNull(publication);
        assertEquals(5000, bytes.get());
        var late = inbox.expect();
        inbox.invalidate();
        assertEquals(9096, bytes.get());
        late.accept("late decoded source"); late.accept("duplicate");
        assertEquals(5000, bytes.get());
        publication.close();
        assertEquals(0, bytes.get());
        assertEquals(0, inbox.pending());
    }
}
