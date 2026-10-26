package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Light in the pall ({@link PallLight}, in the shader - {@code pallDiffuse}): two streams save energy, the thick layer has its own
 *  color, the thin one glows along the thickness and is not a white wall, the thick dust below is twilight, the shadow of the pall on the ground from the thin one is 1;
 *  linear light and screen.
 */
class PallLightTest {
    private static final double DUST = PallLight.truncated(PallLight.DUST_G), MIST = PallLight.truncated(PallLight.MIST_G);
    /** Noon: sun overhead and sky (light of frame {@code NukePall.light}, green channel: sky 0,64 on screen). */
    private static final double SKY = PallLight.SKY * PallLight.linear(0.64), SUN = 1 - SKY;

    /** The upward flow at the top of the layer, divided by π: the force beam 1 under the cosine mu and the sky top. */
    private static double up(double w, double g, double tc, double ground, double mu, double beam, double top) {
        return PallLight.diffuse(w, g, 0, tc, ground, mu, beam, 1, 0, top, 0, 2.0 / 3);
    }

    /** Downward flux at the bottom of the layer (diffuse) divided by π. */
    private static double down(double w, double g, double tc, double ground, double mu, double beam, double top) {
        return PallLight.diffuse(w, g, tc, tc, ground, mu, beam, 1, 0, top, 0, -2.0 / 3);
    }

    /** Without absorption (ω' = 1) and without ground, all light from the beam and sky comes out of the layer up or down. */
    @Test
    void conservativeLayerKeepsAllLight() {
        for (double g : new double[]{DUST, MIST}) {
            for (double tc : new double[]{0.01, 0.3, 1, 4, 16, 40}) {
                for (double mu : new double[]{0.12, 0.4, 0.8, 1}) {
                    double out = up(1, g, tc, 0, mu, 1, 0) + down(1, g, tc, 0, mu, 1, 0) + mu * Math.exp(-tc / mu);
                    assertEquals(mu, out, 1e-3 * mu, "beam: g = " + g + ", τc = " + tc + ", μ = " + mu);
                }
                double out = up(1, g, tc, 0, 1, 0, 1) + down(1, g, tc, 0, 1, 0, 1);
                assertEquals(1, out, 1e-3, "sky: g = " + g + ", τc = " + tc);
            }
        }
    }

    /** Absorbing dust loses light, and the thicker the layer, the more. */
    @Test
    void dustAbsorbsMoreTheThickerItIs() {
        double w = PallLight.albedo(PallLight.linear(0.53), DUST), last = 1;
        for (double tc : new double[]{0.1, 0.5, 2, 6, 20}) {
            double out = up(w, DUST, tc, 0, 1, 1, 0) + down(w, DUST, tc, 0, 1, 1, 0) + Math.exp(-tc);
            assertTrue(out < last, "τc = " + tc + ": " + out + " no less " + last);
            last = out;
        }
        assertTrue(last < 0.8, "thick dust absorbs little: it comes out " + last);
    }

    /** A thick layer in the diffused light of the sky reflects its color - the color of a wall of dust, smoke and splashes (the same on the screen). */
    @Test
    void thickLayerHasItsColour() {
        for (double r : new double[]{0.24, 0.3, 0.45, 0.53, 0.6, 0.9, 0.93}) {
            double g = r > 0.8 ? MIST : DUST, w = PallLight.albedo(PallLight.linear(r), g);
            double tc = 60 / PallLight.k(w, g);
            assertEquals(r, PallLight.display(up(w, g, tc, 0.2, 1, 0, 1)), 1e-3, "color " + r);
        }
    }

    /**
     * The reflection that the wall of dust on the screen adds to the light of the sky ({@link PallLight#lift}): a thick layer in the light of the sky base and
     *  this reflection on the screen is exactly the color × (base + x).
     */
    @Test
    void liftedLightShowsAsOnTheWall() {
        for (double r : new double[]{0.45, 0.6, 0.918}) {
            for (double base : new double[]{0.12, 0.17, 0.5, 1}) {
                for (double x : new double[]{0, 0.05, 0.3, 0.8}) {
                    double lit = PallLight.linear(r) * (PallLight.linear(base) + PallLight.lift(base, x));
                    assertEquals(r * (base + x), PallLight.display(lit), 1e-9, "color " + r + ", sky " + base + ", reflection " + x);
                }
            }
            assertEquals(r, PallLight.display(PallLight.linear(r)), 1e-12);
        }
    }

    /**
     * A beam that goes out in the same way as scattered light (kμ ≈ 1; for smoke k > 1 - the sun is around noon): the light in the layer is
     *  a solution to the equation, not a formula with a clamped denominator. Shifted beam ({@link PallLight#beamMu}) against the exact
     *  formula with a real cosine (at kμ = 1 its denominator is up to 10⁻⁴, in double precision it is still accurate): in the
     *  layer, up and down, - not further 0,01 to the power beam 1. The clamped denominator there gave an upward flow ×3,8 and a zero down
     *  near the floor.
     */
    @Test
    void beamThatFadesLikeDiffuseLightStillSolvesTheSlab() {
        for (double w : new double[]{0.55, 0.52, 0.48, 0.4368}) {
            double g = 0.375, k = PallLight.k(w, g);
            assertTrue(k > 1, "at smoke k > 1: " + k);
            for (double mu = 0.85; mu <= 1; mu += 0.0005) {
                if (Math.abs(k * k * mu * mu - 1) < 1e-4) continue;
                for (double tc : new double[]{1, 6, 10}) {
                    for (double ground : new double[]{0.06, 0.2}) {
                        for (double share : new double[]{0, 0.5, 1}) {
                            for (double c : new double[]{-2.0 / 3, 0.3, 2.0 / 3}) {
                                double tau = share * tc;
                                double got = PallLight.diffuse(w, g, tau, tc, ground, mu, 1, 1, 0, 0, 0, c);
                                assertEquals(exactBeam(w, g, tau, tc, ground, mu, c), got, 0.01,
                                        "ω' " + w + ", μ " + mu + ", τc " + tc + ", τ " + tau + ", earth " + ground + ", c " + c);
                            }
                        }
                    }
                }
            }
        }
    }

    /** The light of one beam of force 1 in a layer is a particular solution at a real cosine, without shift (for kμ ≠ 1 it is exact). */
    private static double exactBeam(double w, double g, double tau, double tc, double ground, double mu, double c) {
        double q = 1 - w * g, k = PallLight.k(w, g), p = 2.0 / 3 * k / q, e = Math.exp(-k * tc);
        double a = 0.75 * w * mu * mu * (1 + g * (1 - w)) / (k * k * mu * mu - 1);
        double b = 0.75 * w * mu - 3 * mu * (1 - w) * a;
        double e1 = Math.exp(-tc / mu);
        double r1 = -a + 2.0 / 3 * b;
        double r2 = ground * mu * e1 - ((1 - ground) * a + 2.0 / 3 * (1 + ground) * b) * e1;
        double m11 = 1 + p, m12 = e * (1 - p), m21 = e * (1 - ground - (1 + ground) * p), m22 = 1 - ground + (1 + ground) * p;
        double det = m11 * m22 - m12 * m21;
        double c1 = (r1 * m22 - m12 * r2) / det, c2 = (m11 * r2 - m21 * r1) / det;
        double x = Math.exp(-k * tau), y = Math.exp(-k * (tc - tau)), t1 = Math.exp(-tau / mu);
        return c1 * x + c2 * y + a * t1 + c * (k / q * (c2 * y - c1 * x) + b * t1);
    }

    /**
     * Light of fire ({@link PallLight#flame}): until the reflection on the screen reaches the color of the flame - the same as {@link PallLight#lift},
     * , then - the radiation of the flame itself, in each channel no brighter than it.
     */
    @Test
    void fireLightIsNoBrighterThanTheFlames() {
        double[] fire = {1.0, 0.541, 0.18};
        for (double base : new double[]{0.12, 0.17, 0.5, 1}) {
            for (double x = 0; x <= 3; x += 0.01) {
                for (double c : fire) {
                    double light = PallLight.flame(base, x * c, c);
                    assertTrue(light <= PallLight.linear(c) + 1e-12, "sky " + base + ", reflection " + x + ": brighter than flame " + c);
                    if (PallLight.lift(base, x * c) <= PallLight.linear(c)) assertEquals(PallLight.lift(base, x * c), light, 1e-12);
                }
            }
        }
    }

    /**
     * Thick dust above the fires at night (pall near the ground a minute after the explosion, as in the scenario {@code pall-low}): light from below
     * —flame under all the dust (the reflection of the wall above the fire zone on the screen is already the color of the fire). The dust near the floor on the screen is no higher than
     * 0,85 (shoulder fx_pall: shoulder there still leaves more than half the difference), and the thickness of the billows between it and the fire (one
     *  unit) darkens it noticeably: the billows are visible. The light turned through the color of dust (lift without limit) would be three times brighter than the fire: near the floor the dust is brighter than 1 and in
     *  the shadows of the billows are the same, the shoulder reduced everything to an even orange.
     */
    @Test
    void dustOverFiresKeepsItsShading() {
        double[] dust = {0.6, 0.53, 0.45}, fire = {1.0, 0.541, 0.18};
        double base = 0.17, glow = CloudPuffs.glowOnSmoke((float) base), sky = PallLight.linear(NukePall.nightSky((float) base, 1)), tc = 6;
        for (int c = 0; c < 3; c++) {
            double w = PallLight.albedo(PallLight.linear(dust[c]), DUST), x = fire[c] * 0.95 * glow / dust[c];
            double bounded = PallLight.flame(base, x, fire[c]), lifted = PallLight.lift(base, x);
            double floor = PallLight.display(w * PallLight.diffuse(w, DUST, tc, tc, PallLight.LAND, 1, 0, 1, 0, sky, bounded, 0));
            double shaded = PallLight.display(w * PallLight.diffuse(w, DUST, tc - 1, tc, PallLight.LAND, 1, 0, 1, 0, sky, bounded, 0));
            assertTrue(floor < 0.85, "channel " + c + ": dust near the floor on the screen " + floor);
            assertTrue(shaded < 0.85 * floor, "channel " + c + ": shadow billows " + shaded + " against " + floor);
            if (c == 0) {
                double old = PallLight.display(w * PallLight.diffuse(w, DUST, tc - 1, tc, PallLight.LAND, 1, 0, 1, 0, sky, lifted, 0));
                assertTrue(old > 1, "without limit the dust in the shadows of billows on the screen " + old);
            }
        }
    }

    /**
     * The light that a layer of water dust over the sea at noon sends upward (the eye looks at it from above): according to ∫ J·e^(−τ) dτ
     *  scattered to the eye - grows with the thickness of the layer, the thin one glows barely (in terms of thickness), the thick one - no brighter than its color: the fog
     *  does not become a white wall.
     */
    @Test
    void thinMistGlowsByItsDepthThickHasItsColour() {
        double w = PallLight.albedo(PallLight.linear(0.918), MIST), last = 0;
        for (double tc : new double[]{0.02, 0.1, 0.5, 2, 8, 40}) {
            double seen = 0;
            int n = 4000;
            for (int i = 0; i < n; i++) {
                double tau = (i + 0.5) / n * tc, j = w * (PallLight.diffuse(w, MIST, tau, tc, PallLight.SEA, 1, SUN, 1, 0, SKY, 0, MIST)
                        + 0.25 * SUN * hg(MIST, -1) * Math.exp(-tau));
                seen += j * Math.exp(-tau) * tc / n;
            }
            assertTrue(seen > last, "τc = " + tc + ": " + seen);
            if (tc <= 0.1) assertTrue(seen < 0.6 * tc, "fine water dust shines " + seen + " with thickness " + tc);
            assertTrue(seen < 0.95, "τc = " + tc + ": white wall, " + seen);
            last = seen;
        }
    }

    /**
     * Thick dust at noon (visible at 40 blocks, 150 blocks high): below - brown twilight (like “darkness in the middle of the day” after
     * Hiroshima), on the screen several times darker than the top, but not black; the light fades towards the ground slower than the direct beam.
     */
    @Test
    void denseDustIsDuskBelow() {
        double w = PallLight.albedo(PallLight.linear(0.53), DUST), tc = 0.098 * 150 * 0.548;
        double top = w * (PallLight.diffuse(w, DUST, 0, tc, PallLight.LAND, 1, SUN, 1, 0, SKY, 0, 0) + 0.25 * SUN * hg(DUST, 0));
        double bottom = w * (PallLight.diffuse(w, DUST, tc, tc, PallLight.LAND, 1, SUN, 1, 0, SKY, 0, 0) + 0.25 * SUN * hg(DUST, 0) * Math.exp(-tc));
        assertTrue(PallLight.display(bottom) < PallLight.display(top) / 3, "below " + bottom + ", above " + top);
        assertTrue(PallLight.display(bottom) > 0.06, "black below: " + PallLight.display(bottom));
        assertTrue(bottom / top > 10 * Math.exp(-tc), "diffused light goes out like direct light: " + bottom / top);
    }

    /**
     * Shadow pall on the ground ({@code shade} to {@code fx_pall_composite}): light on the ground under the layer against the sky without it - from the
     *  thin layer 1, with less thickness; water dust that does not absorb shades less dust of the same thickness.
     */
    @Test
    void groundShadeFadesWithDepth() {
        double wd = PallLight.albedo(PallLight.linear(0.53), DUST), wm = PallLight.albedo(PallLight.linear(0.918), MIST), last = 1.0001;
        for (double tc : new double[]{1e-4, 0.3, 1, 3, 8}) {
            double dust = ground(wd, DUST, tc, PallLight.LAND), mist = ground(wm, MIST, tc, PallLight.SEA);
            if (tc < 1e-3) assertEquals(1, dust, 1e-3);
            assertTrue(dust < last && dust > 0, "τc = " + tc + ": " + dust);
            if (tc > 0.1) assertTrue(mist > dust, "τc = " + tc + ": water dust " + mist + ", dust " + dust);
            last = dust;
        }
    }

    private static double ground(double w, double g, double tc, double albedo) {
        double mu = 1;
        return (mu * SUN * Math.exp(-tc / mu) + PallLight.diffuse(w, g, tc, tc, albedo, mu, SUN, 1, 0, SKY, 0, -2.0 / 3)) / (mu * SUN + SKY);
    }

    /** The light of fine dust for layer squares is the limit of a layer without thickness: scattered towards the eye, averaged in directions. */
    @Test
    void thinLightIsTheThinLimit() {
        double w = PallLight.albedo(PallLight.linear(0.53), DUST), mu = 0.8;
        double i0 = PallLight.diffuse(w, DUST, 0, 1e-6, PallLight.LAND, mu, SUN, 1, 0, SKY, 0, 0);
        assertEquals(PallLight.thin(w, SUN, SKY, mu, PallLight.LAND), w * (i0 + 0.25 * SUN), 2e-3);
    }

    private static double hg(double g, double c) {
        double k = 1 + g * g - 2 * g * c;
        return (1 - g * g) / (k * Math.sqrt(k));
    }
}
