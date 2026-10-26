package ua.zentix.incoming.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImpactTimingTest {
    private static final long MS = 1_000_000L;

    @Test void optionalSavingIsNotMandatorySimulation() {
        long[] now = {0};
        ImpactTiming timing = new ImpactTiming(() -> now[0]);
        timing.begin(() -> true);
        now[0] += 40 * MS;
        timing.push("chunk_unload");
        now[0] += 7 * MS;
        timing.push("nested");
        now[0] += 3 * MS;
        timing.pop();
        timing.pop();
        now[0] += 2 * MS;
        assertEquals(10 * MS, timing.optionalNanos());
        assertEquals(40 * MS, timing.mandatoryNanos(2 * MS));
    }

    @Test void unloadDebtStaysInReserveAndSuppliersStayLazy() {
        long[] now = {0};
        ImpactTiming timing = new ImpactTiming(() -> now[0]);
        timing.begin(() -> false);
        timing.push(() -> { throw new AssertionError("evaluated section supplier"); });
        timing.push("chunk_unload");
        now[0] += 47 * MS;
        timing.popPush("entities");
        now[0] += 3 * MS;
        timing.pop();
        timing.pop();
        assertEquals(0, timing.optionalNanos());
        assertEquals(50 * MS, timing.mandatoryNanos(0));
    }

    @Test void lateMandatoryWorkAndAnOuterProfilerPopAreNotOptionalSaving() {
        long[] now = {0};
        ImpactTiming timing = new ImpactTiming(() -> now[0]);
        timing.begin(() -> true);
        now[0] += 30 * MS;
        timing.push("chunk_unload");
        now[0] += 5 * MS;
        timing.pop();
        now[0] += 10 * MS; // another mod's Post handler, before the LOWEST measurement endpoint
        timing.pop(); // an outer profiler section began before this observer was installed
        timing.pop();
        assertEquals(5 * MS, timing.optionalNanos());
        assertEquals(40 * MS, timing.mandatoryNanos(0));
    }

    @Test void unknownCostAndRecentMandatorySpikeDoNotGrantThirtyMilliseconds() {
        TickReserve reserve = new TickReserve();
        assertEquals(0, reserve.earlyNanos(30 * MS, 0, 50 * MS));
        reserve.observe(40 * MS);
        assertEquals(10 * MS, reserve.earlyNanos(30 * MS, 0, 50 * MS));
        reserve.observe(49 * MS);
        for (int i = 0; i < 19; i++) {
            reserve.observe(40 * MS);
            assertEquals(MS, reserve.earlyNanos(30 * MS, 0, 50 * MS));
        }
        reserve.observe(43 * MS);
        assertEquals(7 * MS, reserve.earlyNanos(30 * MS, 0, 50 * MS));
        assertEquals(0, reserve.earlyNanos(30 * MS, 8 * MS, 50 * MS), "late schedule, not MSPT classification");
        assertEquals(0, new TickReserve().mandatoryNanos(), "new world carries no old measurement");
    }
}
