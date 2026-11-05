package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MtB7RefusalsTest {
    @Test void capOverrideRequiresDiagnosticGateAndRejectsUnexpectedValues() {
        assertEquals(512L << 20, MtB7Refusals.cap(false, "1536"));
        assertEquals(512L << 20, MtB7Refusals.cap(false, "malformed"));
        assertEquals(512L << 20, MtB7Refusals.cap(true, null));
        assertEquals(1536L << 20, MtB7Refusals.cap(true, "1536"));
        assertEquals(2048L << 20, MtB7Refusals.cap(true, "2048"));
        for (String value : new String[]{"0", "-1", "511", "513", "2049", "99999999999", "bad"})
            assertThrows(IllegalArgumentException.class, () -> MtB7Refusals.cap(true, value));
    }

    @Test void firstChunkReasonIsImmediateThenRateLimitedWithoutEviction() {
        var trace = new MtB7Refusals(1);
        assertTrue(trace.admit(10, MtB7Refusals.Reason.STALE, 100));
        assertFalse(trace.admit(10, MtB7Refusals.Reason.STALE, 100));
        assertFalse(trace.admit(10, MtB7Refusals.Reason.STALE, 299));
        assertTrue(trace.admit(10, MtB7Refusals.Reason.MEMORY, 101));
        assertTrue(trace.admit(10, MtB7Refusals.Reason.STALE, 300));
        for (long key = 11; key < 10 + MtB7Refusals.CHUNK_LIMIT; key++)
            assertTrue(trace.admit(key, MtB7Refusals.Reason.STALE, 300));
        assertFalse(trace.admit(-1, MtB7Refusals.Reason.STALE, 300));
        assertTrue(trace.admit(10, MtB7Refusals.Reason.STALE, 500));
        assertFalse(trace.admit(-1, MtB7Refusals.Reason.STALE, 1000));
        assertTrue(trace.snapshot().contains("refusal_trace_chunks=2048"));
        assertTrue(trace.snapshot().contains("refusal_trace_overflow=2"));
    }
}
