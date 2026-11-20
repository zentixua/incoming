package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shader pack's sky behind distant nuclear matter ({@link SkyBand}, {@code fx_sky_band}, {@code pallAirBand}): the
 * pass is replayed in Java over a synthetic pack frame (every direction on screen) and the band is read where a cap stands.
 */
class SkyBandTest {
    /** A golden dusk sky: warm at the horizon toward the sun (+x), deep blue-violet at the zenith (display values). */
    private static double[] dusk(double[] d) {
        double toSun = Math.max(d[0], 0), up = Math.max(d[1], 0);
        return new double[]{0.85 - 0.55 * up + 0.1 * toSun, 0.55 - 0.3 * up, 0.25 + 0.15 * up};
    }

    /** One {@code fx_sky_band} pass over a frame whose colour along a direction is {@code frame}, sky where {@code sky}. */
    private static float[] pass(Function<double[], double[]> frame, Predicate<double[]> sky, double[] sun, double ju, double jv) {
        float[] out = new float[SkyBand.AZ * SkyBand.EL * 4];
        double[] d = new double[3];
        int n = SkyBand.SAMPLES;
        for (int e = 0; e < SkyBand.EL; e++) {
            for (int a = 0; a < SkyBand.AZ; a++) {
                double[] sum = new double[3];
                int taken = 0;
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        SkyBand.direction(a, e, (i + ju) / n, (j + jv) / n, d);
                        if (!SkyBand.takes(d[0], d[1], d[2], sun[0], sun[1], sun[2], SkyBand.cosExclude()) || !sky.test(d)) continue;
                        double[] c = frame.apply(d);
                        for (int k = 0; k < 3; k++) sum[k] += c[k];
                        taken++;
                    }
                }
                int at = (e * SkyBand.AZ + a) * 4;
                for (int k = 0; k < 3; k++) out[at + k] = taken > 0 ? (float) (sum[k] / taken) : 0;
                out[at + 3] = (float) taken / (n * n);
            }
        }
        return out;
    }

    private static double[] unit(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    private static double angle(double[] a, double[] b) {
        return Math.toDegrees(Math.acos(Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2])));
    }

    /**
     * Lead decision 20.11 (B): a cap with the sun directly behind it never takes the sun's colour. The pack draws a white sun
     * disc with glare out to 8° (brighter than any sky); the cap's airlight there is the sky beside the sun, within the dusk
     * sky's own range, at sun elevations from 1° to 20°, and the same for the moon opposite.
     */
    @Test
    void capWithTheSunBehindItNeverTakesTheSunsColour() {
        for (double rotation : new double[]{0, 30, -40}) for (double elevation : new double[]{1, 5, 12, 20}) {
            // the vanilla sun at this elevation in the west, as the pack draws it on its turned path
            double a = Math.toRadians(90 - elevation), sun[] = new double[3];
            SkyBand.packSun(-Math.sin(a), Math.cos(a), rotation, sun);
            for (double[] body : new double[][]{sun, {-sun[0], -sun[1], -sun[2]}}) {
                Function<double[], double[]> frame = d -> angle(d, body) < 8 ? new double[]{1, 1, 1} : dusk(d);
                String at = "rotation " + rotation + "°, sun " + elevation + "°";
                SkyBand band = new SkyBand();
                for (int f = 0; f < 30; f++) band.observe(pass(frame, d -> true, sun, (f * 0.754877) % 1, (f * 0.569840) % 1), f / 60.0);
                float[] drawn = new float[SkyBand.AZ * SkyBand.EL * 4];
                assertTrue(band.fill(drawn, 0.5));
                double[] air = new double[3];
                SkyBand.read(drawn, body[0], Math.max(body[1], 0), body[2], air);
                // the brightest the sky itself gets
                assertTrue(air[0] <= 0.95 + 1e-6 && air[1] <= 0.55 + 1e-6 && air[2] <= 0.40 + 1e-6,
                        at + ": airlight in front of the sun " + air[0] + " " + air[1] + " " + air[2]);
                assertTrue(air[2] < 0.5 * air[0], at + ": not white, the dusk sky's own warmth: " + air[0] + " " + air[2]);
            }
        }
    }

    /**
     * Only sky pixels count: terrain on the horizon (dark, below 4°) and a near wall (black, 30° wide) never enter the band;
     * the cap's airlight is the pack's warm sky there, not vanilla's blue, and not what stands beside the cap.
     */
    @Test
    void onlyTheSkyEntersTheBand() {
        double[] sun = unit(-1, 0.02, 0.3);
        Predicate<double[]> sky = d -> d[1] > Math.sin(Math.toRadians(4)) && !(Math.abs(Math.atan2(d[2], d[0])) < Math.toRadians(15) && d[1] < 0.5);
        Function<double[], double[]> frame = d -> sky.test(d) ? dusk(d) : new double[]{0, 0, 0};
        SkyBand band = new SkyBand();
        for (int f = 0; f < 30; f++) band.observe(pass(frame, sky, sun, (f * 0.754877) % 1, (f * 0.569840) % 1), f / 60.0);
        float[] drawn = new float[SkyBand.AZ * SkyBand.EL * 4];
        assertTrue(band.fill(drawn, 0.5));
        for (double az = 0; az < 360; az += 7.5) {
            for (double el = 0; el <= 90; el += 5) {
                double a = Math.toRadians(az), e = Math.toRadians(el);
                double[] d = {Math.cos(e) * Math.cos(a), Math.sin(e), Math.cos(e) * Math.sin(a)}, air = new double[3];
                SkyBand.read(drawn, d[0], d[1], d[2], air);
                // the dusk sky's range over the whole hemisphere
                assertTrue(air[0] >= 0.30 - 1e-6 && air[1] >= 0.25 - 1e-6, az + "°, " + el + "°: something dark entered " + air[0] + " " + air[1]);
            }
        }
    }

    /** Without a pack (nothing observed) or with every bin stale the band is off: the airlight is vanilla's sky. */
    @Test
    void noPackNoBand() {
        SkyBand band = new SkyBand();
        float[] drawn = new float[SkyBand.AZ * SkyBand.EL * 4];
        assertFalse(band.fill(drawn, 0));
        double[] sun = {1, 0, 0};
        band.observe(pass(SkyBandTest::dusk, d -> true, sun, 0.5, 0.5), 0);
        assertTrue(band.fill(drawn, SkyBand.STALE));
        assertFalse(band.fill(drawn, SkyBand.STALE + 0.1), "a band nobody has seen for " + SkyBand.STALE + " s is not the sky now");
        band.reset();
        assertFalse(band.fill(drawn, 0));
    }

    /** A bin seen once fills its hole-free neighbours; bins move toward a changed sky smoothly, without a step per pass. */
    @Test
    void holesFillAndTheSkyChangesSmoothly() {
        SkyBand band = new SkyBand();
        float[] one = new float[SkyBand.AZ * SkyBand.EL * 4];
        int seen = 3 * SkyBand.AZ + 5;
        one[seen * 4] = 0.8f;
        one[seen * 4 + 1] = 0.5f;
        one[seen * 4 + 2] = 0.2f;
        one[seen * 4 + 3] = 1;
        band.observe(one, 0);
        float[] drawn = new float[SkyBand.AZ * SkyBand.EL * 4];
        assertTrue(band.fill(drawn, 0));
        for (int i = 0; i < SkyBand.AZ * SkyBand.EL; i++) {
            assertArrayEquals(new float[]{0.8f, 0.5f, 0.2f}, new float[]{drawn[i * 4], drawn[i * 4 + 1], drawn[i * 4 + 2]}, 1e-6f, "bin " + i);
        }
        float[] next = one.clone();
        next[seen * 4] = 0.2f;
        double last = 0.8;
        for (int f = 1; f <= 120; f++) {
            band.observe(next, f / 60.0);
            band.fill(drawn, f / 60.0);
            double now = drawn[seen * 4];
            assertTrue(now <= last + 1e-9 && last - now < 0.6 / 60 / SkyBand.TAU * 1.05, "frame " + f + ": " + last + " → " + now);
            last = now;
        }
        assertEquals(0.2 + 0.6 * Math.exp(-2 / SkyBand.TAU), last, 1e-3, "two seconds: e^(−t/τ)");
    }

    /**
     * The pack's sun path (Iris {@code CelestialUniforms}: Ry(−90°)·Rz(rotation)·Rx(sky angle) on the zenith) recomputed
     * with explicit matrices: {@link SkyBand#packSun} is that direction for any rotation and time of day, and with no
     * rotation it is vanilla's sun (−sin a, cos a, 0). A 30° turn moves the noon sun 30° off vanilla's (far outside the cone),
     * a sun at the horizon barely (the turn is about the east–west axis).
     */
    @Test
    void packSunFollowsTheTurnedPath() {
        for (double rotation : new double[]{0, 30, -40, 75}) {
            for (double sky = 0; sky < 1; sky += 0.07) {
                double a = sky * 2 * Math.PI, r = Math.toRadians(rotation);
                // Rx(a)·(0, 1, 0)
                double x = 0, y = Math.cos(a), z = Math.sin(a);
                // Rz(r)
                double x2 = x * Math.cos(r) - y * Math.sin(r), y2 = x * Math.sin(r) + y * Math.cos(r), z2 = z;
                // Ry(−90°): x' = x cos θ + z sin θ, z' = −x sin θ + z cos θ, θ = −90°
                double[] want = {-z2, y2, x2}, got = new double[3];
                SkyBand.packSun(-Math.sin(a), Math.cos(a), rotation, got);
                assertArrayEquals(want, got, 1e-12, "rotation " + rotation + ", sky angle " + sky);
            }
        }
        double a = Math.toRadians(10), vanilla[] = {-Math.sin(a), Math.cos(a), 0}, turned[] = new double[3];
        SkyBand.packSun(vanilla[0], vanilla[1], 0, turned);
        assertArrayEquals(vanilla, turned, 1e-12);
        SkyBand.packSun(vanilla[0], vanilla[1], 30, turned);
        assertEquals(29.6, angle(vanilla, turned), 0.2, "turned sun off vanilla's");
    }

    /** Straight up and down the azimuth is undefined: the read takes azimuth 0 (as the shader) and stays finite. */
    @Test
    void verticalRaysReadAFiniteBand() {
        float[] band = new float[SkyBand.AZ * SkyBand.EL * 4];
        for (int i = 0; i < band.length; i++) band[i] = (i % 7) * 0.1f;
        double[] up = new double[3], down = new double[3], near = new double[3];
        SkyBand.read(band, 0, 1, 0, up);
        SkyBand.read(band, 0, -1, 0, down);
        SkyBand.read(band, 1e-12, 1, 0, near);
        for (int c = 0; c < 3; c++) {
            assertTrue(Double.isFinite(up[c]) && Double.isFinite(down[c]), "channel " + c);
            assertEquals(near[c], up[c], 1e-9, "zenith: azimuth 0");
        }
    }

    /**
     * Review #686 r4: a pack switched by Iris's Apply (no shader reload, same world) — the old pack's sky leaves a bin seen
     * by the new one within a second, and a bin only grazed by a sliver of sky (under 1/8 of its samples) is not kept alive
     * by it: it expires after {@link SkyBand#STALE}.
     */
    @Test
    void aSwitchedPackTakesOverWithinASecond() {
        SkyBand band = new SkyBand();
        float[] old = new float[SkyBand.AZ * SkyBand.EL * 4], next = new float[SkyBand.AZ * SkyBand.EL * 4], drawn = new float[old.length];
        for (int i = 0; i < SkyBand.AZ * SkyBand.EL; i++) {
            old[i * 4] = 1;
            old[i * 4 + 3] = 1;
            next[i * 4 + 3] = i == 0 ? 1f / 64 : 1;
        }
        for (int f = 0; f <= 300; f++) band.observe(old, f / 60.0);
        for (int f = 1; f <= 60; f++) band.observe(next, 5 + f / 60.0);
        assertTrue(band.fill(drawn, 6));
        assertTrue(drawn[4] < Math.exp(-1 / SkyBand.TAU) + 0.01, "a bin seen by the new pack: " + drawn[4] + " of the old sky left");
        for (int f = 61; f <= 60 * (1 + SkyBand.STALE); f++) band.observe(next, 5 + f / 60.0);
        assertTrue(band.fill(drawn, 5 + 1 + SkyBand.STALE));
        assertTrue(drawn[3] < 1, "the sliver bin is not fresh: filled from its row");
        assertTrue(drawn[0] < 0.2, "the sliver bin takes its neighbours' new sky, not the old pack's: " + drawn[0]);
    }

    /** The band is read the same way it is laid out: a uniform per-bin colour comes back exactly at each bin centre. */
    @Test
    void readFindsEachBinAtItsCentre() {
        float[] band = new float[SkyBand.AZ * SkyBand.EL * 4];
        for (int i = 0; i < SkyBand.AZ * SkyBand.EL; i++) band[i * 4] = i;
        double[] d = new double[3], out = new double[3];
        for (int e = 0; e < SkyBand.EL; e++) {
            for (int a = 0; a < SkyBand.AZ; a++) {
                SkyBand.direction(a, e, 0.5, 0.5, d);
                SkyBand.read(band, d[0], d[1], d[2], out);
                assertEquals(e * SkyBand.AZ + a, out[0], 1e-6, "bin " + a + ", " + e);
            }
        }
    }

    private static String text(String path) throws IOException {
        try (InputStream in = SkyBandTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String block(String glsl, String from, String to) {
        int a = glsl.indexOf(from), b = glsl.indexOf(to, a);
        assertTrue(a >= 0 && b > a, from);
        return glsl.substring(a, b).replaceAll("//[^\\n]*", "").replaceAll("\\s+", " ").trim();
    }

    /** The shaders are what the Java copies model: bins, cone test, layout and read. Editing one requires the other. */
    @Test
    void shadersMatchTheJavaCopy() throws IOException {
        String pass = text("/assets/incoming/shaders/core/fx_sky_band.fsh"), pall = text("/assets/incoming/shaders/include/fx_pall.glsl");
        assertTrue(pass.contains("const int BAND_AZ = " + SkyBand.AZ + ", BAND_EL = " + SkyBand.EL + ", BAND_SAMPLES = " + SkyBand.SAMPLES + ", BAND_EDGE = 4;"));
        assertTrue(pall.contains("const int AIR_BAND_AZ = " + SkyBand.AZ + ", AIR_BAND_EL = " + SkyBand.EL + ";"));
        assertEquals("bool skyBandTakes(vec3 dir, vec3 sun, float cosOut) { return abs(dot(dir, sun)) < cosOut; }",
                block(pass, "bool skyBandTakes", "// Copy — SkyBand.direction"));
        assertEquals("vec3 bandDir(ivec2 bin, vec2 at) { float az = (float(bin.x) + at.x) / float(BAND_AZ) * 6.28318530718, "
                        + "el = (float(bin.y) + at.y) / float(BAND_EL) * 1.57079632679; return vec3(cos(el) * cos(az), sin(el), cos(el) * sin(az)); }",
                block(pass, "vec3 bandDir", "void main()"));
        assertEquals("vec3 pallAirBand(vec3 dir) { float az = 0.0; if (abs(dir.x) + abs(dir.z) > 1e-9 * length(dir)) az = atan(dir.z, dir.x); "
                        + "float u = az / 6.28318530718 * float(AIR_BAND_AZ) - 0.5; "
                        + "float v = asin(clamp(dir.y / max(length(dir), 1e-9), 0.0, 1.0)) / 1.57079632679 * float(AIR_BAND_EL) - 0.5; "
                        + "v = clamp(v, 0.0, float(AIR_BAND_EL - 1)); float u0 = floor(u), v0 = floor(v), fu = u - u0, fv = v - v0; "
                        + "int a0 = int(mod(u0, float(AIR_BAND_AZ))), a1 = int(mod(u0 + 1.0, float(AIR_BAND_AZ))); "
                        + "int e0 = int(v0), e1 = min(e0 + 1, AIR_BAND_EL - 1); "
                        + "vec3 lo = mix(PallAirBand[e0 * AIR_BAND_AZ + a0].rgb, PallAirBand[e0 * AIR_BAND_AZ + a1].rgb, fu); "
                        + "vec3 hi = mix(PallAirBand[e1 * AIR_BAND_AZ + a0].rgb, PallAirBand[e1 * AIR_BAND_AZ + a1].rgb, fu); "
                        + "return mix(lo, hi, fv); }",
                block(pall, "vec3 pallAirBand", "// Airlight along view direction dir"));
        // the pass takes only sky pixels (SceneDepth: sky 1e9) with sky around them, and nothing behind the eye
        assertTrue(pass.contains("return texelFetch(Sampler1, clamp(p, ivec2(0), size - 1), 0).r >= 1e8;"));
        assertEquals("bool skyAround(ivec2 at, ivec2 size) { for (int dy = -2; dy <= 2; dy++) { for (int dx = -2; dx <= 2; dx++) { "
                        + "if (!skyAt(at + ivec2(dx, dy), size)) return false; } } "
                        + "return skyAt(at + ivec2(BAND_EDGE, 0), size) && skyAt(at - ivec2(BAND_EDGE, 0), size) "
                        + "&& skyAt(at + ivec2(0, BAND_EDGE), size) && skyAt(at - ivec2(0, BAND_EDGE), size); }",
                block(pass, "bool skyAround", "// Copy — SkyBand.takes"));
        assertTrue(pass.contains("if (!skyAround(at, depth)) continue;"));
        assertTrue(pass.contains("if (clip.w <= 0.0) continue;"));
    }
}
