package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastLitter;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontLitterTest {
    /**
     * Где фронт срывает траву и листья (жалоба 23.10: «кольца никакого»): у шахеда — кольцо в десятки метров, у ракеты
     * дальше, у разрыва высоко в воздухе — нигде; край — где u* за фронтом падает до порога дёрна.
     */
    @Test
    void tearRingGrowsWithTheCharge() {
        double drone = FrontLitter.tearRange(ChemicalFront.of(BlastSource.of(ua.zentix.incoming.strike.WeaponType.DRONE, false).chargeKg(), true, 2500), 3, 0);
        double missile = FrontLitter.tearRange(ChemicalFront.of(BlastSource.of(ua.zentix.incoming.strike.WeaponType.MISSILE, false).chargeKg(), true, 2500), 6, 0);
        double lancet = FrontLitter.tearRange(ChemicalFront.of(BlastSource.of(ua.zentix.incoming.strike.WeaponType.LOITER, false).chargeKg(), true, 2500), 1, 0);
        assertTrue(lancet > 15 && lancet < 28, "Ланцет: " + lancet);
        // руководство: шахед — около 50 блоков, ракета — 85, «Ланцет» — 20
        assertTrue(drone > 40 && drone < 65, "шахед: " + drone);
        assertTrue(missile > 75 && missile < 95, "ракета: " + missile);
        assertEquals(0, FrontLitter.tearRange(ChemicalFront.of(20, false, 2500), 2, 150), 1e-9, "разрыв в 150 м");
        ChemicalFront f = ChemicalFront.of(BlastSource.of(ua.zentix.incoming.strike.WeaponType.DRONE, false).chargeKg(), true, 2500);
        assertTrue(BlastLitter.tearsCover(f.kpa(drone), 1) && !BlastLitter.tearsCover(f.kpa(drone + 1), 1));
    }

    /** Брошенные клоки у шахеда летят от метров в секунду у края кольца до десятков у воронки. */
    @Test
    void litterFliesFasterNearer() {
        ChemicalFront f = ChemicalFront.of(30, true, 2500);
        double edge = FrontLitter.tearRange(f, 3, 0), vt = Fragments.terminal(Fragments.Kind.LEAF, 0, 0), last = Double.MAX_VALUE;
        for (double r = 10; r <= edge; r += 5) {
            double kpa = f.kpa(r), v = BlastLitter.kick(BlastModel.windSpeed(kpa), BlastModel.chemicalPhaseSeconds(r, 30, true), vt);
            assertTrue(v < last && v > 1, r + " м: " + v);
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
