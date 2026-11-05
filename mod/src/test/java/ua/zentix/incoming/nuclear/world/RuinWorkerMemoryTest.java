package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuinWorkerMemoryTest {
    private static final int HEIGHT = 384;
    private static final long WINDOW_CAP = NuclearPrep.PLANS_CAP - (32L << 20);

    @Test
    void highConfiguredAndAutomaticUpperBoundsLeavePreparationRoomBeforePoolInitialization() {
        // Automatic selection on a 48-logical-core dedicated server, and the maximum valid manual setting.
        for (int desired : new int[]{48 - RuinWorkers.reserve(false), 256}) {
            assertEquals(17, RuinWorkers.memoryThreads(desired, HEIGHT));
            long bytes = RuinWorkers.bufferBytes(null, desired, HEIGHT);
            assertEquals(17L * 48 * 48 * HEIGHT * 15, bytes);
            assertTrue(bytes <= RuinWorkers.BUFFER_CAP);
            assertTrue(bytes + RuinMemory.TRANSITION_ROOM + (32L << 20) < WINDOW_CAP,
                    "The same reservation must leave input/result room before the first disk parser starts");
        }
        assertEquals(1, RuinWorkers.memoryThreads(1, HEIGHT));
        assertEquals(4, RuinWorkers.memoryThreads(4, HEIGHT));
    }

    @Test
    void actualHighCountPoolAndItsIdleExpiryKeepFutureAdmissionWithinWindow() throws Exception {
        int desired = 256;
        int admitted = RuinWorkers.memoryThreads(desired, HEIGHT);
        ThreadPoolExecutor executor = RuinWorkers.newPool(admitted);
        CountDownLatch started = new CountDownLatch(admitted);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < admitted; i++) executor.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(admitted, executor.getPoolSize());
            assertEquals(admitted, executor.getMaximumPoolSize());
            long reserved = RuinWorkers.bufferBytes(executor, desired, HEIGHT);
            assertEquals(reserved, RuinWorkers.residentBufferBytes(executor, HEIGHT));
            assertTrue(reserved + RuinMemory.TRANSITION_ROOM < WINDOW_CAP);
            // Use the production timeout policy with a shorter duration; no synthetic live-thread counter.
            executor.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
            release.countDown();
            awaitIdleExpiry(executor);
            assertEquals(0, RuinWorkers.residentBufferBytes(executor, HEIGHT));
            assertEquals(reserved, RuinWorkers.bufferBytes(executor, desired, HEIGHT));
            assertTrue(reserved + RuinMemory.TRANSITION_ROOM < WINDOW_CAP);
            // An expired pool can actually restart, still at its admitted parallelism.
            executor.execute(() -> {});
            assertTrue(executor.getPoolSize() <= admitted);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void downsizeKeepsRunningThreadBuffersChargedUntilActualExit() throws Exception {
        int admitted = RuinWorkers.memoryThreads(256, HEIGHT);
        ThreadPoolExecutor executor = RuinWorkers.newPool(admitted);
        CountDownLatch started = new CountDownLatch(admitted);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < admitted; i++) executor.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            RuinWorkers.resize(executor, 2);
            long oldBuffers = admitted * RuinWorkers.threadBufferBytes(HEIGHT);
            assertEquals(oldBuffers, RuinWorkers.bufferBytes(executor, 2, HEIGHT));
            assertEquals(oldBuffers, RuinWorkers.residentBufferBytes(executor, HEIGHT));
            executor.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
            release.countDown();
            awaitIdleExpiry(executor);
            assertEquals(2 * RuinWorkers.threadBufferBytes(HEIGHT), RuinWorkers.bufferBytes(executor, 2, HEIGHT));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void tallerWorldsReduceActualParallelismWithoutReducingThePerCellCharge() {
        for (int height : new int[]{256, 384, 1024, 4064}) {
            int count = RuinWorkers.memoryThreads(256, height);
            long bytes = RuinWorkers.bufferBytes(null, 256, height);
            assertEquals((long) count * 48 * 48 * height * 15, bytes);
            assertTrue(bytes <= RuinWorkers.BUFFER_CAP);
            assertTrue(bytes + RuinMemory.TRANSITION_ROOM < WINDOW_CAP);
        }
        assertEquals(6, RuinWorkers.memoryThreads(256, 1024));
    }

    private static void awaitIdleExpiry(ThreadPoolExecutor executor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.getPoolSize() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(0, executor.getPoolSize(), "Idle threads must release their ThreadLocal buffers");
    }
}
