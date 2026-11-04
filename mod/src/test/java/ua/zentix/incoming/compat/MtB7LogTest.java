package ua.zentix.incoming.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** TEST ONLY: telemetry must discard excess evidence rather than grow memory/disk or overwrite a run. */
class MtB7LogTest {
    @TempDir Path directory;

    @Test void overflowIsAccountedAndUtf8DiskOutputIsBounded() throws Exception {
        Path path = directory.resolve("bounded.csv");
        var log = new MtB7Log(path, "header", 256);
        for (int i = 0; i < 10000; i++) log.add("measurement,é," + i);
        log.close();
        var rows = Files.readAllLines(path);
        long saved = rows.stream().filter(row -> row.startsWith("measurement,")).count();
        assertEquals(10000, saved + log.dropped.get());
        assertTrue(log.dropped.get() > 0);
        assertTrue(Files.size(path) <= 256 + 64, "fixed final drop footer is the only allowance");
        assertEquals("# dropped=" + log.dropped.get() + " producer_shutdown=complete", rows.getLast());
    }

    @Test void oversizeAndClosedRowsAreDroppedAndExistingEvidenceCannotBeOverwritten() throws Exception {
        Path path = directory.resolve("existing.csv");
        var log = new MtB7Log(path, "header");
        log.add("x".repeat(8193));
        log.add("kept");
        log.close();
        log.add("late");
        assertEquals(2, log.dropped.get());
        assertTrue(Files.readString(path).contains("kept\n"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> new MtB7Log(path, "replacement"));
        assertTrue(Files.readString(path).startsWith("header\n"));
    }
    @Test void blockedProducerIsExplicitlyIncompleteAndDrainedProducerKeepsItsFinalRow() throws Exception {
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var gate = new java.util.concurrent.CountDownLatch(1);
        var began = new java.util.concurrent.CountDownLatch(1);
        var log = new MtB7Log(directory.resolve("incomplete.csv"), "header");
        try {
            pool.execute(() -> { began.countDown(); try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } log.add("late result"); });
            assertTrue(began.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(MtB7Log.drain(pool, 5));
            log.close(false);
            assertTrue(Files.readString(directory.resolve("incomplete.csv")).contains("producer_shutdown=incomplete"));
        } finally { gate.countDown(); assertTrue(pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)); log.close(false); }
        assertEquals(1, log.dropped.get(), "late producer cannot rewrite a sealed incomplete footer");

        var finished = java.util.concurrent.Executors.newSingleThreadExecutor();
        var complete = new MtB7Log(directory.resolve("complete.csv"), "header");
        finished.execute(() -> complete.add("final result"));
        assertTrue(MtB7Log.drain(finished, 1000));
        complete.close(true);
        assertTrue(Files.readString(directory.resolve("complete.csv")).contains("final result\n# dropped=0 producer_shutdown=complete"));
    }
    @Test void directClientCallbackCannotOutliveACompleteFooterEvenWithoutServerMetrics() throws Exception {
        var observations = new MtB7Log.Observations();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var began = new java.util.concurrent.CountDownLatch(1);
        var gate = new java.util.concurrent.CountDownLatch(1);
        var log = new MtB7Log(directory.resolve("client-callback.csv"), "header");
        try {
            pool.execute(() -> {
                if (!observations.begin()) return;
                began.countDown();
                try { gate.await(); log.add("client hash result"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                finally { observations.end(); }
            });
            assertTrue(began.await(1, java.util.concurrent.TimeUnit.SECONDS));
            boolean complete = observations.stop();
            assertFalse(complete);
            assertFalse(observations.begin(), "shutdown closes new callback admission atomically");
            log.close(complete);
            assertTrue(Files.readString(directory.resolve("client-callback.csv")).contains("producer_shutdown=incomplete"));
        } finally { gate.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)); log.close(false); }
        assertEquals(1, log.dropped.get());

        var drained = new MtB7Log.Observations();
        var clean = new MtB7Log(directory.resolve("client-drained.csv"), "header");
        assertTrue(drained.begin()); clean.add("last accepted client row"); drained.end();
        assertTrue(drained.stop()); clean.close(true);
        assertTrue(Files.readString(directory.resolve("client-drained.csv")).contains("last accepted client row\n# dropped=0 producer_shutdown=complete"));
    }

}
