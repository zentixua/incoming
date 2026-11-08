package ua.zentix.incoming.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkLoadPaceTest {
    private static final long MS = 1_000_000L, TICK = 50 * MS;

    /** Simulated server: ticks of {@code tickMs}, our loads asked near the tick end, vanilla decodes them all after it. */
    private static final class Server {
        final long[] now = {0};
        final ChunkLoadPace pace = new ChunkLoadPace(() -> now[0]);
        long scheduled = TICK;
        int tick;
        long maxLate, started;

        /** One tick: returns how many of {@code demand} loads started. */
        int tick(long tickNanos, int demand, long decodeNanos) {
            long start = Math.max(now[0], scheduled - TICK);
            maxLate = Math.max(maxLate, start - (scheduled - TICK));
            now[0] = start;
            pace.tickStarted(scheduled - TICK);
            now[0] += tickNanos - 2 * MS; // our Post lane, near the end of the tick
            int n = 0;
            while (n < demand && pace.tryStart(scheduled, tick, false)) n++;
            now[0] += 2 * MS;
            pace.tickEnded();
            for (int i = 0; i < n; i++) {
                now[0] += decodeNanos;
                pace.decoded();
            }
            scheduled += TICK;
            tick++;
            started += n;
            return n;
        }
    }

    @Test void loadsDecodeBeforeTheNextTick() {
        // r4 run6 (1 Mt over Zearth): tick 20 ms, decode ~2.6 ms a chunk; the settlement wants more than fits
        Server s = new Server();
        for (int i = 0; i < 400; i++) s.tick(20 * MS, 40, 2_600_000);
        assertEquals(0, s.maxLate, "a tick started late");
        // the space before the next tick is used, not wasted: (50 - 20 - 5) / 2.6 = 9 a tick
        assertTrue(s.started >= 400 * 8, "loads per tick " + s.started / 400.0);
        assertEquals(2.6, s.pace.costNanos() / MS, 0.05);
    }

    @Test void slowerDecodeIsLearned() {
        // started with the 3 ms default, a 6 ms decode makes the first gaps late; the cost then follows the measurement
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
        pace.tickEnded();
        now[0] += 30 * MS; // the read was still queued behind saves
        pace.decoded();
        now[0] += 40 * MS;
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

    @Test void busyServerStillStartsAtTheFloor() {
        // the tick takes the whole 50 ms: no time before the next tick; background loads still progress slowly
        Server s = new Server();
        for (int i = 0; i < 100; i++) s.tick(TICK, 40, 2 * MS);
        assertEquals(100 / ChunkLoadPace.FLOOR_TICKS, s.started);
    }

    @Test void requestedLoadsNotYetDecodedCount() {
        long[] now = {0};
        ChunkLoadPace pace = new ChunkLoadPace(() -> now[0]);
        pace.tickStarted(0);
        now[0] += 20 * MS;
        int n = 0;
        while (pace.tryStart(TICK, 0, false)) n++;
        // (50 - 20 - 5) ms / 3 ms default
        assertEquals(8, n);
        assertEquals(8, pace.outstanding(), 1e-9);
        // the requested loads fill the gap: no more this tick, the floor waits for its own ticks
        assertFalse(pace.tryStart(TICK, 0, false));
        assertFalse(pace.tryStart(TICK, ChunkLoadPace.FLOOR_TICKS - 1, false));
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
