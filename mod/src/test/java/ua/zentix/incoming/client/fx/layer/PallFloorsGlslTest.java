package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pall's presentation floors inside the passing wall ({@link PallPass#SEE}, {@link PallPass#LIT_TAU}): the shader copies
 * ({@code pallLitDepth} in {@code pall.glsl}) are the Java laws, and without the eye inside young dust (gate 0) the image
 * keeps the law's dust and its dark interior.
 */
class PallFloorsGlslTest {
    private static String read() {
        try (InputStream in = PallFloorsGlslTest.class.getResourceAsStream("/assets/incoming/shaders/include/pall.glsl")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void shaderFloorsAreTheJavaFloors() {
        GlslFunction lit = GlslFunction.parse(read(), "pallLitDepth");
        for (double gate = 0; gate <= 1; gate += 0.125)
            for (double x = 0; x < 60; x = x * 1.3 + 0.01)
                assertEquals(PallPass.litDepth(x, gate), lit.call(Map.of("tau", x, "gate", gate)), 1e-4 * (1 + x), "pallLitDepth " + x + " " + gate);
    }

    @Test
    void outsideYoungDustTheImageKeepsTheLaw() {
        for (double x = 0; x < 60; x = x * 1.3 + 0.01) {
            assertEquals(1, PallPass.keep(0), 1e-12);
            assertEquals(x, PallPass.litDepth(x, 0), 1e-12);
        }
        // inside: dense young dust is drawn at a constant share, so it still clears as the law's dust settles (15 kt at 1.6 km, 0.58 → 0.37
        // per block over 5–40 s: 56 → 88 m by Koschmieder 3.912/σ), and lit no darker than at the floor depth
        assertEquals(33, 3.912 / (1.0 * PallPass.keep(1)), 1);
        assertEquals(56, 3.912 / (0.58 * PallPass.keep(1)), 1);
        assertEquals(88, 3.912 / (0.37 * PallPass.keep(1)), 1);
        assertEquals(PallPass.LIT_TAU, PallPass.litDepth(40, 1), 1e-4);
        assertTrue(PallPass.litDepth(2, 1) < PallPass.litDepth(4, 1), "light still falls with depth: the vertical gradient stays");
    }

    /**
     * Young dust's turbulent path (pallTurbReach) grows at its eddy speed, then slows to a slow turnover that stops once the layer has mixed:
     * bounded, never runs away.
     */
    @Test
    void youngDustMotionDecays() {
        GlslFunction reach = GlslFunction.parse(read(), "pallTurbReach");
        double total = reach.call(Map.of("age", 1e4));
        double early = reach.call(Map.of("age", 1.0)) - reach.call(Map.of("age", 0.0));
        double late = reach.call(Map.of("age", 31.0)) - reach.call(Map.of("age", 30.0));
        assertTrue(total > 0 && total <= 2.5, "bounded path, tiles: " + total);
        assertTrue(early > 4 * late, "moves at once, slow after 30 s: " + early + " vs " + late);
        assertEquals(total, reach.call(Map.of("age", 120.0)), 1e-6, "still once the layer has mixed");
        assertEquals(0, reach.call(Map.of("age", 0.0)), 1e-9);
    }

    /** The shader's copies of Java constants are the Java values (a drifted copy draws a different medium than the Java readers see). */
    @Test
    void shaderCopiesAreTheJavaValues() {
        String src = read();
        java.util.Map<String, Double> copies = java.util.Map.of(
                "PALL_SEE_NEAR", (double) PallPass.SEE_NEAR,
                "PALL_LIT_TAU", (double) PallPass.LIT_TAU,
                "PALL_MIX_S", ua.zentix.incoming.nuclear.model.DustModel.RISE_S,
                "PALL_SALTATION_S", ua.zentix.incoming.nuclear.model.DustModel.SALTATION_S,
                "PALL_YOUNG_TOP", ua.zentix.incoming.client.nuclear.PallField.YOUNG_TOP);
        for (var e : copies.entrySet()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("const float " + e.getKey() + " = ([0-9.]+);").matcher(src);
            assertTrue(m.find(), e.getKey());
            assertEquals(e.getValue(), Double.parseDouble(m.group(1)), 1e-9, e.getKey());
        }
    }
}
