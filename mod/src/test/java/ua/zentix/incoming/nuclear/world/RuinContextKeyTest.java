package ua.zentix.incoming.nuclear.world;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared ruin maps key chunks one to one with hashes spread over a square of chunks (RuinContext.key). */
class RuinContextKeyTest {
    @Test
    void squareOfChunksSpreadsOverHashes() {
        int side = 128;
        Set<Long> keys = new HashSet<>();
        Set<Integer> raw = new HashSet<>(), mixed = new HashSet<>();
        for (int x = -side / 2; x < side / 2; x++) for (int z = -side / 2; z < side / 2; z++) {
            long chunk = ChunkPos.asLong(x, z);
            Long key = RuinContext.key(chunk);
            keys.add(key);
            raw.add(Long.hashCode(chunk));
            mixed.add(key.hashCode());
        }
        assertEquals(side * side, keys.size(), "one to one");
        // Long.hashCode of a raw chunk key is x ^ z: 16 384 chunks share a few hundred hashes
        assertTrue(raw.size() <= 2 * side * 2, "raw hashes " + raw.size());
        assertTrue(mixed.size() > side * side * 0.99, "mixed hashes " + mixed.size());
    }
}
