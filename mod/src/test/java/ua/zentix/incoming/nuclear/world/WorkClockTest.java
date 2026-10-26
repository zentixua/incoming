package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkClockTest {
    private static final long MS = 1_000_000L;
    private static final long BUDGET = 4 * MS, UNIT = 300_000L;

    private final long[] now = {0};

    /** A unit that does not meet the deadline does not begin. */
    @Test
    void doesNotStartWorkThatWouldOverrun() {
        WorkClock c = new WorkClock();
        c.start(10_000_000L);
        assertTrue(c.canStart());
        c.record(20_000_000L);
        assertFalse(c.canStart(), "unit for 20 ms does not fit into the budget 10 ms");
    }

    /** Even after a very long unit in the new tick, one unit is done: the queue does not rise. */
    @Test
    void alwaysDoesOneUnitPerTick() {
        WorkClock c = new WorkClock();
        c.start(10_000_000L);
        c.record(50_000_000L);
        c.start(10_000_000L);
        assertTrue(c.canStart());
    }

    /** The assessment fades: after one heavy column for fifty light ones, we again take the work back to back. */
    @Test
    void estimateDecays() {
        WorkClock c = new WorkClock();
        c.start(5_000_000_000L);
        c.record(8_000_000L);
        for (int i = 0; i < 60; i++) c.record(10_000L);
        c.start(1_000_000L);
        c.record(10_000L);
        assertTrue(c.canStart(), "the assessment has not faded");
    }

    /** Counting clock: a queue of 1000 units at 1 ms with a budget 30 ms goes at 29 per tick and no more. */
    @Test
    void countingClockSplitsQueueByBudget() {
        WorkClock c = WorkClock.counting(1_000_000L);
        int left = 1000, ticks = 0;
        while (left > 0) {
            c.start(30_000_000L);
            int units = 0;
            while (left > 0 && c.canStart()) {
                c.end(c.begin());
                left--;
                units++;
            }
            ticks++;
            assertTrue(units >= 1 && units <= 30, "per tick " + units + " units");
        }
        assertEquals(ticks, c.ticksWorked());
        assertEquals(29, c.maxUnitsPerTick());
    }

    /** tick queue of even units by 0.3 ms; the first tick unit is {@code first} ns. Returns the number of units. */
    private int tick(WorkClock clock, long first) {
        clock.start(BUDGET);
        int units = 0;
        while (clock.canStart()) {
            long began = clock.begin();
            now[0] += units == 0 ? first : UNIT;
            clock.end(began);
            units++;
        }
        now[0] += 50 * MS;
        return units;
    }

    @Test
    void cheapUnitsFillTheBudget() {
        WorkClock clock = WorkClock.decaying(() -> now[0], 0.5);
        tick(clock, UNIT);
        int units = tick(clock, UNIT);
        // 0.3 ms unit, 4 ms budget: 13 units, the latter starts before the deadline with a margin for evaluation
        assertEquals(13, units);
        assertTrue(clock.maxTickNanos() <= BUDGET, "tick " + clock.maxTickNanos() + " ns");
    }

    /** Burst at 20 ms (pause GC in the middle of one): the tick-to-tick estimate releases the queue in a few ticks. */
    @Test
    void spikeReleasesWithinFewTicks() {
        WorkClock decaying = WorkClock.decaying(() -> now[0], 0.5);
        tick(decaying, 20 * MS);
        int ticks = 0;
        while (tick(decaying, UNIT) < 13) ticks++;
        assertTrue(ticks <= 4, "after the splash " + ticks + " tick incomplete queue");
        assertEquals(20 * MS, decaying.largestRecentNanos());
    }

    /** The score, melting only by units, after the same surge keeps the queue at one unit for tick tens tick. */
    @Test
    void perUnitDecayAloneStaysSlow() {
        WorkClock plain = WorkClock.decaying(() -> now[0], 1);
        tick(plain, 20 * MS);
        int single = 0;
        while (tick(plain, UNIT) == 1) single++;
        assertTrue(single > 20, "one unit per tick only " + single + " tick");
    }

    @Test
    void statusReportsLastTick() {
        WorkClock clock = WorkClock.decaying(() -> now[0], 0.5);
        int units = tick(clock, UNIT);
        clock.start(BUDGET);
        assertEquals(units, clock.unitsLastTick());
        assertEquals(UNIT, clock.largestRecentNanos());
    }

    /**
     * Inspection of what is waiting is not a unit: time is counted as a tick, the evaluation and number of units do not change, the first tick unit
     *  is still started; term ({@link WorkClock#overdue}) - to date. The inspection does not move the counting clock.
     */
    @Test
    void spentWorkCountsTimeNotUnits() {
        WorkClock clock = WorkClock.decaying(() -> now[0], 1);
        clock.start(BUDGET);
        long began = clock.begin();
        now[0] += 3 * MS;
        assertEquals(3 * MS, clock.spent(began));
        assertEquals(3 * MS, clock.usedThisTickNanos());
        assertFalse(clock.workedThisTick());
        assertEquals(0, clock.estimateNanos());
        assertTrue(clock.canStart(), "after inspections the first tick unit is not started");
        assertFalse(clock.overdue());
        now[0] += MS;
        assertTrue(clock.overdue(), "the deadline has passed, but the clock does not see it");

        WorkClock counting = WorkClock.counting(MS);
        counting.start(BUDGET);
        for (int i = 0; i < 10; i++) counting.spent(counting.begin());
        assertFalse(counting.overdue(), "inspections moved the counting clock");
        assertEquals(0, counting.usedThisTickNanos());
    }

    /**
     * Job visible to the player ({@link WorkClock#canStartVisible}): on a server without free time, its duration is longer than a small
     *  for an addition, but not further than the full term; with free time - the same as for the rest of the work.
     */
    @Test
    void visibleWorkGetsMoreTimeOnlyWithoutSpareTime() {
        boolean[] spare = {false};
        WorkClock clock = WorkClock.decaying(() -> now[0], 1);
        clock.start(30 * MS, () -> spare[0], 2 * MS);
        long began = clock.begin();
        now[0] += 3 * MS;
        clock.end(began);
        assertFalse(clock.canStart(), "short term 2 ms has expired, but work is in progress");
        assertTrue(clock.overdue());
        assertTrue(clock.canStartVisible(6 * MS), "visible work no additive on busy server");
        assertFalse(clock.overdueVisible(6 * MS));
        now[0] += 5 * MS;
        assertFalse(clock.canStartVisible(6 * MS), "addition greater than 6 ms");
        assertTrue(clock.overdueVisible(6 * MS));
        now[0] = 31 * MS;
        assertFalse(clock.canStartVisible(100 * MS), "the supplement took out the full term");

        spare[0] = true;
        now[0] = 0;
        clock.start(30 * MS, () -> spare[0], 2 * MS);
        began = clock.begin();
        now[0] += 10 * MS;
        clock.end(began);
        assertEquals(clock.canStart(), clock.canStartVisible(6 * MS), "with free time visible work another deadline");
        assertEquals(clock.overdue(), clock.overdueVisible(6 * MS));
    }

    @Test
    void tickDecayOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorkClock.decaying(0));
        assertThrows(IllegalArgumentException.class, () -> WorkClock.decaying(1.5));
    }

    /** Clock inside the unit: those who count have their own counter at the price of the unit, it does not change the queue time; the real ones have their time. */
    @Test
    void samplerCountsWithoutMovingQueueTime() {
        WorkClock counting = WorkClock.counting(UNIT);
        java.util.function.LongSupplier sample = counting.sampler();
        counting.start(BUDGET);
        long began = counting.begin();
        long a = sample.getAsLong(), b = sample.getAsLong();
        assertEquals(UNIT, b - a);
        assertEquals(UNIT, counting.end(began, 0));
        now[0] = 42;
        assertEquals(42, WorkClock.decaying(() -> now[0], 1).sampler().getAsLong());
    }
}
