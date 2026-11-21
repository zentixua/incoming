package ua.zentix.incoming.client.far;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probes (Codex, round 1) for the cloud's shadow on its puffs ({@link SmokePuffs#sunThrough}): moonlight
 * unchanged, soot shades what lies under it, merged flat dust, a tube's light across its axis, world-border precision, and
 * the planning worker's replay of the light shares.
 */
class CloudLightingReviewProbe {
    private static final FarView.Sun NOON = new FarView.Sun(0, 1, 0, 0.8f);
    private static final FarView.Sun LOW_SUN = new FarView.Sun(0.8f, 0.6f, 0, 0.8f);
    private static final double Z = 110;

    private record Puff(double x, double y, double z, float sun) {}

    private static final class Capture implements SmokePuffs.Out {
        final List<Puff> puffs = new ArrayList<>();

        @Override
        public void puff6(double x, double y, double z, double halfA, double halfB, FxAtlas.Sprite sprite,
                          float mix, float shade, float r, float g, float b, float alpha, float sun) {
            if (alpha >= 0.002f) puffs.add(new Puff(x, y, z, sun));
        }

        @Override
        public void puff6Flat(double x, double y, double z, double halfA, double halfB, double soft, double angle,
                              FxAtlas.Sprite sprite, float mix, float shade, float r, float g, float b,
                              float alpha, float sun) {
            puff6(x, y, z, halfA, halfB, sprite, mix, shade, r, g, b, alpha, sun);
        }

        @Override
        public void puff6(double x, double y, double z, double halfA, double halfB, FxAtlas.Sprite sprite,
                          float mix, float shade, float r, float g, float b, float alpha) {
            throw new AssertionError("Missing puff light shares");
        }

        @Override
        public void puff6Flat(double x, double y, double z, double halfA, double halfB, double soft, double angle,
                              FxAtlas.Sprite sprite, float mix, float shade, float r, float g, float b, float alpha) {
            throw new AssertionError("Missing flat puff light shares");
        }

        @Override
        public void veil(SmokePuffs.Veil veil, int light) {}

        @Override
        public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx,
                              double by, double bz, double halfB, float alphaB, int lightB, float r, float g,
                              float b, float[] joint) {
            return true;
        }
    }

    @AfterEach
    void reset() {
        SmokePuffs.planner(null);
        SmokePuffs.reset();
        FarFlightView.reset();
    }

    @Test
    void loneSootPuffRetainsTheOriginalMoonlight() {
        var puffs = frame(FarView.NO_SUN, SmokeRaidTest.blob(0, 0, Z, 2, 2, 100, 1));
        assertFalse(puffs.isEmpty());
        assertEquals(1, puffs.getFirst().sun(), 0, "The shader still uses the direct channel for moonlight");
    }

    @Test
    void sootTubeRetainsTheOriginalMoonlight() {
        SmokeGrid.Source tube = (seconds, out) -> {
            out.tube(-4, 0, 30, 2, 2, 0.015, 0, 4, 0, 30, 2, 2, 0.015, 0,
                    0.55f, 0.55f, 0.55f, 300, 0, 2);
            return true;
        };
        var puffs = frame(FarView.NO_SUN, tube);
        assertFalse(puffs.isEmpty());
        assertEquals(1, puffs.getFirst().sun(), 1e-6, "The shader still uses the direct channel for moonlight");
    }

    @Test
    void whitePuffReceivesTheShadowOfTheSootAboveIt() {
        Puff unblocked = receiver(frame(NOON, whitePuffUnderSoot(false)));
        Puff blocked = receiver(frame(NOON, whitePuffUnderSoot(true)));
        double edge = SmokePuffs.edge(1);
        double sootPath = SmokePuffs.sunThrough(0, -7, 0, edge, edge, 3, 3, sootBlocker(),
                SmokePuffs.shade(1, 0, 0, 0.55f), NOON);
        assertTrue(sootPath < 0.001, "Blocker alone transmits " + sootPath);
        assertTrue(blocked.sun() < 0.1 * unblocked.sun(),
                () -> "Open " + unblocked + ", under soot " + blocked + ", soot path " + sootPath);
    }

    @Test
    void overlappingFlatDustDoesNotEnterTheUprightEnvelope() {
        Puff clear = receiver(frame(NOON, flatDustBelowUprightPuff(false)));
        Puff dusty = receiver(frame(NOON, flatDustBelowUprightPuff(true)));
        // dust carries no soot and casts nothing; the residue (0.682 → 0.783) is the existing hold-time merge reshaping
        // the clump the dust joined (σh/σv 1.7 → 2.9: a partly flat caster casts by 1 − flatness); before soot-only
        // casting: 0.682 → 0.173
        assertEquals(clear.sun(), dusty.sun(), 0.15, "Flat dust must be excluded even when close enough to merge");
    }

    @Test
    void tubeLightIsContinuousAsTheSunCrossesItsAxis() {
        float step = 1e-6f;
        double k = SmokePuffs.shade(1, 0, 0, 0.55f);
        double left = SmokePuffs.tubeThrough(3, 0, 0, 3, 6, 0.45, 0, 1, 0, k, new FarView.Sun(-step, 1, 0, 0.8f));
        double right = SmokePuffs.tubeThrough(3, 0, 0, 3, 6, 0.45, 0, 1, 0, k, new FarView.Sun(step, 1, 0, 0.8f));
        assertEquals(left, right, 1e-4, "Sun angles on either side of local noon, vertical tube");
    }

    @Test
    void movingASmallPuffNearTheWorldBorderDoesNotChangeItsLight() {
        double x = 29_000_000;
        var nearSpawn = frame(NOON, SmokeRaidTest.blob(0, 0, Z, 0.15, 0.15, 1, 2));
        var nearBorder = frame(NOON, SmokeRaidTest.blob(x, 0, Z, 0.15, 0.15, 1, 2), new Vec3(x, 0, 0));
        assertFalse(nearSpawn.isEmpty());
        assertEquals(nearSpawn, nearBorder);
    }

    @Test
    void workerRecordingKeepsBothLightSharesOfEachCapturedFrame() {
        SmokeGrid.Source source = whitePuffUnderSoot(true);
        List<Puff> noon = frame(NOON, source);
        List<Puff> lowSun = frame(LOW_SUN, source);
        assertNotEquals(noon, lowSun, "The fixture must respond to the changed light");
        reset();

        SmokePuffs.Worker worker = (SmokePuffs.Worker) SmokePuffs.worker();
        SmokePuffs.planner(worker);
        assertTrue(frame(NOON, source).isEmpty());
        assertTrue(worker.await(SmokePuffs.DRAIN_NANOS));
        assertEquals(noon, frame(LOW_SUN, source));
        assertTrue(worker.await(SmokePuffs.DRAIN_NANOS));
        assertEquals(lowSun, frame(FarView.NO_SUN, source));
    }

    private static List<Puff> frame(FarView.Sun sun, SmokeGrid.Source source) {
        return frame(sun, source, Vec3.ZERO);
    }

    private static List<Puff> frame(FarView.Sun sun, SmokeGrid.Source source, Vec3 eye) {
        FarView view = new FarView(eye, new Vector3f(0, 0, 1), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0),
                0, 1000, 0.002, 1, 1, 1, new Vector3f(1, 1, 1), 20_000, 192, 192, 0,
                Sight.Air.at(0, 20_000), sun);
        Capture capture = new Capture();
        FarFlightView.budget();
        source.emit(1000, SmokePuffs.sink(view, capture, source, 1000));
        SmokePuffs.flush();
        return capture.puffs;
    }

    private static Puff receiver(List<Puff> puffs) {
        var matches = puffs.stream().filter(p -> Math.abs(p.y()) < 0.1).toList();
        assertEquals(1, matches.size(), () -> "Single receiver expected in " + puffs);
        return matches.getFirst();
    }

    private static double sootBlocker() {
        return 2 * Math.PI * 3 * 3 * 20;
    }

    private static SmokeGrid.Source whitePuffUnderSoot(boolean blocker) {
        return (seconds, out) -> {
            out.blob(0, 0, Z, 1, 1, 0, 0, 2 * Math.PI / SmokeGrid.DROP_K, 0.55f, 0.55f, 0.55f, 300, 0);
            if (blocker) out.blob(0, 7, Z, 3, 3, sootBlocker() / SmokeGrid.SOOT_K, 0, 0, 0.55f, 0.55f, 0.55f, 300, 0);
            return true;
        };
    }

    private static SmokeGrid.Source flatDustBelowUprightPuff(boolean dust) {
        return (seconds, out) -> {
            out.blob(0, 0, Z, 1, 1, 100 / SmokeGrid.SOOT_K, 0, 0, 0.55f, 0.55f, 0.55f, 300, 0);
            out.blob(0, 10.5, Z, 12, 7, 700 / SmokeGrid.SOOT_K, 0, 0, 0.55f, 0.55f, 0.55f, 300, 0);
            if (dust) out.blob(0, 10, Z, 12, 1, 0, 1400 / SmokeGrid.dustK(0.55f, 0.55f, 0.55f),
                    0, 0.55f, 0.55f, 0.55f, 300, 0);
            return true;
        };
    }
}
