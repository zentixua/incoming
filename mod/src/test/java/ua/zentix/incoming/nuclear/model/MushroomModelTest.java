package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MushroomModelTest {
    /** Adjusting the eye at night ({@code Sight.adapted} in the light of the sky 0,17): the light appears so many times brighter. */
    private static final double NIGHT = 1 / Math.pow(0.17, 4);

    /** The ball rolls into a ring in 2,5·√(R/g): for 15 the kt is about 12 s; the share of the ring increases from 0 to 1. */
    @Test
    void ballRollsUpIntoARing() {
        double tr = MushroomModel.rollUpSeconds(15, false);
        assertTrue(tr > 9 && tr < 14, "15 kt: " + tr + " s");
        assertTrue(MushroomModel.rollUpSeconds(1, false) < tr);
        assertEquals(0, MushroomModel.rollUp(0, 15, false), 0);
        assertEquals(1, MushroomModel.rollUp(tr, 15, false), 1e-12);
        double last = 0;
        for (double t = 0; t <= 2 * tr; t += 0.05) {
            double r = MushroomModel.rollUp(t, 15, false);
            assertTrue(r >= last, t + " s");
            last = r;
        }
    }

    /**
     * On the outside, the ball is the ball itself (the entire fraction of the difference in the core at the edge, a smooth sphere), curled up, the edge mixed with air
     * ({@link MushroomModel#EDGE_HEAT}, billows in full swing); the apparent temperature is not higher than the core, without a jump, only falling from the end of the
     *  second maximum.
     */
    @Test
    void edgeCoolsAsTheBallRollsUp() {
        double tr = MushroomModel.rollUpSeconds(15, false);
        assertEquals(1, MushroomModel.edgeShare(0.2 * tr, 15, false), 0);
        assertEquals(MushroomModel.EDGE_HEAT, MushroomModel.edgeShare(tr, 15, false), 1e-12);
        assertEquals(MushroomModel.BALL_BILLOWS, MushroomModel.billows(0.2 * tr, 15, false), 0);
        assertEquals(1, MushroomModel.billows(tr, 15, false), 1e-12);
        assertEquals(FireballModel.temperature(1, 15), MushroomModel.edgeTemperature(1, 15, false), 1e-9);
        double last = Double.MAX_VALUE, tm = FireballModel.secondMaximumSeconds(15);
        for (double t = tm; t < 600; t *= 1.01) {
            double edge = MushroomModel.edgeTemperature(t, 15, false);
            assertTrue(edge <= MushroomModel.coreTemperature(t, 15, false) + 1e-9, t + " s");
            assertTrue(edge <= last, t + " s: " + edge);
            assertTrue(last == Double.MAX_VALUE || last - edge < 0.02 * last, t + " s: jump " + last + " → " + edge);
            last = edge;
        }
    }

    @Test
    void capCarriesSoilOnlyWhenTheBallTouchesTheGround() {
        double rf = FireballModel.maxRadius(2, false);
        assertEquals(1, MushroomModel.soil(2, 0, true), 0);
        assertEquals(1, MushroomModel.soil(2, 500, true), 0, "ground - always with soil");
        assertEquals(1, MushroomModel.soil(2, rf, false), 1e-12, "the ball touches the ground");
        // 2 kt on 302 m (ball 92 m): there is only condensation in the cap - when cooled it is white
        assertEquals(0, MushroomModel.soil(2, 302, false), 0);
        double last = 1;
        for (double h = 0; h <= 3 * rf; h += rf / 16) {
            double s = MushroomModel.soil(2, h, false);
            assertTrue(s <= last, "higher - no more soil: " + h);
            last = s;
        }
    }

    /**
     * The light of the ball and the cap around ({@link MushroomModel#light}): at the surface - the brightness of the black body of its apparent temperature,
     *  further - like (R/d)²; in the daytime, after a second, the ball 15 kt in 5 km shines brighter than the sun, and through 20 s a curled cap - already
     *  not even a fraction of a percent of the day.
     */
    @Test
    void ballLightsItsSurroundings() {
        MushroomModel.Shape s = MushroomModel.shape(1, 15, 0, true, 300);
        double surface = BlackBody.luminance(MushroomModel.edgeTemperature(1, 15, true));
        assertEquals(surface, MushroomModel.light(s, 15, true, 0), 1e-9 * surface);
        assertEquals(surface / 4, MushroomModel.light(s, 15, true, 2 * s.capR()), 1e-9 * surface);
        assertTrue(MushroomModel.light(s, 15, true, 5_000) > 1, "1 s to 5 km: " + MushroomModel.light(s, 15, true, 5_000));
        MushroomModel.Shape late = MushroomModel.shape(20, 15, 0, true, 3_000);
        assertTrue(MushroomModel.light(late, 15, true, 2_000) < 1e-3, "20 s to 2 km: " + MushroomModel.light(late, 15, true, 2_000));
    }

    /** The core of the cap is the same ball before folding and cools down by mixing after: the temperature does not jump and only drops. */
    @Test
    void coreCoolsWithoutAJump() {
        double tr = MushroomModel.rollUpSeconds(15, false);
        assertEquals(MushroomModel.coreTemperature(tr - 1e-6, 15, false), MushroomModel.coreTemperature(tr + 1e-6, 15, false), 0.01);
        double last = Double.MAX_VALUE;
        for (double t = 0.2; t < 900; t += 0.2) {
            double k = MushroomModel.coreTemperature(t, 15, false);
            assertTrue(k <= last && k > MushroomModel.AMBIENT_K, t + " s: " + k);
            last = k;
        }
    }

    /**
     * At night the 15 kt core glows for minutes: bright after one minute (brighter than a white wall at noon,
     * with eye adaptation), still visible after two and a half, invisible after ten. At 1 kt it fades sooner.
     */
    @Test
    void coreGlowsForMinutesAtNight() {
        assertTrue(night(60, 15) > 1, "60 s: " + night(60, 15));
        assertTrue(night(150, 15) > 0.05, "150 s: " + night(150, 15));
        assertTrue(night(600, 15) < 0.01, "600 s: " + night(600, 15));
        assertTrue(night(60, 1) < 0.05 * night(60, 15), "1 kt in a minute: " + night(60, 1));
    }

    private static double night(double t, double yieldKt) {
        return BlackBody.luminance(MushroomModel.coreTemperature(t, yieldKt, false)) * NIGHT;
    }

    /**
     * Mushroom: before detonation and after dispersal it is not present; cap - first the fireball itself (the sphere of its radius at its height,
     *  opaque), the ring grows from it; The stem enters the recess under the dome, but not higher than the middle of the ring (for the ground one - from
     *  from the very beginning: it is the trail of the ball; for the airborne one, the column of dust catches up with the cap later); the skirt is no further along the front of the ground.
     */
    @Test
    void shapeFollowsTheCloud() {
        assertNull(MushroomModel.shape(0, 15, 0, true, 0));
        assertNull(MushroomModel.shape(MushroomModel.lifeSeconds(15) + 1, 15, 0, true, 10_000));
        MushroomModel.Shape early = MushroomModel.shape(0.5, 15, 0, true, 300);
        assertNotNull(early);
        assertEquals(0, early.ringR(), 0);
        assertEquals(FireballModel.radius(0.5, 15, true), early.capR(), 1e-9);
        assertEquals(FireballModel.radius(0.5, 15, true), early.ringB(), 1e-9);
        assertEquals(FireballModel.centreHeight(0.5, 0, 15, true), early.capY(), 1e-9);
        // ball - hot gas without droplets, opaque with its NO₂: across in green thickness more than five
        assertEquals(0, early.capDrops(), 0);
        assertTrue(MushroomModel.capAbsorption(early, 1) * 2 * early.capR() > 5, "the ball is transparent");
        assertTrue(early.domeY() + early.domeH() < early.capY() + early.ringB(), "dome inside the ball");
        for (double t = 1; t < MushroomModel.lifeSeconds(15); t += 3) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 2_000);
            assertNotNull(s, t + " s");
            assertTrue(s.stemTop() <= s.capY() + 1e-9, t + " s: stem above the middle of the ring");
            assertTrue(s.stemTop() >= s.capBottom() - 1e-9, t + " s: the ground stem did not reach the cap");
            assertTrue(s.skirtR() + s.skirtW() <= 2_000 + 1e-9, t + " s: skirt further than front");
            assertTrue(s.ringR() + s.ringA() <= s.capR() + 1e-9, t + " s: ring wider than cap");
            assertTrue(s.top() >= s.capY(), t + " s");
        }
        // near the air, a column of dust rises from the ground and catches up with the cap later
        double high = 4 * FireballModel.maxRadius(15, false);
        MushroomModel.Shape air = MushroomModel.shape(5, 15, high, false, 2_000);
        assertTrue(air.stemTop() < air.capBottom(), "air: stem at the cap already after 5 s");
        MushroomModel.Shape late = MushroomModel.shape(60, 15, 0, true, 5_000);
        assertEquals(MushroomModel.RING * late.capR(), late.ringR(), 1e-9);
        double tau = late.capDrops() * 2 * Math.min(late.ringA(), late.ringB());
        assertTrue(tau > 30, "cap after a minute thick: " + tau);
    }

    /**
     * The ball becomes a cap of one shape: after the second maximum (the ball no longer grows jerkily), the shape numbers per hundredth of
     * seconds change by fractions of a percent - without a jump on the folding.
     */
    @Test
    void ballTurnsIntoTheCapWithoutAJump() {
        for (boolean surface : new boolean[]{true, false}) {
            double hob = surface ? 0 : 2 * FireballModel.maxRadius(15, false), dt = 0.01, from = 2 * FireballModel.secondMaximumSeconds(15);
            MushroomModel.Shape last = MushroomModel.shape(from, 15, hob, surface, 300);
            for (double t = from + dt; t < 60; t += dt) {
                MushroomModel.Shape s = MushroomModel.shape(t, 15, hob, surface, 300);
                double[] a = {last.capY(), last.capR(), last.ringR(), last.ringB(), last.domeY(), last.domeR(), last.domeH(), last.top()};
                double[] b = {s.capY(), s.capR(), s.ringR(), s.ringB(), s.domeY(), s.domeR(), s.domeH(), s.top()};
                for (int i = 0; i < a.length; i++) {
                    assertTrue(Math.abs(b[i] - a[i]) < 0.02 * s.capR() + 2, (surface ? "ground " : "air ") + t + " s, number " + i + ": " + a[i] + " → " + b[i]);
                }
                last = s;
            }
        }
    }

    /** The stem is widest near the ground (dust funnel) or at the cap: the mushroom frame takes its edges. */
    @Test
    void stemIsWidestAtItsEnds() {
        for (double flare : new double[]{0, 10, 40}) {
            double ends = Math.max(MushroomModel.stemRadius(0, 30, flare), MushroomModel.stemRadius(1, 30, flare));
            for (double w = 0; w <= 1; w += 0.01) assertTrue(MushroomModel.stemRadius(w, 30, flare) <= ends + 1e-9, "w = " + w);
        }
    }

    /**
     * stem in power (Glasstone and Dolan 2.17): up to 20 kt its radius is about half the radius of cap, megatons have legs
     *  in 5–10 times narrower than clouds; the average height of the stem is {@code stemR} wide. Under the ball before folding is its thin
     *  trace, curled up, the share of G&D.
     */
    @Test
    void stemFollowsGlasstone() {
        assertEquals(0.5, MushroomModel.stemShare(2), 1e-12);
        assertEquals(0.5, MushroomModel.stemShare(20), 1e-12);
        assertEquals(0.2, MushroomModel.stemShare(1000), 1e-12);
        assertEquals(0.1, MushroomModel.stemShare(30000), 1e-12);
        for (double y = 1; y < 1e5; y *= 1.3) assertTrue(MushroomModel.stemShare(y * 1.3) <= MushroomModel.stemShare(y) + 1e-12, y + " kt");
        double mean = 0;
        for (int i = 0; i < 1000; i++) mean += MushroomModel.stemRadius((i + 0.5) / 1000, 1, 0) / 1000;
        assertEquals(1, mean, 1e-3, "average stem radius");
        for (double y : new double[]{1, 2, 15, 1000}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 3 * FireballModel.maxRadius(y, false), tr = MushroomModel.rollUpSeconds(y, surface);
                MushroomModel.Shape ball = MushroomModel.shape(0.2 * tr, y, hob, surface, 1e9);
                assertEquals(MushroomModel.BALL_STEM * ball.capR(), ball.stemR(), 1e-9, y + " kt: stem under the ball");
                MushroomModel.Shape s = MushroomModel.shape(CloudModel.stabilizationSeconds(y), y, hob, surface, 1e9);
                assertEquals(MushroomModel.stemShare(y) * s.capR(), s.stemR(), 1e-9, y + " kt: stem curled cap");
            }
        }
    }

    /** The skirt goes beyond the bottom of the stem with a bell until the dust settles on the ground: the wide stem of kilotons does not cover it. */
    @Test
    void skirtSticksOutOfTheStem() {
        for (double y : new double[]{1, 2, 15, 1000}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 3 * FireballModel.maxRadius(y, false);
                for (double t : new double[]{10, 30, 60, CloudModel.stabilizationSeconds(y)}) {
                    MushroomModel.Shape s = MushroomModel.shape(t, y, hob, surface, 1e9);
                    double foot = MushroomModel.stemRadius(0, s.stemR(), MushroomModel.dustFlare(s));
                    double body = MushroomModel.stemRadius(0, s.stemR(), 0);
                    assertTrue(foot <= Math.max(MushroomModel.DUST_BASE * s.capR(), body) + 1e-9, y + " kt, " + t + " s: the bell is wider than the circle of dust");
                    assertTrue(s.skirtR() + s.skirtW() > 1.2 * foot, y + " kt, " + t + " s: skirt under stem");
                }
            }
        }
    }

    /**
     * Boiling clock cap - the integral of the circulation rate from the explosion: from zero, without jumps, only forward, and to stabilization
     *  rise. The revolution of the cap 15 kt at the 20 second is 15–35 s (the substance bypasses the cross section of the ring at the rate of rise), then
     *  slower; over the entire life - several revolutions, for 1 and 15 kt - the same order.
     */
    @Test
    void capChurnsWithTheRise() {
        for (double kt : new double[]{1, 2, 15}) {
            MushroomModel.Churn c = MushroomModel.churn(kt, 0, true);
            assertEquals(0, c.at(0), 0);
            double last = 0, step = 0.05, fastest = 0;
            for (double t = 1e-3; t < c.end(); t += 0.01) fastest = Math.max(fastest, rate(kt, t));
            for (double t = step; t < c.end(); t += step) {
                double v = c.at(t);
                assertTrue(v >= last, kt + " kt, " + t + " s");
                // per step - no more than with the fastest access
                assertTrue(v - last <= 1.01 * fastest * step, kt + " kt, " + t + " s: " + (v - last));
                last = v;
            }
            assertEquals(c.at(c.end()), c.at(3 * c.end()), 0, kt + " kt: clock stops after the end of the table");
            assertTrue(c.at(c.end()) > 10 && c.at(c.end()) < 40, kt + " kt: " + c.at(c.end()) + " rad");
            // table - the same integral as direct addition in small steps
            double sum = 0, rate0 = rate(kt, 1e-3);
            for (double t = 0; t < 120; t += 0.01) {
                double r = rate(kt, t + 0.005);
                sum += (t == 0 ? 0.5 * (rate0 + r) : r) * 0.01;
            }
            assertEquals(sum, c.at(120), 0.01 * sum, kt + " kt: clock after 2 minutes");
        }
        double turn20 = 2 * Math.PI / rate(15, 20), turn120 = 2 * Math.PI / rate(15, 120);
        assertTrue(turn20 > 15 && turn20 < 35, "revolution at 20 second: " + turn20 + " s");
        assertTrue(turn120 > 3 * turn20, "revolution to second minute: " + turn120 + " s");
    }

    private static double rate(double kt, double t) {
        MushroomModel.Shape s = MushroomModel.shape(t, kt, 0, true, 0);
        return s == null ? 0 : MushroomModel.churnRate(s, CloudModel.riseSpeed(t, 0, kt, true));
    }

    /**
     * Substance cap. Drops: σ = 3·LWC/(2·ρw·r_e) - 0,075 1/m at 0,5 g/m³ and 10 µm, come with a ring (the ball has them no),
     *  and cap 15 kt after rise - τ ≈ 130 across the section of the ring, within 30–450 for a cumulus cloud of such a height.
     * NO₂: all that the explosion gave (share of NOx per power), - in cap volume; absorbs eight times more strongly in blue than in
     *  red (Bogumil et al. 2003).
     */
    @Test
    void capHoldsDropsAndTheNo2OfTheBurst() {
        assertEquals(0.075, MushroomModel.DROP_SIGMA, 1e-12);
        for (double kt : new double[]{1, 15, 1000}) {
            double hob = 240 * Math.cbrt(kt), tr = MushroomModel.rollUpSeconds(kt, false);
            assertEquals(0, MushroomModel.shape(0.2 * tr, kt, hob, false, 1e9).capDrops(), 0, kt + " kt: drops in a ball");
            MushroomModel.Shape rolled = MushroomModel.shape(tr, kt, hob, false, 1e9);
            assertEquals(MushroomModel.DROP_SIGMA, rolled.capDrops(), 1e-12, kt + " kt: drops in a ring");
            for (double t = 0.5; t < MushroomModel.lifeSeconds(kt); t *= 1.7) {
                MushroomModel.Shape s = MushroomModel.shape(t, kt, hob, false, 1e9);
                double molecules = s.capNo2() * MushroomModel.capVolume(s);
                assertEquals(MushroomModel.NO2_SHARE * MushroomModel.NOX_PER_KT * kt * s.life(), molecules, 1e-9 * molecules, kt + " kt, " + t + " s");
                assertTrue(MushroomModel.capAbsorption(s, 0) < MushroomModel.capAbsorption(s, 1)
                        && MushroomModel.capAbsorption(s, 1) < MushroomModel.capAbsorption(s, 2), kt + " kt, " + t + " s: NO₂ brown");
            }
        }
        assertEquals(8.1, MushroomModel.NO2_CROSS[2] / MushroomModel.NO2_CROSS[0], 0.1);
        double stab = CloudModel.stabilizationSeconds(15);
        MushroomModel.Shape s = MushroomModel.shape(stab, 15, 592, false, 1e9);
        double tau = s.capDrops() * 2 * Math.min(s.ringA(), s.ringB());
        assertTrue(tau > 100 && tau < 200, "15 kt after ascent: τ " + tau);
        // volume cap - spheroid: for a ball - its volume
        MushroomModel.Shape ball = MushroomModel.shape(0.5, 15, 592, false, 1e9);
        assertEquals(4.0 / 3 * Math.PI * Math.pow(ball.capR(), 3), MushroomModel.capVolume(ball), 1e-6 * MushroomModel.capVolume(ball));
    }

    /** The rate at which the top of a cloud rises is a derivative of its height; the ball floats up at the same speed. */
    @Test
    void riseSpeedIsTheSlopeOfTheTop() {
        for (boolean surface : new boolean[]{false, true}) {
            for (double t = 0.5; t < 600; t += 7.3) {
                double slope = (CloudModel.top(t + 1e-3, 0, 15, surface) - CloudModel.top(t - 1e-3, 0, 15, surface)) / 2e-3;
                assertEquals(slope, CloudModel.riseSpeed(t, 0, 15, surface), 1e-4 * Math.max(slope, 1), t + " s");
                double ball = (FireballModel.centreHeight(t + 1e-3, 0, 15, surface) - FireballModel.centreHeight(t - 1e-3, 0, 15, surface)) / 2e-3;
                assertEquals(slope, ball, 1e-4 * Math.max(slope, 1), t + " s: ball");
            }
        }
    }

    /**
     * The mushroom is visible while it rises and while turbulence dissipates it: pairs of particles diverge by radius cap,
     * ⟨r²⟩ = g·ε·t³ (Richardson - Obukhov). Dispersion grows with the size of the cloud, so life grows with power - everywhere
     *  from 0,1 kt to 1 Mt, although 1 kt stabilizes twice as long as 15 kt (τ “Wospa”): the previous 4 stabilizations gave 1 kt
     * 45 minutes, and 15 kt - 24. 15 kt - 27 minutes, 1 Mt - more than an hour (“about an hour or more”, Glasstone and Dolan, §2.15).
     * The first half of the dispersion the entire mushroom is visible, but towards the end it is not.
     */
    @Test
    void lifeGrowsWithYield() {
        double prev = 0;
        for (double kt = 0.1; kt <= 1000.001; kt *= 1.02) {
            double life = MushroomModel.lifeSeconds(kt), r = CloudModel.capRadius(kt), td = MushroomModel.dispersalSeconds(kt);
            assertTrue(life > prev, kt + " kt: " + life + " s after " + prev);
            assertEquals(CloudModel.stabilizationSeconds(kt) + td, life, 1e-9, kt + " kt");
            assertEquals(r * r, MushroomModel.RICHARDSON * MushroomModel.DISSIPATION * td * td * td, 1e-9 * r * r, kt + " kt");
            prev = life;
        }
        assertEquals(27 * 60, MushroomModel.lifeSeconds(15), 60, "15 kt");
        assertTrue(MushroomModel.lifeSeconds(1) < MushroomModel.lifeSeconds(15), "1 kt");
        assertTrue(MushroomModel.lifeSeconds(1000) > 3600, "1 Mt: " + MushroomModel.lifeSeconds(1000));
        for (double kt : new double[]{1, 15, 1000}) {
            double stab = CloudModel.stabilizationSeconds(kt), td = MushroomModel.dispersalSeconds(kt);
            assertEquals(1, MushroomModel.shape(stab + 0.5 * td - 1, kt, 0, true, 1e9).life(), 0, kt + " kt: scattering center");
            assertEquals(0.5, MushroomModel.shape(stab + 0.75 * td, kt, 0, true, 1e9).life(), 1e-9, kt + " kt: melting");
            assertNull(MushroomModel.shape(MushroomModel.lifeSeconds(kt) + 1e-3, kt, 0, true, 1e9), kt + " kt: dissipated");
        }
    }

    /**
     * The stem jets rise with the air that pulls the ring - a share of {@link MushroomModel#STEM_INFLOW} of the rising speed of
     * clouds: at the moment of detonation - this share of it rises to stabilization (all the way - the same share of the path of the top), and does not go with
     *  speed at the moment of detonation the entire life of the mushroom.
     */
    @Test
    void stemRisesWithTheCloud() {
        for (double kt : new double[]{1, 15, 1000}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 240 * Math.cbrt(kt), life = MushroomModel.lifeSeconds(kt);
                String at = kt + " kt, " + (surface ? "ground" : "air");
                assertEquals(0, MushroomModel.stemRise(0, hob, kt, surface), 1e-9, at);
                double v0 = MushroomModel.stemRise(1e-3, hob, kt, surface) / 1e-3, rise = CloudModel.riseSpeed(0, hob, kt, surface);
                assertEquals(MushroomModel.STEM_INFLOW * rise, v0, 1e-3 * v0, at + ": at the moment of explosion");
                double all = MushroomModel.STEM_INFLOW * (CloudModel.top(1e6, hob, kt, surface) - CloudModel.top(0, hob, kt, surface));
                assertEquals(all, MushroomModel.stemRise(life, hob, kt, surface), 0.01 * all, at + ": towards the end of life");
                double prev = 0;
                for (double t = 1; t < life; t += 10) {
                    double h = MushroomModel.stemRise(t, hob, kt, surface);
                    assertTrue(h >= prev && h <= all + 1e-9, at + ", " + t + " s: " + h);
                    prev = h;
                }
            }
        }
    }

    /**
     * Rising is the same law for a ball and a cloud: the top of the mushroom and the middle of the ring cap never fall - neither while the ball is folded into a ring (the shape goes from the ball to the cap), nor then. Air and ground blasting, 1, 15 and 1000 kt.
     */
    @Test
    void topAndRingNeverGoDown() {
        for (double kt : new double[]{1, 15, 1000}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 240 * Math.cbrt(kt), life = MushroomModel.lifeSeconds(kt);
                MushroomModel.Shape prev = null;
                for (double t = 0.005; t < life; t += t < 60 ? 0.005 : 0.5) {
                    MushroomModel.Shape s = MushroomModel.shape(t, kt, hob, surface, 1e9);
                    if (s == null) break;
                    if (prev != null) {
                        String at = kt + " kt, " + (surface ? "ground" : "air") + ", " + t + " s";
                        assertTrue(s.top() >= prev.top() - 1e-9, at + ": top down " + prev.top() + " → " + s.top());
                        assertTrue(s.capY() >= prev.capY() - 1e-9, at + ": ring dropped " + prev.capY() + " → " + s.capY());
                    }
                    prev = s;
                }
            }
        }
    }
}
