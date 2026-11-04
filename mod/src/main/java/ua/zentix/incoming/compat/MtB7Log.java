package ua.zentix.incoming.compat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** TEST ONLY. Fixed queue, row and file limits; producers never wait for disk. */
public class MtB7Log implements AutoCloseable {
    public static final Path ROOT = Path.of("logs", "mt-b7", "run-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID());
    private record Producer(ExecutorService executor, Runnable report) {}
    private static final List<MtB7Log> LOGS = new CopyOnWriteArrayList<>();
    private static final List<Producer> PRODUCERS = new CopyOnWriteArrayList<>();
    private static volatile boolean stopping, closingLogs;
    private static volatile java.util.function.BooleanSupplier completion = () -> true;
    private static volatile Runnable finalReport = () -> {};
    private static final Observations OBSERVATIONS = new Observations();
    /** Counter only: never retain a callback, wait, or hold this monitor during hashing. */
    public static final class Observations {
        private int active;
        private boolean stopped;
        public synchronized boolean begin() { if (stopped) return false; active++; return true; }
        public synchronized void end() { if (--active < 0) throw new IllegalStateException("MT-B7 observation underflow"); }
        public synchronized boolean stop() { stopped = true; return active == 0; }
    }
    public static boolean beginObservation() { return OBSERVATIONS.begin(); }
    public static void endObservation() { OBSERVATIONS.end(); }
    public static void finalReport(Runnable report) { finalReport = report; }
    public static void completeness(java.util.function.BooleanSupplier check) { completion = check; }
    static { Runtime.getRuntime().addShutdownHook(new Thread(MtB7Log::shutdown, "MT-B7 ordered shutdown")); }
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(512);
    public final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private volatile boolean closing, failed, complete = true;
    private final long limit;

    public static boolean stopping() { return stopping; }
    /** At most the hash and PNG executors; admission checks stopping() before execute. */
    public static void producer(ExecutorService executor, Runnable report) {
        synchronized (PRODUCERS) {
            if (stopping || PRODUCERS.size() >= 2) throw new IllegalStateException("MT-B7 producer lifetime closed/full");
            PRODUCERS.add(new Producer(executor, report));
        }
    }
    private static void shutdown() {
        stopping = true;
        boolean complete = OBSERVATIONS.stop();
        for (Producer producer : PRODUCERS) {
            complete &= drain(producer.executor, 3000);
            producer.report.run();
        }
        complete &= completion.getAsBoolean();
        finalReport.run();
        synchronized (LOGS) { closingLogs = true; }
        for (MtB7Log log : LOGS) log.close(complete);
    }
    static boolean drain(ExecutorService executor, long millis) {
        synchronized (executor) { executor.shutdown(); }
        try { return executor.awaitTermination(millis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
    }
    public MtB7Log(Path file, String header) throws IOException { this(file, header, 64L << 20); }
    public MtB7Log(Path file, String header, long limit) throws IOException {
        this.limit = limit;
        synchronized (LOGS) {
            if (closingLogs || LOGS.size() >= 8) throw new IOException("MT-B7 log lifetime closed/full");
            Files.createDirectories(file.getParent());
            BufferedWriter output = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW);
            output.write(header); output.newLine(); output.flush();
            writer = new Thread(() -> write(output, header.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L), "MT-B7 log " + file.getFileName());
            writer.setDaemon(true); LOGS.add(this); writer.start();
        }
    }
    public void add(String row) {
        if (row.length() > 8192 || closing || failed || !queue.offer(row)) dropped.incrementAndGet();
    }
    private void write(BufferedWriter output, long bytes) {
        long flushAt = 0;
        try (output) {
            while (!closing || !queue.isEmpty()) {
                String row = queue.poll(200, TimeUnit.MILLISECONDS);
                if (row != null) {
                    int size = row.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                    if (bytes + size <= limit) { output.write(row); output.newLine(); bytes += size; }
                    else dropped.incrementAndGet();
                }
                if (System.nanoTime() >= flushAt) { output.flush(); flushAt = System.nanoTime() + 1_000_000_000L; }
            }
            output.write("# dropped=" + dropped.get() + " producer_shutdown=" + (complete ? "complete" : "incomplete")); output.newLine();
        } catch (IOException | InterruptedException error) {
            failed = true; dropped.addAndGet(queue.size() + 1L); queue.clear();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    @Override public void close() { close(true); }
    void close(boolean producersComplete) {
        complete &= producersComplete;
        closing = true;
        try { writer.join(3000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (!writer.isAlive()) LOGS.remove(this);
        // Missing final footer means incomplete drain, including a writer blocked longer than the join.
    }
}
