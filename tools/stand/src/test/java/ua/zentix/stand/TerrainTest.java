package ua.zentix.stand;

import net.minecraft.server.level.ChunkTrackingView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Какие чанки камера ждёт (Terrain.drawn): все, что Sodium возьмёт в рендер, — чанки вида сервера с соседями в виде. */
class TerrainTest {
    @Test
    void edgeOfTheDrawDistanceIsAwaited() {
        // 100/outside: глаз (800, 95, 0), дальность 12; чанк (61, 1) — земля в ~177 м из 192, круг r − 1 его пропускал
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
            // ждём до края дальности, но не крайний ряд вида: у него соседа нет, Sodium его не рисует
            assertTrue(far >= r, "r " + r + ": far " + far);
            assertFalse(Terrain.drawn(0, 0, r, r + 2, 0), "r " + r);
        }
    }

    @Test
    void distanceIsTheNearestPointLikeSodium() {
        // глаз (800, 95, 0): секция (61, 4, 1) — ближняя точка (976, 64..80, 16): ~176,7 м по горизонтали, в 191
        assertTrue(Terrain.near(800, 95, 0, 61, 4, 1, 191));
        assertFalse(Terrain.near(800, 95, 0, 62, 4, 1, 191));
        // по высоте — отдельно: секция над глазом на 200 м вне дальности 191 при любом сдвиге
        assertFalse(Terrain.near(800, 95, 0, 50, 18, 0, 191));
        assertTrue(Terrain.near(800, 95, 0, 50, 5, 0, 1));
    }
}
