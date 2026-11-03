package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuinWorkersTest {
    @Test
    void expiredPoolThreadsDoNotPermanentlyBlockSettlementOnLargeCoreServers() {
        var live = new java.util.concurrent.atomic.AtomicInteger(46);
        var executor = new java.util.concurrent.ThreadPoolExecutor(46, 46, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>()) {
            @Override public int getPoolSize() { return live.get(); }
        };
        try {
            org.junit.jupiter.api.Assertions.assertTrue(RuinWorkers.residentBufferBytes(executor, 384) > NuclearPrep.PLANS_CAP / 2);
            live.set(0); // allowCoreThreadTimeOut releases idle threads and their ThreadLocal solver buffers
            assertEquals(46, executor.getCorePoolSize());
            assertEquals(0, RuinWorkers.residentBufferBytes(executor, 384));
            live.set(8);
            org.junit.jupiter.api.Assertions.assertTrue(RuinWorkers.residentBufferBytes(executor, 384) < NuclearPrep.PLANS_CAP / 2);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Ruins pool queue: first the tasks of the chunks that the player sees, then the rest near the wave, then the distant ones; inside the
     *  stage - according to setting. The count of level tasks is also removed from the task abandoned before the start.
     */
    @Test
    void queueGoesByPriorityThenByOrder() {
        List<String> ran = new ArrayList<>();
        PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>();
        String[][] tasks = {{"far 1", "FAR"}, {"near 1", "NEAR"}, {"far 2", "FAR"}, {"seen 1", "SEEN"}, {"near 2", "NEAR"}, {"seen 2", "SEEN"}};
        List<RuinWorkers.Task<Boolean>> made = new ArrayList<>();
        for (String[] t : tasks) {
            RuinWorkers.Task<Boolean> task = new RuinWorkers.Task<>(() -> ran.add(t[0]), RuinWorkers.Priority.valueOf(t[1]));
            made.add(task);
            queue.add(task);
        }
        assertEquals(2, RuinWorkers.inFlight(RuinWorkers.Priority.SEEN));
        assertEquals(4, RuinWorkers.inFlight(RuinWorkers.Priority.NEAR));
        assertEquals(6, RuinWorkers.inFlight());
        made.get(2).cancel(false);
        for (Runnable r; (r = queue.poll()) != null; ) r.run();
        assertEquals(List.of("seen 1", "seen 2", "near 1", "near 2", "far 1"), ran);
        assertEquals(0, RuinWorkers.inFlight());
    }

    @Test
    void cancelledQueuedTaskImmediatelyReleasesQueueAndCount() throws Exception {
        var counts = new java.util.concurrent.atomic.AtomicIntegerArray(3);
        var executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 1, java.util.concurrent.TimeUnit.SECONDS,
                new PriorityBlockingQueue<>());
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            org.junit.jupiter.api.Assertions.assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var queued = new RuinWorkers.Task<>(() -> { throw new AssertionError("cancelled task ran"); },
                    RuinWorkers.Priority.FAR, counts, executor);
            executor.execute(queued);
            assertEquals(1, counts.get(2));
            queued.cancel(false);
            org.junit.jupiter.api.Assertions.assertTrue(executor.getQueue().isEmpty());
            assertEquals(0, counts.get(2));
            // An executor/cancellation race may still call run; accounting must be exactly once.
            queued.run();
            assertEquals(0, counts.get(2));
        } finally {
            release.countDown();
            executor.shutdownNow();
            org.junit.jupiter.api.Assertions.assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelledRunningTaskKeepsReservationUntilWorkerActuallyExits() throws Exception {
        var counts = new java.util.concurrent.atomic.AtomicIntegerArray(3);
        var executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 1, java.util.concurrent.TimeUnit.SECONDS,
                new PriorityBlockingQueue<>());
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var running = new RuinWorkers.Task<>(() -> {
                started.countDown();
                release.await();
                return true;
            }, RuinWorkers.Priority.SEEN, counts, executor);
            executor.execute(running);
            org.junit.jupiter.api.Assertions.assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            running.cancel(false);
            org.junit.jupiter.api.Assertions.assertTrue(running.isDone());
            assertEquals(1, counts.get(0), "Future completion is not worker completion");
            release.countDown();
            executor.shutdown();
            org.junit.jupiter.api.Assertions.assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, counts.get(0));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
