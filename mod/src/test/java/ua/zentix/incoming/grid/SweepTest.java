package ua.zentix.incoming.grid;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outage cascade build: bounded work units, exactly the chunks of covered districts, each with its district's due time. */
class SweepTest {
    @Test
    void buildsEveryCoveredChunkInBoundedUnits() {
        Outage o = new Outage(1, 1234.5, -987.25, 2000, 0, 50, Outage.NEVER, 0, -1, 600, 1500);
        for (boolean restore : new boolean[]{false, true}) {
            BlackoutWorld.Sweep s = new BlackoutWorld.Sweep(o, restore);
            while (!s.built()) assertTrue(s.buildRow() <= BlackoutWorld.Sweep.SEGMENT);
            Long2LongOpenHashMap got = new Long2LongOpenHashMap();
            got.defaultReturnValue(-1);
            for (var e : s.byDue.long2ObjectEntrySet()) {
                IntArrayList chunks = e.getValue();
                for (int i = 0; i < chunks.size(); i++) assertEquals(-1, got.put(s.chunk(chunks.getInt(i)), e.getLongKey()), "chunk twice");
            }
            int expected = 0;
            for (int x = s.minX; x <= s.maxX; x++) {
                for (int z = s.minZ; z <= s.maxZ; z++) {
                    long d = Districts.of(x, z);
                    long due = !o.covers(d) ? Outage.NEVER : restore ? o.lightAt(d) : o.darkAt(d);
                    if (due == Outage.NEVER) continue;
                    expected++;
                    assertEquals(due, got.get(ChunkPos.asLong(x, z)), "chunk " + x + " " + z + " restore " + restore);
                }
            }
            assertEquals(expected, got.size());
            assertTrue(expected > 0);
        }
    }
}
