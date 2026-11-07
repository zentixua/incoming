package ua.zentix.incoming.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slow-tick lines count only stop-the-world pauses, not concurrent cycles that run beside the game threads. */
class GcPausesTest {
    @Test
    void countsPauseBeansOnly() {
        for (String pause : new String[] {"G1 Young Generation", "G1 Old Generation", "ZGC Minor Pauses", "ZGC Major Pauses",
                "Shenandoah Pauses", "PS Scavenge", "PS MarkSweep", "Copy", "MarkSweepCompact"}) assertTrue(GcPauses.pauses(pause), pause);
        for (String cycle : new String[] {"G1 Concurrent GC", "ZGC Minor Cycles", "ZGC Major Cycles", "ZGC Cycles", "Shenandoah Cycles"})
            assertFalse(GcPauses.pauses(cycle), cycle);
        assertTrue(GcPauses.millis() >= 0);
    }
}
