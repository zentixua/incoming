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
 * words as its Java reference ({@code SmokePuffs.Veil.before}): a shader falling back to the linear ramp or
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
        // the light word carries middle and width in quarters, × opacity (SmokePuffs.Veil.packed)
        assertTrue(vsh.contains("veilMiddle = float(UV2.x) / 4.0 * (Color.a - veilNear);") && vsh.contains("veilWidth = float(UV2.y) / 4.0 * (Color.a - veilNear);"), "fx.vsh light word");
        // the near part: share in Normal.z (byte /127, SmokePuffs.Veil.byteShare), width in the softness (sixteenths)
        assertTrue(vsh.contains("veilNear = Normal.z * Color.a;") && vsh.contains("veilNearWidth = softness * veilNear;"), "fx.vsh near part");
        assertTrue(fsh.contains("float depth = tau * (n * clamp(scene / w0, 0.0, 1.0) + (1.0 - n) * beyond);"), "fx.fsh two parts");
        assertTrue(fsh.contains("float beyond = clamp((scene - from) / max(to - from, 0.25), 0.0, 1.0);"), "fx.fsh part ahead");
        // the hand is left alone, at the same distance as the pall's (SmokePuffs.HAND)
        assertTrue(fsh.contains("|| pallHand(scene)) return 0.0;"), "the hand");
        // the mesh at the eye is not faded at the near plane
        assertTrue(fsh.contains("if (!veil) seen *= clamp((viewDistance - 0.3) / 1.2, 0.0, 1.0);"), "near-plane fade spares the veil");
        assertTrue(vsh.contains("ivec2(0, 240) : UV2"), "the veil's light is open sky");
        // the veil branch uses the Beer–Lambert share, not the softness ramp
        assertTrue(fsh.contains("if (veil) seen *= veilBefore(scene, vertexColor.a);"), "fx.fsh veil branch");
        assertTrue(fsh.contains("float tau = -log(max(1.0 - a, 1e-3)) * pow(1000.0, fog);"), "the ray's depth as Veil.shaderTau × byteDeeper reads it");
        assertTrue(fsh.contains("if (!veil) seen *= 1.0 - fog * FxFogColor.a;"), "the veil's fog byte is its depth, not Minecraft fog");
    }
}
