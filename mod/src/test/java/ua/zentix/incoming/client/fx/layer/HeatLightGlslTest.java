package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ordinary direct light without borrowed nuclear scattering (FLASH). */
class HeatLightGlslTest {
    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/heat.glsl"));
    }

    @Test
    void shaderMatchesDirectIrradianceFromOneHundredToFiveThousandMetres() throws IOException {
        String glsl = source().replaceAll("//[^\\n]*", "");
        GlslFunction gain = GlslFunction.parse(glsl, "heatBallGain");
        for (double d : new double[]{100, 500, 1000, 2000, 3000, 5000}) {
            for (double visibility : new double[]{1000, 5000, 20000}) {
                double h = 3, r = 6, length = Math.hypot(d, h), sigma = 3.912 / visibility;
                for (double cosine : new double[]{d / length, h / length, 0, -d / length}) {
                    double expected = r * r * Math.exp(-sigma * length) / (length * length) * Math.max(cosine, 0);
                    double actual = gain.call(Map.of("d2", length * length, "r2", r * r, "sd", sigma * length, "nv", cosine));
                    assertEquals(expected, actual, 1e-14);
                    assertEquals(HeatPass.ballGain(length * length, r * r, sigma * length, cosine), actual, 1e-14);
                }
            }
        }
        assertTrue(glsl.contains("heatBallGain(d2, b.w * b.w, sd, nv)"), "sources actually use the tested law");
        assertFalse(glsl.contains("mushBallFace"), "ordinary balls and jets have no nuclear scattering term");
        assertFalse(glsl.contains("around"), "jets also emit direct light only");
    }

    @Test
    void aLowFlashDoesNotBrightenTheGroundAgainBeyondOneKilometre() throws IOException {
        GlslFunction gain = GlslFunction.parse(source().replaceAll("//[^\\n]*", ""), "heatBallGain");
        double previous = Double.POSITIVE_INFINITY;
        for (double d = 100; d <= 5000; d += 10) {
            double length = Math.hypot(d, 3);
            double now = gain.call(Map.of("d2", length * length, "r2", 36.0, "sd", length * 3.912 / 20000, "nv", 3 / length));
            assertTrue(now < previous, "horizontal ground, " + d + " m");
            previous = now;
        }
        assertEquals(0, HeatPass.ballGain(1e6, 36, 0.2, -0.5), 0, "a back-facing surface receives no direct light");
    }
}
