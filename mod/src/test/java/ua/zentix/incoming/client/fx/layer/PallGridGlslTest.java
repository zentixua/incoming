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
