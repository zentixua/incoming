package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pall march's sample boundaries are fixed in the world for a fixed eye ({@code pall.glsl}: {@code pallGridStep}, {@code pallGridNext},
 * {@link PallPass#snapBounds}): as the dust head moves outward and the pall's bounds grow every frame, a static stretch of the ray keeps its
 * samples — samples sliding with the front read as boiling dust.
 */
class PallGridGlslTest {
    private static String read() {
        try (InputStream in = PallGridGlslTest.class.getResourceAsStream("/assets/incoming/shaders/include/pall.glsl")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The head grid's boundaries over a static stretch of ray are the same whatever the head's entry and width this frame. */
    @Test
    void headGridStaysPutAsTheFrontMoves() {
        String src = read();
        GlslFunction step = GlslFunction.parse(src, "pallGridStep"), next = GlslFunction.parse(src, "pallGridNext");
        double jitter = 0.37;
        // the head's wanted step changes smoothly as it grows (0.12 of its top): within one octave the snapped step is the same
        double s1 = step.call(Map.of("want", 5.1)), s2 = step.call(Map.of("want", 7.9));
        assertEquals(s1, s2, 0.0);
        assertTrue(s1 >= 7.9 && s1 < 2 * 7.9);
        // two frames, the front 40 blocks farther: from any point of the stretch 300–400 the next boundary is the same
        for (double t = 300; t < 400; t += 0.7) {
            double a = next.call(Map.of("t", t, "step", s1, "jitter", jitter)), b = next.call(Map.of("t", t, "step", s2, "jitter", jitter));
            assertEquals(a, b, 1e-6);
            assertTrue(a > t && a <= t + s1 + 1e-6);
            assertEquals(0, ((a / s1 - jitter) % 1 + 1) % 1, 1e-6, "a boundary is a multiple of the step from the eye");
        }
    }

    /**
     * The head's step on a ray never jumps between neighbouring rays: on the eye's power-of-two grid while the budget is free,
     * growing continuously where a ray's budget share binds. A step from the ray's own span (span / floor(span / step)) or a
     * power of two of a per-ray share put neighbouring pixels on different grids: ribs, a toothed edge, octave lines (review:
     * two rays 0.043° apart asking 15.996 and 16.009 steps snapped to grids 16 and 32).
     */
    @Test
    void headStepIsContinuousAcrossRays() {
        String src = read();
        GlslFunction head = GlslFunction.parse(src, "pallHeadStep"), grid = GlslFunction.parse(src, "pallGridStep");
        for (double want = 3; want < 40; want *= 1.13) {
            assertEquals(grid.call(Map.of("want", want)), head.call(Map.of("want", want, "fit", 1.0)), 0.0, "free budget: the world grid");
            double prev = head.call(Map.of("want", want, "fit", 1.0));
            for (double fit = 1; fit > 0.02; fit -= 0.0005) {
                double s = head.call(Map.of("want", want, "fit", fit));
                assertTrue(s >= want / fit - 1e-9, "within the ray's budget share");
                assertTrue(Math.abs(s - prev) <= prev * 0.03, "no jump at fit " + fit + ": " + prev + " → " + s);
                prev = s;
            }
        }
        // the reviewer's pair: one wall, the rays' shares of a 22-step budget 16/15.996 and 16/16.009 of the wanted step
        double w = 6, a = head.call(Map.of("want", w, "fit", 15.996 / 16)), b = head.call(Map.of("want", w, "fit", 16.009 / 16));
        assertEquals(a, b, a * 0.002);
        String march;
        try (InputStream in = PallGridGlslTest.class.getResourceAsStream("/assets/incoming/shaders/include/fx_pall.glsl")) {
            assertNotNull(in);
            march = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        assertTrue(march.contains("fineStep[k] = pallHeadStep(fineStep[k], fit);"), "the march takes the head step from pallHeadStep");
    }

    /** The pall's bounds the ordinary march spans stay put while the pall grows within a quarter octave, at most a fifth wider. */
    @Test
    void marchBoundsStayPutWhileThePallGrows() {
        // one quarter octave: 1024 < r ≤ 1217.9 all snap to 2^10.25
        for (double r = 1025; r < 1217; r += 3) assertEquals(Math.pow(2, 10.25), PallPass.snapBounds(r), 1e-6);
        for (double r = 2; r < 1e5; r *= 1.07) {
            double s = PallPass.snapBounds(r);
            assertTrue(s >= r - 1e-9 && s <= r * Math.pow(2, 0.25) + 1e-6, r + " → " + s);
        }
    }
}
