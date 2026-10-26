package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MushroomFieldTest {
    /**
     * The frame of the mushroom (top and radius) are numbers for the entire life of the mushroom: the airborne one, until the front reaches the ground, does not have a skirt (there is nothing to lift it
     * ), and the zero of its cross-section does not divide the frame (NaN threw the mushroom out of the frame as a pyramid of visibility).
     */
    @Test
    void boundsAreFiniteBeforeTheFrontReachesTheGround() {
        double high = 4 * FireballModel.maxRadius(15, false);
        for (double t = 0.1; t < MushroomModel.lifeSeconds(15); t += 1.7) {
            for (double front : new double[] {0, 300, 3_000}) {
                MushroomModel.Shape s = MushroomModel.shape(t, 15, high, false, front);
                if (s == null) continue;
                double top = MushroomField.top(s), reach = MushroomField.reach(s);
                assertTrue(Double.isFinite(top) && top >= 1, t + " s, front " + front + ": top " + top);
                assertTrue(Double.isFinite(reach) && reach >= 1, t + " s, front " + front + ": radius " + reach);
                if (front == 0) assertEquals(0, s.skirtTau(), 0, t + " s: skirt before the front arrives");
            }
        }
    }

    /**
     * The frame of the beam is an inclined cylinder (mushSpan): each place of the body (the axis is shifted by the drift by a fraction of the height, mushShare, around
     *  it - up to reach) lies in it at any height from 0,05 the top underground to the top, and below and above the end of the rise.
     */
    @Test
    void boundHoldsTheDriftedBody() {
        double reach = 900, drift = 1700;
        for (double[] te : new double[][] {{4000, 3000}, {2500, 3000}, {500, 3000}}) {
            double top = te[0], end = te[1], k = drift / end;
            double r = MushroomField.boundRadius(reach, drift, top, end);
            for (double h = -0.05 * top; h <= top; h += top / 200) {
                double axis = drift * Math.clamp(h / end, 0, 1), tilted = k * h;
                // farthest from the inclined axis - the place of the body is on the edge of reach in the direction of the divergence of the axes
                double worst = Math.abs(axis - tilted) + reach;
                assertTrue(worst <= r + 1e-9, "top " + top + ", end " + end + ", height " + h + ": " + worst + " > " + r);
            }
        }
    }

    /**
     * A frame with a column of water holds its wall and crown with billows (up to {@link MushroomField#REACH_COLUMN} wall thickness
     *  beyond their edge) for the entire life of the pillar, while the mushroom at the explosion of 15 kt on the water is still young and narrower than it, and does not swell: and at a low
     *  pillar (the first fractions of a second, the end of the fall) - no wider than three of its radii (the ray of the mushroom would go through the empty, and the thickness towards the
     *  light - past the cap).
     */
    @Test
    void boundHoldsTheWaterColumn() {
        for (double t = 0.001; t < 40; t += t < 1 ? 0.01 : 0.37) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 3_000);
            WaterModel.Column c = WaterModel.column(t, 15);
            if (c == null) continue;
            double r = MushroomField.REACH_COLUMN * MushroomField.columnUnit(c);
            double reach = MushroomField.reach(s, c), top = MushroomField.top(s, c);
            assertTrue(reach >= c.mid() + c.half() + r - 1e-9 && reach >= c.crownR() + r - 1e-9, t + " s: radius " + reach);
            assertTrue(top >= c.top() + r - 1e-9, t + " s: top " + top);
            assertTrue(reach >= MushroomField.reach(s) && top >= MushroomField.top(s), t + " s: mushroom frame");
            // and no wider than necessary: the frame with the pillar is no wider than three of its radii and no higher than its radius above it
            assertTrue(reach <= Math.max(MushroomField.reach(s), 3 * c.outer()), t + " s: radius " + reach + " at the pillar " + c.outer());
            assertTrue(top <= Math.max(MushroomField.top(s), c.top() + c.outer()), t + " s: top " + top + " at the pillar " + c.top());
        }
        MushroomModel.Shape s = MushroomModel.shape(5, 15, 0, true, 3_000);
        assertEquals(MushroomField.reach(s), MushroomField.reach(s, null), 0, "without pillar - mushroom frame");
    }

    /** Shadow direction is single, not lower than {@link MushroomField#SHADOW_LOW}; the axes of the plane across it are orthonormal. */
    @Test
    void shadowAxesAreOrthonormal() {
        double[] s = new double[3], a = new double[3], b = new double[3];
        for (double angle = 0; angle < 2 * Math.PI; angle += 0.01) {
            for (double z : new double[] {0, 0.3, -0.95}) {
                MushroomField.shadowSun(-Math.sin(angle), Math.cos(angle), z, s);
                MushroomField.shadowAxes(s, a, b);
                String at = "angle " + angle + ", z " + z;
                assertEquals(1, dot(s, s), 1e-9, at);
                assertTrue(s[1] >= MushroomField.SHADOW_LOW - 1e-12, at);
                assertEquals(1, dot(a, a), 1e-9, at);
                assertEquals(1, dot(b, b), 1e-9, at);
                assertEquals(0, dot(a, s), 1e-9, at);
                assertEquals(0, dot(b, s), 1e-9, at);
                assertEquals(0, dot(a, b), 1e-9, at);
            }
        }
    }

    /**
     * The shadow map tile holds the projection of the mushroom frame across the sun (an inclined cylinder mushLine: circles of radius boundR around the
     *  axis, shifted by drift by a fraction of the height, from −0,05 top to top) with a supply of {@link MushroomField#SHADOW_PAD} texels
     *  with each sides: straight lines at the edge of the tile go past the mushroom, and the shadow is not cut off by the edge.
     */
    @Test
    void shadowTileHoldsTheBound() {
        double[] s = new double[3], a = new double[3], b = new double[3], rect = new double[4];
        for (double angle = -1.55; angle <= 1.55; angle += 0.05) {
            MushroomField.shadowSun(-Math.sin(angle), Math.cos(angle), 0, s);
            MushroomField.shadowAxes(s, a, b);
            for (double[] g : new double[][] {{1200, 2500, 3000, 1700, -400}, {600, 400, 3000, 0, 0}, {80, 300, 1, 40, 25}}) {
                double r = g[0], top = g[1], end = g[2], dx = g[3], dz = g[4];
                MushroomField.shadowRect(a, b, r, dx, dz, top, end, rect);
                double kx = dx / Math.max(end, 1), kz = dz / Math.max(end, 1);
                for (int i = 0; i < 2; i++) {
                    double[] e = i == 0 ? a : b;
                    double pad = MushroomField.SHADOW_PAD * rect[2 + i] / MushroomField.SHADOW, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                    for (double h = -0.05 * top; h <= top + 1e-9; h += top / 100) {
                        for (double phi = 0; phi < 2 * Math.PI; phi += 0.05) {
                            double u = e[0] * (kx * h + r * Math.cos(phi)) + e[1] * h + e[2] * (kz * h + r * Math.sin(phi));
                            min = Math.min(min, u);
                            max = Math.max(max, u);
                        }
                    }
                    String at = "angle " + angle + ", frame " + r + "/" + top + ", axis " + i;
                    assertTrue(min >= rect[i] + pad - 1e-6, at + ": " + min + " < " + (rect[i] + pad));
                    assertTrue(max <= rect[i] + rect[2 + i] - pad + 1e-6, at + ": " + max + " > " + (rect[i] + rect[2 + i] - pad));
                }
            }
        }
    }

    /**
     * The color of the cap is from drops and NO₂, without hours. Observations: in reflected light the cloud is white, in transmitted light it is red-brown, and
     *  there is brown light inside it (Taylor and Minge 1963); first the cloud is “red or red-brown” (G&D §2.08). What the physics
     *  of this substance gives: cap 15 kt after rising reflects almost white (channels are not lower than 0,6, blue is not less than 0,75 red), and the
     *  beam from above comes out under it and stands in its middle brown (blue is less than a third of red). A just-folded
     *  ring in reflected light is ocher (the shade on the screen is 28–45°; for red-brown it is less than 25°), and it does not become red-brown
     *  at any fraction of NO₂: for a strong absorption R∞ ∝ ω' ∝ 1/σ along the channel - shade 29°. Red-brown - light that has passed through the ring
     *  (shade less than 25°). The cap itself turns white while NO₂ thins out in a growing volume.
     */
    @Test
    void capIsWhiteOutsideAndBrownInside() {
        double hob = 592, stab = CloudModel.stabilizationSeconds(15);
        double[] w = new double[4];
        MushroomModel.Shape s = MushroomModel.shape(stab, 15, hob, false, 1e9);
        double tau = MushroomField.capTau(s, MushroomField.capMatter(s, 0, 0, w));
        double[] r = reflect(w), down = new double[3], mid = new double[3];
        for (int i = 0; i < 3; i++) {
            down[i] = PallLight.diffuse(w[i], w[3], tau, tau, 0, 1, 1, 1, 0, 0, 0, -2.0 / 3) + Math.exp(-tau);
            mid[i] = PallLight.diffuse(w[i], w[3], tau / 2, tau, 0, 1, 1, 1, 0, 0, 0, 0);
        }
        String at = String.format(Locale.ROOT, "reflection %.2f %.2f %.2f, under cap %.3f %.3f %.3f", r[0], r[1], r[2],
                down[0], down[1], down[2]);
        assertTrue(r[2] >= 0.6 && r[2] >= 0.75 * r[0], at);
        assertTrue(down[2] < down[0] / 3 && mid[2] < mid[0] / 3, at);
        assertTrue(tau > 20, "opaque: " + tau);
        // just rolled ring: in reflected light - ocher, through - red-brown
        double tr = MushroomModel.rollUpSeconds(15, false);
        MushroomModel.Shape rolled = MushroomModel.shape(tr, 15, hob, false, 1e9);
        double ringTau = MushroomField.capTau(rolled, MushroomField.capMatter(rolled, 0, 0, w));
        double[] young = reflect(w), through = new double[3];
        for (int i = 0; i < 3; i++) {
            through[i] = PallLight.diffuse(w[i], w[3], ringTau, ringTau, 0, 1, 1, 1, 0, 0, 0, -2.0 / 3) + Math.exp(-ringTau);
        }
        String ring = String.format(Locale.ROOT, "ring: %.3f %.3f %.3f, shade %.0f°, through %.0f°", young[0], young[1], young[2],
                hue(young), hue(through));
        assertTrue(young[0] > young[1] && young[1] > young[2] && young[2] > 0, ring);
        assertTrue(hue(young) >= 28 && hue(young) <= 45, ring);
        assertTrue(hue(through) < 25, ring);
        // more NO₂ - darker, but not redder than ocher: absorption by channels × k
        double[] dark = new double[3];
        for (double k = 1; k <= 1e4; k *= 3) {
            for (int i = 0; i < 3; i++) dark[i] = MushroomField.wallReflectance(1 / (1 + k * (1 / w[i] - 1)), w[3]);
            assertTrue(hue(dark) >= 28, String.format(Locale.ROOT, "NO₂ × %.0f: shade %.1f°", k, hue(dark)));
        }
        // turns white on its own, without jumps: blue grows towards red
        double last = 0;
        for (double t = tr; t <= stab; t += 5) {
            MushroomField.capMatter(MushroomModel.shape(t, 15, hob, false, 1e9), 0, 0, w);
            double[] c = reflect(w);
            double ratio = c[2] / c[0];
            assertTrue(ratio >= last, t + " s: " + ratio);
            last = ratio;
        }
    }

    /**
     * Reflection of a thick layer ({@link MushroomField#wallReflectance}, hemispherical average) - from 0 for one absorption
     *  to 1 for one scattering and not less than zero for any ω'; for Eddington ({@link PallLight#reflectance}) with strong
     *  absorption - less than zero.
     */
    @Test
    void wallReflectanceIsNeverNegative() {
        for (double g : new double[]{0, 0.375, 0.459, 0.6}) {
            assertEquals(0, MushroomField.wallReflectance(0, g), 1e-12);
            assertEquals(1, MushroomField.wallReflectance(1, g), 1e-6);
            double last = -1;
            for (double w = 0; w <= 1; w += 0.001) {
                double r = MushroomField.wallReflectance(w, g);
                assertTrue(r >= 0 && r <= 1 && r >= last, "ω' " + w + ", g' " + g + ": " + r);
                last = r;
            }
        }
        assertTrue(PallLight.reflectance(0, 0.459) < -0.05, "Eddington at one takeover");
    }

    /**
     * cap with soil (ground blasting), when there is almost no NO₂ left in it - gray color {@link MushroomField#CAP_DUSTY}:
     *  soil absorption - in excess of NO₂. The ball only absorbs drops (ω' = 0): it is visible with its light.
     */
    @Test
    void dustyCapAndBall() {
        double[] w = new double[4];
        MushroomModel.Shape late = MushroomModel.shape(3 * CloudModel.stabilizationSeconds(15), 15, 0, true, 1e9);
        MushroomField.capMatter(late, 1, 0, w);
        double g = PallLight.truncated(MushroomField.G);
        assertEquals(g, w[3], 1e-12);
        for (int i = 0; i < 3; i++) {
            double dusty = PallLight.albedo(PallLight.linear(((MushroomField.CAP_DUSTY >> (16 - 8 * i)) & 0xFF) / 255.0), g);
            assertTrue(w[i] < dusty && w[i] > dusty - 0.02, i + ": " + w[i] + " against " + dusty);
        }
        MushroomModel.Shape ball = MushroomModel.shape(0.5, 15, 592, false, 1e9);
        double sigma = MushroomField.capMatter(ball, 0, 0, w);
        assertEquals(0, w[0] + w[1] + w[2], 0);
        assertTrue(MushroomField.capTau(ball, sigma) > 5, "the ball is opaque");
    }

    /**
     * Steam over water is a mixture of substances by attenuation: ω' of the mixture is the average ω' by attenuation fractions. While the steam has just appeared
     *  (fresh ring 1 and 15 kt, where ω' channels below 1/(4 − 3g') ≈ 0,38), ω' not jumps: the previous mixing in the color of the layer
     *  turned the Eddington reflection, clamped to zero, and in the very first frame the pair raised such channels to 0,38. Where pair
     * all weakening is ω' pair, between - in a straight line from cap to pair; weakening and asymmetry - cap.
     */
    @Test
    void steamMixesWithoutAJump() {
        double[] dry = new double[4], wet = new double[4];
        boolean dark = false;
        for (double kt : new double[]{1, 15}) {
            double tr = MushroomModel.rollUpSeconds(kt, false), hob = 240 * Math.cbrt(kt);
            for (double t = 0.25 * tr; t <= tr; t += 0.05 * tr) {
                MushroomModel.Shape s = MushroomModel.shape(t, kt, hob, false, 1e9);
                double sigma = MushroomField.capMatter(s, 0, 0, dry);
                assertEquals(sigma, MushroomField.capMatter(s, 0, 1e-6, wet), 0, kt + " kt, " + t + " s: weakening");
                for (int i = 0; i < 4; i++) {
                    assertEquals(dry[i], wet[i], 1e-6, kt + " kt, " + t + " s, channel " + i);
                    if (i < 3 && dry[i] < 1 / (4 - 3 * dry[3])) dark = true;
                }
                double steam = MushroomModel.rollUp(t, kt, false), share = MushroomField.STEAM_SHARE * steam;
                MushroomField.capMatter(s, 0, steam, wet);
                for (int i = 0; i < 3; i++) {
                    double mixed = dry[i] + (MushroomField.steamAlbedo(i, dry[3]) - dry[i]) * share;
                    assertEquals(mixed, wet[i], 1e-12, kt + " kt, " + t + " s, channel " + i + ": mixture");
                }
            }
        }
        assertTrue(dark, "the fresh ring has channels with ω' below 0,38");
        MushroomModel.Shape s = MushroomModel.shape(MushroomModel.rollUpSeconds(15, false), 15, 592, false, 1e9);
        MushroomField.capMatter(s, 0, 1 / MushroomField.STEAM_SHARE, wet);
        for (int i = 0; i < 3; i++) assertEquals(MushroomField.steamAlbedo(i, wet[3]), wet[i], 1e-12, "one pair, channel " + i);
        assertTrue(MushroomField.steamAlbedo(1, wet[3]) > 0.98, "absorbs almost no steam");
    }

    /** Reflection of a thick layer along channels with albedo and asymmetry {@code w} (ω' r, g, b, g'). */
    private static double[] reflect(double[] w) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) r[i] = MushroomField.wallReflectance(w[i], w[3]);
        return r;
    }

    /** Color shade on the screen, degrees (as in HSV): linear r, g, b - in screen values, γ 2,2. */
    private static double hue(double[] linear) {
        double r = PallLight.display(linear[0]), g = PallLight.display(linear[1]), b = PallLight.display(linear[2]);
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), c = max - min;
        if (c <= 0) return 0;
        double h;
        if (max == r) h = (g - b) / c;
        else if (max == g) h = 2 + (b - r) / c;
        else h = 4 + (r - g) / c;
        return (60 * h + 360) % 360;
    }

    private static double dot(double[] x, double[] y) {
        return x[0] * y[0] + x[1] * y[1] + x[2] * y[2];
    }

    /**
     * Pattern of the surface of the ball: to the minimum brightness - it is not there (place −1), to the break - the first frame, then the frames go forward with
     *  ascent; share of the pattern - while the ball is a ball (1 - share of the ring), without a read file - 0 (ball as before).
     */
    @Test
    void ballSurfaceFollowsTheBreakawayAndTheRollUp() {
        double[] out = new double[2];
        double y = 15;
        float[] rises;
        try (var in = MushroomFieldTest.class.getResourceAsStream("/assets/incoming/textures/fx/nukeball.bin")) {
            rises = NukeBallSurface.parse(in).rises();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        MushroomField.ballSurface(0.5 * FireballModel.firstMinimumSeconds(y), 600, y, false, rises, out);
        assertEquals(-1, out[0], 0);
        assertEquals(1, out[1], 1e-12);
        MushroomField.ballSurface(FireballModel.breakawaySeconds(y), 600, y, false, rises, out);
        assertEquals(0, out[0], 1e-9);
        double last = -1;
        for (double t = 0.001; t < 40; t *= 1.1) {
            MushroomField.ballSurface(t, 600, y, false, rises, out);
            assertTrue(out[0] >= last, "frame not backward: " + t);
            last = out[0];
            assertEquals(1 - MushroomModel.rollUp(t, y, false), out[1], 1e-12);
            MushroomField.ballSurface(t, 600, y, false, (float[]) null, out);
            assertEquals(0, out[1], 0);
        }
        // frame - the path of ascent in radii: by the end of the folding at 15 kt - 4,1 radius from the 5 file, it does not go further than the last
        MushroomField.ballSurface(MushroomModel.rollUpSeconds(y, false), 600, y, false, rises, out);
        assertEquals(NukeBallSurface.frame(rises, FireballModel.surfaceRise(MushroomModel.rollUpSeconds(y, false), 600, y, false)), out[0], 1e-9);
        assertTrue(out[0] > 0.6 * (rises.length - 1) && out[0] < rises.length - 1, "frame to the end of folding: " + out[0]);
    }
    /**
     * The stem and the skirt are the dust of the explosion site, the same substance as the layer of dust of the wave under them (pall carpet): the same color of the thick
     *  layer and the same asymmetry. stem feeds only dust from this layer ({@code StemDust}); its color “dust with smoke” made it
     *  grayer than the carpet on which it stands.
     */
    @Test
    void stemAndSkirtAreTheLayersDust() {
        for (int i = 0; i < 3; i++) {
            assertEquals(NukePall.DUST[i], ((MushroomField.DUST >> (16 - 8 * i)) & 0xFF) / 255.0, 0.5 / 255, "channel " + i);
        }
        assertEquals(PallLight.DUST_G, MushroomField.G, 0, "asymmetry of mushroom dust - like pall");
    }
    /**
     * The ground under the mushroom is the top of the layer of dust that lies on it: without dust - the earth itself, under the thick layer - the color of the thick layer of
     * dust (the same R∞ as the carpet on the screen), between - without jumps and monotonously. stem in the shadow of cap sees this carpet from below, and
     *  not the bare earth 0,2.
     */
    @Test
    void groundUnderTheMushroomIsTheDustLayerOnIt() {
        double g = PallLight.truncated(MushroomField.G);
        for (int i = 0; i < 3; i++) {
            double r = PallLight.linear(NukePall.DUST[i]);
            assertEquals(PallLight.LAND, MushroomField.slabOver(r, g, 0, PallLight.LAND), 1e-12, "without dust - earth");
            assertEquals(r, MushroomField.slabOver(r, g, 200, PallLight.LAND), 1e-6, "thick layer - carpet color");
            double prev = PallLight.LAND;
            for (double tau = 0.01; tau < 50; tau *= 1.2) {
                double now = MushroomField.slabOver(r, g, tau, PallLight.LAND);
                assertTrue(Math.abs(now - prev) < 0.02, "channel " + i + " on τ " + tau);
                assertTrue((r - now) * (r - PallLight.LAND) >= -1e-12, "between the ground and the carpet");
                prev = now;
            }
        }
    }
}
