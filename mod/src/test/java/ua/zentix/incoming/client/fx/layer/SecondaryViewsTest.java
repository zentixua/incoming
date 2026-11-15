package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecondaryViewsTest {
    private record Target(int framebuffer, int depth, int width, int height) {}

    @Test
    void equalSizeTargetsKeepSeparateHistoriesAndResizeRetiresTheOldOne() {
        List<Object> freed = new ArrayList<>();
        SecondaryViews<Target, Object> cache = new SecondaryViews<>(freed::add);
        var a = cache.acquire(new Target(1, 11, 96, 96), 0, Object::new);
        cache.release(a, 0);
        var b = cache.acquire(new Target(2, 12, 96, 96), 1, Object::new);
        assertNotSame(a.value, b.value);
        cache.release(b, 1);
        var again = cache.acquire(a.key, 2, () -> { throw new AssertionError("History unexpectedly recreated"); });
        assertSame(a.value, again.value);
        cache.release(again, 2);
        var resized = cache.acquire(new Target(1, 13, 192, 192), 3, Object::new);
        assertNotSame(a.value, resized.value);
        cache.expire(SecondaryViews.TTL_NS + 2);
        assertEquals(List.of(b.value, a.value), freed);
        assertEquals(1, cache.size());
        cache.release(resized, SecondaryViews.TTL_NS + 2);
        cache.clear();
        cache.clear();
        assertEquals(3, freed.size());
    }

    @Test
    void fifthFeedWaitsForExpiryAndNestedRendersRemainPinned() {
        List<Integer> freed = new ArrayList<>();
        SecondaryViews<Integer, Integer> cache = new SecondaryViews<>(freed::add);
        var first = cache.acquire(0, 0, () -> 0);
        for (int i = 1; i < SecondaryViews.LIMIT; i++) {
            int value = i;
            cache.acquire(i, i, () -> value);
        }
        assertNull(cache.acquire(4, 4, () -> { throw new AssertionError("Must not allocate a fifth active view"); }));
        cache.expire(2 * SecondaryViews.TTL_NS);
        assertEquals(4, cache.size());
        cache.release(first, 2 * SecondaryViews.TTL_NS);
        assertNull(cache.acquire(4, 2 * SecondaryViews.TTL_NS + 1, () -> 4));
        assertNotNull(cache.acquire(4, 3 * SecondaryViews.TTL_NS, () -> 4));
        assertEquals(List.of(0), freed);
        assertEquals(4, cache.size());
        cache.clear();
        assertEquals(5, freed.stream().distinct().count());
    }

    @Test
    void recursionCannotSwapAnActiveHistoryAndReplacedAttachmentsAreFreed() {
        List<Object> freed = new ArrayList<>();
        SecondaryViews<Target, Object> cache = new SecondaryViews<>(freed::add);
        Target target = new Target(1, 11, 96, 96);
        var entry = cache.acquire(target, 0, Object::new);
        assertNull(cache.acquire(target, 1, () -> { throw new AssertionError("Recursive acquisition"); }));
        cache.invalidate(key -> key.framebuffer == 1);
        assertTrue(freed.isEmpty());
        cache.release(entry, 2);
        cache.invalidate(key -> key.framebuffer == 1);
        assertEquals(List.of(entry.value), freed);
        assertEquals(0, cache.size());
    }

    @Test
    void expiryStartsAtLastUseAndIsIndependentOfRefreshRate() {
        List<Integer> freed = new ArrayList<>();
        SecondaryViews<Integer, Integer> cache = new SecondaryViews<>(freed::add);
        var entry = cache.acquire(1, 0, () -> 1);
        cache.release(entry, 100);
        cache.expire(100 + SecondaryViews.TTL_NS - 1);
        assertTrue(freed.isEmpty());
        cache.expire(100 + SecondaryViews.TTL_NS);
        assertEquals(List.of(1), freed);
        assertEquals(0, cache.size());
    }
}
