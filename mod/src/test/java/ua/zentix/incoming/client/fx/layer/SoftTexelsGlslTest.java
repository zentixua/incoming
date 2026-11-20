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
 * screen is smooth, without flat texel squares or staircase edges. Pixel-art pieces keep the nearest texel and the hard
 * 0.1 cut.
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

    @Test
    void smoothReadStaysInsideTheSpritesCell() throws IOException {
        // fx.fsh clamps the smooth read into a SOFT_CELL square: every smoke sprite must be exactly one such atlas cell
        String fsh = Files.readString(FX);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("const float SOFT_CELL = ([0-9.]+);").matcher(fsh);
        assertTrue(m.find());
        assertEquals(FxAtlas.CELL, Double.parseDouble(m.group(1)), 0);
        for (int i = 0; i < 16; i++) {
            FxAtlas.Sprite s = FxAtlas.smoke(i);
            double x0 = s.u0() * FxAtlas.WIDTH, x1 = s.u1() * FxAtlas.WIDTH, y0 = s.v0() * FxAtlas.HEIGHT,
                    y1 = s.v1() * FxAtlas.HEIGHT;
            assertEquals(0, x0 % FxAtlas.CELL, 1e-3, "smoke " + i);
            assertEquals(0, y0 % FxAtlas.CELL, 1e-3, "smoke " + i);
            assertEquals(FxAtlas.CELL, x1 - x0, 1e-3, "smoke " + i);
            assertEquals(FxAtlas.CELL, y1 - y0, 1e-3, "smoke " + i);
        }
    }

    @Test
    void particlesWriteTheirTextureMode() {
        // regular smoke: soft; explicitly smooth smoke and any smooth particle: 0; a pixel-art piece: the nearest texel
        assertEquals(FxQuads.SOFT_TEXELS, mode(ua.zentix.incoming.client.fx.particle.Fx.Kind.SMOKE, false));
        assertEquals(0, mode(ua.zentix.incoming.client.fx.particle.Fx.Kind.SMOKE, true));
        assertEquals(127, mode(ua.zentix.incoming.client.fx.particle.Fx.Kind.SPARK, false));
        assertEquals(0, mode(ua.zentix.incoming.client.fx.particle.Fx.Kind.SPARK, true));
    }

    private static byte mode(ua.zentix.incoming.client.fx.particle.Fx.Kind kind, boolean smooth) {
        FxQuads out = new FxQuads();
        ua.zentix.incoming.client.fx.particle.FxParticle.texels(out, kind, smooth);
        return out.textureMode();
    }
}
