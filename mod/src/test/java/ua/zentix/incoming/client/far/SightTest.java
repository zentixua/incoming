package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
