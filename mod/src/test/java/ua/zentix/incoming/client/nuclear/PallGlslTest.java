package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.VolumeNoise;
import ua.zentix.incoming.nuclear.Detonation;
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
     * The pall's table holds one row per slot, the haze ({@link PallField#row} writes {@link PallField#haze} at every node, none over open
     * sea), the slot points the shader to it ({@link NukePall#tableRows}: S[2].w) and flags water under the pall (S[3].w), and
     * {@code pallField} reads the smoke parcel over the point (the fire zone's smoke by radius, widened by its shift, at the parcels'
     * strength by shift, the fire light at the point's own radius), the source water on the fire zone, the water map only with water
     * under the pall. No dust, spray or base surge is in the pall's shader: they are the dust pass's.
     */
    @Test
    void hazeIsThePallsOnlyRow() {
        PallField p = new PallField(GROUND);
        float[] row = new float[4 * PallField.ROWS * PallField.BINS];
        double[] v = new double[4];
        boolean haze = false;
        for (double ticks : new double[]{40, 400, 24000}) {
            p.row(ticks, true, row);
            for (int i = 0; i < PallField.BINS; i++) {
                p.haze(i, ticks, v);
                for (int k = 0; k < 4; k++) assertEquals((float) v[k], row[4 * i + k], 0, "haze, knot " + i);
                haze |= v[0] > 0;
            }
            p.row(ticks, false, row);
            for (int i = 0; i < PallField.BINS; i++) assertEquals(0, row[4 * i] + row[4 * i + 2], 0, "haze or fires over open sea, knot " + i);
        }
        assertTrue(haze, "no haze in any node");
        assertEquals(1, PallField.ROWS);
        for (int k = 0; k < 2; k++) {
            org.joml.Matrix4f m = new org.joml.Matrix4f();
            NukePall.tableRows(m, k, true);
            assertEquals((k + 0.5) / ua.zentix.incoming.client.fx.layer.PallPass.ROWS, m.m23(), 1e-7, "slot " + k + ": haze");
            assertEquals(1, m.m33(), 0, "slot " + k + ": water flag");
            NukePall.tableRows(m, k, false);
            assertEquals(0, m.m33(), 0, "slot " + k + ": land");
        }
        assertEquals(2 * PallField.ROWS, ua.zentix.incoming.client.fx.layer.PallPass.ROWS, "table rows - not for two slots");
        String f = body(GLSL, "PallAt pallField(");
        assertTrue(f.contains("vec4 haze = texture(table, vec2(min(uh, 1.0), S[2].w));"), "haze - not in its own row");
        assertEquals(2, occurrences(f, "texture(table"), "the pall reads its one row twice: shape by radius, parcels by travel "
                + "(a third read put fx_pall_composite over its AMD compiler budget)");
        assertEquals(2, occurrences(f, "S[2].w)"), "the pall reads one table row");
        assertTrue(f.contains("float len = S[1].z, down = dot(q, S[2].xy), along = clamp(down, 0.0, len), travel = clamp(down - S[3].x, 0.0, len);")
                && f.contains("float widen = 1.0 + PALL_PARCEL_SPREAD * travel / max(S[3].x, 1.0);")
                && f.contains("vec2 source = (q - S[2].xy * along) / widen;"),
                "the parcel over the point is not the newest one (travel past the zone's downwind edge) across the widened zone");
        assertTrue(f.contains("float carried = texture(table, vec2((travel / max(len, 1.0) * (PALL_BINS - 1.0) + 0.5) / PALL_BINS, S[2].w)).a;")
                && f.contains("sh = haze.r * carried * pallEdge(uh)"), "the parcels' strength is not read by shift at its nodes");
        assertTrue(f.contains("float fire = haze.b * (1.0 - smoothstep(0.85 * S[3].x, 1.05 * S[3].x, length(q)));"),
                "fire light is not the fires under the point (the zone's fire share, PallField.fire)");
        assertTrue(f.contains("if (S[3].w > 0.5 && sh + fire > 0.0) {") && f.contains("sh *= 1.0 - pallWater(ground, S, G, source);"),
                "the haze's source water - read without water under the pall, or not at its source");
        assertEquals(ua.zentix.incoming.nuclear.model.SmokeParcels.SPREAD, scalar(GLSL, "PALL_PARCEL_SPREAD"), 1e-7);
        assertEquals(PallField.BINS, scalar(GLSL, "PALL_BINS"), 0);
        assertEquals(PallField.BINS, ua.zentix.incoming.nuclear.model.SmokeParcels.NODES, "the haze row's nodes are the parcels' drawn nodes");
        assertEquals(PallField.column(0), ua.zentix.incoming.nuclear.model.SmokeParcels.COLUMN, 1e-12, "the parcels' column is the pall's profile");
        assertTrue(body(GLSL, "vec2 pallSoil(").contains("if (G.x <= 0.0 || S[1].w < 1.5) return vec2(0.0);"), "soil read without hard ground or sand under the pall");
        for (String gone : new String[]{"pallWall", "pallSurge", "PallWater", "PallYoung", "wallShare", "froxelEnd"}) {
            assertEquals(-1, GLSL.indexOf(gone), "pall.glsl still draws ground dust: " + gone);
            assertEquals(-1, read("include/fx_pall.glsl").indexOf(gone), "fx_pall.glsl still draws ground dust: " + gone);
        }
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
     * Billows of the mixed layer ({@code pallMixed}) are the same as in Java ({@link PallField#clumps}, {@link PallField#MIXED}): the
     * haze is a mixed layer, an even blanket from above.
     */
    @Test
    void blanketMatchesJava() {
        assertEquals(PallField.MIXED, scalar(GLSL, "PALL_MIXED"), 1e-7);
        GlslFunction mixed = GlslFunction.parse(GLSL, "pallMixed");
        for (double shape = 0; shape <= 3.5; shape += 0.05) {
            for (double m = 0; m <= 1; m += 0.05) {
                assertEquals(PallField.clumps(shape, m), mixed.call(Map.of("shape", shape, "mixed", m, "PALL_MIXED", PallField.MIXED)), 1e-9);
            }
        }
        String fx = body(read("include/fx_pall.glsl"), "void slot(");
        assertTrue(fx.contains("mixed = pallMixed(shape, 1.0);") && fx.contains("float h = f.sigma * mix(1.0, mixed, 0.5),"),
                "haze billows - not the mixed layer's");
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
     * Desert dust ({@code SAND}) is AERONET desert dust: its thick-layer colour is R∞ of the measured single-scattering albedo
     * ({@link NukePall#SAND_OMEGA}, Dubovik et al. 2002) after delta-Eddington with the dust phase g (forward peak g²), to the colour's
     * three decimals. Sand's share of lofted mass ({@code pallDustW}) is {@link PallField#sandShare}, with sand and hard ground as fractions of
     * the ground, as the map holds them.
     */
    @Test
    void sandDustIsDesertDust() {
        double g = PallLight.DUST_G, f = g * g;
        for (int c = 0; c < 3; c++) {
            double w = NukePall.SAND_OMEGA[c], scaled = w * (1 - f) / (1 - w * f);
            assertEquals(NukePall.SAND[c], PallLight.display(PallLight.reflectance(scaled, PallLight.truncated(g))), 6e-4, "channel " + c);
        }
        assertTrue(GLSL.replaceAll("\\s+", " ").contains("float share = sand / max(1.0 + hard * (paved - 1.0), 1e-6);"),
                "pallDustW - not PallField.sandShare");
        assertEquals(1, PallField.sandShare(1, 0, 0.2), 0);
        assertEquals(0, PallField.sandShare(0, 0.5, 0.2), 0);
        // half the ground hard (debris dust 0.2 of the soil's mass), the other half sand (map: sand 0.5, hard 0.5): sand is 0.5 / 0.6 of the mass
        assertEquals(0.5 / 0.6, PallField.sandShare(0.5, 0.5, 0.2), 1e-12);
    }

    /**
     * Shader substance ({@code PALL_*_W}, {@code PALL_*_G} in {@code pall.glsl}) - from the colors of the thick layer {@code fx_pall.glsl}
     *  in linear ({@link PallLight#albedo}, asymmetry {@link PallLight#truncated}): thick pall on the screen of the same colors,
     * that the wall of dust and splashes (R∞ albedo is its color). Soot and tornado flames in linear ({@code *_LINEAR}) are the same colors.
     */
    @Test
    void materialsHaveTheirColours() {
        String fx = read("include/fx_pall.glsl");
        String[][] materials = {{"DUST", "DUST"}, {"SAND", "DUST"}, {"SMOKE", "DUST"}, {"HAZE", "DUST"}, {"MIST", "MIST"}, {"SOOT", "SOOT"}};
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
            assertEquals(vec3(fx, "SAND")[c], NukePall.SAND[c], 0, "sand, channel " + c);
            assertEquals(smoke[c], NukePall.SMOKE[c], 0, "smoke, channel " + c);
            assertEquals(fire[c], NukePall.FIRE[c], 0, "flame, channel " + c);
        }
    }

    /**
     * pall behind the front at night - in the same light as the head of the wall in front of it: thick dust ({@code PALL_DUST_W}) in the light of the ball
     *  and fires according to the laws of the shader (pallDiffuse; ball - black body, like the body of a mushroom, pallBallShare × eye addiction;
     * fires - strength {@link CloudPuffs#glowOnSmoke}, divided by the color of dust {@code DUST}, on the screen above the sky light - in
     *  linear difference, pallLift; sky light - {@link NukePall#nightSky} in linear; result - on the screen, pallDisplay).
     * Not dark at the epicenter. The glow of the fires pall ({@code PallFireLight}) flares up later, for a minute,
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
        assertEquals(PallLight.CROSS, scalar(GLSL, "PALL_CROSS"), 0, "to white - like PallLight.screen");
        double g = scalar(GLSL, "PALL_DUST_G");
        CloudPuffs puffs = new CloudPuffs(GROUND);
        BlastWall blast = new BlastWall(GROUND, false);
        float ambient = 0.17f;
        double k = CloudPuffs.glowOnSmoke(ambient), sky = linear.call(Map.of("v", (double) NukePall.nightSky(ambient, 1))), tc = 8;
        float[] wall = new float[8], slot = new float[BlastWall.SLOT];
        NukePall.Sky night = new NukePall.Sky();
        night.sy = -1;
        java.util.Arrays.fill(night.sky, (float) sky);
        night.base = ambient;
        night.exposure = (float) Sight.adapted(1, ambient);
        night.glowOnSmoke = (float) k;
        int burning = 0;
        for (double t : new double[]{2, 6, 10, 20, 40}) {
            // fires at full flame (PallField.fireLight = 1: a burning city under its pall) — the strongest light from below
            assertTrue(puffs.pallLight(MushroomModel.shape(t, 15, 0, true, GROUND.metres(blast.wall(t).head())), 1, wall));
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
        assertTrue(march.contains("vec3 emit = s.albedo * (field + beams + fire + lamps);"), "point light - scattered, beams, fire and lamps");
        assertTrue(march.contains("vec3 lamps = pallLampsAt(p);"), "lamps once per point, by the window's block light");
        assertTrue(march.contains("+ min(below * exp(-gSootK * self) + (0.5 + 0.75 * PALL_SOOT_G * mv) * glow + around, gFlame);"),
                "fire on the smoke of the storm - one sum under the flame limit");
        assertTrue(march.contains("burnLight = pallFlame(") && march.contains("s.burn += burnLight;"), "fires under dust - no brighter than flames");
        assertTrue(march.contains("s.tau += f.column + liftedUp;"), "thickness above a point - the entire column of the layer");
        // light pall into the screen - once per channel; glow of mushrooms - film curve on top: sum with pall in linear
        //  (its screen back - once) and into the screen - again per channel
        String shown = body(GLSL, "vec3 pallShown(");
        assertEquals(3, occurrences(shown, "pallDisplay("), "light pall into the screen - once per channel");
        assertEquals(3, occurrences(march, "pallDisplay("), "glow of mushrooms on top - again per channel");
        assertTrue(march.contains("vec3 lin = pallLinear3(pallShown(light, cover) + airlight) + mushFilm3(emitted);"), "glow of the ball - film curve on top of the pall");
        assertTrue(march.contains("vec3 shown = pallShown(light + heat, cover) + airlight;"), "glow of the curled cap - in the same way, with pall");
        assertTrue(shown.contains("shoulder(vec3(pallDisplay(medium.r), pallDisplay(medium.g), pallDisplay(medium.b))) * shownCover"),
                "at the output - a screen with a shoulder");
        assertTrue(shown.contains("shownCover = min(cover * lum, 1.0);"), "overexposure - a fraction of a pixel, not more than the entire pixel");
        assertTrue(march.contains("vec4 res = vec4(shown, min(transmit, 1.0));") && !march.contains("transmit + lost"),
                "frame transmission - real: haze shows airlight in place of matter, never what lies behind it (sun through the cap)");
        // shadow on the world into the screen once; world in the light of the balls once per channel; the frame's one flash
        // exposure (DESIGN fireball-light §4.1) once for the world's own light and once for the rest of the frame; the eye's
        // veil over the finished pixel once per channel
        // (and once for a see-through pane's own light in the ball-lit world, as the unlit path takes it with the frame)
        assertEquals(10, occurrences(composite, "pallDisplay("), "every conversion into the screen is counted");
        assertEquals(1, occurrences(body(composite, "vec3 ballLitWorld("), "pallDisplay(pallLinear(kept) * PallFlash.x)"),
                "the world's own light takes the flash exposure once, in linear light");
        assertTrue(composite.contains("float kept = mix(1.0, pallDisplay(shade(p, over)), seen * open);")
                        && composite.contains("float open = pallOpenHere(air);") && composite.contains("vec3 air = p - 0.5 * dir;"),
                "the shadow on the world is on the screen, in the distance it melts into haze and fog, it is not underground");
        assertTrue(composite.contains("if (PallLamps > 0.5) kept = pallKeepLamps(kept, Sampler10, pallSurfaceLevel(PallOpen, PallOpenAt, PallOpenWrap, air), 15.0 * open);"),
                "torches and lamps keep their share of a pixel's light under the shadow");
        assertTrue(composite.contains("pall.a *= kept;"), "without the light of the balls, the shadow is a multiplier of the world in mixing");
        assertTrue(composite.contains("if (whole && pallValid(pall)) laid = vec4(laid.rgb + laid.a * ballLit, 0.0);"),
                "world in the light of balls - behind the pall, the whole pixel");
        assertEquals(scalar(GLSL, "PALL_CROSS"), scalar(composite, "CROSS"), "overexposure to white - like a substance");
        assertArrayEquals(vec3(GLSL, "PALL_LUMA"), vec3(composite, "LUMA"));
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
        GlslFunction source = GlslFunction.parse(GLSL, "fireSourceCell");
        for (double cell : new double[]{2, 4, 32, 64, 96, 512})
            assertEquals(FireStorm.sourceCell(cell), source.call(Map.of("cell", cell)), 1e-9);
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
        GlslFunction risen = GlslFunction.parse(GLSL, "fireRisen");
        for (double h = -10; h <= 900; h += 3.5)
            for (double[] gb : new double[][]{{0, 0}, {0.4, 0}, {0.4, 0.1}, {0.4, 0.4}, {0.4, 0.6}, {1e-8, 1e-8}})
                assertEquals(FireStorm.feedAt(h, 840, gb[0], gb[1]), risen.call(Map.of("h", h, "top", 840.0, "g", gb[0], "b", gb[1])), 1e-9,
                        "h = " + h + ", g " + gb[0] + ", b " + gb[1]);
    }
}
