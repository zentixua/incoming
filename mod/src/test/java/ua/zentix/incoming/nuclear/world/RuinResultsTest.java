package ua.zentix.incoming.nuclear.world;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import net.minecraft.world.level.block.state.BlockState;
import static org.junit.jupiter.api.Assertions.*;

class RuinResultsTest {
    private final long baseline = RuinResults.bytes();
    private final ArrayList<RuinResults.Lease> leases = new ArrayList<>();

    private RuinResults.Lease reserve(long maximum) {
        var lease = RuinResults.tryReserve(maximum, maximum + RuinResults.OWNER_BYTES);
        assertNotNull(lease);
        leases.add(lease);
        return lease;
    }

    @AfterEach
    void clean() {
        for (var lease : leases) { lease.cancel(); lease.actualExit(); }
        assertEquals(baseline, RuinResults.bytes());
    }

    private static RuinPlan plan() {
        return new RuinPlan(new int[2], new long[3], new BlockState[4], new BlockState[2], new int[5],
                new int[6], new long[7], new long[8], new long[9], null, null, null, new long[10], null);
    }

    @Test
    void roomIncludesMetadataAndOverflowNeverCreatesOwnership() {
        assertNull(RuinResults.tryReserve(100, 100 + RuinResults.OWNER_BYTES - 1));
        assertNull(RuinResults.tryReserve(Long.MAX_VALUE, Long.MAX_VALUE));
        assertNull(RuinResults.tryReserve(0, -1));
        assertThrows(IllegalArgumentException.class, () -> RuinResults.tryReserve(-1, 100));
        assertEquals(baseline, RuinResults.bytes());
        var zero = reserve(0); zero.abortBeforeSubmit(); zero.abortBeforeSubmit();
    }

    @Test
    void aggregateNumericOwnershipCannotWrapAtLongLimit() {
        var full = reserve(Long.MAX_VALUE - RuinResults.OWNER_BYTES - baseline);
        assertEquals(Long.MAX_VALUE, RuinResults.bytes());
        assertNull(RuinResults.tryReserve(0, Long.MAX_VALUE));
        full.abortBeforeSubmit();
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void completedUncollectedAndLocalHandoffRemainCharged() {
        var p = plan(); var lease = reserve(p.bytes() + 1000);
        lease.markResult(p);
        lease.bindFuture(CompletableFuture.completedFuture(p));
        lease.actualExit();
        lease.detachFuture();
        assertEquals(baseline + p.bytes() + RuinResults.OWNER_BYTES, RuinResults.bytes());
        p.releaseResultToCountedOwner(); p.releaseResultToCountedOwner();
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void cancelledExecutionKeepsFullReservationUntilActualExit() throws Exception {
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var p = plan(); var lease = reserve(p.bytes() + 1000);
        var task = new FutureTask<>(() -> {
            entered.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS));
            lease.markResult(p); return p;
        });
        var thread = Thread.ofPlatform().unstarted(() -> { try { task.run(); } finally { lease.actualExit(); } });
        lease.bindFuture(task);
        try {
            thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            lease.cancel(); task.cancel(false); lease.cancel();
            assertEquals(baseline + p.bytes() + 1000 + RuinResults.OWNER_BYTES, RuinResults.bytes());
        } finally { proceed.countDown(); thread.join(5000); }
        assertFalse(thread.isAlive()); assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void discardAfterMarkStillWaitsForExit() {
        var p = plan(); var lease = reserve(p.bytes()); lease.markResult(p);
        p.discardResult(); p.discardResult();
        assertEquals(baseline + p.bytes() + RuinResults.OWNER_BYTES, RuinResults.bytes());
        lease.actualExit(); assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void queuedNeverStartedAndFailureReleaseAtCallback() {
        var lease = reserve(1000);
        var task = new FutureTask<>(() -> { throw new AssertionError("queued task ran"); });
        lease.bindFuture(task); task.cancel(false); lease.actualExit();
        lease.actualExit(); assertEquals(baseline, RuinResults.bytes());
        var failed = reserve(1000); failed.actualExit();
        failed.bindFuture(CompletableFuture.failedFuture(new IllegalStateException()));
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void failedAfterMarkIsNotMistakenForSuccessfulUncollectedResult() {
        var p = plan(); var lease = reserve(p.bytes()); lease.markResult(p);
        lease.bindFuture(CompletableFuture.failedFuture(new IllegalStateException()));
        lease.actualExit(); assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void completionBeforeBindingHandlesSuccessCancellationAndFailure() {
        for (int outcome = 0; outcome < 3; outcome++) {
            var p = plan(); var lease = reserve(p.bytes()); lease.markResult(p); lease.actualExit();
            var future = new CompletableFuture<RuinPlan>();
            if (outcome == 0) future.complete(p);
            else if (outcome == 1) future.cancel(false);
            else future.completeExceptionally(new IllegalStateException());
            lease.bindFuture(future);
            assertEquals(baseline + (outcome == 0 ? p.bytes() + RuinResults.OWNER_BYTES : 0), RuinResults.bytes());
            p.releaseResultToCountedOwner();
        }
    }

    @Test
    void queuedPromotionDoesNotCancelReplacementOwner() {
        var p = plan(); var lease = reserve(p.bytes());
        var old = new CompletableFuture<RuinPlan>(); lease.bindFuture(old);
        lease.beginTransfer(); old.cancel(false);
        var next = new CompletableFuture<RuinPlan>(); lease.bindFuture(next);
        lease.markResult(p); next.complete(p); lease.actualExit();
        assertEquals(baseline + p.bytes() + RuinResults.OWNER_BYTES, RuinResults.bytes());
        p.releaseResultToCountedOwner();
    }

    @Test
    void promotionThatCannotMoveRebindsCancelledOldFuture() {
        var lease = reserve(1000); var old = new CompletableFuture<RuinPlan>(); lease.bindFuture(old);
        lease.beginTransfer(); old.cancel(false); lease.actualExit(); lease.bindFuture(old);
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void transferMayPrecedeExitOnlyAfterCountedConsumerTakesOwnership() {
        var p = plan(); var lease = reserve(p.bytes()); lease.markResult(p);
        p.releaseResultToCountedOwner(); lease.actualExit(); lease.cancel();
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void emptySingletonNeverSharesMutableLease() throws Exception {
        var first = reserve(1000); var second = reserve(1000);
        first.markResult(RuinPlan.EMPTY); second.markResult(RuinPlan.EMPTY);
        var field = RuinPlan.class.getDeclaredField("resultOwner"); field.setAccessible(true);
        assertNull(field.get(RuinPlan.EMPTY));
        first.actualExit();
        assertEquals(baseline + RuinResults.OWNER_BYTES, RuinResults.bytes());
        second.actualExit();
        assertEquals(baseline, RuinResults.bytes());
        first.releaseToCountedOwner(); second.releaseToCountedOwner();
    }

    @Test
    void oversizedOutputAndDuplicateOwnerCannotSilentlyShrinkCharge() {
        var p = plan(); var small = reserve(p.bytes() - 1);
        assertThrows(IllegalStateException.class, () -> small.markResult(p)); small.actualExit();
        var first = reserve(p.bytes()); var second = reserve(p.bytes()); first.markResult(p);
        assertThrows(IllegalStateException.class, () -> second.markResult(p)); second.actualExit();
        p.releaseResultToCountedOwner();
    }

    @Test
    void cancelMarkExitAndConsumerReleaseAreIdempotentUnderConcurrency() throws Exception {
        for (int round = 0; round < 100; round++) {
            var p = plan(); var lease = reserve(p.bytes());
            var cancel = Thread.ofPlatform().start(lease::cancel);
            lease.markResult(p);
            var release = Thread.ofPlatform().start(p::releaseResultToCountedOwner);
            lease.actualExit(); cancel.join(); release.join();
            lease.cancel(); lease.actualExit(); lease.releaseToCountedOwner();
            assertEquals(baseline, RuinResults.bytes());
        }
    }
}
