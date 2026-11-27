package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Curtain of ejection crater ({@link Ejecta}): mass is preserved, falls back in seconds, there is no soil underground. */
class EjectaTest {
    private static final double[] CHARGES = {6.4, 50, 150};

    static Ejecta of(double tnt) {
        BlastTarget t = BlastTarget.of(GroundMaterial.DIRT, tnt, 0);
        return new Ejecta(CraterModel.chargeRadius(tnt, CraterModel.Soil.DRY), t.mass(tnt) * (1 - CloudModel.DUST_LOFTED), t.matter().clods);
    }

    /**
     * seeds in the air plus fallen ones - all seeds are ejected; the jets lay exactly what is in the air; emission plus dust in the air—
     * mass crater.
     */
    @Test
    void massIsConserved() {
        for (double tnt : CHARGES) {
            BlastTarget t = BlastTarget.of(GroundMaterial.DIRT, tnt, 0);
            Ejecta e = of(tnt);
            assertEquals(t.mass(tnt), e.mass + t.mass(tnt) * CloudModel.DUST_LOFTED, 1e-6, "emission + dust - crater");
            assertEquals(e.mass * (1 - BlastTarget.Matter.DIRT.clods), e.grains, 1e-9);
            double prev = e.grains + 1e-9;
            for (double s : new double[]{0.02, 0.1, 0.3, 1, 2, 3, 6, 12}) {
                double[] sum = {0};
                e.jets(s, 77, (x, y, z, ax, ay, az, sigma, kg) -> sum[0] += kg);
                double aloft = e.grainsAloft(s, 77);
                assertEquals(aloft, sum[0], 1e-6 * e.grains, tnt + " kg, " + s + " s: jets - what is in the air");
                assertTrue(aloft <= prev, "does not arrive in the air: " + s + " s");
                assertTrue(aloft >= 0 && aloft <= e.grains + 1e-9);
                prev = aloft;
            }
            assertEquals(0, e.grainsAloft(e.life + 0.1, 77), 1e-9, "by the end of life everything on earth");
        }
    }

    /**
     * Waiting for a note: in 0,1 s Shahed, the entire ejection is still in the air (the cone grows from the crater); to 1 s the main mass fell,
     *  in the air - less than a third; nine tenths on the ground for 1–3 s for all three charges.
     */
    @Test
    void curtainFallsBackInSeconds() {
        Ejecta shahed = of(50);
        assertTrue(shahed.grainsAloft(0.1, 1) > 0.9 * shahed.grains, "0,1 s: " + shahed.grainsAloft(0.1, 1) / shahed.grains);
        assertTrue(shahed.grainsAloft(1, 1) < 0.35 * shahed.grains, "1 s: " + shahed.grainsAloft(1, 1) / shahed.grains);
        for (double tnt : CHARGES) {
            Ejecta e = of(tnt);
            assertTrue(e.settled > 0.6 && e.settled < 3, tnt + " kg: 90 % on the ground for " + e.settled + " s");
        }
    }

    /** The jets are above the ground (the fallen is not drawn below it) and no further than the place where the fastest element flew. */
    @Test
    void jetsStayAboveGroundAndWithinReach() {
        for (double tnt : CHARGES) {
            Ejecta e = of(tnt);
            double reach = e.radius + e.vMax * e.vMax / Ejecta.G;
            for (double s : new double[]{0.05, 0.2, 1, 2.5}) {
                e.jets(s, 5, (x, y, z, ax, ay, az, sigma, kg) -> {
                    assertTrue(y - Math.abs(ay) > -1e-6, "end of the stream underground: " + (y - Math.abs(ay)));
                    assertTrue(Math.hypot(x, z) + Math.hypot(ax, az) <= reach + 1e-6, "beyond the greatest range");
                    assertTrue(sigma >= 0.25 * e.radius - 1e-9);
                });
            }
        }
    }

    /** Lumps nearby and a curtain - one law: the speed at the edge of the crater is C₂√(gR), faster at the middle. */
    @Test
    void particlesAndCurtainShareTheLaw() {
        Ejecta e = of(50);
        assertEquals(Ejecta.C2 * Math.sqrt(Ejecta.G * e.radius), e.speed(1), 1e-9);
        assertTrue(e.speed(0.01) > e.speed(0.5));
        assertEquals(e.radius, e.from(1), 1e-9);
    }

    /**
     * The entire balance of destruction for each target: pieces near ({@link BlastTarget#clods}, particles) + seed curtains (near the ground,
     * {@link Ejecta#grains}) + dust in the air ({@link BlastTarget#dust}) = mass of the bowl or breach; in pieces - not the entire volume, but
     *  its share (review of the front F: the lumps took the entire volume of the bowl on top of the curtain and dust).
     */
    @Test
    void wholeMassIsSharedOnce() {
        for (double tnt : CHARGES) {
            for (GroundMaterial m : new GroundMaterial[]{GroundMaterial.DIRT, GroundMaterial.SAND, GroundMaterial.GRAVEL, GroundMaterial.STONE,
                    GroundMaterial.BRICK, GroundMaterial.WOOD}) {
                BlastTarget t = BlastTarget.of(m, tnt, 0);
                double grains = t.family() == BlastTarget.Family.SOIL ? new Ejecta(1, t.ballistic(tnt), t.matter().clods).grains : 0;
                assertEquals(t.mass(tnt), t.clods(tnt) + grains + t.dust(tnt), 1e-9 * t.mass(tnt), m + ", " + tnt + " kg");
                assertEquals(t.clods(tnt), t.clodVolume(tnt) * t.matter().density, 1e-9 * t.mass(tnt));
                assertTrue(t.clodVolume(tnt) < t.volume(tnt), m + ": in pieces - less than the entire bowl");
            }
            // slab over the void: the entire gap without dust - in pieces (there is no curtain); the small chip goes into the void behind the slab
            double r = ua.zentix.incoming.warhead.BreachModel.Material.POOR.radius(tnt, ua.zentix.incoming.warhead.BreachModel.UNTAMPED);
            BlastTarget slab = BlastTarget.of(GroundMaterial.WOOD, false, 0.5, r);
            assertEquals(BlastTarget.Family.SLAB, slab.family());
            double behind = slab.mass(tnt) * slab.matter().fines * (1 - BlastTarget.SLAB_FRONT);
            assertEquals(slab.mass(tnt), slab.clods(tnt) + slab.dust(tnt) + behind, 1e-9 * slab.mass(tnt));
        }
    }
}
