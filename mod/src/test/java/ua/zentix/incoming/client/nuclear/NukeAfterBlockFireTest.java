package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The firestorm stays drawn and heard where the server lights no block fire ({@code doFireTick} off at detonation,
 * {@link Detonation#blockFire}; rule 11 of docs/design/time.md §8.3). Local chunks then hold no real fire; if the client
 * compared the model with them, trust would fall to nothing within a few seconds and close the storm near every player.
 */
class NukeAfterBlockFireTest {
    private static final Detonation D = new Detonation(1, new Vec3(0, 664, 0), 64, 15, false, 0, 0f, 2.5f, 20_000, 0, 1f,
            true, 0.35f, 0L, true, true);
    /** Model fire expected in the player's chunks each measurement: well above {@link NukeAfter#TRUST_MIN}. */
    private static final double MODEL_HERE = 10 * NukeAfter.TRUST_MIN;
    /** The storm and secondaries close below this trust ({@code NukeAfter.secondaries}, {@code FireStorm.gate}). */
    private static final double CLOSED = 0.2;

    @Test
    void withoutBlockFireLocalChunksBurnTheModel() {
        Detonation off = D.withBlockFire(false);
        assertFalse(NukeAfter.realFire(off, true), "a local chunk without block fire draws the model");
        assertFalse(NukeAfter.realFire(off, false));
        assertTrue(NukeAfter.realFire(D, true), "with block fire a local chunk shows the real fire");
        assertFalse(NukeAfter.realFire(D, false));
    }

    @Test
    void trustHoldsWithoutBlockFireAndFallsWhenRealFireIsMissing() {
        // the server lit no fire in the player's chunks (seen 0) for 100 measurements
        assertEquals(1, run(D.withBlockFire(false)), 0, "no comparison without block fire: trust stays 1");
        assertTrue(run(D) < CLOSED, "with block fire and none seen the model would be distrusted (control)");
    }

    /** {@code NukeAfter.measure}'s comparison: the model in local chunks counts only where real fire shows. */
    private static double run(Detonation d) {
        double trust = 1;
        for (int i = 0; i < 100; i++) {
            double modelHere = NukeAfter.realFire(d, true) ? MODEL_HERE : 0;
            if (modelHere >= NukeAfter.TRUST_MIN) trust = NukeAfter.trust(trust, modelHere, 0);
        }
        return trust;
    }
}
