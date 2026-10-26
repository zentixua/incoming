package ua.zentix.incoming.client.fx.layer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Baking frames ({@code tools/bake_fx/pack.py}: {@code fireball.json}, {@code puff6.json} and pictures) are in place, the
 *  size and number that awaits the layer sheet ({@link FxAtlas}), and all together are not heavier than the limit in jar.
 */
class FxAtlasTest {
    private static final String DIR = "/assets/incoming/textures/fx/particle/";
    /** Effect frames in jar - no more than a byte (task V: everything baked together - up to 8 MB). */
    static final long JAR_BUDGET = 8L << 20;

    private static Reader open(String name) {
        InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + name);
        assertNotNull(in, name);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static JsonObject json(String name) throws IOException {
        try (Reader r = open(name)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static void image(String name, int size) throws IOException {
        try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + name)) {
            assertNotNull(in, name);
            BufferedImage img = ImageIO.read(in);
            assertEquals(size, img.getWidth(), name);
            assertEquals(size, img.getHeight(), name);
        }
    }

    /**
     * fireball: 48 frames according to 256 (32 - combustion) and their motion vectors according to 128, the burning time increases, as it cools down it gets colder,
     *  the fire on the screen goes out to soot; the frame is not narrower than before (1,3 ball radius).
     */
    @Test
    void fireballFramesMatchTheBake() throws IOException {
        JsonObject o = json("fireball.json");
        int tile = o.get("tile").getAsInt();
        assertEquals(FxAtlas.FIREBALL_FRAMES, o.get("frames").getAsInt());
        assertEquals(FxAtlas.FIREBALL_BURN, o.get("burn_frames").getAsInt());
        assertEquals(FxAtlas.FIREBALL_SIZE, tile, "place of the ball frame on the sheet - 256");
        assertEquals(FxAtlas.FIREBALL_FLOW, o.get("flow").getAsFloat(), 1e-6, "the limit of motion vectors is what the shader expects");
        assertEquals(FxAtlas.FIREBALL_FLOW_SIZE, o.get("flow_tile").getAsInt());
        for (int i = 0; i < FxAtlas.FIREBALL_FRAMES; i++) {
            image(String.format(Locale.ROOT, "fireball_%02d.png", i), tile);
            image(String.format(Locale.ROOT, "fireballv_%02d.png", i), FxAtlas.FIREBALL_FLOW_SIZE);
        }
        FxAtlas.Fireball f;
        try (Reader r = open("fireball.json")) {
            f = FxAtlas.parseFire(r);
        }
        // the entire ball of fuel glows from the first moment (CCPS): the first frame is almost the brightest; frames - after exposure to fire
        //  (pack.py film_exposure, presentation), in the screen measure: brighter than the mid-tones, but not a flat white disk (with it folds and
        //  soot are not visible - ball 3.6)
        int peak = 0;
        for (int i = 1; i < FxAtlas.FIREBALL_FRAMES; i++) if (f.fire()[i] > f.fire()[peak]) peak = i;
        assertTrue(f.fire()[0] >= 0.85 * f.fire()[peak], "flash is almost the brightest frame");
        // (frames from the render 512 on the substance - peak 0,345, circle 2 - 0,37, 3.6 - 0,87: flat disk)
        assertTrue(f.fire()[peak] >= 0.33 && f.fire()[peak] <= 0.75, "the fire on the screen is bright, but with folds: " + f.fire()[peak]);
        assertTrue(peak < FxAtlas.FIREBALL_FRAMES / 4, "brightest at the beginning of combustion: frame " + peak);
        for (int i = 1; i < FxAtlas.FIREBALL_FRAMES; i++) {
            assertTrue(f.burn()[i] > f.burn()[i - 1], "frame time increases: " + i);
            if (i > peak) assertTrue(f.fire()[i] <= f.fire()[i - 1] + 0.02, "fire goes out: frame " + i);
            if (f.burn()[i - 1] >= 1) assertTrue(f.kelvin()[i] <= f.kelvin()[i - 1], "cooling down - colder: frame " + i);
        }
        assertTrue(f.fire()[FxAtlas.FIREBALL_FRAMES - 1] < 0.01, "last frame - soot");
        assertTrue(f.burn()[FxAtlas.FIREBALL_BURN - 1] >= 1 && f.burn()[FxAtlas.FIREBALL_BURN - 2] < 1, "burning - 32 frame");
        for (int i = 0; i < FxAtlas.FIREBALL_FRAMES; i++) assertTrue(f.half()[i] >= 1.3 - 1e-3, "frame is not narrower than 1,3 of the radius of the ball: " + i);
        // frame according to the state of the ball: burning - according to time, cooling - according to temperature, to the Draper point - the last
        assertEquals(0, FxAtlas.fireballFrameOf(f, 0, 1484, 1484), 1e-9);
        assertTrue(FxAtlas.fireballFrameOf(f, 0.5, 1484, 1484) > 2, "mid-burn - no flash");
        assertTrue(FxAtlas.fireballFrameOf(f, 2, 800, 1484) >= FxAtlas.FIREBALL_FRAMES - 3, "cooled down - near the soot");
        // cooling occurs immediately from the end of combustion, without a freeze frame: a ball of fuel (1484 K) and explosives (1500 K) - according to the share of
        //  cooling to the Draper point, and do not wait until they cool down to the simulation flame at its end
        for (double endK : new double[]{1484, 1500}) {
            double end = FxAtlas.fireballFrameOf(f, 1, endK, endK);
            assertEquals(FxAtlas.FIREBALL_BURN - 1, end, 0.05, "end of combustion - last frame of combustion");
            double last = end;
            for (double k = endK - 20; k > 820 && last < FxAtlas.FIREBALL_FRAMES - 1; k -= 20) {
                double frame = FxAtlas.fireballFrameOf(f, 1.5, k, endK);
                assertTrue(frame > last, "cooling down at 20 K, the frame goes on: " + k + " K, frame " + frame);
                assertTrue(frame - last < 2.5, "and without jumping through frames: " + k + " K");
                last = frame;
            }
        }
    }

    /** The average brightness does not catch a smooth disk: the folds should be different in the opaque core; The border is checked separately by fireballRimIsWarmAndNotGhostly. */
    @Test
    void burningFireballKeepsItsInteriorContrast() throws IOException {
        for (int i = 0; i < 10; i++) {
            try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + String.format(Locale.ROOT, "fireball_%02d.png", i))) {
                assertNotNull(in);
                BufferedImage img = ImageIO.read(in);
                double[] light = new double[img.getWidth() * img.getHeight()];
                int count = 0;
                double middle = (img.getWidth() - 1) / 2.0, radius = 0.35 * img.getWidth();
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int rgba = img.getRGB(x, y);
                        if ((rgba >>> 24) <= 230 || Math.hypot(x - middle, y - middle) >= radius) continue;
                        light[count++] = (0.2126 * (rgba >> 16 & 255) + 0.7152 * (rgba >> 8 & 255) + 0.0722 * (rgba & 255)) / 255;
                    }
                }
                assertTrue(count > 100, "there is an opaque fire inside the ball: frame " + i);
                Arrays.sort(light, 0, count);
                // Difference in 15% white screen: noticeable creases, not noise in almost cut off white (3.6: 4–11%).
                double contrast = light[9 * count / 10] - light[count / 10];
                assertTrue(contrast > 0.15, "folds inside the fire are visible: frame " + i + ", contrast " + contrast);
            }
        }
    }

    /** Even the thin flame remains warm: not a yellow transparent film blending into the khaki background. */
    @Test
    void fireballRimIsWarmAndNotGhostly() throws IOException {
        for (int i = 0; i < 10; i++) {
            try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + String.format(Locale.ROOT, "fireball_%02d.png", i))) {
                assertNotNull(in);
                BufferedImage img = ImageIO.read(in);
                int orange = 0, hot = 0, white = 0;
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int rgba = img.getRGB(x, y);
                        double a = (rgba >>> 24) / 255.0, r = (rgba >> 16 & 255) / 255.0,
                                g = (rgba >> 8 & 255) / 255.0, b = (rgba & 255) / 255.0;
                        if (a < 0.05) continue;
                        double light = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                        assertTrue(a + 2.0 / 255 >= light, "bright fire not transparent film: frame " + i);
                        if (a < 0.6 && r > 0.3) assertTrue(g < 0.7 * r, "thin flame turns red: frame " + i);
                        if (a > 0.6 && r > 0.5) {
                            hot++;
                            if (g < 0.75 * r) orange++;
                            if (b > 0.1 && r > 0.8) white++;
                        }
                    }
                }
                assertTrue(orange > hot / 2, "the body is orange, not lemon: frame " + i);
                if (i < 4) assertTrue(white > 0, "hot frames have a light core: frame " + i);
            }
        }
    }

    /**
     * The frame contains all the substance: the edge of the ball frame is empty (review #426: the cooling soot was cut off in a circle along the radius of the frame,
     *  and a “ghost sphere” with a smooth edge hung in the game).
     */
    @Test
    void fireballFramesHoldAllTheirMatter() throws IOException {
        for (int i = 0; i < FxAtlas.FIREBALL_FRAMES; i++) {
            BufferedImage img = read(String.format(Locale.ROOT, "fireball_%02d.png", i));
            int w = img.getWidth(), h = img.getHeight(), edge = 0;
            for (int k = 0; k < w; k++) {
                for (int m : new int[]{0, 1, h - 2, h - 1}) edge = Math.max(edge, Math.max(img.getRGB(k, m) >>> 24, img.getRGB(m, k) >>> 24));
            }
            assertTrue(edge <= 4, "at the edge of the frame " + i + " no substance: alpha " + edge);
        }
    }

    /**
     * Soot cooling without seed rendering (review #426: cooling frames are speckled). The reason is sampling: noise reduction Cycles cleans
     *  light, but not the opacity of transparent soot, and with 32 samples per pixel and adaptive sampling, the noise remained in soote;
     * increasing the frame 1,3 of the radius of the ball from the render 256 stretched it into spots. Render - no less than 256 samples without
     *  adaptive and twice as large as the frame (the frame of the ball in combustion - without enlargement, the cooling frame is wider - its pixel is the average of
     *  of several render pixels): this is what {@code tools/bake_fx/pack.py} writes from {@code meta.json} baking. The measure on
     *  pixels of the frame of the seed circle 2 did not catch: stretched noise - spots in 2–3 pixels, and not the spread of neighbors.
     */
    @Test
    void coolingSootIsRenderedWithoutGrain() throws IOException {
        JsonObject o = json("fireball.json");
        assertTrue(o.has("samples") && o.get("samples").getAsInt() >= 256, "samples per pixel - no less than 256");
        assertTrue(o.has("adaptive_sampling") && !o.get("adaptive_sampling").getAsBoolean(), "without adaptive sampling");
        assertTrue(o.has("render") && o.get("render").getAsInt() >= 2 * o.get("tile").getAsInt(), "render twice the size of the frame");
    }

    private static BufferedImage read(String name) throws IOException {
        try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + name)) {
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    /** The reflection does not flip the top; 256² cells do not intersect smoke maps and each other. */
    @Test
    void fireballSlotsKeepTheTopAndTheirOwnSpace() {
        for (int i = 0; i < FxAtlas.FIREBALL_FRAMES; i++) {
            var a = FxAtlas.fireballAt(i, false);
            var b = FxAtlas.fireballAt(i, true);
            assertEquals(a.u0(), b.u1());
            assertEquals(a.u1(), b.u0());
            assertEquals(a.v0(), b.v0());
            assertEquals(a.v1(), b.v1());
            assertEquals(FxAtlas.FIREBALL_U, a.u1() - a.u0(), 1e-6);
            assertEquals(FxAtlas.FIREBALL_V, a.v1() - a.v0(), 1e-6);
            assertTrue(a.v0() >= FxAtlas.FIREBALL_TOP && a.v1() <= 1);
            assertTrue(FxAtlas.spray6(15, false).v1() + 2 * FxAtlas.PUFF6_STEP <= a.v0() + 1e-6);
            // frame movement vectors - under the frames, in their cells
            float fu = i % FxAtlas.FIREBALL_FLOW_COLUMNS * FxAtlas.FIREBALL_FLOW_U,
                    fv = FxAtlas.FIREBALL_FLOW_TOP + i / FxAtlas.FIREBALL_FLOW_COLUMNS * FxAtlas.FIREBALL_FLOW_V;
            assertTrue(fv >= FxAtlas.fireballAt(FxAtlas.FIREBALL_FRAMES - 1, false).v1() - 1e-6 && fv + FxAtlas.FIREBALL_FLOW_V <= 1 + 1e-6);
            assertTrue(fu + FxAtlas.FIREBALL_FLOW_U <= 1 + 1e-6);
            for (int j = 0; j < i; j++) {
                var c = FxAtlas.fireballAt(j, false);
                assertTrue(a.u0() >= c.u1() || a.u1() <= c.u0() || a.v0() >= c.v1() || a.v1() <= c.v0());
            }
        }
    }

    /** At the border of the frames, the weight of the mixture reaches the next one, without resetting the brightness or opacity. */
    @Test
    void fireballFrameBlendIsContinuous() {
        for (int i = 1; i < FxAtlas.FIREBALL_FRAMES; i++) {
            assertEquals(1, FxAtlas.fireballMix(i - 1e-5), 2e-5);
            assertEquals(0, FxAtlas.fireballMix(i), 0);
            assertEquals(FxAtlas.fireballAt(i, false), FxAtlas.fireballAt(i + 0.5, false));
        }
        assertEquals(0.5, FxAtlas.fireballMix(3.5), 1e-6);
        assertEquals(0, FxAtlas.fireballMix(FxAtlas.FIREBALL_FRAMES - 1));
        assertEquals(0, FxAtlas.fireballMix(FxAtlas.FIREBALL_FRAMES + 3));
        assertEquals(0, FxAtlas.fireballMix(-1));
    }

    /** billows: 16 frames of three maps 128, edge growth is from 1 and growing, the limit of motion vectors is what the shader expects. */
    @Test
    void puffFramesMatchTheBake() throws IOException {
        JsonObject o = json("puff6.json");
        int tile = o.get("tile").getAsInt();
        assertEquals(FxAtlas.PUFF6_FRAMES, o.get("frames").getAsInt());
        assertEquals("abv", o.get("maps").getAsString());
        assertEquals(FxAtlas.PUFF6_FLOW, o.get("flow").getAsFloat(), 1e-6);
        assertEquals((float) tile / 1024, FxAtlas.PUFF6_U, 1e-6, "frame location on the sheet - baking frame size");
        assertEquals((float) tile / FxAtlas.HEIGHT, FxAtlas.PUFF6_V, 1e-6);
        for (int i = 0; i < FxAtlas.PUFF6_FRAMES; i++) {
            for (char m : "abv".toCharArray()) image(String.format(Locale.ROOT, "puff6%c_%02d.png", m, i), tile);
        }
        float[] growth;
        try (Reader r = open("puff6.json")) {
            growth = FxAtlas.parseGrowth(r);
        }
        assertEquals(1, growth[0], 1e-6);
        for (int i = 1; i < growth.length; i++) assertTrue(growth[i] >= growth[i - 1], "billows is growing: frame " + i);
        try (Reader r = open("puff6.json")) {
            float[] light = FxAtlas.parseLight(r);
            assertTrue(light[1] > 0 && light[2] > 0, "dense light in front and medium - yes");
        }
    }

    /**
     * The cloud takes only swirling frames ({@link FxAtlas#turbulentLast}): in the baked billows, by the end of the simulation the fine
     *  structure goes away (the final billows dissipates), and the clouds at 150–300 m were smooth blurry balls (look gate RV2).
     * The last frame - with a fine structure not less than 2/3 largest; Even shots of the end are not taken.
     */
    @Test
    void cloudsKeepTheirClumps() throws IOException {
        for (String name : new String[]{"puff6.json", "spray6.json"}) {
            float[] detail;
            try (Reader r = open(name)) {
                detail = FxAtlas.parseDetail(r);
            }
            assertNotNull(detail, name + ": fine frame structure exists (tools/bake_fx/pack.py --detail)");
            int last = FxAtlas.turbulentLast(detail);
            float top = 0;
            for (float v : detail) top = Math.max(top, v);
            for (int i = 0; i <= last; i++) assertTrue(detail[i] >= FxAtlas.TURBULENT * top || i < 2, name + ": frame " + i + " swirls");
            assertTrue(last < FxAtlas.PUFF6_FRAMES - 1 || detail[FxAtlas.PUFF6_FRAMES - 1] >= FxAtlas.TURBULENT * top, name);
        }
        float[] smoke;
        try (Reader r = open("puff6.json")) {
            smoke = FxAtlas.parseDetail(r);
        }
        // billows of smoke - cap of the cloud of explosion EmberGen (tools/bake_fx, puff-vdb): her footage swirls to the end, and the cloud takes everything
        assertEquals(FxAtlas.PUFF6_FRAMES - 1, FxAtlas.turbulentLast(smoke), "at the cap the clouds are swirling all the frames");
        assertEquals(FxAtlas.PUFF6_FRAMES - 1, FxAtlas.turbulentLast(null), "no data - all frames");
    }

    /**
     * billows of smoke - billows, not “cotton wool”: in the opaque core of each frame (opacity greater than 0,6) the light from above (map A,
     * green) departs from its smoothing over 9×9 pixels on average by 12 % or more. The former billows of his Mantaflow —
     *  fluffy ball with smooth maps (0,01–0,08 in the frames that the clouds took): the clouds in the game were smooth dark balls
     *  without the light tops of the billows; cloud cap EmberGen from above - 0,13–0,19.
     */
    @Test
    void puffFramesHaveBillowsInTheirLight() throws IOException {
        for (int f = 0; f < FxAtlas.PUFF6_FRAMES; f++) {
            String name = String.format(Locale.ROOT, "puff6a_%02d.png", f);
            try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + name)) {
                assertNotNull(in, name);
                BufferedImage img = ImageIO.read(in);
                int n = img.getWidth();
                double[] top = new double[n * n];
                boolean[] core = new boolean[n * n];
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        int argb = img.getRGB(x, y);
                        top[y * n + x] = Math.pow((argb >> 8 & 255) / 255.0, 2.2);
                        core[y * n + x] = (argb >>> 24) > 0.6 * 255;
                    }
                }
                double dev = 0, sum = 0;
                int count = 0;
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        if (!core[y * n + x]) continue;
                        double blur = 0;
                        for (int dy = -4; dy <= 4; dy++) {
                            for (int dx = -4; dx <= 4; dx++) blur += top[Math.clamp(y + dy, 0, n - 1) * n + Math.clamp(x + dx, 0, n - 1)];
                        }
                        dev += Math.abs(top[y * n + x] - blur / 81);
                        sum += top[y * n + x];
                        count++;
                    }
                }
                assertTrue(count > 0.1 * n * n, name + ": billows has an opaque core");
                double fine = dev / sum;
                assertTrue(fine >= 0.12, name + ": billows in the light from above " + fine);
            }
        }
    }

    /**
     * Frames of billows clouds (smoke and splashes) do not have a straight edge of the alpha: billows are placed in any rotation - standing, reflected and
     *  lying on the ground (dust near the ground), and the straight edge of any frame stands up as a wall somewhere (review #443: cutting the cap of a cloud
     *  with a plane when baking gave a straight bottom at frames, and dust deposited on 55 s a sheer wall). The measure is according to a real
     *  two-dimensional alpha ({@link #straightEdge}): a silhouette along the rays from the middle, and the longest piece of it that lies on the straight line
     *  (within the {@link #STRAIGHT_TOL} diameter) is no more than {@link #STRAIGHT} diameter. At the swirling edge - 0,11–0,29
     *  (former billows, splashes, cap on top), at the bottom of the previous frames cut off by a plane - up to 0,46, at the frame with the cropped side
     *  - from 0,66.
     * The average over the rings (as with opacity profiles) loses its directional boundary. fireball is not included here: the bottom of the
     *  frame is cut off near the ground on purpose ({@code pack.py}: DOWN), the ball stands on it and does not lie down.
     */
    @Test
    void cloudFramesHaveNoStraightEdge() throws IOException {
        for (String prefix : new String[]{"puff6a", "spray6a", "eject6a", "crush6a", "air6a"}) {
            for (int f = 0; f < FxAtlas.PUFF6_FRAMES; f++) {
                String name = String.format(Locale.ROOT, "%s_%02d.png", prefix, f);
                double edge = straightEdge(alpha(name));
                assertTrue(edge <= STRAIGHT, name + ": straight piece of silhouette on " + edge + " diameter");
            }
        }
        // a frame with a cropped side (a third on the right is empty) and with a cut corner, the test catches
        double[][] side = alpha("puff6a_05.png"), corner = alpha("puff6a_09.png");
        int n = side.length;
        for (double[] row : side) Arrays.fill(row, n - n * 35 / 100, n, 0);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) if (x + y > 5 * n / 4) corner[y][x] = 0;
        }
        assertTrue(straightEdge(side) > STRAIGHT, "cut side: " + straightEdge(side));
        assertTrue(straightEdge(corner) > STRAIGHT, "cut corner: " + straightEdge(corner));
    }

    /**
     * The smoke billows frame is round: billows is a Gaussian model with square axes (σh across, σv up), and the shape of the cloud is determined by the square,
     * , and not the picture. The cap of the explosion cloud from above is elongated (the alpha inertia axis 1,4–1,7: 1), and the frame, reflected and lying on the
     *  ground, laid the Gauss in an oblique stripe with long straight sides - a large billow of dust had a coating around the eye wall
     *  (review #443). The ratio of the alpha inertia axes of each frame is no more than {@link #PUFF_AXES} (the previous billows were 1,0–1,2,
     * , the cap on top, compressed during baking along the major axis, was 1,05–1,15).
     */
    @Test
    void puffFramesAreRound() throws IOException {
        for (int k = 0; k < 4 * FxAtlas.PUFF6_FRAMES; k++) {
            String name = String.format(Locale.ROOT, "%s6a_%02d.png", CLUB_SETS[k / FxAtlas.PUFF6_FRAMES], k % FxAtlas.PUFF6_FRAMES);
            double[][] a = alpha(name);
            double w = 0, cx = 0, cy = 0;
            for (int y = 0; y < a.length; y++) {
                for (int x = 0; x < a.length; x++) {
                    w += a[y][x];
                    cx += x * a[y][x];
                    cy += y * a[y][x];
                }
            }
            cx /= w;
            cy /= w;
            double xx = 0, yy = 0, xy = 0;
            for (int y = 0; y < a.length; y++) {
                for (int x = 0; x < a.length; x++) {
                    xx += (x - cx) * (x - cx) * a[y][x] / w;
                    yy += (y - cy) * (y - cy) * a[y][x] / w;
                    xy += (x - cx) * (y - cy) * a[y][x] / w;
                }
            }
            double mid = (xx + yy) / 2, diff = Math.sqrt((xx - yy) * (xx - yy) / 4 + xy * xy);
            double axes = Math.sqrt((mid + diff) / (mid - diff));
            assertTrue(axes <= PUFF_AXES, name + ": alpha axis " + axes);
        }
    }

    /** Sets of billows that are placed by Gaussian models: smoke and billows of other ready-made simulations (emission, crushing, air). */
    static final String[] CLUB_SETS = {"puff", "eject", "crush", "air"};

    /**
     * An atlas frame of any billow kind matches pixel for pixel: frame extent (including mirrored) in u and v is exactly
     * the baked image size ({@code <name>6.json} "tile") in atlas pixels {@link FxAtlas#WIDTH}×{@link FxAtlas#HEIGHT},
     * starting at an integer pixel. This scale determines the billow mip level by pixels per texel (FxQuads, FPS thread,
     * #455): a frame at another scale would choose the wrong level.
     */
    @Test
    void clubFramesMapOneTexelToOneSheetPixel() throws IOException {
        for (ua.zentix.incoming.client.fx.smoke.SmokeGrid.Look look : ua.zentix.incoming.client.fx.smoke.SmokeGrid.Look.values()) {
            String name = switch (look) {
                case SMOKE -> "puff6.json";
                case EJECTA -> "eject6.json";
                case CRUSH -> "crush6.json";
                case AIRBURST -> "air6.json";
            };
            int tile = json(name).get("tile").getAsInt();
            for (int i = 0; i < FxAtlas.PUFF6_FRAMES; i++) {
                for (boolean mirror : new boolean[]{false, true}) {
                    FxAtlas.Sprite f = FxAtlas.club(look, i, mirror);
                    String what = look + " " + i + (mirror ? " reflected" : "");
                    assertEquals(tile, Math.abs(f.u1() - f.u0()) * FxAtlas.WIDTH, 1e-3, what + ": by u");
                    assertEquals(tile, Math.abs(f.v1() - f.v0()) * FxAtlas.HEIGHT, 1e-3, what + ": by v");
                    double x = Math.min(f.u0(), f.u1()) * FxAtlas.WIDTH, y = Math.min(f.v0(), f.v1()) * FxAtlas.HEIGHT;
                    assertEquals(Math.rint(x), x, 1e-3, what + ": start at u - not on the pixel");
                    assertEquals(Math.rint(y), y, 1e-3, what + ": start at v - not on the pixel");
                    assertEquals(mirror, f.u1() < f.u0(), what + ": reflection - reverse u");
                }
            }
        }
    }

    /**
     * billows of other ready-made simulations ({@code bake.py clubs-vdb}): the same 16 frames of three maps 128, that of smoke, in the amount of smoke
     *  (one normalization of maps for all billows of the layer); growth from 1 does not decrease; the share of life - from 0 to 1 does not decrease (phenomenon frame -
     * {@link FxAtlas#lifeFrame}); there is a fine structure; places on the sheet - one shader frame step from the beginning of the smoke frames and not
     *  overlap with neighbors.
     */
    @Test
    void clubSetsMatchTheBake() throws IOException {
        JsonObject smoke = json("puff6.json");
        for (FxAtlas.Clubs c : new FxAtlas.Clubs[]{FxAtlas.EJECT, FxAtlas.CRUSH, FxAtlas.AIR}) {
            JsonObject o = json(c.prefix + "6.json");
            int tile = o.get("tile").getAsInt();
            assertEquals(FxAtlas.PUFF6_FRAMES, o.get("frames").getAsInt(), c.prefix);
            assertEquals("abv", o.get("maps").getAsString());
            assertEquals(FxAtlas.PUFF6_FLOW, o.get("flow").getAsFloat(), 1e-6, c.prefix + ": the limit of motion vectors is what the shader expects");
            assertEquals(smoke.get("diffuse").getAsFloat(), o.get("diffuse").getAsFloat(), 1e-6, c.prefix + ": maps - to the extent of smoke");
            for (int i = 0; i < FxAtlas.PUFF6_FRAMES; i++) {
                for (char m : "abv".toCharArray()) image(String.format(Locale.ROOT, "%s6%c_%02d.png", c.prefix, m, i), tile);
            }
            float[] growth, life, detail;
            try (Reader r = open(c.prefix + "6.json")) {
                growth = FxAtlas.parseGrowth(r);
            }
            try (Reader r = open(c.prefix + "6.json")) {
                life = FxAtlas.parseLife(r);
            }
            try (Reader r = open(c.prefix + "6.json")) {
                detail = FxAtlas.parseDetail(r);
            }
            assertEquals(1, growth[0], 1e-6);
            assertEquals(0, life[0], 1e-6);
            assertEquals(1, life[FxAtlas.PUFF6_FRAMES - 1], 1e-6);
            for (int i = 1; i < FxAtlas.PUFF6_FRAMES; i++) {
                assertTrue(growth[i] >= growth[i - 1], c.prefix + ": height, frame " + i);
                assertTrue(life[i] > life[i - 1], c.prefix + ": life share, frame " + i);
            }
            assertNotNull(detail, c.prefix);
            // frame location: in shader frame step (FxSix: start of smoke frames + integer number of frames) and after previous sets
            float rows = (c.frames[0].v0() - FxAtlas.PUFF6_TOP) / FxAtlas.PUFF6_V;
            assertEquals(Math.round(rows), rows, 1e-3, c.prefix + ": frame - in shader frame step");
            assertTrue(c.row >= 96 && c.row + 12 <= FxAtlas.HEIGHT / 64, c.prefix + ": below the previous places, inside the sheet");
        }
        assertEquals(12, FxAtlas.CRUSH.row - FxAtlas.EJECT.row);
        assertEquals(12, FxAtlas.AIR.row - FxAtlas.CRUSH.row);
        // frame of the phenomenon according to the share of life - from the first frame to the last, without return
        double prev = -1;
        for (double f = 0; f <= 1.0001; f += 0.05) {
            double k = FxAtlas.lifeFrame(ua.zentix.incoming.client.fx.smoke.SmokeGrid.Look.EJECTA, f);
            assertTrue(k >= prev, "the frame does not go backwards according to the share of life");
            prev = k;
        }
    }

    /** The ratio of the alpha axes of inertia of the billows frame is no more than ({@link #puffFramesAreRound}). */
    static final double PUFF_AXES = 1.25;

    /** The longest straight piece of the silhouette is no more than this fraction of the diameter ({@link #straightEdge}). */
    static final double STRAIGHT = 0.32;
    /** Straight - within this fraction of the diameter from the chord. */
    static final double STRAIGHT_TOL = 0.012;
    /** The silhouette is texels with no less opacity (thick smoke can also be seen as pale). */
    static final double SILHOUETTE = 0.2;

    private static double[][] alpha(String name) throws IOException {
        try (InputStream in = FxAtlasTest.class.getResourceAsStream(DIR + name)) {
            assertNotNull(in, name);
            BufferedImage img = ImageIO.read(in);
            double[][] a = new double[img.getHeight()][img.getWidth()];
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) a[y][x] = (img.getRGB(x, y) >>> 24) / 255.0;
            }
            return a;
        }
    }

    /**
     * Longest straight section of the alpha silhouette as a fraction of its diameter: 360 rays from the
     * middle (alpha centroid), each takes the farthest texel no more transparent than {@link #SILHOUETTE}
     * (half-texel step). A section is consecutive ray points all within {@link #STRAIGHT_TOL} diameters of the
     * chord between its ends; diameter is twice the mean silhouette radius.
     */
    static double straightEdge(double[][] a) {
        int n = a.length, rays = 360;
        double w = 0, cx = 0, cy = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                w += a[y][x];
                cx += x * a[y][x];
                cy += y * a[y][x];
            }
        }
        cx /= w;
        cy /= w;
        double[] px = new double[rays], py = new double[rays];
        double mean = 0;
        for (int i = 0; i < rays; i++) {
            double t = 2 * Math.PI * i / rays, dx = Math.cos(t), dy = Math.sin(t), r = 0;
            for (double s = 0; s < n; s += 0.5) {
                double x = cx + s * dx, y = cy + s * dy;
                if (x < 0 || y < 0 || x > n - 1 || y > n - 1) break;
                if (a[(int) Math.round(y)][(int) Math.round(x)] >= SILHOUETTE) r = s;
            }
            px[i] = cx + r * dx;
            py[i] = cy + r * dy;
            mean += r / rays;
        }
        double diameter = 2 * mean, best = 0;
        for (int i = 0; i < rays; i++) {
            for (int len = 3; len < rays / 2; len++) {
                int j = (i + len) % rays;
                double ax = px[i], ay = py[i], bx = px[j] - ax, by = py[j] - ay, chord = Math.hypot(bx, by);
                if (chord < 1e-6) continue;
                boolean straight = true;
                for (int k = 1; k < len && straight; k++) {
                    int m = (i + k) % rays;
                    straight = Math.abs((px[m] - ax) * by - (py[m] - ay) * bx) / chord <= STRAIGHT_TOL * diameter;
                }
                if (!straight) break;
                best = Math.max(best, chord / diameter);
            }
        }
        return best;
    }

    /** billows of splashes: the same 16 frames of three maps as for smoke, in the amount of smoke (one normalization of maps for all billows of the layer). */
    @Test
    void sprayFramesMatchTheBake() throws IOException {
        JsonObject o = json("spray6.json"), smoke = json("puff6.json");
        int tile = o.get("tile").getAsInt();
        assertEquals(FxAtlas.PUFF6_FRAMES, o.get("frames").getAsInt());
        assertEquals("abv", o.get("maps").getAsString());
        assertEquals(smoke.get("diffuse").getAsFloat(), o.get("diffuse").getAsFloat(), 1e-6, "splash maps - to the extent of smoke");
        for (int i = 0; i < FxAtlas.PUFF6_FRAMES; i++) {
            for (char m : "abv".toCharArray()) image(String.format(Locale.ROOT, "spray6%c_%02d.png", m, i), tile);
        }
        try (Reader r = open("spray6.json")) {
            float[] growth = FxAtlas.parseGrowth(r);
            assertEquals(1, growth[0], 1e-6);
            for (int i = 1; i < growth.length; i++) assertTrue(growth[i] >= growth[i - 1], "billows is growing: frame " + i);
        }
    }

    /** Everything baked and drawn with the code for the effects layer in jar is no heavier than {@link #JAR_BUDGET}. */
    @Test
    void framesFitTheJarBudget() throws IOException {
        Path dir = Path.of("src/main/resources" + DIR);
        long bytes;
        try (Stream<Path> files = Files.list(dir)) {
            bytes = files.mapToLong(p -> p.toFile().length()).sum();
        }
        assertTrue(bytes <= JAR_BUDGET, "effects shots " + bytes / 1024 + " KB");
    }
}
