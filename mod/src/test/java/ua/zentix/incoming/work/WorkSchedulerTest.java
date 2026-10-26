package ua.zentix.incoming.work;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.world.WorkClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkSchedulerTest {
    private static final long MS = 1_000_000L;
    private static final long TOTAL = 30 * MS, GRID_CAP = (long) (TOTAL * WorkScheduler.GRID_SHARE);

    /**
     * Lane shares within the default 30 ms budget give the same times as the former separate settings (4 ms for blackout,
     * 8 ms for ruin preparation): blackout and nuke GameTest results remain unchanged.
     */
    @Test
    void sharesKeepTheOldLimitsAtTheDefaultBudget() {
        assertEquals(4 * MS, GRID_CAP);
        assertEquals(8 * MS, (long) (TOTAL * WorkScheduler.PREP_SHARE));
    }

    @Test
    void impactTakesAllUnlessLowerLanesWait() {
        assertEquals(TOTAL, WorkScheduler.impactBudget(TOTAL, false));
        assertEquals(20 * MS, WorkScheduler.impactBudget(TOTAL, true));
    }

    @Test
    void lowerLaneGetsWhatIsLeftUpToItsCap() {
        assertEquals(GRID_CAP, WorkScheduler.remaining(TOTAL, 0, GRID_CAP));
        assertEquals(2 * MS, WorkScheduler.remaining(TOTAL, 28 * MS, GRID_CAP));
        assertEquals(0, WorkScheduler.remaining(TOTAL, 45 * MS, GRID_CAP), "the higher lane exceeded its deadline: no negative allowance");
    }

    /**
     * A nuke wave and blackout cascade run together (counting clocks, 1 ms work units): blackout gets
     * its 4 ms rather than one work unit per tick; with no blackout work, the nuke gets the whole remainder.
     */
    @Test
    void nuclearLeavesGridItsShareWhileGridWaits() {
        assertEquals(TOTAL, WorkScheduler.nuclearBudget(TOTAL, 0, 0));
        assertEquals(26 * MS, WorkScheduler.nuclearBudget(TOTAL, 0, GRID_CAP));
        assertEquals(0, WorkScheduler.nuclearBudget(TOTAL, 28 * MS, GRID_CAP), "impacts and the blackout reserve exhausted the allowance");
        WorkClock nuclear = WorkClock.counting(MS), grid = WorkClock.counting(MS);
        for (int tick = 0; tick < 50; tick++) {
            nuclear.start(WorkScheduler.nuclearBudget(TOTAL, 0, GRID_CAP));
            while (nuclear.canStart()) nuclear.end(nuclear.begin());
            grid.start(WorkScheduler.remaining(TOTAL, nuclear.usedThisTickNanos(), GRID_CAP));
            while (grid.canStart()) grid.end(grid.begin());
            assertTrue(nuclear.usedThisTickNanos() + grid.usedThisTickNanos() <= TOTAL + 2 * MS);
        }
        assertTrue(grid.maxUnitsPerTick() >= 3, "blackout per tick: " + grid.maxUnitsPerTick() + " work units");
        assertTrue(nuclear.maxUnitsPerTick() <= 26, "nuke per tick: " + nuclear.maxUnitsPerTick() + " work units");
    }

    /**
     * The server is behind (no time left in the tick): the lane runs to the short deadline {@link WorkScheduler#BUSY_MS}, rather than using its full
     * budget on top of server work; once time is available, it runs to its own deadline again.
     */
    @Test
    void busyServerGetsShortDeadline() {
        long[] now = {0};
        WorkClock c = WorkClock.decaying(() -> now[0], 1);
        boolean[] spare = {false};
        c.start(TOTAL, () -> spare[0], WorkScheduler.BUSY_MS * MS);
        int units = 0;
        while (c.canStart()) {
            long began = c.begin();
            now[0] += MS / 2;
            c.end(began);
            units++;
        }
        assertEquals(4, units, "2 ms deadline, 0.5 ms work units: start while the deadline has not passed");
        now[0] = WorkScheduler.BUSY_MS * MS;
        assertTrue(c.overdue(), "the short deadline passed");
        // a heavy work unit does not stop the lane on subsequent ticks: the 5 ms estimate exceeds the short allowance
        c.start(TOTAL, () -> spare[0], WorkScheduler.BUSY_MS * MS);
        long heavy = c.begin();
        now[0] += 5 * MS;
        c.end(heavy);
        c.start(TOTAL, () -> spare[0], WorkScheduler.BUSY_MS * MS);
        c.end(c.begin());
        assertTrue(c.canStart(), "after a heavy work unit, run to the short deadline again rather than one work unit per tick");
        spare[0] = true;
        assertTrue(c.canStart(), "time is available in the tick: continue within the budget");
        assertFalse(c.overdue());
    }

    /** Counting clocks (flight scenarios, checks) advance only through work: wall-clock "time left in the tick" does not affect them. */
    @Test
    void countingClockIgnoresSpareTime() {
        WorkClock c = WorkClock.counting(MS);
        c.start(TOTAL, () -> false, 0);
        c.end(c.begin());
        assertTrue(c.canStart(), "counting clocks get the full budget");
        assertFalse(c.overdue());
    }

    /** Even past the lane deadline, run one work unit: otherwise a lower lane would stop for the whole salvo. */
    @Test
    void laneWithNoBudgetStillDoesOneUnit() {
        WorkClock c = WorkClock.counting(MS);
        c.start(0);
        assertTrue(c.canStart());
        c.end(c.begin());
        assertFalse(c.canStart());
        assertTrue(c.workedThisTick());
        assertEquals(MS, c.usedThisTickNanos());
    }

    /**
     * Two lanes with endless work and 1 ms work units (counting clocks): each tick uses at most the total allowance plus one work unit
     * per lane; impacts get 2/3 of the total, blackout gets the remainder up to its cap; lane estimates do not affect each other.
     */
    @Test
    void tickStaysWithinTotalPlusOneUnitPerLane() {
        WorkClock impact = WorkClock.counting(MS), grid = WorkClock.counting(MS);
        for (int tick = 0; tick < 50; tick++) {
            impact.start(WorkScheduler.impactBudget(TOTAL, true));
            while (impact.canStart()) impact.end(impact.begin());
            grid.start(WorkScheduler.remaining(TOTAL, impact.usedThisTickNanos(), GRID_CAP));
            while (grid.canStart()) grid.end(grid.begin());
            long used = impact.usedThisTickNanos() + grid.usedThisTickNanos();
            assertTrue(used <= TOTAL + 2 * MS, "tick " + used / MS + " ms");
            assertTrue(grid.workedThisTick(), "blackout had no work unit");
            assertTrue(impact.usedThisTickNanos() <= 20 * MS, "impacts " + impact.usedThisTickNanos() / MS + " ms");
        }
        assertEquals(19, impact.maxUnitsPerTick());
        assertEquals(3, grid.maxUnitsPerTick());
    }

    /** A long explosion in the impact lane: blackout below it runs its work unit without waiting for the impact estimate to decay. */
    @Test
    void longImpactUnitDoesNotStarveGrid() {
        long[] now = {0};
        WorkClock impact = WorkClock.decaying(() -> now[0], 0.5);
        WorkClock grid = WorkClock.counting(MS);
        impact.start(WorkScheduler.impactBudget(TOTAL, true));
        long began = impact.begin();
        now[0] += 280 * MS;
        impact.end(began);
        assertFalse(impact.canStart());
        grid.start(WorkScheduler.remaining(TOTAL, impact.usedThisTickNanos(), GRID_CAP));
        assertTrue(grid.canStart());
        grid.end(grid.begin());
        assertFalse(grid.canStart(), "the blackout deadline passed: only one work unit");
    }

    /** Real clock policy, not WorkClock.counting: the optional consumer makes Post.hasTime false every tick. */
    @Test
    void readyImpactRunsBeforeBackgroundWithinMeasuredHeadroom() {
        for (int mandatory : new int[]{40, 43, 47}) {
            long[] now = {0};
            WorkClock impact = WorkClock.decaying(() -> now[0], 0.5);
            TickReserve reserve = new TickReserve();
            reserve.observe(mandatory * MS);
            int postBusy = 0, earlyWorked = 0;
            long work = 0, maxTick = 0;
            for (int tick = 0; tick < 600; tick++) {
                long start = now[0], deadline = start + 50 * MS;
                long early = reserve.earlyNanos(WorkScheduler.impactBudget(TOTAL, true), now[0], deadline);
                impact.start(early, () -> now[0] < deadline, 0);
                while (impact.canStart(0)) {
                    long began = impact.begin(); now[0] += MS / 4; impact.end(began, 0);
                }
                if (impact.workedThisTick()) earlyWorked++;
                now[0] += mandatory * MS;
                assertTrue(now[0] - start <= 50 * MS + MS / 4);
                now[0] = Math.max(now[0], deadline); // optional autosave takes the remaining time
                if (now[0] >= deadline) postBusy++;
                long unitsBeforePost = impact.units();
                impact.resume(0, () -> now[0] < deadline, 0);
                assertFalse(impact.canStart(0), "no fresh progress guarantee in Post");
                assertEquals(unitsBeforePost, impact.units());
                assertTrue(impact.usedThisTickNanos() <= TOTAL);
                work += impact.usedThisTickNanos();
                maxTick = Math.max(maxTick, now[0] - start);
            }
            assertEquals(600, postBusy);
            assertEquals(600, earlyWorked);
            assertTrue(work / 600.0 / MS > 2, "IMPACT no longer pinned to BUSY_MS");
            System.out.printf(java.util.Locale.ROOT,
                    "PL10 AFTER mandatory=%d post_busy=%d/600 early_work=%d/600 impact_ms=%.3f max_tick_ms=%.3f%n",
                    mandatory, postBusy, earlyWorked, work / 600.0 / MS, maxTick / (double) MS);
        }
    }

    @Test
    void twoWindowsHaveOneBudgetAndOneProgressGuarantee() {
        long[] now = {0};
        WorkClock clock = WorkClock.decaying(() -> now[0], 0.5);
        clock.start(10 * MS);
        long began = clock.begin(); now[0] += 35 * MS; clock.end(began);
        clock.resume(WorkScheduler.remaining(TOTAL, clock.usedThisTickNanos(), TOTAL), () -> true, 0);
        assertFalse(clock.canStart());
        assertEquals(35 * MS, clock.usedThisTickNanos());
        assertEquals(1, clock.ticksWorked());
        // Empty early window: a new impact still starts in Post on the same tick.
        clock.start(0);
        clock.resume(0, () -> false, 0);
        assertTrue(clock.canStart());
        began = clock.begin(); now[0] += MS; clock.end(began);
        assertFalse(clock.canStart());
    }

    @Test
    void skippedEarlyWindowStillHonorsTheAbsoluteDeadlineWhenPostHasTime() {
        for (int mandatory : new int[]{40, 43, 47, 49, 50, 51}) {
            long[] now = {mandatory * MS};
            long deadline = 50 * MS;
            TickReserve reserve = new TickReserve();
            reserve.observe(50 * MS);
            assertEquals(0, reserve.earlyNanos(TOTAL, 0, deadline));
            WorkClock clock = WorkClock.decaying(() -> now[0], 0.5);
            clock.start(WorkScheduler.untilTick(TOTAL, now[0], deadline), () -> now[0] < deadline, 2 * MS);
            while (clock.canStart()) {
                long began = clock.begin();
                now[0] += MS / 4;
                clock.end(began);
            }
            assertTrue(now[0] <= Math.max(mandatory * MS, deadline) + MS / 4, "deadline plus one last unit");
            assertTrue(clock.usedThisTickNanos() <= TOTAL);
        }
        assertEquals(TOTAL, WorkScheduler.untilTick(TOTAL, 0, 100 * MS), "the work cap still applies");
    }
}
