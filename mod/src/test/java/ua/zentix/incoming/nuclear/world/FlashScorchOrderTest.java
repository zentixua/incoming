package ua.zentix.incoming.nuclear.world;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ThermalModel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flash layer urgent order (Artem 18.11: flash fires near ground zero sometimes came with the wave): the layer must precede
 * the ruins, so visible chunks near their deadline are worked earliest deadline first; the view's nearest-player order alone
 * put ground zero, the tightest deadline, last.
 */
class FlashScorchOrderTest {
    /** 15 kt ground burst, server wave speed 0.35. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 8, 20_000, 7, 1f, false, 0.35f);

    @Test
    void urgentPassTakesVisibleChunksEarliestDeadlineFirst() {
        boolean[] seen = {false, true, true, false, true};
        long[] deadline = {5, 90, 3, 1, 40};
        assertArrayEquals(new int[]{2, 4, 1}, FlashScorch.urgent(seen, deadline));
    }

    /** Ground zero seen from 1.5 km is first in the urgent pass, and its deadline leaves the layer room after ignition. */
    @Test
    void groundZeroSeenFromAfarComesFirst() {
        ChunkPos zero = new ChunkPos(0, 0), viewer = new ChunkPos(1500 >> 4, 0), ring = new ChunkPos(500 >> 4, 0);
        long[] deadline = {FlashScorch.deadline(GROUND, viewer), FlashScorch.deadline(GROUND, ring), FlashScorch.deadline(GROUND, zero)};
        assertArrayEquals(new int[]{2, 1, 0}, FlashScorch.urgent(new boolean[]{true, true, true}, deadline), "deadlines " + java.util.Arrays.toString(deadline));
        // the layer is written before the ruins replace the chunk (ScarQueue: one tick before wave arrival at the center)
        for (ChunkPos p : new ChunkPos[]{zero, ring, viewer}) {
            assertTrue(FlashScorch.deadline(GROUND, p) < ScarQueue.due(GROUND, p) - ScarQueue.EARLY, p.toString());
        }
        // at 500 m there is room: ignition at the chunk's nearest point comes before the deadline
        double ignite = GROUND.flashTicks(GROUND.fluence(new Vec3(ring.getMinBlockX(), 65, 8)), ThermalModel.ignite(15));
        assertTrue(GROUND.gameTime() + Math.ceil(ignite) <= FlashScorch.deadline(GROUND, ring), "ignition " + ignite + ", deadline " + deadline[1]);
    }
}
