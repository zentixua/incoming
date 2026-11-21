package ua.zentix.incoming.client.far;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

/**
 * Sunlight on a puff through the rest of its cloud ({@link SmokePuffs#sunThrough}, {@link SmokePuffs#sunlit}): the closed form against
 * the Gaussian integrated along the ray, a black cloud's sun side lit and far side in the sky's light only, white
 * matter and lone puffs unchanged, nothing at night.
 */
class CloudSunTest {
    /** Morning sun of the gate scene (time 1500, 35° up), clear day: direct share 0.8. */
    private static final float[] SUN = {0.820f, 0.572f, 0, 0.8f};

    /** Soot and droplet mixes: two-stream attenuation as {@link SmokePuffs#shade}. */
    private static final double SOOT = SmokePuffs.shade(1, 0, 0, 0.9f), DROPS = SmokePuffs.shade(0, 0, 1, 0.9f);

    @Test
    void closedFormIsTheGaussianAlongTheRay() {
        double sh = 12, sv = 7, ext = 900;
        double[][] points = {{0, 0, 0}, {10, -5, 3}, {-20, 8, -6}, {4, 12, 25}};
        for (double[] o : points) {
            double sum = 0, dt = 0.01;
            double n = Math.sqrt(SUN[0] * SUN[0] + SUN[1] * SUN[1]);
            for (double t = dt / 2; t < 200; t += dt) {
                double x = o[0] + SUN[0] / n * t, y = o[1] + SUN[1] / n * t, z = o[2];
                sum += ext / (Math.pow(2 * Math.PI, 1.5) * sh * sh * sv) * Math.exp(-0.5 * (x * x + z * z) / (sh * sh) - 0.5 * y * y / (sv * sv)) * dt;
            }
            float[] unit = {(float) (SUN[0] / n), (float) (SUN[1] / n), 0, 1};
            double k = 1;
            double expected = Math.exp(-k * sum);
            double got = SmokePuffs.sunThrough(o[0], o[1], o[2], 0, 0, sh, sv, ext, k, unit);
            assertEquals(expected, got, 1e-4 + 1e-3 * expected, "point " + java.util.Arrays.toString(o));
        }
    }

    @Test
    void blackCloudSunSideLitFarSideSkyOnly() {
        double sh = 15, sv = 15, ext = SmokeGrid.SOOT_K * 4.0;
        double tau = ext / (2 * Math.PI * sh * sv);
        assertTrue(tau > 20, "a dense soot clump: thickness across " + tau);
        // a cluster puff of 0.4 of the edge radius at 1.5 σ toward and away from the sun
        double edge = SmokePuffs.edge(tau), r = 0.4 * edge * sh;
        double toward = SmokePuffs.sunlit(SmokePuffs.sunThrough(1.5 * sh * SUN[0], 1.5 * sh * SUN[1], 0, r, r, sh, sv, ext, SOOT, SUN), SUN);
        double away = SmokePuffs.sunlit(SmokePuffs.sunThrough(-1.5 * sh * SUN[0], -1.5 * sh * SUN[1], 0, r, r, sh, sv, ext, SOOT, SUN), SUN);
        assertTrue(toward > 0.95, "sun side keeps the sun: " + toward);
        assertEquals(1 - SUN[3], away, 0.01, "far side: the sky's diffuse share only");
    }

    @Test
    void whiteMatterPassesLightNightUnchanged() {
        double sh = 15, sv = 15, ext = SmokeGrid.DROP_K * 40.0;
        double away = SmokePuffs.sunThrough(-1.5 * sh * SUN[0], -1.5 * sh * SUN[1], 0, 6, 6, sh, sv, ext, DROPS, SUN);
        double soot = SmokePuffs.sunThrough(-1.5 * sh * SUN[0], -1.5 * sh * SUN[1], 0, 6, 6, sh, sv, ext, SOOT, SUN);
        assertTrue(away > soot && away > 0.9, "droplets pass the light on (" + away + "), soot does not (" + soot + ")");
        assertEquals(1, SmokePuffs.sunlit(0, FarView.NO_SUN), 0, "no direct sun (night): the vertex colour keeps fire light");
    }

    @Test
    void loneGaussianIsLitFromItsOwnEdge() {
        // a cloud of one Gaussian: its envelope is itself, and from its visible edge the rest of the thickness is
        // below the edge threshold, so a lone clump keeps the sun (its shadow is in its maps)
        double sh = 10, sv = 10, ext = SmokeGrid.SOOT_K * 2.0, tau = ext / (2 * Math.PI * sh * sv), edge = SmokePuffs.edge(tau);
        double t = SmokePuffs.sunThrough(0, 0, 0, edge * sh, edge * sv, sh, sv, ext, SOOT, SUN);
        assertTrue(t > 0.9, "lone clump from its edge: " + t);
    }

    @Test
    void sunGainOnlyOnLitDarkSmokeFacingTheEye() {
        float[] sun = SUN;
        double c = SmokePuffs.SOOT_COLOUR, white = SmokePuffs.thick(SmokeGrid.DROP_W, SmokeGrid.DROP_G);
        // the eye on the sun's side of the puff (front-lit): soot lit ×(1 + (gain − 1)·w)
        double front = SmokePuffs.sootSun(1, sun, c, sun[0], sun[1], sun[2]);
        assertEquals(1 - sun[3] + sun[3] * SmokePuffs.SOOT_SUN, front, 1e-3);
        // back-lit: the eye sees the shadowed side, no lift
        assertEquals(1, SmokePuffs.sootSun(1, sun, c, -sun[0], -sun[1], -sun[2]), 1e-3);
        // buried (no sun reaches it): only the sky's diffuse share, no lift
        assertEquals(1 - sun[3], SmokePuffs.sootSun(0, sun, c, sun[0], sun[1], sun[2]), 1e-3);
        // white matter keeps its light; night has no sun term
        assertEquals(1, SmokePuffs.sootSun(1, sun, white, sun[0], sun[1], sun[2]), 0.03);
        assertEquals(1, SmokePuffs.sootSun(1, FarView.NO_SUN, c, 0, 1, 0), 1e-9);
    }
}
