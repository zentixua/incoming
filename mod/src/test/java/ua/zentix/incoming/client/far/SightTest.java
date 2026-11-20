package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Haze according to Koschmider, a bright point in which the light remains at any angular size, flare and veil. */
class SightTest {
    private static final double PIXEL = Math.toRadians(70) / 1080;

    @Test
    void contrastFallsToEyeThresholdAtVisibilityRange() {
        assertEquals(Sight.THRESHOLD, Sight.transmittance(Sight.CLEAR, Sight.CLEAR), 1e-4);
        assertEquals(Sight.CLEAR, Sight.range(0, 0), 1e-6);
        assertEquals(Sight.RAIN, Sight.range(1, 0), 1e-6);
        assertEquals(Sight.THUNDER, Sight.range(1, 1), 1e-6);
        assertTrue(Sight.range(0.5f, 0) < Sight.CLEAR && Sight.range(0.5f, 0) > Sight.RAIN, "drizzle - between clear and rainy");
    }

    @Test
    void hazeThinsWithHeight() {
        // along the land by the sea - Koschmider by visibility range
        Sight.Air sea = Sight.Air.at(0, Sight.CLEAR);
        for (double d : new double[] {0, 100, 5000, Sight.CLEAR}) {
            assertEquals(Sight.transmittance(d, Sight.CLEAR), sea.transmittance(d, 0), 1e-12);
        }
        // cap 2 kt to the third minute: the middle at 5 km, the eye by the sea at 2,6 km from the epicenter - 5,6 km of air. Almost all the
        //  haze is in the lower kilometers: three quarters of the light cap is visible, and not a third, as through 5,6 km of haze near the ground
        double d = Math.hypot(2600, 5000);
        assertEquals(0.33, Sight.transmittance(d, Sight.CLEAR), 0.01);
        assertEquals(0.75, sea.transmittance(d, 5000), 0.03);
        // down - thicker, up - less often; there is less haze from the mountain than from the sea
        assertTrue(sea.transmittance(3000, 300) > sea.transmittance(3000, 0));
        Sight.Air hill = Sight.Air.at(500, Sight.CLEAR);
        assertTrue(hill.transmittance(3000, -300) < hill.transmittance(3000, 0));
        assertTrue(hill.transmittance(3000, 0) > sea.transmittance(3000, 0));
        // above all the haze there remains clean air: beyond 100 km up you can see, but not everything
        double space = Sight.Air.at(0, Sight.CLEAR).transmittance(1e5, 1e5);
        assertTrue(space > 0.6 && space < 0.85, "up through the atmosphere: " + space);
        // in the rain by the sea, all together - according to visibility range; drops and clouds do not thin out in height - the cap is barely visible in the rain,
        //  is not visible in a thunderstorm
        Sight.Air rain = Sight.Air.at(0, Sight.RAIN), storm = Sight.Air.at(0, Sight.THUNDER);
        assertEquals(Sight.KOSCHMIEDER / Sight.RAIN, rain.haze() + rain.clean() + rain.weather(), 1e-12);
        assertEquals(0, sea.weather(), 0);
        assertTrue(rain.transmittance(d, 5000) < 0.05, "cap in the rain: " + rain.transmittance(d, 5000));
        assertTrue(storm.transmittance(d, 5000) < 1e-4, "cap in a thunderstorm: " + storm.transmittance(d, 5000));
    }

    @Test
    void largeDimPointIsDrawnAsItIs() {
        double[] out = new double[2];
        Sight.point(5, 0.6, 1, 100, PIXEL, out);
        assertEquals(5, out[0], 1e-9);
        assertEquals(0.6, out[1], 1e-9);
    }

    @Test
    void tinyPointKeepsItsLight() {
        // shahed (2,5 m) for 6 km - a third of a pixel: a dot one and a half pixels, paler by the same amount, the light is the same
        double[] out = new double[2];
        double d = 6000, r = 1.25;
        Sight.point(r, 1, 1, d, PIXEL, out);
        assertEquals(0.75 * PIXEL * d, out[0], 1e-9);
        assertEquals((r / d) * (r / d), out[1] * (out[0] / d) * (out[0] / d), 1e-15, "the light of the point is preserved");
        assertTrue(out[1] < 0.1);
    }

    @Test
    void brightPointSpreadsIntoGlareInsteadOfExceedingWhite() {
        double[] out = new double[2];
        Sight.point(5, 40, 0.5, 3000, PIXEL, out);
        assertEquals(1, out[1], 1e-12);
        double r = 5 / 3000.0;
        assertEquals(40 * 0.5 * r * r, (out[0] / 3000) * (out[0] / 3000), 1e-12, "glare light - source light");
    }

    /** Sky light FarView.ambient: noon and midnight (getSkyDarken 0,2). */
    private static final double DAY = 1, NIGHT = 0.17;

    @Test
    void daylightFireballHasNoVeilButNightOneLightsDegrees() {
        // cruise missile ball (11 blocks, brightness 8 against white) for 1,5 km during the day and for 2 and 8 km at night
        double[] out = new double[5];
        double r = 11, b = 8;
        double t = Sight.transmittance(1500, Sight.CLEAR);
        Sight.light(r, Sight.adapted(b, DAY) * t, 1500, PIXEL, out);
        assertEquals(0, out[4], "no veil during the day");
        assertTrue(out[2] > 1, "during the day the ball is brighter than the sky: " + out[2]);
        assertTrue(out[3] / out[0] < 2.5, "glare during the day - no wider than two and a half cores: " + out[3] / out[0]);
        // the halo merges with the sky: during the day the ball remains an orange body, and not a white spot; at night the glare is in full force
        assertTrue(Sight.SCATTER * Sight.adapted(b, DAY) * t < 0.1, "during the day there is almost no halo");
        assertTrue(Sight.SCATTER * Sight.adapted(b, NIGHT) * t >= Sight.HALO, "at night the glare is in full force");
        for (double d : new double[]{2000, 8000}) {
            Sight.light(5, Sight.adapted(b, NIGHT) * Sight.transmittance(d, Sight.CLEAR), d, PIXEL, out);
            double deg = Math.toDegrees(out[4] / d);
            if (d == 2000) assertTrue(deg > 6 && deg <= Math.toDegrees(Sight.VEIL_MAX) + 1e-9, "shahed at night for 2 km - veil " + deg + "°");
            else assertTrue(deg > 1 && deg < 3, "shahed at night for 8 km - veil " + deg + "°");
        }
    }

    @Test
    void lightKeepsItsFluxBelowAPixel() {
        // rocket torch (0,38 block) behind 3 km during the day: the core is in 1,5 px, the light is the same as the real one
        double[] out = new double[5];
        double d = 3000, seen = 13;
        Sight.light(0.38, seen, d, PIXEL, out);
        assertEquals(0.75 * PIXEL * d, out[0], 1e-9);
        assertEquals(seen * 0.38 * 0.38, out[2] * out[0] * out[0], 1e-9, "brightness of the core × its area - source flux");
        assertTrue(out[2] < 1, "spark is paler than white");
    }

    @Test
    void eyeAdaptsToNight() {
        assertEquals(1, Sight.adapted(1, DAY), 1e-12);
        double night = Sight.adapted(1, NIGHT);
        assertTrue(night > 500 && night < 3000, "at night in " + night + " times");
    }

    /**
     * Self-luminous light on the screen ({@link Sight#onScreen}): brighter than white - at full strength and to white, dimmer - darker in
     *  linear light and in sRGB on the screen (body in 0,1 white - ~0,35 screen); no jumps.
     */
    @Test
    void selfLuminousOnScreen() {
        float[] white = Sight.onScreen(0xFF7401, Sight.WHITE, 1);
        assertEquals(1, white[2], 1e-3, "overexposure - white");
        float[] tenth = Sight.onScreen(0xFFFFFF, 0.1, 1);
        assertEquals(0.35, tenth[0], 0.01, "0,1 white on the screen - sRGB");
        float prev = 0;
        for (double l = 0.001; l < 100; l *= 1.05) {
            float g = Sight.onScreen(0xFF7401, l, 1)[1];
            assertTrue(g >= prev - 1e-6 && g - prev < 0.06, "smoothly: " + l);
            prev = g;
        }
    }

    /**
     * Sunlight through the air (18.11: evening dust lit by a nearly white sun at 19°): Beer–Lambert along the slant air column through
     * the clear-day air of {@link Sight#Air} — Rayleigh 0.093 and haze 0.22 vertical optical depth at 550 nm, λ^−4.08 and λ^−1.3.
     * Overhead it is the frame's white daylight; at column 3 (sun 19.3° up) red over blue is ×1.85 in absolute terms (×1.5 relative
     * to noon) and green is half as bright; one law at every elevation — redder and dimmer as the sun sinks, without a threshold.
     */
    @Test
    void sunReddensAlongTheAirMass() {
        double sin19 = Math.sin(Math.toRadians(19.3));
        assertEquals(1, Sight.column(0, 1, Sight.AIR_HEIGHT), 2e-3, "overhead");
        assertEquals(3, Sight.column(0, sin19, Sight.AIR_HEIGHT), 0.05, "19.3 degrees, clean air");
        assertEquals(3, Sight.column(0, sin19, Sight.HAZE_HEIGHT), 0.05, "19.3 degrees, haze");
        assertEquals(Math.sqrt(Math.PI * Sight.EARTH_R / (2 * Sight.AIR_HEIGHT)), Sight.column(0, 0, Sight.AIR_HEIGHT), 0.05, "horizon");
        assertEquals(0.093, Sight.verticalDepth(550) - (Sight.KOSCHMIEDER / Sight.CLEAR - Sight.RAYLEIGH) * Sight.HAZE_HEIGHT, 1e-3, "Rayleigh at 550 nm");
        assertEquals(0.22, (Sight.KOSCHMIEDER / Sight.CLEAR - Sight.RAYLEIGH) * Sight.HAZE_HEIGHT, 0.005, "clear-day haze at 550 nm");
        double[] noon = new double[3], low = new double[3];
        Sight.sunThroughAir(1, 0, noon);
        for (double v : noon) assertEquals(1, v, 1e-3, "white at noon");
        double sin3 = Math.sin(Math.toRadians(19.3));
        Sight.sunThroughAir(sin3, 0, low);
        double relative = low[0] / low[2], absolute = relative * Math.exp(Sight.verticalDepth(465) - Sight.verticalDepth(610));
        assertEquals(1.85, absolute, 0.1, "air mass 3: red over blue through the whole air");
        assertEquals(1.5, relative, 0.08, "air mass 3: red over blue relative to the noon sun");
        assertEquals(0.53, low[1], 0.04, "air mass 3: green");
        double prevRatio = 1, prevGreen = 1;
        for (double deg = 89; deg >= 1; deg -= 4) {
            Sight.sunThroughAir(Math.sin(Math.toRadians(deg)), 0, low);
            double ratio = low[0] / low[2];
            assertTrue(ratio >= prevRatio && low[1] <= prevGreen, deg + " degrees: redder and dimmer as it sinks");
            prevRatio = ratio;
            prevGreen = low[1];
        }
    }

    /**
     * The Chapman column ({@link Sight#column}) is the air a sunbeam really crosses over a round earth: within 6 % of the column
     * integrated along the beam for clean air and haze, from the ground to 20 km, from overhead to below the point's horizon; in
     * the earth's shadow (lowest point of the beam under the ground) there is no beam.
     */
    @Test
    void columnIsTheAirAlongTheBeam() {
        for (double scale : new double[]{Sight.AIR_HEIGHT, Sight.HAZE_HEIGHT}) {
            for (double z : new double[]{0, 3000, 10_000, 20_000}) {
                for (double deg : new double[]{90, 40, 10, 3, 1, 0.5, 0, -1, -2, -3, -4}) {
                    double s = Math.sin(Math.toRadians(deg)), exact = integrated(z, s, scale), law = Sight.column(z, s, scale);
                    if (Double.isInfinite(exact)) {
                        assertEquals(Sight.SHADOWED, law, 0, "earth shadow: z " + z + ", " + deg + "°");
                        assertFalse(Sight.sunAbove(s, z), "no sun in the earth shadow: z " + z + ", " + deg + "°");
                    } else {
                        assertEquals(exact, law, 0.06 * exact, "scale " + scale + ", z " + z + ", " + deg + "°");
                    }
                }
            }
        }
    }

    /** Air column along a ray from height z toward elevation sine s, relative to the vertical column above the ground (midpoint rule). */
    private static double integrated(double z, double s, double scale) {
        double r0 = Sight.EARTH_R + z, sum = 0, length = 2.5e6, ds = 25;
        for (double t = ds / 2; t < length; t += ds) {
            double r = Math.sqrt(r0 * r0 + t * t + 2 * r0 * t * s);
            if (r < Sight.EARTH_R) return Double.POSITIVE_INFINITY;
            sum += Math.exp(-(r - Sight.EARTH_R) / scale) * ds;
        }
        return sum / scale;
    }

    /**
     * Mushroom flat blue-grey at dusk (19.11, logo frames at dayTime 12600–12900): the cap took the sun of the ground — gone with the
     * eye's sunset — and only vanilla's blue sky lit it. A cap kilometres up stands above the haze and keeps the sun: at a 3° sun
     * the ground gets 2 % of noon green, a cap 10 km up over 60 %, golden (red over blue); at −2° (the ground long in the earth's
     * shadow) a cap 15 km up still has a red beam, while the shadow's top √(2z/R) sits at 2.5° for 6 km and 3.9° for 15 km.
     * One law over the whole dusk: no step between neighbouring ticks of the world clock at any height.
     */
    @Test
    void capAloftKeepsTheSunAfterGroundSunset() {
        double[] ground = new double[3], cap = new double[3];
        double sin3 = Math.sin(Math.toRadians(3));
        Sight.sunThroughAir(sin3, 0, ground);
        Sight.sunThroughAir(sin3, 10_000, cap);
        assertTrue(ground[1] < 0.03, "ground at 3°: " + ground[1]);
        assertTrue(cap[1] > 0.6 && cap[0] > cap[1] && cap[1] > cap[2] && cap[0] > 1.5 * cap[2], "cap 10 km at 3°: golden " + java.util.Arrays.toString(cap));
        double below2 = Math.sin(Math.toRadians(-2));
        Sight.sunThroughAir(below2, 0, ground);
        Sight.sunThroughAir(below2, 15_000, cap);
        assertEquals(0, ground[0], 0, "ground in the earth's shadow at −2°");
        assertTrue(cap[0] > 0.2 && cap[0] > 3 * cap[2], "cap 15 km at −2°: red beam " + java.util.Arrays.toString(cap));
        for (double[] h : new double[][]{{6000, 2.5}, {15_000, 3.9}}) {
            assertTrue(Sight.sunAbove(Math.sin(Math.toRadians(-h[1] + 0.05)), h[0]), h[0] + " m sees the sun just above its dip");
            assertFalse(Sight.sunAbove(Math.sin(Math.toRadians(-h[1] - 0.05)), h[0]), h[0] + " m is in the shadow just below its dip");
        }
        assertTrue(Sight.sunAbove(Math.sin(Math.toRadians(-6.3)), Sight.SUN_TOP), "the top of the sunlit air");
        // vanilla's sun over the dusk, tick by tick (ClientLevel.getTimeOfDay): no jump between neighbouring ticks at any height
        double[] before = new double[3], now = new double[3];
        for (double z : new double[]{0, 500, 3000, 6000, 10_000, 15_000, 25_000}) {
            Sight.sunThroughAir(vanillaSun(11_500), z, before);
            for (int tick = 11_501; tick <= 13_500; tick++) {
                Sight.sunThroughAir(vanillaSun(tick), z, now);
                for (int c = 0; c < 3; c++) assertEquals(before[c], now[c], 0.01, "z " + z + ", tick " + tick + ", channel " + c);
                System.arraycopy(now, 0, before, 0, 3);
            }
        }
    }

    /** Sine of vanilla's sun elevation at day time {@code tick} ({@code DimensionType.timeOfDay}, sun direction y = cos 2πt). */
    private static double vanillaSun(int tick) {
        double f = tick / 24_000.0 - 0.25;
        f -= Math.floor(f);
        double t = (f * 2 + (0.5 - Math.cos(f * Math.PI) / 2)) / 3;
        return Math.cos(t * 2 * Math.PI);
    }

    /**
     * The shader's copy of the beam's air ({@code fx_pall.glsl}: {@code sunColumn}, constants of earth and air, the per-channel depths
     * {@code SUN_CLEAN}, {@code SUN_HAZE}) is this law: changing it in one place breaks this test.
     */
    @Test
    void shaderSunIsThisLaw() throws java.io.IOException {
        String fx;
        try (java.io.InputStream in = SightTest.class.getResourceAsStream("/assets/incoming/shaders/include/fx_pall.glsl")) {
            fx = new String(java.util.Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("//[^\n]*", "");
        }
        assertEquals(Sight.EARTH_R, constant(fx, "SUN_EARTH_R"), 0);
        assertEquals(Sight.AIR_HEIGHT, constant(fx, "SUN_AIR_H"), 0);
        assertEquals(Sight.HAZE_HEIGHT, constant(fx, "SUN_HAZE_H"), 0);
        assertEquals(Sight.SHADOWED, constant(fx, "SUN_SHADOWED"), 0);
        double[] clean = channels(fx, "SUN_CLEAN"), haze = channels(fx, "SUN_HAZE");
        for (int c = 0; c < 3; c++) {
            assertEquals(Sight.cleanDepth(Sight.CHANNEL_NM[c]), clean[c], 1e-6, "clean air, channel " + c);
            assertEquals(Sight.hazeDepth(Sight.CHANNEL_NM[c]), haze[c], 1e-6, "haze, channel " + c);
        }
        // every lit point takes the sun of its own altitude above sea level (eye-relative y plus the eye's altitude), the cap as the pall;
        // the cap's sunward depths do not hang on its beam (they bound its own emission's escape too)
        String flat = fx.replaceAll("\\s+", " ");
        assertTrue(flat.contains("float pallSunHeight(vec3 p) { return p.y + PallSunTop.w; }"), "sun height is the point's altitude");
        assertEquals(2, flat.split("pallSunBeam\\(pallSunHeight\\(p\\)\\)", -1).length - 1, "cap and pall take the beam at their altitude");
        assertTrue(flat.contains("if (pallSunUp()) { vec3 beam = pallSunBeam(pallSunHeight(p)); float toSun = mushSunTau("),
                "the cap's sunward depth is taken whenever the frame has sun");
        ua.zentix.incoming.client.nuclear.GlslFunction column = ua.zentix.incoming.client.nuclear.GlslFunction.parse(fx, "sunColumn");
        for (double h : new double[]{Sight.AIR_HEIGHT, Sight.HAZE_HEIGHT}) {
            for (double z = -50; z <= 40_000; z += 250) {
                for (double s = -0.15; s <= 1.0001; s += 0.0025) {
                    double law = Sight.column(z, s, h), glsl = column.call(java.util.Map.of("z", z, "s", s, "h", h));
                    assertEquals(law, glsl, 1e-6 * Math.max(1, law), "z " + z + ", s " + s + ", h " + h);
                }
            }
        }
    }

    private static double constant(String source, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("const\\s+float\\s+" + name + "\\s*=\\s*([^;]+);").matcher(source);
        assertTrue(m.find(), "no const float " + name);
        return Double.parseDouble(m.group(1).strip());
    }

    private static double[] channels(String source, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("const\\s+vec3\\s+" + name + "\\s*=\\s*vec3\\(([^)]*)\\)").matcher(source);
        assertTrue(m.find(), "no const vec3 " + name);
        String[] p = m.group(1).split(",");
        return new double[]{Double.parseDouble(p[0].strip()), Double.parseDouble(p[1].strip()), Double.parseDouble(p[2].strip())};
    }
}
