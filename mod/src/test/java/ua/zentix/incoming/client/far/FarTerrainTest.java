package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FarTerrainTest {
    @Test
    void leavesDoNotReplaceTheForestFloorAndSolidsStillSupportDust() {
        // Leaves at 10–12 m, open air under them, floor at 0: the cold flow belongs on the floor.
        assertEquals(0, FarTerrain.belowCanopy(12, -64, true, y -> y > 0));
        // An actual solid at 5 m is a surface, whether stone, wood, a roof or a ledge.
        assertEquals(5, FarTerrain.belowCanopy(12, -64, true, y -> y > 5));
        assertEquals(12, FarTerrain.belowCanopy(12, -64, false, y -> y > 0));
        assertEquals(-64, FarTerrain.belowCanopy(12, -64, true, y -> true));
    }
}
