package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.gametest.*;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.grid.*;

/**
 * Saving blacked-out chunks: twins go to disk as lamps, decided from the saved tag (a snapshot) alone, without losing
 * another mod's section or palette-entry data and without one unreadable section spoiling the others.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GridSaveGameTests {
    private static CompoundTag fixture(GameTestHelper h, boolean keepLamp) {
        var level = h.getLevel();
        var a = h.absolutePos(new BlockPos(7, 9, 7));
        LevelChunk chunk = new LevelChunk(level, new net.minecraft.world.level.ChunkPos(a));
        var section = chunk.getSection(chunk.getSectionIndex(a.getY()));
        var lamp = Blocks.SEA_LANTERN.defaultBlockState();
        section.setBlockState(2, 2, 2, GridLights.unlit(lamp));
        if (keepLamp) section.setBlockState(3, 2, 2, lamp);
        var root = ChunkSerializer.write(level, chunk);
        var list = root.getList("sections", Tag.TAG_COMPOUND);
        CompoundTag states = null;
        for (Tag t : list) {
            var s = (CompoundTag) t;
            if (s.getByte("Y") == (a.getY() >> 4)) states = s.getCompound("block_states");
        }
        if (states == null) throw new AssertionError("section missing");
        states.putString("anothermod:payload", "opaque section data");
        for (Tag t : states.getList("palette", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            entry.putString("anothermod:entry", entry.getString("Name"));
        }
        if (keepLamp) {
            // Canonical lamp+its twin forces a rebuild. Keep extension data on the section and the air entry.
            for (Tag t : states.getList("palette", Tag.TAG_COMPOUND)) {
                var entry = (CompoundTag) t;
                if (!entry.getString("Name").equals("minecraft:air")) entry.remove("anothermod:entry");
            }
        }
        ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, root));
        for (Tag t : list) {
            var s = (CompoundTag) t;
            if (s.getByte("Y") == (a.getY() >> 4)) return s.getCompound("block_states");
        }
        throw new AssertionError("section lost");
    }

    @GameTest(template = "range", batch = "grid_save_collision", timeoutTicks = 60)
    public static void canonicalDuplicateRebuildPreservesEveryCell(GameTestHelper h) {
        var level = h.getLevel();
        var pos = new net.minecraft.world.level.ChunkPos(h.absolutePos(new BlockPos(7, 9, 7)));
        var registry = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY;
        var codec = net.minecraft.world.level.chunk.PalettedContainer.codecRW(registry,
                net.minecraft.world.level.block.state.BlockState.CODEC,
                net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        var lamp = Blocks.SEA_LANTERN.defaultBlockState();
        var twin = GridLights.unlit(lamp);
        var states = new java.util.ArrayList<net.minecraft.world.level.block.state.BlockState>();
        states.add(lamp);
        states.add(twin);
        for (var state : registry) {
            if (!GridLights.isLit(state) && !GridLights.isUnlit(state)) states.add(state);
            if (states.size() >= 350) break;
        }
        for (int distinct : new int[]{2, 16, 200, 350}) {
            LevelChunk chunk = new LevelChunk(level, pos);
            var section = chunk.getSection(1);
            for (int i = 0; i < 4096; i++) section.setBlockState(i & 15, i >> 8, i >> 4 & 15, states.get(i % distinct), false);
            var root = ChunkSerializer.write(level, chunk);
            ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, root));
            int verified = 0;
            for (Tag t : root.getList("sections", Tag.TAG_COMPOUND)) {
                var sec = (CompoundTag) t;
                if (sec.getByte("Y") != chunk.getSectionYFromSectionIndex(1)) continue;
                var back = codec.parse(NbtOps.INSTANCE, sec.getCompound("block_states")).getOrThrow();
                for (int i = 0; i < 4096; i++) {
                    var original = states.get(i % distinct);
                    var expected = original == twin ? lamp : original;
                    h.assertTrue(back.get(i & 15, i >> 8, i >> 4 & 15) == expected,
                            "collision remap changed cell " + i + " at palette size " + distinct);
                    h.assertTrue(section.getBlockState(i & 15, i >> 8, i >> 4 & 15) == original, "live section changed");
                    verified++;
                }
            }
            h.assertTrue(verified == 4096, "missing section");
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "grid_save_foreign", timeoutTicks = 30)
    public static void malformedForeignSectionCannotInvalidateEarlierLampConversion(GameTestHelper h) {
        var level = h.getLevel();
        var a = h.absolutePos(new BlockPos(7, 9, 7));
        LevelChunk chunk = new LevelChunk(level, new net.minecraft.world.level.ChunkPos(a));
        var lamp = Blocks.SEA_LANTERN.defaultBlockState();
        int first = chunk.getSectionIndex(a.getY());
        for (int i = first; i <= first + 1; i++) {
            var section = chunk.getSection(i);
            section.setBlockState(2, 2, 2, GridLights.unlit(lamp));
            if (i != first) section.setBlockState(3, 2, 2, lamp);
        }
        var tag = ChunkSerializer.write(level, chunk);
        tag.putBoolean("isLightOn", true);
        var sections = tag.getList("sections", Tag.TAG_COMPOUND);
        CompoundTag earlier = null;
        for (Tag t : sections) {
            var sec = (CompoundTag) t;
            if (sec.getByte("Y") == (a.getY() >> 4)) earlier = sec;
            if (sec.getByte("Y") == (a.getY() >> 4) + 1) sec.getCompound("block_states").remove("data");
        }
        ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, tag));
        boolean converted = earlier != null && earlier.getCompound("block_states").get("palette").toString().contains("minecraft:sea_lantern");
        h.assertFalse(converted && tag.getBoolean("isLightOn"), "earlier section converted with old light marked valid");
        h.succeed();
    }

    @GameTest(template = "range", batch = "grid_save_foreign", timeoutTicks = 30)
    public static void preserveForeignSectionAndAirOnCollision(GameTestHelper h) {
        CompoundTag after = fixture(h, true);
        h.assertTrue(after.contains("anothermod:payload") && after.toString().contains("anothermod:entry"),
                "foreign data lost on duplicate rebuild: " + after.getAllKeys() + " " + after.get("palette"));
        h.assertFalse(after.toString().contains("incoming:unlit_"), "twin left on disk: " + after.get("palette"));
        h.succeed();
    }

    @GameTest(template = "range", batch = "grid_save_foreign", timeoutTicks = 30)
    public static void preserveForeignEntryOnRename(GameTestHelper h) {
        CompoundTag after = fixture(h, false);
        for (Tag t : after.getList("palette", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) t;
            if (entry.getString("Name").equals("minecraft:sea_lantern")) {
                h.assertTrue(entry.contains("anothermod:entry"), "foreign lamp metadata lost: " + entry);
                h.succeed();
                return;
            }
        }
        h.fail("lamp missing");
    }

    @GameTest(template = "range", batch = "grid_save_foreign", timeoutTicks = 30)
    public static void finishSavingSnapshotAfterLiveRelight(GameTestHelper h) {
        var level = h.getLevel();
        var a = h.absolutePos(new BlockPos(7, 9, 7));
        LevelChunk chunk = new LevelChunk(level, new net.minecraft.world.level.ChunkPos(a));
        var section = chunk.getSection(chunk.getSectionIndex(a.getY()));
        var lamp = Blocks.SEA_LANTERN.defaultBlockState();
        section.setBlockState(2, 2, 2, GridLights.unlit(lamp));
        var snapshot = ChunkSerializer.write(level, chunk);
        section.setBlockState(2, 2, 2, lamp);
        ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, snapshot));
        h.assertFalse(snapshot.getList("sections", Tag.TAG_COMPOUND).toString().contains("incoming:unlit_sea_lantern"),
                "captured snapshot still contains its unlit twin after live relight");
        h.succeed();
    }
}
