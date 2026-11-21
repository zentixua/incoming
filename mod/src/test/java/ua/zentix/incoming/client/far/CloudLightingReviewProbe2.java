package ua.zentix.incoming.client.far;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.warhead.GroundMaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probes (Codex, round 2): a trace of mass that swaps a merged puff's dominant source does not flip its light, a
 * dirt stem gets no soot gain, a flat soot layer does not cast an upright shadow.
 */
class CloudLightingReviewProbe2 {
    private static final FarView.Sun NOON = new FarView.Sun(0, 1, 0, 0.8f);
    private static final FarView.Sun MORNING = new FarView.Sun(0.8f, 0.6f, 0, 0.8f);
    private static final double RECEIVER_Z = 110;

    @AfterEach
    void reset() {
        SmokePuffs.reset();
        FarFlightView.reset();
    }

    @Test
    void dustStemCannotGetTheSootGain() {
        float[] color = FarBlasts.dustColour(GroundMaterial.DIRT);
        float r = SmokeGrid.dustAlbedo(color[0]), g = SmokeGrid.dustAlbedo(color[1]), b = SmokeGrid.dustAlbedo(color[2]);
        // Same tube inputs as a blast stem: dust mass per metre, zero soot, ordinary dirt colour.
        double sigma = 2, tau = 0.05;
        double dust = tau * Math.sqrt(2 * Math.PI) * sigma / SmokeGrid.dustK(r, g, b);
        SmokeGrid.Source stem = (seconds, out) -> {
            out.tube(0, 0, 30, sigma, sigma, 0, dust, 0, 8, 30, sigma, sigma, 0, dust, r, g, b, 300, 0, sigma);
            return true;
        };

        Capture capture = frame(MORNING, List.of(stem));
        double max = capture.puffs.stream().mapToDouble(Puff::sun).max().orElseThrow();
        assertTrue(max <= 1.005, () -> "Soot-free stem, transverse thickness " + tau + ", direct multiplier " + max);
    }

    @Test
    void swappingTheDominantSourceByATraceDoesNotFlipMergedLighting() {
        Capture shadedSourceHeavier = frame(NOON, raid(1.0001));
        int shadedLevel = SmokePuffs.lastLevel();
        double shadedFloor = SmokePuffs.lastRaw();
        reset();
        Capture openSourceHeavier = frame(NOON, raid(0.9999));
        int openLevel = SmokePuffs.lastLevel();
        double openFloor = SmokePuffs.lastRaw();

        assertTrue(shadedLevel > 0 && openLevel > 0,
                () -> "Expected budget merge at raw fills " + shadedFloor + " and " + openFloor);
        double shaded = receiverSun(shadedSourceHeavier), open = receiverSun(openSourceHeavier);
        assertEquals(shaded, open, 0.01, () -> "Raw fill " + shadedFloor + " / " + openFloor + ", merge level "
                + shadedLevel + " / " + openLevel + ", average direct multiplier " + shaded + " / " + open);
    }

    @Test
    void aFullyFlatSmokePuffDoesNotCastAnUprightEnvelopeShadow() {
        double clear = receiverSun(frame(NOON, List.of(cloud(1, false, false))));
        double underFlat = receiverSun(frame(NOON, List.of(cloud(1, true, true))));
        assertEquals(clear, underFlat, 0.01, () -> "Fully flat soot caster (width/height 8), receiver " + clear + " / " + underFlat);
    }

    private static List<SmokeGrid.Source> raid(double shadedMass) {
        List<SmokeGrid.Source> sources = new ArrayList<>();
        sources.add(cloud(shadedMass, true, false));
        sources.add(cloud(1, false, false));
        // Distant from the receiver's cell. These fill enough of the frame to invoke the normal cross-source LOD.
        for (int i = 0; i < 120; i++) {
            sources.add(SmokeRaidTest.blob(22, 0, 35, 9, 9, 2 * Math.PI * 9 * 9 * 0.12, i));
        }
        return sources;
    }

    private static SmokeGrid.Source cloud(double receiverMass, boolean blocker, boolean flat) {
        return (seconds, out) -> {
            out.blob(0.5, 0.5, RECEIVER_Z, 4, 4, 0, receiverMass * 2 * Math.PI * 16 / SmokeGrid.dustK(0.55f, 0.55f, 0.55f),
                    0, 0.55f, 0.55f, 0.55f, 300, 0);
            if (blocker) {
                double soot = 20 * 2 * Math.PI * 16 / SmokeGrid.SOOT_K;
                out.blob(0.5, 14, RECEIVER_Z, 4, flat ? 0.5 : 4, soot, 0, 0, 0.55f, 0.55f, 0.55f, 300, 0);
            }
            return true;
        };
    }

    private static double receiverSun(Capture capture) {
        List<Puff> receiver = capture.puffs.stream().filter(p -> Math.abs(p.x()) < 6 && Math.abs(p.y()) < 6
                && Math.abs(p.z() - RECEIVER_Z) < 6).toList();
        assertFalse(receiver.isEmpty(), "Expected a surviving receiver by the center of the screen");
        double alpha = receiver.stream().mapToDouble(Puff::alpha).sum();
        return receiver.stream().mapToDouble(p -> p.alpha() * p.sun()).sum() / alpha;
    }

    private static Capture frame(FarView.Sun sun, List<SmokeGrid.Source> sources) {
        FarView view = new FarView(Vec3.ZERO, new Vector3f(0, 0, 1), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0),
                0, 1000, 0.002, 1, 1, 1, new Vector3f(1, 1, 1), 20_000, 192, 192, 0, Sight.Air.at(0, 20_000), sun);
        Capture capture = new Capture();
        FarFlightView.budget();
        for (SmokeGrid.Source source : sources) source.emit(1000, SmokePuffs.sink(view, capture, source, 1000));
        SmokePuffs.flush();
        return capture;
    }

    private record Puff(double x, double y, double z, double alpha, double sun) {}

    private static final class Capture implements SmokePuffs.Out {
        final List<Puff> puffs = new ArrayList<>();

        @Override
        public void puff6(double x, double y, double z, double halfA, double halfB, FxAtlas.Sprite sprite,
                          float mix, float shade, float r, float g, float b, float alpha, float sun) {
            if (alpha >= 0.002f) puffs.add(new Puff(x, y, z, alpha, sun));
        }

        @Override
        public void puff6(double x, double y, double z, double halfA, double halfB, FxAtlas.Sprite sprite,
                          float mix, float shade, float r, float g, float b, float alpha) {
            puff6(x, y, z, halfA, halfB, sprite, mix, shade, r, g, b, alpha, 1);
        }

        @Override
        public void puff6Flat(double x, double y, double z, double halfA, double halfB, double soft, double angle,
                              FxAtlas.Sprite sprite, float mix, float shade, float r, float g, float b, float alpha) {}

        @Override
        public void veil(SmokePuffs.Veil veil, int light) {}

        @Override
        public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx,
                              double by, double bz, double halfB, float alphaB, int lightB, float r, float g,
                              float b, float[] joint) {
            return true;
        }
    }
}
