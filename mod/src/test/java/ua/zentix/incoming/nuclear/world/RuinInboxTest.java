package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuinInboxTest {
    @Test
    void discardedOwnersKeepTheirReadsChargedUntilCompletion() {
        var bytes = new java.util.concurrent.atomic.AtomicLong();
        RuinInbox<String> old = new RuinInbox<>(bytes::addAndGet, 1024);
        var read = old.expect();
        old.invalidate();
        old = null;
        RuinInbox<String> next = new RuinInbox<>(bytes::addAndGet, 1024);
        var fresh = next.expect();
        assertEquals(2048, bytes.get());
        read.accept("cancelled owner's late copy");
        assertEquals(1024, bytes.get());
        fresh.accept("fresh");
        assertEquals(1024, bytes.get());
        assertEquals("fresh", next.poll());
        assertEquals(0, bytes.get());
    }

    @Test
    void cancelledCopyCannotReplaceNewCopyAtTheSameCoordinate() {
        RuinInbox<String> inbox = new RuinInbox<>();
        var old = inbox.expect();
        var alreadyReady = inbox.expect();
        alreadyReady.accept("old ready tile");
        inbox.invalidate();
        assertEquals(1, inbox.pending(), "running old I/O remains charged");
        var fresh = inbox.expect();
        fresh.accept("new tile at the same coordinate");
        old.accept("late old tile");
        assertEquals(1, inbox.pending());
        assertEquals("new tile at the same coordinate", inbox.poll());
        assertNull(inbox.poll());
        assertEquals(0, inbox.pending());
    }

    @Test
    void repeatedCancellationDoesNotLoseInFlightReservationsOrAcceptDuplicateCompletion() {
        RuinInbox<Integer> inbox = new RuinInbox<>();
        var first = inbox.expect();
        inbox.invalidate();
        inbox.invalidate();
        assertEquals(1, inbox.pending());
        first.accept(1);
        first.accept(1);
        assertEquals(0, inbox.pending());
        assertNull(inbox.poll());
        var next = inbox.expect();
        next.accept(2);
        assertEquals(1, inbox.pending(), "ready result still owns its reservation until collected");
        inbox.invalidate();
        assertEquals(0, inbox.pending());
        assertNull(inbox.poll());
    }
}
