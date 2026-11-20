package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

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
    void bilinearReadsAtTheSpriteEdgeTakeOnlyTransparentTexels() throws IOException {
        // smoothly sampled level 0 reads half a texel past the sprite: the smoke row's own borders, its row neighbours and
        // the slots below it (FxAtlas: smoke row 0; fire, flash, glow, disc, spark, flake, paper in row 1) are transparent
        Path tex = Path.of("src/main/resources/assets/incoming/textures");
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < 16; i++) slots.add(String.format(java.util.Locale.ROOT, "fx/particle/smoke_%02d", i));
        for (int i = 0; i < 8; i++) slots.add(String.format(java.util.Locale.ROOT, "fx/particle/fire_%02d", i));
        slots.addAll(List.of("fx/particle/flash", "far/glow", "far/disc", "fx/particle/spark", "fx/particle/flake",
                "fx/particle/paper"));
        for (String slot : slots) {
            BufferedImage image = ImageIO.read(tex.resolve(slot + ".png").toFile());
            int w = image.getWidth(), h = image.getHeight();
            for (int x = 0; x < w; x++) {
                assertEquals(0, image.getRGB(x, 0) >>> 24, slot + " top " + x);
                assertEquals(0, image.getRGB(x, h - 1) >>> 24, slot + " bottom " + x);
            }
            for (int y = 0; y < h; y++) {
                assertEquals(0, image.getRGB(0, y) >>> 24, slot + " left " + y);
                assertEquals(0, image.getRGB(w - 1, y) >>> 24, slot + " right " + y);
            }
        }
    }
}
