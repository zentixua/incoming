package ua.zentix.incoming.nuclear.world;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuinResultCacheTest {
    private final long baseline = RuinResults.bytes();
    private final ArrayList<RuinResults.Lease> leases = new ArrayList<>();
    private final ArrayList<RuinResults.Cache> caches = new ArrayList<>();

    private RuinResults.Lease reserve(long plan, long fragments) {
        var lease = RuinResults.tryReserve(plan, fragments, plan + fragments + RuinResults.OWNER_BYTES);
        assertNotNull(lease); leases.add(lease); return lease;
    }

    private RuinResults.Cache split(RuinResults.Lease lease, long bytes) {
        var cache = lease.splitCache(bytes); caches.add(cache); return cache;
    }

    private static RuinPlan plan() {
        return new RuinPlan(new int[0], new long[0], new BlockState[0], new BlockState[0], new int[0],
                new int[0], new long[0], null, null, null, null, null, new long[0], null);
    }

    @AfterEach
    void clean() {
        for (var lease : leases) { lease.cancel(); lease.actualExit(); }
        for (var cache : caches) cache.release();
        assertNull(RuinResults.currentProducer());
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void combinedAdmissionIncludesBothBoundsAndMetadataWithoutOverflow() {
        assertNull(RuinResults.tryReserve(20, 30, 49 + RuinResults.OWNER_BYTES));
        assertNull(RuinResults.tryReserve(Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertNull(RuinResults.tryReserve(1, Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> RuinResults.tryReserve(0, -1, 100));
        var lease = reserve(20, 30);
        assertEquals(baseline + 50 + RuinResults.OWNER_BYTES, RuinResults.bytes());
        lease.abortBeforeSubmit();
    }

    @Test
    void cacheTransferDoesNotChangeGlobalBytesOrSpendPlanAllowance() {
        var plan = plan(); var lease = reserve(plan.bytes() + 500, 1000);
        long before = RuinResults.bytes(); var first = split(lease, 300); var second = split(lease, 400);
        assertEquals(before, RuinResults.bytes());
        assertEquals(300, first.bytes()); assertEquals(400, second.bytes());
        lease.markResult(plan); lease.actualExit();
        assertEquals(baseline + plan.bytes() + RuinResults.OWNER_BYTES + 700, RuinResults.bytes());
        plan.releaseResultToCountedOwner();
        assertEquals(baseline + 700, RuinResults.bytes());
        first.release(); second.release(); assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void cancellationKeepsPrivateRemainderUntilExitAndPublishedCacheBeyondExit() {
        var lease = reserve(200, 1000); var cache = split(lease, 300);
        lease.cancel();
        assertEquals(baseline + 1200 + RuinResults.OWNER_BYTES, RuinResults.bytes());
        lease.actualExit();
        assertEquals(baseline + 300, RuinResults.bytes());
        lease.cancel(); lease.actualExit(); cache.release(); cache.release();
        assertEquals(0, cache.bytes());
    }

    @Test
    void failureAndEmptyReleaseUnusedOutputButCannotRefundPublishedCache() {
        for (boolean empty : new boolean[]{false, true}) {
            var lease = reserve(100, 1000); var cache = split(lease, 250);
            if (empty) lease.markResult(RuinPlan.EMPTY);
            else lease.bindFuture(CompletableFuture.failedFuture(new IllegalStateException()));
            lease.actualExit();
            assertEquals(baseline + 250, RuinResults.bytes());
            cache.release(); assertEquals(baseline, RuinResults.bytes());
        }
    }

    @Test
    void releasedCacheDoesNotMintAdditionalProducerAllowance() {
        var lease = reserve(100, 1000); var cache = split(lease, 900); cache.release();
        assertThrows(IllegalStateException.class, () -> lease.splitCache(101));
        split(lease, 100);
        assertThrows(IllegalArgumentException.class, () -> lease.splitCache(-1));
        lease.markResult(RuinPlan.EMPTY);
        assertThrows(IllegalStateException.class, () -> lease.splitCache(0));
        lease.actualExit();
        assertThrows(IllegalStateException.class, () -> lease.splitCache(0));
    }

    @Test
    void producingScopeRestoresNestedContextOnSuccessAndFailure() throws Exception {
        var outer = reserve(0, 100); var inner = reserve(0, 100);
        assertNull(RuinResults.currentProducer());
        assertEquals(7, RuinResults.producing(outer, () -> {
            assertSame(outer, RuinResults.currentProducer());
            assertThrows(IllegalStateException.class, () -> RuinResults.producing(inner, () -> {
                assertSame(inner, RuinResults.currentProducer()); throw new IllegalStateException();
            }));
            assertSame(outer, RuinResults.currentProducer()); return 7;
        }));
        assertNull(RuinResults.currentProducer());
        assertThrows(CancellationException.class, () -> RuinResults.producing(outer, () -> {
            throw new CancellationException();
        }));
        assertNull(RuinResults.currentProducer());
    }

    @Test
    void producerScopeDoesNotLeakIntoAnotherThread() throws Exception {
        var lease = reserve(0, 100);
        RuinResults.producing(lease, () -> {
            var observed = new java.util.concurrent.atomic.AtomicReference<RuinResults.Lease>();
            var thread = Thread.ofPlatform().start(() -> observed.set(RuinResults.currentProducer()));
            thread.join(); assertNull(observed.get());
            assertSame(lease, RuinResults.currentProducer()); return null;
        });
        assertNull(RuinResults.currentProducer());
    }

    @Test
    void cacheRefundAndProducerRefundAreIndependentUnderConcurrentRelease() throws Exception {
        var lease = reserve(1000, 1000); var cache = split(lease, 700);
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 16; i++) threads.add(Thread.ofPlatform().start(cache::release));
        lease.cancel(); lease.actualExit();
        for (var thread : threads) thread.join();
        assertEquals(baseline, RuinResults.bytes());
        for (var field : RuinResults.Cache.class.getDeclaredFields())
            assertTrue(field.getType().isPrimitive(), "Cache permit retained a payload/world reference");
    }
}
