package ua.zentix.incoming.compat;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.world.FarLods;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.concurrent.atomic.AtomicReference;

/** Optional DH delivery of changed chunks that are no longer loaded. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DhSavedUpdateGameTests {
    private DhSavedUpdateGameTests() {}

    /** A mark for an unloaded chunk delivers its real saved state without loading the world chunk. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "dh_saved_update", skyAccess = true)
    public static void savedMarkSurvivesUnload(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        BlockPos changed = h.absolutePos(new BlockPos(32, 15, 32));
        level.setBlock(changed, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        var source = level.getChunkAt(changed);
        ChunkPos unloaded = new ChunkPos(source.getPos().x + 100, source.getPos().z + 100);
        var data = ChunkSerializer.write(level, source);
        data.putInt("xPos", unloaded.x);
        data.putInt("zPos", unloaded.z);
        data.putString("Status", ChunkStatus.FULL.getName());
        level.getChunkSource().chunkMap.write(unloaded, data);
        AtomicReference<ChunkAccess> delivered = new AtomicReference<>();
        DhUpdates.testSink(level, (world, copy) -> {
            if (!copy.getPos().equals(unloaded)) return;
            delivered.set(copy);
        });
        StrikeGameTests.afterTest(h, () -> DhUpdates.testSink(level, null));
        DhUpdates.mark(level, unloaded, level.getGameTime() + 5);
        h.succeedWhen(() -> {
            h.assertTrue(delivered.get() != null, "unloaded mark was discarded instead of read from its save");
            h.assertTrue(delivered.get().getBlockState(new BlockPos(unloaded.getMinBlockX() + (changed.getX() & 15),
                    changed.getY(), unloaded.getMinBlockZ() + (changed.getZ() & 15))).is(Blocks.GOLD_BLOCK), "saved update is stale");
            h.assertTrue(level.getChunkSource().getChunkNow(unloaded.x, unloaded.z) == null, "LOD refresh loaded world chunks");
        });
    }

    /** A completed older read must not outlive a later world change and save. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "dh_saved_revision", skyAccess = true)
    public static void laterMarkInvalidatesAnOlderSavedCopy(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        BlockPos changed = h.absolutePos(new BlockPos(32, 15, 32));
        level.setBlock(changed, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        var source = level.getChunkAt(changed);
        ChunkPos target = new ChunkPos(source.getPos().x + 100, source.getPos().z + 100);
        var first = ChunkSerializer.write(level, source);
        first.putInt("xPos", target.x);
        first.putInt("zPos", target.z);
        first.putString("Status", ChunkStatus.FULL.getName());
        level.getChunkSource().chunkMap.write(target, first);
        var oldRead = FarLods.savedCopy(level, target.toLong());
        AtomicReference<ChunkAccess> delivered = new AtomicReference<>();
        DhUpdates.testSink(level, (world, copy) -> {
            if (copy.getPos().equals(target)) delivered.set(copy);
        });
        StrikeGameTests.afterTest(h, () -> DhUpdates.testSink(level, null));
        boolean[] prepared = {false};
        h.onEachTick(() -> {
            if (prepared[0] || !oldRead.isDone()) return;
            h.assertTrue(oldRead.getNow(null) != null, "old disk snapshot missing");
            long due = level.getGameTime() + 5;
            DhUpdates.mark(level, target, due);
            Long2ObjectOpenHashMap<DhUpdates.SavedRead> reads = ObfuscationReflectionHelper.getPrivateValue(
                    DhUpdates.class, level.getData(ModAttachments.DH_UPDATES), "unloaded");
            reads.put(target.toLong(), new DhUpdates.SavedRead(oldRead));
            level.setBlock(changed, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            var latest = ChunkSerializer.write(level, source);
            latest.putInt("xPos", target.x);
            latest.putInt("zPos", target.z);
            latest.putString("Status", ChunkStatus.FULL.getName());
            level.getChunkSource().chunkMap.write(target, latest);
            DhUpdates.mark(level, target, due);
            prepared[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(delivered.get() != null, "latest saved revision never delivered");
            h.assertTrue(delivered.get().getBlockState(new BlockPos(target.getMinBlockX() + (changed.getX() & 15),
                    changed.getY(), target.getMinBlockZ() + (changed.getZ() & 15))).is(Blocks.DIAMOND_BLOCK),
                    "later mark reused the previous save and lost the latest world change");
            h.assertTrue(level.getChunkSource().getChunkNow(target.x, target.z) == null, "revision refresh loaded world chunks");
        });
    }

}
