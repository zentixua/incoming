package ua.zentix.incoming.work;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-thread stalls longer than {@link #STALL_MS}, inside or between ticks (chunk tasks, player packets): once it
 * resumes, WARN “Server thread stalled for N s” with the most frequent sampled stack locations. Vanilla logs only
 * accumulated lag (“Can't keep up”), and a stack only when crashing after 60 s; “Mod work over 30 s” sees only ticks:
 * without this measurement, logs cannot reveal between-tick stalls (sometimes ~14 s, disconnecting players).
 * <p>
 * Measurement only, no behavior changes. Observer thread wakes every {@link #SAMPLE_MS}, compares tick mark, and samples
 * stacks only during a stall. Singleplayer pause (menu) is not a stall. Measurement, not world state (as
 * {@code WorkScheduler.ownNanos}), hence static.
 */
public final class StallWatch {
    static final long STALL_MS = 2000;
    private static final long SAMPLE_MS = 500;
    private static final int MAX_SAMPLES = 120;
    /** When server thread last passed tick start or end, {@code System.nanoTime}. */
    private static volatile long progress;
    /** Last mark interval exceeding {@link #STALL_MS}, ns: stall duration for log (0 — consumed). */
    private static volatile long lastGap;
    private static volatile boolean inTick;
    private static volatile Thread watcher;
    private static volatile int reported;

    private StallWatch() {}

    public static void onTickPre(ServerTickEvent.Pre e) {
        mark();
        inTick = true;
        if (watcher == null) start(e.getServer());
    }

    public static void onTickPost(ServerTickEvent.Post e) {
        mark();
        inTick = false;
    }

    private static void mark() {
        long now = System.nanoTime(), gap = now - progress;
        // before the mark: an observer seeing the new mark already reads this duration
        if (gap > STALL_MS * 1_000_000L) lastGap = gap;
        progress = now;
    }

    /** Stalls logged (checks). */
    public static int reported() {
        return reported;
    }

    private static synchronized void start(MinecraftServer server) {
        if (watcher != null) return;
        Thread main = server.getRunningThread();
        Thread t = new Thread(() -> {
            try {
                watch(server, main);
            } finally {
                watcher = null;
            }
        }, "incoming-stall-watch");
        t.setDaemon(true);
        watcher = t;
        t.start();
    }

    private static void watch(MinecraftServer server, Thread main) {
        List<StackTraceElement[]> samples = new ArrayList<>();
        long stalledAt = 0, pausedAt = 0;
        boolean stalledInTick = false;
        // server shutdown (world saving) is not a thread stall: observer ends with server
        while (server.isRunning()) {
            try {
                Thread.sleep(SAMPLE_MS);
            } catch (InterruptedException ex) {
                return;
            }
            long at = progress;
            if (stalledAt != 0 && at != stalledAt) {
                // duration ends at first mark after stall (written by server thread); other ticks have passed by observer wakeup
                long gap = lastGap;
                lastGap = 0;
                report(gap > 0 ? gap : at - stalledAt, stalledInTick, samples);
                samples.clear();
                stalledAt = 0;
            }
            if (server.isPaused()) {
                // singleplayer pause: no ticks while player in menu — wait for first tick afterward. Pause is set at tick start, with
                // no tick events during it: a between-tick stall where player pressed Esc ended now (duration accurate to wakeup)
                if (stalledAt != 0) report(System.nanoTime() - stalledAt, stalledInTick, samples);
                pausedAt = at;
                samples.clear();
                stalledAt = 0;
                continue;
            }
            if (at == pausedAt || System.nanoTime() - at < STALL_MS * 1_000_000L) continue;
            if (stalledAt == 0) {
                stalledAt = at;
                stalledInTick = inTick;
            }
            if (samples.size() < MAX_SAMPLES) samples.add(main.getStackTrace());
        }
    }

    /** Stall log: duration, location (inside/between ticks), and up to three stack-top locations by sample count. */
    private static void report(long nanos, boolean tick, List<StackTraceElement[]> samples) {
        // group identical stack tops: sample count for each
        Map<String, Integer> count = new LinkedHashMap<>();
        Map<String, StackTraceElement[]> first = new LinkedHashMap<>();
        for (StackTraceElement[] st : samples) {
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < Math.min(12, st.length); i++) key.append(st[i]).append('|');
            count.merge(key.toString(), 1, Integer::sum);
            first.putIfAbsent(key.toString(), st);
        }
        StringBuilder sb = new StringBuilder();
        count.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3).forEach(en -> {
            sb.append("\n  samples ").append(en.getValue()).append(':');
            StackTraceElement[] st = first.get(en.getKey());
            for (int i = 0; i < Math.min(40, st.length); i++) sb.append("\n    at ").append(st[i]);
        });
        reported++;
        Incoming.LOG.warn("Server thread stalled for {} s {}, stack samples {}:{}", String.format(Locale.ROOT, "%.1f", nanos / 1e9),
                tick ? "in a tick" : "between ticks", samples.size(), sb);
    }
}
