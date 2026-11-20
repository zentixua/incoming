package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke and dust particles beside the eye ({@link FxQuads#softTexels}, {@code fx.fsh}): a 64-pixel billow magnified to the
 * screen is smooth, not flat texel squares 40–60 pixels wide with staircase edges. Pixel-art pieces keep the nearest texel
 * and the hard 0.1 cut.
 */
class SoftTexelsGlslTest {
    private static final Path FX = Path.of("src/main/resources/assets/incoming/shaders/core/fx.fsh");

    private static GlslFunction function(String name) throws IOException {
        return GlslFunction.parse(Files.readString(FX).replaceAll("//[^\n]*", ""), name);
    }

    @Test
    void magnifiedTexelsAreSmooth() throws IOException {
        GlslFunction soft = function("softTexels");
        // a texel no larger than a pixel: the particle's own sampling as before
        assertEquals(0, soft.call(Map.of("level", 0.0)), 0);
        assertEquals(0, soft.call(Map.of("level", 0.7)), 0);
        // two screen pixels per texel and more: bilinear
        assertEquals(1, soft.call(Map.of("level", -1.0)), 0);
        assertEquals(1, soft.call(Map.of("level", -5.6)), 0);
        assertEquals(0.5, soft.call(Map.of("level", -0.5)), 1e-9);
    }

    @Test
    void faintEdgeFadesInsteadOfCutting() throws IOException {
        GlslFunction cut = function("particleCut");
        // pixel-art particle: the vanilla hard cut at 0.1
        assertEquals(0, cut.call(Map.of("a", 0.099, "lod", 0.0, "soft", 0.0)), 0);
        assertEquals(1, cut.call(Map.of("a", 0.101, "lod", 0.0, "soft", 0.0)), 0);
        // smoke: no step at the threshold, nothing below half of it, whole above one and a half
        double below = cut.call(Map.of("a", 0.099, "lod", 0.0, "soft", 1.0)), above = cut.call(Map.of("a", 0.101, "lod", 0.0, "soft", 1.0));
        assertTrue(Math.abs(above - below) < 0.05, below + " " + above);
        assertEquals(0, cut.call(Map.of("a", 0.05, "lod", 0.0, "soft", 1.0)), 0);
        assertEquals(1, cut.call(Map.of("a", 0.15, "lod", 0.0, "soft", 1.0)), 0);
        // far away (a quarter of the picture per pixel and smaller) nothing is cut, either way
        assertEquals(1, cut.call(Map.of("a", 0.001, "lod", 2.0, "soft", 1.0)), 0);
        assertEquals(1, cut.call(Map.of("a", 0.001, "lod", 2.0, "soft", 0.0)), 0);
    }

    @Test
    void smokeParticlesUseTheSoftModeByte() {
        // the shader's bands: particle above 0.75, nearest texel above 0.95 (Normal.y = byte / 127)
        double soft = FxQuads.SOFT_TEXELS / 127.0;
        assertTrue(soft > 0.75 && soft < 0.95, "soft " + soft);
        assertTrue(127 / 127.0 > 0.95);
    }
}
