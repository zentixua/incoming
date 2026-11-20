package ua.zentix.incoming.client.fx.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The veil's Beer–Lambert share before the world ({@code fx.fsh} {@code veilBefore}) is drawn from the same mode byte,
 * light word and erf as its Java reference ({@code SmokePuffs.Veil.before}): a shader falling back to the linear ramp or
 * reading another mode would leave the reference test green.
 */
class VeilGlslTest {
    private static String read(String path) {
        try (InputStream in = VeilGlslTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static double mode(String glsl) {
        Matcher m = Pattern.compile("const float VEIL_MODE = ([0-9.]+) / ([0-9.]+);").matcher(glsl);
        assertTrue(m.find(), "VEIL_MODE");
        return Double.parseDouble(m.group(1)) / Double.parseDouble(m.group(2));
    }

    @Test
    void shaderReadsTheVeilAsJavaWritesIt() {
        String vsh = read("core/fx.vsh"), fsh = read("core/fx.fsh");
        assertEquals(FxQuads.VEIL / 127.0, mode(vsh), 1e-9);
        assertEquals(FxQuads.VEIL / 127.0, mode(fsh), 1e-9);
        // the light word carries middle and width in sixteenths, × opacity (SmokePuffs.Veil.packed)
        assertTrue(vsh.contains("veilMiddle = float(UV2.x) / 16.0 * Color.a;") && vsh.contains("veilWidth = float(UV2.y) / 16.0 * Color.a;"), "fx.vsh light word");
        assertTrue(vsh.contains("ivec2(0, 240) : UV2"), "the veil's light is open sky");
        // the veil branch uses the Beer–Lambert share, not the softness ramp
        assertTrue(fsh.contains("if (veil) seen *= veilBefore(scene, vertexColor.a);"), "fx.fsh veil branch");
        // the same erf as SmokeGrid.erf (Abramowitz–Stegun 7.1.26)
        for (String c : new String[]{"0.3275911", "1.061405429", "1.453152027", "1.421413741", "0.284496736", "0.254829592"}) {
            assertTrue(fsh.contains(c), "erf constant " + c);
        }
        assertTrue(fsh.contains("float tau = -log(max(1.0 - a, 1e-3));"), "the ray's depth as Veil.shaderTau reads it");
    }
}
