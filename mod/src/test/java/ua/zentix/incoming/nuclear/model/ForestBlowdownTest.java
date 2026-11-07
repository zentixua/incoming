package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForestBlowdownTest {
    private static final double[] YIELDS = {1, 15, 100, 250, 1000};

    /** Table 5.149: up to 90 % of trees down at the severe-damage range, about 30 % at the moderate one. */
    @Test
    void curvePassesThroughGlasstoneFractions() {
        for (double w : YIELDS) {
            for (boolean conifer : new boolean[]{true, false}) {
                ForestBlowdown.Curve c = ForestBlowdown.curve(w, conifer);
                double severe = HobBlast.psi(ForestBlowdown.surfaceRange(w, conifer, true), 0, w);
                double moderate = HobBlast.psi(ForestBlowdown.surfaceRange(w, conifer, false), 0, w);
                assertEquals(0.9, c.probability(severe), 1e-4, w + " kt");
                assertEquals(0.3, c.probability(moderate), 1e-4, w + " kt");
            }
        }
    }

    /** Fig. 5.146: drag damage reaches farther than ∛W scaling; an unimproved coniferous stand is stronger than a deciduous one. */
    @Test
    void rangeGrowsFasterThanCubeRootAndConifersAreStronger() {
        for (boolean conifer : new boolean[]{true, false}) {
            for (boolean severe : new boolean[]{true, false}) {
                double r1 = ForestBlowdown.surfaceRange(1, conifer, severe), r1000 = ForestBlowdown.surfaceRange(1000, conifer, severe);
                assertTrue(r1000 / r1 > 10 * 1.15, "range ×" + r1000 / r1 + " at a thousandfold yield");
            }
        }
        for (double w : YIELDS) {
            assertTrue(ForestBlowdown.curve(w, true).median() > ForestBlowdown.curve(w, false).median() * 1.3, w + " kt");
            // the pressure that fells trees falls with yield: a longer blast wind does the same work
            if (w > 1) assertTrue(ForestBlowdown.curve(w, false).median() < ForestBlowdown.curve(1, false).median(), w + " kt");
        }
    }

    /**
     * Zearth, 250 kt on the ground (07.11.2026): at 3–4 km (4.8–3.1 psi) a fixed 5 psi threshold left almost every tree
     * standing. Glasstone fig. 5.146: deciduous stands 90 % down to ~3.5 km and 30 % to ~4.2 km; coniferous 90 % to ~2.5 km
     * and 30 % to ~3.2 km (±20 %, the figure's accuracy).
     */
    @Test
    void zearthForestsAtThreeToFourKilometres() {
        assertEquals(3480, ForestBlowdown.surfaceRange(250, false, true), 3480 * 0.03);
        assertEquals(4160, ForestBlowdown.surfaceRange(250, false, false), 4160 * 0.03);
        assertEquals(2530, ForestBlowdown.surfaceRange(250, true, true), 2530 * 0.03);
        assertEquals(3220, ForestBlowdown.surfaceRange(250, true, false), 3220 * 0.03);
        ForestBlowdown.Curve oak = ForestBlowdown.curve(250, false), spruce = ForestBlowdown.curve(250, true);
        double at3 = HobBlast.psi(3000, 0, 250), at4 = HobBlast.psi(4000, 0, 250);
        assertTrue(oak.probability(at3) > 0.95 && oak.probability(at4) > 0.3, oak.probability(at3) + " / " + oak.probability(at4));
        assertTrue(spruce.probability(at3) > 0.4 && spruce.probability(at4) < 0.05, spruce.probability(at3) + " / " + spruce.probability(at4));
    }
}
