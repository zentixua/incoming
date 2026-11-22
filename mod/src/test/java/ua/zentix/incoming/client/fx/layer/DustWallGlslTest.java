package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The young dust wall's presentation model ({@code shaders/include/dustwall.glsl}, {@link DustWallPass}): its copies in Java
 * match the shader, and its head keeps the look Artem set (21.11): born at zero on the contact line, a crest 1.4–1.6x the
 * layer behind it, settled back to the layer within 0.5–1 s behind the crest.
 */
class DustWallGlslTest {
    private static final Path GLSL = Path.of("src/main/resources/assets/incoming/shaders/include/dustwall.glsl");

    private static String glsl() throws IOException {
        return Files.readString(GLSL);
    }

    /** {@code const float NAME = value} (also inside a comma list), from the shader. */
    private static double constant(String src, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*([-0-9.eE]+)").matcher(src);
        assertTrue(m.find(), name + " in dustwall.glsl");
        return Double.parseDouble(m.group(1));
    }

    @Test
    void javaCopiesMatchTheShader() throws IOException {
        String s = glsl();
        assertEquals(DustWallPass.SPAN, constant(s, "DW_SPAN"), 1e-9);
        assertEquals(DustWallPass.FADE, constant(s, "DW_FADE"), 1e-9);
        assertEquals(DustWallPass.NODES, (int) constant(s, "DW_NODES"));
        // the ray's slab top over the layer: the bound over the largest hump (dwHump: 1.54 + 0.22), the bulges and the haze over them
        assertEquals(DustWallPass.TOP_OVER_LAYER, 1.8 * (1 + constant(s, "DW_BULGE")) * constant(s, "DW_HAZE"), 1e-9);
        assertTrue(s.contains("return 1.54 + 0.22 * smoothstep"), "dwHump spans 1.54–1.76, under the bound 1.8");
    }

    /** {@code dwEnvelope} (same formula): the dust top at age a over a settled layer h = 1 with hump k. */
    private static double envelope(double a, double k, double rise, double hold, double fall) {
        if (a <= 0) return 0;
        double x = Math.max(a - hold, 0) / fall;
        return (1 - Math.exp(-a / rise)) * (1 + (k - 1) * Math.exp(-x * x));
    }

    @Test
    void headIsBornAtTheContactLineCrestsAndSettlesWithinASecond() throws IOException {
        String s = glsl();
        assertTrue(s.contains("return h * (1.0 - exp(-a / DW_RISE)) * (1.0 + (k - 1.0) * exp(-x * x));"),
                "dwEnvelope is the formula this test evaluates");
        double rise = constant(s, "DW_RISE"), hold = constant(s, "DW_HOLD"), fall = constant(s, "DW_FALL");
        for (double k : new double[] {1.54, 1.76}) {
            assertEquals(0, envelope(0, k, rise, hold, fall), 1e-12, "born at zero on the contact line");
            double peak = 0, peakAge = 0;
            for (double a = 0; a < 3; a += 0.005) {
                double e = envelope(a, k, rise, hold, fall);
                if (e > peak) {
                    peak = e;
                    peakAge = a;
                }
            }
            assertTrue(peak >= 1.4 - 1e-3 && peak <= 1.6 + 1e-3, "crest " + peak + "x the layer at hump " + k);
            assertTrue(peakAge < 0.5, "the crest stands right behind the contact line: " + peakAge + " s");
            // settled back to the layer (not to the ground) within 0.5–1 s behind the crest: within 5 % by 0.8 s
            assertTrue(Math.abs(envelope(0.8, k, rise, hold, fall) - 1) < 0.05, "settled by 0.8 s at hump " + k);
            assertTrue(Math.abs(envelope(1.0, k, rise, hold, fall) - 1) < 0.01, "settled by 1 s at hump " + k);
        }
    }

    /**
     * The billow texture (n, bl) and the billowed top live in the air the dust was raised in, at every age. A texture riding
     * with the front (its distance-behind-the-front coordinate {@code DwRing.x - r}) crosses a fixed eye in one or two frames
     * at 470 m/s as the front passes under it: the lower frame fluttered (harness passage second difference 2.7 against 1.1).
     */
    @Test
    void billowTextureIsFixedInTheAir() throws IOException {
        String s = glsl();
        Matcher n = Pattern.compile("\\bfloat n = [^;]*;").matcher(s), bl = Pattern.compile("\\bfloat bl = [^;]*;").matcher(s);
        assertTrue(n.find() && bl.find(), "texture lines in dwDensity");
        assertTrue(n.group().contains("tq") && bl.group().contains("pw."), "n and bl sample the air (tq, pw)");
        int from = s.indexOf("float n = "), to = s.indexOf("if (!coarse) gDwSurf = surf;");
        String texture = s.substring(from, to);
        assertTrue(!texture.contains("DwRing.x - r") && !texture.contains("n = mix(") && !texture.contains("bl = mix("),
                "no front-riding texture or crossfade between the air's texture and the billowed top");
    }
}
