package ua.zentix.incoming.util;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Stop-the-world GC time so far, for slow-tick lines: a slow line names whether a collector pause fell inside it.
 * Concurrent collectors' cycle beans ("G1 Concurrent GC", "ZGC … Cycles", "Shenandoah Cycles") count time when game
 * threads keep running, so only pause beans count.
 */
public final class GcPauses {
    private static final List<GarbageCollectorMXBean> PAUSES = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .filter(gc -> pauses(gc.getName())).toList();

    private GcPauses() {}

    /** Bean counts stop-the-world pauses (G1 young/old, ZGC and Shenandoah pause beans, serial/parallel collectors). */
    static boolean pauses(String name) {
        return !name.contains("Concurrent") && !name.contains("Cycles");
    }

    /** Pause time since JVM start, ms (any thread). */
    public static long millis() {
        long sum = 0;
        for (GarbageCollectorMXBean gc : PAUSES) sum += Math.max(0, gc.getCollectionTime());
        return sum;
    }
}
