package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.FxAtlas;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quad-to-equal-flux-circle-radius multipliers match the fraction of the quad covered by the textures: without
 * this, distant flash and fireball halos would carry 9 times less light than intended (light would be drawn on a
 * quad the circle's size, while the core texture covers 0.084 of the quad). Textures are smoothed and non-repeating;
 * otherwise pixel edges and clipping at the quad edge appear.
 */
class FarSpritesTest {
    private static BufferedImage image(String path) throws IOException {
        try (InputStream in = FarSpritesTest.class.getResourceAsStream("/assets/incoming/" + path)) {
            assertNotNull(in, path);
            return ImageIO.read(in);
        }
    }

    /** Average opacity of rows from..to inclusive. */
    private static double coverage(BufferedImage img, int from, int to) {
        double sum = 0;
        for (int y = from; y <= to; y++) {
            for (int x = 0; x < img.getWidth(); x++) sum += (img.getRGB(x, y) >>> 24) / 255.0;
        }
        return sum / (img.getWidth() * (to - from + 1));
    }

    private static double coverage(String path) throws IOException {
        BufferedImage img = image(path);
        return coverage(img, 0, img.getHeight() - 1);
    }

    /** Half the size of a square by the radius of a circle, which the texture with this coating replaces at opacity 1. */
    private static double factor(double coverage) {
        return Math.sqrt(Math.PI / (4 * coverage));
    }

    @Test
    void factorsMatchTextures() throws IOException {
        assertEquals(FarSprites.DISC, factor(coverage("textures/far/disc.png")), 0.02, "circle");
        assertEquals(FarSprites.GLOW, factor(coverage("textures/far/glow.png")), 0.05, "halo");
    }

    /** A fraction of the pixels are more opaque than half. */
    private static double opaque(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) >>> 24) > 127) n++;
        }
        return (double) n / (img.getWidth() * img.getHeight());
    }

    /**
     * fireball far and near - a square in {@code half} of the radius of the model ball at this moment ({@code fireball.json}: the frame
     *  contains all the matter): in each frame of combustion ({@code tools/bake_fx/pack.py} frames the ball) a circle of the same area,
     *  is the opaque part - the radius of the ball. Otherwise, the ball near and far is of different sizes, and the glare and light on the smoke is not due to what is visible.
     */
    @Test
    void fireballFramesMatchItsRadius() throws IOException {
        com.google.gson.JsonArray half;
        try (InputStream in = FarSpritesTest.class.getResourceAsStream("/assets/incoming/textures/fx/particle/fireball.json")) {
            assertNotNull(in);
            half = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("half");
        }
        for (int i = 0; i < FxAtlas.FIREBALL_BURN; i++) {
            BufferedImage img = image(String.format(Locale.ROOT, "textures/fx/particle/fireball_%02d.png", i));
            double radius = Math.sqrt(4 * opaque(img) / Math.PI) * half.get(i).getAsDouble();
            assertEquals(1, radius, 0.08, "frame " + i);
        }
    }

    /**
     * The former procedural ball ({@code fx/particle/ball_*}: ball particles) grows in its frames - the radius is a fraction of the full, as
     *  writes {@code tools/gen_particles.py}: 0,3 + 0,7 · (1 − e^(−u/0,05)), to the end of +6%.
     */
    @Test
    void proceduralBallFramesMatchItsGrowth() throws IOException {
        for (int i = 0; i < FxAtlas.BALL_FRAMES / 2; i++) {
            BufferedImage img = image(String.format(Locale.ROOT, "textures/fx/particle/ball_%02d.png", i));
            double radius = Math.sqrt(4 * opaque(img) / Math.PI) * FarSprites.FIREBALL;
            double f = (i + 0.5) / FxAtlas.BALL_FRAMES;
            assertEquals((0.3 + 0.7 * (1 - Math.exp(-f / 0.05))) * (1 + 0.06 * f), radius, 0.08, "frame " + i);
        }
    }

    /** The thermal frame has an upside: a random full rotation would send the flame into the ground. */
    @Test
    void bakedFireballCannotTurnItsFlameDown() {
        assertTrue(Math.abs(FarSprites.fireballRotation()) <= Math.toRadians(10));
        assertEquals(0, FarSprites.fireballRotation(), "variety - reflection of u, not thermal tilt");
    }

    @Test
    void flameFactorMatchesTextures() throws IOException {
        double sum = 0;
        for (int i = 0; i < 8; i++) sum += coverage(String.format(Locale.ROOT, "textures/fx/particle/fire_%02d.png", i));
        assertEquals(FarSprites.FIRE, factor(sum / 8), 0.1, "bills of flame");
    }

    @Test
    void ribbonFactorMatchesMiddleRow() throws IOException {
        // the tape takes the line v = 0,5: when smoothing, the average of the two middle lines
        BufferedImage glow = image("textures/far/glow.png");
        int mid = glow.getHeight() / 2;
        assertEquals(FarSprites.RIBBON, 1 / coverage(glow, mid - 1, mid), 0.03);
    }

    @Test
    void ballSeenFromAboveStaysOutOfTheGround() {
        // rocket ground ball (radius 11, middle at radius 0,45 above ground) in 85 blocks, looking down at 54°; soft
        // edge - FxQuads.SOFT half frame size. The frame point on 0,6 with a radius below the middle is the part of the dome closest to the eye
        double r = 11, d = 85, pitch = Math.toRadians(54), soft = 0.5 * FarSprites.FIREBALL * r, s = 0.6 * r;
        double centre = d * Math.sin(pitch), ground = d * (centre + 0.45 * r) / (centre + s * Math.cos(pitch));
        double plane = (ground - d) / soft, front = (ground - d * FarSprites.front(d, FarSprites.BALL_FRONT * r)) / soft;
        assertTrue(plane < 0.3, "the bottom of the ball goes into the ground with a plane through the middle: " + plane);
        assertTrue(front > 0.8, "the dome is visible at the front half: " + front);
        assertEquals(d - 5, d * FarSprites.front(d, 5), 1e-9, "the same angular value at 5 blocks closer");
        assertEquals(1, FarSprites.front(1, 5), 1e-9, "right next to the eye - in place");
        assertEquals(1, FarSprites.front(85, 0), 1e-9);
    }

    /**
     * The stream close up is a strip with a halo profile {@code far/glow} across, {@link FarSprites#STRIP_CROSS} times wider: the light
     *  across is the same as that of an even strip of a circle {@code far/disc} of the same width (profiles are the same as {@code tools/gen_particles.py}
     *  {@code glowAlpha}). An error in the width fraction (integral instead of fraction) made the jet twice as narrow as the model (review F6).
     */
    @Test
    void jetStripCarriesTheLightOfItsWidth() {
        int n = 20_000;
        double disc = 0, glow = 0;
        for (int i = 0; i < n; i++) {
            double u = -1 + (i + 0.5) * 2.0 / n, r = Math.abs(u);
            disc += smooth(0, 0.15, 1 - r);
            glow += Math.exp(-(r / 0.42) * (r / 0.42)) * smooth(0, 0.25, 1 - r);
        }
        assertEquals(disc / glow, FarSprites.STRIP_CROSS, 0.03 * FarSprites.STRIP_CROSS);
    }

    private static double smooth(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
