package ua.zentix.incoming.client.far;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

/**
 * Sunlight on a puff through the rest of its cloud ({@link SmokePuffs#sunThrough}, {@link SmokePuffs#tubeThrough},
 * {@link SmokePuffs#sootSun}): the closed forms against the Gaussian integrated along the ray, a black cloud's sun
 * side lit and far side dark, white matter and lone clouds unchanged, nothing at night, the gain only on dark smoke.
 */
class CloudSunTest {
    /** Morning sun of the gate scene (time 1500, 35° up), clear day: direct share 0.8. */
    private static final FarView.Sun SUN = sun(0.820, 0.572, 0, 0.8f);

    /** Soot and droplet mixes: two-stream attenuation as {@link SmokePuffs#shade}. */
    private static final double SOOT = SmokePuffs.shade(1, 0, 0, 0.9f), DROPS = SmokePuffs.shade(0, 0, 1, 0.9f);

    private static FarView.Sun sun(double x, double y, double z, float w) {
        double n = Math.sqrt(x * x + y * y + z * z);
        return new FarView.Sun((float) (x / n), (float) (y / n), (float) (z / n), w);
    }


    @Test
    void closedFormIsTheGaussianAlongTheRay() {
        double sh = 12, sv = 7, ext = 900;
        double[][] points = {{0, 0, 0}, {10, -5, 3}, {-20, 8, -6}, {4, 12, 25}};
        for (double[] o : points) {
            double sum = 0, dt = 0.01;
            for (double t = dt / 2; t < 200; t += dt) {
                double x = o[0] + SUN.x() * t, y = o[1] + SUN.y() * t, z = o[2] + SUN.z() * t;
                sum += ext / (Math.pow(2 * Math.PI, 1.5) * sh * sh * sv) * Math.exp(-0.5 * (x * x + z * z) / (sh * sh) - 0.5 * y * y / (sv * sv)) * dt;
            }
            double got = SmokePuffs.sunThrough(o[0], o[1], o[2], 0, 0, sh, sv, ext, 1, SUN);
            assertEquals(Math.exp(-sum), got, 1e-4 + 1e-3 * Math.exp(-sum), "point " + java.util.Arrays.toString(o));
        }
    }

    @Test
    void edgeIsWhereTheRayFromTheMiddleLeavesThePuff() {
        // radii 20 and 5 m, sun at 45°: the ray leaves the ellipsoid at (4.85, 4.85) m (review, finding 3)
        FarView.Sun s = sun(1, 1, 0, 0.8f);
        double sh = 30, sv = 30, ext = 5e4;
        double fromEdge = SmokePuffs.sunThrough(0, 0, 0, 20, 5, sh, sv, ext, 1, s);
        double atPoint = SmokePuffs.sunThrough(4.8507 * Math.sqrt(2) * s.x(), 4.8507 * Math.sqrt(2) * s.y(), 0, 0, 0, sh, sv, ext, 1, s);
        assertEquals(atPoint, fromEdge, 1e-6 + 1e-3 * atPoint);
    }

    @Test
    void tubeClosedFormIsTheCylinderAlongTheRay() {
        // a vertical tube, sun at 35°: a 2-D Gaussian across the axis
        double sigma = 6, tau = 10;
        double[][] points = {{0, 0, 0}, {5, 3, -2}, {-8, -4, 6}};
        for (double[] o : points) {
            double sum = 0, dt = 0.005;
            for (double t = dt / 2; t < 200; t += dt) {
                double x = o[0] + SUN.x() * t, z = o[2] + SUN.z() * t;
                sum += tau / (Math.sqrt(2 * Math.PI) * sigma) * Math.exp(-0.5 * (x * x + z * z) / (sigma * sigma)) * dt;
            }
            double got = SmokePuffs.tubeThrough(o[0], o[1], o[2], 0, sigma, tau, 0, 1, 0, 1, SUN);
            assertEquals(Math.exp(-sum), got, 1e-4 + 1e-3 * Math.exp(-sum), "point " + java.util.Arrays.toString(o));
        }
    }

    @Test
    void blackCloudSunSideLitFarSideDark() {
        double sh = 15, sv = 15, ext = SmokeGrid.SOOT_K * 4.0;
        double tau = ext / (2 * Math.PI * sh * sv);
        assertTrue(tau > 20, "a dense soot clump: thickness across " + tau);
        double r = 0.4 * SmokePuffs.edge(tau) * sh;
        double toward = SmokePuffs.sunThrough(1.5 * sh * SUN.x(), 1.5 * sh * SUN.y(), 0, r, r, sh, sv, ext, SOOT, SUN);
        double away = SmokePuffs.sunThrough(-1.5 * sh * SUN.x(), -1.5 * sh * SUN.y(), 0, r, r, sh, sv, ext, SOOT, SUN);
        assertTrue(toward > 0.9, "sun side keeps the sun: " + toward);
        assertTrue(away < 0.01, "far side: no direct sun: " + away);
    }

    @Test
    void whiteMatterPassesLightLoneCloudFromItsEdgeNightNone() {
        double sh = 15, sv = 15, ext = SmokeGrid.DROP_K * 40.0;
        double drops = SmokePuffs.sunThrough(-1.5 * sh * SUN.x(), -1.5 * sh * SUN.y(), 0, 6, 6, sh, sv, ext, DROPS, SUN);
        double soot = SmokePuffs.sunThrough(-1.5 * sh * SUN.x(), -1.5 * sh * SUN.y(), 0, 6, 6, sh, sv, ext, SOOT, SUN);
        assertTrue(drops > soot && drops > 0.9, "droplets pass the light on (" + drops + "), soot does not (" + soot + ")");
        // a cloud of one Gaussian drawn as one puff: from its visible edge the rest is below the edge threshold
        double sootExt = SmokeGrid.SOOT_K * 2.0, tau = sootExt / (2 * Math.PI * 10 * 10), edge = SmokePuffs.edge(tau);
        assertTrue(SmokePuffs.sunThrough(0, 0, 0, edge * 10, edge * 10, 10, 10, sootExt, SOOT, SUN) > 0.9, "a lone clump keeps the sun");
        assertEquals(1, SmokePuffs.sunThrough(-20, -10, 0, 0, 0, 10, 10, 1e5, SOOT, FarView.NO_SUN), 0, "night: the moon's light is a lone puff's");
        assertEquals(1, SmokePuffs.tubeThrough(-20, -10, 0, 0, 5, 50, 0, 1, 0, SOOT, FarView.NO_SUN), 0);
    }

    @Test
    void gainOnlyOnTheSunThatReachesSoot() {
        assertEquals(SmokePuffs.SOOT_SUN, SmokePuffs.sootSun(1, 1), 1e-9, "sunlit soot");
        assertEquals(0, SmokePuffs.sootSun(0, 1), 0, "buried soot: no direct light, no lift");
        assertEquals(1, SmokePuffs.sootSun(1, 0), 0, "white smoke and dust keep their light");
        assertEquals(1, SmokePuffs.sootShare(SmokePuffs.SOOT_COLOUR), 1e-6, "a fire plume's soot");
        assertTrue(SmokePuffs.sootShare(SmokePuffs.thick(SmokeGrid.DROP_W, SmokeGrid.DROP_G)) < 0.05, "a white trail");
    }

    @Test
    void skyLightReachesTheTopNotTheCore() {
        // a dense soot cloud: the top puff sees the sky, the one in its lower middle does not (the sky's light from
        // above, FarView.ZENITH)
        double sh = 15, sv = 15, ext = SmokeGrid.SOOT_K * 4.0, r = 0.3 * SmokePuffs.edge(ext / (2 * Math.PI * sh * sv)) * sh;
        double top = SmokePuffs.sunThrough(0, 2 * sv, 0, r, r, sh, sv, ext, SOOT, FarView.ZENITH);
        double core = SmokePuffs.sunThrough(0, -0.5 * sv, 0, r, r, sh, sv, ext, SOOT, FarView.ZENITH);
        assertTrue(top > 0.9 && core < 0.05, "top " + top + ", core " + core);
    }
}
