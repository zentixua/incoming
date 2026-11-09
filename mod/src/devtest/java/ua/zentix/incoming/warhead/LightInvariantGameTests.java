package ua.zentix.incoming.warhead;

import ua.zentix.incoming.gametest.BlockLightReference;
import ua.zentix.incoming.gametest.StrikeGameTests;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.grid.ChunkLights;
import ua.zentix.incoming.grid.GridLights;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.nuclear.world.RuinPlan;
import ua.zentix.incoming.nuclear.world.RuinPlanner;
import ua.zentix.incoming.registry.ModTags;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Measures all cells in the edited chunk and its ring against a fresh, detached vanilla engine. */
@GameTestHolder(Incoming.MOD_ID)
public final class LightInvariantGameTests {
    private static final TicketType<ChunkPos> HOLD = TicketType.create("incoming_light_test", Comparator.comparingLong(ChunkPos::toLong));
    private LightInvariantGameTests() {}

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        List<TestFunction> tests = new ArrayList<>();
        for (String path : List.of("setblock", "edge_flags", "ruins", "ruins_dark_snapshot", "ruins_pending_dark",
                "blackout", "restore", "shatter", "fragments", "staged", "fire_quench", "crater_falls", "packets")) {
            tests.add(new TestFunction("light_invariant_" + path, "light_invariant." + path, "incoming:range", Rotation.NONE,
                    1200, 0, true, false, 1, 1, true, h -> loaded(h, path)));
        }
        return tests;
    }

    private static void loaded(GameTestHelper h, String path) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos center = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        level.getChunkSource().addRegionTicket(HOLD, center, 3, center);
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) level.getChunk(center.x + x, center.z + z);
        boolean fire = IncomingConfig.SERVER.nukeFires.get();
        IncomingConfig.SERVER.nukeFires.set(false);
        List<BlockPos> made = new ArrayList<>();
        List<net.minecraft.world.entity.Entity> spawned = new ArrayList<>();
        java.util.UUID fallZone = java.util.UUID.randomUUID();
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.nukeFires.set(fire);
            spawned.forEach(net.minecraft.world.entity.Entity::discard);
            CraterFalls.get(level).close(level, fallZone);
            for (BlockPos p : made) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            level.getChunkSource().removeRegionTicket(HOLD, center, 3, center);
        });
        // Two rooms: a middle emitter and a border emitter. Roof above the lamps is removed too by ruins.
        List<BlockPos> lamps = List.of(new BlockPos(center.getMinBlockX() + 7, 100, center.getMinBlockZ() + 7),
                new BlockPos(center.getMinBlockX() + 15, 100, center.getMinBlockZ() + 7));
        for (BlockPos lamp : lamps) {
            for (int z = -2; z <= 2; z++) for (int x = -2; x <= 0; x++) {
                put(level, made, lamp.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                put(level, made, lamp.offset(x, 3, z), Blocks.GLASS.defaultBlockState());
            }
            for (int y = 0; y <= 2; y++) for (int z = -2; z <= 2; z++)
                put(level, made, lamp.offset(-2, y, z), Blocks.GLASS.defaultBlockState());
            put(level, made, lamp, path.equals("fire_quench") ? Blocks.FIRE.defaultBlockState() : Blocks.GLOWSTONE.defaultBlockState());
            if (path.equals("crater_falls")) put(level, made, lamp.offset(1, -1, 0), Blocks.STONE.defaultBlockState());
        }
        LevelChunk chunk = level.getChunk(center.x, center.z);
        Detonation d = new Detonation(100000, new Vec3(center.getMinBlockX() - 40, 101, center.getMinBlockZ() + 7),
                99, 15, true, level.getGameTime(), 0, 0, 20000, 42, 0.03f, false, 1);
        CompletableFuture<?>[] fence = {fence(level, center, 2)};
        RuinPlan[] plan = {null};
        ua.zentix.incoming.gametest.LightPacketProbe[] packets = {null};
        FragmentStrike[] fragments = {null};
        BlockLightReference.Difference[] beforeRepair = {null};
        h.startSequence().thenWaitUntil(() -> h.assertTrue(fence[0].isDone(), "initial light pending"))
                .thenExecute(() -> {
                    report(h, path + ":before", center, true);
                    if (path.equals("packets")) packets[0] = ua.zentix.incoming.gametest.LightPacketProbe.attach(h, center);
                    if (path.startsWith("ruins") || path.equals("fire_quench")) {
                        if (path.equals("fire_quench")) chunk.setData(ua.zentix.incoming.registry.ModAttachments.FLASH_LAYER, d.id());
                        if (path.startsWith("ruins_") ) for (BlockPos p : lamps) raw(chunk, p, GridLights.unlit(Blocks.GLOWSTONE.defaultBlockState()));
                        plan[0] = RuinPlanner.planFresh(level, d, chunk, false);
                        if (path.equals("ruins_dark_snapshot")) for (BlockPos p : lamps) raw(chunk, p, Blocks.GLOWSTONE.defaultBlockState());
                        h.assertTrue(plan[0].changedBlocks() > 0, "ruin fixture has no changes");
                        h.assertTrue(plan[0].apply(level, chunk, d, new ColumnScar.Budget(false)), "ruin plan rejected");
                    } else if (path.equals("blackout") || path.equals("restore")) {
                        ChunkLights.applyInPlace(level, chunk, true, Integer.MAX_VALUE, 0, true, new LongArrayList(), null);
                        if (path.equals("restore")) ChunkLights.applyInPlace(level, chunk, false, Integer.MAX_VALUE, 0, true, new LongArrayList(), null);
                    } else if (path.equals("shatter")) {
                        h.assertTrue(Warheads.shatter(level, Vec3.atCenterOf(lamps.getFirst()), 16, 2, 6, ModTags.SHATTERS) > 0, "shatter removed nothing");
                    } else if (path.equals("fragments")) {
                        fragments[0] = Warheads.testFragments(level, WeaponType.ROCKET, Vec3.atCenterOf(lamps.getFirst()), new Vec3(0, 1, 0), 1234);
                    } else if (path.equals("crater_falls")) {
                        long settled = CraterFalls.get(level).settled();
                        CraterFalls.get(level).open(level, fallZone, Vec3.atCenterOf(lamps.getFirst()), 32);
                        BlockPos from = lamps.getFirst().offset(1, 5, 0);
                        for (int i = 0; i <= CraterFalls.LIVE_CAP; i++) {
                            put(level, made, from, Blocks.SAND.defaultBlockState());
                            spawned.add(net.minecraft.world.entity.item.FallingBlockEntity.fall(level, from, Blocks.SAND.defaultBlockState()));
                        }
                        h.assertTrue(CraterFalls.get(level).settled() > settled, "CraterFalls fallback was not exercised");
                        spawned.forEach(net.minecraft.world.entity.Entity::discard);
                        for (int y = 0; y < 5; y++) made.add(lamps.getFirst().offset(1, y, 0));
                    } else if (path.equals("staged")) {
                        Warheads.testBlast(level, Vec3.atCenterOf(lamps.getFirst()), 8, true, false, System::nanoTime, 2_000_000);
                    } else {
                        for (BlockPos p : lamps) level.setBlock(p, Blocks.AIR.defaultBlockState(), path.equals("edge_flags")
                                ? Warheads.EDGE_FLAGS : Block.UPDATE_ALL);
                    }
                }).thenWaitUntil(() -> h.assertTrue(StrikeWorld.get(level).idle(), "strike work pending"))
                .thenExecute(() -> fence[0] = fence(level, center, 2))
                .thenWaitUntil(() -> h.assertTrue(fence[0].isDone(), "edited light pending"))
                .thenIdle(2)
                .thenExecute(() -> {
                    if (packets[0] != null) packets[0].verify(h, center);
                    if (path.startsWith("ruins")) {
                        long removed = lamps.stream().filter(p -> level.getBlockState(p).isAir()).count();
                        Incoming.LOG.info("LIGHT_PATH {} removedEmitters={}/{}", path, removed, lamps.size());
                        h.assertTrue(removed == lamps.size(), "ruin fixture did not remove both emitters");
                    }
                    if (fragments[0] != null) h.assertTrue(fragments[0].glassBroken() > 0, "fragment block write not exercised");
                    beforeRepair[0] = report(h, path, center, false);
                    for (BlockPos p : lamps) level.getChunkSource().getLightEngine().checkBlock(p);
                    fence[0] = fence(level, center, 2);
                }).thenWaitUntil(() -> h.assertTrue(fence[0].isDone(), "source check intervention pending"))
                .thenExecute(() -> {
                    report(h, path + ":source_checks", center, true);
                    h.assertTrue(beforeRepair[0].over() + beforeRepair[0].under() == 0, "Light mismatch before diagnostic intervention: " + beforeRepair[0]);
                }).thenSucceed();
    }

    /** Real non-entity-ticking fallback, used by the disk-edge fixture. */
    public static void fallAtEdge(GameTestHelper h, BlockPos from) {
        ServerLevel level = h.getLevel();
        long before = CraterFalls.get(level).settled();
        java.util.UUID zone = java.util.UUID.randomUUID();
        CraterFalls.get(level).open(level, zone, Vec3.atCenterOf(from), 16);
        level.setBlock(from, Blocks.SAND.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        var entity = net.minecraft.world.entity.item.FallingBlockEntity.fall(level, from, Blocks.SAND.defaultBlockState());
        entity.discard();
        CraterFalls.get(level).close(level, zone);
        h.assertTrue(CraterFalls.get(level).settled() > before, "edge crater-fall fallback not exercised");
    }

    private static void put(ServerLevel level, List<BlockPos> made, BlockPos p, BlockState state) {
        level.setBlock(p, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); made.add(p);
    }

    private static void raw(LevelChunk chunk, BlockPos p, BlockState state) {
        chunk.getSection(chunk.getSectionIndex(p.getY())).setBlockState(p.getX() & 15, p.getY() & 15, p.getZ() & 15, state);
    }

    public static CompletableFuture<?> fence(ServerLevel level, ChunkPos center, int radius) {
        List<CompletableFuture<?>> work = new ArrayList<>();
        for (int z = -radius; z <= radius; z++) for (int x = -radius; x <= radius; x++)
            work.add(level.getChunkSource().getLightEngine().waitForPendingTasks(center.x + x, center.z + z));
        return CompletableFuture.allOf(work.toArray(CompletableFuture<?>[]::new));
    }

    public static BlockLightReference.Difference report(GameTestHelper h, String name, ChunkPos center, boolean requireEqual) {
        var diff = BlockLightReference.capture(h.getLevel(), center, 2).compare(center, 1);
        Incoming.LOG.info("LIGHT_INVARIANT {} cells={} over={} under={} examples={}", name, diff.cells(), diff.over(), diff.under(), diff.examples());
        if (requireEqual) h.assertTrue(diff.over() + diff.under() == 0, name + " light mismatches " + (diff.over() + diff.under()) + " " + diff.examples());
        return diff;
    }
}
