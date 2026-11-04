package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuinFragmentCacheBudgetTest {
    private final long baseline = RuinResults.bytes();
    private final ArrayList<RuinResults.CacheBudget> budgets = new ArrayList<>();
    private final ArrayList<RuinResults.Lease> leases = new ArrayList<>();

    private RuinResults.CacheBudget cache(long limit) {
        var budget = RuinResults.tryReserveCache(limit, limit + RuinResults.OWNER_BYTES);
        assertNotNull(budget); budgets.add(budget); return budget;
    }

    private RuinResults.Lease producer() {
        var lease = RuinResults.tryReserve(1000, 2000, 3000 + RuinResults.OWNER_BYTES);
        assertNotNull(lease); leases.add(lease); return lease;
    }

    @AfterEach
    void clean() {
        for (var lease : leases) { lease.cancel(); lease.actualExit(); }
        for (var budget : budgets) budget.close();
        assertNull(RuinResults.currentProducer());
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void fixedBudgetRemainsFullWithoutAnyEntriesUntilClosed() {
        var empty = cache(0); var full = cache(8192);
        assertEquals(RuinResults.OWNER_BYTES, empty.bytes());
        assertEquals(8192 + RuinResults.OWNER_BYTES, full.bytes());
        assertEquals(baseline + empty.bytes() + full.bytes(), RuinResults.bytes());
        for (int i = 0; i < 100; i++) assertEquals(8192 + RuinResults.OWNER_BYTES, full.bytes());
        full.close(); full.release(); empty.release(); empty.close();
        assertEquals(0, full.bytes()); assertEquals(0, empty.bytes());
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void refusalAndScalarOverflowCannotChangeTheCounter() {
        assertNull(RuinResults.tryReserveCache(100, 99 + RuinResults.OWNER_BYTES));
        assertNull(RuinResults.tryReserveCache(0, -1));
        assertNull(RuinResults.tryReserveCache(Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> RuinResults.tryReserveCache(-1, 1000));
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void cacheAndResultAdmissionShareTheSameOverflowSafeAtomicCounter() {
        var largest = cache(Long.MAX_VALUE - RuinResults.OWNER_BYTES - baseline);
        assertEquals(Long.MAX_VALUE, RuinResults.bytes());
        assertNull(RuinResults.tryReserveCache(0, Long.MAX_VALUE));
        assertNull(RuinResults.tryReserve(0, Long.MAX_VALUE));
        largest.close(); assertEquals(baseline, RuinResults.bytes());
        var result = producer();
        assertNull(RuinResults.tryReserveCache(Long.MAX_VALUE - RuinResults.OWNER_BYTES, Long.MAX_VALUE));
        assertEquals(baseline + 3000 + RuinResults.OWNER_BYTES, RuinResults.bytes());
        result.abortBeforeSubmit();
    }

    @Test
    void cacheCloseAndResultCancellationRefundOnlyTheirOwnReservations() {
        for (boolean cacheFirst : new boolean[]{false, true}) {
            var fixed = cache(4096); var result = producer();
            result.markResult(RuinPlan.EMPTY); result.cancel();
            if (cacheFirst) fixed.close();
            assertEquals(baseline + (cacheFirst ? 0 : fixed.bytes()) + 2000 + RuinResults.OWNER_BYTES,
                    RuinResults.bytes(), "Completed result must keep its temporary fragments through actual exit");
            result.actualExit();
            assertEquals(baseline + fixed.bytes(), RuinResults.bytes());
            fixed.close(); assertEquals(baseline, RuinResults.bytes());
        }
    }

    @Test
    void concurrentCacheCloseIsExactlyOnceWhileOtherOwnersStayCharged() throws Exception {
        var fixed = cache(1 << 20); var other = cache(4096); var result = producer();
        var start = new CountDownLatch(1); var threads = new ArrayList<Thread>();
        try {
            for (int i = 0; i < 16; i++) threads.add(Thread.ofPlatform().start(() -> {
                try { start.await(); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                for (int j = 0; j < 100; j++) { fixed.close(); fixed.release(); }
            }));
        } finally {
            start.countDown();
            for (var thread : threads) thread.join(5000);
        }
        for (var thread : threads) assertFalse(thread.isAlive());
        assertEquals(0, fixed.bytes());
        assertEquals(baseline + other.bytes() + 3000 + RuinResults.OWNER_BYTES, RuinResults.bytes());
        result.abortBeforeSubmit(); other.close();
    }

    @Test
    void budgetOwnsNoPayloadAndLeaseGetsNoCacheReferencesOrNewState() {
        for (var field : RuinResults.CacheBudget.class.getDeclaredFields())
            assertTrue(field.getType().isPrimitive(), "Cache budget retained a payload/context reference: " + field);
        var existing = Set.of("maximum", "charged", "fragments", "future", "marked", "empty", "exited", "cancelled", "released");
        for (var field : RuinResults.Lease.class.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()))
            assertTrue(existing.contains(field.getName()), "Lease gained unrelated cache ownership: " + field);
    }

    @Test
    void successfulScopeClearsBeforeResultPublicationAndActualExit() throws Exception {
        var lease = producer();
        var result = RuinResults.producing(lease, () -> {
            assertSame(lease, RuinResults.currentProducer()); return RuinPlan.EMPTY;
        });
        assertNull(RuinResults.currentProducer());
        lease.markResult(result);
        assertEquals(baseline + 2000 + RuinResults.OWNER_BYTES, RuinResults.bytes());
        lease.actualExit(); assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void nestedScopesRestoreTheOuterProducerAfterBothSuccessAndFailure() throws Exception {
        var outer = producer(); var inner = producer();
        int answer = RuinResults.producing(outer, () -> {
            assertSame(outer, RuinResults.currentProducer());
            int value = RuinResults.producing(inner, () -> {
                assertSame(inner, RuinResults.currentProducer()); return 17;
            });
            assertSame(outer, RuinResults.currentProducer());
            assertThrows(Exception.class, () -> RuinResults.producing(inner, () -> { throw new Exception("scope"); }));
            assertSame(outer, RuinResults.currentProducer()); return value;
        });
        assertEquals(17, answer); assertNull(RuinResults.currentProducer());
    }

    @Test
    void checkedExceptionCancellationAndErrorAlwaysRemoveTheScope() {
        var lease = producer();
        assertThrows(Exception.class, () -> RuinResults.producing(lease, () -> { throw new Exception("checked"); }));
        assertNull(RuinResults.currentProducer());
        assertThrows(CancellationException.class, () -> RuinResults.producing(lease, () -> { throw new CancellationException(); }));
        assertNull(RuinResults.currentProducer());
        assertThrows(AssertionError.class, () -> RuinResults.producing(lease, () -> { throw new AssertionError("error"); }));
        assertNull(RuinResults.currentProducer());
    }

    @Test
    void producerScopeIsNotInheritedByAnotherThreadOrGenericCallable() throws Exception {
        var lease = producer();
        RuinResults.producing(lease, () -> {
            var clean = new AtomicBoolean();
            var thread = Thread.ofPlatform().start(() -> clean.set(RuinResults.currentProducer() == null));
            thread.join(5000); assertFalse(thread.isAlive()); assertTrue(clean.get());
            assertSame(lease, RuinResults.currentProducer()); return null;
        });
        assertNull(RuinResults.currentProducer());
        assertNull(((java.util.concurrent.Callable<RuinResults.Lease>) RuinResults::currentProducer).call());
    }

    @Test
    void cancelledActualWorkerClearsScopeBeforeItsExitCallback() throws Exception {
        var lease = producer(); var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var cleanAtExit = new AtomicBoolean();
        var task = new FutureTask<>(() -> RuinResults.producing(lease, () -> {
            assertSame(lease, RuinResults.currentProducer()); entered.countDown();
            assertTrue(proceed.await(5, TimeUnit.SECONDS));
            assertSame(lease, RuinResults.currentProducer()); return RuinPlan.EMPTY;
        }));
        lease.bindFuture(task);
        var thread = Thread.ofPlatform().unstarted(() -> {
            try { task.run(); }
            finally {
                cleanAtExit.set(RuinResults.currentProducer() == null);
                lease.actualExit();
            }
        });
        try {
            thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            lease.cancel(); task.cancel(false);
            assertEquals(baseline + 3000 + RuinResults.OWNER_BYTES, RuinResults.bytes());
            assertNull(RuinResults.currentProducer());
        } finally { proceed.countDown(); thread.join(5000); }
        assertFalse(thread.isAlive()); assertTrue(cleanAtExit.get());
        assertEquals(baseline, RuinResults.bytes());
    }
}
