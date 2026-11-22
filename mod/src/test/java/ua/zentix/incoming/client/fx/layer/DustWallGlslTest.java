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
     * The billow texture (n, bl) is fixed in the air across the ground and carried in height by the dust's own envelope
     * (yc): no texture rides with the front. Riding with it, the texture slid outward at the front's speed and crossed the
     * eye's rays in one or two frames as the front passed under it (the lower frame fluttered); sliced at fixed heights,
     * the 470 m/s head swept through new air every frame.
     */
    @Test
    void billowTextureIsFixedInTheAirAndRisesWithTheDust() throws IOException {
        String s = glsl();
        Matcher n = Pattern.compile("\\bfloat n = [^;]*;").matcher(s), bl = Pattern.compile("\\bfloat bl = [^;]*;").matcher(s);
        assertTrue(n.find() && bl.find(), "texture lines in dwDensity");
        assertTrue(n.group().contains("tq") && bl.group().contains("pw.x, yc"), "n and bl sample the air (tq, pw) at the dust's height");
        assertTrue(s.contains("float yc = q.y * h / max(env, 2.0)"), "height as a share of the envelope");
        String texture = s.substring(s.indexOf("float yc = "), s.indexOf("if (!coarse) gDwSurf = surf;"));
        assertTrue(!texture.contains("DwRing.x - r") && !texture.contains("headTex"), "no texture riding with the front");
        String surface = s.substring(s.indexOf("float surf = "), s.indexOf("if (!coarse) gDwSurf = surf;"));
        assertTrue(!surface.contains("smoothstep") && !surface.contains("bf"), "the billowed top is the texture's at every age");
    }

    /**
     * The head's coordinate along the front is bearing × a fixed arc radius: bearing × the front's current radius slid every
     * feature along the front at bearing × the front's speed (~600–740 m/s), and the ring read as turning around ground zero
     * (tools/dust_wall_gl.py measures the head's arc: no shift along the front).
     */
    @Test
    void headFeaturesKeepTheirBearing() throws IOException {
        String s = glsl();
        assertTrue(!Pattern.compile("atan\\([^)]*\\) \\* DwRing").matcher(s).find(), "no bearing × the front's radius");
        assertTrue(s.contains("hand * sin("), "residual orbits turn either way");
        // LOOK Trait 0: nothing is placed by its distance behind the front (that coordinate rides out at the front's speed)
        assertTrue(!s.contains("DwRing.x - r"), "no feature rides with the front");
    }
}
