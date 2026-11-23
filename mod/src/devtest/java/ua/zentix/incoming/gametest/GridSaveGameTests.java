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

    /** Twin and lamp entries carrying different values of another mod's key: a merge would lose one; the tag stays. */
    @GameTest(template = "range", batch = "grid_save_foreign", timeoutTicks = 30)
    public static void collisionKeepsBothOwnerValues(GameTestHelper h) {
        CompoundTag states = pair(false);
        CompoundTag before = states.copy();
        h.assertTrue(ChunkSaves.relightPalette(states) == ChunkSaves.DECLINED, "collision not declined");
        h.assertTrue(states.equals(before), "two distinct owner values became " + states.get("palette"));
        h.succeed();
    }

    /** An index beyond the palette list (not vanilla's data): the packed bytes stay as another writer left them. */
    @GameTest(template = "range", batch = "grid_save_foreign", timeoutTicks = 30)
    public static void invalidIndexLeavesTheSectionAlone(GameTestHelper h) {
        for (boolean collision : new boolean[]{true, false}) {
            CompoundTag states = pair(true);
            if (!collision) states.getList("palette", Tag.TAG_COMPOUND).getCompound(0).putString("Name", "minecraft:stone");
            CompoundTag before = states.copy();
            h.assertTrue(ChunkSaves.relightPalette(states) == ChunkSaves.DECLINED, "index 2 in a two-entry palette accepted (collision " + collision + ")");
            h.assertTrue(states.equals(before), "foreign index 2 in a two-entry palette replaced: " + states);
        }
        h.succeed();
    }

    /** Sea lantern and its twin, one cell each; {@code invalid}: cell 1 holds index 2, beyond the two-entry list. */
    private static CompoundTag pair(boolean invalid) {
        CompoundTag states = new CompoundTag();
        ListTag palette = new ListTag();
        for (String id : new String[]{"minecraft:sea_lantern", "incoming:unlit_sea_lantern"}) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", id);
            if (!invalid) entry.putString("archive:owner", id.startsWith("minecraft") ? "east" : "west");
            palette.add(entry);
        }
        states.put("palette", palette);
        long[] data = new long[256];
        data[0] = invalid ? 0x21L : 0x10L;
        states.putLongArray("data", data);
        return states;
    }

    /**
     * A lamp beside its twin state in vanilla's own tag goes the full way (rebuilt from the live section): every cell
     * decodes to the lamp or its original state at eight packing widths, and the live section is untouched.
     */
    @GameTest(template = "range", batch = "grid_save_collision", timeoutTicks = 60)
    public static void collisionRebuildAcrossPackingWidths(GameTestHelper h) {
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
            if (states.size() >= 2049) break;
        }
        for (int distinct : new int[]{17, 33, 65, 129, 257, 513, 1025, 2049}) {
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
                    h.assertTrue(back.get(i & 15, i >> 8, i >> 4 & 15) == expected, "cell " + i + " changed at palette size " + distinct);
                    h.assertTrue(section.getBlockState(i & 15, i >> 8, i >> 4 & 15) == original, "live section changed");
                    verified++;
                }
            }
            h.assertTrue(verified == 4096, "missing section");
        }
        h.succeed();
    }

    /**
     * Save cost of a blacked-out city chunk: 24 sections of 40 block states with two unlit lamp kinds each. Logs vanilla's
     * serialization beside the fast path (renaming palette entries) and the full way (the lamp state also listed in each
     * section, rebuilt from the live section), per chunk. A measurement, not a check: wall time on shared machines.
     */
    @GameTest(template = "range", batch = "grid_save_cost", timeoutTicks = 200)
    public static void saveCostOfADarkCityChunk(GameTestHelper h) {
        var level = h.getLevel();
        var pos = new net.minecraft.world.level.ChunkPos(h.absolutePos(new BlockPos(7, 9, 7)));
        var lamps = new net.minecraft.world.level.block.state.BlockState[]{Blocks.SEA_LANTERN.defaultBlockState(), Blocks.GLOWSTONE.defaultBlockState()};
        var filler = new java.util.ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for (var state : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY) {
            if (!GridLights.isLit(state) && !GridLights.isUnlit(state)) filler.add(state);
            if (filler.size() >= 38) break;
        }
        long[] nanos = new long[3];
        int rounds = 200;
        for (int kind = 0; kind < 2; kind++) {
            LevelChunk chunk = new LevelChunk(level, pos);
            for (int si = 0; si < chunk.getSectionsCount(); si++) {
                var section = chunk.getSection(si);
                for (int i = 0; i < 4096; i++) {
                    var st = i % 97 == 0 ? GridLights.unlit(lamps[i % 2]) : i % 89 == 0 && kind == 1 ? lamps[i % 2] : filler.get(i % filler.size());
                    section.setBlockState(i & 15, i >> 8, i >> 4 & 15, st, false);
                }
            }
            for (int r = 0; r < rounds; r++) {
                long t0 = System.nanoTime();
                var root = ChunkSerializer.write(level, chunk);
                long t1 = System.nanoTime();
                ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, root));
                long t2 = System.nanoTime();
                if (r >= rounds / 2) {
                    if (kind == 0) nanos[0] += t1 - t0;
                    nanos[kind + 1] += t2 - t1;
                }
                h.assertFalse(root.toString().contains("incoming:unlit_"), "twin left in the save (kind " + kind + ")");
            }
        }
        int n = rounds / 2;
        Incoming.LOG.info("SAVE_PALETTE_COST per chunk: vanilla write {} us, fast path {} us ({}%), full way {} us ({}%)",
                nanos[0] / n / 1000, nanos[1] / n / 1000, 100 * nanos[1] / Math.max(1, nanos[0]), nanos[2] / n / 1000, 100 * nanos[2] / Math.max(1, nanos[0]));
        h.succeed();
    }
}
