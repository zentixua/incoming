package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SmokePreparationTest {
    private static final SmokeGrid.Sink DISCARD = (x,y,z,ax,ay,az,sh,sv,s,d,w,r,g,b,k,rise) -> {};

    private static class Source implements SmokeGrid.Source {
        final double x;
        int calls;
        double age;
        boolean pause;
        Source(double x) { this.x = x; }
        @Override public boolean bounds(double t, SmokeBounds out) { out.puff(x, 0, 0, 1, 1); return true; }
        @Override public boolean emit(double seconds, SmokeGrid.Sink out) {
            calls++; age = seconds;
            out.blob(x, seconds, 0, 1, 1, 1, 0, 0, 1, 1, 1, 300, 0);
            if (pause) SmokePreparation.checkpoint();
            return true;
        }
    }

    @Test void invisibleHistoryDoesNotEmitAndReentryUsesAbsoluteAge() {
        for (int hidden : new int[]{0, 100, 10_000}) {
            var prep = new SmokePreparation();
            var sources = new ArrayList<SmokeGrid.Source>();
            var shown = new Source(0); sources.add(shown);
            for (int i = 0; i < hidden; i++) sources.add(new Source(1000));
            for (int frame = 0; frame < 10; frame++) prep.draw(sources, Set.of(), frame, 0, 0, 0,
                    b -> b.minX < 100, (s,t) -> DISCARD, () -> 0);
            assertEquals(10, shown.calls);
            assertEquals(10, sources.stream().mapToInt(s -> ((Source)s).calls).sum());
            prep.draw(sources, Set.of(), 1800, 0, 0, 0, b -> true, (s,t) -> DISCARD, () -> 0);
            assertTrue(sources.stream().filter(s -> ((Source)s).age == 1800).count() > 0,
                    "newly visible sources are sampled at current time, never replayed in steps");
        }
    }

    @Test void visibleWorkIsBoundedPrioritizedAndFair() {
        var prep = new SmokePreparation();
        var sources = new ArrayList<SmokeGrid.Source>();
        for (int i = 0; i < 1000; i++) sources.add(new Source(i * 100));
        for (int frame = 0; frame < 64; frame++) {
            int before = sources.stream().mapToInt(s -> ((Source)s).calls).sum();
            prep.draw(sources, Set.of(), frame, 0, 0, 0, b -> true, (s,t) -> DISCARD, () -> 0);
            assertEquals(SmokePreparation.UPDATES, sources.stream().mapToInt(s -> ((Source)s).calls).sum() - before);
        }
        for (int i = 0; i < SmokePreparation.SOURCES; i++) assertTrue(((Source)sources.get(i)).calls > 0);
        for (int i = SmokePreparation.SOURCES; i < sources.size(); i++) assertEquals(0, ((Source)sources.get(i)).calls);
    }

    @Test void interruptedPreparationKeepsOnlyPreviousCompleteSample() {
        var prep = new SmokePreparation(); var source = new Source(0);
        List<SmokeGrid.Source> sources = new ArrayList<>(List.of(source));
        double[] seen = {-1};
        SmokePreparation.Draw draw = (s,t) -> (x,y,z,ax,ay,az,sh,sv,soot,d,w,r,g,b,k,rise) -> seen[0] = y;
        prep.draw(sources, Set.of(), 10, 0, 0, 0, b -> true, draw, () -> 0);
        assertEquals(10, seen[0]); source.pause = true;
        int[] checks = {0};
        prep.draw(sources, Set.of(), 10.1, 0, 0, 0, b -> true, draw,
                () -> ++checks[0] < 3 ? 0 : SmokePreparation.NANOS);
        assertEquals(10, seen[0], "an interrupted source must not show a partial cloud");
        prep.draw(sources, Set.of(), 11, 0, 0, 0, b -> false, draw, () -> 0);
        seen[0] = -1; checks[0] = 0;
        prep.draw(sources, Set.of(), 100, 0, 0, 0, b -> true, draw,
                () -> ++checks[0] < 3 ? 0 : SmokePreparation.NANOS);
        assertEquals(-1, seen[0], "expired snapshots must not be replayed on return");
        source.pause = false;
        prep.draw(sources, Set.of(), 101, 0, 0, 0, b -> true, draw, () -> 0);
        assertEquals(101, seen[0]);
    }

    @Test void returnedSnapshotExpiresUntilItsFirstFreshUpdate() {
        var prep = new SmokePreparation(); var source = new Source(0);
        List<SmokeGrid.Source> sources = new ArrayList<>(List.of(source));
        double[] seen = {-1};
        SmokePreparation.Draw draw = (s,t) -> { seen[0] = t; return DISCARD; };
        prep.draw(sources, Set.of(), 10, 0, 0, 0, b -> true, draw, () -> 0);
        prep.draw(sources, Set.of(), 10.1, 0, 0, 0, b -> false, draw, () -> 0);
        source.pause = true;
        for (double t : new double[]{10.2, 10.3}) {
            seen[0] = -1;
            int[] checks = {0};
            prep.draw(sources, Set.of(), t, 0, 0, 0, b -> true, draw,
                    () -> ++checks[0] < 3 ? 0 : SmokePreparation.NANOS);
            assertEquals(t < 10.25 ? 10 : -1, seen[0], "a short return must not make the pre-hide sample immortal");
        }
        source.pause = false;
        prep.draw(sources, Set.of(), 10.4, 0, 0, 0, b -> true, draw, () -> 0);
        assertEquals(10.4, seen[0]);
        source.pause = true;
        int[] checks = {0};
        prep.draw(sources, Set.of(), 11, 0, 0, 0, b -> true, draw,
                () -> ++checks[0] < 3 ? 0 : SmokePreparation.NANOS);
        assertEquals(10.4, seen[0], "a fresh continuously visible snapshot must not expire");
    }

    @Test void onePausedCloudDoesNotAbortOtherUpdates() {
        long[] nanos = {0};
        var slow = new Source(0) {
            @Override public boolean emit(double t, SmokeGrid.Sink out) {
                nanos[0] += SmokePreparation.SOURCE_NANOS;
                SmokePreparation.checkpoint();
                fail("source must yield its slice"); return true;
            }
        };
        var other = new Source(20);
        var prep = new SmokePreparation();
        prep.draw(new ArrayList<>(List.of(slow, other)), Set.of(), 0, 0, 0, 0, b -> true,
                (s,t) -> DISCARD, () -> nanos[0]);
        assertEquals(1, other.calls, "a paused foreground cloud must not stop the queue");
    }

    @Test void lastEmitReservesItsCostBeforeStarting() {
        long[] nanos = {0};
        var sources = new ArrayList<SmokeGrid.Source>();
        for (int i = 0; i < 128; i++) sources.add(new Source(0) {
            @Override public boolean emit(double t, SmokeGrid.Sink out) {
                nanos[0] += 150_000;
                return super.emit(t, out);
            }
        });
        var prep = new SmokePreparation();
        for (int f = 0; f < 100; f++) {
            long before = nanos[0];
            prep.draw(sources, Set.of(), f / 20.0, 0, 0, 0, b -> true, (s,t) -> DISCARD, () -> nanos[0]);
            assertTrue(nanos[0] - before <= SmokePreparation.NANOS, "last emit overran the frame budget");
        }
    }

    @Test void hiddenMaintenanceIsBoundedFairAndNeverEmits() {
        long[] nanos = {0};
        int[] advanced = new int[64];
        var sources = new ArrayList<SmokeGrid.Source>();
        for (int i = 0; i < advanced.length; i++) {
            int index = i;
            sources.add(new Source(1000) {
                @Override public void maintain(double seconds) {
                    advanced[index]++;
                    nanos[0] += SmokePreparation.MAINTENANCE_SLICE / 2;
                    SmokePreparation.checkpoint();
                }
            });
        }
        var prep = new SmokePreparation();
        for (int f = 0; f < 32; f++) {
            long before = nanos[0];
            prep.draw(sources, Set.of(), 10 + f / 20.0, 0, 0, 0, b -> false,
                    (s,t) -> { fail("hidden cloud was drawn"); return DISCARD; }, () -> nanos[0]);
            assertTrue(nanos[0] - before <= SmokePreparation.MAINTENANCE_NANOS);
        }
        for (int count : advanced) assertTrue(count > 0, "hidden maintenance starved a cloud");
        for (var source : sources) assertEquals(0, ((Source)source).calls);
    }

    @Test void returnedAndYoungCloudsPrecedeFreshForegroundSnapshots() {
        var prep = new SmokePreparation();
        var sources = new ArrayList<SmokeGrid.Source>();
        for (int i = 0; i < 40; i++) sources.add(new Source(i < 20 ? 0 : 100));
        for (int f = 0; f < 20; f++) prep.draw(sources, Set.of(), f / 60.0, 0, 0, 0,
                b -> true, (s,t) -> DISCARD, () -> 0);
        for (int f = 0; f < 20; f++) prep.draw(sources, Set.of(), 120 + f / 60.0, 0, 0, 0,
                b -> b.minX < 50, (s,t) -> DISCARD, () -> 0);
        var young = new Source(200) { @Override public double age(double t) { return 0; } };
        sources.add(young);
        prep.draw(sources, Set.of(), 120 + 20 / 60.0, 0, 0, 0, b -> true, (s,t) -> DISCARD, () -> 0);
        assertEquals(1, young.calls);
        assertEquals(SmokePreparation.UPDATES - 1, sources.subList(20, 40).stream()
                .filter(s -> ((Source)s).age == 120 + 20 / 60.0).count());
    }

    @Test void capacityBoundaryDoesNotFlickerWithSmallCameraMotion() {
        var prep = new SmokePreparation();
        var sources = new ArrayList<SmokeGrid.Source>();
        for (int i = 0; i < SmokePreparation.SOURCES; i++) sources.add(new Source(-100));
        var excluded = new Source(100); sources.add(excluded);
        for (int f = 0; f < 100; f++) prep.draw(sources, Set.of(), f / 60.0, f % 2 == 0 ? -0.1 : 0.1, 0, 0,
                b -> true, (s,t) -> DISCARD, () -> 0);
        assertEquals(0, excluded.calls);
        // A meaningful move still replaces less important sources.
        prep.draw(sources, Set.of(), 2, 99, 0, 0, b -> true, (s,t) -> DISCARD, () -> 0);
        assertEquals(1, excluded.calls);
    }

    @Test void broadDistantMarginsDoNotDisplaceNearbyClouds() {
        var prep = new SmokePreparation();
        var sources = new ArrayList<SmokeGrid.Source>();
        for (int i = 0; i < SmokePreparation.SOURCES; i++) sources.add(new Source(3000) {
            @Override public boolean bounds(double t, SmokeBounds out) { out.puff(x, 0, 0, 400, 400); return true; }
        });
        var near = new Source(0); sources.add(near);
        for (int frame = 0; frame < 32; frame++) prep.draw(sources, Set.of(), frame, 0, 0, 0,
                b -> true, (s,t) -> DISCARD, () -> 0);
        assertTrue(near.calls > 0, "an old cloud's empty conservative margin must not monopolize the budget");
    }

    @Test void refreshPriorityDoesNotChangePuffMergeOrder() {
        var prep = new SmokePreparation(); var far = new Source(100); var near = new Source(0);
        var legacy = new Source(50) {
            @Override public boolean bounds(double t, SmokeBounds out) { return false; }
        };
        List<SmokeGrid.Source> sources = new ArrayList<>(List.of(far, legacy, near));
        var seen = new ArrayList<SmokeGrid.Source>();
        for (int frame = 0; frame < 3; frame++) {
            seen.clear();
            prep.draw(sources, Set.of(), frame, 0, 0, 0, b -> true, (s,t) -> { seen.add(s); return DISCARD; }, () -> 0);
            assertEquals(sources, seen);
        }
    }
    /** A fire plume or launch cloud: no bounds, but on the preparation budget (LEAN 27.10: they emitted every frame outside it). */
    private static class Plume extends Source {
        final long[] nanos;
        int drawnFrame = -1;
        Plume(long[] nanos) { super(0); this.nanos = nanos; }
        @Override public boolean bounds(double t, SmokeBounds out) { return false; }
        @Override public boolean budgeted() { return true; }
        @Override public boolean emit(double seconds, SmokeGrid.Sink out) { nanos[0] += 20_000; return super.emit(seconds, out); }
    }

    /**
     * Plumes and launch clouds share the frame's 2 ms with blast clouds (clockless: emits advance a counting clock): the
     * frame never runs over, the plumes take none of the nearest-cloud places, every source is refreshed oldest sample
     * first within a bounded number of frames, a new plume is drawn from its first frame, and a non-budgeted (nuclear)
     * source keeps emitting every frame.
     */
    @Test void plumesShareTheBudgetWithoutDisplacingClouds() {
        long[] nanos = {0};
        var sources = new ArrayList<SmokeGrid.Source>();
        for (int i = 0; i < SmokePreparation.SOURCES; i++) sources.add(new Source(i) {
            @Override public boolean emit(double t, SmokeGrid.Sink out) { nanos[0] += 100_000; return super.emit(t, out); }
        });
        var plumes = new ArrayList<Plume>();
        for (int i = 0; i < 60; i++) plumes.add(new Plume(nanos));
        sources.addAll(plumes);
        var nuclear = new Source(5) { @Override public boolean bounds(double t, SmokeBounds out) { return false; } };
        sources.add(nuclear);
        var prep = new SmokePreparation();
        java.util.Map<SmokeGrid.Source, Double> sampled = new java.util.IdentityHashMap<>();
        var late = new Plume(nanos);
        for (int f = 0; f < 80; f++) {
            if (f == 40) sources.add(late);
            int frame = f;
            long before = nanos[0];
            var drawn = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<SmokeGrid.Source, Boolean>());
            prep.draw(sources, Set.of(), f / 20.0, 0, 0, 0, b -> true, (s, t) -> {
                drawn.add(s);
                sampled.put(s, t);
                if (s instanceof Plume plume && plume.drawnFrame < 0) plume.drawnFrame = frame;
                return DISCARD;
            }, () -> nanos[0]);
            assertTrue(nanos[0] - before <= SmokePreparation.NANOS, "preparation overran the frame budget");
            assertEquals(f + 1, nuclear.calls, "a non-budgeted source emits every frame");
            if (f < 12) continue;
            assertEquals(SmokePreparation.SOURCES + 61 + (f >= 40 ? 1 : 0), drawn.size(), "every cloud and plume is drawn");
            for (var e : sampled.entrySet()) assertTrue(f / 20.0 - e.getValue() <= 12 / 20.0 + 1e-9, "a snapshot fell behind");
        }
        assertEquals(40, late.drawnFrame, "a new plume is drawn from its first frame");
    }
}
