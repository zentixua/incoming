package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.*;

/** Actual production executor/factory, with numeric non-worker ownership supplied by the caller. */
class RuinWorkerAdmissionTest {
    private static final int HEIGHT = 384;
    private static final long THREAD = RuinWorkers.threadBufferBytes(HEIGHT);
    private static final long CAP = NuclearPrep.PLANS_CAP;

    @Test
    void expiredPoolAndCapturedCallbacksRebirthOnlyWithinCurrentSharedRoom() throws Exception {
        var pool = new RuinWorkers.OwnedPool(10, HEIGHT);
        var counts = new AtomicIntegerArray(3);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(8);
        var tasks = new ArrayList<Future<?>>();
        try {
            // Captured callbacks cannot traverse worlds or create a worker from a stale headroom sample.
            Thread callback = new Thread(() -> {
                for (int i = 0; i < 10; i++) tasks.add(submit(pool, counts, () -> {
                    started.countDown();
                    release.await();
                    return null;
                }, i == 9 ? RuinWorkers.Priority.SEEN : RuinWorkers.Priority.FAR));
            });
            callback.start();
            callback.join();
            assertEquals(0, pool.getPoolSize());
            assertEquals(10, counts.get(0) + counts.get(2));
            long base = 417_653_525L;
            assertTrue(base + 10 * THREAD + RuinMemory.TRANSITION_ROOM > CAP, "far-read policy must refuse");
            pump(pool, base, 10);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(8, pool.getPoolSize());
            assertEquals(8 * THREAD, resident());
            assertTrue(base + resident() <= CAP);
            assertFalse(tasks.get(9).isDone(), "SEEN work is alive behind its controlled latch");
            tasks.get(9).cancel(false);
            assertEquals(8 * THREAD, resident(), "cancellation cannot lend a still-live thread's buffers");
            pool.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
            release.countDown();
            for (Future<?> task : tasks) if (!task.isCancelled()) task.get(5, TimeUnit.SECONDS);
            awaitExpiry(pool);
            assertEquals(0, resident());
            assertEquals(0, counts.get(0) + counts.get(2));

            var reborn = new CountDownLatch(1);
            var nextRelease = new CountDownLatch(1);
            Future<?> next = submit(pool, counts, () -> { reborn.countDown(); nextRelease.await(); return null; }, RuinWorkers.Priority.SEEN);
            try {
                assertEquals(0, pool.getPoolSize());
                pump(pool, CAP - THREAD, 10);
                assertTrue(reborn.await(5, TimeUnit.SECONDS));
                assertEquals(THREAD, resident());
                pump(pool, CAP - THREAD, 10);
                assertEquals(THREAD, resident(), "repeated pumping must not lend workspace twice");
                nextRelease.countDown();
                next.get(5, TimeUnit.SECONDS);
            } finally { nextRelease.countDown(); }
        } finally {
            release.countDown();
            close(pool);
        }
        assertEquals(0, resident());
    }

    @Test
    void oneToGrowthAndUnpressuredControlUseTheSameFactory() throws Exception {
        var pool = new RuinWorkers.OwnedPool(10, HEIGHT);
        var counts = new AtomicIntegerArray(3);
        var release = new CountDownLatch(1);
        var all = new CountDownLatch(10);
        try {
            for (int i = 0; i < 10; i++) submit(pool, counts, () -> { all.countDown(); release.await(); return null; }, RuinWorkers.Priority.NEAR);
            pump(pool, CAP - THREAD, 1);
            awaitThreads(pool, 1);
            pump(pool, 417_653_525L, 10);
            awaitThreads(pool, 8);
            assertEquals(8 * THREAD, resident());
            assertTrue(417_653_525L + resident() <= CAP);
            pump(pool, 0, 10);
            assertTrue(all.await(5, TimeUnit.SECONDS));
            assertEquals(10 * THREAD, resident(), "unpressured control must retain configured parallelism");
        } finally { release.countDown(); close(pool); }
        assertEquals(0, resident());
    }

    @Test
    void tallerDimensionWaitsForDeltaInsteadOfRetroactivelyOverchargingLiveThreads() throws Exception {
        var pool = new RuinWorkers.OwnedPool(1, HEIGHT);
        var counts = new AtomicIntegerArray(3);
        var release = new CountDownLatch(1);
        var first = new CountDownLatch(1);
        try {
            Future<?> old = submit(pool, counts, () -> { first.countDown(); release.await(); return null; }, RuinWorkers.Priority.SEEN);
            pump(pool, 0, 1);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            pool.observeHeight(1024);
            long grown = RuinWorkers.threadBufferBytes(1024);
            long base = CAP - grown + 1;
            Future<?> next = submit(pool, counts, () -> 42, RuinWorkers.Priority.SEEN);
            pump(pool, base, 1);
            assertEquals(THREAD, resident());
            release.countDown();
            old.get(5, TimeUnit.SECONDS);
            assertFalse(RuinWorkers.started(next), "a taller task must not enter its callable without the workspace delta");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!next.isDone() && System.nanoTime() < until) {
                pump(pool, base - 1, 1);
                Thread.sleep(1); // wait for the actual beforeExecute ownership transition
            }
            assertEquals(42, next.get(5, TimeUnit.SECONDS));
            assertEquals(grown, resident());
            assertEquals(CAP, base - 1 + resident());
        } finally { release.countDown(); close(pool); }
        assertEquals(0, resident());
    }

    @Test
    void shutdownSurvivorKeepsWorkspaceWhileANewLifetimeWaits() throws Exception {
        var oldPool = new RuinWorkers.OwnedPool(1, HEIGHT);
        var nextPool = new RuinWorkers.OwnedPool(1, HEIGHT);
        var counts = new AtomicIntegerArray(3);
        var release = new CountDownLatch(1);
        var first = new CountDownLatch(1);
        try {
            Future<?> running = submit(oldPool, counts, () -> {
                first.countDown();
                for (;;) try { release.await(); break; } catch (InterruptedException ignored) { /* actual exit is controlled */ }
                return null;
            }, RuinWorkers.Priority.SEEN);
            pump(oldPool, 0, 1);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            running.cancel(true);
            oldPool.shutdownNow();
            assertEquals(THREAD, resident());
            assertFalse(RuinWorkers.finished(running));
            Future<?> next = submit(nextPool, new AtomicIntegerArray(3), () -> true, RuinWorkers.Priority.SEEN);
            pump(nextPool, CAP - THREAD, 1);
            assertEquals(0, nextPool.getPoolSize());
            release.countDown();
            assertTrue(oldPool.awaitTermination(5, TimeUnit.SECONDS));
            awaitExpiry(oldPool);
            pump(nextPool, CAP - THREAD, 1);
            assertEquals(true, next.get(5, TimeUnit.SECONDS));
            assertTrue(RuinWorkers.finished(running));
        } finally { release.countDown(); close(oldPool); close(nextPool); }
        assertEquals(0, resident());
    }

    @Test
    void cancellationAndShutdownWakeHeightWaitersWithoutLendingTheirLiveBuffers() throws Exception {
        var pool = new RuinWorkers.OwnedPool(2, HEIGHT);
        var counts = new AtomicIntegerArray(3);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(2);
        try {
            var first = submit(pool, counts, () -> { entered.countDown(); release.await(); return null; }, RuinWorkers.Priority.SEEN);
            var second = submit(pool, counts, () -> { entered.countDown(); release.await(); return null; }, RuinWorkers.Priority.SEEN);
            pump(pool, 0, 2);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.observeHeight(1024);
            var cancelled = submit(pool, counts, () -> { throw new AssertionError("cancelled taller task ran"); }, RuinWorkers.Priority.SEEN);
            var stopped = submit(pool, counts, () -> { throw new AssertionError("unfunded taller task ran"); }, RuinWorkers.Priority.SEEN);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!pool.getQueue().isEmpty() && System.nanoTime() < until) Thread.sleep(1);
            assertTrue(pool.getQueue().isEmpty(), "both taller tasks must reach the actual execution gate");
            cancelled.cancel(false);
            while (!RuinWorkers.finished(cancelled) && System.nanoTime() < until) Thread.sleep(1);
            assertTrue(RuinWorkers.finished(cancelled));
            assertEquals(2 * THREAD, resident(), "cancelled task does not release a resident thread");
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            awaitExpiry(pool);
            assertTrue(stopped.isCancelled());
            assertTrue(RuinWorkers.finished(stopped));
            assertEquals(0, counts.get(0));
        } finally { release.countDown(); close(pool); }
        assertEquals(0, resident());
    }

    @Test
    void factoryResultDiscardedByShutdownReleasesItsUnstartedWorkspace() throws Exception {
        var pool = new RuinWorkers.OwnedPool(1, HEIGHT);
        try {
            var factory = pool.getThreadFactory();
            pool.setThreadFactory(run -> {
                Thread thread = factory.newThread(run);
                if (thread != null) pool.shutdownNow(); // STOP wins after factory grant, before start
                return thread;
            });
            submit(pool, new AtomicIntegerArray(3), () -> null, RuinWorkers.Priority.SEEN);
            pump(pool, 0, 1);
            assertEquals(0, pool.getPoolSize());
            assertEquals(0, resident(), "a never-started thread cannot retain workspace forever");
        } finally { close(pool); }
    }

    @Test
    void stopBetweenDequeueAndHeightGateCannotEnterAnUnfundedCallable() throws Exception {
        var pool = new RuinWorkers.OwnedPool(1, HEIGHT);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        class First implements Runnable, Comparable<Object> {
            @Override public int compareTo(Object ignored) { return 0; }
            @Override public void run() {
                entered.countDown();
                try { release.await(); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            }
        }
        try {
            pool.execute(new First());
            pump(pool, 0, 1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.observeHeight(1024);
            var next = submit(pool, new AtomicIntegerArray(3), () -> {
                throw new AssertionError("stopped height gate entered an unfunded callable");
            }, RuinWorkers.Priority.SEEN);
            var workspace = pool.getClass().getDeclaredField("workspace").get(pool);
            var field = workspace.getClass().getDeclaredField("credits");
            field.setAccessible(true);
            Object credit = ((java.util.Queue<?>) field.get(workspace)).element();
            synchronized (credit) {
                release.countDown();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!pool.getQueue().isEmpty() && System.nanoTime() < until) Thread.sleep(1);
                assertTrue(pool.getQueue().isEmpty(), "task must be dequeued before STOP");
                pool.shutdownNow(); // gate cannot acquire its monitor until STOP has become visible
            }
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(next.isCancelled());
            assertTrue(RuinWorkers.finished(next));
        } finally { release.countDown(); close(pool); }
        assertEquals(0, resident());
    }

    @Test
    void failedExitCallbackStillTerminatesCountExactlyOnce() {
        var counts = new AtomicIntegerArray(3);
        var exits = new java.util.concurrent.atomic.AtomicInteger();
        var task = new RuinWorkers.Task<>(() -> 42, RuinWorkers.Priority.SEEN, counts, null, () -> {
            exits.incrementAndGet();
            throw new IllegalStateException("controlled ownership callback failure");
        });
        assertThrows(IllegalStateException.class, task::run);
        assertTrue(RuinWorkers.finished(task));
        assertEquals(0, counts.get(0));
        task.run();
        assertEquals(1, exits.get());
        assertEquals(0, counts.get(0));
    }

    private static <T> Future<T> submit(RuinWorkers.OwnedPool pool, AtomicIntegerArray counts,
                                       java.util.concurrent.Callable<T> body, RuinWorkers.Priority priority) {
        var task = new RuinWorkers.Task<>(body, priority, counts, pool);
        pool.execute(task);
        return task;
    }
    private static void pump(RuinWorkers.OwnedPool pool, long base, int target) {
        RuinWorkers.service(pool, CAP - base - resident(), target);
        assertTrue(base + resident() <= CAP);
    }
    private static long resident() { return RuinWorkers.residentBufferBytes(HEIGHT); }
    private static void awaitThreads(RuinWorkers.OwnedPool pool, int threads) {
        assertEquals(threads, pool.getPoolSize()); // factory registers synchronously before prestart returns
    }
    private static void awaitExpiry(RuinWorkers.OwnedPool pool) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((pool.getPoolSize() != 0 || RuinWorkers.residentBufferBytes(pool, HEIGHT) != 0) && System.nanoTime() < end) Thread.sleep(1);
        assertEquals(0, pool.getPoolSize());
        assertEquals(0, RuinWorkers.residentBufferBytes(pool, HEIGHT));
    }
    private static void close(RuinWorkers.OwnedPool pool) throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        awaitExpiry(pool);
    }
}
