package ua.zentix.incoming.warhead;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BunkerCoverTest {
    /** Charge GBU-57, kg TNT. */
    private static final double MOP = 2423;

    /**
     * The bomb has broken through the roof of the 1 block (top 75, height map 76) and explodes in the room below it: its move next to the charge
     *  (below the roof to the bottom of the room) and the edge of the wall above the top is not moved - the charge is shallow, the explosion breaks out.
     */
    @Test
    void shaftUnderThinRoofBreaches() {
        int[] ring = {76, 76, 66, 76, 76, 66, 76, 80};
        int top = BunkerCover.surface(ring);
        assertEquals(76, top);
        assertEquals(6, BunkerCover.depth(top, 70));
        assertTrue(new BuriedBlast(MOP, BunkerCover.depth(top, 70)).breaches());
    }

    /** Movement in open ground on the 24 block: top - ground around, not the bottom of the movement; explosion underground, no breakthrough. */
    @Test
    void shaftInOpenSoilStaysUnderground() {
        int[] ring = {64, 64, 65, 64, 63, 64, 64, 64};
        int top = BunkerCover.surface(ring);
        assertEquals(64, top);
        assertEquals(24, BunkerCover.depth(top, 40));
        assertFalse(new BuriedBlast(MOP, BunkerCover.depth(top, 40)).breaches());
    }

    /** The breakthrough boundary is the reduced depth 1 m/kg^⅓: for 2,4 t - 13,4 m; a meter shallower - a breakthrough, a meter deeper - no. */
    @Test
    void breachBoundaryIsScaledDepthOne() {
        double edge = Math.cbrt(MOP);
        assertTrue(new BuriedBlast(MOP, edge - 1).breaches());
        assertFalse(new BuriedBlast(MOP, edge + 1).breaches());
    }

    /** The ring does not change (the copy is sorted). */
    @Test
    void ringIsNotModified() {
        int[] ring = {80, 60, 70, 64, 64, 64, 64, 64};
        BunkerCover.surface(ring);
        assertEquals(80, ring[0]);
        assertEquals(60, ring[1]);
    }
}
