package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effects behind see-through blocks ({@code see.glsl}, {@link SeeThrough}): the layer drawn over a frame that already has the
 * translucent surface in it gives exactly the picture of the surface laid over the layer laid over the world — any number of
 * surface layers (both faces of a glass block), any number of layer fragments behind and in front of it, quads
 * (ONE, ONE_MINUS_SRC_ALPHA) and the pall composite (ONE, SRC_ALPHA), each layer fragment and the pall split at the surface's
 * own distance.
 */
class SeeThroughGlslTest {
    private static GlslFunction seeLight() throws IOException {
        String glsl = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/see.glsl")).replaceAll("//[^\\n]*", "");
        return GlslFunction.parse(glsl, "seeLight");
    }

    /** A layer fragment: premultiplied light, opacity, distance along the view axis. */
    private record Fragment(double light, double opacity, double distance) {}

    @Test
    void theLayerBehindASurfaceLooksAsIfTheSurfaceWereDrawnOverIt() throws IOException {
        GlslFunction see = seeLight();
        Random r = new Random(7);
        for (int run = 0; run < 2000; run++) {
            double world = r.nextDouble(), surface = 2 + 20 * r.nextDouble();
            // one to three surface layers (a pane, a block's two faces, glass in front of water)
            int layers = 1 + r.nextInt(3);
            double[] cover = new double[layers], colour = new double[layers];
            for (int i = 0; i < layers; i++) {
                cover[i] = r.nextDouble();
                colour[i] = r.nextDouble();
            }
            List<Fragment> fragments = new ArrayList<>();
            int n = r.nextInt(8);
            for (int i = 0; i < n; i++) {
                double opacity = r.nextInt(4) == 0 ? 0 : r.nextDouble();
                // premultiplied: light up to the opacity, glows (opacity 0) add light
                double light = opacity > 0 ? opacity * r.nextDouble() : 0.3 * r.nextDouble();
                fragments.add(new Fragment(light, opacity, 40 * r.nextDouble()));
            }
            fragments.sort((a, b) -> Double.compare(b.distance, a.distance));

            // the truth: fragments behind the surface over the world, the surface over them, then the ones in front
            double truth = world;
            boolean crossed = false;
            for (Fragment f : fragments) {
                if (!crossed && f.distance <= surface) {
                    truth = surfaceOver(truth, cover, colour);
                    crossed = true;
                }
                truth = f.light + (1 - f.opacity) * truth;
            }
            if (!crossed) truth = surfaceOver(truth, cover, colour);

            // the frame the layer finds: the surface already over the world; its own light X and transmittance T
            double frame = surfaceOver(world, cover, colour), through = 1;
            for (double a : cover) through *= 1 - a;
            double own = frame - through * world;
            for (Fragment f : fragments) {
                double light = f.distance > surface
                        ? see.call(Map.of("light", f.light, "opacity", f.opacity, "glassLight", own, "through", through)) : f.light;
                frame = light + (1 - f.opacity) * frame;
            }
            assertEquals(truth, frame, 1e-9, "run " + run);
        }
    }

    @Test
    void thePallBehindASurfaceIsLaidTheSameWay() throws IOException {
        GlslFunction see = seeLight();
        Random r = new Random(11);
        for (int run = 0; run < 500; run++) {
            double world = r.nextDouble(), cover = r.nextDouble(), colour = r.nextDouble();
            // pall composite: light L over the world it lets through by τ (blend ONE, SRC_ALPHA); τ = 0 — it writes the whole pixel
            double light = r.nextDouble(), kept = r.nextInt(5) == 0 ? 0 : r.nextDouble();
            double truth = cover * colour + (1 - cover) * (light + kept * world);
            double frame = cover * colour + (1 - cover) * world, through = 1 - cover, own = frame - through * world;
            double laid = see.call(Map.of("light", light, "opacity", 1 - kept, "glassLight", own, "through", through)) + kept * frame;
            assertEquals(truth, laid, 1e-12, "run " + run);
        }
    }

    @Test
    void thePallInFrontOfASurfaceIsNotTintedAndThePallBehindItIs() throws IOException {
        String glsl = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/see.glsl")).replaceAll("//[^\\n]*", "");
        GlslFunction medium = GlslFunction.parse(glsl, "seeMediumLight");
        Random r = new Random(13);
        for (int run = 0; run < 2000; run++) {
            // any medium in front of the surface (light, transmittance) and any behind it, as fx_pall_composite splits it at the
            // surface's own distance (mediumTo); the pall's shadow keeps k of the world's light
            double world = r.nextDouble(), cover = r.nextDouble(), colour = r.nextDouble(), kept = r.nextDouble();
            double tauFront = r.nextInt(6) == 0 ? 1 : r.nextDouble(), tauBehind = r.nextInt(6) == 0 ? 1 : r.nextDouble();
            double lightFront = (1 - tauFront) * r.nextDouble(), lightBehind = (1 - tauBehind) * r.nextDouble();
            double own = cover * colour, through = 1 - cover;
            double truth = lightFront + tauFront * (own + through * (lightBehind + tauBehind * kept * world));
            // the composite's whole ray to the world, and the frame it is laid on (ONE, SRC_ALPHA)
            double light = lightFront + tauFront * lightBehind, alpha = tauFront * tauBehind * kept;
            double frame = own + through * world;
            double laid = medium.call(Map.of("light", light, "tau", alpha, "lightFront", lightFront, "tauFront", tauFront,
                    "glassLight", own, "through", through)) + alpha * frame;
            assertEquals(truth, laid, 1e-9, "run " + run);
        }
    }

    @Test
    void theSameDustGivesAnotherPictureInFrontOfAPaneThanBehindIt() throws IOException {
        String glsl = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/see.glsl")).replaceAll("//[^\\n]*", "");
        GlslFunction medium = GlslFunction.parse(glsl, "seeMediumLight");
        // a pane letting half through with its own light 0.1, dust of light 0.3 letting half through, a world of 0.6
        double own = 0.1, through = 0.5, light = 0.3, tau = 0.5, world = 0.6, frame = own + through * world;
        double front = medium.call(Map.of("light", light, "tau", tau, "lightFront", light, "tauFront", tau, "glassLight", own,
                "through", through)) + tau * frame;
        double behind = medium.call(Map.of("light", light, "tau", tau, "lightFront", 0.0, "tauFront", 1.0, "glassLight", own,
                "through", through)) + tau * frame;
        // dust in front: 0.3 + 0.5·(0.1 + 0.5·0.6); behind the pane: 0.1 + 0.5·(0.3 + 0.5·0.6)
        assertEquals(0.5, front, 1e-12);
        assertEquals(0.4, behind, 1e-12);
    }

    @Test
    void betweenTwoSeparatePanesTheLayerTakesBothTints() throws IOException {
        // one surface per pixel (vanilla Fabulous keeps one too): a pane at 3, another at 10 with smoke at 20 behind both and
        // smoke at 6 between them. Behind both: exact. Between: the frame's light and transmittance are both panes', so the
        // smoke takes the rear pane's tint too, the documented limit (as if it stood behind both)
        GlslFunction see = seeLight();
        double world = 0.6, a1 = 0.4, c1 = 0.9, a2 = 0.5, c2 = 0.2;
        double[] cover = {a2, a1}, colour = {c2, c1};
        double frame = surfaceOver(world, cover, colour), through = (1 - a1) * (1 - a2), own = frame - through * world;
        double far = see.call(Map.of("light", 0.3, "opacity", 0.5, "glassLight", own, "through", through));
        double behindBoth = far + 0.5 * frame;
        assertEquals(surfaceOver(0.3 + 0.5 * world, cover, colour), behindBoth, 1e-12, "behind both panes");
        double mid = see.call(Map.of("light", 0.3, "opacity", 0.5, "glassLight", own, "through", through)) + 0.5 * frame;
        double between = a1 * c1 + (1 - a1) * (0.3 + 0.5 * (a2 * c2 + (1 - a2) * world));
        assertEquals(behindBoth, mid, 1e-12, "between: laid as if behind both");
        assertTrue(Math.abs(between - mid) < 0.1, "the limit's error stays below a tenth of the frame here: " + Math.abs(between - mid));
    }

    @Test
    void withoutASurfaceNothingChanges() throws IOException {
        GlslFunction see = seeLight();
        for (double light : new double[]{0, 0.2, 0.9}) {
            for (double opacity : new double[]{0, 0.5, 1}) {
                assertEquals(light, see.call(Map.of("light", light, "opacity", opacity, "glassLight", 0.0, "through", 1.0)), 0);
            }
        }
    }

    @Test
    void everyPassThatLaysTheLayerLooksThroughTheSurface() throws IOException {
        Path core = Path.of("src/main/resources/assets/incoming/shaders/core");
        for (String shader : new String[]{"fx.fsh", "fx_pall_composite.fsh", "fx_probe.fsh", "fx_heat.fsh"}) {
            String s = Files.readString(core.resolve(shader));
            assertTrue(s.contains("#moj_import <incoming:see.glsl>") && s.contains("FxSee > 0.5"), shader);
        }
    }

    /** Surface layers in drawing order over {@code under} (vanilla translucent blending). */
    private static double surfaceOver(double under, double[] cover, double[] colour) {
        double v = under;
        for (int i = 0; i < cover.length; i++) v = cover[i] * colour[i] + (1 - cover[i]) * v;
        return v;
    }
}
