package ua.zentix.incoming.work;

import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.world.WorkClock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Work-unit queue without a world: jobs receive {@code null} for the world and world time as a number. */
class UnitQueueTest {
    private static final long MS = 1_000_000L;

    /** A job of {@code units} work units; logs "name:number" and "name:end". */
    private static final class Fake implements UnitQueue.Job {
        final String name;
        final List<String> log;
        int units;
        boolean ready = true;
        UnitQueue.Job waitsFor;
        int ended;
        int readyCalls, blockedCalls;
        Runnable onStep = () -> {};

        Fake(String name, int units, List<String> log) {
            this.name = name;
            this.units = units;
            this.log = log;
        }

        @Override
        public boolean ready(ServerLevel level) {
            readyCalls++;
            return ready;
        }

        @Override
        public boolean blocked() {
            blockedCalls++;
            return waitsFor instanceof Fake f && f.ended == 0;
        }

        @Override
        public boolean step(ServerLevel level) {
            log.add(name + ":" + units);
            onStep.run();
            return --units > 0;
        }

        @Override
        public void end(ServerLevel level) {
            ended++;
            log.add(name + ":end");
        }

        @Override
        public String describe() {
            return name;
        }
    }

    private static void tick(UnitQueue q, WorkClock clock, long budget, long now) {
        clock.start(budget);
        q.work(null, now, clock);
    }

    /** New jobs run their first work units before existing jobs continue: the second strike detonates without waiting for the first strike's batches. */
    @Test
    void headsBeforeTails() {
        List<String> log = new ArrayList<>();
        UnitQueue q = new UnitQueue();
        q.add(0, new Fake("a", 5, log));
        q.add(0, new Fake("b", 3, log));
        tick(q, WorkClock.counting(MS), 4 * MS, 0);
        assertEquals(List.of("a:5", "b:3", "a:4"), log);
        tick(q, WorkClock.counting(MS), 100 * MS, 1);
        assertEquals(List.of("a:5", "b:3", "a:4", "b:2", "a:3", "b:1", "b:end", "a:2", "a:1", "a:end"), log);
        assertTrue(q.isEmpty());
    }

    /** A job waiting for another is skipped until that job finishes; later jobs continue. */
    @Test
    void blockedWaitsForItsJob() {
        List<String> log = new ArrayList<>();
        UnitQueue q = new UnitQueue();
        Fake main = new Fake("main", 3, log);
        Fake fireball = new Fake("fire", 1, log);
        fireball.waitsFor = main;
        Fake other = new Fake("other", 1, log);
        q.add(0, main);
        q.add(0, fireball);
        q.add(0, other);
        tick(q, WorkClock.counting(MS), 3 * MS, 0);
        assertEquals(List.of("main:3", "other:1", "other:end"), log);
        tick(q, WorkClock.counting(MS), 100 * MS, 1);
        assertEquals(List.of("main:3", "other:1", "other:end", "main:2", "main:1", "main:end"), log);
        tick(q, WorkClock.counting(MS), 100 * MS, 2);
        assertEquals("fire:end", log.getLast());
        assertTrue(q.isEmpty());
    }

    /** An unready job waits until {@link UnitQueue#GIVE_UP_TICKS}, then is canceled: released once, without work units. */
    @Test
    void unreadyJobGivesUpOnce() {
        List<String> log = new ArrayList<>();
        UnitQueue q = new UnitQueue();
        Fake far = new Fake("far", 2, log);
        far.ready = false;
        q.add(0, far);
        tick(q, WorkClock.counting(MS), 30 * MS, UnitQueue.GIVE_UP_TICKS - 1);
        assertEquals(List.of(), log);
        tick(q, WorkClock.counting(MS), 30 * MS, UnitQueue.GIVE_UP_TICKS);
        assertEquals(List.of("far:end"), log);
        assertEquals(1, far.ended);
        assertTrue(q.isEmpty());
    }

    /** A job enqueued by another job (an explosion invokes handlers) joins the end and runs in the same pass. */
    @Test
    void jobAddedFromJobRunsInSameTick() {
        List<String> log = new ArrayList<>();
        UnitQueue q = new UnitQueue();
        Fake first = new Fake("first", 1, log);
        first.onStep = () -> q.add(0, new Fake("child", 1, log));
        q.add(0, first);
        tick(q, WorkClock.counting(MS), 30 * MS, 0);
        assertEquals(List.of("first:1", "first:end", "child:1", "child:end"), log);
    }

    /** Shutdown: all runnable work finishes without a deadline; unready jobs and jobs waiting for them are released. */
    @Test
    void finishDoesReadyWorkAndReleasesTheRest() {
        List<String> log = new ArrayList<>();
        UnitQueue q = new UnitQueue();
        Fake big = new Fake("big", 500, log);
        Fake far = new Fake("far", 2, log);
        far.ready = false;
        Fake after = new Fake("after", 1, log);
        after.waitsFor = far;
        q.add(0, big);
        q.add(0, far);
        q.add(0, after);
        tick(q, WorkClock.counting(MS), 3 * MS, 0);
        q.finish(null, 1);
        assertTrue(q.isEmpty());
        assertEquals(0, big.units);
        assertEquals(1, big.ended);
        assertEquals(1, far.ended);
        assertEquals(1, after.ended);
        assertTrue(!log.contains("far:2") && !log.contains("after:1"), log.toString());
    }

    /** An expensive work-unit kind does not start near the deadline after cheap units: each kind has its own estimate. */
    @Test
    void kindsHaveOwnEstimates() {
        long[] now = {0};
        WorkClock clock = WorkClock.decaying(() -> now[0], 1);
        clock.start(100 * MS);
        long b = clock.begin();
        now[0] += 40 * MS;
        clock.end(b, 1);
        for (int i = 0; i < 20; i++) {
            b = clock.begin();
            now[0] += MS / 10;
            clock.end(b, 2);
        }
        clock.start(30 * MS);
        b = clock.begin();
        now[0] += MS / 10;
        clock.end(b, 2);
        assertTrue(clock.canStart(2), "a cheap batch fits");
        assertTrue(!clock.canStart(1), "expensive rays (40 ms) do not fit a 30 ms deadline");
    }

    @Test
    void oldSweepDoesNotHoldNewFrontUntilItsEnd() {
        List<String> log = new ArrayList<>();
        UnitQueue queue = new UnitQueue();
        Fake ignition = new Fake("ignition", 1000, log);
        Fake shatter = new Fake("shatter", 1000, log);
        queue.add(0, ignition);
        queue.add(0, shatter);
        WorkClock clock = WorkClock.counting(MS);
        tick(queue, clock, 4 * MS, 0);
        Fake front = new Fake("front", 10, log);
        queue.add(1, front);
        for (int t = 1; t <= 12 && front.ended == 0; t++) tick(queue, clock, 4 * MS, t);
        assertEquals(1, front.ended, "the main front gets a turn every round");
        assertEquals(0, ignition.ended);
        assertEquals(0, shatter.ended);
        queue.finish(null, 13);
        assertTrue(queue.isEmpty());
        assertEquals(1, ignition.ended);
        assertEquals(1, shatter.ended);
    }

    @Test
    void continuationReadinessIsCachedPerWindowIncludingSkippedJobs() {
        List<String> log = new ArrayList<>();
        UnitQueue queue = new UnitQueue();
        Fake waiting = new Fake("waiting", 1000, log), active = new Fake("active", 1000, log);
        queue.add(0, waiting);
        queue.add(0, active);
        WorkClock clock = WorkClock.counting(MS);
        tick(queue, clock, 3 * MS, 0); // both heads become continuations
        waiting.ready = false;
        waiting.readyCalls = waiting.blockedCalls = active.readyCalls = active.blockedCalls = 0;
        tick(queue, clock, 100 * MS, 1);
        assertEquals(1, waiting.readyCalls);
        assertEquals(1, waiting.blockedCalls);
        assertEquals(1, active.readyCalls);
        assertEquals(1, active.blockedCalls);
        assertTrue(active.units < 950, "many rounds ran inside one window");
        waiting.ready = true;
        tick(queue, clock, 100 * MS, 1); // Post can be a new window in the same world tick
        assertEquals(2, waiting.readyCalls);
        assertEquals(2, waiting.blockedCalls);
        assertTrue(waiting.units < 999, "the next window observes newly ready terrain");
    }

    @Test
    void dependencyIntroducedByHeadIsCheckedBeforeContinuationAndRefreshedNextWindow() {
        List<String> log = new ArrayList<>();
        UnitQueue queue = new UnitQueue();
        Fake blocks = new Fake("blocks", 3, log), rays = new Fake("rays", 2, log);
        blocks.onStep = () -> blocks.waitsFor = rays;
        queue.add(0, blocks);
        queue.add(0, rays);
        WorkClock clock = WorkClock.counting(MS);
        tick(queue, clock, 100 * MS, 0);
        assertEquals(1, rays.ended);
        assertEquals(2, blocks.units, "newly introduced dependency blocks the continuation this window");
        assertEquals(2, blocks.blockedCalls, "one head check and one continuation check");
        tick(queue, clock, 100 * MS, 0);
        assertEquals(1, blocks.ended);
        assertEquals(3, blocks.blockedCalls, "one more check in the new window");
    }
}
