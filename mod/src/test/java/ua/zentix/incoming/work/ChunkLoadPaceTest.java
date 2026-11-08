package ua.zentix.incoming.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkLoadPaceTest {
    private static final long MS = 1_000_000L, TICK = 50 * MS;

    /**
     * Simulated server: ticks of {@code tickMs}; our loads are asked in the middle of the work lanes, {@code afterMs}
     * before the tick ends (other lanes and mods' Post work after them); vanilla decodes them all after the tick.
     */
    private static final class Server {
        final long[] now = {0};
        final ChunkLoadPace pace = new ChunkLoadPace(() -> now[0]);
        long scheduled = TICK;
        int tick;
        long maxLate, started, decodes;

        /** One tick: returns how many of {@code demand} loads, each decoding {@code perLoad} chunks, started. */
        int tick(long tickNanos, long afterNanos, int demand, int perLoad, long decodeNanos) {
            long start = Math.max(now[0], scheduled - TICK);
            maxLate = Math.max(maxLate, start - (scheduled - TICK));
            now[0] = start;
            pace.tickStarted(scheduled - TICK);
            now[0] += tickNanos - afterNanos;
            int n = 0;
            while (n < demand && pace.tryStart(perLoad, tick, false)) n++;
            now[0] += afterNanos;
            pace.tickEnded(scheduled);
            for (int i = 0; i < n * perLoad; i++) {
                now[0] += decodeNanos;
                pace.decoded();
            }
            scheduled += TICK;
            tick++;
            started += n;
            decodes += (long) n * perLoad;
            return n;
        }

        int tick(long tickNanos, int demand, long decodeNanos) {
            return tick(tickNanos, 2 * MS, demand, 1, decodeNanos);
        }
    }

    @Test void loadsDecodeBeforeTheNextTick() {
        // r4 run6 (1 Mt over Zearth): tick 20 ms, decode ~2.6 ms a chunk; the settlement wants more than fits
        Server s = new Server();
        // the 1 ms starting cost is learned in the first ticks
        for (int i = 0; i < 100; i++) s.tick(20 * MS, 40, 2_600_000);
        s.maxLate = 0;
        s.started = 0;
        for (int i = 0; i < 400; i++) s.tick(20 * MS, 40, 2_600_000);
        assertEquals(0, s.maxLate, "a tick started late");
        // the space before the next tick is used, not wasted: (50 - 20 - 5) / 2.6 = 9 a tick
        assertTrue(s.started >= 399 * 8, "loads per tick " + s.started / 400.0);
        assertEquals(2.6, s.pace.costNanos() / MS, 0.05);
    }

    @Test void slowerDecodeIsLearned() {
        // started with the 1 ms default, a 6 ms decode makes the first gaps late; the cost then follows the measurement
        Server s = new Server();
        for (int i = 0; i < 100; i++) s.tick(20 * MS, 40, 6 * MS);
        s.maxLate = 0;
        for (int i = 0; i < 400; i++) s.tick(20 * MS, 40, 6 * MS);
        assertEquals(0, s.maxLate, "a tick started late after the cost was measured");
        assertEquals(6, s.pace.costNanos() / MS, 0.1);
    }

    @Test void waitingForDiskIsNotDecodeTime() {
        long[] now = {0};
        ChunkLoadPace pace = new ChunkLoadPace(() -> now[0]);
        pace.tickStarted(0);
        now[0] += 20 * MS;
        pace.tickEnded(50 * MS);
        now[0] += 35 * MS; // the read was still queued behind saves
        pace.decoded();
        now[0] += 45 * MS;
        pace.decoded();
        assertEquals(ChunkLoadPace.DEFAULT_COST_NANOS, pace.costNanos());
    }

    @Test void decodesInsideATickAreNotSampled() {
        long[] now = {0};
        ChunkLoadPace pace = new ChunkLoadPace(() -> now[0]);
        pace.tickStarted(0);
        now[0] += 1 * MS;
        pace.decoded(); // a synchronous load inside the tick
        now[0] += 10 * MS;
        pace.decoded();
        assertEquals(ChunkLoadPace.DEFAULT_COST_NANOS, pace.costNanos());
    }

    @Test void requestsEarlyInTheTickUseTheGapAtItsEnd() {
        // loads asked with 27 ms left, but 20 ms of lanes and Post work follow: the gap is 7 ms, not 27
        Server s = new Server();
        for (int i = 0; i < 100; i++) s.tick(43 * MS, 20 * MS, 40, 1, 2_600_000);
        s.maxLate = 0;
        for (int i = 0; i < 400; i++) s.tick(43 * MS, 20 * MS, 40, 1, 2_600_000);
        assertEquals(0, s.maxLate, "a tick started late");
    }

    @Test void neighbourDecodesAreCharged() {
        // an isolated full load decodes its 3x3 square (vanilla lights a chunk with ring-1 neighbors from disk)
        Server s = new Server();
        for (int i = 0; i < 100; i++) s.tick(20 * MS, 2 * MS, 40, 3, 2_600_000);
        s.maxLate = 0;
        s.decodes = 0;
        for (int i = 0; i < 400; i++) s.tick(20 * MS, 2 * MS, 40, 3, 2_600_000);
        assertEquals(0, s.maxLate, "a tick started late");
        assertTrue(s.decodes >= 399 * 8, "decodes per tick " + s.decodes / 400.0);
    }

    @Test void unevenTicksUseTheGapsTheyHaveAndStayOnTime() {
        // r4 run8 settlement: tick p50 ~17 ms, p95 ~34 ms (Pre..Post), a load decodes ~9 chunks of ~1 ms. The smallest of
        // the last 10 gaps gives 1.06 loads a tick here (0 late); the median with the late-start factor 2.1, but 37 ticks
        // over 10 ms late; the 20th percentile 1.8 with 1.
        Server s = new Server();
        java.util.Random random = new java.util.Random(1);
        java.util.function.LongSupplier tick = () -> (long) ((random.nextDouble() < 0.1 ? 30 + 10 * random.nextDouble()
                : 12 + 12 * random.nextDouble()) * MS);
        for (int i = 0; i < 200; i++) s.tick(tick.getAsLong(), 2 * MS, 40, 9, MS);
        s.started = 0;
        int lateTicks = 0, n = 2000;
        for (int i = 0; i < n; i++) {
            long before = s.maxLate;
            s.maxLate = 0;
            s.tick(tick.getAsLong(), 2 * MS, 40, 9, MS);
            if (s.maxLate > 10 * MS) lateTicks++;
            s.maxLate = Math.max(before, s.maxLate);
        }
        assertTrue(lateTicks <= n / 100, "ticks over 10 ms late " + lateTicks + " of " + n + ", worst " + s.maxLate / 1e6 + " ms");
        assertTrue(s.started >= 1.5 * n, "loads per tick " + (double) s.started / n + ", factor " + s.pace.factor());
    }

    @Test void lateStartAfterAnOnTimeTickCutsTheBudget() {
        long[] now = {0};
        ChunkLoadPace pace = new ChunkLoadPace(() -> now[0]);
        pace.tickStarted(0);
        now[0] = 20 * MS;
        pace.tickEnded(TICK); // ended with 30 ms to spare
        now[0] = TICK + 8 * MS; // started 8 ms late: something between ticks overran
        pace.tickStarted(TICK);
        assertEquals(ChunkLoadPace.FACTOR_DOWN, pace.factor(), 1e-9);
        now[0] = TICK + 60 * MS;
        pace.tickEnded(2 * TICK); // the tick itself overran: its late successor says nothing about loads
        now[0] = 2 * TICK + 20 * MS;
        pace.tickStarted(2 * TICK);
        assertEquals(ChunkLoadPace.FACTOR_DOWN, pace.factor(), 1e-9);
        now[0] = 2 * TICK + 30 * MS;
        pace.tickEnded(3 * TICK);
        assertTrue(pace.tryStart(1000, 3, false)); // the floor's one load
        assertFalse(pace.tryStart(1000, 3, false)); // denied
        now[0] = 3 * TICK;
        pace.tickStarted(3 * TICK); // on time after a denied load: the budget grows back
        assertEquals(ChunkLoadPace.FACTOR_DOWN + ChunkLoadPace.FACTOR_UP, pace.factor(), 1e-9);
    }

    @Test void busyServerStillStartsAtTheFloor() {
        // the tick takes the whole 50 ms: no time before the next tick; background loads still progress slowly
        Server s = new Server();
        for (int i = 0; i < 100; i++) s.tick(TICK, 40, 2 * MS);
        assertEquals(100 / ChunkLoadPace.FLOOR_TICKS, s.started);
    }

    @Test void requestedLoadsNotYetDecodedCount() {
        long[] now = {1};
        ChunkLoadPace pace = new ChunkLoadPace(() -> now[0]);
        pace.tickStarted(0);
        now[0] = 20 * MS;
        pace.tickEnded(TICK); // a 30 ms gap measured
        pace.tickStarted(TICK);
        now[0] = TICK + 10 * MS; // asked early in the next tick: the measured gap counts, not the time left now
        int n = 0;
        while (pace.tryStart(1, 1, false)) n++;
        // (30 - 5) ms / 1 ms default
        assertEquals(25, n);
        assertEquals(25, pace.outstanding(), 1e-9);
        // the requested decodes fill the gap: no more this tick, the floor waits for its own ticks
        assertFalse(pace.tryStart(1, 1, false));
        assertFalse(pace.tryStart(1, ChunkLoadPace.FLOOR_TICKS, false));
        assertTrue(pace.tryStart(0, 1, false), "a load decoding nothing is free");
    }

    @Test void reportCountsLateTickStarts() {
        long[] now = {TICK};
        ChunkLoadPace pace = new ChunkLoadPace(() -> now[0]);
        pace.tickStarted(0);
        now[0] = 100 * MS;
        pace.tickStarted(100 * MS);
        now[0] = 175 * MS; // 75 ms interval, 25 ms after its schedule
        pace.tickStarted(150 * MS);
        now[0] = 200 * MS;
        pace.tickStarted(200 * MS);
        String r = pace.report();
        assertTrue(r.contains("intervals over 60 ms 33.3%"), r);
        assertTrue(r.contains("late over 10 ms 33.3%"), r);
        assertTrue(r.contains("longest interval 75.0 ms"), r);
    }
}
