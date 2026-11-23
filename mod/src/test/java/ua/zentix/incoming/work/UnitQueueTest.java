package ua.zentix.incoming.work;

import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.util.StopDrain;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Work-unit queue without a world: jobs receive {@code null} for the world and world time as a number. */
class UnitQueueTest {
    private static final long MS = 1_000_000L;

    @Test
    void asynchronousContinuationResamplesReadiness() {
        UnitQueue queue = new UnitQueue();
        boolean[] ready = {true};
        int[] steps = {0};
        queue.add(0, new UnitQueue.Job() {
            @Override public boolean dynamicReadiness() { return true; }
            @Override public boolean ready(ServerLevel level) { return ready[0]; }
            @Override public boolean step(ServerLevel level) {
                steps[0]++;
                if (steps[0] == 2) ready[0] = false;
                return steps[0] < 3;
            }
            @Override public String describe() { return "async continuation"; }
        });
        tick(queue, WorkClock.counting(MS), 100 * MS, 0);
        assertEquals(2, steps[0], "a continuation must not join its pending future in the same window");
        ready[0] = true;
        tick(queue, WorkClock.counting(MS), 100 * MS, 1);
        assertTrue(queue.isEmpty());
    }

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
        drain(q, 1);
        assertTrue(q.isEmpty());
        assertEquals(0, big.units);
        assertEquals(1, big.ended);
        assertEquals(1, far.ended);
        assertEquals(1, after.ended);
        assertTrue(!log.contains("far:2") && !log.contains("after:1"), log.toString());
    }

    /**
     * World time stops during the shutdown drain: a job waiting for a later tick (an explosion front on its way) is told to
     * drain, including one enqueued during the drain, and finishes instead of repeating a step without progress.
     */
    @Test
    void finishDrainsJobsWaitingForLaterTicks() {
        UnitQueue q = new UnitQueue();
        Waiting child = new Waiting(null, 2);
        Waiting first = new Waiting(() -> q.add(0, child), 3);
        q.add(0, first);
        q.finish(null, 0, WorkClock.counting(MS), 1000 * MS);
        assertTrue(q.isEmpty());
        assertEquals(3, first.done, "queued job did not drain");
        assertEquals(2, child.done, "job enqueued during the drain did not drain");
        assertEquals(1, first.ended);
        assertEquals(1, child.ended);
    }

    /** A job that never finishes (a wait ignoring drain) is released at the limit instead of holding shutdown forever. */
    @Test
    void finishReleasesAJobThatNeverFinishesAtTheLimit() {
        UnitQueue q = new UnitQueue();
        int[] steps = {0}, ended = {0};
        q.add(0, new UnitQueue.Job() {
            @Override public boolean step(ServerLevel level) { steps[0]++; return true; }
            @Override public void end(ServerLevel level) { ended[0]++; }
            @Override public String describe() { return "endless"; }
        });
        q.finish(null, 0, WorkClock.counting(MS), 100 * MS);
        assertTrue(q.isEmpty());
        assertEquals(1, ended[0]);
        assertTrue(steps[0] >= 98 && steps[0] <= 101, "about one 1 ms unit per ms of the limit: " + steps[0]);
    }

    /**
     * Save and Quit while a continuation is about to hand over to a successor (a collapse's last snapshot unit enqueues its
     * fall): the queue length stays 1 across that window, yet the successor is ready and runs before the drain ends.
     */
    @Test
    void finishRunsASuccessorEnqueuedByTheLastContinuation() {
        UnitQueue queue = new UnitQueue();
        int[] parent = {0}, child = {0}, released = {0};
        queue.add(0, new Named() {
            @Override public boolean step(ServerLevel ignored) {
                if (++parent[0] == 1) return true;
                queue.add(0, new Named() {
                    @Override public boolean step(ServerLevel ignored) { child[0]++; return false; }
                    @Override public void end(ServerLevel ignored) { released[0]++; }
                });
                return false;
            }
        });
        WorkClock ordinary = WorkClock.counting(MS);
        ordinary.start(MS);
        queue.work(null, 0, ordinary); // the parent is a continuation before the stop
        queue.finish(null, 0, WorkClock.counting(MS), StopDrain.LIMIT_NANOS);
        assertEquals(2, parent[0]);
        assertEquals(1, child[0], "ready successor released before its first unit");
        assertEquals(1, released[0]);
    }

    /**
     * A repair that ran a unit every tick for five minutes waits one tick for its light fence: inactivity, not age, cancels
     * a job, so its last unit still runs.
     */
    @Test
    void giveUpCountsTicksWithoutProgressNotAge() {
        UnitQueue queue = new UnitQueue();
        int[] steps = {0}, releases = {0};
        boolean[] pendingFence = {false};
        queue.add(0, new Named() {
            @Override public boolean dynamicReadiness() { return true; }
            @Override public boolean ready(ServerLevel ignored) { return !pendingFence[0]; }
            @Override public boolean step(ServerLevel ignored) { return ++steps[0] < UnitQueue.GIVE_UP_TICKS + 1; }
            @Override public void end(ServerLevel ignored) { releases[0]++; }
        });
        WorkClock clock = WorkClock.counting(MS);
        for (int t = 0; t < UnitQueue.GIVE_UP_TICKS; t++) {
            clock.start(MS);
            queue.work(null, t, clock);
        }
        assertEquals(UnitQueue.GIVE_UP_TICKS, steps[0]);
        pendingFence[0] = true;
        clock.start(MS);
        queue.work(null, UnitQueue.GIVE_UP_TICKS, clock);
        pendingFence[0] = false;
        clock.start(MS);
        queue.work(null, UnitQueue.GIVE_UP_TICKS + 1, clock);
        assertEquals(UnitQueue.GIVE_UP_TICKS + 1, steps[0], "one pending fence cancelled a progressing continuation");
        assertEquals(1, releases[0]);
        // still cancelled after the limit without a unit
        UnitQueue idle = new UnitQueue();
        int[] idleEnds = {0};
        boolean[] ready = {true};
        idle.add(0, new Named() {
            @Override public boolean dynamicReadiness() { return true; }
            @Override public boolean ready(ServerLevel ignored) { return ready[0]; }
            @Override public boolean step(ServerLevel ignored) { ready[0] = false; return true; }
            @Override public void end(ServerLevel ignored) { idleEnds[0]++; }
        });
        tick(idle, WorkClock.counting(MS), MS, 100);
        tick(idle, WorkClock.counting(MS), MS, 100 + UnitQueue.GIVE_UP_TICKS - 1);
        assertEquals(0, idleEnds[0]);
        tick(idle, WorkClock.counting(MS), MS, 100 + UnitQueue.GIVE_UP_TICKS);
        assertEquals(1, idleEnds[0]);
    }

    /**
     * Three worlds' impact queues share one stop deadline: 30,001 units of 1 ms each would take 90 s with a clock per world.
     * Work that did not fit is released (its {@code end} commits what it owes the world).
     */
    @Test
    void worldsShareOneStopDeadline() {
        long[] nanos = {0};
        int[] applied = {0}, ended = {0};
        for (int dimension = 0; dimension < 3; dimension++) {
            UnitQueue impacts = new UnitQueue();
            impacts.add(0, new Named() {
                int units;
                @Override public boolean step(ServerLevel ignored) {
                    nanos[0] += MS;
                    applied[0]++;
                    return ++units < 30_001;
                }
                @Override public void end(ServerLevel ignored) { ended[0]++; }
            });
            impacts.finish(null, 0, WorkClock.decaying(() -> nanos[0], 1), StopDrain.LIMIT_NANOS);
            assertTrue(impacts.isEmpty());
        }
        assertTrue(nanos[0] <= StopDrain.LIMIT_NANOS, "worlds took " + nanos[0] / 1e9 + " s of a " + StopDrain.LIMIT_NANOS / 1e9 + " s stop");
        assertTrue(applied[0] >= 29_999, "the deadline's time went unused: " + applied[0] + " units");
        assertEquals(3, ended[0], "every unfinished job is released through end");
    }

    private abstract static class Named implements UnitQueue.Job {
        @Override public String describe() { return "test job"; }
    }

    private static void drain(UnitQueue q, long now) {
        q.finish(null, now, WorkClock.counting(MS), Long.MAX_VALUE / 4);
    }

    /** Steps without progress until drained, then {@code units} units; runs {@code onFirst} in its first step. */
    private static final class Waiting implements UnitQueue.Job {
        final Runnable onFirst;
        final int units;
        boolean drained;
        int done, ended;

        Waiting(Runnable onFirst, int units) {
            this.onFirst = onFirst;
            this.units = units;
        }

        @Override public void drain() { drained = true; }
        @Override public boolean step(ServerLevel level) {
            if (!drained) return true;
            if (done == 0 && onFirst != null) onFirst.run();
            return ++done < units;
        }
        @Override public void end(ServerLevel level) { ended++; }
        @Override public String describe() { return "waiting"; }
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
        drain(queue, 13);
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

    @Test
    void windowIncludesReadinessAndReleaseWithoutInflatingUnitEstimate() {
        long[] now = {0};
        int[] completed = {0};
        WorkClock clock = WorkClock.decaying(() -> now[0], 1);
        clock.start(100 * MS);
        for (int i = 0; i < 2; i++) {
            UnitQueue queue = new UnitQueue();
            queue.add(0, costed(now, 3 * MS, 2 * MS, 4 * MS, completed));
            queue.work(null, 0, clock);
            assertEquals((i + 1) * 9 * MS, clock.usedThisTickNanos());
        }
        assertEquals(2, completed[0]);
        assertEquals(2, clock.units());
        assertEquals(2 * MS, clock.estimateNanos(), "readiness and release are not the next step's estimate");
    }

    @Test
    void earlyWindowOverheadIsNotRefundedToTheLateWindow() {
        long[] now = {0};
        int[] completed = {0};
        UnitQueue queue = new UnitQueue();
        for (int i = 0; i < 100; i++) queue.add(0, costed(now, MS, MS, 0, completed));
        WorkClock clock = WorkClock.decaying(() -> now[0], 1);
        clock.start(20 * MS);
        queue.work(null, 0, clock);
        // WorkScheduler shares this expenditure across its early and late windows.
        clock.resume(30 * MS - clock.usedThisTickNanos(), () -> true, 0);
        clock.allowLateStart();
        queue.work(null, 0, clock);
        assertTrue(now[0] <= 32 * MS, "30 ms cap plus one final 2 ms job, actual " + now[0]);
        assertEquals(now[0], clock.usedThisTickNanos(), "both windows include all queue work exactly once");
        clock.start(1000 * MS);
        queue.work(null, 1, clock);
        assertEquals(100, completed[0], "accounting changes pace, not eventual jobs or releases");
        assertTrue(queue.isEmpty());
    }

    private static UnitQueue.Job costed(long[] now, long ready, long step, long release, int[] completed) {
        return new UnitQueue.Job() {
            @Override
            public boolean ready(ServerLevel level) {
                now[0] += ready;
                return true;
            }

            @Override
            public boolean step(ServerLevel level) {
                now[0] += step;
                return false;
            }

            @Override
            public void end(ServerLevel level) {
                now[0] += release;
                completed[0]++;
            }

            @Override
            public String describe() {
                return "costed job";
            }
        };
    }
}
