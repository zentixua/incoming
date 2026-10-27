package ua.zentix.incoming.client.fx.smoke;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** A bounded preparation stage, separate from the puff/fill budget after emission. Render-thread only. */
public final class SmokePreparation {
    /** Presentation limits: retain a full hundred-strike scene, but do not scale preparation with an endless raid. */
    static final int SOURCES = 128, UPDATES = 16;
    static final long NANOS = 2_000_000;
    static final long SOURCE_NANOS = NANOS / 4;
    static final long MAINTENANCE_NANOS = NANOS / 4, MAINTENANCE_SLICE = MAINTENANCE_NANOS / 4;
    private static final long MIN_RESERVE = 50_000;
    /** Presentation: never draw an obsolete position when the camera returns. */
    static final double MAX_SAMPLE_AGE = 0.25;
    private static long deadline = Long.MAX_VALUE;
    private static LongSupplier clock = System::nanoTime;
    private static final Pause PAUSE = new Pause();

    private static final class Pause extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Pause() { super(null, null, false, false); }
    }

    /** Cooperative boundary between complete terrain-path steps, never inside a partially modified step. */
    static void checkpoint() {
        if (deadline != Long.MAX_VALUE && clock.getAsLong() >= deadline) throw PAUSE;
    }

    @FunctionalInterface
    public interface Draw {
        SmokeGrid.Sink sink(SmokeGrid.Source source, double sampledAt);
    }

    private static final class Entry {
        final SmokeGrid.Source source;
        final SmokeBounds bounds = new SmokeBounds();
        Snapshot ready, staging;
        double priority, sampledAt, age;
        long frame, maintained, estimate = SOURCE_NANOS;
        boolean hasSample, dead, legacy, selected, wasSelected, bounded, returning;
        Entry(SmokeGrid.Source source) { this.source = source; }
    }

    private final IdentityHashMap<SmokeGrid.Source, Entry> entries = new IdentityHashMap<>();
    private final ArrayList<Entry> selected = new ArrayList<>(SOURCES), unbounded = new ArrayList<>();
    private final ArrayList<Entry> maintenance = new ArrayList<>();
    private final java.util.PriorityQueue<Entry> nearest = new java.util.PriorityQueue<>(Comparator.comparingDouble(e -> e.priority));
    private long frame;

    public void reset() { entries.clear(); selected.clear(); nearest.clear(); maintenance.clear(); }

    public void draw(List<SmokeGrid.Source> sources, Set<SmokeGrid.Source> inGrid, double seconds,
                     double x, double y, double z, Predicate<SmokeBounds> visible, Draw draw) {
        draw(sources, inGrid, seconds, x, y, z, visible, draw, System::nanoTime);
    }

    void draw(List<SmokeGrid.Source> sources, Set<SmokeGrid.Source> inGrid, double seconds,
              double x, double y, double z, Predicate<SmokeBounds> visible, Draw draw, LongSupplier timer) {
        frame++;
        long frameDeadline = timer.getAsLong() + NANOS;
        selected.clear();
        unbounded.clear();
        nearest.clear();
        maintenance.clear();
        for (var it = sources.iterator(); it.hasNext();) {
            SmokeGrid.Source s = it.next();
            Entry e = entries.computeIfAbsent(s, Entry::new);
            e.frame = frame;
            boolean retained = e.selected;
            e.wasSelected = e.selected;
            e.legacy = e.selected = e.bounded = false;
            if (s.ended() || e.dead) { entries.remove(s); it.remove(); continue; }
            if (inGrid.contains(s)) continue;
            e.bounds.clear();
            boolean bounded = s.bounds(seconds, e.bounds);
            e.bounded = bounded;
            if (bounded && (e.bounds.empty() || !visible.test(e.bounds))) continue;
            if (!bounded && !s.budgeted()) {
                // Nuclear sources keep their established path and limits.
                e.legacy = true;
                continue;
            }
            e.age = s.age(seconds);
            if (!bounded) {
                // Fire plumes and launch clouds have no bounds to rank by distance: always drawn, as before, without
                // taking any of the nearest-cloud places; their refreshes share the frame budget.
                e.priority = Double.MAX_VALUE;
                unbounded.add(e);
                continue;
            }
            e.priority = e.bounds.priority(x, y, z);
            // Keep selected clouds across small camera movements at the capacity boundary.
            if (retained) e.priority *= 1.2;
            if (nearest.size() < SOURCES) nearest.add(e);
            else if (e.priority > nearest.element().priority) { nearest.poll(); nearest.add(e); }
        }
        entries.values().removeIf(e -> e.frame != frame);
        selected.addAll(nearest);
        selected.addAll(unbounded);
        for (Entry e : selected) {
            e.selected = true;
            // Only a return from exclusion invalidates an old snapshot. Continuously visible clouds must remain
            // present even when low FPS makes the refresh round longer than this threshold.
            if (!e.wasSelected) e.returning = true;
            if (seconds < e.sampledAt || e.returning && seconds - e.sampledAt > MAX_SAMPLE_AGE) e.hasSample = false;
        }
        for (Entry e : entries.values()) if (e.bounded && !e.selected && !e.dead && e.source.age(seconds) >= 1)
            maintenance.add(e);
        maintenance.sort(Comparator.comparingLong(e -> e.maintained));
        // Real sample age includes time off screen. Young rapidly changing clouds get the first slots;
        // otherwise refresh the oldest sample first, with contribution breaking ties.
        selected.sort(Comparator.<Entry>comparingInt(e -> e.age < 1 ? 1 : 0).reversed()
                .thenComparing(Comparator.comparingDouble((Entry e) -> e.hasSample ? seconds - e.sampledAt : Double.POSITIVE_INFINITY).reversed())
                .thenComparing(Comparator.comparingDouble((Entry e) -> e.priority).reversed()));
        clock = timer;
        long visibleDeadline = frameDeadline - (maintenance.isEmpty() ? 0 : MAINTENANCE_NANOS);
        try {
            int updates = 0;
            for (Entry e : selected) {
                // The refresh count limits blast clouds; plumes and launch clouds are limited by the time alone.
                if (e.bounded && updates >= UPDATES) continue;
                long now = timer.getAsLong();
                if (now + MIN_RESERVE > visibleDeadline) break;
                // Reserve the measured source cost (with margin), not just the instant of starting emit.
                if (now + e.estimate > visibleDeadline) continue;
                deadline = Math.min(visibleDeadline, now + SOURCE_NANOS);
                if (e.bounded) updates++;
                if (e.staging == null) e.staging = new Snapshot();
                e.staging.clear();
                try {
                    if (!e.source.emit(seconds, e.staging)) { e.dead = true; continue; }
                    Snapshot swap = e.ready; e.ready = e.staging; e.staging = swap;
                    e.sampledAt = seconds;
                    e.hasSample = true;
                    e.returning = false;
                    e.estimate = Math.clamp((timer.getAsLong() - now) * 5 / 4, MIN_RESERVE, SOURCE_NANOS);
                } catch (Pause ignored) {
                    // Completed path nodes remain cached. A partial cloud is never published.
                    // Its own slice is exhausted; other clouds may still finish within the frame budget.
                    e.estimate = SOURCE_NANOS;
                }
            }
            long maintenanceDeadline = Math.min(frameDeadline, timer.getAsLong() + MAINTENANCE_NANOS);
            int advanced = 0;
            for (Entry e : maintenance) {
                long now = timer.getAsLong();
                if (advanced >= UPDATES || now + MAINTENANCE_SLICE > maintenanceDeadline) break;
                deadline = now + MAINTENANCE_SLICE;
                e.maintained = frame;
                advanced++;
                try { e.source.maintain(seconds); }
                catch (Pause ignored) { /* Fine path nodes are resumable; rotate fairly through hidden sources. */ }
            }
        } finally {
            deadline = Long.MAX_VALUE;
            clock = System::nanoTime;
        }
        // Puff merging is order-sensitive. Scheduling refreshes must not reshuffle the cloud every frame.
        for (var it = sources.iterator(); it.hasNext();) {
            SmokeGrid.Source s = it.next();
            Entry e = entries.get(s);
            if (e.legacy) {
                if (!s.emit(seconds, draw.sink(s, seconds))) { entries.remove(s); it.remove(); }
            } else if (e.selected && e.hasSample && !e.dead && seconds >= e.sampledAt)
                e.ready.replay(draw.sink(s, e.sampledAt));
        }
    }

    /** Reusable raw model output. Camera-dependent selection, lighting and merging still run in the current frame. */
    private static final class Snapshot implements SmokeGrid.Sink {
        private static final int WIDTH = 23;
        private double[] data = new double[WIDTH * 128];
        private int count;
        private SmokeGrid.Look look = SmokeGrid.Look.SMOKE;
        private double frame;

        void clear() { count = 0; look = SmokeGrid.Look.SMOKE; frame = 0; }
        @Override public void look(SmokeGrid.Look value, double at) { look = value; frame = at; }

        private int next(boolean tube) {
            if ((count + 1) * WIDTH > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            int i = count++ * WIDTH;
            data[i] = tube ? 1 : 0; data[i + 1] = look.ordinal(); data[i + 2] = frame;
            return i + 3;
        }

        @Override
        public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                            double water, float dr, float dg, float db, double kelvin, double rise) {
            int i = next(false);
            data[i] = x; data[i+1] = y; data[i+2] = z; data[i+3] = ax; data[i+4] = ay; data[i+5] = az;
            data[i+6] = sh; data[i+7] = sv; data[i+8] = soot; data[i+9] = dust; data[i+10] = water;
            data[i+11] = dr; data[i+12] = dg; data[i+13] = db; data[i+14] = kelvin; data[i+15] = rise;
        }

        @Override
        public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA,
                         double bx, double by, double bz, double shb, double svb, double sootB, double dustB,
                         float dr, float dg, float db, double kelvin, double rise, double sigma0) {
            int i = next(true);
            data[i] = ax; data[i+1] = ay; data[i+2] = az; data[i+3] = sha; data[i+4] = sva; data[i+5] = sootA; data[i+6] = dustA;
            data[i+7] = bx; data[i+8] = by; data[i+9] = bz; data[i+10] = shb; data[i+11] = svb; data[i+12] = sootB; data[i+13] = dustB;
            data[i+14] = dr; data[i+15] = dg; data[i+16] = db; data[i+17] = kelvin; data[i+18] = rise; data[i+19] = sigma0;
        }

        private static final SmokeGrid.Look[] LOOKS = SmokeGrid.Look.values();
        void replay(SmokeGrid.Sink out) {
            for (int n = 0; n < count; n++) {
                int b = n * WIDTH, i = b + 3;
                out.look(LOOKS[(int) data[b+1]], data[b+2]);
                if (data[b] == 0) out.segment(data[i], data[i+1], data[i+2], data[i+3], data[i+4], data[i+5], data[i+6], data[i+7],
                        data[i+8], data[i+9], data[i+10], (float) data[i+11], (float) data[i+12], (float) data[i+13], data[i+14], data[i+15]);
                else out.tube(data[i], data[i+1], data[i+2], data[i+3], data[i+4], data[i+5], data[i+6], data[i+7], data[i+8], data[i+9],
                        data[i+10], data[i+11], data[i+12], data[i+13], (float) data[i+14], (float) data[i+15], (float) data[i+16],
                        data[i+17], data[i+18], data[i+19]);
            }
        }
    }
}
