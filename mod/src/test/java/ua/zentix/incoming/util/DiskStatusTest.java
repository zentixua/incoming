package ua.zentix.incoming.util;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What kind of chunk is on the disk ({@link DiskStatus#of}): whole - full generation (name with or without namespace, as in old
 *  saves) without additional generation under zero; with the final blocks - and the chunk of the world 1.17 after the update (status “empty”,
 *  pre-generation from zero to the full generation of 1.17) or in the format up to 1.18; not on disk and under-generated (with or without
 *  blocks) - equally not. Chunks are like in copies of the Artem world “Newisle 2.3.0”: not on disk, beginning of structures without blocks,
 *  biomes, {@code structure_starts} without namespace.
 */
class DiskStatusTest {
    private static CompoundTag status(String status) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", status);
        return tag;
    }

    /** How the update of the world 1.17 ({@code ChunkHeightAndBiomeFix}) leaves the chunk: the status is “empty”, the purpose of regeneration. */
    private static CompoundTag retrogen(String target) {
        CompoundTag tag = status("minecraft:empty");
        CompoundTag retrogen = new CompoundTag();
        retrogen.putString("target_status", target);
        retrogen.putLongArray("missing_bedrock", new long[4]);
        tag.put("below_zero_retrogen", retrogen);
        return tag;
    }

    @Test
    void fullWithoutRetrogenIsWhole() {
        assertEquals(DiskStatus.State.WHOLE, DiskStatus.of(status("minecraft:full")));
        // old save: vanilla reads the name without namespace as minecraft:full and loads the chunk without generation
        assertEquals(DiskStatus.State.WHOLE, DiskStatus.of(status("full")), "full without namespace");
    }

    @Test
    void finishedRetrogenHasFinalBlocks() {
        // full generation 1.17 - heightmaps, after renaming the statuses - spawn
        for (String s : new String[] {"minecraft:spawn", "minecraft:heightmaps", "minecraft:light", "spawn", "heightmaps"}) {
            assertEquals(DiskStatus.State.FINAL, DiskStatus.of(retrogen(s)), s);
            assertTrue(DiskStatus.of(retrogen(s)).blocksFinal(), s);
        }
        // loaded to its neighbors and saved before the target: the blocks above zero are the same
        CompoundTag partly = retrogen("minecraft:spawn");
        partly.putString("Status", "minecraft:carvers");
        assertEquals(DiskStatus.State.FINAL, DiskStatus.of(partly), "status below the pregeneration goal");
        // the neighbors' decorations have not yet risen: they also write in this chunk
        //  a name with invalid characters (corrupted or someone else's data) - not vanilla status, not a parsing error
        for (String s : new String[] {"minecraft:features", "minecraft:carvers", "minecraft:noise", "minecraft:structure_starts", "", "Full", "minecraft:Spawn"}) {
            assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(retrogen(s)), s);
        }
    }

    @Test
    void absentAndProtoChunksAreTheSame() {
        assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(null), "not on disk");
        assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(new CompoundTag()), "header without Status");
        for (String s : new String[] {"minecraft:structure_starts", "minecraft:biomes", "minecraft:carvers", "minecraft:initialize_light", "minecraft:light",
                "minecraft:spawn", "structure_starts", "other:full", ""}) {
            assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(status(s)), s);
            assertFalse(DiskStatus.of(status(s)).blocksFinal(), s);
        }
    }

    @Test
    void oldFormatByItsStatus() {
        // format up to 1.18: Status inside Level, not on top; what comes out when loading depends on the measurement - not whole
        CompoundTag old = new CompoundTag();
        old.put("Level", status("full"));
        assertEquals(DiskStatus.State.FINAL, DiskStatus.of(old), "format up to 1.18, full generation");
        old.put("Level", status("features"));
        assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(old), "format up to 1.18, without neighboring decorations");
        old.put("Level", status("Full"));
        assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(old), "format up to 1.18, status is not understood");
    }
}
