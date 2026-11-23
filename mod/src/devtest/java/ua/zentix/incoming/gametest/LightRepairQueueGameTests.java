package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.RegionFiles;
import ua.zentix.incoming.work.BlockLighting;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Actual disk loads and unloads; a moving FakePlayer supplies priority, fixture tickets supply its view. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightRepairQueueGameTests {
    private static final TicketType<ChunkPos> VIEW = TicketType.create("incoming_light_repair_view", Comparator.comparingLong(ChunkPos::toLong));
    private LightRepairQueueGameTests() {}

    @GameTest(template = "range", batch = "light_repair_moving", timeoutTicks = 3000, skyAccess = true)
    public static void movingPlayerDoesNotRetainOldScars(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.END);
        h.assertTrue(level != null, "End dimension missing");
        BlockLighting.finish(level);
        var lighting = BlockLighting.get(level);
        var source = level.getChunkSource();
        ChunkPos pad = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos base = new ChunkPos(pad.x + 4096, pad.z + 4096);
        List<ChunkPos> scars = new ArrayList<>();
        List<CompletableFuture<?>> writes = new ArrayList<>();
        for (int z = -2; z < 12; z++) for (int x = -2; x < 22; x++) {
            ChunkPos pos = new ChunkPos(base.x + x, base.z + z);
            LevelChunk chunk = new LevelChunk(level, pos);
            chunk.getSection(chunk.getSectionIndex(98)).setBlockState(8, 98 & 15, 8, Blocks.STONE.defaultBlockState(), false);
            boolean scar = x >= 0 && x < 20 && z >= 0 && z < 10;
            if (scar) { scars.add(pos); chunk.setData(ModAttachments.CHUNK_SCAR, 1); }
            else chunk.setData(ModAttachments.LIGHT_REPAIRED, true);
            chunk.setLightCorrect(true);
            CompoundTag tag = ChunkSerializer.write(level, chunk);
            if (scar) {
                var sections = tag.getList("sections", Tag.TAG_COMPOUND);
                for (int i = 0; i < sections.size(); i++) if (sections.getCompound(i).getByte("Y") == 6) {
                    byte[] data = new byte[2048];
                    int index = (100 & 15) * 256 + 8 * 16 + 8;
                    data[index >> 1] = (byte) (15 << ((index & 1) * 4));
                    sections.getCompound(i).putByteArray("BlockLight", data);
                }
            }
            tag.putBoolean("isLightOn", true);
            writes.add(RegionFiles.write(level, pos, tag));
        }
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "light-moving"));
        player.moveTo(base.getMiddleBlockX(), 101, base.getMiddleBlockZ());
        level.players().add(player);
        WorkScheduler.useImpactClock(level.getServer(), WorkClock.counting(1_000_000_000L));
        ChunkPos[] later = {null};
        long[] admittedBeforeReload = {0};
        int[] observed = {0, 0};
        StrikeGameTests.afterTest(h, () -> {
            level.players().remove(player);
            WorkScheduler.useImpactClock(level.getServer(), WorkScheduler.newImpactClock());
            for (ChunkPos p : scars) source.removeRegionTicket(VIEW, p, 0, p);
        });
        h.onEachTick(() -> {
            observed[0] = Math.max(observed[0], lighting.heldChunks());
            observed[1] = Math.max(observed[1], lighting.pendingRepairs());
            h.assertTrue(lighting.heldChunks() <= 9, "repair retained more than one ring: " + lighting.heldChunks());
            h.assertTrue(lighting.activeRepairs() <= 1 && lighting.pendingRepairs() <= BlockLighting.PENDING_REPAIRS,
                    "unbounded repair admission: " + lighting.pending(level));
        });
        var written = CompletableFuture.allOf(writes.toArray(CompletableFuture<?>[]::new));
        h.startSequence().thenWaitUntil(() -> h.assertTrue(written.isDone(), "fixture writes pending"))
                .thenExecute(() -> {
                    // Two hundred old scars arrive faster than a one-unit counting lane can repair them.
                    for (ChunkPos p : scars) source.addRegionTicket(VIEW, p, 0, p);
                }).thenWaitUntil(() -> {
                    for (ChunkPos p : scars) h.assertTrue(source.getChunkNow(p.x, p.z) != null, "view loading");
                }).thenExecute(() -> {
                    h.assertTrue(lighting.measurements().peakPending() == BlockLighting.PENDING_REPAIRS,
                            "fixture did not exercise pending capacity: " + lighting.measurements());
                    h.assertTrue(lighting.measurements().droppedCapacity() > 0, "fixture did not overflow capacity");
                    // Move across the old zone, then leave it. Pending coordinates must own no view tickets.
                    player.moveTo(base.getMaxBlockX() + 19 * 16, 101, base.getMaxBlockZ() + 9 * 16);
                    admittedBeforeReload[0] = lighting.measurements().admitted();
                    WorkScheduler.useImpactClock(level.getServer(), WorkScheduler.newImpactClock());
                }).thenWaitUntil(() -> h.assertTrue(lighting.measurements().admitted() > admittedBeforeReload[0]
                        && lighting.repairCenter() != null, "next priority admission pending"))
                .thenExecute(() -> {
                    ChunkPos active = lighting.repairCenter();
                    long chosen = distance(active, player.chunkPosition());
                    for (ChunkPos p : scars) if (lighting.repairPending(p))
                        h.assertTrue(chosen <= distance(p, player.chunkPosition()), "repair ignored nearer player chunk " + p);
                    for (int i = scars.size() - 1; i >= 0; i--) {
                        ChunkPos p = scars.get(i);
                        if (Math.max(Math.abs(p.x - active.x), Math.abs(p.z - active.z)) > 2
                                && !source.getChunkNow(p.x, p.z).getData(ModAttachments.LIGHT_REPAIRED)) { later[0] = p; break; }
                    }
                    h.assertTrue(later[0] != null, "no deferred scar remains");
                    for (ChunkPos p : scars) source.removeRegionTicket(VIEW, p, 0, p);
                    source.runDistanceManagerUpdates();
                    player.moveTo(base.getMinBlockX() - 1024, 101, base.getMinBlockZ() - 1024);
                    WorkScheduler.useImpactClock(level.getServer(), WorkScheduler.newImpactClock());
                }).thenWaitUntil(() -> {
                    unloadPass(level);
                    h.assertTrue(lighting.idle() && lighting.heldChunks() == 0, "left-behind repair work retained chunks");
                    for (ChunkPos p : scars) unloaded(h, level, p);
                }).thenExecute(() -> {
                    h.assertTrue(lighting.measurements().droppedUnloaded() > 0, "unloaded pending entries were not dropped");
                    admittedBeforeReload[0] = lighting.measurements().admitted();
                    player.moveTo(later[0].getMiddleBlockX(), 101, later[0].getMiddleBlockZ());
                    source.addRegionTicket(VIEW, later[0], 0, later[0]);
                }).thenWaitUntil(() -> {
                    LevelChunk chunk = source.getChunkNow(later[0].x, later[0].z);
                    h.assertTrue(chunk != null && chunk.getData(ModAttachments.LIGHT_REPAIRED) && lighting.idle(), "later load not repaired");
                    h.assertTrue(lighting.measurements().admitted() > admittedBeforeReload[0], "deferred chunk was marked without repair");
                    h.assertTrue(level.getBrightness(LightLayer.BLOCK, ghost(later[0])) == 0, "later load retained phantom peak");
                }).thenExecute(() -> {
                    admittedBeforeReload[0] = lighting.measurements().admitted();
                    source.removeRegionTicket(VIEW, later[0], 0, later[0]);
                }).thenWaitUntil(() -> { unloadPass(level); unloaded(h, level, later[0]); })
                .thenExecute(() -> source.addRegionTicket(VIEW, later[0], 0, later[0]))
                .thenWaitUntil(() -> h.assertTrue(source.getChunkNow(later[0].x, later[0].z) != null, "second reload pending"))
                .thenIdle(5).thenExecute(() -> {
                    h.assertTrue(source.getChunkNow(later[0].x, later[0].z).getData(ModAttachments.LIGHT_REPAIRED), "marker not saved");
                    h.assertTrue(lighting.measurements().admitted() == admittedBeforeReload[0], "marked chunk repaired again");
                    Incoming.LOG.info("LIGHT_REPAIR_MOVING chunks={} observedHeld={} observedPending={} {}", scars.size(), observed[0], observed[1], lighting.measurements());
                }).thenSucceed();
    }

    private static BlockPos ghost(ChunkPos p) { return new BlockPos(p.getMinBlockX() + 8, 100, p.getMinBlockZ() + 8); }

    @GameTest(template = "range", batch = "light_repair_new", timeoutTicks = 1600, skyAccess = true)
    public static void newChunkEditsDoNotBecomeLegacyRepairs(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        ChunkPos pad = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos pos = new ChunkPos(pad.x + 8192, pad.z + 8192);
        var source = level.getChunkSource();
        var lighting = BlockLighting.get(level);
        long[] admitted = {0};
        StrikeGameTests.afterTest(h, () -> source.removeRegionTicket(VIEW, pos, 0, pos));
        source.addRegionTicket(VIEW, pos, 0, pos);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(source.getChunkNow(pos.x, pos.z) != null, "fresh chunk loading"))
                .thenExecute(() -> {
                    LevelChunk chunk = source.getChunkNow(pos.x, pos.z);
                    h.assertTrue(chunk.getData(ModAttachments.LIGHT_REPAIRED), "fresh chunk did not record current lighting");
                    BlockPos p = ghost(pos);
                    level.setBlock(p, Blocks.GLOWSTONE.defaultBlockState(), 3);
                    ua.zentix.incoming.grid.ChunkLights.applyInPlace(level, chunk, true, Integer.MAX_VALUE, 0, false,
                            new it.unimi.dsi.fastutil.longs.LongArrayList(), null);
                    ua.zentix.incoming.nuclear.world.ColumnScar.replace(level, p, chunk.getBlockState(p), Blocks.AIR.defaultBlockState());
                    chunk.setData(ModAttachments.CHUNK_SCAR, 1);
                }).thenWaitUntil(() -> h.assertTrue(lighting.idle(), "current edits pending"))
                .thenExecute(() -> {
                    admitted[0] = lighting.measurements().admitted();
                    source.removeRegionTicket(VIEW, pos, 0, pos);
                }).thenWaitUntil(() -> { unloadPass(level); unloaded(h, level, pos); })
                .thenExecute(() -> source.addRegionTicket(VIEW, pos, 0, pos))
                .thenWaitUntil(() -> h.assertTrue(source.getChunkNow(pos.x, pos.z) != null, "fresh scar reload pending"))
                .thenIdle(5).thenExecute(() -> {
                    h.assertTrue(source.getChunkNow(pos.x, pos.z).getData(ModAttachments.LIGHT_REPAIRED), "current-light marker did not persist");
                    h.assertTrue(lighting.measurements().admitted() == admitted[0] && !lighting.repairPending(pos), "fixed darkening/scar scheduled legacy repair");
                    h.assertTrue(level.getBrightness(LightLayer.BLOCK, ghost(pos)) == 0, "removed current source left light");
                }).thenSucceed();
    }

    /**
     * Legacy recovery feeds vanilla's light engine in slices: a scar chunk whose saved block light is a uniform ghost 15
     * through four sections (every cell a check) never has more than two slices of checks queued in one tick, and the
     * ghost still clears. The engine is one queue for all light, including the light tasks of chunks a moving player
     * loads synchronously; whole sections per unit queued 195,000 checks in a minute after a 1 Mt blackout and held such
     * loads 0.3–0.8 s (srvlag run 23.11).
     */
    @GameTest(template = "range", batch = "light_repair_slices", timeoutTicks = 2400, skyAccess = true)
    public static void denseLegacyRepairFeedsLightInSlices(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.END);
        h.assertTrue(level != null, "End dimension missing");
        BlockLighting.finish(level);
        var lighting = BlockLighting.get(level);
        var source = level.getChunkSource();
        ChunkPos pad = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos center = new ChunkPos(pad.x + 12288, pad.z + 12288);
        List<CompletableFuture<?>> writes = new ArrayList<>();
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) {
            ChunkPos pos = new ChunkPos(center.x + x, center.z + z);
            LevelChunk chunk = new LevelChunk(level, pos);
            chunk.getSection(chunk.getSectionIndex(98)).setBlockState(8, 98 & 15, 8, Blocks.STONE.defaultBlockState(), false);
            boolean scar = x == 0 && z == 0;
            if (scar) chunk.setData(ModAttachments.CHUNK_SCAR, 1);
            else chunk.setData(ModAttachments.LIGHT_REPAIRED, true);
            chunk.setLightCorrect(true);
            CompoundTag tag = ChunkSerializer.write(level, chunk);
            if (scar) {
                byte[] ghost = new byte[2048];
                java.util.Arrays.fill(ghost, (byte) 0xFF);
                var sections = tag.getList("sections", Tag.TAG_COMPOUND);
                for (int i = 0; i < sections.size(); i++) {
                    int y = sections.getCompound(i).getByte("Y");
                    if (y >= 7 && y <= 10) sections.getCompound(i).putByteArray("BlockLight", ghost.clone());
                }
            }
            tag.putBoolean("isLightOn", true);
            writes.add(RegionFiles.write(level, pos, tag));
        }
        WorkScheduler.useImpactClock(level.getServer(), WorkClock.counting(1_000_000_000L));
        long[] last = {-1};
        int[] peak = {0};
        StrikeGameTests.afterTest(h, () -> {
            WorkScheduler.useImpactClock(level.getServer(), WorkScheduler.newImpactClock());
            source.removeRegionTicket(VIEW, center, 0, center);
        });
        h.onEachTick(() -> {
            long now = lighting.measurements().checks();
            if (last[0] >= 0) peak[0] = (int) Math.max(peak[0], now - last[0]);
            last[0] = now;
            h.assertTrue(peak[0] <= 2 * BlockLighting.LIGHT_SLICE, "recovery queued " + peak[0] + " light checks in one tick");
        });
        var written = CompletableFuture.allOf(writes.toArray(CompletableFuture<?>[]::new));
        long[] before = {0};
        BlockPos probe = new BlockPos(center.getMinBlockX() + 8, 8 * 16 + 8, center.getMinBlockZ() + 8);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(written.isDone(), "fixture writes pending"))
                .thenExecute(() -> {
                    before[0] = lighting.measurements().checks();
                    source.addRegionTicket(VIEW, center, 0, center);
                })
                .thenWaitUntil(() -> {
                    LevelChunk chunk = source.getChunkNow(center.x, center.z);
                    h.assertTrue(chunk != null && chunk.getData(ModAttachments.LIGHT_REPAIRED) && lighting.idle(), "dense repair pending");
                })
                .thenExecute(() -> {
                    long checks = lighting.measurements().checks() - before[0];
                    h.assertTrue(checks > 8 * BlockLighting.LIGHT_SLICE, "fixture issued only " + checks + " checks");
                    h.assertTrue(level.getBrightness(LightLayer.BLOCK, probe) == 0, "ghost light remained: " + level.getBrightness(LightLayer.BLOCK, probe));
                    int slice = lighting.measurements().peakSlice();
                    h.assertTrue(slice <= BlockLighting.LIGHT_SLICE, "one slice gave the light engine " + slice + " checks");
                    Incoming.LOG.info("LIGHT_REPAIR_SLICES checks={} peakPerTick={} peakSlice={}", checks, peak[0], slice);
                }).thenSucceed();
    }

    /**
     * A section's light storage can vanish between two slices (its last block removed): the scan moves on to the next
     * stored section from its first cell, not from the row where the previous section's slice stopped. A slice stops
     * at {@link BlockLighting#LIGHT_SLICE} checks mid-row: here 255 stale cells fill section 9's first row and 256 its
     * second. The unsupported peak in section 13's first row is cleared before the chunk is marked repaired, through
     * the stop's light drain and an actual unload and reload.
     */
    @GameTest(template = "range", batch = "light_repair_cursor", timeoutTicks = 2400)
    public static void emptiedSectionDoesNotSkipTheNextSectionsFirstCells(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.END);
        h.assertTrue(level != null, "End dimension missing");
        BlockLighting.finish(level);
        var lighting = BlockLighting.get(level);
        var source = level.getChunkSource();
        var light = source.getLightEngine();
        ChunkPos pad = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos center = new ChunkPos(pad.x + 14500, pad.z + 14500);
        BlockPos stone = new BlockPos(center.getMiddleBlockX(), 159, center.getMiddleBlockZ());
        BlockPos ghost = new BlockPos(stone.getX(), 208, stone.getZ());
        List<CompletableFuture<?>> writes = new ArrayList<>();
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
            ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
            LevelChunk chunk = new LevelChunk(level, pos);
            boolean scar = pos.equals(center);
            if (scar) {
                chunk.setData(ModAttachments.CHUNK_SCAR, 1);
                for (int y : new int[]{159, 223}) chunk.getSection(chunk.getSectionIndex(y)).setBlockState(8, 15, 8, Blocks.STONE.defaultBlockState(), false);
            } else chunk.setData(ModAttachments.LIGHT_REPAIRED, true);
            chunk.setLightCorrect(true);
            CompoundTag tag = ChunkSerializer.write(level, chunk);
            if (scar) for (Tag t : tag.getList("sections", Tag.TAG_COMPOUND)) {
                CompoundTag section = (CompoundTag) t;
                byte[] data = new byte[2048];
                if (section.getByte("Y") == 9) {
                    java.util.Arrays.fill(data, 0, 256, (byte) 0xff); // 255 stale cells in the first row, 256 in the second
                    data[0] = (byte) 0xf0;
                } else if (section.getByte("Y") == 13) {
                    data[(8 * 16 + 8) / 2] = 15; // isolated unsupported peak in the first row
                } else continue;
                section.putByteArray("BlockLight", data);
            }
            tag.putBoolean("isLightOn", true);
            writes.add(RegionFiles.write(level, pos, tag));
        }
        WorkScheduler.useImpactClock(level.getServer(), WorkClock.counting(1_000_000_000L));
        StrikeGameTests.afterTest(h, () -> {
            source.removeRegionTicket(VIEW, center, 0, center);
            WorkScheduler.useImpactClock(level.getServer(), WorkScheduler.newImpactClock());
        });
        long[] before = {0};
        var written = CompletableFuture.allOf(writes.toArray(CompletableFuture<?>[]::new));
        h.startSequence().thenWaitUntil(() -> h.assertTrue(written.isDone(), "fixture writes pending"))
                .thenExecute(() -> {
                    written.join();
                    before[0] = lighting.measurements().checks();
                    source.addRegionTicket(VIEW, center, 0, center);
                })
                .thenWaitUntil(() -> h.assertTrue(lighting.measurements().checks() > before[0], "first slice outstanding"))
                .thenExecute(() -> {
                    long slice = lighting.measurements().checks() - before[0];
                    h.assertTrue(slice <= BlockLighting.LIGHT_SLICE, "first slice gave the light engine " + slice + " checks");
                    h.assertTrue(level.getBrightness(LightLayer.BLOCK, ghost) == 15 && level.getBlockState(stone).is(Blocks.STONE), "fixture did not load");
                    level.setBlock(stone, Blocks.AIR.defaultBlockState(), 3); // section 9 holds no block now: its storage goes
                    var fence = light.waitForPendingTasks(center.x, center.z);
                    long until = System.nanoTime() + 5_000_000_000L;
                    while (!fence.isDone() && System.nanoTime() < until) {
                        light.tryScheduleUpdate();
                        source.pollTask();
                        Thread.yield();
                    }
                    var data = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(net.minecraft.core.SectionPos.of(stone));
                    h.assertTrue(fence.isDone() && (data == null || data.isEmpty()), "section 9 still has light storage");
                    BlockLighting.finish(level); // the server stop's light drain
                })
                .thenWaitUntil(() -> h.assertTrue(lighting.idle() && source.getChunkNow(center.x, center.z).getData(ModAttachments.LIGHT_REPAIRED), "repair pending"))
                .thenExecute(() -> {
                    h.assertTrue(level.getBrightness(LightLayer.BLOCK, ghost) == 0, "repaired with unsupported light " + level.getBrightness(LightLayer.BLOCK, ghost));
                    h.assertTrue(lighting.measurements().peakSlice() <= BlockLighting.LIGHT_SLICE, "slice of " + lighting.measurements().peakSlice() + " checks");
                    source.removeRegionTicket(VIEW, center, 0, center);
                })
                .thenWaitUntil(() -> {
                    unloadPass(level);
                    h.assertTrue(source.chunkMap.getVisibleChunkIfPresent(center.toLong()) == null
                            && !source.chunkMap.pendingUnloads.containsKey(center.toLong()), "awaiting unload");
                })
                .thenExecute(() -> source.addRegionTicket(VIEW, center, 0, center))
                .thenWaitUntil(() -> h.assertTrue(source.getChunkNow(center.x, center.z) != null, "awaiting reload"))
                .thenExecute(() -> {
                    boolean marked = source.getChunkNow(center.x, center.z).getData(ModAttachments.LIGHT_REPAIRED);
                    int value = level.getBrightness(LightLayer.BLOCK, ghost);
                    h.assertTrue(marked && value == 0, "reloaded: repair marker " + marked + " with unsupported light " + value);
                }).thenSucceed();
    }

    private static long distance(ChunkPos a, ChunkPos b) {
        long x = (long) a.x - b.x, z = (long) a.z - b.z;
        return x * x + z * z;
    }
    private static void unloadPass(ServerLevel level) {
        int[] slots = {256};
        level.getChunkSource().tick(() -> slots[0]-- > 0, false);
    }
    private static void unloaded(GameTestHelper h, ServerLevel level, ChunkPos p) {
        var map = level.getChunkSource().chunkMap;
        h.assertTrue(map.getVisibleChunkIfPresent(p.toLong()) == null && !map.pendingUnloads.containsKey(p.toLong()), "chunk still retained " + p);
    }
}
