package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastLitter;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontLitterTest {
    /**
     * Where the front tears off grass and leaves (complaint 23.10: “no ring”): at Shahed there is a ring of tens of meters, at the rocket
     *  further, at a gap high in the air - nowhere; edge - where u* behind the front falls to the threshold of the turf.
     */
    @Test
    void tearRingGrowsWithTheCharge() {
        double drone = FrontLitter.tearRange(ChemicalFront.of(BlastSource.of(ua.zentix.incoming.strike.WeaponType.DRONE, false).chargeKg(), true, 2500), 3, 0);
        double missile = FrontLitter.tearRange(ChemicalFront.of(BlastSource.of(ua.zentix.incoming.strike.WeaponType.MISSILE, false).chargeKg(), true, 2500), 6, 0);
        double lancet = FrontLitter.tearRange(ChemicalFront.of(BlastSource.of(ua.zentix.incoming.strike.WeaponType.LOITER, false).chargeKg(), true, 2500), 1, 0);
        assertTrue(lancet > 15 && lancet < 28, "Lancet: " + lancet);
        // manual: Shahed - about 50 blocks, rocket - 85, "Lancet" - 20
        assertTrue(drone > 40 && drone < 65, "shahed: " + drone);
        assertTrue(missile > 75 && missile < 95, "rocket: " + missile);
        assertEquals(0, FrontLitter.tearRange(ChemicalFront.of(20, false, 2500), 2, 150), 1e-9, "gap in 150 m");
        ChemicalFront f = ChemicalFront.of(BlastSource.of(ua.zentix.incoming.strike.WeaponType.DRONE, false).chargeKg(), true, 2500);
        assertTrue(BlastLitter.tearsCover(f.kpa(drone), 1) && !BlastLitter.tearsCover(f.kpa(drone + 1), 1));
    }

    /** Shahed's thrown shreds fly from meters per second at the edge of the ring to tens at the crater. */
    @Test
    void litterFliesFasterNearer() {
        ChemicalFront f = ChemicalFront.of(30, true, 2500);
        double edge = FrontLitter.tearRange(f, 3, 0), vt = Fragments.terminal(Fragments.Kind.LEAF, 0, 0), last = Double.MAX_VALUE;
        for (double r = 10; r <= edge; r += 5) {
            double kpa = f.kpa(r), v = BlastLitter.kick(BlastModel.windSpeed(kpa), BlastModel.chemicalPhaseSeconds(r, 30, true), vt);
            assertTrue(v < last && v > 1, r + " m: " + v);
            last = v;
        }
    }
    @Test
    void visibleLiftDoesNotAddSpeedToThePhysicalKick() {
        for (double speed : new double[]{0, 1, 10, 50}) {
            for (double lift : new double[]{0.35, 0.7}) {
                double[] kick = FrontLitter.launch(speed, lift);
                assertEquals(speed, Math.hypot(kick[0], kick[1]), 1e-10);
                assertTrue(kick[0] >= 0 && kick[1] >= 0);
            }
        }
    }

}
