package ua.zentix.incoming.nuclear.world;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A settlement cursor counts positions of one layout: a cursor saved over another layout restarts the pass. */
class RuinProgressTest {
    private static CompoundTag pass(long cursor, boolean onDisk) {
        CompoundTag saved = new CompoundTag();
        saved.putLong("cursor", cursor);
        saved.putBoolean("radial", true);
        if (onDisk) saved.putBoolean("on_disk", true);
        CompoundTag tag = new CompoundTag();
        tag.put("7", saved);
        return tag;
    }

    @Test
    void cursorOverEveryCirclePositionRestarts() {
        // 3.10.4 counted every position of the circle; the layout now holds only region files on disk
        assertEquals(0, RuinProgress.load(pass(40000, false), null).pass(7).cursor);
    }

    @Test
    void cursorOverRegionFilesSurvivesSaveAndLoad() {
        RuinProgress p = RuinProgress.load(pass(40000, true), null);
        assertEquals(40000, p.pass(7).cursor);
        CompoundTag again = p.save(new CompoundTag(), null);
        assertTrue(again.getCompound("7").getBoolean("on_disk"));
        assertEquals(40000, RuinProgress.load(again, null).pass(7).cursor);
    }
}
