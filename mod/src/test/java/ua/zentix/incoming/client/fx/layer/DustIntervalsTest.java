package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The readers' dust transmittance: the Java copy equals the shader's, and a gap between two crossings is clear (review N7). */
class DustIntervalsTest {
    private static final Path GLSL = Path.of("src/main/resources/assets/incoming/shaders/include/wall_layer.glsl");

    @Test
    void javaCopyEqualsTheShader() throws IOException {
        String s = Files.readString(GLSL);
        GlslFunction t = GlslFunction.parse(s, "wallPrefixT"), share = GlslFunction.parse(s, "wallPrefixShare");
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            double aIn = r.nextDouble() * 100, aOut = aIn + r.nextDouble() * 200, bIn = aOut + (r.nextBoolean() ? 0 : r.nextDouble() * 300);
            double bOut = bIn + (bIn == aOut && r.nextBoolean() ? 0 : r.nextDouble() * 200), whole = r.nextDouble();
            double tA = bOut == bIn ? whole : whole + (1 - whole) * r.nextDouble(), sA = r.nextDouble(), z = r.nextDouble() * 900 - 50;
            Map<String, Double> v = Map.of("z", z, "aIn", aIn, "aOut", aOut, "tA", tA, "bIn", bIn, "bOut", bOut, "t", whole, "s", sA);
            assertEquals(DustIntervals.transmittance(z, aIn, aOut, tA, bIn, bOut, whole), t.call(v), 1e-9, v.toString());
            assertEquals(DustIntervals.lightShare(z, aIn, aOut, tA, bIn, bOut, whole, sA), share.call(v), 1e-9, v.toString());
        }
    }

    /** Two layers of optical depth 1 at [0, 10] and [90, 100]: e^−1 at 10 and through the gap, e^−2 behind (a single span gave 0.819 and 0.165). */
    @Test
    void theGapBetweenTwoCrossingsIsClear() {
        double tA = Math.exp(-1), t = Math.exp(-2);
        assertEquals(tA, DustIntervals.transmittance(10, 0, 10, tA, 90, 100, t), 1e-12);
        assertEquals(tA, DustIntervals.transmittance(50, 0, 10, tA, 90, 100, t), 1e-12);
        assertEquals(tA, DustIntervals.transmittance(90, 0, 10, tA, 90, 100, t), 1e-12);
        assertEquals(t, DustIntervals.transmittance(100, 0, 10, tA, 90, 100, t), 1e-12);
        assertEquals(Math.exp(-1.5), DustIntervals.transmittance(95, 0, 10, tA, 90, 100, t), 1e-9);
        assertEquals(0.6, DustIntervals.lightShare(50, 0, 10, tA, 90, 100, t, 0.6), 1e-12);
    }
}
