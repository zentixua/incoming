package ua.zentix.incoming.client.fx.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxParticleTest {
    /**
     * billows from the chain is visible by whether it overlaps its neighbors on the screen: along the trail - always, on the side there is a young club
     *  smaller than a step (a chain of dots on the tape) - no, grown to two steps - entirely; between them - without decreasing.
     */
    @Test
    void chainPuffShowsOnlyWhereItOverlapsNeighboursOnScreen() {
        // billows MLRS at the nozzle: square 2 × 0,7, dense part 0,7 it; step - the rocket's path per tick during acceleration
        float young = 2 * FxParticle.DENSE * 0.7f, gap = 3;
        assertEquals(1, FxParticle.chainShare(young, gap, 0), "along the trail");
        assertEquals(1, FxParticle.chainShare(young, gap, 0.1f), "almost along the trail");
        assertEquals(0, FxParticle.chainShare(young, gap, 1), "side view of young billows - dot on tape");
        assertEquals(0, FxParticle.chainShare(gap, gap, 1), "width per step - clearance between billows");
        assertEquals(1, FxParticle.chainShare(2 * gap, gap, 1), "two steps wide - completely");
        assertEquals(1, FxParticle.chainShare(young, 0, 1), "without a step - not a chain");
        float last = 0;
        for (float w = 0; w <= 3 * gap; w += 0.05f) {
            float share = FxParticle.chainShare(w, gap, 1);
            assertTrue(share >= last && share <= 1, w + ": " + share);
            last = share;
        }
    }
}
