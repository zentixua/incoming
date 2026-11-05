package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.StemDust;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StemSurfaceTest {
    private static Detonation burst(double yield, float scale) {
        return new Detonation(1, new Vec3(100, 64 + 592 * scale, -200), 64, yield, false,
                0, 0, 0, 20_000, 7, scale, false, 0.25f);
    }

    private static StemDust.Source source(Detonation d) {
        return new StemDust.Source(d.yieldKt(), d.hobMetres(), d.surface(), d.visibility(), d.front(), d.scale());
    }

    private static StemDust.Source sweep(StemSurface survey, FarTerrain.Water water, int rings) {
        StemDust.Source result = null;
        for (int i = 0; i < rings; i++) result = survey.survey(water);
        return result;
    }

    @Test
    void knownWaterHasNoTerrestrialSourceAndStopsReadingAfterBoundedSweep() {
        for (double yield : new double[]{5, 15}) {
            Detonation d = burst(yield, 1);
            StemDust.Source dry = source(d);
            StemSurface survey = new StemSurface(d, dry);
            AtomicInteger reads = new AtomicInteger();
            FarTerrain.Water ocean = (x, z) -> { reads.incrementAndGet(); return 1; };
            for (int tick = 0; tick < dry.rings() - 1; tick++) {
                assertSame(dry, survey.survey(ocean), "publish a completed sweep, not one revision per sample");
                assertEquals((tick + 1) * StemSurface.PER_TICK, reads.get());
            }
            StemDust.Source wet = survey.survey(ocean);
            for (int r = 0; r < wet.rings(); r++) assertEquals(0, wet.landShare(r), 0);
            assertTrue(StemDust.of(dry).lambda(120) > 0, "the dry control supplies dust");
            for (double t : new double[]{15, 30, 60, 120, 300}) assertEquals(0, StemDust.of(wet).lambda(t), 0);
            for (int tick = 0; tick < 100; tick++) assertSame(wet, survey.survey(ocean));
            assertEquals(dry.rings() * StemSurface.ANGLES, reads.get());
        }
    }

    @Test
    void knownDrySurfacePreservesTheExactReferenceSource() {
        Detonation d = burst(15, 1);
        StemDust.Source dry = source(d);
        assertSame(dry, sweep(new StemSurface(d, dry), (x, z) -> 0, dry.rings()));
        assertSame(dry, sweep(new StemSurface(d, dry), (x, z) -> Double.NaN, dry.rings()));
        assertSame(dry, new StemSurface(d, dry).survey(null));
    }

    @Test
    void shorelineSamplesTheScaledFootprintAndKeepsLandContributions() {
        for (float scale : new float[]{0.25f, 1, 2.5f}) {
            Detonation d = burst(15, scale);
            StemDust.Source dry = source(d);
            StemSurface survey = new StemSurface(d, dry);
            double reach = d.blocks(dry.radius(dry.rings() - 1));
            AtomicInteger fartherThanModel = new AtomicInteger();
            StemDust.Source coast = sweep(survey, (x, z) -> {
                double distance = Math.hypot(x - d.burst().x, z - d.burst().z);
                assertTrue(distance <= reach + 2, "sampling stays inside the scaled source footprint");
                if (distance > dry.radius(dry.rings() - 1)) fartherThanModel.incrementAndGet();
                return x >= d.burst().x ? 1 : 0;
            }, dry.rings());
            for (int r = 0; r < coast.rings(); r++) {
                assertEquals(0.5, coast.landShare(r), 0);
                assertEquals(dry.arrivalSeconds(dry.radius(r)), coast.arrivalSeconds(coast.radius(r)), 0);
            }
            if (scale > 1) assertTrue(fartherThanModel.get() > 0, "blocks, not unscaled model meters");
            for (double t : new double[]{30, 60, 120})
                assertEquals(0.5 * StemDust.of(dry).lambda(t), StemDust.of(coast).lambda(t), 1e-10);
        }
    }

    @Test
    void oneKnownWaterSampleNeverBecomesAnAllWaterFootprintAndLateKnowledgeIsRetained() {
        Detonation d = burst(15, 1);
        StemDust.Source dry = source(d);
        StemSurface survey = new StemSurface(d, dry);
        AtomicInteger read = new AtomicInteger();
        StemDust.Source partial = sweep(survey, (x, z) -> read.getAndIncrement() == 0 ? 1 : Double.NaN, dry.rings());
        assertEquals(7.0 / 8, partial.landShare(0), 0, "unknown seven angular samples retain reference land");
        for (int r = 1; r < partial.rings(); r++) assertEquals(1, partial.landShare(r), 0);
        assertTrue(StemDust.of(partial).lambda(120) > 0);
        StemDust.Source wet = sweep(survey, (x, z) -> 1, dry.rings());
        for (int r = 0; r < wet.rings(); r++) assertEquals(0, wet.landShare(r), 0);
        assertSame(wet, sweep(survey, (x, z) -> 0, dry.rings()), "later terrain changes/replay do not rewrite known source columns");
    }

    @Test
    void finalUnknownColumnPublishesEvenBeforeTheEndOfItsRetrySweep() {
        Detonation d = burst(15, 1);
        StemDust.Source dry = source(d);
        StemSurface survey = new StemSurface(d, dry);
        AtomicInteger read = new AtomicInteger();
        StemDust.Source partial = sweep(survey, (x, z) -> read.getAndIncrement() == 0 ? Double.NaN : 1, dry.rings());
        assertEquals(1.0 / 8, partial.landShare(0), 0);
        StemDust.Source wet = survey.survey((x, z) -> 1);
        assertEquals(0, wet.landShare(0), 0);
        assertEquals(0, StemDust.of(wet).lambda(120), 0);
    }
}
