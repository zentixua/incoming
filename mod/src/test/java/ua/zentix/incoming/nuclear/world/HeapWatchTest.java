package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;

import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Heap measurement for ruins in advance ({@link HeapWatch#reading}): only the old generation and only after the assembly, which
 *  sees its live volume, or above the limit. Numbers - from the collector log in the game (ZGC with generations, heap
 * 12 GB, limit 75 % - 9216 MB): large cycle GC(3730) - old 6342 → 5878 MB with living 5386, young “after”
 * 1190 MB with living 56; small GC(3739) - did not release the old, the young “after” 1348 MB while 86 was alive.
 */
class HeapWatchTest {
    private static final long MB = 1L << 20;
    private static final long LIMIT = 9216 * MB;
    private static final String OLD = "ZGC Old Generation", YOUNG = "ZGC Young Generation", MAJOR = "ZGC Major Cycles", MINOR = "ZGC Minor Cycles";
    private static final String G1_OLD = "G1 Old Gen", G1_EDEN = "G1 Eden Space";

    private static MemoryUsage used(long mb) {
        return new MemoryUsage(0, mb * MB, 12288 * MB, 12288 * MB);
    }

    @Test
    void majorCycleMeasuresOldOnly() {
        long live = HeapWatch.reading(MAJOR, Map.of(OLD, used(6342), YOUNG, used(1840)), Map.of(OLD, used(5878), YOUNG, used(1190)), Set.of(OLD), LIMIT);
        assertEquals(5878 * MB, live, "large cycle: old after assembly, without young");
    }

    @Test
    void minorCycleIsNoMeasurement() {
        // small cycle: the old grows by advancement, the garbage is not collected in it
        assertEquals(-1, HeapWatch.reading(MINOR, Map.of(OLD, used(5878), YOUNG, used(3854)), Map.of(OLD, used(5878), YOUNG, used(1348)), Set.of(OLD), LIMIT));
        assertEquals(-1, HeapWatch.reading(MINOR, Map.of(OLD, used(5878)), Map.of(OLD, used(5901)), Set.of(OLD), LIMIT));
    }

    @Test
    void minorCycleThatFreedOldMeasures() {
        // mixed assembly G1 comes from “G1 Young Generation”: it can be seen by the fact that the old one has become smaller
        assertEquals(5100 * MB, HeapWatch.reading("G1 Young Generation", Map.of(G1_OLD, used(5600)), Map.of(G1_OLD, used(5100)), Set.of(G1_OLD), LIMIT));
    }

    @Test
    void fullGcOldGrewMeasures() {
        // complete assembly G1 transfers the living from the young to the old: the old grows, but the measurement is correct
        long live = HeapWatch.reading("G1 Old Generation", Map.of(G1_OLD, used(7000), G1_EDEN, used(900)), Map.of(G1_OLD, used(7200), G1_EDEN, used(0)), Set.of(G1_OLD), LIMIT);
        assertEquals(7200 * MB, live);
    }

    @Test
    void overLimitMeasuresWithoutFreeing() {
        // the old is beyond the limit, and there is no large assembly: without this measurement, the ruins would not have stopped before the shortage
        assertEquals(9400 * MB, HeapWatch.reading(MINOR, Map.of(OLD, used(9300)), Map.of(OLD, used(9400)), Set.of(OLD), LIMIT));
    }

    @Test
    void missingPoolIsNoMeasurement() {
        assertEquals(-1, HeapWatch.reading(MAJOR, Map.of(YOUNG, used(10)), Map.of(YOUNG, used(5)), Set.of(OLD), LIMIT));
    }

    @Test
    void strikesCountOnlyInARow() {
        int s = HeapWatch.strikes(0, 9300 * MB, LIMIT);
        assertEquals(1, s);
        s = HeapWatch.strikes(s, 6000 * MB, LIMIT);
        assertEquals(0, s, "measurement below the limit resets the count");
        s = HeapWatch.strikes(HeapWatch.strikes(s, 9300 * MB, LIMIT), 9400 * MB, LIMIT);
        assertEquals(2, s);
    }
}
