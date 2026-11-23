package ua.zentix.incoming.work;

import net.minecraft.server.level.ServerLevel;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.world.WorkClock;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * One lane's queue in one world ({@link WorkScheduler}); each job is a chain of short units.
 * Heads run their first unit in enqueue order before any continuations. Continuations take one unit per turn,
 * retaining their rotation between windows and ticks, so an old fire sweep cannot monopolize a new blast front.
 * <p>
 * A job that cannot run waits up to {@link #GIVE_UP_TICKS} after its last unit (or its enqueue); jobs waiting on
 * dependencies are skipped. Continuation readiness and dependencies are sampled once per work window, after heads have
 * run: a first unit can introduce a dependency. A later window, including Post in the same world tick, refreshes that
 * sample. Jobs are not saved; shutdown drains available work to completion through {@link #finish(ServerLevel)} and
 * releases only jobs that cannot run.
 */
public final class UnitQueue {
    /** A chain of units, continuing while {@link #step} returns true. */
    public interface Job {
        /** Is the area ready for a unit? */
        default boolean ready(ServerLevel level) {
            return true;
        }

        /** A unit can start an asynchronous phase whose readiness changes within this work window. */
        default boolean dynamicReadiness() { return false; }

        /** Is this job waiting for another job to finish? */
        default boolean blocked() {
            return false;
        }

        /** Next unit kind, with its own clock estimate ({@link WorkClock#canStart(int)}). */
        default int unitKind() {
            return 0;
        }

        /** Execute one unit; false means the job is complete. */
        boolean step(ServerLevel level);

        /**
         * Shutdown drain ({@link UnitQueue#finish}): world time no longer advances, so a unit that waits for a later
         * world tick does that work now. A step returning true without progress would repeat forever in finish's window.
         */
        default void drain() {}

        /** Release held resources, including tickets, when completed, cancelled or failed. */
        default void end(ServerLevel level) {}

        /** Job description for logs. */
        String describe();
    }

    /** Maximum wait without a unit (area readiness, an outstanding fence), in ticks, before cancellation. */
    public static final int GIVE_UP_TICKS = 6000;

    private static final class Entry {
        final Job job;
        final long added;
        /** World time of the last unit, or of the enqueue: {@link #GIVE_UP_TICKS} counts inactivity from it. */
        long progressed;
        long checkedWindow = -1;
        Outcome availability;

        Entry(Job job, long added) {
            this.job = job;
            this.added = added;
            this.progressed = added;
        }
    }

    private enum Outcome { SKIPPED, OUT_OF_TIME, MORE, DONE }

    /** Jobs whose first unit has not run. */
    private final List<Entry> heads = new ArrayList<>();
    /** Started jobs with remaining units. */
    private final Deque<Entry> tails = new ArrayDeque<>();
    private long window;
    /** Units executed so far: {@link #finish} drains while windows make progress. */
    private long units;
    /** Inside {@link #finish}: jobs enqueued meanwhile drain too. */
    private boolean draining;

    public void add(ServerLevel level, Job job) {
        add(level.getGameTime(), job);
    }

    /** Enqueue with an explicit world time for tests without a world. */
    public void add(long now, Job job) {
        if (draining) job.drain();
        heads.add(new Entry(job, now));
    }

    public boolean isEmpty() {
        return heads.isEmpty() && tails.isEmpty();
    }

    public int size() {
        return heads.size() + tails.size();
    }

    /** Age of the oldest queued job in ticks. */
    public long oldestWaitTicks(ServerLevel level) {
        long oldest = Long.MAX_VALUE;
        if (!heads.isEmpty()) oldest = heads.getFirst().added;
        for (Entry entry : tails) oldest = Math.min(oldest, entry.added);
        return oldest == Long.MAX_VALUE ? 0 : level.getGameTime() - oldest;
    }

    /** Work until the clock stops it: new heads first, then continuations. */
    public void work(ServerLevel level, WorkClock clock) {
        work(level, level.getGameTime(), clock);
    }

    /**
     * Explicit world time for tests; level is passed through to jobs. A job can enqueue another job through a mod
     * callback. That head joins the end of the list and can run in the same head pass if time permits.
     */
    public void work(ServerLevel level, long now, WorkClock clock) {
        long usedBefore = clock.usedThisTickNanos();
        long began = clock.begin();
        try {
            workWindow(level, now, clock);
        } finally {
            clock.accountWindow(began, usedBefore);
        }
    }

    private void workWindow(ServerLevel level, long now, WorkClock clock) {
        long currentWindow = ++window;
        for (int i = 0; i < heads.size(); ) {
            Entry e = heads.get(i);
            Outcome o = run(level, now, e, clock, currentWindow, false);
            if (o == Outcome.OUT_OF_TIME) return;
            if (o == Outcome.SKIPPED) {
                i++;
                continue;
            }
            heads.remove(i);
            if (o == Outcome.MORE) tails.add(e);
            else finish(level, e);
        }
        int missed = 0;
        while (!tails.isEmpty() && missed < tails.size()) {
            if (clock.workedThisTick() && clock.overdue()) return;
            Entry e = tails.removeFirst();
            Outcome o = run(level, now, e, clock, currentWindow, true);
            if (o == Outcome.DONE) finish(level, e);
            else tails.addLast(e);
            // A full circle without progress: all wait, or none of their estimates fits the remaining time.
            if (o == Outcome.SKIPPED || o == Outcome.OUT_OF_TIME) missed++;
            else missed = 0;
        }
    }

    /** One unit; continuation readiness and dependencies are sampled once per work window. */
    private Outcome run(ServerLevel level, long now, Entry e, WorkClock clock, long window, boolean continuation) {
        try {
            if (!continuation || e.checkedWindow != window || e.job.dynamicReadiness()) {
                e.availability = availability(level, now, e);
                // The first step can introduce a dependency (e.g. blast blocks waiting for sibling rays).
                // Sample again on entering the continuation queue, after all heads have had their turn.
                if (continuation) e.checkedWindow = window;
            }
            if (e.availability != Outcome.MORE) return e.availability;
            int kind = e.job.unitKind();
            if (!clock.canStart(kind)) return Outcome.OUT_OF_TIME;
            long c0 = clock.begin();
            units++;
            e.progressed = now;
            try {
                return e.job.step(level) ? Outcome.MORE : Outcome.DONE;
            } finally {
                clock.end(c0, kind);
            }
        } catch (RuntimeException ex) {
            Incoming.LOG.error("{} failed with an exception and was removed", e.job.describe(), ex);
            return Outcome.DONE;
        }
    }

    private static Outcome availability(ServerLevel level, long now, Entry e) {
        if (e.job.blocked()) return Outcome.SKIPPED;
        if (e.job.ready(level)) return Outcome.MORE;
        // Inactivity, not age: a job that ran units until a moment ago (a light repair waiting one tick for its fence)
        // has not failed to load its area.
        if (now - e.progressed < GIVE_UP_TICKS) return Outcome.SKIPPED;
        Incoming.LOG.warn("{} cancelled: not ready for {} ticks", e.job.describe(), GIVE_UP_TICKS);
        return Outcome.DONE;
    }

    private static void finish(ServerLevel level, Entry e) {
        try {
            e.job.end(level);
        } catch (RuntimeException ex) {
            Incoming.LOG.error("{}: release failed", e.job.describe(), ex);
        }
    }

    /** Drain window: the first available unit of each always runs, so a window without a unit means nothing can run. */
    private static final long DRAIN_WINDOW_NANOS = 1_000_000_000L;
    /** A drain running longer than this logs what it is still working on (the stop waits; started work is never dropped). */
    static final long SLOW_DRAIN_NANOS = 30_000_000_000L;

    /**
     * On shutdown, complete all available work. Unready areas are still never read; jobs that remain unavailable or depend
     * on unavailable jobs are released. Started work is never cut short: jobs are not saved, so a job released midway
     * would leave its edits half applied.
     */
    public void finish(ServerLevel level) {
        finish(level, level.getGameTime());
    }

    /** Explicit world time for tests without a world. */
    public void finish(ServerLevel level, long now) {
        finish(level, now, new WorkClock());
    }

    /**
     * Same with the clock (tests). Windows repeat while units run: a unit that enqueues a ready successor is progress
     * even when the queue length stays the same. World time stops during the drain, so each job is told to do now what
     * waits for a later tick ({@link Job#drain}); a step returning true without progress would otherwise repeat forever.
     * Returns whether the drain logged as slow.
     */
    boolean finish(ServerLevel level, long now, WorkClock clock) {
        draining = true;
        boolean slow = false;
        try {
            for (Entry e : heads) e.job.drain();
            for (Entry e : tails) e.job.drain();
            long began = clock.begin();
            while (!isEmpty()) {
                long before = units;
                clock.start(DRAIN_WINDOW_NANOS);
                work(level, now, clock);
                if (units == before) break;
                if (!slow && clock.begin() - began > SLOW_DRAIN_NANOS) {
                    slow = true;
                    List<String> left = new ArrayList<>();
                    for (Entry e : heads) left.add(e.job.describe());
                    for (Entry e : tails) left.add(e.job.describe());
                    Incoming.LOG.warn("Shutdown: impact work still draining after {} s, finishing: {}", SLOW_DRAIN_NANOS / 1_000_000_000L, left);
                }
            }
        } finally {
            draining = false;
        }
        clear(level);
        return slow;
    }

    /** Release all jobs without doing work; jobs are not saved. */
    public void clear(ServerLevel level) {
        List<Entry> all = new ArrayList<>(heads);
        all.addAll(tails);
        heads.clear();
        tails.clear();
        for (Entry e : all) finish(level, e);
    }
}
