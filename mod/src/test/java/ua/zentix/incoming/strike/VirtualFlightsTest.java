package ua.zentix.incoming.strike;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualFlightsTest {
    /**
     * MLRS missile at the target (494 221 313, heading to 517 328): its chunk (30, 19) and a point in 16 blocks ahead (31, 20) are ticking,
     *  and the chunk between them on the corner (31, 19) is not. Returning to the world, the projectile’s first step would be to leave the ticking chunks
     *  and back outside the world - and so on for each tick until the lifespan was gone.
     */
    @Test
    void cornerChunkOnTheWayKeepsProjectileOutOfWorld() {
        Vec3 pos = new Vec3(494.5, 221, 313.5);
        Vec3 forward = new Vec3(23, -12, 15).normalize();
        assertFalse(VirtualFlights.clearAhead(pos, forward, 3, (x, z) -> !(x == 31 && z == 19)));
    }

    @Test
    void wholeWayLiveLetsProjectileBack() {
        Vec3 pos = new Vec3(494.5, 221, 313.5);
        Vec3 forward = new Vec3(23, -12, 15).normalize();
        assertTrue(VirtualFlights.clearAhead(pos, forward, 3, (x, z) -> x >= 30 && x <= 31 && z >= 19 && z <= 20));
    }

    @Test
    void unliveChunkBeyondTheCheckedStretchDoesNotMatter() {
        // by +x: 16 blocks (or three steps) ahead - chunks 0 and 1; Chunk 2 is no longer needed
        assertTrue(VirtualFlights.clearAhead(new Vec3(8, 70, 8), new Vec3(1, 0, 0), 2, (x, z) -> x < 2));
        assertFalse(VirtualFlights.clearAhead(new Vec3(8, 70, 8), new Vec3(1, 0, 0), 9, (x, z) -> x < 2));
    }

    @Test
    void verticalFlightChecksOwnChunk() {
        assertTrue(VirtualFlights.clearAhead(new Vec3(8, 70, 8), new Vec3(0, -1, 0), 4, (x, z) -> x == 0 && z == 0));
    }
}
