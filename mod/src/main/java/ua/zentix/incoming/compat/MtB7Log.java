package ua.zentix.incoming.compat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** TEST ONLY. Fixed queue, row and file limits; producers never wait for disk. */
public class MtB7Log implements AutoCloseable {
    public static final Path ROOT = Path.of("logs", "mt-b7", "run-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID());
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(512);
    public final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private volatile boolean closing, failed;
    private final long limit;

    public MtB7Log(Path file, String header) throws IOException { this(file, header, 64L << 20); }
    public MtB7Log(Path file, String header, long limit) throws IOException {
        this.limit = limit;
        Files.createDirectories(file.getParent());
        BufferedWriter output = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW);
        output.write(header); output.newLine(); output.flush();
        writer = new Thread(() -> write(output, header.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L), "MT-B7 log " + file.getFileName());
        writer.setDaemon(true); writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "MT-B7 log close"));
    }
    public void add(String row) {
        // String length bounds retained queue ownership before encoding on the writer.
        if (row.length() > 8192 || closing || failed || !queue.offer(row)) dropped.incrementAndGet();
    }
    private void write(BufferedWriter output, long bytes) {
        long flushAt = 0;
        try (output) {
            while (!closing || !queue.isEmpty()) {
                String row = queue.poll(200, TimeUnit.MILLISECONDS);
                if (row != null) {
                    int size = row.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                    if (bytes + size <= limit) {
                        output.write(row); output.newLine(); bytes += size;
                    } else dropped.incrementAndGet();
                }
                if (System.nanoTime() >= flushAt) {
                    output.flush(); flushAt = System.nanoTime() + 1_000_000_000L;
                }
            }
            output.write("# dropped=" + dropped.get()); output.newLine();
        } catch (IOException | InterruptedException error) {
            failed = true; dropped.addAndGet(queue.size() + 1L); queue.clear();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    @Override public void close() {
        closing = true;
        try { writer.join(3000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
