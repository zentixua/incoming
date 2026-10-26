package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.Yield;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The laws of the mushroom in the shader ({@code shaders/include/mushroom.glsl}) are the same as in Java ({@link MushroomModel},
 * {@link MushroomField}): shader functions and constants are read from the file and are considered expression parsing GLSL
 * ({@link GlslFunction}). Changing the law in just one place breaks this test.
 */
class MushroomGlslTest {
    private static final String GLSL = read("include/mushroom.glsl");

    private static String read(String path) {
        try (InputStream in = MushroomGlslTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** All shader numeric constants ({@code const float} and {@code const int}), expressions are based on those already read. */
    private static Map<String, Double> constants() {
        Map<String, Double> out = new HashMap<>();
        Matcher m = Pattern.compile("const\\s+(?:float|int)\\s+(\\w+)\\s*=\\s*([^;]+);").matcher(GLSL);
        while (m.find()) {
            GlslFunction f = GlslFunction.parse("float c() { return " + m.group(2) + "; }", "c");
            out.put(m.group(1), f.call(out));
        }
        return out;
    }

    /** presentation of fireball ({@code mushBallGain}, {@code mushFilm}, {@code mushFilmWhite}) - the same as {@link NukeExposure}. */
    @Test
    void ballExposureMatchesJava() {
        GlslFunction gain = GlslFunction.parse(GLSL, "mushBallGain"), film = GlslFunction.parse(GLSL, "mushFilm");
        GlslFunction white = GlslFunction.parse(GLSL, "mushFilmWhite");
        for (double adapt : new double[]{1, 1200}) {
            for (double key = 0; key < 1e8; key = key * 1.3 + 0.001) {
                assertEquals(NukeExposure.gain(key, adapt), gain.call(Map.of("key", key, "adapt", adapt)), 1e-9, "key " + key);
            }
        }
        GlslFunction share = GlslFunction.parse(GLSL, "mushFilmShare");
        for (double roll = -0.1; roll < 1.1; roll += 0.005) {
            assertEquals(NukeExposure.filmShare(roll), share.call(Map.of("roll", roll)), 1e-12, "share of the ring " + roll);
        }
        for (double x = -0.5; x < 30; x += 0.01) {
            assertEquals(NukeExposure.film(x), film.call(Map.of("x", x)), 1e-12, "x " + x);
            assertEquals(NukeExposure.white(x), white.call(Map.of("top", x)), 1e-12, "x " + x);
        }
    }

    @Test
    void stemRadiusMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "mushStemR");
        for (double flare : new double[]{0, 15, 59}) {
            for (double w = -0.2; w <= 1.2; w += 0.01) {
                assertEquals(MushroomModel.stemRadius(w, 37, flare), f.call(Map.of("w", w, "stemR", 37.0, "flare", flare)), 1e-9,
                        "w = " + w + ", bell " + flare);
            }
        }
    }

    /** The suction at the base of the stem ({@code mushFoot*}, layer profile - {@code pallProfile}) is the same as in Java ({@link MushroomField#footDensity}). */
    @Test
    void footMatchesJava() {
        String both = read("include/pall.glsl") + GLSL;
        GlslFunction width = GlslFunction.parse(GLSL, "mushFootWidth"), share = GlslFunction.parse(GLSL, "mushFootShare");
        GlslFunction density = GlslFunction.parse(both, "mushFootDensity"), band = GlslFunction.parse(GLSL, "mushFootBand");
        GlslFunction narrow = GlslFunction.parse(GLSL, "mushFootNarrow"), wall = GlslFunction.parse(GLSL, "mushFootWall");
        for (double rho = 0; rho <= 600; rho += 3) assertEquals(MushroomField.footWall(rho, 400), wall.call(Map.of("rho", rho, "r", 400.0)), 1e-9);
        for (double top : new double[]{0.5, 40, 380}) {
            for (double h = -0.2 * top; h <= 5 * top; h += 0.01 * top) {
                Map<String, Double> at = Map.of("h", h, "top", top);
                assertEquals(MushroomField.footWidth(h, top), width.call(at), 1e-9, "h " + h);
                assertEquals(MushroomField.footShare(h, top), share.call(at), 1e-9, "h " + h);
                for (double rise : new double[]{0.3 * top, 1e9}) {
                    assertEquals(MushroomField.footDensity(h, top, 0.4, 0.7, rise),
                            density.call(Map.of("h", h, "top", top, "sigma", 0.4, "share", 0.7, "rise", rise)), 1e-9, "h " + h);
                }
                for (double merge : new double[]{0, 0.4 * top, 2.3 * top}) {
                    assertEquals(MushroomField.footBand(h, top, 0.7, merge), band.call(Map.of("h", h, "top", top, "share", 0.7, "merge", merge)), 1e-9, "h " + h);
                    assertEquals(MushroomField.footNarrow(h, top, merge), narrow.call(Map.of("h", h, "top", top, "merge", merge)), 1e-9, "h " + h);
                }
            }
        }
    }

    /** The reflection of the walls of the valleys cap ({@code mushWallR}) is the same as in Java ({@link MushroomField#wallReflectance}). */
    @Test
    void wallReflectanceMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "mushWallR");
        for (double g = 0; g <= 0.61; g += 0.05) {
            for (double w = 0; w <= 1; w += 0.01) {
                assertEquals(MushroomField.wallReflectance(w, g), f.call(Map.of("w", w, "g", g)), 1e-9, "ω' " + w + ", g' " + g);
            }
        }
    }

    @Test
    void driftShareMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "mushShare");
        for (double end : new double[]{0.5, 300, 2500}) {
            for (double h = -100; h <= 4000; h += 7) {
                assertEquals(MushroomModel.driftShare(h, end), f.call(Map.of("h", h, "endTop", end)), 1e-9, "h = " + h);
            }
        }
    }

    /** Line of light of a black body: the same nodes, temperature of the node i - place i in the line. */
    @Test
    void blackBodyNodesMatchJava() {
        Map<String, Double> c = constants();
        assertEquals(MushroomField.BB_NODES, c.get("MUSH_BB_NODES"), 0);
        assertEquals(MushroomField.BB_FROM, c.get("MUSH_BB_FROM"), 0);
        assertEquals(MushroomField.BB_TO, c.get("MUSH_BB_TO"), 0);
        assertEquals(MushroomField.BB_ROW, c.get("MUSH_BB_ROW"), 0);
        assertEquals(MushroomField.MUSHROOMS, c.get("MUSHROOMS"), 0);
        GlslFunction u = GlslFunction.parse(GLSL, "mushBbU");
        for (int i = 0; i < MushroomField.BB_NODES; i++) {
            Map<String, Double> args = new HashMap<>(c);
            args.put("kelvin", MushroomField.nodeKelvin(i));
            assertEquals(i, u.call(args) * (MushroomField.BB_NODES - 1), 1e-6, "node " + i);
        }
    }

    /** Shadow map: same tile and same low sun height; light above the point in three layers - as in Java. */
    @Test
    void shadowLawsMatchJava() {
        Map<String, Double> c = constants();
        assertEquals(MushroomField.SHADOW, c.get("MUSH_SHADOW"), 0);
        assertEquals(MushroomField.SHADOW_LOW, c.get("MUSH_SHADOW_LOW"), 1e-12);
        assertEquals(MushroomField.SHADOW_RECT, c.get("MUSH_SHADOW_RECT"), 0);
        assertEquals(MushroomField.SHADOW_TOP, c.get("MUSH_SHADOW_TOP"), 0);
        GlslFunction layer = GlslFunction.parse(GLSL, "mushShadowLayer");
        for (double top : new double[]{0.5, 320, 384}) {
            for (double h = -60; h <= 460; h += 3.7) {
                assertEquals(MushroomField.shadowLayer(h, top, 0.3, 0.6, 0.95), layer.call(Map.of("h", h, "top", top, "t0", 0.3, "t1", 0.6, "t2", 0.95)),
                        1e-9, "top " + top + ", height " + h);
            }
        }
        GlslFunction seen = GlslFunction.parse(GLSL, "mushSeen"), across = GlslFunction.parse(GLSL, "mushAcross");
        for (double mu : new double[]{0.05, 0.3, 1}) {
            for (double w : new double[]{1, 30, 300}) {
                for (double d : new double[]{0, 10, 500, 5000}) {
                    assertEquals(MushroomField.seen(w, d, mu), seen.call(Map.of("w", w, "d", d, "mu", mu)), 1e-9,
                            "μ " + mu + ", width " + w + ", distance " + d);
                    assertEquals(MushroomField.across(w, d, mu), across.call(Map.of("r", w, "h", d, "mu", mu)), 1e-6 * MushroomField.across(w, d, mu),
                            "μ " + mu + ", radius " + w + ", height " + d);
                }
            }
        }
        // light to layer in map pass: with two pall threads (pallDiffuse) - as in Java
        GlslFunction through = GlslFunction.parse(read("include/pall.glsl") + GLSL + read("core/fx_mush_shadow.fsh"), "through");
        for (double tau : new double[]{0, 0.3, 2, 9, 64}) {
            for (double w : new double[]{0.5, 0.9, 0.99}) {
                for (double d : new double[]{0, 200, 3000}) {
                    for (double width : new double[]{20, 150, 900}) {
                        for (double g : new double[]{0.375, 0.7}) {
                            Map<String, Double> args = Map.of("tau", tau, "d", d, "width", width, "w", w, "g", g, "mu", 0.3);
                            // −2/3 in the shader - seven characters
                            assertEquals(MushroomField.shadowThrough(tau, d, width, w, g, 0.3), through.call(args), 1e-7,
                                    "thickness " + tau + ", albedo " + w + ", distance " + d + ", width " + width + ", g " + g);
                        }
                    }
                }
            }
        }
    }

    /**
     * Scattered like a mushroom forward onto the earth: a cloud throughout the entire hemisphere - like a layer without an edge (on the horizontal earth (1 + μ) / 2
     *  of its flow), narrow and distant - like a beam from the sky near the sun (v·μ). Therefore, a thin stem hundreds of blocks from the ground gives the shadow of
     *  as an almost straight beam (what it scatters forward comes from a small fraction of the sky), and a wide layer low above the ground - like
     *  - two layer streams. The width of the cloud is the area of ​​its shadow across the sun: from above - a circle, from the side - in full height.
     */
    @Test
    void forwardLightSeenLikeTheCloud() {
        for (double mu : new double[]{0.05, 0.3, 1}) {
            assertEquals((1 + mu) / (2 * mu), MushroomField.seen(1e9, 1, mu), 1e-9);
            assertEquals(1e-8, MushroomField.seen(1, 1e4, mu), 1e-14);
            for (double v = 0.01; v < 1; v += 0.01) {
                double w = Math.sqrt(v / (1 - v));
                assertTrue(MushroomField.seen(w + 0.01, 1, mu) > MushroomField.seen(w, 1, mu), "wider - more visible");
            }
            assertEquals(40 * Math.sqrt(mu), MushroomField.across(40, 0, mu), 1e-9, "flat - a circle compressed by an inclination");
        }
        assertEquals(40, MushroomField.across(40, 900, 1), 1e-9, "sun overhead - circle above");
        assertEquals(Math.sqrt(2 / Math.PI * 40 * 900), MushroomField.across(40, 900, 0), 1e-9, "the sun is on the horizon - the pillar is on the side");
        double stem = PallLight.albedo(PallLight.linear(((MushroomField.DUST >> 8) & 0xFF) / 255.0), PallLight.truncated(MushroomField.G));
        // stem: thickness 2 across, half-width 60 blocks, in 400 blocks straight to the sun at height 30°
        double g = PallLight.truncated(MushroomField.G);
        double t = MushroomField.shadowThrough(2, 400, 60, stem, g, 0.5);
        assertTrue(t < Math.exp(-2) + 0.02, String.format(Locale.ROOT, "stem skips %.3f", t));
        // a layer is a thousand times wider than the distance to it - like a layer without an edge
        double slab = Math.exp(-2) + PallLight.diffuse(stem, g, 2, 2, 0, 1, 1, 1, 0, 0, 0, -2.0 / 3) * 1.5 / (2 * 0.5);
        assertEquals(slab, MushroomField.shadowThrough(2, 50, 5e4, stem, g, 0.5), 1e-5);
    }

    /**
     * The direct shadow map beyond the MUSH_SHADOW_DARK thickness is not considered, and the output to the frame takes the light under it as under this thickness:
     *  through it and at the very white matter of the mushroom (water dust of the skirt, drops and steam cap) less than a percent of the light of the sun passes through -
     *  in a direct beam and scattered forward, even if the cloud covers the entire hemisphere of the low sun (cosine beam at output
     *  per frame - no less than 0,12).
     */
    @Test
    void shadowCutLosesUnderAPercent() {
        double dark = constants().get("MUSH_SHADOW_DARK"), dust = PallLight.truncated(MushroomField.G);
        // mushroom substance - with asymmetry of dust pall, column of water - drops
        double[][] matter = {{MushroomField.CAP_DUSTY, dust}, {MushroomField.DUST, dust},
                {MushroomField.STEAM, dust}, {CloudPuffs.SPRAY, dust}, {CloudPuffs.SPRAY, PallLight.truncated(MushroomField.G_WATER)}};
        for (double[] at : matter) {
            int rgb = (int) at[0];
            double g = at[1];
            assertDark(dark, PallLight.albedo(PallLight.linear(((rgb >> 8) & 0xFF) / 255.0), g), g, String.format(Locale.ROOT, "%06X", rgb));
        }
        // cap - drops and NO₂ (shadow map takes green): the whitest in power and time
        double[] w = new double[4];
        double white = 0, g = 0;
        for (double kt : new double[] {0.1, 1, 15, 100, 1000, 10_000}) {
            for (double t = 1; t < MushroomModel.lifeSeconds(kt); t *= 1.3) {
                MushroomModel.Shape s = MushroomModel.shape(t, kt, 240 * Math.cbrt(kt), false, 1e9);
                if (s == null) continue;
                MushroomField.capMatter(s, 0, 0, w);
                if (w[1] > white) {
                    white = w[1];
                    g = w[3];
                }
            }
        }
        assertDark(dark, white, g, String.format(Locale.ROOT, "cap, ω' %.4f", white));
    }

    private static void assertDark(double dark, double w, double g, String what) {
        for (double mu = 0.12; mu <= 1; mu += 0.01) {
            double through = MushroomField.shadowThrough(dark, 0, 1, w, g, mu);
            assertTrue(through < 0.01, String.format(Locale.ROOT, "%s, g %.3f, sun %.2f: %.4f", what, g, mu, through));
        }
    }

    /** Light of the ball on the edge of the world ({@code mushBallFace}) - law {@link MushroomField#ballFace}. */
    @Test
    void ballFaceMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "mushBallFace");
        for (double nv = -1; nv <= 1; nv += 0.25) {
            for (double vy : new double[]{-0.1, 0, 0.15, 0.7}) {
                for (double ny : new double[]{-1, 0, 1}) {
                    for (double sd : new double[]{0, 0.05, 0.4, 0.8, 3}) {
                        for (double[] pall : new double[][]{{1, 0}, {0.3, 0.25}}) {
                            Map<String, Double> args = Map.of("nv", nv, "vy", vy, "ny", ny, "sd", sd, "albedo", 0.2, "through", pall[0], "diffuse", pall[1]);
                            assertEquals(MushroomField.ballFace(nv, vy, ny, sd, 0.2, pall[0], pall[1]), f.call(args), 1e-6, args.toString());
                        }
                    }
                }
            }
        }
    }

    /**
     * 15 kt in the air, viewer at 4 km, clear (complaint 18.10: in the first seconds the “second sun” gives sharp shadows across the entire
     *  map). According to physics, the ball in the second maximum is a disk at ~6° at a height of ~9°, the light on the edge of it is ~250 times brighter than noon
     * (Glasstone and Dolan: P_max = 4·W^0,56 kt/s - the same), through 2 s - ~2,6 times. The face turned away from the ball sees
     *  the ground illuminated by it, the side and top also see the sky around the ball, scattered by air: at the second maximum they are
     *  times brighter than midday (on the screen they are white, like the face to the ball), and not in daylight alone 200 times darker
     * face to ball; through 2 s - the face to the ball is brighter than the side by a factor of 5–6.
     */
    @Test
    void ballLightsWhatFacesAway() {
        double kt = 15, hob = Yield.optimalBurstHeight(kt), range = 4000, sd = Sight.KOSCHMIEDER * Math.hypot(range, hob) / ThermalModel.VISIBILITY_CLEAR;
        for (double t : new double[]{FireballModel.secondMaximumSeconds(kt), 2}) {
            MushroomModel.Shape s = MushroomModel.shape(t, kt, hob, false, 0);
            double d = Math.hypot(range, s.capY()), vy = s.capY() / d, flat = Math.sqrt(1 - vy * vy);
            double e = BlackBody.luminance(MushroomModel.edgeTemperature(t, kt, false)) * s.capR() * s.capR() / (d * d);
            double toward = e * MushroomField.ballFace(flat, vy, 0, sd, 0.2, 1, 0), away = e * MushroomField.ballFace(-flat, vy, 0, sd, 0.2, 1, 0);
            double side = e * MushroomField.ballFace(0, vy, 0, sd, 0.2, 1, 0), top = e * MushroomField.ballFace(vy, vy, 1, sd, 0.2, 1, 0);
            String at = String.format(Locale.ROOT, "%.2f s: to the ball %.1f, away %.2f, sideways %.2f, top %.2f noon", t, toward, away, side, top);
            if (t < 1) {
                assertTrue(toward > 150 && toward < 300, at);
                assertTrue(away > 3 && side > 20 && top > 20, at);
            } else {
                assertTrue(toward > 1.5 && toward < 4, at);
                assertTrue(toward / side > 3 && toward / side < 7, at);
            }
        }
    }

    /**
     * Light of the ball on the world ({@code fx_pall_composite}): decline from the middle - like {@link MushroomModel#light}; the world pixel under
     *  is like {@link MushroomField#relit}. Without the light of the ball, the pixel is the same (the shadow is the same fraction on the screen); the light falls according to the
     *  color of the block: a dark block brightens less, and a pixel darker than the open sky (cave, house) is the same number of times less;
     *  the color of a block is no whiter than white.
     */
    @Test
    void ballLightMatchesJava() {
        GlslFunction falloff = GlslFunction.parse(GLSL, "mushBallFalloff");
        MushroomModel.Shape s = MushroomModel.shape(3, 15, 0, true, 1e9);
        double lum = ua.zentix.incoming.nuclear.model.BlackBody.luminance(MushroomModel.edgeTemperature(3, 15, true));
        for (double d : new double[]{0, 100, s.capR(), 500, 2000, 9000}) {
            double r = s.capR();
            assertEquals(MushroomModel.light(s, 15, true, d) / lum, falloff.call(Map.of("d2", d * d, "r2", r * r)), 1e-9, "d = " + d);
        }
        GlslFunction relit = GlslFunction.parse(read("include/pall.glsl") + read("include/scene.glsl"), "relit");
        for (double world : new double[]{0, 0.05, 0.3, 0.8, 1}) {
            for (double kept : new double[]{0.4, 1}) {
                for (double open : new double[]{0.3, 1}) {
                    for (double added : new double[]{0, 0.01, 2, 300}) {
                        Map<String, Double> args = Map.of("world", world, "kept", kept, "open", open, "added", added);
                        assertEquals(MushroomField.relit(world, kept, open, added), relit.call(args), 1e-9, args.toString());
                    }
                }
            }
        }
        // without the ball light - the same pixel with the same shadow
        assertEquals(0.3 * 0.7, PallLight.display(MushroomField.relit(0.3, 0.7, 0.31, 0)), 1e-9);
        // light - according to the color of the block: the dark one brightens less than the light one, the pixel in the house is twice as dark as the sky - half as much
        double open = 0.31, light = 0.5;
        double bright = MushroomField.relit(0.3, 1, open, light) - PallLight.linear(0.3);
        double dark = MushroomField.relit(0.1, 1, open, light) - PallLight.linear(0.1);
        assertTrue(dark < bright, dark + " ≥ " + bright);
        double inside = MushroomField.relit(PallLight.display(PallLight.linear(0.3) / 2), 1, open, light) - PallLight.linear(0.3) / 2;
        assertEquals(bright / 2, inside, 1e-9);
        // the torch has a pixel brighter than the open sky - the block color is no whiter than white
        assertEquals(PallLight.linear(0.9) + light, MushroomField.relit(0.9, 1, open, light), 1e-9);
    }

    /** Mushroom table: The shader reads texels 0..TEXELS−1 of the mushroom string (by number or constant). */
    @Test
    void tableLayoutMatchesJava() {
        Map<String, Double> c = constants();
        Matcher m = Pattern.compile("ivec2\\((\\w+), k\\)").matcher(GLSL);
        int max = -1;
        while (m.find()) {
            String at = m.group(1);
            max = Math.max(max, at.matches("\\d+") ? Integer.parseInt(at) : c.get(at).intValue());
        }
        assertEquals(MushroomField.FOOT, max);
        assertEquals(MushroomField.FOOT_COLUMNS, c.get("MUSH_FOOT_COLUMNS").intValue());
        assertEquals(FootColumn.NODES, c.get("MUSH_FOOT_NODES").intValue());
        assertEquals(MushroomField.FOOT_COLUMNS + FootColumn.NODES / 4, c.get("MUSH_FOOT_EDGES").intValue());
        assertEquals(FootColumn.EDGE_NODES, c.get("MUSH_FOOT_EDGE_NODES").intValue());
        assertEquals(MushroomField.TEXELS, MushroomField.FOOT_COLUMNS + (FootColumn.NODES + FootColumn.EDGE_NODES) / 4);
    }

    /**
     * billows and edge are the same numbers as the mushroom frame in Java; at the distance to which the frame takes the substance (in units of
     *  billows of the part), the edge shifted outward by the strongest billows no longer provides substance, and deeper than the edge shifted inward
     *  just as strongly - all the density.
     */
    @Test
    void edgeLawsMatchJava() {
        Map<String, Double> c = constants();
        assertEquals(MushroomField.LOBES_CAP, c.get("MUSH_CAP_LOBES"), 0);
        assertEquals(MushroomField.LOBES_STEM, c.get("MUSH_STEM_LOBES"), 0);
        assertEquals(MushroomField.LOBES_SKIRT, c.get("MUSH_SKIRT_LOBES"), 0);
        assertEquals(MushroomField.EDGE_CAP, c.get("MUSH_CAP_EDGE"), 0);
        assertEquals(MushroomField.EDGE_STEM, c.get("MUSH_STEM_EDGE"), 0);
        assertEquals(MushroomField.EDGE_SKIRT, c.get("MUSH_SKIRT_EDGE"), 0);
        assertEquals(MushroomField.LOBES_COLUMN, c.get("MUSH_COLUMN_LOBES"), 0);
        assertEquals(MushroomField.EDGE_COLUMN, c.get("MUSH_COLUMN_EDGE"), 0);
        assertEquals(MushroomField.BLEND, c.get("MUSH_BLEND"), 0);
        assertEquals(MushroomModel.SKIN, c.get("MUSH_SKIN"), 1e-12);
        assertEquals(MushroomField.REACH_CAP, c.get("MUSH_CAP_REACH"), 1e-12);
        assertEquals(MushroomField.REACH_STEM, c.get("MUSH_STEM_REACH"), 1e-12);
        assertEquals(MushroomField.REACH_SKIRT, c.get("MUSH_SKIRT_REACH"), 1e-12);
        assertEquals(MushroomField.REACH_COLUMN, c.get("MUSH_COLUMN_REACH"), 1e-12);
        GlslFunction edge = GlslFunction.parse(GLSL, "mushEdge");
        // at the cap and pillar, the frame takes another quarter of the smoothing of the union of the ring and dome, wall and crown: soft
        // minimum lowers the edge
        double[][] parts = {{MushroomField.LOBES_CAP, MushroomField.EDGE_CAP, MushroomField.REACH_CAP - MushroomField.BLEND / 4},
                {MushroomField.LOBES_STEM, MushroomField.EDGE_STEM, MushroomField.REACH_STEM},
                {MushroomField.LOBES_SKIRT, MushroomField.EDGE_SKIRT, MushroomField.REACH_SKIRT},
                {MushroomField.LOBES_COLUMN, MushroomField.EDGE_COLUMN, MushroomField.REACH_COLUMN - MushroomField.BLEND / 4}};
        for (double[] p : parts) {
            double lobes = MushroomField.LOBES_MAX * p[0];
            assertEquals(0, edge.call(Map.of("d", p[2] - lobes, "e", p[1])), 1e-12);
            assertEquals(1, edge.call(Map.of("d", -p[1], "e", p[1])), 1e-12);
        }
    }

    /**
     * Boiling cap ({@code mushCapLobes}): two layers of a pattern with a half-life shift, shares in the total - 1. The layer pattern changes to
     *  only where there is no layer (share 0), and the layer without a lobe is entirely below the lowest living place: the change is not visible. The life of the
     *  layer is the rise and departure of billows in half a section, according to Kolmogorov (vortex rotation time ∝ size^⅔). Correction for the average—
     * average of the upper of the two layers (billows ±1/2, uniformly) with their shifts with a minus sign: the cap for the life of the layer in the
     * average does not shrink or grow, and the top of the billows is not higher than {@link MushroomField#LOBES_MAX} (the frame according to it mushroom).
     * The rotation at the fade mushroom axis is zero, at the ring axis it is complete.
     */
    @Test
    void capBoilsWithoutJumps() {
        Map<String, Double> c = constants();
        assertEquals(MushroomModel.RING, c.get("MUSH_RING"), 0);
        double sink = c.get("MUSH_SINK"), cycle = c.get("MUSH_CYCLE");
        double top = c.get("MUSH_LOBES_TOP"), deep = c.get("MUSH_LOBES_DEEP");
        assertEquals(2 * Math.pow(0.5, 2.0 / 3), cycle, 0.005);
        assertEquals(0.5 + 0.35 * 0.5, top, 1e-12, "top billows single layer (mushLobes)");
        assertTrue(top <= MushroomField.LOBES_MAX);
        assertTrue(top - sink <= -top, "the retiring layer protrudes above the living one");
        GlslFunction age = GlslFunction.parse(GLSL, "mushLayerAge"), weight = GlslFunction.parse(GLSL, "mushLayerWeight");
        GlslFunction seed = GlslFunction.parse(GLSL, "mushLayerSeed"), rise = GlslFunction.parse(GLSL, "mushRise");
        GlslFunction taper = GlslFunction.parse(GLSL, "mushTaper");
        double[] lastSeed = {-1, -1}, lastW = {0, 0};
        for (double psi = 0; psi < 30; psi += 1e-3) {
            double sum = 0;
            for (int j = 0; j < 2; j++) {
                Map<String, Double> a = new HashMap<>(c);
                a.put("churn", psi);
                a.put("j", (double) j);
                double w = weight.call(Map.of("age", age.call(a))), s = seed.call(a);
                if (psi > 0 && s != lastSeed[j]) {
                    assertTrue(w < 0.01 && lastW[j] < 0.01, "layer " + j + " changed the pattern in sight: " + psi + " rad");
                }
                lastSeed[j] = s;
                lastW[j] = w;
                sum += w;
            }
            assertEquals(1, sum, 1e-9, psi + " rad");
        }
        int n = 400;
        for (double low = 0; low <= 0.5 + 1e-9; low += 0.05) {
            double mean = 0;
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < n; k++) {
                    double x = (i + 0.5) / n - 0.5, y = (k + 0.5) / n - 0.5;
                    mean += Math.max(x - low * sink, y - (1 - low) * sink);
                }
            }
            mean /= (double) n * n;
            Map<String, Double> a = new HashMap<>(c);
            a.put("low", low);
            double r = rise.call(a);
            assertEquals(0, mean + r, 1e-3, "share " + low);
            // top: top billows of a strong layer with amendment - not higher than the mushroom frame; bottom: bottom billows of strong layer with adjustment
            assertTrue(top - low * sink + r <= MushroomField.LOBES_MAX + 1e-12, "share " + low);
            assertTrue(-top - low * sink + r >= -deep - 1e-12, "share " + low + ": hollow deeper MUSH_LOBES_DEEP");
        }
        assertEquals(0, taper.call(Map.of("rho", 0.0, "ringR", 300.0)), 0);
        assertEquals(1, taper.call(Map.of("rho", 300.0, "ringR", 300.0)), 0);
        assertEquals(1, taper.call(Map.of("rho", 900.0, "ringR", 300.0)), 0);
    }

    /**
     * Reversal cap ({@code mushFlow}): the substance that is now at the top of the ring section came from the axis of the mushroom, outside -
     *  top, inside - bottom, bottom - outside (Turner vortex: inside up, along the top out, outside down). The core of the section
     *  rotates entirely, from the outside - like 1/r² (Rankine vortex): at the edge of the section without a jump.
     */
    @Test
    void capRollsUpTheMiddleAndDownTheOutside() {
        GlslFunction wasU = GlslFunction.parse(GLSL, "mushWasU"), wasV = GlslFunction.parse(GLSL, "mushWasV");
        GlslFunction swirl = GlslFunction.parse(GLSL, "mushSwirl");
        double ang = 0.3;
        // {u, v} now → from where: shift sign along u and along v
        double[][] cases = {{0, 1, -1, 0}, {1, 0, 0, 1}, {-1, 0, 0, -1}, {0, -1, 1, 0}};
        for (double[] k : cases) {
            Map<String, Double> a = Map.of("u", k[0], "v", k[1], "ang", ang);
            double du = wasU.call(a) - k[0], dv = wasV.call(a) - k[1];
            if (k[2] != 0) assertEquals(k[2], Math.signum(du), 0, "u = " + k[0] + ", v = " + k[1]);
            if (k[3] != 0) assertEquals(k[3], Math.signum(dv), 0, "u = " + k[0] + ", v = " + k[1]);
            assertEquals(1, Math.hypot(wasU.call(a), wasV.call(a)), 1e-12, "along an ellipse section");
        }
        assertEquals(1, swirl.call(Map.of("u", 0.3, "v", -0.4)), 0);
        assertEquals(1, swirl.call(Map.of("u", 0.6, "v", 0.8)), 1e-12);
        assertEquals(0.25, swirl.call(Map.of("u", 0.0, "v", 2.0)), 1e-12);
    }

    /** The ball surface pattern map in the shader ({@code mushOctU}, {@code mushOctV}) is the same as in Java ({@link NukeBallSurface}). */
    @Test
    void ballSurfaceMapMatchesJava() {
        GlslFunction u = GlslFunction.parse(GLSL, "mushOctU"), v = GlslFunction.parse(GLSL, "mushOctV");
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            double x = rnd.nextGaussian(), y = rnd.nextGaussian(), z = rnd.nextGaussian();
            Map<String, Double> a = Map.of("x", x, "y", y, "z", z);
            assertEquals(NukeBallSurface.octU(x, y, z), u.call(a), 1e-9);
            assertEquals(NukeBallSurface.octV(x, y, z), v.call(a), 1e-9);
        }
    }

    /**
     * The surface pattern appears in the shader ({@code mushBallContrast}) as it is opened by the front ({@link
     * FireballModel#unmasked}): the place of the frame before the tear is the share of the open one - 1 ({@link MushroomField#ballSurface}).
     */
    @Test
    void ballSurfaceContrastMatchesTheBreakaway() {
        GlslFunction f = GlslFunction.parse(GLSL, "mushBallContrast");
        double[] out = new double[2];
        for (double t = 0.0005; t < 0.5; t *= 1.1) {
            MushroomField.ballSurface(t, 600, 15, false, new float[]{0, 1, 2}, out);
            assertEquals(FireballModel.unmasked(t, 15), f.call(Map.of("f", out[0])), 1e-9, "t = " + t);
        }
    }
}
