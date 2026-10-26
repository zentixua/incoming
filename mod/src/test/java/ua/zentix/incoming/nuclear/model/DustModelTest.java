package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shock wave dust - review laws “Blast-lofted dust” (§8) and the numbers by which it is checked: height at the front and layer
 *  (GRABLE, MET, Rosenblatt), offset above loose sand (Gaj &amp; Small), darkness in Hiroshima (Hersey, evidence).
 * Hiroshima here - 15 kt on 592 m (DS02: 16 kt on 600 m), city - {@link DustModel.Surface#REFERENCE}.
 */
class DustModelTest {
    private static final double YIELD = 15, HOB = 592, CLEAR = ThermalModel.VISIBILITY_CLEAR;

    private static DustModel.Column hiroshima(double groundM) {
        return DustModel.column(groundM, HOB, YIELD, false, CLEAR, DustModel.Surface.REFERENCE);
    }

    /**
     * The thickness above the ground, during which during the day there is night under the layer (the light on the ground is less than a thousandth of an open day), twilight (less than
     *  three hundredths): two Eddington streams in a layer of dust with an albedo of the color of the wall, the sun at 40° ({@code PallLight}; client
     *  pall test verifies the light itself).
     */
    private static final double DARK = 11, DUSK = 6;

    /** Weakening of the mixture of baskets - k = 3Q/(2ρD) by shares: ~0,11 m²/g (for the distribution MET in the review - 0,13). */
    @Test
    void mixExtinctionIsGeometric() {
        assertEquals(108, DustModel.K_MIX, 1);
        assertEquals(3 * 2 / (2 * 2650 * 40e-6), DustModel.K[2], 1e-9);
    }

    /**
     * Over loose dry sand at 43 m/s, the removal is about a kilogram per m² per second, within two times (review §8, according to Gaj
     * &amp; Small, eq. 8); the air is behind the front, the wind of which is 43 m/s (2,8 psi).
     */
    @Test
    void fluxOverLooseSand() {
        double rho = DustModel.shockedAir(Solve.decreasingRoot(kpa -> -BlastModel.windSpeed(kpa), -43, 0.01, 1000));
        for (double behind : new double[]{10, 30, 100}) {
            double f = DustModel.flux(rho, 43, behind, 0.28);
            assertTrue(f > 0.5 && f < 2, behind + " m behind the front: " + f);
        }
        // below the breakdown threshold - nothing; removal is continuous where saltation turns into suspension
        assertEquals(0, DustModel.flux(rho, 3, 10, 0.34));
        double b = DustModel.plate(20), edge = 10 * 0.34 * Math.sqrt(2) * Math.pow(b, 1.25);
        assertEquals(DustModel.flux(rho, edge * 1.0001, 20, 0.34), DustModel.flux(rho, edge * 0.9999, 20, 0.34), 1e-3 * DustModel.flux(rho, edge, 20, 0.34));
        double prev = 0;
        for (double u = 1; u < 150; u += 0.5) {
            double f = DustModel.flux(rho, u, 20, 0.34);
            assertTrue(f >= prev, u + " m/s: offset decreases");
            prev = f;
        }
    }

    /**
     * Raised per phase ({@link DustModel#soil}: the law of removal with the fact that it does not depend on the step, outside the cycle, and without steps after
     *  when the wind fell below the threshold) - the same amount {@link DustModel#flux} in the middle of the steps as according to the law itself.
     */
    @Test
    void soilIsTheFluxIntegral() {
        int checked = 0;
        for (double psi = 0.3; psi < 200; psi *= 1.3) {
            double kpa = BlastModel.kpa(psi), rho = DustModel.shockedAir(kpa), front = BlastModel.frontSpeed(kpa);
            for (double along : new double[]{0.2, 1}) {
                for (double threshold : new double[]{0.28, 0.34, 0.78, 3}) {
                    double positive = 0.3 + psi / 40, u0 = BlastModel.windSpeed(kpa) * Math.sqrt(along), want = 0, dt = positive / 200;
                    for (int i = 0; i < 200; i++) {
                        double t = (i + 0.5) * dt, x = t / positive;
                        want += DustModel.flux(rho, u0 * (1 - x) * Math.exp(-x), front * t, threshold) * dt;
                    }
                    assertEquals(want, DustModel.soil(kpa, along, positive, threshold), 1e-12 * want, psi + " psi, " + along + ", " + threshold);
                    if (want > 0) checked++;
                }
            }
        }
        assertTrue(checked > 50, "with takeaway - " + checked);
    }

    /**
     * Grass and crops (u*ₜₕ from 3 m/s, Gaj &amp; Small §2.2): in 1,4 km (6 psi) the wind of the wave almost does not touch them - less than 2 %
     *  that loose sand gives off (hundreds of grams).
     */
    @Test
    void grassHoldsTheSoil() {
        DustModel.Surface grass = new DustModel.Surface(3, 15, 0), sand = new DustModel.Surface(0.28, 15, 0);
        DustModel.Column g = DustModel.column(1400, HOB, YIELD, false, CLEAR, grass), s = DustModel.column(1400, HOB, YIELD, false, CLEAR, sand);
        assertTrue(g.soil() < 0.02 * s.soil(), "the grass gave away " + g.soil() + " kg/m² with sand " + s.soil());
        assertTrue(s.soil() > 0.1, "loose sand gave away " + s.soil() + " kg/m²");
        assertTrue(hiroshima(1400).soil() > 0.1 && hiroshima(1400).soil() < s.soil());
    }

    /**
     * Head - layer at the front: in the first tenths of seconds - tens of meters (GRABLE: 6–25 m in 0,3–0,66 s), by the end of the positive
     *  phase - ~45 m; at the leading edge - zero, at the back it is thicker (Kuhl: ξ^(5/6)).
     */
    @Test
    void headIsTensOfMetres() {
        for (double r : new double[]{600, 1000, 1400, 2000}) {
            DustModel.Column c = hiroshima(r);
            double quarter = c.height(0.25), end = c.height(c.positive());
            assertTrue(quarter > 6 && quarter < 30, r + " m: in 0,25 s top " + quarter);
            assertTrue(end > 30 && end < 70, r + " m: by the end of the phase, top " + end);
            assertEquals(0, c.height(0));
            assertTrue(c.height(0.05) < quarter);
        }
    }

    /**
     * After seconds the layer is “several hundred feet” (Rosenblatt: 60–150 m y NTS to 4–5 s; here 58 m to 1,4 km, where phase
     * wave - 0,83 s, and 105 m in 600 m), above the heated earth grows in a minute (review §5: 120–350 m in 1 km, 80–230 m
     *  in 1,4 km) and does not fall.
     */
    @Test
    void layerRisesOverHeatedGround() {
        for (double r : new double[]{600, 1000, 1400}) {
            double h = hiroshima(r).height(5);
            assertTrue(h > 50 && h < 150, r + " m: through 5 s top " + h);
        }
        double h1 = hiroshima(1000).ceiling(), h14 = hiroshima(1400).ceiling();
        assertTrue(h1 > 120 && h1 < 350, "1 km: top " + h1);
        assertTrue(h14 > 80 && h14 < 230, "1,4 km: top " + h14);
        DustModel.Column c = hiroshima(1400);
        double prev = 0;
        for (double t = 0.01; t < 3600; t += 0.5) {
            assertTrue(c.height(t) >= prev - 1e-9, t + " s: top down");
            prev = c.height(t);
        }
    }

    /**
     * Hiroshima, Fuji in 1,42 km: “about twenty minutes in the darkened morning” - night under the layer from seconds to 20-th minute, to
     * 30-th lighter (Hersey 1946).
     */
    @Test
    void darkForTwentyMinutesAtFujii() {
        DustModel.Column c = hiroshima(1420);
        for (double t = 3; t <= 20 * 60; t += 10) assertTrue(c.depth(t) > DARK, t + " s: in 1,42 km it’s no longer night, thick " + c.depth(t));
        assertTrue(c.depth(30 * 60) < DARK, "by 30 minute no lighter: " + c.depth(30 * 60));
        assertTrue(c.depth(60 * 60) < 3, "an hour later it's still dark: " + c.depth(60 * 60));
    }

    /** Tanimoto in 3,2 km: “twilight”, the day was getting dark - not night and the first minutes were not clear. */
    @Test
    void twilightAtTanimoto() {
        DustModel.Column c = hiroshima(3200);
        for (double t = 5; t <= 180; t += 5) {
            double tau = c.depth(t);
            assertTrue(tau > 4 && tau < DARK, t + " s: in 3,2 km thickness " + tau);
        }
    }

    /** Takakura in 300 m: it is dark, but “the sky began to brighten” in minutes - the dust at the epicenter goes into the stem. */
    @Test
    void lighterWithinMinutesAtTakakura() {
        DustModel.Column c = hiroshima(300);
        assertTrue(c.depth(30) > DARK, "in 300 m it’s not dark at once");
        assertTrue(c.depth(10 * 60) < DUSK, "at 300 m after 10 minutes it is still dark: " + c.depth(600));
    }

    /** Large (400 µm) falls out in 1–3 minutes - the dark head settles (review §8.4); small (3 microns) hanging clock. */
    @Test
    void coarseFallsOutInMinutes() {
        DustModel.Column c = hiroshima(1400);
        assertTrue(c.airborne(4, 180) < 0.05, "400 µm after 3 minutes: " + c.airborne(4, 180));
        assertTrue(c.airborne(0, 3600) > 0.9, "3 µm in an hour: " + c.airborne(0, 3600));
        // what falls settles: the flow to the ground - the first minutes after more than half an hour
        assertTrue(c.fallout(60) > 3 * c.fallout(1800));
    }

    /** Nothing is raised to the front; per wave phase - that's all; all the dust is soil and debris; there are no debris where the houses stand. */
    @Test
    void liftFollowsThePositivePhase() {
        assertEquals(0, DustModel.lifted(0), 1e-12);
        assertEquals(1, DustModel.lifted(1), 1e-12);
        assertEquals(1, DustModel.lifted(5), 1e-12);
        double prev = 0;
        for (double s = 0.01; s <= 1; s += 0.01) {
            assertTrue(DustModel.lifted(s) > prev);
            prev = DustModel.lifted(s);
        }
        assertEquals(4.62, DustModel.lifted(1e-4) / 1e-4, 0.01, "at the front it grows like a pressure impulse");
        DustModel.Column c = hiroshima(1400);
        assertEquals(0, c.depth(0));
        assertEquals(c.lifted(), c.depth(c.positive()), 1e-9 * c.lifted());
        assertEquals(0, DustModel.destroyed(0.5));
        assertEquals(1, DustModel.destroyed(6), 1e-12);
        // there are no debris where the pressure on the ground is below 0,6 psi (for 15 kt at 592 m - further than 6,3 km)
        assertEquals(0, hiroshima(HobBlast.rangeFor(DustModel.DEBRIS_FROM_PSI, HOB, YIELD) + 1).debris(), 1e-12);
    }

    /** More power means a wider and higher layer: the same shape in terms of reduced range, the top is like ∛Y. */
    @Test
    void largerYieldWiderDust() {
        double ys = Math.cbrt(1000 / YIELD);
        DustModel.Column small = hiroshima(1400), big = DustModel.column(1400 * ys, HOB * ys, 1000, false, CLEAR, DustModel.Surface.REFERENCE);
        assertTrue(big.mass() > 0.5 * small.mass() && big.mass() < 3 * small.mass(), small.mass() + " → " + big.mass());
        assertTrue(big.first() > 1.5 * small.first(), "top in wave phase " + small.first() + " → " + big.first());
        double r15 = DustModel.reach(HOB, YIELD, false, CLEAR, DustModel.Surface.REFERENCE, 0.05);
        double r1 = DustModel.reach(HOB * Math.cbrt(1 / YIELD), 1, false, CLEAR, DustModel.Surface.REFERENCE, 0.05);
        assertTrue(r15 > 4000 && r15 < 8000, "dust 15 kt to " + r15 + " m");
        assertTrue(r1 < 0.6 * r15);
        assertTrue(hiroshima(r15 * 0.99).lifted() >= 0.05 && hiroshima(r15 * 1.01).lifted() <= 0.05);
    }
}
