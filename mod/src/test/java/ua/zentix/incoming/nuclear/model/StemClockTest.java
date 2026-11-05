package ua.zentix.incoming.nuclear.model;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Causal source timing, distinct from the uncalibrated cloud transport estimates. */
class StemClockTest {
    private static Detonation burst(double y, double hob, boolean surface, float scale, float wave, float visibility) {
        return new Detonation(1, new Vec3(0, 64 + hob * scale, 0), 64, y, surface, 0,
                0, 0, visibility, 7, scale, false, wave);
    }

    private static StemDust.Source source(Detonation d) {
        return new StemDust.Source(d.yieldKt(), d.hobMetres(), d.surface(), d.visibility(), d.front(), d.scale());
    }

    private static double arrival(Detonation d, double radius) {
        return d.arrivalTicks(d.slantOf(d.blocks(radius))) / (20 * d.scale());
    }

    private static DustModel.Column column(Detonation d, double r) {
        return DustModel.column(r, d.hobMetres(), d.yieldKt(), d.surface(), d.visibility(), DustModel.Surface.REFERENCE);
    }

    private static double firstSupply(Detonation d, StemDust.Source s) {
        double first = Double.POSITIVE_INFINITY;
        for (int i = 0; i < StemDust.RINGS; i++) {
            double r = s.radius(i);
            DustModel.Column c = column(d, r);
            if (c.mass() > 0 && c.stem() > 0) first = Math.min(first, arrival(d, r) + c.stemFrom());
        }
        assertTrue(Double.isFinite(first), "positive source required, not a vacuous empty-column check");
        return first;
    }

    @Test
    void actualGameAndRealClocksAtIdenticalGeometry() {
        for (boolean ground : new boolean[]{false, true}) {
            Detonation game = burst(1000, ground ? 0 : 2400, ground, 1, 0.25f, 20_000);
            Detonation real = game.withWave(1);
            StemDust.Source gs = source(game), rs = source(real);
            StemDust gd = StemDust.of(gs), rd = StemDust.of(rs);
            assertNotEquals(gs, rs);
            assertNotSame(gd, rd);
            assertTrue(gs.arrivalSeconds(gs.radius(23)) > rs.arrivalSeconds(rs.radius(23)) + 5);
            for (double t : new double[]{60, 90, 120}) {
                assertTrue(Math.abs(gd.lambda(t) - rd.lambda(t)) > 0.01, "both modes must use their source clock");
                assertEquals(discreteLambda(game, gs, (int) t), gd.lambda(t), 1e-7);
                assertEquals(discreteLambda(real, rs, (int) t), rd.lambda(t), 1e-7);
            }
            if (!ground) {
                double between = 0.5 * (firstSupply(game, gs) + firstSupply(real, rs));
                assertEquals(0, gd.lambda(between), 0, "GAME layer has not supplied any dust yet");
                assertTrue(rd.lambda(between) > 0, "REAL must already have a positive supplying layer");
            }
        }
    }

    @Test
    void scaleUsesSlantBlocksAndModelSecondsAtEverySource() {
        for (float scale : new float[]{0.25f, 1, 2.5f}) {
            for (boolean ground : new boolean[]{false, true}) {
                Detonation d = burst(15, ground ? 0 : 592, ground, scale, 0.25f, 20_000);
                StemDust.Source s = source(d);
                for (int i = 0; i < StemDust.RINGS; i++)
                    assertEquals(arrival(d, s.radius(i)), s.arrivalSeconds(s.radius(i)), 1e-12);
                assertEquals(arrival(d, 321.123), s.arrivalSeconds(321.123), 1e-12, "moving foot, not just cached annuli");
                assertEquals(discreteLambda(d, s, 60), StemDust.of(s).lambda(60), 1e-7);
            }
        }
    }

    @Test
    void interpolationDoesNotBorrowPositiveDustBeforeArrivalAndInflow() {
        for (boolean ground : new boolean[]{false, true}) {
            for (float scale : new float[]{0.25f, 1, 2.5f}) {
                Detonation d = burst(1000, ground ? 0 : 2400, ground, scale, 0.25f, 20_000);
                StemDust.Source s = source(d);
                StemDust dust = StemDust.of(s);
                double first = firstSupply(d, s);
                assertTrue(first > Math.floor(first), "exercise an onset inside an interpolation interval");
                for (double t : new double[]{Math.floor(first), Math.nextDown(first), first})
                    assertEquals(0, dust.lambda(t), 0, "no source yet at " + t + ", begins " + first);
                assertTrue(dust.lambda(first + 0.01) > 0, "positive source must enter after onset");
                assertEquals(0, dust.lambda(-1), 0);
            }
        }
    }

    @Test
    void completeValueIdentityIsSharedBySynchronousAndBackgroundCache() throws ReflectiveOperationException {
        Detonation game = burst(15, 592, false, 1, 0.25f, 20_000);
        StemDust.Source a = source(game), same = source(burst(15, 592, false, 1, 0.25f, 20_000));
        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        StemDust.ready(a);
        StemDust expected = StemDust.of(a);
        assertSame(expected, StemDust.ready(same));
        for (Detonation different : new Detonation[]{game.withWave(1), burst(15, 592, false, 2.5f, 0.25f, 20_000),
                burst(5, 592, false, 1, 0.25f, 20_000), burst(15, 600, false, 1, 0.25f, 20_000),
                burst(15, 592, true, 1, 0.25f, 20_000), burst(15, 592, false, 1, 0.25f, 5_000)}) {
            StemDust.Source b = source(different);
            assertNotEquals(a, b);
            StemDust.ready(b);
            assertNotSame(expected, StemDust.of(b));
            assertSame(expected, StemDust.ready(a));
        }
        for (int i = 0; i < 12; i++) StemDust.of(1, 500 + i, false, 20_000);
        var field = StemDust.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) field.get(null);
        synchronized (cache) { assertTrue(cache.size() <= StemDust.CACHE_LIMIT); }
        assertEquals(expected.lambda(60), StemDust.of(a).lambda(60), 0, "eviction/rebuild retains the full clock");
    }

    @Test
    void backgroundFailureBetweenStatusAndResultStaysOutOfTheFrame() throws ReflectiveOperationException {
        StemDust.Source source = source(burst(15, 592, false, 1, 0.25f, 20_000));
        var failure = new IllegalStateException("controlled completion between status observation and result read");
        CompletableFuture<StemDust> transition = new CompletableFuture<>() {
            @Override
            public boolean isDone() {
                boolean observed = super.isDone();
                completeExceptionally(failure);
                return observed;
            }

            @Override
            public boolean isCompletedExceptionally() {
                boolean observed = super.isCompletedExceptionally();
                completeExceptionally(failure);
                return observed;
            }
        };
        Map<StemDust.Source, CompletableFuture<StemDust>> cache = cache();
        synchronized (cache) {
            var previous = new LinkedHashMap<>(cache);
            try {
                cache.clear();
                cache.put(source, transition);
                assertNull(assertDoesNotThrow(() -> StemDust.ready(source)), "concurrent failure is still dust-free");
                assertTrue(transition.isCompletedExceptionally(), "the transition must actually occur");
                assertNull(assertDoesNotThrow(() -> StemDust.ready(source)), "terminal failure remains nonthrowing");
            } finally {
                cache.clear();
                cache.putAll(previous);
            }
        }
    }

    @Test
    void allPendingSlotsRemainBoundedWithoutEvictionOrResubmission() throws ReflectiveOperationException {
        Map<StemDust.Source, CompletableFuture<StemDust>> cache = cache();
        synchronized (cache) {
            var previous = new LinkedHashMap<>(cache);
            try {
                cache.clear();
                for (int i = 0; i < StemDust.CACHE_LIMIT; i++)
                    cache.put(source(burst(1, 500 + i, false, 1, 0.25f, 20_000)), new CompletableFuture<>());
                var pending = new LinkedHashMap<>(cache);
                StemDust.Source next = source(burst(15, 592, false, 1, 0.25f, 20_000));
                for (int frame = 0; frame < 20; frame++) assertNull(StemDust.ready(next));
                assertEquals(pending, cache, "all eight pending owners survive without another queued computation");
            } finally {
                cache.clear();
                cache.putAll(previous);
            }
        }
    }

    @Test
    void lateVisibleConsumerKeepsItsAdmittedResult() throws ReflectiveOperationException {
        Map<StemDust.Source, CompletableFuture<StemDust>> cache = cache();
        var previous = new LinkedHashMap<>(cache);
        var work = new ArrayDeque<Runnable>();
        Executor worker = work::add;
        var readers = new ArrayList<Supplier<StemDust>>();
        StringBuilder frames = new StringBuilder();
        try {
            cache.clear();
            for (int i = 0; i < 24; i++) {
                StemDust.Source s = source(burst(15, 592 + i, false, 1, 0.25f, 20_000));
                StemDust.Request request = StemDust.request(s, worker);
                readers.add(request::ready);
            }
            StemDust first = null;
            boolean stable = true;
            for (int frame = 0; frame < 12; frame++) {
                // Frame scans every Active before writing its four selected rows; the visible owner is last here.
                for (var reader : readers) reader.get();
                StemDust selected = readers.getLast().get();
                frames.append(selected == null ? '0' : '1');
                if (frame >= 3) {
                    stable &= selected != null && selected.lambda(60) > 0;
                    if (first == null) first = selected;
                    else stable &= selected == first;
                }
                assertTrue(cache.size() <= StemDust.CACHE_LIMIT);
                assertTrue(work.size() <= StemDust.CACHE_LIMIT);
                // A legal, deterministic completion schedule: all admitted native builders finish between frames.
                while (!work.isEmpty()) work.remove().run();
            }
            System.out.println("STEM_CONSUMER_24 " + frames);
            assertTrue(stable, "late visible owner must receive and retain its result: " + frames);
        } finally {
            while (!work.isEmpty()) work.remove().run();
            cache.clear();
            cache.putAll(previous);
        }
    }

    @Test
    void consumerOwnsItsTableUntilReleaseEvenAfterSharedEviction() throws ReflectiveOperationException {
        Map<StemDust.Source, CompletableFuture<StemDust>> cache = cache();
        var previous = new LinkedHashMap<>(cache);
        var work = new ArrayDeque<Runnable>();
        try {
            cache.clear();
            StemDust.Source s = source(burst(15, 592, false, 1, 0.25f, 20_000));
            StemDust.Request a = StemDust.request(s, work::add), b = StemDust.request(s, work::add);
            assertNull(a.ready());
            assertNull(b.ready());
            assertEquals(1, work.size(), "equal source requests share the admitted builder");
            work.remove().run();
            StemDust table = a.ready();
            assertNotNull(table);
            assertSame(table, b.ready());
            for (int i = 0; i < 9; i++) StemDust.of(1, 500 + i, false, 20_000);
            assertFalse(cache.containsKey(s), "actually evict the shared reuse entry");
            assertSame(table, a.ready());
            assertSame(table, b.ready());
            a.release();
            assertSame(table, b.ready(), "releasing one owner does not cancel another owner's result");
            assertNull(a.ready(), "released owner must reacquire after eviction, as after a replay seek");
            assertEquals(1, work.size());
            work.remove().run();
            assertNotSame(table, a.ready());
            assertEquals(table.lambda(60), a.ready().lambda(60), 0, "ownership changes no model values");
            assertEquals(table.top(60), a.ready().top(60), 0);
        } finally {
            while (!work.isEmpty()) work.remove().run();
            cache.clear();
            cache.putAll(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<StemDust.Source, CompletableFuture<StemDust>> cache() throws ReflectiveOperationException {
        var field = StemDust.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        return (Map<StemDust.Source, CompletableFuture<StemDust>>) field.get(null);
    }

    /** Independent one-second right-endpoint quadrature: source amounts/law are unchanged at the original nodes. */
    private static double discreteLambda(Detonation d, StemDust.Source s, int t) {
        double held = 0, oldest = 0, hob = d.hobMetres(), y = d.yieldKt();
        double reach = DustModel.STEM_M * Math.cbrt(y / 15), cap = Math.max(hob, CloudModel.capBottom(t, hob, y, d.surface()));
        for (int j = 0; j <= t; j++) {
            double z = MushroomModel.stemRise(t, hob, y, d.surface()) - MushroomModel.stemRise(j, hob, y, d.surface());
            if (z >= cap) continue;
            double supplied = 0;
            for (int i = 0; i < StemDust.RINGS; i++) {
                double r = s.radius(i), age = j - arrival(d, r);
                DustModel.Column c = column(d, r);
                if (age <= c.stemFrom()) continue;
                double r0 = reach * i / StemDust.RINGS, r1 = reach * (i + 1) / StemDust.RINGS;
                supplied += Math.PI * (r1 * r1 - r0 * r0) * c.stem() / DustModel.STEM_S * c.depth(age);
            }
            if (supplied > 0) { held += supplied; oldest = Math.max(oldest, z); }
        }
        double height = hob <= FireballModel.maxRadius(y, d.surface()) ? cap : Math.max(oldest,
                MushroomModel.stemRise(t, hob, y, d.surface()) - MushroomModel.stemRise(t - 1, hob, y, d.surface()));
        return held / Math.max(height, 1);
    }
}
