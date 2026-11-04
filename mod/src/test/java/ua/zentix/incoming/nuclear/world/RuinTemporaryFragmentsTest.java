package ua.zentix.incoming.nuclear.world;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuinTemporaryFragmentsTest {
    private final long baseline = RuinResults.bytes();
    private final ArrayList<RuinResults.Lease> leases = new ArrayList<>();

    private RuinResults.Lease reserve(long plan, long fragments) {
        var lease = RuinResults.tryReserve(plan, fragments, plan + fragments + RuinResults.OWNER_BYTES);
        assertNotNull(lease); leases.add(lease); return lease;
    }

    private static RuinPlan plan() {
        return new RuinPlan(new int[0], new long[0], new BlockState[0], new BlockState[0], new int[0],
                new int[0], new long[0], null, null, null, null, null, new long[0], null);
    }

    @AfterEach
    void clean() {
        for (var lease : leases) { lease.cancel(); lease.actualExit(); }
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void admissionIncludesPlanTemporaryFragmentsAndMetadataWithoutOverflow() {
        assertNull(RuinResults.tryReserve(20, 30, 49 + RuinResults.OWNER_BYTES));
        assertNull(RuinResults.tryReserve(Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertNull(RuinResults.tryReserve(1, Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> RuinResults.tryReserve(0, -1, 100));
        var lease = reserve(20, 30);
        assertEquals(baseline + 50 + RuinResults.OWNER_BYTES, RuinResults.bytes());
        lease.abortBeforeSubmit(); lease.abortBeforeSubmit(); lease.actualExit();
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void resultShrinkRetainsAllTemporaryFragmentsUntilActualExit() {
        var plan = plan(); var lease = reserve(plan.bytes() + 1000, 5000);
        lease.markResult(plan); lease.bindFuture(CompletableFuture.completedFuture(plan));
        assertEquals(baseline + plan.bytes() + RuinResults.OWNER_BYTES + 5000, RuinResults.bytes());
        lease.actualExit(); lease.actualExit();
        assertEquals(baseline + plan.bytes() + RuinResults.OWNER_BYTES, RuinResults.bytes());
        plan.releaseResultToCountedOwner();
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void earlyCountedConsumerTransferCannotRefundTemporaryFragments() {
        var plan = plan(); var lease = reserve(plan.bytes(), 5000); lease.markResult(plan);
        plan.releaseResultToCountedOwner(); lease.releaseToCountedOwner(); lease.cancel();
        assertEquals(baseline + 5000, RuinResults.bytes());
        lease.actualExit(); lease.actualExit();
        assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void cancellationAndFailureRefundBothAllowancesOnlyAtExit() {
        for (boolean marked : new boolean[]{false, true}) {
            var plan = plan(); var lease = reserve(plan.bytes(), 5000);
            if (marked) lease.markResult(plan);
            lease.cancel();
            assertEquals(baseline + plan.bytes() + RuinResults.OWNER_BYTES + 5000, RuinResults.bytes());
            lease.actualExit(); assertEquals(baseline, RuinResults.bytes());
        }
        var failed = reserve(1000, 5000);
        failed.bindFuture(CompletableFuture.failedFuture(new IllegalStateException()));
        failed.actualExit(); assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void emptyResultAlsoRetainsTemporaryFragmentsUntilExit() {
        var lease = reserve(1000, 5000); lease.markResult(RuinPlan.EMPTY);
        assertEquals(baseline + RuinResults.OWNER_BYTES + 5000, RuinResults.bytes());
        lease.actualExit(); assertEquals(baseline, RuinResults.bytes());
    }

    @Test
    void temporaryAllowanceCannotHideAnOversizedPlan() {
        var plan = plan(); var lease = reserve(plan.bytes() - 1, 5000);
        assertThrows(IllegalStateException.class, () -> lease.markResult(plan));
        lease.actualExit(); assertEquals(baseline, RuinResults.bytes());
    }
}
