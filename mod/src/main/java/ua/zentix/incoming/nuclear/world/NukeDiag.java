package ua.zentix.incoming.nuclear.world;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Post-wave zone diagnostics for laptop runs: only counts and logs “DIAG …”, no behavior changes.
 * Enabled by JVM property {@code -Dincoming.nukeDiag=true}; without it no lines or threads.
 * Measurement, not world state (as {@code WorkScheduler.ownNanos}).
 */
public final class NukeDiag {
    public static final boolean ON = Boolean.getBoolean("incoming.nukeDiag");

    /** Why chunk work in the ruin queue was deferred (attempt counts over report window). */
    enum Wait {
        BELOW_FULL("chunk itself below full load"),
        SERVER_PLAN("server-thread plan waits for the REACH window"),
        PLAN_R1("finished plan waits for neighbours r1"),
        PLAN_R2("finished plan waits for neighbours r2"),
        WINDOW("window being read from disk"),
        ADMIT("background pool full"),
        BACKGROUND("plan building in the background"),
        SUBMIT("snapshots not submitted"),
        AGAIN("another unit (fracture/stale)");

        final String text;

        Wait(String text) {
            this.text = text;
        }
    }

    private static final long[] WAITS = new long[Wait.values().length];
    private static final AtomicLong READS = new AtomicLong(), READ_NANOS = new AtomicLong(), READ_MAX = new AtomicLong();
    private static final AtomicLong READS_IN_FLIGHT = new AtomicLong();
    /** Tick longer than this, ms: log its stack traces. */
    private static final long LONG_TICK_MS = 250;
    private static volatile long tickStart;
    private static final List<StackTraceElement[]> samples = new ArrayList<>();
    private static volatile Thread sampler;

    private NukeDiag() {}

    static void waited(Wait w) {
        if (ON) WAITS[w.ordinal()]++;
    }

    /** Disk window read submitted: completion via {@link #readDone}. */
    static long readStart() {
        if (!ON) return 0;
        READS_IN_FLIGHT.incrementAndGet();
        return System.nanoTime();
    }

    static void readDone(long start) {
        if (!ON) return;
        long took = System.nanoTime() - start;
        READS_IN_FLIGHT.decrementAndGet();
        READS.incrementAndGet();
        READ_NANOS.addAndGet(took);
        READ_MAX.accumulateAndGet(took, Math::max);
    }

    /** Log ruin-queue waiting reasons and window reads over report window; reset counters. */
    static String takeWaits() {
        StringBuilder sb = new StringBuilder();
        for (Wait w : Wait.values()) {
            if (WAITS[w.ordinal()] == 0) continue;
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(w.text).append(' ').append(WAITS[w.ordinal()]);
        }
        java.util.Arrays.fill(WAITS, 0);
        long n = READS.getAndSet(0), sum = READ_NANOS.getAndSet(0), max = READ_MAX.getAndSet(0);
        return (sb.isEmpty() ? "none" : sb) + "; disk reads (windows and far rings) " + n + " (avg " + (n == 0 ? 0 : sum / n / 1_000_000) + " ms, longest "
                + max / 1_000_000 + " ms, in flight " + READS_IN_FLIGHT.get() + ", background pool tasks " + RuinWorkers.inFlight() + " of " + RuinWorkers.capacity() + ")";
    }

    /**
     * World chunk I/O queue: disk-pending writes ({@code IOWorker.pendingWrites} — unloaded chunks with NBT in memory),
     * mailbox tasks (reads ahead of writes); current heap. Fields read reflectively without synchronization — report
     * numbers, not decision inputs.
     */
    public static String io(net.minecraft.server.level.ServerLevel level) {
        String queue;
        try {
            Object worker = level.getChunkSource().chunkMap.chunkScanner();
            Class<?> type = net.minecraft.world.level.chunk.storage.IOWorker.class;
            java.lang.reflect.Field writes = type.getDeclaredField("pendingWrites"), mailbox = type.getDeclaredField("mailbox");
            writes.setAccessible(true);
            mailbox.setAccessible(true);
            queue = "writes pending " + ((Map<?, ?>) writes.get(worker)).size() + ", mailbox tasks "
                    + ((net.minecraft.util.thread.ProcessorMailbox<?>) mailbox.get(worker)).size();
        } catch (ReflectiveOperationException | RuntimeException e) {
            queue = "queue unreadable (" + e + ")";
        }
        Runtime rt = Runtime.getRuntime();
        return queue + "; mod reads in flight " + READS_IN_FLIGHT.get() + "; heap " + ((rt.totalMemory() - rt.freeMemory()) >> 20) + " of " + (rt.maxMemory() >> 20) + " MB";
    }

    // ---------------------------------------------------------------- slow tick: server thread stacks

    public static void onTickPre(ServerTickEvent.Pre e) {
        if (!ON) return;
        if (sampler == null) startSampler(e.getServer());
        tickStart = System.nanoTime();
    }

    public static void onTickPost(ServerTickEvent.Post e) {
        if (!ON) return;
        long start = tickStart;
        tickStart = 0;
        long ms = (System.nanoTime() - start) / 1_000_000;
        List<StackTraceElement[]> got;
        synchronized (samples) {
            got = new ArrayList<>(samples);
            samples.clear();
        }
        if (start == 0 || ms < LONG_TICK_MS) return;
        // group identical stack tops: sample count for each (sample every 20 ms after the tick's first 100 ms)
        Map<String, Integer> count = new HashMap<>();
        Map<String, StackTraceElement[]> full = new HashMap<>();
        for (StackTraceElement[] st : got) {
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < Math.min(12, st.length); i++) key.append(st[i]).append('|');
            count.merge(key.toString(), 1, Integer::sum);
            full.putIfAbsent(key.toString(), st);
        }
        StringBuilder sb = new StringBuilder();
        count.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3).forEach(en -> {
            sb.append("\n  samples ").append(en.getValue()).append(':');
            StackTraceElement[] st = full.get(en.getKey());
            for (int i = 0; i < Math.min(45, st.length); i++) sb.append("\n    at ").append(st[i]);
        });
        Incoming.LOG.info("DIAG long tick {} ms, stack samples {}:{}", ms, got.size(), sb);
    }

    private static synchronized void startSampler(MinecraftServer server) {
        if (sampler != null) return;
        Thread main = server.getRunningThread();
        sampler = new Thread(() -> {
            try {
                loop(server, main);
            } finally {
                sampler = null;
            }
        }, "incoming-nuke-diag");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static void loop(MinecraftServer server, Thread main) {
        while (server.isRunning()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                return;
            }
            long start = tickStart;
            if (start == 0 || System.nanoTime() - start < 100_000_000L) continue;
            StackTraceElement[] st = main.getStackTrace();
            synchronized (samples) {
                if (samples.size() < 500) samples.add(st);
            }
        }
    }
}
