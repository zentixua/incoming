package ua.zentix.stand;

import net.minecraft.server.level.ChunkTrackingView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Camera-required chunks (Terrain.drawn): everything Sodium renders, server-view chunks with neighbours in view. */
class TerrainTest {
    @Test
    void edgeOfTheDrawDistanceIsAwaited() {
        // 100/outside: eye (800, 95, 0), range 12; chunk (61, 1) has ground ~177 m away of 192, skipped by circle r − 1
        assertTrue(Terrain.drawn(50, 0, 12, 61, 1));
        assertTrue(Terrain.drawn(50, 0, 12, 50 + 12, 0));
    }

    @Test
    void awaitedChunksAreSentAndSoAreTheirNeighbours() {
        for (int r = 2; r <= 32; r++) {
            int far = 0;
            for (int x = -r - 3; x <= r + 3; x++) {
                for (int z = -r - 3; z <= r + 3; z++) {
                    if (!Terrain.drawn(0, 0, r, x, z)) continue;
                    far = Math.max(far, Math.abs(x));
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++)
                            assertTrue(ChunkTrackingView.of(new net.minecraft.world.level.ChunkPos(0, 0), r).contains(x + dx, z + dz),
                                    "r " + r + ": " + (x + dx) + " " + (z + dz));
                }
            }
            // Wait to range edge, excluding outermost view row: it lacks a neighbour, Sodium does not render it
            assertTrue(far >= r, "r " + r + ": far " + far);
            assertFalse(Terrain.drawn(0, 0, r, r + 2, 0), "r " + r);
        }
    }

    @Test
    void distanceIsTheNearestPointLikeSodium() {
        // Eye (800, 95, 0): section (61, 4, 1), nearest point (976, 64..80, 16), ~176,7 m horizontally, within 191
        assertTrue(Terrain.near(800, 95, 0, 61, 4, 1, 191));
        assertFalse(Terrain.near(800, 95, 0, 62, 4, 1, 191));
        // Vertical distance separate: section 200 m above eye exceeds 191 for every offset
        assertFalse(Terrain.near(800, 95, 0, 50, 18, 0, 191));
        assertTrue(Terrain.near(800, 95, 0, 50, 5, 0, 1));
    }
}
