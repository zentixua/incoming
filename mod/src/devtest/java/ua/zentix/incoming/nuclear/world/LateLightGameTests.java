package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.BlockLightReference;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.grid.ChunkLights;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.util.RegionFiles;
import ua.zentix.incoming.warhead.LightInvariantGameTests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Real disk chunks, radius-zero loading, and a neighbor promoted only after the edit and light fence. */
@GameTestHolder(Incoming.MOD_ID)
public final class LateLightGameTests {
    private static final TicketType<ChunkPos> HOLD = TicketType.create("incoming_late_light_test", Comparator.comparingLong(ChunkPos::toLong));
    private static final List<String> PATHS = List.of("edge_flags", "ruins", "blackout", "dark_load", "dark_load_ruins", "settle", "shatter", "fragments", "staged", "crater_falls", "repair_scar", "repair_outage", "stop", "dark_load_city", "repair_external", "busy");
    private LateLightGameTests() {}

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        List<TestFunction> result = new ArrayList<>();
        for (String path : PATHS) {
            result.add(new TestFunction("light_late_" + path, "light_late." + path, "incoming:range", Rotation.NONE,
                    1600, 0, true, false, 1, 1, true, h -> run(h, path)));
        }
        return result;
    }

    private static void run(GameTestHelper h, String path) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        ChunkPos pad = new ChunkPos(h.absolutePos(BlockPos.ZERO)), center = new ChunkPos(pad.x + 300 + 128 * PATHS.indexOf(path), pad.z + 300);
        ChunkPos east = new ChunkPos(center.x + 1, center.z);
        ChunkPos anchor = center;
        boolean fullRing = System.getenv("INCOMING_LIGHT_FULL_RING") != null;
        long[] promotionStart = {0};
        int[] loads = new int[4];
        boolean[] measuringLoads = {true};
        boolean fire = IncomingConfig.SERVER.nukeFires.get();
        IncomingConfig.SERVER.nukeFires.set(false);
        Outage[] outage = {null};
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.nukeFires.set(fire);
            if (path.equals("busy")) {
                ua.zentix.incoming.strike.StrikeWorld.get(level).impacts().clear(level);
                ua.zentix.incoming.work.WorkScheduler.useImpactClock(level.getServer(), ua.zentix.incoming.work.WorkScheduler.newImpactClock());
            }
            if (outage[0] != null) {
                Outage o = outage[0];
                PowerGrid.get(level).replace(new Outage(o.id(), o.x(), o.z(), o.radius(), o.start(), o.speed(), 0, 0, -1));
            }
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                ChunkPos held = new ChunkPos(center.x + dx, center.z + dz);
                level.getChunkSource().removeRegionTicket(HOLD, held, 0, held);
            }
            level.getChunkSource().removeRegionTicket(HOLD, center, 3, center);
        });
        BlockLightReference original = new BlockLightReference(level.getMinBuildHeight(), level.getHeight());
        Map<ChunkPos, LevelChunk> chunks = new LinkedHashMap<>();
        for (int z = -3; z <= 3; z++) for (int x = -3; x <= 3; x++) {
            ChunkPos pos = new ChunkPos(center.x + x, center.z + z);
            LevelChunk chunk = new LevelChunk(level, pos);
            chunks.put(pos, chunk);
            var tile = original.add(pos);
            for (int i = 0; i < tile.blocks.length; i++) {
                BlockPos p = tile.position(i);
                if (p.getY() == 98) tile.blocks[i] = Blocks.STONE.defaultBlockState();
                if (pos.equals(center) && p.getY() == 103) tile.blocks[i] = Blocks.GLASS.defaultBlockState();
                if ((pos.equals(center) || path.equals("dark_load_city")) && p.getY() == 100 && (p.getZ() & 15) == 7 && ((p.getX() & 15) == 7 || (p.getX() & 15) == 15))
                    tile.blocks[i] = Blocks.GLOWSTONE.defaultBlockState();
                if (path.equals("repair_external") && p.getY() == 100 && (p.getZ() & 15) == 7
                        && (pos.equals(east) && (p.getX() & 15) == 14 || pos.equals(new ChunkPos(center.x + 2, center.z)) && (p.getX() & 15) == 0))
                    tile.blocks[i] = Blocks.GLOWSTONE.defaultBlockState();
                if (path.equals("dark_load_city") && p.getY() == 99 && (p.getX() & 15) == 3 && (p.getZ() & 15) == 3)
                    tile.blocks[i] = Blocks.CHEST.defaultBlockState();
                if (pos.equals(center) && path.equals("crater_falls") && (p.getX() & 15) == 8 && (p.getZ() & 15) == 7) {
                    if (p.getY() == 99) tile.blocks[i] = Blocks.STONE.defaultBlockState();
                    if (p.getY() == 103) tile.blocks[i] = Blocks.AIR.defaultBlockState();
                }
                if (pos.equals(center) && path.equals("fragments") && (p.getX() & 15) == 5 && p.getY() >= 99 && p.getY() <= 102)
                    tile.blocks[i] = Blocks.GLASS.defaultBlockState();
                if (!tile.blocks[i].isAir()) chunk.getSection(chunk.getSectionIndex(p.getY())).setBlockState(p.getX() & 15, p.getY() & 15, p.getZ() & 15, tile.blocks[i], false);
            }
            if (path.equals("dark_load_city")) {
                BlockPos chest = new BlockPos(pos.getMinBlockX() + 3, 99, pos.getMinBlockZ() + 3);
                chunk.setBlockEntity(new net.minecraft.world.level.block.entity.ChestBlockEntity(chest, Blocks.CHEST.defaultBlockState()));
            }
            Heightmap.primeHeightmaps(chunk, java.util.Set.of(RuinPlan.HEIGHTMAP_TYPES));
            chunk.setLightCorrect(true);
        }
        var computed = original.engine(false);
        List<CompletableFuture<?>> writes = new ArrayList<>();
        Map<ChunkPos, CompoundTag> tags = new LinkedHashMap<>();
        for (var entry : chunks.entrySet()) {
            if (path.startsWith("repair") && entry.getKey().equals(center)) {
                for (int x : new int[]{7, 15}) entry.getValue().getSection(entry.getValue().getSectionIndex(100))
                        .setBlockState(x, 100 & 15, 7, Blocks.AIR.defaultBlockState(), false);
                if (!path.equals("repair_outage")) entry.getValue().setData(ua.zentix.incoming.registry.ModAttachments.CHUNK_SCAR, 1);
            }
            if (path.equals("repair_external") && entry.getKey().equals(east)) entry.getValue()
                    .getSection(entry.getValue().getSectionIndex(100)).setBlockState(14, 100 & 15, 7, Blocks.AIR.defaultBlockState(), false);
            CompoundTag tag = ChunkSerializer.write(level, entry.getValue());
            ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
            for (int i = 0; i < sections.size(); i++) {
                CompoundTag s = sections.getCompound(i);
                var data = computed.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(entry.getKey(), s.getByte("Y")));
                if (data != null) s.putByteArray("BlockLight", data.getData().clone());
            }
            tag.putBoolean("isLightOn", true);
            tags.put(entry.getKey(), tag);
            writes.add(RegionFiles.write(level, entry.getKey(), tag));
        }
        if (path.equals("dark_load_city")) writes.addAll(writeEntities(level, chunks.keySet()));
        Detonation d = new Detonation(ua.zentix.incoming.nuclear.NuclearEvents.get(level).nextId(), new Vec3(center.getMinBlockX() - 40, 101, center.getMinBlockZ() + 7),
                98, 15, true, level.getGameTime() - 10000, 0, 0, 20000, 42, 0.03f, false, 1);
        RuinContext ctx = new RuinContext(level, d);
        for (var entry : tags.entrySet()) {
            var read = DiskShots.parse(level, entry.getKey(), entry.getValue());
            if (path.equals("dark_load_ruins")) {
                for (var states : read.states()) if (states != null) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var twin = ua.zentix.incoming.grid.GridLights.unlit(states.get(x, y, z));
                    if (twin != null) states.set(x, y, z, twin);
                }
            }
            ctx.putDisk(level, read);
        }
        RuinContext.Grid grid = ctx.cachedGridForTest(level, center);
        RuinPlan plan = RuinPlanner.finish(grid, Collapse.solve(grid));
        boolean[] appliedOnLoad = {false};
        ua.zentix.incoming.warhead.FragmentStrike[] fragments = {null};
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> entityListener = event -> {
            if (measuringLoads[0] && event.getEntity() instanceof net.minecraft.world.entity.decoration.ArmorStand
                    && Math.abs(event.getEntity().chunkPosition().x - center.x) <= 3 && Math.abs(event.getEntity().chunkPosition().z - center.z) <= 3) loads[3]++;
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(entityListener);
        StrikeGameTests.afterTest(h, () -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(entityListener));
        java.util.function.Consumer<net.neoforged.neoforge.event.level.ChunkEvent.Load> listener = event -> {
            if (measuringLoads[0] && event.getChunk() instanceof LevelChunk full
                    && Math.abs(full.getPos().x - center.x) <= 5 && Math.abs(full.getPos().z - center.z) <= 5) {
                loads[0]++; loads[1] += full.getBlockEntities().size(); loads[2] += full.getBlockEntitiesPos().size();
            }
            if (!path.equals("dark_load_ruins") || !event.getChunk().getPos().equals(center) || appliedOnLoad[0]) return;
            LevelChunk loaded = (LevelChunk) event.getChunk();
            h.assertTrue(ua.zentix.incoming.grid.GridLights.isUnlit(loaded.getBlockState(new BlockPos(center.getMaxBlockX(), 100, center.getMinBlockZ() + 7))),
                    "onLoad fixture lamp was not darkened");
            h.assertTrue(plan.apply(level, loaded, d, new ColumnScar.Budget(false)), "onLoad ruin plan rejected");
            appliedOnLoad[0] = true;
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        StrikeGameTests.afterTest(h, () -> {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
            ua.zentix.incoming.nuclear.NuclearEvents.get(level).past().remove(d);
            if (path.equals("settle")) NuclearWorld.get(level).clear(level);
        });
        CompletableFuture<?>[] fence = {CompletableFuture.allOf(writes.toArray(CompletableFuture<?>[]::new))};
        BlockLightReference.Difference[] beforeRepair = {null};
        h.startSequence().thenWaitUntil(() -> h.assertTrue(fence[0].isDone(), "fixture disk writes pending"))
                .thenExecute(() -> {
                    if (path.startsWith("dark_load") || path.equals("repair_outage")) outage[0] = PowerGrid.get(level).addOutage(center.getMiddleBlockX(), center.getMiddleBlockZ(),
                            256, level.getGameTime() - 10000, 64, Outage.NEVER, 0, -1);
                    level.getChunkSource().addRegionTicket(HOLD, anchor, 0, anchor);
                }).thenWaitUntil(() -> h.assertTrue(level.getChunkSource().getChunkNow(anchor.x, anchor.z) != null, "anchor not loaded"))
                .thenExecute(() -> fence[0] = LightInvariantGameTests.fence(level, center, 1))
                .thenWaitUntil(() -> h.assertTrue(fence[0].isDone(), "root light pending"))
                .thenExecute(() -> {
                    if (!path.startsWith("dark_load") && !path.startsWith("repair")) h.assertTrue(level.getChunkSource().getChunkNow(east.x, east.z) == null, "neighbor already FULL at edit");
                    LevelChunk chunk = NuclearTickets.inMemory(level, center.toLong());
                    if (path.equals("blackout")) outage[0] = PowerGrid.get(level).addOutage(center.getMiddleBlockX(), center.getMiddleBlockZ(),
                            256, level.getGameTime() - 10000, 64, Outage.NEVER, 0, -1);
                    h.assertTrue(chunk != null, "source not in memory");
                    var storage = level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(east.x, 6, east.z));
                    Incoming.LOG.info("LIGHT_LATE {} neighborFull=false neighborLightStorage={} rootSourceLight={}", path, storage != null,
                            level.getBrightness(LightLayer.BLOCK, new BlockPos(center.getMaxBlockX(), 100, center.getMinBlockZ() + 7)));
                    if (path.equals("settle")) {
                        ua.zentix.incoming.nuclear.NuclearEvents.get(level).past().add(d);
                        RuinSettleGameTests.resumeOnly(level, d.id(), center);
                    } else if (path.equals("shatter")) {
                        h.assertTrue(ua.zentix.incoming.warhead.Warheads.shatter(level, new Vec3(center.getMiddleBlockX(), 100, center.getMinBlockZ() + 7),
                                16, 2, 5, ua.zentix.incoming.registry.ModTags.SHATTERS) > 0, "shatter path not exercised");
                    } else if (path.equals("fragments")) {
                        fragments[0] = ua.zentix.incoming.warhead.Warheads.testFragments(level, ua.zentix.incoming.strike.WeaponType.ROCKET,
                                new Vec3(center.getMiddleBlockX() + 0.5, 100.5, center.getMinBlockZ() + 7.5), new Vec3(0, 1, 0), 1234);
                    } else if (path.equals("staged")) {
                        ua.zentix.incoming.warhead.Warheads.testBlast(level, new Vec3(center.getMiddleBlockX() + 0.5, 100.5, center.getMinBlockZ() + 7.5),
                                4, true, false, System::nanoTime, 2_000_000);
                    } else if (path.equals("crater_falls")) {
                        LightInvariantGameTests.fallAtEdge(h, new BlockPos(center.getMinBlockX() + 8, 105, center.getMinBlockZ() + 7));
                    } else if (path.startsWith("ruins")) h.assertTrue(plan.apply(level, chunk, d, new ColumnScar.Budget(false)), "disk plan rejected");
                    else if (path.startsWith("blackout")) ChunkLights.applyInPlace(level, chunk, true, Integer.MAX_VALUE, 0, false, new LongArrayList(), null);
                    else if (path.equals("edge_flags") || path.equals("stop") || path.equals("busy")) {
                        ua.zentix.incoming.work.BlockLighting.changed(level, center);
                        for (int x : new int[]{7, 15}) level.setBlock(new BlockPos(center.getMinBlockX() + x, 100, center.getMinBlockZ() + 7),
                                Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                    if (path.equals("busy")) {
                        ua.zentix.incoming.work.WorkScheduler.useImpactClock(level.getServer(), WorkClock.counting(1_000_000));
                        ua.zentix.incoming.strike.StrikeWorld.get(level).impacts().add(level, new ua.zentix.incoming.work.UnitQueue.Job() {
                            @Override public boolean step(ServerLevel ignored) { return true; }
                            @Override public String describe() { return "continuous impact regression"; }
                        });
                    }
                    if (path.equals("stop")) {
                        ua.zentix.incoming.work.BlockLighting.finish(level);
                        h.assertTrue(ua.zentix.incoming.work.BlockLighting.get(level).heldChunks() == 0, "stop retained light leases");
                    }
                    fence[0] = LightInvariantGameTests.fence(level, center, 1);
                }).thenWaitUntil(() -> {
                    if (path.equals("busy")) {
                        h.assertTrue(ua.zentix.incoming.work.BlockLighting.get(level).idle(), "continuous impacts starved light work");
                        ua.zentix.incoming.strike.StrikeWorld.get(level).impacts().clear(level);
                    } else h.assertTrue(ua.zentix.incoming.strike.StrikeWorld.get(level).idle(), "strike path pending");
                    if (fragments[0] != null) h.assertTrue(fragments[0].glassBroken() > 0, "fragment path did not write blocks");
                    if (path.equals("settle")) h.assertTrue(level.getChunkSource().getChunkNow(center.x, center.z)
                            .getData(ua.zentix.incoming.registry.ModAttachments.CHUNK_SCAR) >= d.id(), "settlement has not applied ruins");
                    if (path.equals("dark_load_ruins")) h.assertTrue(appliedOnLoad[0], "load/ruin combination not exercised");
                }).thenExecute(() -> {
                    if (fullRing) {
                        promotionStart[0] = System.nanoTime();
                        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                            ChunkPos held = new ChunkPos(center.x + dx, center.z + dz);
                            level.getChunkSource().addRegionTicket(HOLD, held, 0, held);
                        }
                    }
                }).thenWaitUntil(() -> {
                    if (fullRing) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                        h.assertTrue(level.getChunkSource().getChunkNow(center.x + dx, center.z + dz) != null, "FULL ring pending");
                }).thenExecute(() -> {
                    if (fullRing) Incoming.LOG.info("LIGHT_FULL_RING {} promotionMs={}", path, (System.nanoTime() - promotionStart[0]) / 1e6);
                    fence[0] = LightInvariantGameTests.fence(level, center, 1);
                })
                .thenWaitUntil(() -> h.assertTrue(fence[0].isDone(), "edit light pending"))
                .thenWaitUntil(() -> {
                    var lighting = ua.zentix.incoming.work.BlockLighting.get(level);
                    h.assertTrue(lighting.idle(), "light lease pending: " + lighting.pending(level));
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    if (path.startsWith("repair")) {
                        h.assertTrue(level.getChunkSource().getChunkNow(center.x, center.z).getData(ua.zentix.incoming.registry.ModAttachments.LIGHT_REPAIRED), "repair completion not marked");
                    }
                    if (path.equals("settle")) {
                        ua.zentix.incoming.nuclear.NuclearEvents.get(level).past().remove(d);
                        NuclearWorld.get(level).clear(level);
                    }
                    measuringLoads[0] = false;
                    Incoming.LOG.info("LIGHT_LOADS {} fullChunks={} instantiatedBlockEntities={} blockEntityPositions={} entities={}", path, loads[0], loads[1], loads[2], loads[3]);
                    Incoming.LOG.info("LIGHT_WORK {} {}", path, ua.zentix.incoming.work.BlockLighting.get(level).measurements());
                    var holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(east.toLong());
                    var proxy = holder.getChunkIfPresentUnchecked(net.minecraft.world.level.chunk.status.ChunkStatus.INITIALIZE_LIGHT);
                    var wrapped = proxy instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imp ? imp.getWrapped() : proxy;
                    Incoming.LOG.info("LIGHT_SAVE {} neighborClass={} proxyUnsaved={} wrappedUnsaved={} correctedBorder={}", path,
                            proxy.getClass().getSimpleName(), proxy.isUnsaved(), wrapped.isUnsaved(),
                            level.getBrightness(LightLayer.BLOCK, new BlockPos(east.getMinBlockX(), 100, east.getMinBlockZ() + 7)));
                    for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                ChunkPos held = new ChunkPos(center.x + dx, center.z + dz);
                level.getChunkSource().removeRegionTicket(HOLD, held, 0, held);
            }
                })
                .thenWaitUntil(() -> {
                    var map = level.getChunkSource().chunkMap;
                    for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                        long key = ChunkPos.asLong(center.x + x, center.z + z);
                        h.assertTrue(map.getVisibleChunkIfPresent(key) == null && !map.pendingUnloads.containsKey(key), "first load not fully unloaded " + new ChunkPos(key) + " tickets=" + map.getDistanceManager().tickets.get(key)
                                + " pending=" + map.pendingUnloads.containsKey(key));
                    }
                })
                .thenExecute(() -> {
                    // Promotion from stored blocks/light happens after the decrease, never in the editing callback.
                    level.getChunkSource().addRegionTicket(HOLD, center, 3, center);
                }).thenWaitUntil(() -> {
                    for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++)
                        h.assertTrue(level.getChunkSource().getChunkNow(center.x + x, center.z + z) != null, "comparison ring not ready");
                }).thenIdle(5)
                .thenExecute(() -> fence[0] = LightInvariantGameTests.fence(level, center, 2))
                .thenWaitUntil(() -> h.assertTrue(fence[0].isDone(), "neighbor light pending"))
                .thenExecute(() -> {
                    if (path.startsWith("repair")) h.assertTrue(level.getChunkSource().getChunkNow(center.x, center.z).getData(ua.zentix.incoming.registry.ModAttachments.LIGHT_REPAIRED), "repair marker lost on disk");
                    beforeRepair[0] = LightInvariantGameTests.report(h, "late_" + path, center, false);
                    for (int x : new int[]{7, 15}) level.getChunkSource().getLightEngine().checkBlock(new BlockPos(center.getMinBlockX() + x, 100, center.getMinBlockZ() + 7));
                    fence[0] = LightInvariantGameTests.fence(level, center, 2);
                }).thenWaitUntil(() -> h.assertTrue(fence[0].isDone(), "source intervention pending"))
                .thenExecute(() -> {
                    LightInvariantGameTests.report(h, "late_" + path + ":source_checks", center, false);
                    for (int z = center.z - 1; z <= center.z + 1; z++) for (int x = center.x - 1; x <= center.x + 1; x++)
                        for (int y = 80; y <= 120; y++) for (int a = 0; a < 16; a++) {
                            var light = level.getChunkSource().getLightEngine();
                            light.checkBlock(new BlockPos(x * 16, y, z * 16 + a));
                            light.checkBlock(new BlockPos(x * 16 + 15, y, z * 16 + a));
                            light.checkBlock(new BlockPos(x * 16 + a, y, z * 16));
                            light.checkBlock(new BlockPos(x * 16 + a, y, z * 16 + 15));
                        }
                    fence[0] = LightInvariantGameTests.fence(level, center, 2);
                }).thenWaitUntil(() -> h.assertTrue(fence[0].isDone(), "border intervention pending"))
                .thenExecute(() -> {
                    LightInvariantGameTests.report(h, "late_" + path + ":border_checks", center, true);
                    h.assertTrue(beforeRepair[0].over() + beforeRepair[0].under() == 0,
                            "Late-loaded saved light mismatches before diagnostic interventions: " + beforeRepair[0]);
                }).thenSucceed();
    }
    /** Real vanilla entity-storage input, separate from chunk blocks, fenced before requesting the first chunk. */
    @SuppressWarnings("unchecked")
    private static List<CompletableFuture<?>> writeEntities(ServerLevel level, java.util.Set<ChunkPos> positions) {
        try {
            var managerField = ServerLevel.class.getDeclaredField("entityManager");
            managerField.setAccessible(true);
            Object manager = managerField.get(level);
            var storageField = manager.getClass().getDeclaredField("permanentStorage");
            storageField.setAccessible(true);
            var storage = (net.minecraft.world.level.entity.EntityPersistentStorage<net.minecraft.world.entity.Entity>) storageField.get(manager);
            List<CompletableFuture<?>> written = new ArrayList<>();
            for (ChunkPos pos : positions) {
                var entity = new net.minecraft.world.entity.decoration.ArmorStand(level, pos.getMinBlockX() + 5.5, 99, pos.getMinBlockZ() + 5.5);
                entity.setNoGravity(true);
                storage.storeEntities(new net.minecraft.world.level.entity.ChunkEntities<>(pos, List.of(entity)));
                written.add(storage.loadEntities(pos));
            }
            return written;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

}
