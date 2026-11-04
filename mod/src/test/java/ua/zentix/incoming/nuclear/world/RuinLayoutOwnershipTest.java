package ua.zentix.incoming.nuclear.world;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.*;

class RuinLayoutOwnershipTest {
    private static Detonation geometry(double yield) {
        return new Detonation(-1, new Vec3(3104.5, 63, 560.5), 63, yield,
                true, 0, 0, 0, 20000, 0, 1, false, .25f);
    }

    @Test
    void queuedCancellationReleasesWithoutConstructing() {
        var bytes = new AtomicLong();
        var calls = new AtomicInteger();
        var d = geometry(1000);
        var task = new NuclearPrep.LayoutBuild(d, g -> {
            calls.incrementAndGet();
            return NuclearPrep.layout(g);
        }, bytes::addAndGet, NuclearPrep.layoutReservation(d));
        assertTrue(bytes.get() > 0);
        task.cancel(false);
        task.run();
        task.cancel(false);
        assertEquals(0, calls.get());
        assertEquals(0, bytes.get());
    }

    @Test
    void cancelledActualBuilderKeepsWorkspaceUntilExecutionReturns() throws Exception {
        var bytes = new AtomicLong();
        var built = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var d = geometry(1000);
        long reservation = NuclearPrep.layoutReservation(d);
        var task = new NuclearPrep.LayoutBuild(d, g -> {
            var result = NuclearPrep.layout(g);
            built.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return result;
        }, bytes::addAndGet, reservation);
        Thread worker = Thread.ofPlatform().unstarted(task);
        try {
            worker.start();
            assertTrue(built.await(10, TimeUnit.SECONDS));
            task.cancel(false);
            assertTrue(task.isDone());
            assertEquals(reservation, bytes.get(), "Future completion is not actual builder exit");
            task.cancel(false);
            assertEquals(reservation, bytes.get());
        } finally {
            release.countDown();
            worker.join(10_000);
        }
        assertFalse(worker.isAlive());
        assertEquals(0, bytes.get());
        assertThrows(java.util.concurrent.CancellationException.class, () -> task.getNow(null));
    }

    @Test
    void completedResultRetainsBackingUntilTransferOrDiscard() {
        for (boolean transfer : new boolean[] {false, true}) {
            var bytes = new AtomicLong();
            var d = geometry(100);
            var task = new NuclearPrep.LayoutBuild(d, NuclearPrep::layout, bytes::addAndGet,
                    NuclearPrep.layoutReservation(d));
            task.run();
            var layout = task.getNow(null);
            assertNotNull(layout);
            assertEquals(layout.bytes(), bytes.get());
            assertTrue(bytes.get() > 8L * (layout.order().length + layout.edge().length + layout.toScan().size()),
                    "reverse index, tile objects and backing slack were omitted");
            if (transfer) {
                task.transferred();
                assertNull(task.getNow(null), "transferred future retained the result");
            } else task.cancel(false);
            assertEquals(0, bytes.get());
        }
    }

    @Test
    void supportedHighYieldsFitOneWorkspaceAndRetainEntireZone() {
        for (double yield : new double[] {100, 1000}) {
            var d = geometry(yield);
            var layout = NuclearPrep.layout(d);
            long reservation = NuclearPrep.layoutReservation(d);
            assertTrue(reservation > layout.bytes());
            assertTrue(reservation + RuinWorkers.threadBufferBytes(384) * RuinWorkers.memoryThreads(256, 384)
                    + RuinMemory.TRANSITION_ROOM < NuclearPrep.PLANS_CAP - (32L << 20),
                    "honest layout reservation permanently closed 1-Mt admission");
            assertEquals(yield == 100 ? 58486 : 268082, layout.order().length);
            assertEquals(yield == 100 ? 63086 : 277736, layout.toScan().size());
        }
    }
}
