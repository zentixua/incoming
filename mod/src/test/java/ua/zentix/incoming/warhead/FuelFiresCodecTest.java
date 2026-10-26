package ua.zentix.incoming.warhead;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The burning fuel is preserved in peace (review FV): after restarting the server, the cells burn out within their legal time and
 *  go out - with doFireTick = false the vanilla fire without accounting would last forever.
 */
class FuelFiresCodecTest {
    @Test
    void poolsSurviveASaveWithTheirScheduleAndState() {
        FuelFires fires = new FuelFires();
        List<Long> cells = List.of(new BlockPos(10, 63, -4).asLong(), new BlockPos(11, 63, -4).asLong(), new BlockPos(10, 64, -3).asLong(),
                new BlockPos(9, 63, -4).asLong(), new BlockPos(10, 63, -5).asLong());
        fires.pools.add(new FuelFires.Pool(3, WeaponType.DRONE.id(), 123_456L, 9000, cells, 0b00100L, 0b00110L));
        var json = FuelFires.CODEC.encodeStart(JsonOps.INSTANCE, fires).getOrThrow();
        FuelFires back = FuelFires.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(1, back.pools.size());
        FuelFires.Pool a = fires.pools.getFirst(), b = back.pools.getFirst();
        assertEquals(a.id, b.id);
        assertEquals(a.weapon, b.weapon);
        assertEquals(a.blastTick, b.blastTick);
        assertEquals(a.reach, b.reach);
        assertArrayEquals(a.cells, b.cells);
        assertEquals(a.smothered, b.smothered);
        assertEquals(a.done, b.done);
        // the duration of each cell is from the weapon and the tick of the blow: the same, and the last one goes out at the time of the law
        assertArrayEquals(a.out, b.out);
        assertEquals(123_456L + Math.round(20 * WeaponType.DRONE.spec().pool().seconds()), b.out[0]);
        assertTrue(fires.state().getFirst().equals(back.state().getFirst()));
        // blank is not written
        assertTrue(new FuelFires().isEmpty());
    }
}
