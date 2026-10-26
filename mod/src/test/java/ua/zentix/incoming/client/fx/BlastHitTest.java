package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.ShockAtBody;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Front near the body ({@link BlastHit}): concussion and debris near the camera are part of the picture of the blows. */
class BlastHitTest {
    @AfterEach
    void clear() {
        Concussion.reset();
    }

    /**
     * There are no explosion effects of their own ({@code blast_effects} = false) - there is no concussion, no debris, and the air defense gap, which goes
     *  here at any setting; with them the front, which is stunning (Shahed in 30 m), gives both.
     */
    @Test
    void blastEffectsOffMeansNoConcussionAndNoLitter() {
        ShockAtBody shock = ShockAtBody.chemical(50, true, 30);
        boolean[] litter = {false};
        assertEquals(0, BlastHit.feel(false, shock, 30, null, 0, () -> litter[0] = true));
        assertFalse(litter[0], "litter with effects turned off");
        assertEquals(0, Concussion.level(5), "concussion with effects turned off");
        double dose = BlastHit.feel(true, shock, 30, null, 0, () -> litter[0] = true);
        assertTrue(dose > 0 && litter[0], "dose " + dose);
        assertTrue(Concussion.level(5) > 0);
    }

    /** The front below the model threshold (rocket at 100 m) does not touch the screen. */
    @Test
    void weakFrontLeavesTheScreen() {
        assertEquals(0, BlastHit.feel(true, ShockAtBody.chemical(150, true, 100), 3, null, 0, () -> {}));
        assertEquals(0, Concussion.level(5));
    }
}
