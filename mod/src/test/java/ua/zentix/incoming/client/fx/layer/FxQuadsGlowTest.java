package ua.zentix.incoming.client.fx.layer;

import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The glow of the small glowing through the real {@link FxQuads}: the square is written as the layer writes it; the vertices of the frame
 *  are read as {@code fx.vsh} reads them (byte {@code Emit}, color, {@code UV2} spots), and are drawn by the
 *  glow pass model - sampling in the middle of its pixels (spot halo - {@code glowAlpha} from {@code fx.fsh}). The energy in the passage is
 *  the same as that of the source, regardless of its size and shift; without spots ({@code FxQuads.spots}) the test fails: point sampling
 *  either loses the source, or gives it a whole pixel.
 */
class FxQuadsGlowTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/incoming/shaders");
    private static GlslFunction glow, mag;

    @BeforeAll
    static void sheet() throws IOException {
        // sheet: place far/disc - white opaque (medium for glow - along it), the rest is empty
        int[] base = new int[FxAtlas.WIDTH * FxAtlas.HEIGHT];
        FxAtlas.Sprite disc = FxAtlas.disc();
        for (int y = Math.round(disc.v0() * FxAtlas.HEIGHT); y < Math.round(disc.v1() * FxAtlas.HEIGHT); y++) {
            Arrays.fill(base, y * FxAtlas.WIDTH + Math.round(disc.u0() * FxAtlas.WIDTH), y * FxAtlas.WIDTH + Math.round(disc.u1() * FxAtlas.WIDTH), 0xFFFFFFFF);
        }
        FxAtlas.emissionFrom(base);
        glow = GlslFunction.parse(Files.readString(SHADERS.resolve("core/fx.fsh")).replaceAll("//[^\n]*", ""), "glowAlpha");
        mag = GlslFunction.parse(Files.readString(SHADERS.resolve("include/bloom.glsl")).replaceAll("//[^\n]*", ""), "bloomMag");
    }

    /** The spot size code is the same in Java and in the shader, back and forth - with a step of 0,07%. */
    @Test
    void spotMagnitudeRoundTrips() {
        assertEquals(0, FxBloom.magnitude(0));
        assertEquals(0, mag.call(Map.of("code", 0.0)), 0);
        for (double v = 1e-4; v < 1e4; v *= 1.37) {
            int c = FxBloom.magnitude(v);
            assertEquals(1, FxBloom.magnitudeOf(c) / v, 0.00035, "magnitude " + v);
            assertEquals(FxBloom.magnitudeOf(c), mag.call(Map.of("code", (double) c)), 1e-9 * FxBloom.magnitudeOf(c), "shader, code " + c);
        }
    }

    /**
     * White circle-core (like {@code FarSprites.core}) from 0,05 to 2,8 pixel of the glow passage and in different places relative to its
     * pixels: light in the passage - its solid angle within 2% (spots; without them - from zero to many times).
     */
    @Test
    void smallEmitterKeepsItsEnergyInTheGlowPass() {
        double texel = 0.002, d = 50;
        FxQuads quads = new FxQuads();
        for (double size = 0.05; size < 2.9; size *= 1.21) {
            for (double shift = 0; shift < 1; shift += 0.137) {
                quads.begin(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
                quads.glowTexel(texel);
                quads.emission(1);
                double half = size / 2 * texel * d, cx = (shift + 0.31) * texel * d, cy = (0.7 * shift + 0.12) * texel * d;
                quads.billboard((float) cx, (float) cy, (float) -d, (float) d, (float) half, 0, FxAtlas.disc(), 1, 1, 1, 1, 0, 1,
                        LightTexture.FULL_BRIGHT, 0);
                quads.emission(0);
                double expected = Math.pow(size, 2), got = glowPass(quads, texel);
                assertEquals(1, got / expected, 0.02, "core " + size + " pixel pass, shift " + shift);
            }
        }
    }

    /**
     * The light of the frame squares in the glow pass (in pass pixels²): vertices - as {@code fx.vsh} reads them; square,
     * , glowing in spots, does not go into the passage; luminous - with the light of color in the middle of the pixels in it; the spot is a halo.
     */
    private static double glowPass(FxQuads quads, double texel) {
        double sum = 0;
        for (int i = 0; i < quads.size(); i++) {
            long q = quads.address(i);
            int emit = MemoryUtil.memGetByte(q + 35) & 255;
            if (emit < FxBloom.EMITTER) continue;
            // screen: x/−z, y/−z in pass pixels
            double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                long p = q + k * 36L;
                double z = -MemoryUtil.memGetFloat(p + 8), x = MemoryUtil.memGetFloat(p) / z / texel, y = MemoryUtil.memGetFloat(p + 4) / z / texel;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
            }
            double r = (MemoryUtil.memGetByte(q + 20) & 255) / 255.0;
            boolean spot = emit >= FxBloom.SPOT;
            double light, cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, half = (x1 - x0) / 2;
            if (spot) {
                light = r * mag.call(Map.of("code", (double) (MemoryUtil.memGetShort(q + 28) & 0xFFFF)));
            } else {
                light = Math.pow(r, 2.2) * FxBloom.gain(emit - FxBloom.EMITTER);
            }
            for (int j = (int) Math.floor(y0); j <= y1; j++) {
                for (int k = (int) Math.floor(x0); k <= x1; k++) {
                    double px = k + 0.5, py = j + 0.5;
                    if (px < x0 || px > x1 || py < y0 || py > y1) continue;
                    double rr = Math.hypot(px - cx, py - cy) / half * 64 / 63;
                    sum += spot ? light * glow.call(Map.of("r", rr)) : light;
                }
            }
        }
        return sum;
    }

    /**
     * The glow passage takes what covers the fire and its halo: a luminous circle in 50 half-size blocks 5, smoke behind it within
     *  within its half-size (53) and in front of it (45) - in the passage, smoke further (60) - no. At the beginning of the most luminous
     * , the smoke in the 53 blocks (the sleeve on the side at the depth of the middle of the ball) did not go into the passage and the halo remained above it.
     */
    @Test
    void glowPassTakesWhatStandsWithinTheFire() {
        FxQuads quads = new FxQuads();
        quads.begin(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
        quads.glowTexel(0.002);
        quads.billboard(-8, 0, -60, 60, 2, 0, FxAtlas.disc(), 0.2f, 0.2f, 0.2f, 0.8f, 0, 1, LightTexture.FULL_BRIGHT, 0);
        quads.billboard(8, 0, -53, 53, 2, 0, FxAtlas.disc(), 0.2f, 0.2f, 0.2f, 0.8f, 0, 1, LightTexture.FULL_BRIGHT, 0);
        quads.emission(1);
        quads.billboard(0, 0, -50, 50, 5, 0, FxAtlas.disc(), 1, 1, 1, 1, 0, 1, LightTexture.FULL_BRIGHT, 0);
        quads.emission(0);
        quads.billboard(0, 4, -45, 45, 2, 0, FxAtlas.disc(), 0.2f, 0.2f, 0.2f, 0.8f, 0, 1, LightTexture.FULL_BRIGHT, 0);
        assertEquals(4, quads.size());
        assertEquals(1, quads.glowStart(), "from smoke in 53 blocks: further - 60, there is none");
    }

    /** The spot and its square: the square is only in the layer, the spot is only in the pass, with a tint in color and light in {@code UV2}. */
    @Test
    void spotCarriesItsLightInUv2() {
        double texel = 0.002, d = 50;
        FxQuads quads = new FxQuads();
        quads.begin(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
        quads.glowTexel(texel);
        quads.emission(1);
        double half = 0.2 * texel * d;
        quads.billboard(0, 0, (float) -d, (float) d, (float) half, 0, FxAtlas.disc(), 1, 0.5f, 0.25f, 1, 0, 1, LightTexture.FULL_BRIGHT, 0);
        assertEquals(2, quads.size(), "square and its spot");
        assertEquals(FxBloom.SKIP, MemoryUtil.memGetByte(quads.address(0) + 35) & 255, "square - only in layer");
        long spot = quads.address(1);
        assertEquals(FxBloom.SPOT, MemoryUtil.memGetByte(spot + 35) & 255, "spot");
        assertEquals(255, MemoryUtil.memGetByte(spot + 20) & 255, "largest hue channel - whole byte");
        assertEquals(Math.pow(0.5, 2.2), (MemoryUtil.memGetByte(spot + 21) & 255) / 255.0, 0.003, "hue - the ratio of linear light channels");
        assertTrue((MemoryUtil.memGetShort(spot + 28) & 0xFFFF) > 0, "spot light - in UV2");
    }
}
