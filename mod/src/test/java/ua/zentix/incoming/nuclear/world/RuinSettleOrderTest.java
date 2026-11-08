package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuinSettleOrderTest {
    @Test void loadsGoTileByTileSoNeighboursAreShared() {
        // ready in a scattered order (as entries become ready), taken tile by tile, row by row inside a tile
        LongAVLTreeSet ready = new LongAVLTreeSet(RuinSettle.SETTLE_ORDER);
        int t = RuinSettle.SETTLE_TILE;
        java.util.Random random = new java.util.Random(3);
        java.util.List<Long> keys = new java.util.ArrayList<>();
        for (int z = -t; z < 2 * t; z++) for (int x = -2 * t; x < t; x++) keys.add(ChunkPos.asLong(x, z));
        java.util.Collections.shuffle(keys, random);
        for (long k : keys) ready.add(k);
        // every next chunk is in the same tile as the previous one or starts the next tile
        long prev = ready.firstLong();
        assertEquals(ChunkPos.asLong(-2 * t, -t), prev);
        int tileChanges = 0, newDecodes = 0;
        java.util.Set<Long> decoded = new java.util.HashSet<>();
        for (long k : ready) {
            int x = ChunkPos.getX(k), z = ChunkPos.getZ(k);
            if (Math.floorDiv(x, t) != Math.floorDiv(ChunkPos.getX(prev), t) || Math.floorDiv(z, t) != Math.floorDiv(ChunkPos.getZ(prev), t)) tileChanges++;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (decoded.add(ChunkPos.asLong(x + dx, z + dz))) newDecodes++;
            prev = k;
        }
        assertEquals(3 * 3 - 1, tileChanges);
        // all held at once (the settlement window holds ~170): (8 + 2)² / 64 per load in a tile, less shared tile edges
        assertTrue(newDecodes < 2.0 * keys.size(), "decodes per load " + (double) newDecodes / keys.size());
    }
}
