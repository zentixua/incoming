package ua.zentix.incoming.nuclear.model;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Surface knowledge may change unborn inventories, never previously admitted airborne matter. */
class StemHistoryTest {
    private static StemDust.Source source(double yield, float scale, float wave) {
        double hob = Yield.optimalBurstHeight(yield);
        Detonation d = new Detonation(1, new Vec3(0, 64 + hob * scale, 0), 64, yield, false,
                0, 0, 0, 20_000, 7, scale, false, wave);
        return new StemDust.Source(yield, hob, false, 20_000, d.front(), scale);
    }

    private static StemDust.Source wet(StemDust.Source dry) { return dry.withLand(new double[dry.rings()]); }

    @Test
    void lateWaterCannotEraseOrStopTransferFromBornInventories() {
        for (double yield : new double[]{5, 15}) {
            StemDust.Source dry = source(yield, 1, .25f);
            StemDust reference = StemDust.of(dry);
            StemDust.Request request = StemDust.request(dry, Runnable::run);
            assertSame(reference, request.ready(0));
            assertTrue(reference.lambda(45) > 0);
            request.update(wet(dry), 45);
            for (double t : new double[]{45, 45.2, 46, 47, 60, 100, 300}) {
                StemDust actual = request.ready(t);
                assertSame(reference, actual, "no replacement or mass deletion after all rings are born");
                assertEquals(reference.lambda(t), actual.lambda(t), 0);
                assertEquals(reference.top(t), actual.top(t), 0);
            }
            request.release();
            request.update(wet(dry), 0);
            assertSame(reference, request.ready(0), "seek/reacquisition cannot unfreeze recorded source history");
        }
    }

    @Test
    void earlyWaterIsEmptyAndOnlyUnbornAnnuliCanChangeAtTheirActualFrontClock() {
        for (float scale : new float[]{.25f, 1, 2.5f}) for (float wave : new float[]{.25f, 1}) {
            StemDust.Source dry = source(15, scale, wave);
            StemDust.Request early = StemDust.request(dry, Runnable::run);
            early.ready(0);
            early.update(wet(dry), 0);
            assertEquals(0, early.ready(0).lambda(120), 0);

            StemDust.Request midway = StemDust.request(dry, Runnable::run);
            StemDust initial = midway.ready(0);
            double boundary = dry.arrivalSeconds(dry.radius(12));
            midway.update(wet(dry), boundary);
            StemDust actual = midway.ready(boundary);
            double[] land = new double[dry.rings()];
            for (int i = 0; i < land.length; i++)
                land[i] = dry.arrivalSeconds(dry.radius(i)) <= boundary ? 1 : 0;
            assertEquals(1, land[12], 0, "a source born exactly at this clock is already committed");
            assertEquals(0, land[23], 0, "the front has not created the outer inventory yet");
            StemDust expected = StemDust.of(dry.withLand(land));
            for (double t : new double[]{0, boundary / 2, boundary})
                assertEquals(initial.lambda(t), actual.lambda(t), 0, "a new table cannot rewrite the past");
            for (double t : new double[]{30, 60, 120, 300}) {
                assertEquals(expected.lambda(t), actual.lambda(t), 0);
                assertEquals(expected.top(t), actual.top(t), 0);
            }
            assertTrue(actual.lambda(120) > 0 && actual.lambda(120) < initial.lambda(120));
        }
    }

    @Test
    void completionAcrossBirthCannotApplyStaleWeightsOrOrphanTheWorker() throws ReflectiveOperationException {
        withEmptyCache(() -> {
            StemDust.Source dry = source(15, 1, .25f);
            var work = new ArrayDeque<Runnable>();
            StemDust.Request request = StemDust.request(dry, work::add);
            assertNull(request.ready(0));
            work.remove().run();
            StemDust initial = request.ready(0);
            request.update(wet(dry), 0);
            assertSame(initial, request.ready(0));
            assertEquals(1, work.size());
            double clock = dry.arrivalSeconds(dry.radius(12));
            for (int i = 0; i < 30; i++) {
                request.update(wet(dry), clock);
                assertSame(initial, request.ready(clock));
                assertEquals(1, work.size(), "the blocked worker stays owned across revisions");
            }
            work.remove().run();
            assertSame(initial, request.ready(clock), "a stale completed table cannot erase born rings");
            assertEquals(1, work.size(), "one coalesced replacement for the remaining unborn rings");
            work.remove().run();
            StemDust replacement = request.ready(clock);
            assertNotSame(initial, replacement);
            assertTrue(replacement.lambda(120) > 0 && replacement.lambda(120) < initial.lambda(120));
            assertEquals(initial.lambda(clock), replacement.lambda(clock), 0);
            assertTrue(work.isEmpty());
            request.update(wet(dry), 60);
            assertSame(replacement, request.ready(60));
        });
    }

    @Test
    void coldEarlyKnowledgeSurvivesALateSupersededInitialBuild() throws ReflectiveOperationException {
        withEmptyCache(() -> {
            StemDust.Source dry = source(5, 1, .25f);
            var work = new ArrayDeque<Runnable>();
            StemDust.Request request = StemDust.request(dry, work::add);
            assertNull(request.ready(0));
            request.update(wet(dry), 0);
            work.remove().run();
            assertNull(request.ready(45), "cold dry result was never displayed and cannot overwrite early water");
            work.remove().run();
            assertEquals(0, request.ready(45).lambda(120), 0);
        });
    }

    @SuppressWarnings("unchecked")
    private static void withEmptyCache(Runnable test) throws ReflectiveOperationException {
        var field = StemDust.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        var cache = (Map<StemDust.Source, CompletableFuture<StemDust>>) field.get(null);
        var saved = new LinkedHashMap<>(cache);
        try { cache.clear(); test.run(); }
        finally { cache.clear(); cache.putAll(saved); }
    }
}
