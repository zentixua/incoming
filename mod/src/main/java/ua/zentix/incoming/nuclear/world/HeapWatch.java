package ua.zentix.incoming.nuclear.world;

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Live heap size for prebuilt ruins ({@link NuclearPrep}): old generation after a collection revealing its live size.
 * Old generation means heap pools supporting usage thresholds ({@link MemoryPoolMXBean#isUsageThresholdSupported}):
 * “G1 Old Gen”, “ZGC Old Generation”; single-generation collectors use the whole heap; young pools have no threshold
 * (JDK 21). Reading comes from GC notification ({@link #reading}): full or major collection, or one leaving old generation
 * smaller than before (G1 mixed), after which it contains live objects and some uncollected garbage; also any collection
 * leaving old generation above the limit. No reading after a minor collection below limit: old generation grows through
 * promotion; ZGC young pool “after collection” includes allocations during the cycle: in-game 1.2–1.4 GB versus live
 * 56–86 MB, pool sum 9.3–10.1 GB versus live 6.4–6.8 (GC log).
 * No old-generation pools (another JVM): fallback reading, all heap pools' usage after last collection
 * ({@link #fallbackLive}). One per JVM: all worlds share its memory.
 */
final class HeapWatch {
    private static final HeapWatch INSTANCE = new HeapWatch();
    /** Live heap fraction above which no more ruins are prebuilt. */
    static final double LIMIT = 0.75;
    /**
     * Collectors processing the whole old generation (full collection, major cycle, single-generation collector cycle):
     * read afterward even if old generation grew (G1 full GC promotes live young objects into it).
     */
    static final Set<String> MAJOR = Set.of("G1 Old Generation", "PS MarkSweep", "MarkSweepCompact", "ZGC Major Cycles", "ZGC Cycles", "Shenandoah Cycles");

    private final Set<String> oldPools = new HashSet<>();
    private final long limit = (long) (Runtime.getRuntime().maxMemory() * LIMIT);
    /** Last reading, bytes; −1 — none yet. Written by JMX notification thread, read by server thread. */
    private volatile long live = -1;
    /** Reading number: written after {@link #live}, so a read number is no newer than the value. */
    private volatile long measured;

    private HeapWatch() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.isUsageThresholdSupported()) oldPools.add(pool.getName());
        }
        if (oldPools.isEmpty()) return;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc instanceof NotificationEmitter emitter && collects(gc)) emitter.addNotificationListener(this::onGc, null, null);
        }
    }

    static HeapWatch get() {
        return INSTANCE;
    }

    /**
     * Collection after which pools know their usage: young, mixed, full, ZGC or Shenandoah cycle. Not pauses within cycles:
     * JDK 21 G1 “G1 Concurrent GC” means Remark and Cleanup; ZGC/Shenandoah “… Pauses” repeat “… Cycles”.
     */
    private static boolean collects(GarbageCollectorMXBean gc) {
        String name = gc.getName();
        return !name.contains("Concurrent") && !name.endsWith("Pauses");
    }

    private void onGc(Notification n, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) return;
        var gc = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
        var info = gc.getGcInfo();
        long after = reading(gc.getGcName(), info.getMemoryUsageBeforeGc(), info.getMemoryUsageAfterGc(), oldPools, limit);
        if (after < 0) return;
        live = after;
        measured++;
    }

    /**
     * Old generation after {@code gc} if its live size is visible, otherwise −1: collection in {@link #MAJOR}; any collection
     * shrinking at least one old pool; or old generation afterward above {@code limit} — uncollected garbage no longer
     * matters there: without a full collection no reading would exist and heap would run out.
     */
    static long reading(String gc, Map<String, MemoryUsage> before, Map<String, MemoryUsage> after, Set<String> oldPools, long limit) {
        long sum = 0;
        boolean freed = false;
        for (String pool : oldPools) {
            MemoryUsage b = before.get(pool), a = after.get(pool);
            if (b == null || a == null) return -1;
            sum += a.getUsed();
            freed |= a.getUsed() < b.getUsed();
        }
        return freed || MAJOR.contains(gc) || sum > limit ? sum : -1;
    }

    /** Old-generation reading: pools have an old generation (otherwise {@link #fallbackLive}). */
    boolean available() {
        return !oldPools.isEmpty();
    }

    /** Live size limit ({@link #LIMIT} of heap), bytes. */
    long limit() {
        return limit;
    }

    /** Last reading number (0 — none); without old-generation pools, collection count. */
    long measured() {
        if (available()) return measured;
        long collections = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collects(gc)) collections += Math.max(0, gc.getCollectionCount());
        }
        return collections;
    }

    /** Live size from last reading, bytes; −1 — none yet. */
    long live() {
        return available() ? live : fallbackLive();
    }

    /**
     * Without old-generation pools: all heap pools' usage after last collection; pools lacking this data use current usage
     * (including garbage, hence conservative).
     */
    private static long fallbackLive() {
        long sum = 0;
        boolean any = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            MemoryUsage after = pool.getCollectionUsage();
            if (after == null) continue;
            sum += after.getUsed();
            any = true;
        }
        if (any) return sum;
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Consecutive readings over limit: {@code strikes} is the prior count, returns new count. */
    static int strikes(int strikes, long live, long limit) {
        return live > limit ? strikes + 1 : 0;
    }

    /**
     * Own consecutive-over-limit count (server thread): separate for each memory-conscious user ({@code FlashScorch}),
     * as for prebuilt ruins. One reading is not yet shortage: old generation may contain some uncollected garbage.
     */
    static final class Tracker {
        private long lastMeasured;
        private int strikes;

        /** Live heap size above limit in two consecutive readings. */
        boolean tight() {
            HeapWatch watch = get();
            long measured = watch.measured();
            if (measured != lastMeasured) {
                lastMeasured = measured;
                strikes = HeapWatch.strikes(strikes, watch.live(), watch.limit());
            }
            return strikes >= 2;
        }
    }
}
