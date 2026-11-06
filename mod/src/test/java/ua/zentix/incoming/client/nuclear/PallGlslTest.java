package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.VolumeNoise;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The laws of pall and firestorm in the shader ({@code shaders/include/pall.glsl}) are the same as in Java ({@link PallField},
 * {@link FireStorm}, {@link VolumeNoise#shape}): shader functions are read from a file and are considered a small parse of
 *  expressions GLSL, numbers are compared with Java throughout the area. Changing the law in just one place breaks this test.
 */
class PallGlslTest {
    private static final String GLSL = read("include/pall.glsl");
    /** 15 kt near the ground, scale 1, wall of dust in the distance - 0,42 speed of sound. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
    /** The same 15 kt in the air, at 500 m above the ground. */
    private static final Detonation AIR = new Detonation(2, new Vec3(0, 64 + 500, 0), 64, 15, false, 0, 0.5f, 8, 20_000, 9, 1f, false, 0.42f);

    private static String read(String path) {
        try (InputStream in = PallGlslTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void sceneDistanceKeepsOnlyTheWorldInFront() {
        // distance to the world behind the pixel (SceneDepth): worthless - NaN, infinity, behind the eye (alien depth LOD) -
        //  like the sky, 1e9: otherwise the pall beam would go through NaN, and the frame pixel would come out black
        GlslFunction f = GlslFunction.parse(GLSL, "pallScene");
        for (double d : new double[] {0, 0.5, 64, 3000, 9.9e7}) assertEquals(d, f.call(Map.of("d", d)), 0, "d = " + d);
        for (double d : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, -5000, 1e8, 1e9, 3e38}) {
            assertEquals(1e9, f.call(Map.of("d", d)), 0, "d = " + d);
        }
    }

    /**
     * Frame ray grid pall (fx_pall: pixel ray t - through the middle of the pixel of the full frame floor ((t + 0,5)·factor)):
     *  the place of the middle of this pixel in it - exactly t, and accumulation (fx_pall_resolve) takes the past in it, without
     *  blurring with its neighbors. With a shift of half a pixel of the full frame, the past of each frame was mixed with its neighbors and a pall of the background was taken at the edge of the
     *  object - a border along the contour of the blocks.
     */
    @Test
    void historyIsReadWhereTheRayWent() {
        GlslFunction lattice = GlslFunction.parse(GLSL, "pallLattice");
        for (int factor = 2; factor <= 4; factor++) {
            for (int t = 0; t < 40; t++) {
                double ray = Math.floor((t + 0.5) * factor) + 0.5;
                assertEquals(t, lattice.call(Map.of("p", ray, "factor", (double) factor)), 1e-9, "factor " + factor + ", t " + t);
            }
        }
    }

    /**
     * The wedge of the wall head without noise is the same as in Java: raised along the phase share ({@link DustModel#lifted}), top, density at
     *  ground and transition to pall ({@link BlastWall#top}, {@link BlastWall#shape}, {@link BlastWall#handoff}) and middle
     *  head ({@link BlastWall#mean}).
     */
    @Test
    void wallHeadMatchesJava() {
        GlslFunction lifted = GlslFunction.parse(GLSL, "pallLifted"), top = GlslFunction.parse(GLSL, "pallWallTop"),
                shape = GlslFunction.parse(GLSL, "pallWallShape"), fade = GlslFunction.parse(GLSL, "pallWallFade"),
                mean = GlslFunction.parse(GLSL, "pallWallMean");
        for (double u = -0.5; u <= 3; u += 0.001) {
            String at = "u = " + u;
            assertEquals(DustModel.lifted(u), lifted.call(Map.of("s", u)), 1e-6, at);
            assertEquals(BlastWall.top(u), top.call(Map.of("u", u)), 1e-6, at);
            assertEquals(BlastWall.shape(u), shape.call(Map.of("u", u)), 1e-6, at);
            assertEquals(BlastWall.handoff(u), fade.call(Map.of("u", u)), 1e-9, at);
        }
        for (double len : new double[]{3, 25, 300}) {
            for (double h = 10; h <= 300; h += 58) {
                for (double s = -10; s <= 2 * len; s += len / 37) {
                    for (double z = -0.6 * h; z <= 1.2 * h; z += h / 23) {
                        Map<String, Double> args = Map.of("s", s, "z", z, "len", len, "top", h);
                        assertEquals(BlastWall.mean(s, z, len, h), mean.call(args), 1e-6, len + ", " + h + ": " + s + ", " + z);
                    }
                }
            }
        }
    }

    /**
     * The head of the wall at the point is the same as in Java ({@link BlastWall#sigma}): frame ({@code pallWallInside}), weakening from the edge of
     *  to the end of the wedge or splash over water ({@code pallWallDensity}), shift and top of the splash ({@code pallWallAt}), average head
     * ({@code pallWallMean}). Each sample of thickness to light ({@code wallMove}: {@code wallChord}, {@code wallChordNear}, shadow of
     *  billows in {@code wall}) has its own attenuation, like {@code NukePall.chord}, and not the point where the beam comes from: from the edge to the end of the
     *  wedge it changes significantly. The shader is assembled with the same text as in the file (slot numbers - according to the mat4 columns), and
     *  is compared with Java according to the slots of the explosion head near the ground and in the air, at points and at points shifted by the ray. The transition to the veil
     *  behind the wedge is one constant with Java ({@link BlastWall#HANDOFF_TO}) at the frame, at the ring of segments ({@code pallWallSpans})
     *  and at the ray to the light ({@code wall}, {@link NukePall#dustLight}).
     */
    @Test
    void wallHeadSigmaMatchesJava() {
        assertEquals(BlastWall.HANDOFF_FROM, scalar(GLSL, "PALL_WALL_HANDOFF_FROM"), 0);
        assertEquals(BlastWall.HANDOFF_TO, scalar(GLSL, "PALL_WALL_HANDOFF_TO"), 0);
        String fx = read("include/fx_pall.glsl");
        String at = body(GLSL, "WallAt pallWallAt("), move = body(fx, "WallAt wallMove("), chord = body(fx, "float wallChord("),
                near = body(fx, "float wallChordNear("), wall = body(fx, "void wall("), spans = body(GLSL, "vec4 pallWallSpans(");
        // where the point: s is beyond the edge of the dust from the ring axis, z is above the relief; shifted - from its place, the water and terrain are the same
        assertTrue(at.contains("h.s = V[0].x - length(h.rel);"), "pallWallAt: s - not from the ring axis");
        assertTrue(move.contains("m.rel = h.rel + v.xz;") && move.contains("m.s = V[0].x - length(m.rel);") && move.contains("m.z = h.z + v.y;"),
                "wallMove: shift - not on beam");
        assertTrue(spans.contains("inner = max(V[0].x - PALL_WALL_HANDOFF_TO * V[0].y - V[1].y, 0.0)"), "ring of segments - not up to the head frame");
        assertTrue(wall.contains("float reach = PALL_WALL_HANDOFF_TO * h.P.x + h.P.y,"), "beam to the light - not to the frame of the head");
        assertTrue(chord.contains("return sum * ds;") && near.contains("return sum * len;"), "thickness to light - with weakening of the point from where the beam");
        // thickness to light and shadow billows - samples of shifted points, each with its own attenuation
        String sample = glsl(match(chord, "sum \\+= (.+?);"));
        assertEquals(sample + " * (b - a)", glsl(match(near, "sum \\+= (.+?);")), "wallChordNear: sample is not the same as wallChord");
        assertEquals(sample, glsl(match(wall, "float m1 = (.+?);")), "shadow billows: the sample is not the same as that of thicknesses");
        assertEquals(sample, glsl(match(wall, "float m2 = (.+?);")), "shadow billows: the sample is not the same as that of thicknesses");
        assertEquals(sample, glsl(match(wall, "float hm = (.+?);")), "head at point: not the same sample");
        String[] stop = match(at, "if \\((.+?)\\) return h;").split("\\|\\|"), p = args(match(at, "h\\.P = vec4\\(([^;]+)\\); return h;"));
        String lag = glsl(match(at, "float lag = (.+?);")), numbers = "float Px = " + glsl(p[0]) + ", Py = " + glsl(p[1]) + ", Pz = " + glsl(p[2]) + ";";
        StringBuilder test = new StringBuilder(GLSL).append("\nfloat headAt(float pts, float ptz, float ptwet").append(SLOT_PARAMS).append(") {\n");
        for (String c : stop) test.append("if (").append(glsl(c)).append(") return 0.0;\n");
        test.append("float lag = ").append(lag).append(";\n").append(numbers).append("\nfloat Pw = ").append(glsl(p[3])).append(";\nreturn ")
                .append(sample).append(";\n}\n");
        test.append("float headMoved(float pts, float ptz, float ptwet").append(SLOT_PARAMS).append(") {\nfloat lag = ").append(lag).append(";\n")
                .append(numbers).append("\nfloat Pw = ").append(glsl(match(move, "m\\.P\\.w = (.+?);"))).append(";\nreturn ").append(sample).append(";\n}\n");
        GlslFunction head = GlslFunction.parse(test.toString(), "headAt"), moved = GlslFunction.parse(test.toString(), "headMoved");
        float[] slot = new float[BlastWall.SLOT];
        int points = 0, inside = 0, steps = 0;
        double[][] rays = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {0.6, 0.8, 0}, {-0.8, 0.2, 0.566}};
        for (Detonation d : new Detonation[]{GROUND, AIR}) {
            BlastWall w = new BlastWall(d, false);
            for (double t : new double[]{0.5, 1.5, 4, 9, 16}) {
                if (!w.slot(t, slot) || slot[3] <= 0) continue;
                double len = slot[1], top = slot[2], back = BlastWall.HANDOFF_TO * len + slot[5];
                for (double s = -4; s <= back + 4; s += (back + 8) / 41) {
                    for (double z = -0.7 * top; z <= 1.15 * top; z += top / 17) {
                        for (double wet : new double[]{0, 0.35, 1}) {
                            double angle = 0.3 + s / 97, r = slot[0] - s, x = r * Math.cos(angle), y = r * Math.sin(angle);
                            double want = BlastWall.sigma(slot, x, y, z, wet), shader = head.call(args(slot, slot[0] - Math.hypot(x, y), z, wet));
                            String where = d.surface() + ", " + t + " s: s = " + s + ", z = " + z + ", water " + wet;
                            assertEquals(want, shader, 1e-4 * Math.abs(want) + 1e-7, where);
                            points++;
                            if (want > 0) inside++;
                            else continue;
                            // from a point in the head - with a beam up, down and to the sides: at her own, half a wedge and further than the wedge
                            for (double[] v : rays) {
                                for (double l : new double[]{0.1 * top, 0.5 * len, 1.3 * len}) {
                                    double mx = x + v[0] * Math.cos(angle) * l - v[2] * Math.sin(angle) * l, my = y + v[0] * Math.sin(angle) * l + v[2] * Math.cos(angle) * l;
                                    double mz = z + v[1] * l, javaM = BlastWall.sigma(slot, mx, my, mz, wet);
                                    double shaderM = moved.call(args(slot, slot[0] - Math.hypot(mx, my), mz, wet));
                                    assertEquals(javaM, shaderM, 1e-4 * Math.abs(javaM) + 1e-7, where + ", beam " + java.util.Arrays.toString(v) + " on " + l);
                                    steps++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(points > 10_000 && inside > 1_000 && steps > 20_000, points + " points in my head " + inside + ", ray samples " + steps);
    }

    /**
     * Splashes over water - its own row of the slot table (its own height, its own top): {@link PallField#row} writes dust, splashes and haze
     *  with the rows {@link PallField#DUST_ROW}, {@link PallField#SPRAY_ROW}, {@link PallField#HAZE_ROW}, the slot points to them to the
     *  shader ({@link NukePall#tableRows}: S[2].z, S[3].w, S[2].w), and {@code pallField} reads the splashes there - one sample of the
     *  table on top of dust and haze, only at the explosion with water under the pall - and puts them, like {@link PallField#sigma}: at the edge - like
     * dust, above the water - instead of it, with its top.
     */
    @Test
    void sprayHasItsOwnRow() {
        PallField p = new PallField(GROUND, 6000);
        float[] row = new float[4 * PallField.ROWS * PallField.BINS];
        double[] v = new double[4];
        boolean spray = false;
        for (double ticks : new double[]{40, 400, 2400}) {
            p.row(ticks, true, true, row);
            for (int i = 0; i < PallField.BINS; i++) {
                p.dust(i, ticks, v);
                for (int k = 0; k < 4; k++) assertEquals((float) v[k], row[4 * (PallField.DUST_ROW * PallField.BINS + i) + k], 0, "dust, knot " + i);
                p.spray(i, ticks, v);
                for (int k = 0; k < 4; k++) assertEquals((float) v[k], row[4 * (PallField.SPRAY_ROW * PallField.BINS + i) + k], 0, "splash, knot " + i);
                spray |= v[0] > 0;
                p.haze(i, ticks, v);
                for (int k = 0; k < 4; k++) assertEquals((float) v[k], row[4 * (PallField.HAZE_ROW * PallField.BINS + i) + k], 0, "haze, knot " + i);
            }
        }
        assertTrue(spray, "there are no splashes in any node");
        for (int k = 0; k < 2; k++) {
            org.joml.Matrix4f m = new org.joml.Matrix4f();
            NukePall.tableRows(m, k);
            assertEquals((PallField.ROWS * k + PallField.DUST_ROW + 0.5) / ua.zentix.incoming.client.fx.layer.PallPass.ROWS, m.m22(), 1e-7, "slot " + k + ": dust");
            assertEquals((PallField.ROWS * k + PallField.SPRAY_ROW + 0.5) / ua.zentix.incoming.client.fx.layer.PallPass.ROWS, m.m33(), 1e-7, "slot " + k + ": splash");
            assertEquals((PallField.ROWS * k + PallField.HAZE_ROW + 0.5) / ua.zentix.incoming.client.fx.layer.PallPass.ROWS, m.m23(), 1e-7, "slot " + k + ":haze");
        }
        assertEquals(2 * PallField.ROWS, ua.zentix.incoming.client.fx.layer.PallPass.ROWS, "table rows - not for two slots");
        String f = body(GLSL, "PallAt pallField(");
        assertTrue(f.contains("vec4 dust = texture(table, vec2(min(ud, 1.0), S[2].z));"), "dust uses the wrong row");
        assertTrue(f.contains("vec4 haze = texture(table, vec2(min(uh, 1.0), S[2].w));"), "haze - not in your own line");
        int water = f.indexOf("if (W.w > 0.5) {"), read = f.indexOf("vec4 spray = texture(table, vec2(min(ud, 1.0), S[3].w));");
        assertTrue(water >= 0 && read > water, "splashes - not in their own line or can be read without water under the pall");
        assertEquals(1, occurrences(f, "S[3].w"), "line of splashes - not one sample");
        // like PallField.layers and sigma: splashes at the edge - like dust, above water instead of dust, the top is not lower than the block
        for (String law : new String[]{"ss = spray.r * edge;", "topS = max(spray.g, 1.0);", "if (sd + ss > 0.0) wet = pallWater(ground, G, lift);",
                "sd *= 1.0 - wet;", "ss *= wet;", "hs = above / topS;", "ss * pallProfile(hs)", "ss * topS * pallColumn(hs)"}) {
            assertTrue(f.contains(law), "spray pall - not like in Java: no \"" + law + "»");
        }
    }

    /** Wall slot parameters for check functions: numbers mat4 head by columns. */
    private static final String SLOT_PARAMS;

    static {
        StringBuilder b = new StringBuilder();
        for (int c = 0; c < 4; c++) for (String r : new String[]{"x", "y", "z", "w"}) b.append(", float V").append(c).append(r);
        SLOT_PARAMS = b.toString();
    }

    /** Arguments to the head check function: point (behind the edge of dust, above the terrain, water) and slot head numbers [0..15]. */
    private static Map<String, Double> args(float[] slot, double s, double z, double wet) {
        Map<String, Double> a = new HashMap<>();
        a.put("pts", s);
        a.put("ptz", z);
        a.put("ptwet", wet);
        String[] rows = {"x", "y", "z", "w"};
        for (int i = 0; i < 16; i++) a.put("V" + i / 4 + rows[i % 4], (double) slot[i]);
        return a;
    }

    /**
     * Shader expression - in the names of the check function: {@code V[c].r} - {@code Vcr}; at the point ({@code h}, {@code m},
     * {@code h1}, ...) - {@code Px}...{@code Pw}, {@code pts}, {@code ptz}, {@code ptwet}.
     */
    private static String glsl(String e) {
        e = e.replaceAll("V\\[(\\d)]\\.([xyzw])", "V$1$2");
        e = e.replaceAll("\\b[hm]\\d?\\.P\\.([xyzw])\\b", "P$1");
        return e.replaceAll("\\b[hm]\\d?\\.(s|z|wet)\\b", "pt$1").strip();
    }

    /** Top-level call arguments (separated by commas outside parentheses). */
    private static String[] args(String list) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0, from = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                out.add(list.substring(from, i).strip());
                from = i + 1;
            }
        }
        out.add(list.substring(from).strip());
        return out.toArray(String[]::new);
    }

    /** The first group of the first match in the text. */
    private static String match(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) throw new AssertionError("there is no \" in the shader" + regex + "»");
        return m.group(1);
    }

    /** The body of the shader function with this beginning of the declaration, spaces - one at a time. */
    private static String body(String source, String header) {
        int at = source.indexOf(header);
        if (at < 0) throw new AssertionError("not in the shader " + header);
        int open = source.indexOf('{', at), depth = 1, i = open + 1;
        for (; depth > 0; i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}') depth--;
        }
        return source.substring(open + 1, i - 1).replaceAll("\\s+", " ");
    }

    @Test
    void profileMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallProfile");
        for (double x = -1.5; x <= 1.5; x += 0.005) assertEquals(PallField.profile(x), f.call(Map.of("x", x)), 1e-9, "x = " + x);
    }

    @Test
    void columnMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallColumn");
        for (double x = -1.5; x <= 1.5; x += 0.005) assertEquals(PallField.column(x), f.call(Map.of("x", x)), 1e-9, "x = " + x);
    }

    @Test
    void edgeMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallEdge");
        for (double u = 0; u <= 1.2; u += 0.002) assertEquals(PallField.edge(u), f.call(Map.of("u", u)), 1e-9, "u = " + u);
    }

    @Test
    void surgeMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallSurge"), top = GlslFunction.parse(GLSL, "pallSurgeTop");
        for (double u = 0; u <= 1.3; u += 0.002) {
            assertEquals(PallField.surgeProfile(u), f.call(Map.of("u", u)), 1e-9, "u = " + u);
            assertEquals(PallField.surgeTop(u), top.call(Map.of("u", u)), 1e-9, "u = " + u);
        }
    }

    /**
     * Water dust pall ({@code MIST} to {@code fx_pall.glsl}) - the same white as the wall of spray above the water
     *  ({@link CloudPuffs#SPRAY}): pall behind the front above the water continues its color, like brown dust - the wall above the land.
     */
    @Test
    void mistIsTheSprayWallColour() {
        double[] mist = vec3(read("include/fx_pall.glsl"), "MIST");
        for (int c = 0; c < 3; c++) assertEquals((CloudPuffs.SPRAY >> (16 - 8 * c) & 0xFF) / 255.0, mist[c], 0.5 / 255, "channel " + c);
    }

    /**
     * billows of the head - in the coordinates of the dust itself: the share of the path with the wind per phase ({@code pallWallCarried}) - the same as in Java
     *  ({@link BlastWall#carriedShare}), the place of the dust ({@code pallWallOrigin}) - the same law as {@link BlastWall#origin}, and
     *  head noise takes it, and not a place behind the front (billows would go with the front - complaint 18.10).
     */
    @Test
    void headNoiseIsInDustCoordinates() {
        GlslFunction carried = GlslFunction.parse(GLSL, "pallWallCarried");
        for (double u = -0.5; u <= 3; u += 0.001) assertEquals(BlastWall.carriedShare(u), carried.call(Map.of("u", u)), 1e-6, "u = " + u);
        String origin = body(GLSL, "vec3 pallWallOrigin(");
        for (String law : new String[]{"float zr = clamp(z / max(top, 0.001), 0.0, 1.0);", "float x = carried * pallWallCarried(u) * pow(zr, 1.0 / 7.0);",
                "return vec3(rel.x - out1.x * x, z - x * (0.02 + 0.06 * zr), rel.y - out1.y * x);"}) {
            assertTrue(origin.contains(law), "head dust place - not like in Java: no \"" + law + "»");
        }
        String wall = body(read("include/fx_pall.glsl"), "float wallAt(");
        assertTrue(wall.contains("wallNoise(pallWallOrigin(h.rel, h.z, u * V[2].x, h.P.y, V[1].x) / max(V[2].w, 1.0)"), "head noise - not in dust coordinates");
    }

    /**
     * pall - one blanket: fluctuations in the density of the mixed layer ({@code pallMixed}) and the unevenness of its top
     *  ({@code PALL_BUMPS}, the last parameter {@code pallField}) - the same as in Java ({@link PallField#clumps},
     * {@link PallField#MIXED}, {@link PallField#BUMPS}); the bumps are the top of the layer ({@code topD}), and not holes in it, and they are taken by the
     *  pall beam.
     */
    @Test
    void blanketMatchesJava() {
        assertEquals(PallField.BUMPS, scalar(GLSL, "PALL_BUMPS"), 1e-7);
        assertEquals(PallField.MIXED, scalar(GLSL, "PALL_MIXED"), 1e-7);
        GlslFunction mixed = GlslFunction.parse(GLSL, "pallMixed");
        for (double shape = 0; shape <= 3.5; shape += 0.05) {
            for (double m = 0; m <= 1; m += 0.05) {
                assertEquals(PallField.clumps(shape, m), mixed.call(Map.of("shape", shape, "mixed", m, "PALL_MIXED", PallField.MIXED)), 1e-9);
            }
        }
        String f = body(GLSL, "PallAt pallField(");
        assertTrue(f.contains("float topD = max(dust.g, 1.0) * bumps,"), "the bumps are not the top of the layer");
        assertTrue(f.contains("a.mixed = dust.a;"), "confusion - not from the table");
        String fx = read("include/fx_pall.glsl");
        assertTrue(fx.contains("pallField(Sampler1, ground, S, R, W, p, floorUp, topBumps(S, p));"), "pall beam - without top bumps");
        assertTrue(body(fx, "float topBumps(").contains("return 1.0 + PALL_BUMPS * (2.0 * n - 1.0);"), "tubercles - not 1 ± PALL_BUMPS");
    }

    @Test
    void shapeMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallShape");
        for (double r = 0; r <= 1; r += 0.05) {
            for (double g = 0; g <= 1; g += 0.05) {
                for (double fill = 0; fill <= 1; fill += 0.1) {
                    assertEquals(VolumeNoise.shape(r, g, fill), f.call(Map.of("n.x", r, "n.y", g, "fill", fill)), 1e-9,
                            "r = " + r + ", g = " + g + ", fill = " + fill);
                }
            }
        }
    }

    /**
     * The haze to the mushroom, pall and wall ({@code pallAir}) is the same as that of the distant picture ({@link Sight.Air}): the air of the frame in the
     *  blocks of the model ({@code MushroomField.air}) at any scale, up, down and along the ground.
     */
    @Test
    void airMatchesJava() {
        GlslFunction depth = GlslFunction.parse(GLSL, "pallAirDepth");
        double heights = scalar(GLSL, "PALL_AIR_HEIGHTS");
        assertEquals(Sight.HAZE_HEIGHT / Sight.AIR_HEIGHT, heights, 1e-7);
        for (double scale : new double[] {1, 0.25}) {
            Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, 15, false, 0, 0.5f, 8, 20_000, 7, (float) scale, false, 0.42f);
            for (double eyeY : new double[] {40, 70, 300}) {
                Vec3 eye = new Vec3(0, eyeY, 0);
                for (double range : new double[] {Sight.CLEAR, Sight.RAIN, Sight.THUNDER}) {
                    org.joml.Vector4f air = new org.joml.Vector4f();
                    MushroomField.air(d, eye, range, air);
                    Sight.Air metres = MushroomField.air(d, eye, range);
                    for (double t : new double[] {0, 5, 400, 6000, 30000}) {
                        for (double dy : new double[] {-0.3, -1e-6, 0, 1e-6, 0.2, 0.9}) {
                            double glsl = Math.exp(-depth.call(Map.of("t", t, "dy", dy, "haze", (double) air.x, "clean", (double) air.y,
                                    "weather", (double) air.z, "kh", (double) air.w, "PALL_AIR_HEIGHTS", heights)));
                            double java = metres.transmittance(d.metres(t), d.metres(dy * t));
                            assertEquals(java, glsl, 1e-5 + 1e-5 * java, "scale " + scale + ", eye " + eyeY + ", t " + t + ", dy " + dy);
                        }
                    }
                }
            }
        }
    }

    /**
     * As far as the pall beam goes ({@code pallClip} in {@code fx_pall}): in the exponential layer with attenuation at the eye sigma and thickness
     * tau vertically, the beam with cosine mu gains thickness 12 to the vertical exactly there; exits the layer without typing 12 - not
     *  is terminated; almost horizontal - 12/sigma, and never closer. There is no dust in the eye - it does not break off.
     */
    @Test
    void clipWhereDepthReachesTwelve() {
        GlslFunction f = GlslFunction.parse(read("include/fx_pall.glsl"), "pallClip");
        assertTrue(f.call(Map.of("sigma", 0.0, "tau", 5.0, "mu", 0.0)) >= 1e6);
        // the eye is below a rough relief map: there is no thickness under it, there is dust near the eye - like in smooth dust
        for (double mu : new double[]{0, 0.3, 1}) assertEquals(12 / 0.06, f.call(Map.of("sigma", 0.06, "tau", 0.0, "mu", mu)), 1e-9);
        for (double sigma : new double[]{0.003, 0.06, 0.5}) {
            for (double tau : new double[]{0.1, 2, 30, 400}) {
                for (double mu : new double[]{0, 1e-7, 1e-4, 0.01, 0.1, 0.4, 1}) {
                    double clip = f.call(Map.of("sigma", sigma, "tau", tau, "mu", mu));
                    String at = "sigma " + sigma + ", tau " + tau + ", mu " + mu;
                    assertTrue(clip >= 12 / sigma * (1 - 1e-6), at + ": closer than in flat dust");
                    if (tau <= 12 * mu) {
                        assertTrue(clip >= 1e6, at + ": the ray exits the layer without typing 12");
                        continue;
                    }
                    double depth = mu == 0 ? sigma * clip : tau / mu * (1 - Math.exp(-clip * mu * sigma / tau));
                    assertEquals(12, depth, 1e-3, at);
                }
            }
        }
    }

    /** Light of a ball on dust in {@code fx_pall} ({@code pallBallShare}) - law {@link CloudPuffs#ball}: L·R²/d², near the surface - L. */
    @Test
    void ballLightMatchesJava() {
        GlslFunction f = GlslFunction.parse(read("include/fx_pall.glsl"), "pallBallShare");
        for (double radius : new double[]{0, 40, 150, 600}) {
            for (double d = 0; d <= 4000; d += 50) {
                assertEquals(CloudPuffs.ball(1, radius, d), f.call(Map.of("radius", radius, "d", d)), 1e-12,
                        "radius = " + radius + ", d = " + d);
            }
        }
    }

    @Test
    void burnMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallBurn");
        for (double r = 0; r <= 4000; r += 25) {
            for (double up = 0; up <= 1; up += 0.25) {
                for (double fireR : new double[]{0, 1900}) {
                    assertEquals(CloudPuffs.burn(r, up, 0.8, fireR), f.call(Map.of("r", r, "up", up, "glow", 0.8, "fireR", fireR)), 1e-9,
                            "r = " + r + ", up = " + up + ", fireR = " + fireR);
                }
            }
        }
    }

    /** Light and shader screen ({@code pallLinear}, {@code pallDisplay}, {@code pallLift}) are the same as in {@link PallLight}. */
    @Test
    void displayMatchesJava() {
        GlslFunction linear = GlslFunction.parse(GLSL, "pallLinear"), display = GlslFunction.parse(GLSL, "pallDisplay"), lift = GlslFunction.parse(GLSL, "pallLift");
        for (double v = 0; v <= 1.5; v += 0.01) {
            assertEquals(PallLight.linear(v), linear.call(Map.of("v", v)), 1e-12, "v = " + v);
            assertEquals(PallLight.display(v), display.call(Map.of("v", v)), 1e-6 * (1 + v), "v = " + v);
            for (double base : new double[]{0.12, 0.17, 0.6, 1}) {
                assertEquals(PallLight.lift(base, v), lift.call(Map.of("base", base, "x", v)), 1e-12, "sky " + base + ", reflection " + v);
            }
        }
    }

    /** Small billows of the shader are the same as those of {@link VolumeNoise#detail}. */
    @Test
    void detailMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallDetail");
        for (double r = 0; r <= 1; r += 0.002) assertEquals(VolumeNoise.detail(r), f.call(Map.of("r", r)), 1e-9, "r = " + r);
    }

    /** The attenuation of scattered light and the light itself in the layer ({@code pallK}, {@code pallDiffuse}) are the same as in {@link PallLight}. */
    @Test
    void lightMatchesJava() {
        GlslFunction k = GlslFunction.parse(GLSL, "pallK"), f = GlslFunction.parse(GLSL, "pallDiffuse");
        // for dark smoke (0,55, 0,48) k > 1: the beam goes out as scattered light at μ = 1/k
        double[] albedos = {1, 0.9989, 0.97, 0.9, 0.76, 0.55, 0.48};
        double[] phases = {0.3333, 0.375, 0.4595};
        for (double w : albedos) {
            for (double g : phases) {
                assertEquals(PallLight.k(w, g), k.call(Map.of("w", w, "g", g)), 1e-12, "w = " + w + ", g = " + g);
                for (double tc : new double[]{0.01, 0.7, 3, 12}) {
                    for (double share : new double[]{0, 0.4, 1}) {
                        // 0,95 and 0,975 - in the band kμ ≈ 1 for dark smoke (beam shift, pallBeamMu)
                        for (double mu1 : new double[]{0.12, 0.6, 0.95, 0.975, 1}) {
                            for (double f2 : new double[]{0, 0.5}) {
                                for (double top : new double[]{0, 0.2}) {
                                    for (double bottom : new double[]{0, 0.7}) {
                                        for (double c : new double[]{-2.0 / 3, 0.2, 2.0 / 3}) {
                                            double tau = share * tc, ground = bottom > 0 ? 0.2 : 0.06;
                                            double java = PallLight.diffuse(w, g, tau, tc, ground, mu1, 0.8, 0.35, f2, top, bottom, c);
                                            Map<String, Double> args = new HashMap<>(Map.of("w", w, "g", g, "tau", tau, "tc", tc, "ground", ground,
                                                    "mu1", mu1, "f1", 0.8, "mu2", 0.35, "f2", f2, "top", top));
                                            args.put("bottom", bottom);
                                            args.put("c", c);
                                            // 2/3 in the shader - 0,6666667: with an almost non-absorbing substance (the matrix is almost degenerate) this is 10⁻⁵
                                            assertEquals(java, f.call(args), 1e-4 * (1 + Math.abs(java)), "w = " + w + ", g = " + g + ", τc = " + tc
                                                    + ", τ = " + tau + ", μ1 = " + mu1 + ", f2 = " + f2 + ", top " + top + ", bottom " + bottom + ", c = " + c);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Shader substance ({@code PALL_*_W}, {@code PALL_*_G} in {@code pall.glsl}) - from the colors of the thick layer {@code fx_pall.glsl}
     *  in linear ({@link PallLight#albedo}, asymmetry {@link PallLight#truncated}): thick pall on the screen of the same colors,
     * that the wall of dust and splashes (R∞ albedo is its color). Soot and tornado flames in linear ({@code *_LINEAR}) are the same colors.
     */
    @Test
    void materialsHaveTheirColours() {
        String fx = read("include/fx_pall.glsl");
        String[][] materials = {{"DUST", "DUST"}, {"SMOKE", "DUST"}, {"HAZE", "DUST"}, {"MIST", "MIST"}, {"SOOT", "SOOT"}};
        Map<String, Double> full = Map.of("DUST", PallLight.DUST_G, "MIST", PallLight.MIST_G, "SOOT", PallLight.SOOT_G);
        for (String[] m : materials) {
            double g = scalar(GLSL, "PALL_" + m[1] + "_G");
            assertEquals(PallLight.truncated(full.get(m[1])), g, 1e-7, m[0]);
            double[] colour = vec3(fx, m[0]), w = vec3(GLSL, "PALL_" + m[0] + "_W");
            for (int c = 0; c < 3; c++) {
                assertEquals(PallLight.albedo(PallLight.linear(colour[c]), g), w[c], 1e-7, m[0] + ", channel " + c);
                assertEquals(colour[c], PallLight.display(PallLight.reflectance(w[c], g)), 1e-4, m[0] + ", channel " + c);
            }
        }
        for (String m : new String[]{"SOOT", "WHIRL_FIRE"}) {
            double[] colour = vec3(fx, m), linear = vec3(fx, m + "_LINEAR");
            for (int c = 0; c < 3; c++) assertEquals(PallLight.linear(colour[c]), linear[c], 1e-7, m + ", channel " + c);
        }
        assertEquals(PallLight.LAND, scalar(GLSL, "PALL_LAND"), 0);
        assertEquals(PallLight.SEA, scalar(GLSL, "PALL_SEA"), 0);
        // dust near the camera (NukePall.dustLight) - the same substances and the same flame
        double[] dust = vec3(fx, "DUST"), smoke = vec3(fx, "SMOKE"), fire = vec3(fx, "FIRE");
        for (int c = 0; c < 3; c++) {
            assertEquals(dust[c], NukePall.DUST[c], 0, "channel " + c);
            assertEquals(smoke[c], NukePall.SMOKE[c], 0, "smoke, channel " + c);
            assertEquals(fire[c], NukePall.FIRE[c], 0, "flame, channel " + c);
        }
    }

    /**
     * pall behind the front at night - in the same light as the head of the wall in front of it: thick dust ({@code PALL_DUST_W}) in the light of the ball
     *  and fires according to the laws of the shader (pallDiffuse; ball - black body, like the body of a mushroom, pallBallShare × eye addiction;
     * fires - strength {@link CloudPuffs#glowOnSmoke}, divided by the color of dust {@code DUST}, on the screen above the sky light - in
     *  linear difference, pallLift; sky light - {@link NukePall#nightSky} in linear; result - on the screen, pallDisplay).
     * Top, under the ball and cap in the first seconds (beam, pallBallLight), - the same color as the top of the
     *  head of the wall in the same place ({@link NukePall#dustLight} - the light of the head, as in the frame), with an accuracy of a third: the head is not a
     *  flat layer. And not dark at the epicenter. The glow of the fires pall ({@code PallFireLight}) flares up later, for a minute,
     *  - it is not here.
     * <p>
     * The light of fires is no brighter than the flame ({@code pallFlame}, {@link PallLight#flame}). At night, above the fire zone, the reflection on the screen
     *  is narrower than the color of fire ({@code FIRE}), but dust cannot produce light of that color - turning through the color of dust would give light
     *  three times brighter than fire. There is pall near the floor - dust in the light of the flame itself: on the side no brighter than the flame and the same shade (red, followed by
     *  green, blue), in red - no less than 0,6 it: not a dark curtain over the fires.
     */
    @Test
    void pallBehindTheFrontTakesTheWallLight() {
        GlslFunction burn = GlslFunction.parse(GLSL, "pallBurn"), light = GlslFunction.parse(GLSL, "pallDiffuse"),
                lift = GlslFunction.parse(GLSL, "pallLift"), display = GlslFunction.parse(GLSL, "pallDisplay"), linear = GlslFunction.parse(GLSL, "pallLinear");
        assertTrue(GLSL.replaceAll("\\s+", " ").contains("vec3 pallFlame(float base, vec3 x, vec3 flame) { return min(pallLift3(base, x), "
                + "vec3(pallLinear(flame.r), pallLinear(flame.g), pallLinear(flame.b))); }"), "pallFlame - not PallLight.flame");
        String fx = read("include/fx_pall.glsl");
        GlslFunction ball = GlslFunction.parse(fx, "pallBallShare");
        double[] dust = vec3(fx, "DUST"), fire = vec3(fx, "FIRE"), w = vec3(GLSL, "PALL_DUST_W");
        assertEquals(PallLight.CROSS, scalar(fx, "CROSS"), 0, "to white - like PallLight.screen");
        double g = scalar(GLSL, "PALL_DUST_G");
        CloudPuffs puffs = new CloudPuffs(GROUND);
        BlastWall blast = new BlastWall(GROUND, false);
        float ambient = 0.17f;
        double k = CloudPuffs.glowOnSmoke(ambient), sky = linear.call(Map.of("v", (double) NukePall.nightSky(ambient, 1))), tc = 8;
        float[] wall = new float[8], high = new float[3], slot = new float[BlastWall.SLOT];
        NukePall.Sky night = new NukePall.Sky();
        night.sy = -1;
        java.util.Arrays.fill(night.sky, (float) sky);
        night.base = ambient;
        night.exposure = (float) Sight.adapted(1, ambient);
        night.glowOnSmoke = (float) k;
        int checked = 0, burning = 0;
        for (double t : new double[]{2, 6, 10, 20, 40}) {
            assertTrue(puffs.pallLight(MushroomModel.shape(t, 15, 0, true, GROUND.metres(blast.wall(t).head())), wall));
            assertTrue(blast.slot(t, slot));
            double head = blast.wall(t).head();
            for (double share : new double[]{0.01, 0.03, 0.05, 0.2, 0.5, 0.8, 0.97}) {
                double r = head * share;
                float[] here = slot.clone();
                here[0] = (float) (r + here[1]);
                // the middle of the cap was blown away by the wind (CloudPuffs.pallLight), point - at 0,9 head height above r
                double wind = GROUND.windDir(), lx = Math.cos(wind) * wall[2] - r, ly = wall[7] - 0.9 * here[2], lz = Math.sin(wind) * wall[2];
                double toBall = Math.sqrt(lx * lx + ly * ly + lz * lz);
                double a = ball.call(Map.of("radius", (double) wall[0], "d", toBall)) * night.exposure;
                double b = burn.call(Map.of("r", r, "up", 0.0, "glow", (double) wall[1], "fireR", (double) wall[3]));
                double mu = ly / toBall;
                // the top of the head of the wall, if the end of the wedge were here (the point is in the middle of its thickness), - before it, the pall was here
                NukePall.dustLight(here, wall, night, wind, r, 0.9 * here[2], 0, 0.9 * here[2], high);
                double sum = 0, fires = 0;
                double[] side = new double[3], top = new double[3], luma = {0.2126, 0.7152, 0.0722};
                // the reflection of fires on the dust is already the color of fire in all channels: pall light - flame (pallFlame); at the edge of the fire zone,
                // , where only blue is saturated, pall in red and should be darker than the flame
                boolean flames = true;
                for (int c = 0; c < 3; c++) {
                    flames &= lift.call(Map.of("base", (double) ambient, "x", fire[c] * b * k / dust[c])) > linear.call(Map.of("v", fire[c]));
                }
                for (int c = 0; c < 3; c++) {
                    double capF = wall[4 + c] * a;
                    double burnF = Math.min(lift.call(Map.of("base", (double) ambient, "x", fire[c] * b * k / dust[c])),
                            linear.call(Map.of("v", fire[c])));
                    fires += luma[c] * burnF;
                    Map<String, Double> args = new HashMap<>(Map.of("w", w[c], "g", g, "tc", tc, "ground", PallLight.LAND, "mu1", 1.0,
                            "f1", 0.0, "mu2", Math.max(mu, 0.12), "f2", capF, "top", sky, "bottom", burnF));
                    args.put("tau", tc);
                    args.put("c", -2.0 / 3);
                    // pall cuts off with a shoulder (fx_pall: shoulder): brighter 1 - like 1
                    double below = Math.min(1, display.call(Map.of("v", light.call(args))));
                    args.put("c", 0.0);
                    side[c] = display.call(Map.of("v", w[c] * light.call(args)));
                    args.put("tau", 0.0);
                    args.put("c", 2.0 / 3);
                    top[c] = light.call(args);
                    sum += Math.max(below, Math.min(1, display.call(Map.of("v", top[c]))));
                }
                if (mu > 0.9 && t <= 10) {
                    // to the screen - like pall (to white, shoulder): under the ball in the first seconds it is overexposed
                    float[] shown = new float[3];
                    PallLight.screen(top, shown);
                    for (int c = 0; c < 3; c++) {
                        assertEquals(high[c], shown[c], 0.03 + 0.3 * high[c], t + " s, " + r + " blocks, top, channel " + c);
                        checked++;
                    }
                }
                // fires shine brighter than a ball (fx_pall: main light - from below; both - linear light on a white wall)
                if (flames && fires > (luma[0] * wall[4] + luma[1] * wall[5] + luma[2] * wall[6]) * a) {
                    String at = t + " s, " + r + " blocks, near the floor on the side: ";
                    for (int c = 0; c < 3; c++) assertTrue(side[c] <= fire[c] + 0.03, at + "brighter than the flame in the canal " + c + ", " + side[c]);
                    assertTrue(side[0] > side[1] && side[1] > side[2], at + "not the color of fire");
                    assertTrue(side[0] >= 0.6 * fire[0], at + "dark against the flame, " + side[0]);
                    burning++;
                }
                if (r < 1000) assertTrue(sum / 3 > 0.25, t + " s, " + r + " blocks: the pall at the epicenter is dark, " + sum / 3);
            }
        }
        assertTrue(checked > 15, "comparisons with the top of the wall " + checked);
        assertTrue(burning > 10, "comparisons near the floor above the fires " + burning);
    }

    /**
     * A large halo effects layer is drawn according to the formula ({@code glowAlpha} in {@code fx.fsh}), a small halo is drawn from the picture
     * {@code far/glow} ({@code tools/gen_particles.py}): this is the same halo - the middle of the pixels of the picture are at r from
     * −1 to 1 through 63 step, for each pixel the formula gives its value (with an accuracy of 8 bits).
     */
    @Test
    void glowFormulaIsThePicture() throws IOException {
        GlslFunction f = GlslFunction.parse(read("core/fx.fsh"), "glowAlpha");
        BufferedImage img;
        try (InputStream in = PallGlslTest.class.getResourceAsStream("/assets/incoming/textures/far/glow.png")) {
            assertNotNull(in, "far/glow.png");
            img = ImageIO.read(in);
        }
        int n = img.getWidth();
        assertEquals(64, n, "fx.fsh counts the midpoints of the outer pixels in the picture 64×64");
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double x = -1 + 2.0 * i / (n - 1), y = -1 + 2.0 * j / (n - 1);
                double a = (img.getRGB(i, j) >>> 24) / 255.0;
                assertEquals(a, f.call(Map.of("r", Math.hypot(x, y))), 0.5 / 255 + 1e-4, "pixel " + i + ", " + j);
            }
        }
    }

    /**
     * How {@code fx_pall} combines light, and how it and {@code fx_pall_composite} translate it into the screen - according to the text of the
     *  shaders: the parts are tested separately, and {@code main} assembles them, which the test does not count. Fire at a point - all
     * lights together under one limit of flame (each in its own limit, and the sum was brighter than the fire); fires under dust - also in the
     *  limit; the thickness above the point is the entire column of the layer; light pall into the screen - once per channel, brighter than white - per fraction of a pixel
     *  with real transmission; shadow on the world - once.
     */
    @Test
    void pallPutsItsLightTogetherOnce() {
        String march = read("include/fx_pall.glsl"), composite = read("core/fx_pall_composite.fsh");
        assertTrue(march.contains("vec3 fire = min(below + under * (glow + s.fire / max(s.albedo, vec3(1e-7))) + around, gFlame);"),
                "fire at a point - one amount under the flame limit");
        assertTrue(march.contains("vec3 emit = s.albedo * (field + beams + fire);"), "point light - scattered, beams and fire");
        assertTrue(march.contains("+ min(below * exp(-gSootK * self) + (0.5 + 0.75 * PALL_SOOT_G * mv) * glow + around, gFlame);"),
                "fire on the smoke of the storm - one sum under the flame limit");
        assertTrue(march.contains("burnLight = pallFlame(") && march.contains("s.burn += burnLight;"), "fires under dust - no brighter than flames");
        assertTrue(march.contains("s.tau += f.column.x + f.column.y + f.column.z;"), "thickness above a point - the entire column of the layer");
        // light pall into the screen - once per channel; glow of mushrooms - film curve on top: sum with pall in linear
        //  (its screen back - once) and into the screen - again per channel
        assertEquals(6, occurrences(march, "pallDisplay("), "light pall into the screen - once per channel, glow of mushrooms on top - again");
        assertTrue(march.contains("vec3 lin = pallLinear3(pallShown(light, cover)) + mushFilm3(emitted);"), "glow of the ball - film curve on top of the pall");
        assertTrue(march.contains("vec3 shown = pallShown(light + heat, cover);"), "glow of the curled cap - in the same way, with pall");
        assertTrue(march.contains("shoulder(vec3(pallDisplay(medium.r), pallDisplay(medium.g), pallDisplay(medium.b))) * shownCover"),
                "at the output - a screen with a shoulder");
        assertTrue(march.contains("shownCover = min(cover * lum, 1.0);"), "overexposure - a fraction of a pixel, not more than the entire pixel");
        assertTrue(march.contains("vec4 res = vec4(shown, min(transmit + lost, 1.0));"),
                "frame transmission - real and in overexposure");
        assertEquals(4, occurrences(composite, "pallDisplay("), "shadow on the world in the screen - once, world in the light of the balls - once per channel");
        assertTrue(composite.contains("float kept = mix(1.0, pallDisplay(shade(p, over)), seen * pallOpenHere(p - 0.5 * dir));"),
                "the shadow on the world is on the screen, in the distance it melts into haze and fog, it is not underground");
        assertTrue(composite.contains("pall.a *= kept;"), "without the light of the balls, the shadow is a multiplier of the world in mixing");
        assertTrue(composite.contains("pall = vec4(pall.rgb + pall.a * shown, 0.0);"), "world in the light of balls - behind the pall, the whole pixel");
        assertEquals(scalar(march, "CROSS"), scalar(composite, "CROSS"), "overexposure to white - like a substance");
        assertArrayEquals(vec3(march, "LUMA"), vec3(composite, "LUMA"));
    }

    private static int occurrences(String text, String what) {
        int n = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1)) n++;
        return n;
    }

    /** Shader constant {@code const float name = a;}. */
    private static double scalar(String source, String name) {
        Matcher m = Pattern.compile("const\\s+float\\s+" + name + "\\s*=\\s*([^;]+);").matcher(source);
        if (!m.find()) throw new AssertionError("no const float " + name);
        return Double.parseDouble(m.group(1).strip());
    }

    /** Shader constant {@code const vec3 name = vec3(a, b, c);}. */
    private static double[] vec3(String source, String name) {
        Matcher m = Pattern.compile("const\\s+vec3\\s+" + name + "\\s*=\\s*vec3\\(([^)]*)\\)").matcher(source);
        if (!m.find()) throw new AssertionError("no const vec3 " + name);
        String[] p = m.group(1).split(",");
        return new double[]{Double.parseDouble(p[0].strip()), Double.parseDouble(p[1].strip()), Double.parseDouble(p[2].strip())};
    }

    @Test
    void fireLawsMatchJava() {
        GlslFunction bend = GlslFunction.parse(GLSL, "fireBend"), width = GlslFunction.parse(GLSL, "fireWidth"), lod = GlslFunction.parse(GLSL, "fireLod"),
                glow = GlslFunction.parse(GLSL, "fireGlowLod"), column = GlslFunction.parse(GLSL, "fireColumn");
        for (double h = -60; h <= 400; h += 0.5) {
            assertEquals(FireStorm.bend(h, FireStorm.BEND_HEIGHT), bend.call(Map.of("h", h, "hb", FireStorm.BEND_HEIGHT)), 1e-9, "h = " + h);
            for (double cell : new double[]{4, 32}) {
                assertEquals(FireStorm.width(h, cell), width.call(Map.of("h", h, "cell", cell)), 1e-9, "h = " + h);
                assertEquals(FireStorm.glowLod(h, cell), glow.call(Map.of("h", h, "cell", cell)), 1e-9, "h = " + h);
                double w = FireStorm.width(h, cell);
                assertEquals(FireStorm.lod(w, cell), lod.call(Map.of("w", w, "cell", cell)), 1e-9, "w = " + w);
            }
            assertEquals(FireStorm.column(h, 260), column.call(Map.of("h", h, "top", 260.0)), 1e-9, "h = " + h);
        }
    }
}
