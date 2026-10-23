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
        double drone = FrontLitter.tearRange(ChemicalFront.of(30, true, 2500), 3, 0);
        double missile = FrontLitter.tearRange(ChemicalFront.of(450, true, 2500), 6, 0);
        assertTrue(drone > 15 && drone < 80, "шахед: " + drone);
        assertTrue(missile > drone && missile < 200, "ракета: " + missile);
        assertEquals(0, FrontLitter.tearRange(ChemicalFront.of(20, false, 2500), 2, 150), 1e-9, "разрыв в 150 м");
        ChemicalFront f = ChemicalFront.of(30, true, 2500);
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
}
