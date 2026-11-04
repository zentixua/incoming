package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RuinRecordsTest {
    private static RuinRecords<Long> queue() { return new RuinRecords<>(8, ByteBuffer::putLong, ByteBuffer::getLong); }

    @Test void snapshotsStayImmutableAcrossAppendDrainAndRestore() {
        var queue = queue();
        var expected = new ArrayDeque<Long>();
        Random random = new Random(81);
        for (int i = 0; i < 9000; i++) { long n = random.nextLong(); queue.addLast(n); expected.addLast(n); }
        for (int i = 0; i < 319; i++) assertEquals(expected.removeFirst(), queue.removeFirst());
        List<byte[]> snapshot = queue.snapshot();
        var saved = new ArrayDeque<>(expected);
        for (int i = 0; i < 20000; i++) {
            if (!expected.isEmpty() && random.nextBoolean()) assertEquals(expected.removeFirst(), queue.removeFirst());
            else { long n = random.nextLong(); expected.addLast(n); queue.addLast(n); }
        }
        var restored = queue();
        snapshot.forEach(restored::restore);
        restored.addLast(99L); saved.addLast(99L);
        while (!saved.isEmpty()) assertEquals(saved.removeFirst(), restored.removeFirst());
        assertTrue(restored.isEmpty());
        assertEquals(expected.size(), queue.size());
        for (long n : queue) assertEquals(expected.removeFirst(), n);
    }

    @Test void fullPagesAreSharedAndOnlyPartialEndsCopied() {
        var queue = queue();
        for (long i = 0; i < 10001; i++) queue.addLast(i);
        var first = queue.snapshot();
        queue.removeFirst(); queue.addLast(10001L);
        var second = queue.snapshot();
        for (int i = 1; i < first.size() - 1; i++) assertSame(first.get(i), second.get(i));
        assertNotSame(first.getFirst(), second.getFirst());
        assertNotSame(first.getLast(), second.getLast());
        assertThrows(IllegalArgumentException.class, () -> queue.restore(new byte[7]));
    }
}
