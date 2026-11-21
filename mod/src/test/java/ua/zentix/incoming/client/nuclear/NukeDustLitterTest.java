package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ground litter carried past the player by the wave starts near the ground: a standing player's eye with the spawn spread (−2…+6) gets it,
 * an eye 120 m above the forest gets none (sandbox silo take v6c: 533–678 leaves alive, ~300 above the 120 m eye, the nearest a block
 * away — ground litter spawned at the eye's height).
 */
class NukeDustLitterTest {
    @Test
    void litterStartsNearTheGround() {
        int ground = 64;
        for (double up = -2; up <= 6; up += 0.5) assertTrue(NukeDust.litterAt(ground + 1.62 + up, ground), "standing eye " + up);
        assertFalse(NukeDust.litterAt(ground + 120, ground), "an eye 120 m up");
        assertFalse(NukeDust.litterAt(ground + 20, ground), "an eye 20 m up");
    }
}
