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
            assertEquals(1800, shown.age, "history is sampled at current time, never replayed in steps");
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
        prep.draw(sources, Set.of(), 100, 0, 0, 0, b -> true, draw,
                () -> ++checks[0] < 3 ? 0 : SmokePreparation.NANOS);
        assertEquals(10, seen[0], "an interrupted source must not show a partial cloud");
        source.pause = false;
        prep.draw(sources, Set.of(), 101, 0, 0, 0, b -> true, draw, () -> 0);
        assertEquals(101, seen[0]);
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
}
