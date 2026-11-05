package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import static org.junit.jupiter.api.Assertions.*;

class RuinInputsTest {
    @Test
    void lastActualExitRefundsDetachedInputsOnce() throws Exception {
        long before = RuinInputs.detachedBytes();
        var inputs = new RuinInputs();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new PriorityBlockingQueue<>());
        inputs.acquire();
        var running = new RuinWorkers.Task<>(() -> {
            started.countDown();
            release.await();
            return true;
        }, RuinWorkers.Priority.NEAR, new AtomicIntegerArray(3), executor, inputs::release);
        try {
            executor.execute(running);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            inputs.retire(70);
            assertEquals(170, inputs.retained(100));
            inputs.detach(100);
            inputs.detach(999);
            running.cancel(false);
            assertTrue(running.isDone());
            assertFalse(RuinWorkers.finished(running));
            assertEquals(before + 170, RuinInputs.detachedBytes());
            assertEquals(0, inputs.retained(100));
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(RuinWorkers.finished(running));
            assertEquals(before, RuinInputs.detachedBytes());
            running.run();
            assertEquals(before, RuinInputs.detachedBytes());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void queuedCancellationRefundsWithoutExecution() throws Exception {
        long before = RuinInputs.detachedBytes();
        var inputs = new RuinInputs();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new PriorityBlockingQueue<>());
        try {
            executor.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            inputs.acquire();
            var queued = new RuinWorkers.Task<>(() -> { throw new AssertionError("cancelled callable ran"); },
                    RuinWorkers.Priority.FAR, new AtomicIntegerArray(3), executor, inputs::release);
            executor.execute(queued);
            inputs.detach(123);
            assertEquals(before + 123, RuinInputs.detachedBytes());
            queued.cancel(false);
            assertTrue(RuinWorkers.finished(queued));
            assertEquals(before, RuinInputs.detachedBytes());
            queued.run();
            assertEquals(before, RuinInputs.detachedBytes());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void attachedRetirementWaitsForEveryWorkerAndDoesNotLeakIntoAnotherOwner() {
        var inputs = new RuinInputs();
        inputs.acquire();
        inputs.acquire();
        inputs.retire(100);
        inputs.release();
        assertEquals(150, inputs.retained(50));
        inputs.retire(80);
        inputs.release();
        assertEquals(50, inputs.retained(50));
        inputs.acquire();
        assertEquals(50, inputs.retained(50));
        inputs.release();
    }
}
