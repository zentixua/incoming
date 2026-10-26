package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.HeatPass;
import ua.zentix.incoming.client.fx.smoke.CloudModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The jitter law in the shader ({@code fx_heat.fsh}: {@code haze*}) is the same as in Java ({@link HeatPass}). */
class HazeGlslTest {
    private static final String GLSL = read();

    private static String read() {
        try (InputStream in = HazeGlslTest.class.getResourceAsStream("/assets/incoming/shaders/core/fx_heat.fsh")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static double constant(String name) {
        Matcher m = Pattern.compile("const float " + name + " = ([\\d.eE+-]+);").matcher(GLSL);
        assertTrue(m.find(), name);
        return Double.parseDouble(m.group(1));
    }

    @Test
    void constantsAreTheJavaOnes() {
        assertEquals(CloudModel.T_AIR_K, constant("HAZE_T_AIR"), 1e-9);
        assertEquals(HeatPass.COOL, constant("HAZE_COOL"), 1e-9);
        assertEquals(HeatPass.KOLMOGOROV, constant("HAZE_KOLMOGOROV"), 1e-9);
        assertEquals(HeatPass.ACUITY, constant("HAZE_ACUITY"), 1e-12);
        assertEquals(HeatPass.PUPIL, constant("HAZE_PUPIL"), 1e-12);
        assertEquals(HeatPass.EYE_S, constant("HAZE_EYE_S"), 1e-12);
        assertEquals(HeatPass.SWIRL, constant("HAZE_SWIRL"), 1e-12);
        assertEquals(HeatPass.HAZE_CYCLE, constant("HAZE_CYCLE"), 1e-12);
        assertEquals(HeatPass.CONE_FLAT, constant("HAZE_CONE_FLAT"), 1e-15);
    }

    @Test
    void shaderLawsAreJavaLaws() {
        GlslFunction dn = GlslFunction.parse(GLSL, "hazeDn"), angle = GlslFunction.parse(GLSL, "hazeAngle"),
                seen = GlslFunction.parse(GLSL, "hazeSeen");
        for (double k = 300; k < 3000; k += 37) {
            assertEquals(HeatPass.dn(k, HeatPass.TURBULENCE), dn.call(Map.of("excess", k - CloudModel.T_AIR_K, "amp", HeatPass.N0 * HeatPass.TURBULENCE)),
                    1e-15, k + " K");
        }
        for (double len = 0.3; len < 300; len *= 1.7) {
            for (double ap = 0.004; ap < 20; ap *= 2.3) {
                assertEquals(HeatPass.hazeAngle(2e-5, len, 0.4, ap), angle.call(Map.of("dn", 2e-5, "len", len, "eddy", 0.4, "aperture", ap)), 1e-15,
                        len + " / " + ap);
            }
        }
        GlslFunction in = GlslFunction.parse(GLSL, "hazeIn"), out = GlslFunction.parse(GLSL, "hazeOut");
        java.util.Random rnd = new java.util.Random(5);
        for (int i = 0; i < 3000; i++) {
            double a = i % 7 == 0 ? 0 : rnd.nextGaussian() * (i % 2 == 0 ? 1 : 1e-3), b = rnd.nextGaussian() * 30, c = rnd.nextGaussian() * 900;
            double t0 = rnd.nextDouble() * 50, t1 = t0 + rnd.nextDouble() * 200;
            double[] span = HeatPass.coneSpan(a, b, c, t0, t1);
            double lo = in.call(Map.of("a", a, "b", b, "c", c, "t0", t0)), hi = out.call(Map.of("a", a, "b", b, "c", c, "t0", t0, "t1", t1));
            if (span[1] <= span[0]) {
                assertTrue(hi <= lo, "case " + i);
            } else {
                assertEquals(span[0], lo, 1e-9 * Math.max(1, Math.abs(lo)), "case " + i);
                assertEquals(span[1], hi, 1e-9 * Math.max(1, Math.abs(hi)), "case " + i);
            }
        }
        for (double sp = 0.1; sp < 3000; sp *= 1.9) {
            assertEquals(HeatPass.hazeSeen(0.1, sp), seen.call(Map.of("eddy", 0.1, "speed", sp)), 1e-12, sp + " m/s");
        }
    }
}
