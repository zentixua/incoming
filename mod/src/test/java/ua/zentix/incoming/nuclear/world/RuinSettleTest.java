package ua.zentix.incoming.nuclear.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class RuinSettleTest {
    @Test
    void scanExpandsInCircularRingsAndRestoresTheSameNextPosition() {
        Detonation d = new Detonation(1, new Vec3(-123.25, 65, 285.75), 65, 7, true,
                -10000, 0, 0, 20000, 42, 0.03f, false, 1);
        long[] order = RuinSettle.layout(d);
        var seen = new HashSet<Long>();
        double previous = -1;
        for (long key : order) {
            ChunkPos pos = new ChunkPos(key);
            double distance = NuclearPrep.horizontal(pos, d);
            assertTrue(distance + 1.0 / 16 >= previous, "scan moved back toward the burst");
            assertTrue(ScarQueue.inRange(pos, d));
            assertTrue(seen.add(key), "duplicate chunk");
            previous = distance;
        }
        int cx = (int) Math.floor(d.burst().x / 16), cz = (int) Math.floor(d.burst().z / 16);
        int r = (int) Math.ceil(d.ruinRadius() / 16) + 1;
        for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++)
            assertEquals(ScarQueue.inRange(new ChunkPos(x, z), d), seen.contains(ChunkPos.asLong(x, z)));
        RuinProgress progress = new RuinProgress();
        progress.pass(1).cursor = order.length / 2;
        progress.pass(1).pending.add(order[7]);
        RuinProgress restored = RuinProgress.load(progress.save(new CompoundTag(), null), null);
        assertEquals(order[(int) progress.pass(1).cursor], RuinSettle.layout(d)[(int) restored.pass(1).cursor]);
        assertTrue(restored.pass(1).pending.contains(order[7]));
    }

    @Test
    void scanSkipsRegionsAbsentOnDisk() {
        Detonation d = new Detonation(1, new Vec3(-123.25, 65, 285.75), 65, 7, true,
                -10000, 0, 0, 20000, 42, 0.03f, false, 1);
        long[] all = RuinSettle.layout(d), east = RuinSettle.layout(d, (rx, rz) -> rx >= 0 && rz != 1);
        var kept = new HashSet<Long>();
        for (long key : east) {
            ChunkPos pos = new ChunkPos(key);
            assertTrue(pos.getRegionX() >= 0 && pos.getRegionZ() != 1, "chunk of an absent region: " + pos);
            kept.add(key);
        }
        for (long key : all) {
            ChunkPos pos = new ChunkPos(key);
            assertEquals(pos.getRegionX() >= 0 && pos.getRegionZ() != 1, kept.contains(key), "chunk " + pos);
        }
        assertTrue(east.length > 0 && east.length < all.length);
    }

    @Test
    void legacyRowCursorRestartsRatherThanSkippingRadialChunks() {
        CompoundTag old = new CompoundTag(), pass = new CompoundTag();
        pass.putLong("cursor", 12345);
        pass.putLongArray("pending", new long[]{42});
        old.put("1", pass);
        var restored = RuinProgress.load(old, null).pass(1);
        assertEquals(0, restored.cursor);
        assertFalse(restored.complete);
        assertTrue(restored.pending.contains(42));
    }

    @Test
    void concurrencyGrowsWithSpareBudgetAndBacksOffForBusyTicksOrMemory() {
        SettleWindow window = new SettleWindow();
        long room = 256L << 20;
        for (int i = 0; i < 20; i++) window.adapt(5, 30, true, true);
        int high = window.limit(room, 384);
        assertTrue(high >= 128, "fixed small concurrency prevents sustained settlement");
        window.adapt(40, 30, true, true);
        // The target may exceed the memory bound: repeated overload must still shrink below it.
        for (int i = 0; i < 10; i++) window.adapt(40, 30, false, true);
        assertTrue(window.limit(room, 384) < high);
        assertEquals(0, window.limit(0, 384));
        assertTrue(window.limit(2L << 20, 384) <= 2);
    }
}
