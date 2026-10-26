package ua.zentix.incoming.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.Mth;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.warhead.GroundMaterial;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.warhead.Warheads;
import ua.zentix.incoming.work.WorkScheduler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shared heavy-work budget ({@link WorkScheduler}): impacts in work units within a tick deadline, explosion batches
 * with the same result as vanilla, strike-job order, unready areas and blackout share. Time uses counting clocks
 * ({@link WorkClock#counting}: one work unit = 1 ms); no wall-clock measurements on CI.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkGameTests {
    private static final long MS = 1_000_000L;
    /** Center of the "range" platform at grass level (11-layer floor). */
    private static final BlockPos CENTER = new BlockPos(32, 11, 32);

    private WorkGameTests() {}

    /**
     * District: 7×7 stone-brick houses, height 9, glass and glowstone inside, on a 14-block grid around the platform
     * center (x and z from 11 to 53). Debris does not settle as blocks during the test.
     */
    private static void buildTown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // debris settles as blocks (coal block, basalt), including in the crater: test block removal only without it
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        BlockPos c = h.absolutePos(CENTER);
        for (int gx = -1; gx <= 1; gx++) {
            for (int gz = -1; gz <= 1; gz++) {
                BlockPos base = c.offset(gx * 14 - 3, 0, gz * 14 - 3);
                for (int x = 0; x < 7; x++) for (int y = 0; y < 9; y++) for (int z = 0; z < 7; z++) {
                    boolean wall = x == 0 || x == 6 || z == 0 || z == 6 || y == 8;
                    BlockState s = !wall ? Blocks.AIR.defaultBlockState()
                            : y % 3 == 1 && (x == 3 || z == 3) && y != 8 ? Blocks.GLASS.defaultBlockState()
                            : Blocks.STONE_BRICKS.defaultBlockState();
                    level.setBlock(base.offset(x, y, z), s, 2);
                }
                level.setBlock(base.offset(3, 7, 3), Blocks.GLOWSTONE.defaultBlockState(), 2);
            }
        }
    }

    /** Explosions near the platform: each explosion's chosen removal positions (blocks, not air), in order. */
    private record Blast(Vec3 at, float radius, long gameTime, Set<BlockPos> blocks) {}

    private static List<Blast> recordBlasts(GameTestHelper h, double range) {
        ServerLevel level = h.getLevel();
        Vec3 c = Vec3.atCenterOf(h.absolutePos(CENTER));
        List<Blast> out = new ArrayList<>();
        Consumer<ExplosionEvent.Detonate> on = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(c) > range) return;
            Set<BlockPos> solid = new HashSet<>();
            for (BlockPos p : e.getAffectedBlocks()) if (!level.getBlockState(p).isAir()) solid.add(p.immutable());
            out.add(new Blast(e.getExplosion().center(), e.getExplosion().radius(), level.getGameTime(), solid));
        };
        NeoForge.EVENT_BUS.addListener(on);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(on));
        return out;
    }

    /** Explosion-area tickets ({@code BlastArea}: region and loading) in chunks of the area at {@code at}. */
    @SuppressWarnings("unchecked")
    private static int blastTickets(ServerLevel level, Vec3 at) {
        try {
            Field f = DistanceManager.class.getDeclaredField("tickets");
            f.setAccessible(true);
            Field key = Ticket.class.getDeclaredField("key");
            key.setAccessible(true);
            var map = (Long2ObjectMap<SortedArraySet<Ticket<?>>>) f.get(level.getChunkSource().chunkMap.getDistanceManager());
            ChunkPos c = new ChunkPos(BlockPos.containing(at));
            int n = 0;
            for (var entry : map.long2ObjectEntrySet()) {
                ChunkPos p = new ChunkPos(entry.getLongKey());
                if (Math.abs(p.x - c.x) > 4 || Math.abs(p.z - c.z) > 4) continue;
                for (Ticket<?> t : entry.getValue()) {
                    Object k = key.get(t);
                    String type = k instanceof AreaLoader.Area area ? area.type().toString() : t.getType().toString();
                    if (type.equals("incoming_blast")) n++;
                }
            }
            return n;
        } catch (ReflectiveOperationException ex) {
            throw new GameTestAssertException("cannot read tickets: " + ex);
        }
    }

    private static boolean gone(BlockState s) {
        return s.isAir() || s.getBlock() instanceof BaseFireBlock;
    }

    /**
     * Removed by a surface-charge explosion: air, fire, or crater-rim soil placed at a removed position
     * ({@code Craters.dig}; platform is grass-covered dirt).
     */
    private static boolean goneOrRim(BlockState s) {
        if (gone(s)) return true;
        for (BlockState rim : GroundMaterial.DIRT.debris()) if (s.is(rim.getBlock())) return true;
        return false;
    }

    private static void useCounting(GameTestHelper h, WorkClock clock) {
        MinecraftServer server = h.getLevel().getServer();
        WorkScheduler.useImpactClock(server, clock);
        StrikeGameTests.afterTest(h, () -> WorkScheduler.useImpactClock(server, WorkScheduler.newImpactClock()));
    }

    /**
     * A salvo against the district (five Shaheds and a missile in one tick) with counting clocks: each tick uses
     * at most the total allowance plus one work unit; work spans multiple ticks; final result matches vanilla:
     * every block chosen by any explosion ({@code ExplosionEvent.Detonate}) is removed (or burning).
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "work_budget", skyAccess = true)
    public static void impactsStayWithinBudget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        buildTown(h);
        WorkClock clock = WorkClock.counting(MS);
        useCounting(h, clock);
        List<Blast> blasts = recordBlasts(h, 48);
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        for (int i = 0; i < 5; i++) {
            double a = i * Math.PI * 2 / 5;
            Warheads.detonate(level, WeaponType.DRONE, c.add(Math.cos(a) * 14, 0, Math.sin(a) * 14), null, null);
        }
        Warheads.detonate(level, WeaponType.MISSILE, c, null, null);
        long total = WorkScheduler.totalNanos();
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "impact queue: " + StrikeWorld.get(level).impacts().size());
            h.assertTrue(clock.maxTickNanos() <= total + MS, "tick " + clock.maxTickNanos() / MS + " ms with allowance " + total / MS);
            h.assertTrue(clock.ticksWorked() > 1, "all in one tick: budget does not split work (" + clock.units() + " work units)");
            // one charge detonation per strike: no secondary detonations or fireball explosion
            h.assertTrue(blasts.size() == 6, "explosions " + blasts.size());
            int blocks = 0;
            for (Blast b : blasts) {
                for (BlockPos p : b.blocks()) {
                    h.assertTrue(goneOrRim(level.getBlockState(p)), "explosion at " + b.at() + " did not remove " + p + ": " + level.getBlockState(p));
                }
                blocks += b.blocks().size();
            }
            // Shaheds burst inside houses (gases breach the room), missile in the center house
            h.assertTrue(blocks > 300, "blocks removed " + blocks);
            Incoming.LOG.info("WORKBENCH salvo: {} work units over {} ticks (up to {} per tick), explosions {}, blocks {}", clock.units(),
                    clock.ticksWorked(), clock.maxUnitsPerTick(), blasts.size(), blocks);
        });
    }

    /** A single impact with an empty queue: detonation (rays, damage, events) in the impact tick, without visual delay. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "work_same_tick", skyAccess = true)
    public static void impactWithEmptyQueueRunsSameTick(GameTestHelper h) {
        // the world queue is shared: strike work from previous batches may still be finishing; wait until empty
        whenQueueEmpty(h, 150, () -> {
            ServerLevel level = h.getLevel();
            // counting clocks: one work unit = 1 ms, allowance 30; detonation (start, 11 ray work units, Detonate) fits the tick
            useCounting(h, WorkClock.counting(MS));
            List<Blast> blasts = recordBlasts(h, 16);
            long t = level.getGameTime();
            Warheads.detonate(level, WeaponType.DRONE, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), null, null);
            h.runAfterDelay(1, () -> {
                h.assertFalse(blasts.isEmpty(), "no detonation even a tick later");
                h.assertTrue(blasts.getFirst().gameTime() == t, "detonation at tick " + blasts.getFirst().gameTime() + ", impact at " + t);
                h.succeed();
            });
        });
    }

    /** Run {@code then} on the first tick when the world impact queue is empty (tick check runs before the scheduler). */
    private static void whenQueueEmpty(GameTestHelper h, int ticksLeft, Runnable then) {
        if (StrikeWorld.get(h.getLevel()).impacts().isEmpty()) {
            then.run();
            return;
        }
        if (ticksLeft <= 0) throw new GameTestAssertException("impact queue did not empty: " + StrikeWorld.get(h.getLevel()).impacts().size());
        h.runAfterDelay(1, () -> whenQueueEmpty(h, ticksLeft - 1, then));
    }

    /**
     * Order: two Shaheds in one tick; both detonate (rays, damage) during the impact tick, before block batches;
     * the first strike's crater, fuel fireball and debris run only once its detonation has removed everything selected
     * (fireball ignition in already cleared space).
     */
    @GameTest(template = "range", timeoutTicks = 1000, batch = "work_order", skyAccess = true)
    public static void impactUnitsKeepOrder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        buildTown(h);
        WorkClock clock = WorkClock.counting(MS);
        useCounting(h, clock);
        List<Blast> blasts = recordBlasts(h, 48);
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Vec3 first = c.add(-14, 0, 0), second = c.add(14, 0, 0);
        // bookshelves behind the first house's west wall: Shahed wave cannot break them (beyond 5 m, Jarrett B, 5 psi threshold as for
        // logs); the fuel fireball (8 m) reaches them once gases breach the wall; its flash ignites paper (newspaper threshold
        // 7.9 cal/cm² versus ~8.4: more than half the bookshelves, PulseIgnition), but not logs during a one-second flash
        BlockPos pile = BlockPos.containing(first).offset(-7, 0, -1);
        for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++) level.setBlock(pile.offset(0, y, z), Blocks.BOOKSHELF.defaultBlockState(), 2);
        List<String> early = new ArrayList<>();
        int[] fires = {0};
        // the first Shahed's fuel-fireball ignition (inside its house) runs only after detonation removes the chosen blocks
        Consumer<net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent> onFire = e -> {
            if (e.getLevel() != level || !(e.getState().getBlock() instanceof BaseFireBlock) || blasts.isEmpty()) return;
            if (Vec3.atCenterOf(e.getPos()).distanceTo(first) > 12) return;
            fires[0]++;
            for (BlockPos p : blasts.getFirst().blocks()) {
                if (!gone(level.getBlockState(p))) {
                    early.add("fire at " + e.getPos() + " before removal of " + p);
                    return;
                }
            }
        };
        NeoForge.EVENT_BUS.addListener(onFire);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(onFire));
        List<Vec3> starts = new ArrayList<>();
        List<Long> startTicks = new ArrayList<>();
        Consumer<ExplosionEvent.Start> onStart = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(c) > 48) return;
            starts.add(e.getExplosion().center());
            startTicks.add(level.getGameTime());
        };
        NeoForge.EVENT_BUS.addListener(onStart);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(onStart));
        long t = level.getGameTime();
        Warheads.detonate(level, WeaponType.DRONE, first, null, null);
        Warheads.detonate(level, WeaponType.DRONE, second, null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty() && blasts.size() == 2, "explosions " + blasts.size());
            h.assertTrue(blasts.get(0).at().distanceTo(first) < 0.01, "first event is not the first detonation");
            h.assertTrue(starts.size() >= 2 && starts.get(0).distanceTo(first) < 0.01 && starts.get(1).distanceTo(second) < 0.01,
                    "first work units are not the Shahed detonations: " + starts);
            h.assertTrue(startTicks.get(0) == t && startTicks.get(1) == t, "detonations did not start in the impact tick: " + startTicks + " (impact " + t + ")");
            h.assertTrue(fires[0] > 0, "first fuel fireball ignited nothing");
            h.assertTrue(early.isEmpty(), early.toString());
            h.assertTrue(clock.ticksWorked() > 1, "all in one tick: order not checked");
        });
    }

    /**
     * Server shutdown during queued work ({@code StrikeWorld.onServerStopping} → {@code UnitQueue.finish}): active
     * explosions finish immediately (blocks chosen by rays are removed), while a job in a distant unready area
     * releases it without reading it.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_stop", skyAccess = true)
    public static void stopFinishesQueue(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        buildTown(h);
        WorkClock clock = WorkClock.counting(MS);
        useCounting(h, clock);
        List<Blast> blasts = recordBlasts(h, 48);
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Vec3 far = c.add(0, 0, 5200);
        h.assertFalse(Terrain.readyAround(level, far, 32), "distant area already loaded");
        Warheads.detonate(level, WeaponType.MISSILE, c, null, null);
        Warheads.detonate(level, WeaponType.DRONE, far, null, null);
        h.runAfterDelay(1, () -> {
            h.assertFalse(blasts.isEmpty(), "no detonation");
            boolean half = blasts.getFirst().blocks().stream().anyMatch(p -> !gone(level.getBlockState(p)));
            h.assertTrue(half, "missile detonation removed everything in one tick: nothing left to finish");
            StrikeWorld.get(level).impacts().finish(level);
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "queue not empty after shutdown");
            for (Blast b : blasts) {
                // crater rim (Craters.dig, also completed by shutdown) can occupy removed positions through random whole-block addition
                for (BlockPos p : b.blocks()) h.assertTrue(goneOrRim(level.getBlockState(p)), "not destroyed: " + p + " (" + level.getBlockState(p) + ") from explosion at " + b.at());
            }
            h.assertFalse(Terrain.readyAround(level, far, 32), "distant area loaded synchronously");
            // shutdown without actually stopping: strike timelines (secondary missiles, distant Shahed) must not affect other tests
            StrikeWorld.get(level).dropAll(level);
            h.succeed();
        });
    }

    /** The explosion leaves alone a block changed between ray selection and its batch (player placement, flowing water). */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_changed", skyAccess = true)
    public static void portionSkipsChangedBlock(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        buildTown(h);
        WorkClock clock = WorkClock.counting(MS);
        useCounting(h, clock);
        List<Blast> blasts = recordBlasts(h, 48);
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Warheads.detonate(level, WeaponType.MISSILE, c, null, null);
        BlockPos[] changed = {null};
        h.runAfterDelay(1, () -> {
            // the farthest detonation block is in the last batches
            BlockPos far = null;
            for (BlockPos p : blasts.getFirst().blocks()) {
                if (!gone(level.getBlockState(p)) && (far == null || p.distToCenterSqr(c) > far.distToCenterSqr(c))) far = p;
            }
            h.assertTrue(far != null, "missile detonation removed everything in one tick: nothing left to check");
            level.setBlock(far, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            changed[0] = far;
        });
        h.succeedWhen(() -> {
            h.assertTrue(changed[0] != null && StrikeWorld.get(level).impacts().isEmpty(), "queue not empty");
            h.assertTrue(level.getBlockState(changed[0]).is(Blocks.GOLD_BLOCK), "changed block removed: " + level.getBlockState(changed[0]));
        });
    }

    /**
     * Connecting blocks: removing an inner batch changes outer neighbors' properties (fence, wall and pane lose
     * a neighbor, stairs change shape, leaves change distance), but they remain the same blocks, selected by rays
     * and therefore removed.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "work_connected", skyAccess = true)
    public static void portionTakesReshapedNeighbours(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        BlockPos c = h.absolutePos(CENTER);
        BlockState[] rows = {Blocks.OAK_FENCE.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState(),
                Blocks.COBBLESTONE_WALL.defaultBlockState(), Blocks.IRON_BARS.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState()};
        // rows through the center along x and z; neighbors connect (flag 3: shape updates)
        for (int i = -20; i <= 20; i++) {
            for (int y = 0; y < rows.length; y++) {
                level.setBlock(c.offset(i, y, 1), rows[y], 3);
                level.setBlock(c.offset(1, y, i), rows[y], 3);
            }
        }
        WorkClock clock = WorkClock.counting(MS);
        useCounting(h, clock);
        List<Blast> blasts = recordBlasts(h, 48);
        Warheads.detonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(c), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(!blasts.isEmpty() && StrikeWorld.get(level).impacts().isEmpty(), "queue not empty");
            int connected = 0;
            for (Blast b : blasts) {
                for (BlockPos p : b.blocks()) {
                    BlockState now = level.getBlockState(p);
                    h.assertTrue(goneOrRim(now), "selected by rays but not removed: " + p + " " + now + " (explosion at " + b.at() + ")");
                }
            }
            for (BlockPos p : blasts.getFirst().blocks()) if (Math.abs(p.getX() - c.getX()) <= 1 || Math.abs(p.getZ() - c.getZ()) <= 1) connected++;
            // missile-charge wave breaks light and brittle rows over meters (masonry and bars closer); multiple batches
            h.assertTrue(connected > 24, "rays touched too few rows (" + connected + "): no outer batches");
        });
    }

    /**
     * The custom ray loop selects the same blocks as vanilla {@code explode()}: same seed; platform containing stone,
     * water, obsidian, glass and ore; vanilla and bomb calculators (weakened blocks); all rays in one work unit or
     * batches of 128. Non-destructive explosion: the same platform for every run.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_rays", skyAccess = true)
    public static void raysMatchVanilla(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(CENTER);
        BlockState[] mix = {Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(),
                Blocks.GLASS.defaultBlockState(), Blocks.IRON_ORE.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()};
        java.util.Random pick = new java.util.Random(7);
        for (int x = -9; x <= 9; x++) for (int y = 0; y <= 9; y++) for (int z = -9; z <= 9; z++) {
            if (x == 0 && z == 0 && y <= 1) continue;
            level.setBlock(c.offset(x, y, z), mix[pick.nextInt(mix.length)], 2);
        }
        // water does not flow: otherwise the world would change between explosions (and between 128-ray work units), and runs
        // would compare different worlds
        net.minecraft.world.level.levelgen.structure.BoundingBox box = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                c.offset(-10, -1, -10), c.offset(10, 10, 10));
        level.getFluidTicks().clearArea(box);
        List<BlockState> before = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-10, -1, -10), c.offset(10, 10, 10))) before.add(level.getBlockState(p));
        Set<BlockPos> weakened = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-3, 0, -3), c.offset(3, 3, 3))) if (pick.nextBoolean()) weakened.add(p.immutable());
        net.minecraft.world.level.ExplosionDamageCalculator bunker = new net.minecraft.world.level.ExplosionDamageCalculator() {
            @Override
            public java.util.Optional<Float> getBlockExplosionResistance(net.minecraft.world.level.Explosion explosion,
                    net.minecraft.world.level.BlockGetter reader, BlockPos p, BlockState state, net.minecraft.world.level.material.FluidState fluid) {
                return weakened.contains(p) ? java.util.Optional.of(0f) : super.getBlockExplosionResistance(explosion, reader, p, state, fluid);
            }
        };
        Vec3 at = Vec3.atCenterOf(c.above());
        record Run(boolean vanilla, int rays, boolean weak) {}
        List<Run> runs = List.of(new Run(true, 1352, false), new Run(false, 1352, false), new Run(false, 128, false),
                new Run(true, 1352, true), new Run(false, 128, true));
        List<Set<BlockPos>> seen = new ArrayList<>();
        List<Warheads.Probe> probes = new ArrayList<>();
        Consumer<ExplosionEvent.Detonate> on = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(at) > 0.01) return;
            Set<BlockPos> set = new HashSet<>();
            for (BlockPos p : e.getAffectedBlocks()) set.add(p.immutable());
            seen.add(set);
        };
        NeoForge.EVENT_BUS.addListener(on);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(on));
        // one explosion at a time: the next starts after the previous one finishes selection
        int[] started = {0};
        h.onEachTick(() -> {
            if (started[0] > seen.size() || started[0] >= runs.size() || !StrikeWorld.get(level).impacts().isEmpty()) return;
            Run r = runs.get(started[0]++);
            probes.add(Warheads.testRays(level, at, 6, r.weak() ? bunker : null, 12345L, r.vanilla(), r.rays()));
        });
        h.succeedWhen(() -> {
            h.assertTrue(seen.size() == runs.size() && StrikeWorld.get(level).impacts().isEmpty(), "explosions " + seen.size());
            int i0 = 0;
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-10, -1, -10), c.offset(10, 10, 10))) {
                if (level.getBlockState(p) != before.get(i0++)) throw new GameTestAssertException("platform changed at " + p.immutable() + ": runs compared different worlds");
            }
            h.assertTrue(seen.get(0).size() > 100, "rays selected too few: " + seen.get(0).size());
            for (int i = 1; i < runs.size(); i++) {
                Set<BlockPos> want = runs.get(i).weak() ? seen.get(3) : seen.get(0);
                if (!seen.get(i).equals(want)) {
                    Set<BlockPos> extra = new HashSet<>(seen.get(i));
                    extra.removeAll(want);
                    Set<BlockPos> missing = new HashSet<>(want);
                    missing.removeAll(seen.get(i));
                    throw new GameTestAssertException(runs.get(i) + ": extra " + extra.size() + ", missing " + missing.size() + " of " + want.size());
                }
            }
            h.assertFalse(seen.get(3).equals(seen.get(0)), "bomb calculator changed nothing: empty check");
            for (int i = 0; i < runs.size(); i++) {
                h.assertTrue(probes.get(i).vanilla() == runs.get(i).vanilla(), runs.get(i) + ": explosion path differs from the requested path");
            }
        });
    }

    /**
     * Explosion near an assembled Sable craft: the front removes hull before damaging the body behind it, not after
     * the whole world front ends. With counting clocks, all selected hull blocks are already removed inside the damage callback.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_craft", skyAccess = true)
    public static void craftBlocksGoRightAfterDamage(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        BlockPos base = CENTER.offset(3, 0, -2);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 5; z++) h.setBlock(base.offset(x, y, z), Blocks.OAK_PLANKS);
        BlockPos a = h.absolutePos(base), b = h.absolutePos(base.offset(2, 2, 4));
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4)
                .withSuppressedOutput(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d",
                a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        List<BlockPos> plot = new ArrayList<>();
        long[] at = {-1}, damaged = {-1};
        boolean[] hullGoneAtDamage = {false};
        var body = new net.minecraft.world.entity.animal.Pig(net.minecraft.world.entity.EntityType.PIG, level) {
            @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
                if (source.is(ua.zentix.incoming.registry.ModDamageTypes.STRIKE)) {
                    damaged[0] = level.getGameTime();
                    hullGoneAtDamage[0] = !plot.isEmpty() && plot.stream().allMatch(p -> level.getBlockState(p).isAir());
                }
                return super.hurt(source, amount);
            }
        };
        body.setPos(c.add(7, 1, 0));
        body.setNoAi(true);
        body.setNoGravity(true);
        level.addFreshEntity(body);
        Consumer<ExplosionEvent.Detonate> onBlast = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(c) > 1.001) return;
            for (BlockPos p : e.getAffectedBlocks()) {
                if (SubLevels.inPlotGrid(level, new ChunkPos(p)) && !level.getBlockState(p).isAir()) plot.add(p.immutable());
            }
            // Keep the first (world) event as the reference, never reset it to the craft's own event.
            if (at[0] < 0) at[0] = level.getGameTime();
        };
        List<String> left = new ArrayList<>();
        boolean[] checked = {false};
        Consumer<ServerTickEvent.Post> afterScheduler = e -> {
            if (damaged[0] < 0 || checked[0]) return;
            checked[0] = true;
            for (BlockPos p : plot) if (!level.getBlockState(p).isAir()) left.add(p + " " + level.getBlockState(p));
        };
        NeoForge.EVENT_BUS.addListener(onBlast);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, afterScheduler);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(onBlast);
            NeoForge.EVENT_BUS.unregister(afterScheduler);
        });
        int[] waited = {0};
        h.onEachTick(() -> {
            // Sable need not finish assembly in the same tick: explode once the platform beneath the craft is empty
            if (at[0] >= 0 || waited[0]++ != 5) return;
            h.assertTrue(level.getBlockState(a).isAir(), "craft not assembled: " + level.getBlockState(a));
            WorkScheduler.useImpactClock(level.getServer(), WorkClock.counting(MS));
            h.assertTrue(net.minecraft.world.level.Explosion.getSeenPercent(c.add(0, 1 + 1e-5, 0), body) == 0,
                    "craft does not initially shield the body");
            Warheads.detonate(level, WeaponType.MISSILE, c, null, null);
        });
        StrikeGameTests.afterTest(h, () -> WorkScheduler.useImpactClock(level.getServer(), WorkScheduler.newImpactClock()));
        h.succeedWhen(() -> {
            h.assertTrue(checked[0] && damaged[0] >= at[0], "body behind hull did not receive this wave");
            h.assertTrue(hullGoneAtDamage[0], "hull still intact when damaging the body behind it");
            h.assertFalse(plot.isEmpty(), "rays selected no craft blocks");
            // craft blocks in the selection mean rays ran through vanilla explode() with the Sable mixin
            h.assertTrue(left.isEmpty(), "craft blocks not removed in the explosion tick: " + left);
        });
    }

    /**
     * Craft within ray reach but farther than 1.3 power (air rays reach ~1.73 power): use the vanilla explosion path;
     * otherwise Sable's mixin would neither push the craft nor remove its blocks. Reach power × 1.3 + 1 fails the test.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_craft_reach", skyAccess = true)
    public static void craftInRayReachGoesVanilla(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        float power = 8;
        // nearest craft edge 12 blocks away: beyond 1.3 × 8 + 1, closer than a ray of up to 10.4 power travels in air (~13.9)
        BlockPos base = CENTER.offset(12, 0, -1);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) h.setBlock(base.offset(x, y, z), Blocks.OAK_PLANKS);
        BlockPos a = h.absolutePos(base), b = h.absolutePos(base.offset(2, 2, 2));
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4)
                .withSuppressedOutput(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d",
                a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER)).add(0, 1, 0);
        int[] waited = {0};
        Warheads.Probe[] blast = {null};
        h.onEachTick(() -> {
            if (blast[0] != null || waited[0]++ != 5) return;
            h.assertTrue(level.getBlockState(a).isAir(), "craft not assembled: " + level.getBlockState(a));
            h.assertTrue(at.distanceTo(Vec3.atLowerCornerOf(a)) > power * 1.3 + 1, "craft inside the old reach: nothing to check");
            blast[0] = Warheads.testRays(level, at, power, null, 1L, false, 128);
        });
        h.succeedWhen(() -> {
            h.assertTrue(blast[0] != null && blast[0].done(), "explosion not finished");
            h.assertTrue(blast[0].vanilla(), "craft within ray reach but custom path used");
        });
    }

    /**
     * Two bomb detonations after venting (same area, same tick): charge wave in an underground room and the escaped
     * charge share's wave above the surface house. With counting clocks, both ray selections see untouched blocks:
     * no block selected by the previous detonation is removed before the next {@code ExplosionEvent.Detonate}.
     * Otherwise, subsequent rays see removed or intact blocks depending on server speed, changing destruction.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_area_order", skyAccess = true)
    public static void areaExplosionsPickTogether(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // Round-robin rays can finish both blasts in the same tick at 30 ms. One unit per tick keeps this a
        // cross-tick dependency test: weakening peersPicking must still remove blocks before the other Detonate.
        int budget = IncomingConfig.SERVER.workBudgetMs.get();
        // Deliberately below the public 5-ms minimum: one counting-clock unit keeps Detonate events on separate ticks.
        IncomingConfig.SERVER.workBudgetMs.set(1);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.workBudgetMs.set(budget));
        for (BlockPos p : BlockPos.betweenClosed(CENTER.offset(-10, -10, -10), CENTER.offset(10, 0, 10))) {
            level.setBlock(h.absolutePos(p), Blocks.STONE.defaultBlockState(), 2);
        }
        // underground room around the charge, brick walls
        BlockPos room = CENTER.offset(0, -5, 0);
        for (BlockPos p : BlockPos.betweenClosed(room.offset(-3, -2, -3), room.offset(3, 2, 3))) {
            boolean wall = Math.abs(p.getX() - room.getX()) == 3 || Math.abs(p.getY() - room.getY()) == 2 || Math.abs(p.getZ() - room.getZ()) == 3;
            level.setBlock(h.absolutePos(p), wall ? Blocks.BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        // small brick house above
        for (BlockPos p : BlockPos.betweenClosed(CENTER.offset(-3, 1, -3), CENTER.offset(3, 4, 3))) {
            boolean wall = Math.abs(p.getX() - CENTER.getX()) == 3 || Math.abs(p.getZ() - CENTER.getZ()) == 3 || p.getY() == CENTER.getY() + 4;
            if (wall) level.setBlock(h.absolutePos(p), Blocks.BRICKS.defaultBlockState(), 2);
        }
        useCounting(h, WorkClock.counting(MS));
        Vec3 charge = Vec3.atCenterOf(h.absolutePos(room));
        List<Blast> blasts = recordBlasts(h, 48);
        List<String> early = new ArrayList<>();
        long[] first = {-1}, last = {-1};
        Consumer<ExplosionEvent.Detonate> check = e -> {
            // after explosion recording (LOWEST): the last list entry is this detonation
            if (e.getLevel() != level || e.getExplosion().radius() < 12 || e.getExplosion().center().distanceTo(charge) > 16) return;
            if (first[0] < 0) first[0] = level.getGameTime();
            last[0] = level.getGameTime();
            if (blasts.stream().filter(b -> b.radius() >= 12).count() == 2) IncomingConfig.SERVER.workBudgetMs.set(budget);
            for (int i = 0; i < blasts.size() - 1; i++) {
                if (blasts.get(i).radius() < 12) continue;
                for (BlockPos p : blasts.get(i).blocks()) {
                    if (gone(level.getBlockState(p))) {
                        early.add("before detonation " + (i + 2) + " removed " + p + " from the previous detonation");
                        return;
                    }
                }
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, check);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(check));
        Warheads.bunker(level, charge, charge.add(0, 9, 0), null, null);
        h.succeedWhen(() -> {
            long mains = blasts.stream().filter(b -> b.radius() >= 12).count();
            h.assertTrue(mains == 2 && StrikeWorld.get(level).impacts().isEmpty(), "detonations " + mains);
            h.assertTrue(blasts.stream().allMatch(b -> b.radius() < 12 || !b.blocks().isEmpty()), "detonation selected nothing: order not checked");
            h.assertTrue(last[0] > first[0], "all detonations in one tick: order not checked");
            h.assertTrue(early.isEmpty(), early.toString());
        });
    }

    /**
     * Block batches use time: counting clocks (1 ms per block, 5 ms deadline) give exactly 5 blocks per batch; a block
     * over 10 ms (11 ms per block) gives a one-block batch and a "removing block took" line (first five per strike logged).
     * Every block chosen by rays is removed.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "work_portion_time", skyAccess = true)
    public static void blockPortionsByTime(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos[] centres = {CENTER.offset(-14, -6, 0), CENTER.offset(14, -6, 0)};
        for (BlockPos c : centres) {
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-5, -5, -5), c.offset(5, 5, 5))) {
                level.setBlock(h.absolutePos(p), Blocks.DIRT.defaultBlockState(), 2);
            }
        }
        List<Blast> blasts = recordBlasts(h, 48);
        long[] fast = {0}, slow = {0};
        Warheads.Probe quick = Warheads.testBlast(level, Vec3.atCenterOf(h.absolutePos(centres[0])), 6, true, false,
                () -> fast[0] += MS, 5 * MS);
        Warheads.Probe heavy = Warheads.testBlast(level, Vec3.atCenterOf(h.absolutePos(centres[1])), 6, true, false,
                () -> slow[0] += 11 * MS, 5 * MS);
        h.succeedWhen(() -> {
            h.assertTrue(quick.done() && heavy.done() && blasts.size() == 2, "explosions " + blasts.size());
            int blocks = 0;
            for (Blast b : blasts) {
                for (BlockPos p : b.blocks()) h.assertTrue(gone(level.getBlockState(p)), "selected by rays but not removed: " + p);
                blocks += b.blocks().size();
            }
            h.assertTrue(blocks > 60, "rays selected too few: " + blocks);
            h.assertTrue(quick.largestPortion() == 5, "batch at 1 ms per block: " + quick.largestPortion() + " blocks");
            h.assertTrue(quick.slowBlocks() == 0, "slow blocks at 1 ms: " + quick.slowBlocks());
            h.assertTrue(heavy.largestPortion() == 1, "batch at 11 ms per block: " + heavy.largestPortion() + " blocks");
            h.assertTrue(heavy.slowBlocks() > BLAST_SLOW_LOGGED, "slow blocks " + heavy.slowBlocks());
        });
    }

    /**
     * The mod explosion removes POIs absent from world POI data (a map city pasted without {@code setBlock}), without
     * PoiSection "never registered" errors or stale records at removed positions. Villager workstations come in pairs
     * within a section: one placed through the world (recorded), the other directly into the section (unrecorded).
     * Vanilla logs the error only when the section already has POI data: on chunk load it scans a section without
     * data and registers everything; removal from a section without data is silent.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "work_poi_blast", skyAccess = true)
    public static void poiInBlastStaysConsistent(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = CENTER.above(2);
        BlockState[] poi = {Blocks.COMPOSTER.defaultBlockState(), Blocks.CARTOGRAPHY_TABLE.defaultBlockState(),
                Blocks.SMITHING_TABLE.defaultBlockState(), Blocks.LOOM.defaultBlockState(), Blocks.FLETCHING_TABLE.defaultBlockState()};
        List<BlockPos> registered = new ArrayList<>(), bare = new ArrayList<>();
        for (int i = 0; i < poi.length; i++) {
            BlockPos b = h.absolutePos(c.offset(i - 2, 0, 1)), r = (b.getY() & 15) < 15 ? b.above() : b.below();
            level.setBlock(r, poi[i], 3);
            LevelChunk chunk = level.getChunkAt(b);
            chunk.getSection(chunk.getSectionIndex(b.getY())).setBlockState(b.getX() & 15, b.getY() & 15, b.getZ() & 15, poi[i], false);
            registered.add(r);
            bare.add(b);
        }
        // both bed halves bypass POI data: the explosion-removed foot removes the head (the POI) through a shape update
        // outside the block-removal code (in a city, neighboring POI pairs on building floors). Removal order is random;
        // among six beds, at least one foot almost always goes before its head (1 − 1/64)
        List<BlockPos> heads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            BlockPos foot = h.absolutePos(c.offset(i - 3, 0, -2)), head = foot.south();
            for (BlockPos p : List.of(foot, head)) {
                BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH)
                        .setValue(BedBlock.PART, p == head ? BedPart.HEAD : BedPart.FOOT);
                LevelChunk chunk = level.getChunkAt(p);
                chunk.getSection(chunk.getSectionIndex(p.getY())).setBlockState(p.getX() & 15, p.getY() & 15, p.getZ() & 15, bed, false);
            }
            heads.add(head);
            bare.add(head);
        }
        int[] errors = {0};
        var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(PoiSection.class.getName());
        var counter = new org.apache.logging.log4j.core.appender.AbstractAppender("incoming-poi-blast-errors", null, null, true,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent e) {
                if (e.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.ERROR)) errors[0]++;
            }
        };
        counter.start();
        logger.addAppender(counter);
        StrikeGameTests.afterTest(h, () -> {
            logger.removeAppender(counter);
            counter.stop();
        });
        h.runAfterDelay(2, () -> {
            // POI additions through the world run as a server task; by this tick they are recorded
            for (BlockPos p : registered) h.assertTrue(level.getPoiManager().getType(p).isPresent(), "setup: not recorded " + p.toShortString());
            for (BlockPos p : bare) h.assertTrue(level.getPoiManager().getType(p).isEmpty(), "setup: recorded " + p.toShortString());
            long[] t = {0};
            Warheads.Probe probe = Warheads.testBlast(level, Vec3.atCenterOf(h.absolutePos(c)), 6, true, false, () -> t[0] += MS, 5 * MS);
            long[] doneAt = {-1};
            h.succeedWhen(() -> {
                h.assertTrue(probe.done(), "explosion running");
                if (doneAt[0] < 0) doneAt[0] = level.getGameTime();
                // POI-data removal runs as a server task after the block changes
                h.assertTrue(level.getGameTime() >= doneAt[0] + 2, "waiting for server tasks");
                h.assertTrue(errors[0] == 0, "PoiSection errors: " + errors[0]);
                for (BlockPos p : bare) h.assertTrue(gone(level.getBlockState(p)), "not destroyed: " + p.toShortString() + " " + level.getBlockState(p));
                for (BlockPos p : heads) h.assertTrue(gone(level.getBlockState(p.north())), "bed foot not removed: " + p.north().toShortString());
                for (BlockPos p : registered) h.assertTrue(gone(level.getBlockState(p)), "not destroyed: " + p.toShortString());
                for (BlockPos p : bare) h.assertTrue(level.getPoiManager().getType(p).isEmpty(), "POI record remains " + p.toShortString());
                for (BlockPos p : registered) h.assertTrue(level.getPoiManager().getType(p).isEmpty(), "POI record remains " + p.toShortString());
            });
        });
    }

    /** Number of "removing block took" lines per strike (as in {@code BlastArea.SLOW_BLOCKS_LOGGED}). */
    private static final int BLAST_SLOW_LOGGED = 5;

    /**
     * Mod explosions use drop decay ({@code DESTROY_WITH_DECAY}, like a creeper): a removed block drops with probability
     * 1/power, unaffected by {@code tntExplosionDropDecay} (disabled by default). Without decay, everything removed
     * drops: after salvos of 30, thousands of items occupy two thirds of the server thread while ticking.
     * A 15×8×15 dirt box with a power-16 explosion inside; count removed blocks across the whole blast box, including platform floor.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "work_drop_decay", skyAccess = true)
    public static void blastDropsDecay(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        GameRules.BooleanValue rule = level.getGameRules().getRule(GameRules.RULE_TNT_EXPLOSION_DROP_DECAY);
        boolean was = rule.get();
        rule.set(false, level.getServer());
        StrikeGameTests.afterTest(h, () -> rule.set(was, level.getServer()));
        for (BlockPos p : BlockPos.betweenClosed(CENTER.offset(-7, 1, -7), CENTER.offset(7, 8, 7))) {
            level.setBlock(h.absolutePos(p), Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        BlockPos lo = h.absolutePos(CENTER.offset(-24, -11, -24)), hi = h.absolutePos(CENTER.offset(24, 24, 24));
        int before = solid(level, lo, hi);
        Warheads.Probe blast = Warheads.testBlast(level, Vec3.atCenterOf(h.absolutePos(CENTER.above(4))), 16, true, false, System::nanoTime, 5 * MS);
        boolean[] logged = {false};
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "explosion running");
            int removed = before - solid(level, lo, hi), items = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(Vec3.atLowerCornerOf(lo), Vec3.atLowerCornerOf(hi.offset(1, 1, 1))).inflate(8))) {
                items += e.getItem().getCount();
            }
            if (!logged[0]) Incoming.LOG.info("BLASTDROPS power-16 explosion: {} blocks removed, {} items dropped", removed, items);
            logged[0] = true;
            h.assertTrue(removed >= 1000, "explosion removed too few: " + removed);
            h.assertTrue(items > 0, "nothing dropped: decay is not drop cancellation");
            // 1/16 is about 6 %; with spread margin, no more than a sixth
            h.assertTrue(items * 6 <= removed, "dropped " + items + " items for " + removed + " removed blocks");
        });
    }

    private static int solid(ServerLevel level, BlockPos lo, BlockPos hi) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) if (!level.getBlockState(p).isAir()) n++;
        return n;
    }

    /**
     * Vanilla path (as near a craft): damage uses the entity list after {@code ExplosionEvent.Detonate}, delivered
     * in mod work units (the mod's {@code Detonate} handler takes the list; vanilla {@code explode()} iterates an empty list).
     * A cow removed from the list by a handler is unharmed; its neighbor is injured.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "work_handoff", skyAccess = true)
    public static void vanillaPathDamagesDetonateList(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER)).add(0, 1, 0);
        net.minecraft.world.entity.animal.Cow spared = h.spawn(net.minecraft.world.entity.EntityType.COW, CENTER.offset(-3, 1, 0));
        net.minecraft.world.entity.animal.Cow hit = h.spawn(net.minecraft.world.entity.EntityType.COW, CENTER.offset(3, 1, 0));
        boolean[] listed = {false};
        Consumer<ExplosionEvent.Detonate> on = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(at) > 0.01) return;
            listed[0] = e.getAffectedEntities().remove(spared);
        };
        NeoForge.EVENT_BUS.addListener(on);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(on));
        Warheads.Probe blast = Warheads.testBlast(level, at, 4, false, true, System::nanoTime, 5 * MS);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "explosion not finished");
            h.assertTrue(listed[0], "cow absent from Detonate list: nothing to check");
            h.assertTrue(blast.vanilla(), "explosion did not use the vanilla path");
            h.assertTrue(blast.handedDamage(), "vanilla explode() loop dealt damage rather than mod work units");
            h.assertTrue(spared.isAlive() && spared.getHealth() == spared.getMaxHealth(), "entity removed from the list was injured: " + spared.getHealth());
            h.assertTrue(!hit.isAlive() || hit.getHealth() < hit.getMaxHealth(), "cow in the list was not injured");
        });
    }

    /**
     * Explosion near a Sable craft: a craft block removed from the selection by an {@code ExplosionEvent.Detonate}
     * handler remains; other selected craft blocks are removed in the tick when entity damage finishes
     * (real clocks: explosion work units may extend into subsequent ticks).
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_handoff_craft", skyAccess = true)
    public static void craftBlockLeftByDetonateStays(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        BlockPos base = CENTER.offset(3, 0, -2);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 5; z++) h.setBlock(base.offset(x, y, z), Blocks.OAK_PLANKS);
        BlockPos a = h.absolutePos(base), b = h.absolutePos(base.offset(2, 2, 4));
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4)
                .withSuppressedOutput(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d",
                a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER)).add(0, 1, 0);
        BlockPos[] kept = {null};
        List<BlockPos> plot = new ArrayList<>();
        Consumer<ExplosionEvent.Detonate> onBlast = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(c) > 0.01) return;
            for (BlockPos p : e.getAffectedBlocks()) {
                if (!SubLevels.inPlotGrid(level, new ChunkPos(p)) || level.getBlockState(p).isAir()) continue;
                if (kept[0] == null) kept[0] = p.immutable();
                else plot.add(p.immutable());
            }
            if (kept[0] != null) e.getAffectedBlocks().remove(kept[0]);
        };
        Warheads.Probe[] blast = {null};
        List<String> wrong = new ArrayList<>();
        boolean[] checked = {false};
        Consumer<ServerTickEvent.Post> afterScheduler = e -> {
            if (blast[0] == null || blast[0].picking() || checked[0]) return;
            checked[0] = true;
            if (kept[0] != null && level.getBlockState(kept[0]).isAir()) wrong.add("block removed from the selection was destroyed: " + kept[0]);
            for (BlockPos p : plot) if (!level.getBlockState(p).isAir()) wrong.add("not removed: " + p + " " + level.getBlockState(p));
        };
        NeoForge.EVENT_BUS.addListener(onBlast);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, afterScheduler);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(onBlast);
            NeoForge.EVENT_BUS.unregister(afterScheduler);
        });
        int[] waited = {0};
        h.onEachTick(() -> {
            if (blast[0] != null || waited[0]++ != 5) return;
            h.assertTrue(level.getBlockState(a).isAir(), "craft not assembled: " + level.getBlockState(a));
            blast[0] = Warheads.testBlast(level, c, 4, true, false, System::nanoTime, 5 * MS);
        });
        h.succeedWhen(() -> {
            h.assertTrue(checked[0] && blast[0].done(), "no explosion");
            h.assertTrue(kept[0] != null && !plot.isEmpty(), "rays selected too few craft blocks: " + (kept[0] == null ? 0 : 1 + plot.size()));
            h.assertTrue(blast[0].vanilla(), "craft did not use the vanilla path");
            h.assertTrue(wrong.isEmpty(), wrong.toString());
        });
    }

    /**
     * Craft hull shields from explosion as in vanilla: a glass wall (Sable craft) between explosion and cow. Vanilla
     * {@code level.explode} damages entities before removing blocks, leaving the shielded cow almost unharmed; the
     * mod (vanilla path, damage in its work units) injures the cow no more, although rays select the wall and remove
     * it by explosion end. Removing craft blocks during splitting, before damage, leaves the cow unshielded and kills it.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_craft_shield", skyAccess = true)
    public static void craftHullShieldsLikeVanilla(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        // mod A, vanilla B: 24 blocks apart, beyond each other's ray and damage reach (2 × power)
        BlockPos oA = CENTER.offset(0, 0, -12), oB = CENTER.offset(0, 0, 12);
        float power = 4;
        net.minecraft.world.entity.animal.Cow[] cows = new net.minecraft.world.entity.animal.Cow[2];
        BlockPos[] walls = new BlockPos[2];
        Vec3[] centers = new Vec3[2];
        for (int i = 0; i < 2; i++) {
            BlockPos o = i == 0 ? oA : oB;
            BlockPos w = o.offset(1, 0, -1);
            for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) h.setBlock(w.offset(0, y, z), Blocks.GLASS);
            BlockPos a = h.absolutePos(w), b = h.absolutePos(w.offset(0, 2, 2));
            level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4)
                    .withSuppressedOutput(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d",
                    a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
            walls[i] = a;
            centers[i] = Vec3.atBottomCenterOf(h.absolutePos(o)).add(0, 1.5, 0);
            cows[i] = h.spawn(net.minecraft.world.entity.EntityType.COW, o.offset(4, 0, 0));
            cows[i].setNoAi(true);
        }
        List<BlockPos> plot = new ArrayList<>();
        Consumer<ExplosionEvent.Detonate> onBlast = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(centers[0]) > 0.01) return;
            for (BlockPos p : e.getAffectedBlocks()) {
                if (SubLevels.inPlotGrid(level, new ChunkPos(p)) && !level.getBlockState(p).isAir()) plot.add(p.immutable());
            }
        };
        Warheads.Probe[] blast = {null};
        float[] health = {-1, -1};
        Consumer<ServerTickEvent.Post> afterScheduler = e -> {
            if (blast[0] == null || blast[0].picking() || health[0] >= 0) return;
            health[0] = cows[0].getHealth();
            health[1] = cows[1].getHealth();
        };
        NeoForge.EVENT_BUS.addListener(onBlast);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, afterScheduler);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(onBlast);
            NeoForge.EVENT_BUS.unregister(afterScheduler);
            WorkScheduler.useImpactClock(level.getServer(), WorkScheduler.newImpactClock());
        });
        int[] waited = {0};
        h.onEachTick(() -> {
            if (blast[0] != null || waited[0]++ != 5) return;
            for (BlockPos w : walls) h.assertTrue(level.getBlockState(w).isAir(), "craft not assembled: " + level.getBlockState(w));
            // counting clocks: the whole mod explosion runs this tick; craft cannot move between rays and damage
            WorkScheduler.useImpactClock(level.getServer(), WorkClock.counting(MS));
            blast[0] = Warheads.testBlast(level, centers[0], power, true, true, System::nanoTime, 5 * MS);
            level.explode(null, ModDamageTypes.source(level, ModDamageTypes.STRIKE, null, null), null,
                    centers[1].x, centers[1].y, centers[1].z, power, false, Level.ExplosionInteraction.TNT);
        });
        h.succeedWhen(() -> {
            h.assertTrue(health[0] >= 0 && blast[0].done(), "no explosion");
            h.assertTrue(blast[0].vanilla(), "craft did not use the vanilla path");
            float max = cows[1].getMaxHealth();
            // unshielded, a cow 3.5 blocks from a power-4 explosion dies; shielded, it takes 1 damage
            h.assertTrue(health[1] >= max - 1.5f, "vanilla: shielded cow injured " + health[1] + " of " + max + ": hull does not shield, nothing to check");
            h.assertTrue(plot.size() >= 3, "rays selected too few hull blocks: " + plot.size());
            List<String> left = new ArrayList<>();
            for (BlockPos p : plot) if (!level.getBlockState(p).isAir()) left.add(p + " " + level.getBlockState(p));
            h.assertTrue(left.isEmpty(), "selected hull not removed: " + left);
            h.assertTrue(health[0] >= health[1] - 0.01f, "mod: shielded cow injured more than in vanilla: " + health[0] + " versus " + health[1]);
        });
    }

    /**
     * A job with an unready area waits without reading it (otherwise the chunk would load within the tick); later jobs
     * proceed. A distant detonation and one on the platform: the latter runs in the impact tick, the former when its area
     * finishes loading. As in {@link #impactWithEmptyQueueRunsSameTick}, empty previous-batch queue and counting clocks:
     * otherwise "in the impact tick" depends on machine speed (CI third batch after server start: up to 57 ms per ray work unit).
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_unready", skyAccess = true)
    public static void unitSkipsUnreadyChunk(GameTestHelper h) {
        whenQueueEmpty(h, 150, () -> {
            useCounting(h, WorkClock.counting(MS));
            ServerLevel level = h.getLevel();
            Vec3 near = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
            Vec3 far = near.add(0, 0, -5200);
            double area = 32;
            h.assertFalse(Terrain.readyAround(level, far, area), "distant area already loaded");
            int[] nearN = {0}, farN = {0};
            Consumer<ExplosionEvent.Detonate> count = e -> {
                if (e.getLevel() != level) return;
                if (e.getExplosion().center().distanceTo(near) < 24) nearN[0]++;
                if (e.getExplosion().center().distanceTo(far) < 24) farN[0]++;
            };
            boolean[] checked = {false};
            Consumer<ServerTickEvent.Post> afterScheduler = e -> {
                if (checked[0]) return;
                checked[0] = true;
                // same tick, after the scheduler: nearby job done, distant job waiting, area not loaded synchronously
                if (nearN[0] < 1) h.fail("platform detonation did not run in the impact tick");
                if (farN[0] != 0) h.fail("distant detonation ran in an unready area");
                if (Terrain.readyAround(level, far, area)) h.fail("distant area loaded in the impact tick");
            };
            NeoForge.EVENT_BUS.addListener(count);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, afterScheduler);
            StrikeGameTests.afterTest(h, () -> {
                NeoForge.EVENT_BUS.unregister(count);
                NeoForge.EVENT_BUS.unregister(afterScheduler);
            });
            Warheads.detonate(level, WeaponType.DRONE, far, null, null);
            Warheads.detonate(level, WeaponType.DRONE, near, null, null);
            h.runAfterDelay(3, () -> {
                for (int cx = Mth.floor(far.x - area) >> 4; cx <= Mth.floor(far.x + area) >> 4; cx++)
                    for (int cz = Mth.floor(far.z - area) >> 4; cz <= Mth.floor(far.z + area) >> 4; cz++) level.getChunk(cx, cz);
            });
            h.succeedWhen(() -> {
                h.assertTrue(checked[0], "scheduler tick did not run");
                h.assertTrue(farN[0] >= 1, "distant detonation still missing after area load");
                h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "impact queue not empty");
                // timeline and queued jobs held the area; released once all holders release it
                h.assertTrue(blastTickets(level, far) == 0, "distant area tickets remain: " + blastTickets(level, far));
            });
        });
    }

    /**
     * Blackout and salvo in the same ticks (counting clocks for both lanes): while blackout has work, impacts take
     * at most 2/3 of the total allowance plus one work unit, and blackout runs its own work units; the whole tick
     * uses at most the total allowance plus one work unit per lane.
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "work_lanes", skyAccess = true)
    public static void lanesShareTick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        buildTown(h);
        WorkClock impact = WorkClock.counting(MS), grid = WorkClock.counting(MS);
        useCounting(h, impact);
        Blackouts.useClock(server, grid);
        long total = WorkScheduler.totalNanos();
        boolean[] gridBusy = {false};
        int[] shared = {0}, gridWorked = {0};
        List<String> over = new ArrayList<>();
        Consumer<ServerTickEvent.Pre> before = e -> gridBusy[0] = Blackouts.pending(server);
        Consumer<ServerTickEvent.Post> after = e -> {
            long used = impact.usedThisTickNanos() + grid.usedThisTickNanos();
            if (impact.workedThisTick() && used > total + 2 * MS) over.add("tick " + used / MS + " ms");
            if (!gridBusy[0] || !impact.workedThisTick()) return;
            shared[0]++;
            if (grid.workedThisTick()) gridWorked[0]++;
            if (impact.usedThisTickNanos() > total * 2 / 3 + MS) over.add("impacts " + impact.usedThisTickNanos() / MS + " ms during blackout");
        };
        NeoForge.EVENT_BUS.addListener(before);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, after);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(before);
            NeoForge.EVENT_BUS.unregister(after);
            Blackouts.useClock(server, Blackouts.newClock());
            Blackouts.restore(level, null, 0, 0);
        });
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Blackouts.blackout(level, c, 200, 2, -1);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(Blackouts.pending(server), "blackout has no work"))
                .thenExecute(() -> {
                    for (int i = 0; i < 5; i++) {
                        double a = i * Math.PI * 2 / 5;
                        Warheads.detonate(level, WeaponType.DRONE, c.add(Math.cos(a) * 14, 0, Math.sin(a) * 14), null, null);
                    }
                    Warheads.detonate(level, WeaponType.MISSILE, c, null, null);
                })
                .thenWaitUntil(() -> h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "impact queue not empty"))
                .thenExecute(() -> {
                    h.assertTrue(over.isEmpty(), "over budget: " + over);
                    h.assertTrue(shared[0] > 0, "salvo and blackout never ran in the same tick");
                    h.assertTrue(gridWorked[0] > 0, "blackout ran no work unit in any shared tick (" + shared[0] + " ticks)");
                    Incoming.LOG.info("WORKBENCH lanes: {} shared ticks, blackout worked in {}", shared[0], gridWorked[0]);
                })
                .thenSucceed();
    }
}
