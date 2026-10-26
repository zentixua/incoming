package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DustHeightCacheTest {
    @Test void allStrikesShareCellsOnlyWithinTheirWorld() {
        Object world = new Object();
        var first = DustHeightCache.forWorld(world);
        var second = DustHeightCache.forWorld(world);
        assertSame(first, second);
        assertNotSame(first, DustHeightCache.forWorld(new Object()));
        assertEquals(42, first.at(1, 2, 10, 20, (x,z) -> 42));
        assertEquals(42, second.at(1, 2, 10, 20, (x,z) -> { fail("shared LOD cell was queried again"); return 0; }));
    }
    @Test void terrainCellsCacheLodMissesAndSwitchImmediatelyToLoadedSurface() {
        var cache = new DustHeightCache(); int[] reads = {0};
        Sightline.Heights lod = (x,z) -> { reads[0]++; return Double.NaN; };
        for (int tick = 0; tick < 20; tick++) for (int frame = 0; frame < 6; frame++)
            assertTrue(Double.isNaN(cache.at(1, -2, tick, 20, lod)));
        assertEquals(1, reads[0]);
        assertEquals(70, cache.at(1, -2, 19, 1, (x,z) -> 70), "newly loaded terrain supersedes cached LOD miss");
        assertEquals(65, cache.at(1, -2, 20, 1, (x,z) -> 65), "destruction is picked up the next tick");
        assertEquals(60, cache.at(1, -2, 2, 1, (x,z) -> 60), "time rewind cannot retain future terrain");
    }
}
