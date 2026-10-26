package ua.zentix.incoming.client.fx;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.model.BlastLitter;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import ua.zentix.incoming.util.Air;
import static org.junit.jupiter.api.Assertions.*;

class FrontDustTest {
    @Test
    void pressurePhaseEndsTheSupplyButDustSettlesOverSeconds() {
        for (double kg : new double[]{3, 50, 150, 450}) {
            for (double r : new double[]{20, 50, 100}) {
                double phase = BlastModel.chemicalPhaseSeconds(r, kg, true);
                assertEquals(0, FrontDust.envelope(-0.01, phase));
                assertEquals(0, FrontDust.envelope(0, phase));
                assertEquals(0, FrontDust.envelope(1e-10, phase), 1e-6);
                assertTrue(FrontDust.envelope(8 * phase, phase) > 0.8, "no eight-phase deletion");
                assertTrue(FrontDust.envelope(1, phase) > 0.5, "coarse dust remains at one second");
                assertTrue(FrontDust.envelope(3, phase) > 0.15);
                double before = FrontDust.envelope(0.12, phase), after = FrontDust.envelope(0.16, phase);
                assertTrue(after / before > 0.97, "no 35 ms disappearance behind the ring");
                double dt = 1e-5, age = phase + 2;
                double derivative = (FrontDust.envelope(age + dt, phase) - FrontDust.envelope(age - dt, phase)) / (2 * dt);
                assertEquals(-BlastLitter.COVER_DUST_SETTLE / FrontDust.HEIGHT * FrontDust.envelope(age, phase), derivative, 1e-8);
            }
        }
    }

    private record Blob(double x, double y, double z, double h, double v, double dust) {}

    private static Blob emit(FrontDust.Patch p, double age, double radius) {
        Blob[] got = new Blob[1];
        FrontDust.emitPatch(p, p.arrival() + age, radius,
                (x, y, z, ax, ay, az, h, v, soot, dust, water, red, green, blue, kelvin, rise) -> got[0] = new Blob(x, y, z, h, v, dust));
        return got[0];
    }

    @Test
    void expandingAndDriftingPuffsDoNotCreateDustOrEvaporateIt() {
        FrontDust.Patch p = new FrontDust.Patch(30, 64, 0, 30, 0.06, 0.016, 2.4, 6, 0.8, 3, -2, 3);
        assertEquals(0, emit(p, -0.001, 30).dust);
        Blob a = emit(p, 0.2, 100), b = emit(p, 1.2, 500);
        assertEquals(3, b.x - a.x, 1e-12);
        assertEquals(-2, b.z - a.z, 1e-12);
        assertTrue(b.h > a.h);
        // Integrated extinction changes only by deposition, not by the expanding drawn area or a display fade.
        assertEquals(Math.exp(-BlastLitter.COVER_DUST_SETTLE / FrontDust.HEIGHT), b.dust / a.dust, 1e-12);
        assertEquals(FrontDust.COLUMN_TAU * p.area() * p.contrast() * FrontDust.envelope(0.2, p.phase()),
                a.dust * SmokeGrid.dustK(FrontDust.ALBEDO_R, FrontDust.ALBEDO_G, FrontDust.ALBEDO_B), 1e-12);
    }

    /**
     * The billows at the running edge, still compressed by the front, is not an opaque point: the thickness of one billows is no more than EDGE_TAU, it gives up the reserve of the
     *  platform as it grows, and all of it when it grows; the middle of the billows is not lower than the earth and not higher than its σ above it.
     */
    @Test
    void edgePuffsAreBoundedAndGrowFromTheGround() {
        ChemicalFront front = ChemicalFront.cached(50, true);
        double k = SmokeGrid.dustK(FrontDust.ALBEDO_R, FrontDust.ALBEDO_G, FrontDust.ALBEDO_B);
        for (double r : new double[]{20, 35, 50}) {
            double arrival = front.arrivalSeconds(r), phase = BlastModel.chemicalPhaseSeconds(r, 50, true);
            FrontDust.Patch p = new FrontDust.Patch(r, 64, 0, r, arrival, phase, 3.4, 31, 1, 0, 0, 3);
            double full = 0;
            for (double age = 1e-5; age < 0.5; age *= 1.1) {
                Blob b = emit(p, age, front.radiusAt(arrival + age));
                if (b.dust <= 0) continue;
                double tau = k * b.dust / (2 * Math.PI * b.h * b.v), most = Math.max(FrontDust.EDGE_TAU, FrontDust.fullTau(p.area(), p.contrast(), p.sigma()));
                assertTrue(tau <= most + 1e-9, r + " m, " + age + " s: billow depth " + tau);
                assertTrue(b.y - 64 >= 0 && b.y - 64 <= b.v + 1e-12, "billows stands on the ground");
                full = Math.max(full, k * b.dust / (FrontDust.COLUMN_TAU * p.area() * p.contrast() * FrontDust.envelope(age, phase)));
            }
            assertEquals(1, full, 1e-9, r + " m: grown billows carries the entire stock of the site");
        }
    }

    /**
     * Frame price: areas no larger than {@code PATCHES} (rounded by rings) for any charge, the step across the ring is not
     *  smaller than the step along; the site, whose billows are already more transparent than what is being drawn, returns an empty billows, and the source ends
     *  together with the latter - and not through 20 s settling to 10⁻⁵.
     */
    @Test
    void patchesAreFewAndStopWhenInvisible() {
        for (double kg : new double[]{3, 18, 50, 150, 450}) {
            ChemicalFront front = ChemicalFront.cached(kg, true);
            double[] ring = FrontLitter.tearRing(front, 1, 0);
            double inner = ring[0], outer = ring[1], arc = FrontDust.arc(inner, outer);
            int rings = FrontDust.rings(inner, outer), count = 0;
            double dr = (outer - inner) / rings;
            for (int j = 0; j < rings; j++) count += FrontDust.spokes(inner + dr * (j + 0.5), arc);
            assertTrue(count <= FrontDust.PATCHES, kg + " kg: " + count + " sites");
            assertTrue(dr <= arc + 1e-9 && arc >= FrontDust.STEP, kg + " kg: step across " + dr + ", along " + arc);
        }
        ChemicalFront front = ChemicalFront.cached(50, true);
        double r = 30, arrival = front.arrivalSeconds(r), phase = BlastModel.chemicalPhaseSeconds(r, 50, true);
        FrontDust.Patch p = new FrontDust.Patch(r, 64, 0, r, arrival, phase, 3.4, 31, 1, 0, 0, 3);
        double k = SmokeGrid.dustK(FrontDust.ALBEDO_R, FrontDust.ALBEDO_G, FrontDust.ALBEDO_B);
        Blob before = emit(p, p.until() - arrival - 1e-3, 1e3), after = emit(p, p.until() - arrival + 1e-3, 1e3);
        double tau = k * before.dust / (2 * Math.PI * before.h * before.v);
        assertTrue(tau > 0.5 * FrontDust.FAINTEST && tau <= FrontDust.FAINTEST * 1.01, "billows is no longer drawn at the end: " + tau);
        assertEquals(0, after.dust, "after - empty billows");
        assertTrue(p.until() < arrival + 10, "the platform is visible " + (p.until() - arrival) + " s");
    }

    /**
     * General limit: after each explosion of a salvo or series of visible areas, everyone has no more than {@code ALL_PATCHES}; thinning
     *  does not lose or create mass (Σ area × contrast is the same for each explosion) and does not open the ring: each point
     *  of the disruption ring is within 2σ from the middle of the drawn billows. As long as there is someone to thin out, everyone has dust.
     */
    @Test
    void sharedLimitHoldsWithoutLosingTheRings() {
        FrontDust.Ground grass = (bx, bz) -> new FrontDust.Cover(63, false);
        Vec3 eye = new Vec3(0, 65, 0);
        java.util.Random rnd = new java.util.Random(425);
        double[][] salvos = {{18, 40, 0}, {50, 30, 0}, {150, 12, 0}, {50, 16, 40}, {450, 6, 100}};
        for (double[] salvo : salvos) {
            double kg = salvo[0];
            int n = (int) salvo[1];
            long every = (long) salvo[2];
            ChemicalFront front = ChemicalFront.cached(kg, true);
            double[] ring = FrontLitter.tearRing(front, 1, 0);
            java.util.List<FrontDust> live = new java.util.ArrayList<>();
            java.util.Map<FrontDust, Double> mass = new java.util.HashMap<>();
            java.util.Map<FrontDust, Vec3> at = new java.util.HashMap<>();
            for (int k = 0; k < n; k++) {
                long now = k * every;
                Vec3 pos = new Vec3(150 * (rnd.nextDouble() - 0.5), 64, 150 * (rnd.nextDouble() - 0.5));
                FrontDust d = FrontDust.layout(null, pos, front, ring[0], ring[1], 0, Air.CALM, grass, now);
                assertNotNull(d);
                mass.put(d, mass(d.shown()));
                at.put(d, pos);
                FrontDust.share(live, d, now, eye);
                int total = 0;
                for (FrontDust x : live) total += x.visible(now);
                assertTrue(total <= FrontDust.ALL_PATCHES, kg + " kg × " + (k + 1) + ": visible areas " + total);
                for (FrontDust x : live) {
                    if (every == 0) assertTrue(x.visible(now) > 0, kg + " kg × " + (k + 1) + ": explosion has no dust");
                    assertEquals(mass.get(x), mass(x.shown()), 1e-9 * mass.get(x), "thinning preserves mass");
                }
            }
            for (FrontDust x : live) {
                FrontDust.Patch[] shown = x.shown();
                Vec3 c = at.get(x);
                for (double r = ring[0]; r <= ring[1]; r += (ring[1] - ring[0]) / 7) {
                    for (double a = 0; a < 2 * Math.PI; a += Math.PI / 36) {
                        double px = c.x + r * Math.cos(a), pz = c.z + r * Math.sin(a), best = Double.MAX_VALUE;
                        for (FrontDust.Patch p : shown) if (p != null) best = Math.min(best, Math.hypot(p.x() - px, p.z() - pz) / p.sigma());
                        assertTrue(best <= 2, kg + " kg: ring place in " + best + " σ from the nearest billows");
                    }
                }
            }
        }
    }

    private static double mass(FrontDust.Patch[] shown) {
        double m = 0;
        for (FrontDust.Patch p : shown) if (p != null) m += p.area() * p.contrast();
        return m;
    }

    @Test
    void puffsStayBehindTheFrontIncludingTheirLiftAndDrift() {
        ChemicalFront front = ChemicalFront.cached(50, true);
        for (double r : new double[]{20, 50, 100}) {
            double arrival = front.arrivalSeconds(r), phase = BlastModel.chemicalPhaseSeconds(r, 50, true);
            FrontDust.Patch p = new FrontDust.Patch(r, 0, 0, r, arrival, phase, 3.4, 31, 1, 5, 0, 3);
            for (double age = 1e-5; age < 0.1; age *= 1.13) {
                double radius = front.radiusAt(arrival + age);
                Blob b = emit(p, age, radius);
                if (b.dust <= 0) continue;
                assertTrue(Math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z) + 4 * Math.max(b.h, b.v) <= radius + 1e-9);
            }
        }
    }
}
