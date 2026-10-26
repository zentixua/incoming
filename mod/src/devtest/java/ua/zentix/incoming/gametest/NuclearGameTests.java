package ua.zentix.incoming.gametest;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.MobPanic;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.model.BlastInjury;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.nuclear.world.BlastBody;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.grid.GridLights;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.nuclear.world.RuinPlan;
import ua.zentix.incoming.nuclear.world.RuinPlanner;
import ua.zentix.incoming.nuclear.world.CraterJob;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.nuclear.world.RuinWorkers;
import ua.zentix.incoming.nuclear.world.ThermalShadow;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import ua.zentix.incoming.nuclear.radiation.BurnsEffect;
import ua.zentix.incoming.nuclear.radiation.MobFallout;
import ua.zentix.incoming.nuclear.radiation.RadiationTicker;
import ua.zentix.incoming.nuclear.radiation.Shielding;
import ua.zentix.incoming.registry.ModEffects;
import java.util.Comparator;

/**
 * Nuclear strike without window (DESIGN-nuke §12) - scaled down ({@code scale} 0.01–0.07: 1 block = 15–100 m),
 *  so that the pressure zones fit on the site 64×64 "range" (turf on y = 11, surface y = 12).
 * Each test in its batch: the explosion affects everything around it, neighboring sites should not interfere with each other.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearGameTests {
    static final BlockPos CENTER = new BlockPos(32, 12, 32);

    private NuclearGameTests() {}

    static Detonation detonation(GameTestHelper h, BlockPos at, double hob, double yieldKt, float scale) {
        return detonation(h, at, hob, yieldKt, scale, true);
    }

    /**
     * Explosion over the site. {@code fill}: the template stands a block higher than its beginning - there is a layer of air under the platform,
     *  and for collapse ({@code Collapse}) it is all a canopy without support; Under the real city the ground is solid, so the
     *  layer is filled up. Without backfill, there is no support for the site even before the impact - this is how a building on “hanging” ground is checked.
     */
    static Detonation detonation(GameTestHelper h, BlockPos at, double hob, double yieldKt, float scale, boolean fill) {
        if (fill) fill(h);
        return raw(h, at, hob, yieldKt, scale);
    }

    /**
     * The same with the backfill, but above the place in the world {@code ground}, and not the site: a check for which the location of the explosion is determined by the grid
     *  chunks (for GameTestHelper relativePos and absolutePos are not inverse to each other).
     */
    static Detonation detonationAt(GameTestHelper h, BlockPos ground, double hob, double yieldKt, float scale) {
        fill(h);
        return rawAt(h, ground, hob, yieldKt, scale);
    }

    private static void fill(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(63, 0, 63))) {
            BlockPos a = h.absolutePos(p);
            if (level.getBlockState(a).isAir()) level.setBlock(a, Blocks.STONE.defaultBlockState(), 2);
        }
    }

    /**
     * The numbers of explosions of checks are different for everyone: the ruins of the explosion ({@code RuinContext}) live by number, and the sites GameTest
     *  between the batch are the same places; with a random number, a matching explosion would take the light shadow height maps from the last
     *  check at this site (“not a single fire”).
     */
    static final java.util.concurrent.atomic.AtomicInteger IDS = new java.util.concurrent.atomic.AtomicInteger(1_000_000);

    private static Detonation raw(GameTestHelper h, BlockPos at, double hob, double yieldKt, float scale) {
        return rawAt(h, h.absolutePos(at), hob, yieldKt, scale);
    }

    private static Detonation rawAt(GameTestHelper h, BlockPos g, double hob, double yieldKt, float scale) {
        return new Detonation(IDS.incrementAndGet(), new Vec3(g.getX() + 0.5, g.getY() + hob, g.getZ() + 0.5), g.getY(),
                yieldKt, hob <= 0, h.getLevel().getGameTime(), 0, 0, 20_000, 7, scale, false, Detonation.OLD_WAVE);
    }

    /**
     * 15 kt above {@code at} (in the air - at the optimal height, otherwise near the ground) on such a scale that in the center of the block
     * {@code where} there is {@code psi}: the pressure at the construction site is set directly, and not by selecting its location.
     */
    static Detonation atPsi(GameTestHelper h, BlockPos at, boolean air, BlockPos where, double psi) {
        Vec3 p = Vec3.atCenterOf(h.absolutePos(where));
        double lo = 0.002, hi = 1;
        for (int i = 0; i < 40; i++) {
            double mid = Math.sqrt(lo * hi);
            if (raw(h, at, air ? Yield.optimalBurstHeight(15) * mid : 0, 15, (float) mid).psi(p) < psi) lo = mid;
            else hi = mid;
        }
        return detonation(h, at, air ? Yield.optimalBurstHeight(15) * hi : 0, 15, (float) hi);
    }

    /** All the columns of the site - as a line of destruction would have passed through them. */
    private static void scarAll(GameTestHelper h, Detonation d) {
        scarAll(h, d, new ColumnScar.Budget(true));
    }

    static List<RuinPlan> scarAll(GameTestHelper h, Detonation d, ColumnScar.Budget budget) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(63, 0, 63));
        List<RuinPlan> plans = new java.util.ArrayList<>();
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                int[] before = heights(chunk, true);
                RuinPlan plan = RuinPlanner.plan(level, d, chunk);
                h.assertTrue(plan.apply(level, chunk, d, budget), "the new plan is outdated");
                // height maps and sky sources from the plan are the same as when recalculating the entire chunk
                int[] after = heights(chunk, false), full = heights(chunk, true);
                for (int k = 0; k < full.length; k++) {
                    if (after[k] != full[k]) {
                        h.fail("chunk " + chunk.getPos() + ": " + (k / 256 < RuinPlan.HEIGHTMAP_TYPES.length ? RuinPlan.HEIGHTMAP_TYPES[k / 256] : "sky")
                                + " in column " + (k % 256) + " after replacement " + after[k] + ", recalculation " + full[k] + " (to the ruins " + before[k] + ")");
                    }
                }
                plans.add(plan);
            }
        }
        // trunks that fell into a neighboring chunk - after the ruins of all chunks (as in a queue)
        for (RuinPlan plan : plans) {
            for (int k = 0; k < plan.outsideCount(); k++) RuinPlan.placeLog(level, plan.outsidePos(k), plan.outsideState(k));
        }
        return plans;
    }

    /** The platform chunks are in the order {@link #scarAll}. */
    static List<LevelChunk> chunks(GameTestHelper h) {
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(63, 0, 63));
        List<LevelChunk> out = new java.util.ArrayList<>();
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) out.add(h.getLevel().getChunk(cx, cz));
        }
        return out;
    }

    /** Height maps and lower sky sources chunk in a row in columns; recompute - first recalculate the entire chunk. */
    private static int[] heights(LevelChunk chunk, boolean recompute) {
        if (recompute) {
            net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk, java.util.EnumSet.copyOf(List.of(RuinPlan.HEIGHTMAP_TYPES)));
            chunk.initializeLightSources();
        }
        int types = RuinPlan.HEIGHTMAP_TYPES.length;
        int[] out = new int[256 * (types + 1)];
        for (int column = 0; column < 256; column++) {
            for (int t = 0; t < types; t++) out[t * 256 + column] = chunk.getOrCreateHeightmapUnprimed(RuinPlan.HEIGHTMAP_TYPES[t]).getFirstAvailable(column & 15, column >> 4);
            out[types * 256 + column] = chunk.getSkyLightSources().getLowestSourceY(column & 15, column >> 4);
        }
        return out;
    }

    /**
     * 15 kt near the ground, 1 block = 50 m: the tree where at the crown 5.1–5.7 psi, falls with its trunk from the epicenter (log - 5 psi),
     *  and its foliage (layer in the 3 block: the wave breaks it only from ~7 psi) falls without a trunk - not a leaf in the air; glass
     *  in 1.45 km (~3 psi) and boards in 900 m (~7 psi) are knocked out, the wall is made of stone bricks in two blocks with a thickness of 1.25 km
     * (~4 psi) is standing, the soil is intact. (A single brick of thickness 1 breaks under reflected pressure - this is a thin element.)
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_blocks", skyAccess = true)
    public static void blastBreaksLightKeepsMasonryFellsTrees(GameTestHelper h) {
        Detonation d = detonation(h, CENTER, 0, 15, 0.02f);
        BlockPos tree = null;
        for (int dx = 4; dx <= 25 && tree == null; dx++) {
            double psi = d.psi(Vec3.atCenterOf(h.absolutePos(CENTER.east(dx).above(4))));
            if (psi >= 5.1 && psi <= 5.7) tree = CENTER.east(dx);
        }
        h.assertTrue(tree != null, "no space with 5.1–5.7 psi at the crown on the site");
        for (int i = 0; i < 4; i++) h.setBlock(tree.above(i), Blocks.OAK_LOG);
        for (BlockPos p : BlockPos.betweenClosed(tree.offset(-2, 3, -2), tree.offset(2, 5, 2))) {
            if (h.getBlockState(p).isAir()) h.setBlock(p, Blocks.OAK_LEAVES);
        }
        BlockPos glass = CENTER.west(29), bricks = CENTER.north(25), planks = CENTER.south(18);
        h.setBlock(glass, Blocks.GLASS);
        for (BlockPos p : BlockPos.betweenClosed(bricks.offset(-1, 0, -1), bricks.offset(1, 1, 0))) h.setBlock(p, Blocks.STONE_BRICKS);
        h.setBlock(planks, Blocks.OAK_PLANKS);

        scarAll(h, d);

        String at = String.format(Locale.ROOT, " (at the crown %.1f psi)", d.psi(Vec3.atCenterOf(h.absolutePos(tree.above(4)))));
        h.assertBlockNotPresent(Blocks.GLASS, glass);
        h.assertBlockNotPresent(Blocks.OAK_PLANKS, planks);
        for (BlockPos p : BlockPos.betweenClosed(bricks.offset(-1, 0, -1), bricks.offset(1, 1, 0))) h.assertBlockPresent(Blocks.STONE_BRICKS, p.immutable());
        h.assertTrue(!h.getBlockState(tree.above()).is(BlockTags.LOGS), "tree trunk stands" + at);
        int lying = 0;
        for (int i = 0; i < 6; i++) {
            var s = h.getBlockState(tree.east(i));
            if (s.is(BlockTags.LOGS) && s.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X) lying++;
        }
        h.assertTrue(lying >= 3, "the tree did not lie down from the epicenter: logs along x " + lying + at);
        for (BlockPos p : BlockPos.betweenClosed(tree.offset(-2, 3, -2), tree.offset(2, 5, 2))) {
            h.assertTrue(!h.getBlockState(p).is(BlockTags.LEAVES), "foliage hangs without a trunk in " + p.toShortString() + at);
        }
        h.assertTrue(!h.getBlockState(CENTER.east(10).below()).isAir(), "the wave touched the ground");
        h.succeed();
    }

    /**
     * Ruins: stone brick tower 3×3×4 in 480 m from 15 kt near the ground (1 block = 40 m, ~30 psi) is collapsing, in its place is
     *  a blockage high; chunk height maps - for ruins (replacement of entire sections, without {@code setBlock} for each block).
     *  Debris for players ({@link RuinPlan#thrown}) - demolished places of the tower with its block; the smaller limit is the beginning of the same list.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_ruins", skyAccess = true)
    public static void collapsedBuildingLeavesRubble(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos tower = CENTER.east(12);
        for (BlockPos p : BlockPos.betweenClosed(tower.offset(-1, 0, -1), tower.offset(1, 3, 1))) h.setBlock(p, Blocks.STONE_BRICKS);
        List<RuinPlan> plans = scarAll(h, detonation(h, CENTER, 0, 15, 0.025f), new ColumnScar.Budget(false));
        thrownTower(h, plans, tower);
        for (BlockPos p : BlockPos.betweenClosed(tower.offset(-1, 0, -1), tower.offset(1, 0, 1))) {
            BlockPos q = p.immutable();
            var rubble = h.getBlockState(q);
            h.assertTrue(rubble.is(Blocks.GRAVEL) || rubble.is(Blocks.COBBLESTONE) || rubble.is(Blocks.ANDESITE) || rubble.is(Blocks.TUFF),
                    "no blockage in " + q.toShortString() + ": " + rubble);
            for (int y = 1; y <= 3; y++) h.assertTrue(h.getBlockState(q.above(y)).isAir(), "the tower stands: " + q.above(y).toShortString());
            BlockPos abs = h.absolutePos(q);
            h.assertTrue(level.getHeight(Heightmap.Types.WORLD_SURFACE, abs.getX(), abs.getZ()) == abs.getY() + 1,
                    "height map not for ruins: " + level.getHeight(Heightmap.Types.WORLD_SURFACE, abs.getX(), abs.getZ()) + " instead of " + (abs.getY() + 1));
        }
        h.succeed();
    }

    /**
     * Tower of 150 blocks at the epicenter of 15 kt at the optimal height (1 block = 3.3 m) on the corner of four chunks: walls made of
     *  terracotta with windows, ceilings through 5 blocks, solid column of calcite 2×2 and a corner made of diorite. Terracotta,
     *  calcite and diorite are soil blocks, but here it is a building: from the tower on both sides of the boundaries of the chunks there remains a blockage
     *  and, in the worst case, a thin skeleton from the ground, nothing hangs in the air. Ruins built in advance (during the
     *  flight) and those built on site are the same.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_tower", skyAccess = true)
    public static void towerAtGroundZeroFalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos abs = h.absolutePos(CENTER);
        // the center of the tower is at the corner of the chunks: a quarter of the tower in each
        BlockPos c = CENTER.offset(abs.getX() == h.absolutePos(CENTER.east()).getX() - 1 ? -(abs.getX() & 15) : abs.getX() & 15, 0,
                abs.getZ() == h.absolutePos(CENTER.south()).getZ() - 1 ? -(abs.getZ() & 15) : abs.getZ() & 15);
        BlockPos ca = h.absolutePos(c);
        h.assertTrue((ca.getX() & 15) == 0 && (ca.getZ() & 15) == 0, "the tower is not at the corner of the chunks: " + ca.toShortString());
        Detonation d = detonation(h, CENTER, Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f);
        // ruins in advance: plans for an untouched city, then replacement
        int built = tower(h, c);
        List<RuinPlan> ahead = new java.util.ArrayList<>();
        List<LevelChunk> chunks = chunks(h);
        for (LevelChunk chunk : chunks) ahead.add(RuinPlanner.plan(level, d, chunk));
        for (int i = 0; i < chunks.size(); i++) h.assertTrue(ahead.get(i).apply(level, chunks.get(i), d, new ColumnScar.Budget(false)), "the plan is already outdated");
        int standingAhead = towerFell(h, d, c, built, "in advance");
        // the same tower again - ruins in place, chunk by chunk at the time of the wave
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-TOWER_HALF, 0, -TOWER_HALF), c.offset(TOWER_HALF, TOWER_HEIGHT, TOWER_HALF))) {
            level.setBlock(h.absolutePos(p), Blocks.AIR.defaultBlockState(), 2);
        }
        h.assertTrue(tower(h, c) == built, "the tower did not rise again");
        scarAll(h, d, new ColumnScar.Budget(false));
        int standingFresh = towerFell(h, d, c, built, "on site");
        h.assertTrue(standingAhead == standingFresh, "the ruins in advance and on site are different: above the rubble " + standingAhead + " and " + standingFresh + " blocks");
        h.succeed();
    }

    /** Remains of the tower chunk {@code tower} (3×3, rubble in the bottom row): all demolished places above the rubble, with bricks. */
    private static void thrownTower(GameTestHelper h, List<RuinPlan> plans, BlockPos tower) {
        BlockPos at = h.absolutePos(tower);
        List<LevelChunk> chunks = chunks(h);
        int i = 0;
        while (i < chunks.size() && !chunks.get(i).getPos().equals(new ChunkPos(at))) i++;
        h.assertTrue(i < chunks.size(), "tower chunk is not on the site");
        LevelChunk chunk = chunks.get(i);
        var all = plans.get(i).thrown(chunk, 4096);
        for (var p : all) {
            h.assertTrue(h.getLevel().getBlockState(p.pos()).isAir(), "fragment from the place where the block stands: " + p.pos().toShortString());
            h.assertFalse(p.state().isAir(), "a piece of air in " + p.pos().toShortString());
        }
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-1, 1, -1), at.offset(1, 3, 1))) {
            if (new ChunkPos(q).equals(chunk.getPos())) {
                BlockPos b = q.immutable();
                h.assertTrue(all.stream().anyMatch(p -> p.pos().equals(b) && p.state().is(Blocks.STONE_BRICKS)), "no tower fragment in " + b.toShortString());
            }
        }
        var few = plans.get(i).thrown(chunk, 5);
        h.assertTrue(few.equals(all.subList(0, Math.min(5, all.size()))), "small limit is not the beginning of the list: " + few + " and " + all.subList(0, Math.min(5, all.size())));
    }

    private static final int TOWER_HEIGHT = 150, TOWER_HALF = 4;

    /**
     * The same concrete tower on a site without backfill: there is air under the template site, and it has no support even before the impact
     *  (for collapse - all {@code INF}). A building not made of soil falls like this: a block without support and before the impact collapses
     *  in a cascade from being broken by a wave - nothing hangs (backfill in other tests hides this case).
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_tower_floating", skyAccess = true)
    public static void concreteTowerOnFloatingPadFalls(GameTestHelper h) {
        BlockPos c = chunkCorner(h);
        Detonation d = detonation(h, CENTER, Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f, false);
        h.assertTrue(h.getBlockState(BlockPos.ZERO.offset(5, 0, 5)).isAir(), "there is no air under the platform: the test does not check anything");
        int built = tower(h, c, true);
        scarAll(h, d, new ColumnScar.Budget(false));
        towerFell(h, d, c, built, "concrete without backfill");
        h.succeed();
    }

    private static int tower(GameTestHelper h, BlockPos c) {
        return tower(h, c, false);
    }

    /** Tower {@link #towerAtGroundZeroFalls} (concrete - all made of concrete: not built from soil blocks); how many blocks have been delivered? */
    private static int tower(GameTestHelper h, BlockPos c, boolean concrete) {
        int built = 0;
        for (int y = 0; y < TOWER_HEIGHT; y++) {
            for (int dx = -TOWER_HALF; dx <= TOWER_HALF; dx++) {
                for (int dz = -TOWER_HALF; dz <= TOWER_HALF; dz++) {
                    boolean wall = Math.abs(dx) == TOWER_HALF || Math.abs(dz) == TOWER_HALF;
                    BlockState st = null;
                    if (dx == TOWER_HALF && dz == TOWER_HALF) st = (concrete ? Blocks.LIGHT_GRAY_CONCRETE : Blocks.DIORITE).defaultBlockState();
                    else if (wall) st = (y % 5 == 2 || y % 5 == 3) && Math.abs(dx + dz) % 2 == 1 ? Blocks.GLASS.defaultBlockState()
                            : (concrete ? Blocks.GRAY_CONCRETE : Blocks.BROWN_TERRACOTTA).defaultBlockState();
                    else if (dx >= -1 && dx <= 0 && dz >= -1 && dz <= 0) st = (concrete ? Blocks.CYAN_CONCRETE : Blocks.CALCITE).defaultBlockState();
                    else if (y % 5 == 4) st = (concrete ? Blocks.WHITE_CONCRETE : Blocks.WHITE_TERRACOTTA).defaultBlockState();
                    if (st != null && h.getLevel().setBlock(h.absolutePos(c.offset(dx, y, dz)), st, 2)) built++;
                }
            }
        }
        h.assertTrue(built > TOWER_HEIGHT * 32, "the tower did not fit into the world: " + built + " blocks");
        return built;
    }

    /** For the message: what is in the tower in 10 blocks - the core and everything else. */
    private static String profile(GameTestHelper h, BlockPos c) {
        StringBuilder out = new StringBuilder("stands at the waist (core/rest):");
        for (int y0 = 0; y0 < TOWER_HEIGHT; y0 += 10) {
            int core = 0, rest = 0;
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-TOWER_HALF, y0, -TOWER_HALF), c.offset(TOWER_HALF, y0 + 9, TOWER_HALF))) {
                BlockState st = h.getBlockState(p);
                if (st.isAir()) continue;
                if (st.is(Blocks.CALCITE) || st.is(Blocks.CYAN_CONCRETE)) core++;
                else rest++;
            }
            out.append(' ').append(core).append('/').append(rest);
        }
        return out.toString();
    }

    /** From the tower there is a blockage and no more than 5% of blocks above it, nothing hangs above the void; how many blocks are above the rubble? */
    private static int towerFell(GameTestHelper h, Detonation d, BlockPos c, int built, String path) {
        int standing = 0, rubble = 0;
        for (int dx = -TOWER_HALF; dx <= TOWER_HALF; dx++) {
            for (int dz = -TOWER_HALF; dz <= TOWER_HALF; dz++) {
                BlockPos col = c.offset(dx, 0, dz);
                BlockState first = h.getBlockState(col);
                if (first.is(Blocks.GRAVEL) || first.is(Blocks.COBBLESTONE) || first.is(Blocks.ANDESITE) || first.is(Blocks.TUFF)) rubble++;
                int air = 0;
                for (int y = 0; y < TOWER_HEIGHT; y++) {
                    BlockState st = h.getBlockState(col.above(y));
                    if (st.isAir()) {
                        air++;
                        continue;
                    }
                    if (y >= 4) standing++;
                    if (air >= 3) h.fail(path + ": hanging in the air " + st + " in " + col.above(y).toShortString() + " over the void in " + air + " blocks; " + profile(h, c));
                    air = 0;
                }
            }
        }
        h.assertTrue(standing * 20 <= built, path + ": the tower stands higher than the rubble " + standing + " blocks of " + built
                + " (pressure at the tower " + String.format(Locale.ROOT, "%.0f", d.psi(Vec3.atCenterOf(h.absolutePos(c)))) + " psi)");
        h.assertTrue(rubble * 2 >= (2 * TOWER_HALF + 1) * (2 * TOWER_HALF + 1), path + ": there is not enough blockage - " + rubble + " columns");
        return standing;
    }

    /**
     * Thin pillars of 30 blocks (terracotta, copper, chain through two glasses - facade of windows with lintels) at the epicenter 15 kt
     *  and where near the ground 5–10 and 2.5–4.5 psi (1 block ≈ 67 m): the glass is broken, the lintels do not hang above them - only the bottom and rubble of the pillar remain, not a single block above the void (not a chain of dots in the sky above the ruins).
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_columns", skyAccess = true)
    public static void thinColumnsFall(GameTestHelper h) {
        Detonation d = detonation(h, CENTER, Yield.optimalBurstHeight(15) * 0.015, 15, 0.015f);
        BlockPos mid = null, far = null;
        for (int dx = 2; dx <= 30; dx++) {
            double psi = d.psi(Vec3.atCenterOf(h.absolutePos(CENTER.east(dx))));
            if (mid == null && psi >= 5 && psi <= 10) mid = CENTER.east(dx);
            if (far == null && psi >= 2.5 && psi <= 4.5) far = CENTER.east(dx).south(2);
        }
        h.assertTrue(mid != null && far != null, "no places with 5–10 and 2.5–4.5 psi on the site: at the edge " + String.format(Locale.ROOT, "%.1f",
                d.psi(Vec3.atCenterOf(h.absolutePos(CENTER.east(30))))) + " psi");
        BlockPos[] columns = {CENTER.north(3), mid, far};
        BlockState[] bands = {Blocks.BROWN_TERRACOTTA.defaultBlockState(), Blocks.CUT_COPPER.defaultBlockState(), Blocks.CHAIN.defaultBlockState()};
        for (BlockPos c : columns) {
            for (int y = 0; y < 30; y++) h.setBlock(c.above(y), y % 3 == 0 ? bands[(y / 3) % bands.length] : Blocks.GLASS.defaultBlockState());
        }
        scarAll(h, d, new ColumnScar.Budget(false));
        for (BlockPos c : columns) {
            String at = c.toShortString() + " (" + String.format(Locale.ROOT, "%.0f", d.psi(Vec3.atCenterOf(h.absolutePos(c)))) + " psi)";
            int air = 0;
            for (int y = 0; y < 30; y++) {
                BlockState st = h.getBlockState(c.above(y));
                if (st.isAir()) {
                    air++;
                    continue;
                }
                if (air >= 2) h.fail("hanging in the air " + st + " on " + y + " pillar block " + at);
                if (y >= 5) h.fail("the pillar stands: " + st + " on " + y + " block, " + at);
                air = 0;
            }
        }
        h.succeed();
    }

    /** The angle of four chunks at the center of the site (relative coordinates), like {@link #towerAtGroundZeroFalls}. */
    static BlockPos chunkCorner(GameTestHelper h) {
        BlockPos abs = h.absolutePos(CENTER);
        BlockPos c = CENTER.offset(abs.getX() == h.absolutePos(CENTER.east()).getX() - 1 ? -(abs.getX() & 15) : abs.getX() & 15, 0,
                abs.getZ() == h.absolutePos(CENTER.south()).getZ() - 1 ? -(abs.getZ() & 15) : abs.getZ() & 15);
        BlockPos ca = h.absolutePos(c);
        h.assertTrue((ca.getX() & 15) == 0 && (ca.getZ() & 15) == 0, "not the corner of the chunks: " + ca.toShortString());
        return c;
    }

    /**
     * The same tower with a pool on the 25 floor (water on the ceiling between the walls): the water in the column does not make the building
     *  a relief - the tower collapses, the water goes away with it, nothing hangs. Detonation - exactly above the core of the tower: the core stands
     *  (it cannot be knocked over from above), the walls fall, and the bottom of the pool (terracotta under water is not thin, the wave does not break it) the
     *  core remains at the same distance to the support as before the impact - without the hanging rule masonry
     * ({@code Collapse.unspanned}) the ring of the slab hung around the core in the air (CI: the center of the site at the corner of the chunks).
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_pool", skyAccess = true)
    public static void towerWithPoolFalls(GameTestHelper h) {
        BlockPos c = chunkCorner(h);
        Detonation d = detonation(h, c, Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f);
        int built = tower(h, c);
        pool(h, c);
        scarAll(h, d, new ColumnScar.Budget(false));
        towerFell(h, d, c, built, "with swimming pool");
        h.succeed();
    }

    /** Tower with a pool, explosion in the center of the site (not above the core): the location of the tower at the corners of the chunks - whichever one fell out. */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_pool_off", skyAccess = true)
    public static void towerWithPoolFallsOffCore(GameTestHelper h) {
        BlockPos c = chunkCorner(h);
        Detonation d = detonation(h, CENTER, Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f);
        int built = tower(h, c);
        pool(h, c);
        scarAll(h, d, new ColumnScar.Budget(false));
        towerFell(h, d, c, built, "with a pool, the explosion is not above the core");
        h.succeed();
    }

    /** Swimming pool on the 25 floor of the {@link #tower} tower: water on the ceiling between the walls, around the core. */
    private static void pool(GameTestHelper h, BlockPos c) {
        for (int dx = 1 - TOWER_HALF; dx < TOWER_HALF; dx++) {
            for (int dz = 1 - TOWER_HALF; dz < TOWER_HALF; dz++) {
                if (dx >= -1 && dx <= 0 && dz >= -1 && dz <= 0) continue;
                for (int y = 25; y <= 26; y++) h.getLevel().setBlock(h.absolutePos(c.offset(dx, y, dz)), Blocks.WATER.defaultBlockState(), 2);
            }
        }
    }

    /**
     * A blank wall (terracotta without windows or ceilings) is exactly on the border of the chunks, a house with ceilings is in the next chunk:
     *  the wall is part of the building and collapses with it, and does not stand at its full height (in a strip along the border of the chunks).
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_chunk_wall", skyAccess = true)
    public static void blankWallOnChunkLineFalls(GameTestHelper h) {
        BlockPos c = chunkCorner(h);
        int height = 60;
        // the wall is in the last column of the western chunk (lx = 15), the house is in the eastern
        for (int y = 0; y < height; y++) {
            for (int dx = -1; dx <= 5; dx++) {
                for (int dz = 2; dz <= 8; dz++) {
                    boolean edge = dx == -1 || dx == 5 || dz == 2 || dz == 8;
                    BlockState st = null;
                    if (dx == -1) st = Blocks.BROWN_TERRACOTTA.defaultBlockState();
                    else if (edge) st = y % 5 == 2 && (dx + dz) % 2 == 0 ? Blocks.GLASS.defaultBlockState() : Blocks.BROWN_TERRACOTTA.defaultBlockState();
                    else if (y % 5 == 4) st = Blocks.WHITE_TERRACOTTA.defaultBlockState();
                    if (st != null) h.getLevel().setBlock(h.absolutePos(c.offset(dx, y, dz)), st, 2);
                }
            }
        }
        h.assertTrue((h.absolutePos(c.offset(-1, 0, 0)).getX() & 15) == 15, "the wall is not on the border of the chunks");
        Detonation d = detonation(h, CENTER, Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f);
        scarAll(h, d, new ColumnScar.Budget(false));
        for (int dz = 2; dz <= 8; dz++) {
            for (int y = 5; y < height; y++) {
                BlockState st = h.getBlockState(c.offset(-1, y, dz));
                if (!st.isAir()) h.fail("a blank wall on the border of the chunks stands: " + st + " on " + y + " block, " + c.offset(-1, y, dz).toShortString());
            }
        }
        h.succeed();
    }

    /**
     * Brick houses 5×7 on five floors (floors across the 4 block, roof on the 20-m) where above the window ~4.5 psi: one
     *  has a window in the wall to the explosion 3×3 - the glass is broken, the piers on the sides of the window and the wall above it stand (they are held by transverse
     *  walls and ceilings: the shoulder of the pier is the floor); at the other window there is a ribbon along all the walls - the top of the house does not stand on anything
     *  and falls entirely, neither brick nor glass hangs above the void (a rubble blockage on the collapsed one is possible).
     * <p>
     *  Why is this so. A free-standing wall 20 of blocks in a block thickness with 4.5 psi overturns entirely, like the
     *  real one - therefore the walls in the house have transverse walls and ceilings; above the window, 12 rows are checked.
     * The pressure is taken from the wall above the window (8 blocks above the ground): the element there is checked. The place is the first from the epicenter,
     * , where the pressure dropped to 4.5 psi (the lower limit of 3.5 - just so that the search step does not overshoot the band); scale
     * 0.018 - so that it fits on the site with both houses. Detonation near the ground: at a real house in 1,6 km from
     *  air 15 kt the pressure at its height is almost the same (Mach stem), and on the site a house in 20 blocks is 1,1 km
     *  height, above the air blast (11 blocks), and the bottom of the house would be under 6–7 psi; for ground - 4.9 near the ground, 4.3 above
     *  window, 3.1 under the roof.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_window", skyAccess = true)
    public static void wallAboveWindowStandsOnlyWithPiers(GameTestHelper h) {
        Detonation d = detonation(h, CENTER, 0, 15, 0.018f);
        BlockPos far = null;
        for (int dx = 2; dx <= 26 && far == null; dx++) {
            // pressure at the wall above the window
            double psi = d.psi(Vec3.atCenterOf(h.absolutePos(CENTER.east(dx).above(8))));
            if (psi >= 3.5 && psi <= 4.5) far = CENTER.east(dx);
        }
        h.assertTrue(far != null, "no space with 3.5–4.5 psi on site");
        BlockPos piers = far.north(8), ribbon = far.south(4);
        house(h, piers, 7, HOUSE_HEIGHT, (dx, y, dz) -> dx == 0 && y >= 5 && y <= 7 && dz >= 2 && dz <= 4);
        house(h, ribbon, 5, HOUSE_HEIGHT, (dx, y, dz) -> y >= 5 && y <= 7);
        scarAll(h, d, new ColumnScar.Budget(false));
        String at = far.toShortString() + " (" + String.format(Locale.ROOT, "%.1f", d.psi(Vec3.atCenterOf(h.absolutePos(far.above(8))))) + " psi)";
        for (int dz = 2; dz <= 4; dz++) {
            for (int y = 5; y <= 7; y++) h.assertTrue(!h.getBlockState(piers.south(dz).above(y)).is(Blocks.GLASS), "the glass is intact " + y + " block, " + at);
            for (int y = 8; y < HOUSE_HEIGHT; y++) {
                h.assertTrue(h.getBlockState(piers.south(dz).above(y)).is(Blocks.BRICKS), "there is no wall above the window with piers: " + y + " block, " + at);
            }
        }
        for (int dz : new int[] {1, 5}) {
            for (int y = 5; y <= 7; y++) {
                h.assertTrue(h.getBlockState(piers.south(dz).above(y)).is(Blocks.BRICKS), "the pier by the window fell: " + dz + ", " + y + " block, " + at);
            }
        }
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                for (int y = 5; y <= HOUSE_HEIGHT; y++) {
                    BlockState st = h.getBlockState(ribbon.offset(dx, y, dz));
                    // a rubble block on a collapsed corner - not a hanging wall
                    if (st.is(Blocks.BRICKS) || st.is(Blocks.GLASS)) h.fail("the top of the house hangs above the windows with a ribbon: " + st + " in " + ribbon.offset(dx, y, dz).toShortString() + ", " + at);
                }
            }
        }
        h.succeed();
    }

    /** The height of the walls of the house in {@link #house}: five floors along the 4 block. */
    private static final int HOUSE_HEIGHT = 20;

    private interface Glazing {
        boolean glass(int dx, int y, int dz);
    }

    /**
     * Brick house 5 (by x) × {@code width} (by z): walls {@code height} blocks, floors through 4 block (on 4-m, 8-m...)
     *  and roof; glass - where {@code glazing} says.
     */
    private static void house(GameTestHelper h, BlockPos at, int width, int height, Glazing glazing) {
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < width; dz++) {
                for (int y = 0; y <= height; y++) {
                    boolean wall = dx == 0 || dx == 4 || dz == 0 || dz == width - 1;
                    if (y < height && !wall && (y == 0 || y % 4 != 0)) continue;
                    h.setBlock(at.offset(dx, y, dz), y < height && wall && glazing.glass(dx, y, dz) ? Blocks.GLASS : Blocks.BRICKS);
                }
            }
        }
    }

    /**
     * 15 kt near the ground, 1 block = 40 m, in 240 m (~135 psi): the bunker under two blocks of soil is not touched
     *  (under the arch there is no air with the sky - there is no difference), pillar obsidian and bedrock stand (strength ≥ 50 and
     *  indestructible), the free-standing narrow core 4×4×40 of calcite overturns and collapses entirely.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_bunker", skyAccess = true)
    public static void bunkerStaysSlenderCoreFalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos bunker = CENTER.east(6).below(5);
        List<BlockPos> shell = new java.util.ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(bunker.offset(-2, -1, -2), bunker.offset(2, 4, 2))) {
            BlockPos q = p.immutable();
            boolean inside = Math.abs(q.getX() - bunker.getX()) <= 1 && Math.abs(q.getZ() - bunker.getZ()) <= 1 && q.getY() >= bunker.getY() && q.getY() <= bunker.getY() + 2;
            if (inside) level.setBlock(h.absolutePos(q), Blocks.AIR.defaultBlockState(), 2);
            else shell.add(q);
        }
        BlockPos obsidian = CENTER.south(6), bedrock = CENTER.west(6);
        for (int i = 0; i < 3; i++) {
            h.setBlock(obsidian.above(i), Blocks.OBSIDIAN);
            h.setBlock(bedrock.above(i), Blocks.BEDROCK);
        }
        BlockPos core = CENTER.north(10);
        int built = 0;
        for (BlockPos p : BlockPos.betweenClosed(core.offset(-2, 0, -2), core.offset(1, 39, 1))) {
            if (level.setBlock(h.absolutePos(p), Blocks.CALCITE.defaultBlockState(), 2)) built++;
        }
        scarAll(h, detonation(h, CENTER, 0, 15, 0.025f), new ColumnScar.Budget(false));
        for (BlockPos q : shell) h.assertTrue(!h.getBlockState(q).isAir(), "the bunker is broken into " + q.toShortString());
        for (int i = 0; i < 3; i++) {
            h.assertBlockPresent(Blocks.OBSIDIAN, obsidian.above(i));
            h.assertBlockPresent(Blocks.BEDROCK, bedrock.above(i));
        }
        int standing = 0;
        for (BlockPos p : BlockPos.betweenClosed(core.offset(-2, 0, -2), core.offset(1, 39, 1))) if (h.getBlockState(p).is(Blocks.CALCITE)) standing++;
        StringBuilder rows = new StringBuilder();
        for (int y = 0; y < 40; y++) {
            int n = 0;
            for (BlockPos p : BlockPos.betweenClosed(core.offset(-2, y, -2), core.offset(1, y, 1))) if (h.getBlockState(p).is(Blocks.CALCITE)) n++;
            rows.append(n).append(y % 10 == 9 ? " | " : " ");
        }
        h.assertTrue(standing * 10 <= built, "core 4×4 costs: " + standing + " blocks of " + built + ", in the rows below: " + rows);
        h.succeed();
    }

    /**
     * The ruins of a chunk according to the plan in advance and according to the plan in place, when the ruins of the neighboring chunk are already standing, are the same, right down to
     *  places of fires: the plan reads the neighbor as the original one (old blocks of places of his plan, a map of heights to the ruins - for the shadow of the light
     *  pulse). A two-story brick house across the corner of four chunks at ~4 psi; windows - only in half to the explosion:
     * pressure in the far half of the house comes through the nearby chunks. Behind the house there is turf that sets the light on, in the shadow there is no house. The explosion is in 28 blocks from the corner, always on the site: the corner of the chunks happens in 17–47 blocks from its edge, and the explosion
     *  from the west would go behind a wall of barriers that blocks the light of the entire turf (“not a single fire” when detonating
     *  11 blocks beyond the western edge of the site).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_fresh", skyAccess = true)
    public static void freshPlanNextToRuinsMatchesPrepared(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = chunkCorner(h);
        // explosion from the side where there are more than 28 blocks to the edge of the site (0 and 63)
        int side = c.getX() >= 32 ? -1 : 1;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, 0, -4), c.offset(4, 8, 4))) {
            int dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ(), y = p.getY() - c.getY();
            boolean wall = Math.abs(dx) == 4 || Math.abs(dz) == 4, slab = (y == 4 || y == 8) && !wall;
            if (!wall && !slab && y != 8) continue;
            boolean window = wall && y < 8 && dx * side > 0 && (y % 4 == 1 || y % 4 == 2) && Math.floorMod(dx + dz, 3) == 1;
            h.setBlock(p, window ? Blocks.GLASS : Blocks.BRICKS);
        }
        BlockPos burstAt = c.offset(28 * side, 0, 0);
        h.assertTrue(burstAt.getX() >= 2 && burstAt.getX() <= 61, "explosion beyond the edge of the site: " + burstAt.toShortString());
        Detonation d = atPsi(h, burstAt, false, c.above(4), 4);
        List<LevelChunk> chunks = chunks(h);
        List<RuinPlan> ahead = new java.util.ArrayList<>();
        for (LevelChunk chunk : chunks) ahead.add(RuinPlanner.plan(level, d, chunk));
        int changed = 0, fires = 0, charred = 0;
        for (int i = 0; i < chunks.size(); i++) {
            // on the spot: the plan is renewed in chunks, when the neighbors from the west are already in ruins
            RuinPlan fresh = RuinPlanner.plan(level, d, chunks.get(i));
            String diff = fresh.differs(ahead.get(i));
            if (diff == null) diff = RuinBackgroundGameTests.flashDiffers(fresh, ahead.get(i));
            if (diff != null) h.fail("chunk " + chunks.get(i).getPos() + ": the ruins in place are not the same as in advance (in place | in advance; y - from the bottom of the world): " + diff);
            charred += fresh.flash().length;
            h.assertTrue(fresh.apply(level, chunks.get(i), d, new ColumnScar.Budget(true)), "the on-site plan is outdated");
            changed += fresh.changedBlocks();
            fires += fresh.fireCount();
        }
        h.assertTrue(changed > 0, "the house is untouched");
        h.assertTrue(charred > 0, "there are no burning flashes on the house: there is nothing to compare");
        if (fires == 0) {
            // analysis: setting up fires, turf under the sky on the site and its light
            int grass = 0, open = 0;
            double most = 0;
            BlockPos top = null;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(0, 0, 0)), h.absolutePos(new BlockPos(63, 40, 63)))) {
                if (!level.getBlockState(p).is(Blocks.GRASS_BLOCK)) continue;
                grass++;
                if (level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()) == p.getY() + 1) open++;
                double f = d.fluence(Vec3.atCenterOf(p).add(0, 1, 0));
                if (f > most) most = f;
            }
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(0, 41, 0)), h.absolutePos(new BlockPos(63, 120, 63)))) {
                if (!level.getBlockState(p).isAir() && (top == null || p.getY() > top.getY())) top = p.immutable();
            }
            // what happened to the top of the platform (y 10: template turf) and can the ball see it in the living world
            int kept = 0, burnt = 0, gone = 0, seen = 0, fireball = 0;
            for (int x = 0; x < 64; x++) {
                for (int z = 0; z < 64; z++) {
                    BlockPos p = h.absolutePos(new BlockPos(x, 10, z));
                    BlockState st = level.getBlockState(p);
                    if (st.is(Blocks.GRASS_BLOCK)) kept++;
                    else if (st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT)) burnt++;
                    else gone++;
                    Vec3 at = Vec3.atCenterOf(p).add(0, 0.5, 0);
                    if (Math.hypot(at.x - d.burst().x, at.z - d.burst().z) < d.fireballRadius() * 0.8) fireball++;
                    else if (ua.zentix.incoming.nuclear.world.ThermalShadow.visible(level, d.burst(), at) && d.fluence(at) >= 5) seen++;
                }
            }
            h.fail("not a single fire: comparison of fire sites did not check anything (top of the site after the ruins: turf " + kept
                    + ", scorched " + burnt + ", other " + gone + "; in a ball " + fireball + ", outside the ball they see the ball in the light ≥ 5 — " + seen
                    + "; ball " + String.format(Locale.ROOT, "%.1f", d.fireballRadius()) + "; fires in the setting "
                    + IncomingConfig.SERVER.nukeFires.get() + ", turf " + grass + ", under the sky " + open + ", light at the turf before "
                    + String.format(Locale.ROOT, "%.1f", most) + ", above the platform " + (top == null ? "nothing" : level.getBlockState(top) + " " + top.toShortString())
                    + ", detonation " + d.burst() + ", angle " + h.absolutePos(c).toShortString() + ")");
        }
        h.succeed();
    }

    /**
     * Skyscraper 11×11×120 at the epicenter: walls with windows, floors through 4 block, solid core 3×3 made of calcite
     *  (staircases and elevator shafts) — the whole collapses, the core too: no more than 5% of the blocks above the rubble, nothing hangs.
     *  Explosion - above the house away from the core, always in the same place: exactly above the thin core (in 1–2 blocks) the sides are loaded
     *  equally, and it stands stumped to a height where pressure from the side breaks it (like a Genbaku dome); the location of the explosion,
     *  site-dependent (up to 15 blocks from the house), would make the test random.
     */
    @GameTest(template = "range", timeoutTicks = 80, batch = "nuke_skyscraper", skyAccess = true)
    public static void skyscraperAtGroundZeroFalls(GameTestHelper h) {
        BlockPos c = chunkCorner(h);
        int half = 5, height = 120, built = 0;
        for (int y = 0; y < height; y++) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    boolean wall = Math.abs(dx) == half || Math.abs(dz) == half;
                    BlockState st = null;
                    if (wall) st = y % 4 != 3 && Math.abs(dx + dz) % 3 != 0 ? Blocks.GLASS.defaultBlockState() : Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
                    else if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) st = Blocks.CALCITE.defaultBlockState();
                    else if (y % 4 == 3) st = Blocks.SMOOTH_STONE.defaultBlockState();
                    if (st != null && h.getLevel().setBlock(h.absolutePos(c.offset(dx, y, dz)), st, 2)) built++;
                }
            }
        }
        Detonation d = detonation(h, c.offset(6, 0, 4), Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f);
        scarAll(h, d, new ColumnScar.Budget(false));
        int standing = 0;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int air = 0;
                for (int y = 0; y < height; y++) {
                    BlockState st = h.getBlockState(c.offset(dx, y, dz));
                    if (st.isAir()) {
                        air++;
                        continue;
                    }
                    if (y >= 5) standing++;
                    if (air >= 2) h.fail("hanging in the air " + st + " in " + c.offset(dx, y, dz).toShortString());
                    air = 0;
                }
            }
        }
        h.assertTrue(standing * 20 <= built, "the skyscraper stands: above the rubble " + standing + " blocks of " + built);
        h.succeed();
    }

    /**
     * The facade of slabs, steps and glass panels (pillars 5×30) where near the ground 5–10 psi: collapses, nothing hangs.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_facade", skyAccess = true)
    public static void slabStairPaneFacadeFalls(GameTestHelper h) {
        Detonation d = detonation(h, CENTER, Yield.optimalBurstHeight(15) * 0.02, 15, 0.02f);
        BlockPos mid = null;
        for (int dx = 2; dx <= 30 && mid == null; dx++) {
            double psi = d.psi(Vec3.atCenterOf(h.absolutePos(CENTER.east(dx))));
            if (psi >= 5 && psi <= 10) mid = CENTER.east(dx);
        }
        h.assertTrue(mid != null, "no space with 5–10 psi on site");
        BlockState[] rows = {Blocks.STONE_BRICK_SLAB.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState(),
                Blocks.STONE_BRICK_STAIRS.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState()};
        for (int dz = 0; dz < 5; dz++) {
            for (int y = 0; y < 30; y++) h.setBlock(mid.south(dz).above(y), rows[y % rows.length]);
        }
        scarAll(h, d, new ColumnScar.Budget(false));
        String at = mid.toShortString() + " (" + String.format(Locale.ROOT, "%.0f", d.psi(Vec3.atCenterOf(h.absolutePos(mid)))) + " psi)";
        for (int dz = 0; dz < 5; dz++) {
            int air = 0;
            for (int y = 0; y < 30; y++) {
                BlockState st = h.getBlockState(mid.south(dz).above(y));
                if (st.isAir()) {
                    air++;
                    continue;
                }
                if (air >= 2) h.fail("hanging in the air " + st + " on " + y + " facade block, " + at);
                if (y >= 5) h.fail("façade stands: " + st + " on " + y + " block, " + at);
                air = 0;
            }
        }
        h.succeed();
    }

    /**
     * Tower 5×5×60 on the river bank (the bed of the 3 block near its wall, stone bottom) at the epicenter: the tower is collapsing, the bottom and bank
     *  are intact, after 60 tick there is no water anywhere above the void (walls of water no: water has flowed into the hole or glass).
     */
    @GameTest(template = "range", timeoutTicks = 160, batch = "nuke_shore", skyAccess = true)
    public static void shoreTowerFallsWithoutWaterWalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos t = CENTER.offset(-2, 0, -2);
        List<BlockPos> bed = new java.util.ArrayList<>();
        // bank and bottom: stone on 6 blocks down under the river and tower; channel along the eastern wall of the tower
        for (BlockPos p : BlockPos.betweenClosed(t.offset(-3, -6, -3), t.offset(10, -1, 8))) {
            level.setBlock(h.absolutePos(p), Blocks.STONE.defaultBlockState(), 2);
            bed.add(p.immutable());
        }
        for (BlockPos p : BlockPos.betweenClosed(t.offset(5, -3, -3), t.offset(7, -1, 8))) level.setBlock(h.absolutePos(p), Blocks.WATER.defaultBlockState(), 2);
        bed.removeIf(p -> p.getX() >= t.getX() + 5 && p.getX() <= t.getX() + 7 && p.getY() >= t.getY() - 3);
        for (int y = 0; y < 60; y++) {
            for (int dx = 0; dx < 5; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    boolean wall = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    BlockState st = wall ? (y % 4 == 2 && (dx + dz) % 2 == 1 ? Blocks.GLASS : Blocks.BROWN_TERRACOTTA).defaultBlockState()
                            : y % 4 == 3 ? Blocks.WHITE_TERRACOTTA.defaultBlockState() : null;
                    if (st != null) level.setBlock(h.absolutePos(t.offset(dx, y, dz)), st, 2);
                }
            }
        }
        Detonation d = detonation(h, CENTER, Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f);
        scarAll(h, d, new ColumnScar.Budget(false));
        h.runAfterDelay(60, () -> {
            for (BlockPos q : bed) if (h.getBlockState(q).isAir()) h.fail("the shore or bottom is knocked out: " + q.toShortString());
            for (int dx = 0; dx < 5; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    for (int y = 5; y < 60; y++) {
                        BlockState st = h.getBlockState(t.offset(dx, y, dz));
                        if (!st.isAir() && st.getFluidState().isEmpty()) h.fail("the tower on the shore stands: " + st + " in " + t.offset(dx, y, dz).toShortString());
                    }
                }
            }
            for (BlockPos p : BlockPos.betweenClosed(t.offset(-3, -3, -3), t.offset(10, 60, 8))) {
                if (!h.getBlockState(p).getFluidState().isEmpty() && h.getBlockState(p.below()).isAir()) h.fail("water over the void " + p.toShortString());
            }
            h.succeed();
        });
    }

    /**
     * A building close to the rock (a house made of terracotta with windows near a sheer wall of stone 12×12×30) at the epicenter: the house is collapsing,
     *  the rock - not a single knocked out block.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_cliff", skyAccess = true)
    public static void buildingAgainstCliffFallsCliffStays(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos cliff = CENTER.offset(-12, 0, -6);
        List<BlockPos> rock = new java.util.ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(cliff, cliff.offset(11, 29, 11))) {
            level.setBlock(h.absolutePos(p), Blocks.STONE.defaultBlockState(), 2);
            rock.add(p.immutable());
        }
        BlockPos house = cliff.offset(12, 0, 3);
        for (int y = 0; y < 30; y++) {
            for (int dx = 0; dx < 6; dx++) {
                for (int dz = 0; dz < 6; dz++) {
                    boolean wall = dx == 5 || dz == 0 || dz == 5 || dx == 0;
                    BlockState st = wall ? (y % 4 == 2 && dx > 0 && (dx + dz) % 2 == 1 ? Blocks.GLASS : Blocks.BROWN_TERRACOTTA).defaultBlockState()
                            : y % 4 == 3 ? Blocks.WHITE_TERRACOTTA.defaultBlockState() : null;
                    if (st != null) level.setBlock(h.absolutePos(house.offset(dx, y, dz)), st, 2);
                }
            }
        }
        Detonation d = detonation(h, CENTER, Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f);
        scarAll(h, d, new ColumnScar.Budget(false));
        for (BlockPos q : rock) if (h.getBlockState(q).isAir()) h.fail("the rock is knocked out: " + q.toShortString());
        for (int dx = 1; dx < 6; dx++) {
            for (int dz = 0; dz < 6; dz++) {
                for (int y = 5; y < 30; y++) {
                    BlockState st = h.getBlockState(house.offset(dx, y, dz));
                    if (!st.isAir()) h.fail("the house stands by the cliff: " + st + " in " + house.offset(dx, y, dz).toShortString());
                }
            }
        }
        h.succeed();
    }

    /**
     * The relief at the epicenter of the same tower ({@link #towerAtGroundZeroFalls}) does not change: a rock made of stone under turf with a riverbed
     *  and a mesa made of terracotta with a closed cave under the arch in the 2 block - not a single knocked out block of soil, water is in place
     *  and has not flowed out, the vault of the cave is intact: under it there is no air with the sky, there is no difference. The terracotta of the mesa is the same block as the
     *  of the tower: the mountain is distinguished from the tower by its thickness and the sky on both sides, not the block.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_terrain", skyAccess = true)
    public static void terrainAtGroundZeroKeepsShape(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<BlockPos> ground = new java.util.ArrayList<>(), water = new java.util.ArrayList<>();
        // rock 14×14×20: stone, turf and earth on top; along it there is a channel 2 block wide and 3 deep with water
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(6, 12, 6), new BlockPos(19, 31, 19))) {
            BlockPos q = p.immutable();
            boolean river = q.getX() >= 12 && q.getX() <= 13 && q.getY() >= 29;
            BlockState st = river ? Blocks.WATER.defaultBlockState() : q.getY() == 31 ? Blocks.GRASS_BLOCK.defaultBlockState()
                    : q.getY() >= 29 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
            level.setBlock(h.absolutePos(q), st, 2);
            (river ? water : ground).add(q);
        }
        // mesa 12×12×20 made of terracotta, in it there is a cave 4×3×4 under the arch in block 2
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(40, 12, 40), new BlockPos(51, 31, 51))) {
            BlockPos q = p.immutable();
            boolean cave = q.getX() >= 44 && q.getX() <= 47 && q.getZ() >= 44 && q.getZ() <= 47 && q.getY() >= 27 && q.getY() <= 29;
            level.setBlock(h.absolutePos(q), cave ? Blocks.AIR.defaultBlockState() : (q.getY() % 3 == 0 ? Blocks.ORANGE_TERRACOTTA : Blocks.TERRACOTTA).defaultBlockState(), 2);
            if (!cave) ground.add(q);
        }
        Detonation d = detonation(h, CENTER, Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f);
        scarAll(h, d, new ColumnScar.Budget(false));
        for (BlockPos q : ground) {
            if (h.getBlockState(q).isAir()) {
                h.fail("relief embossed: " + q.toShortString() + " (pressure " + String.format(Locale.ROOT, "%.0f", d.psi(Vec3.atCenterOf(h.absolutePos(q)))) + " psi)");
            }
        }
        for (BlockPos q : water) h.assertTrue(h.getBlockState(q).is(Blocks.WATER), "there is no water in " + q.toShortString());
        h.succeed();
    }

    /**
     * The block placed after the plan inside the house (not the place of the plan: the air was and remains there) holds the height maps of the
     *  column, although according to the plan the roof and walls fall to the ground: the substitution compares the blocks of the chunk with a snapshot of the plan
     *  ({@code RuinPlan.sameBlocks}) and looks for the top of these columns again. Without this, the column height map would lie on the ground.
     * A lamp turned off by blackout directly in the section (a double instead of a lamp) does not change the blocks for the plan.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_ruins_edit", skyAccess = true)
    public static void blockPlacedAfterPlanKeepsHeights(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos house = CENTER.east(12);
        for (BlockPos p : BlockPos.betweenClosed(house.offset(-2, 0, -2), house.offset(2, 4, 2))) {
            boolean wall = Math.abs(p.getX() - house.getX()) == 2 || Math.abs(p.getZ() - house.getZ()) == 2 || p.getY() == house.getY() + 4;
            if (wall) h.setBlock(p, Blocks.OAK_PLANKS);
        }
        // lamp - in the column of the house: the adjacent column is already in the adjacent chunk (CI: x or z houses on the edge of the chunk), and the
        //  double below is written in the section of the chunk of the house
        BlockPos lamp = house.above(1);
        h.setBlock(lamp, Blocks.GLOWSTONE);
        LevelChunk chunk = level.getChunkAt(h.absolutePos(house));
        int[] before = heights(chunk, true);
        Detonation d = detonation(h, CENTER, 0, 15, 0.025f);
        RuinPlan plan = RuinPlanner.plan(level, d, chunk);
        h.assertTrue(plan.changedBlocks() > 0 && plan.sameBlocks(chunk), "the chunk immediately after the plan did not match the snapshot of the plan");
        // blackout: lamp double right in the section, like ChunkLights.applyInPlace
        BlockPos lampAt = h.absolutePos(lamp);
        h.assertTrue(level.getChunkAt(lampAt) == chunk, "the lamp is not in the plan chunk");
        var twin = ua.zentix.incoming.grid.GridLights.unlit(Blocks.GLOWSTONE.defaultBlockState());
        h.assertTrue(twin != null, "the glowstone has no counterpart");
        chunk.getSection(chunk.getSectionIndex(lampAt.getY())).setBlockState(lampAt.getX() & 15, lampAt.getY() & 15, lampAt.getZ() & 15, twin);
        h.assertTrue(plan.sameBlocks(chunk), "an extinguished lamp is considered a chunk change");
        BlockPos inside = house.above(3);
        h.setBlock(inside, Blocks.STONE);
        h.assertFalse(plan.sameBlocks(chunk), "a block inside a house is not considered a chunk change");
        h.assertTrue(plan.apply(level, chunk, d, new ColumnScar.Budget(false)), "the plan is outdated: the place inside the house has become the place of the plan");
        h.assertBlockPresent(Blocks.STONE, inside);
        h.assertBlockNotPresent(Blocks.OAK_PLANKS, house.above(4));
        int[] after = heights(chunk, false), full = heights(chunk, true);
        for (int k = 0; k < full.length; k++) {
            if (after[k] != full[k]) {
                h.fail("column " + (k % 256) + ", " + (k / 256 < RuinPlan.HEIGHTMAP_TYPES.length ? RuinPlan.HEIGHTMAP_TYPES[k / 256] : "sky")
                        + ": after replacement " + after[k] + ", recalculation " + full[k] + " (to the ruins " + before[k] + ")");
            }
        }
        BlockPos abs = h.absolutePos(inside);
        h.assertTrue(level.getHeight(Heightmap.Types.WORLD_SURFACE, abs.getX(), abs.getZ()) == abs.getY() + 1, "the height map lay below the stone");
        h.succeed();
    }

    /**
     * A block entity in the ruins of a chunk that has not yet ticked: the new generation leaves structures (beds,
     * hives) near the chest and a living entity, and a plug “DUMMY” in the same place ({@code WorldGenRegion.setBlock}). Ruins are removed by both:
     * after them there is no stub in the deferred data of the chunk, and saving it does not raise it above the air (otherwise -
     * «Tried to load a DUMMY block entity ... found air" in places in the zone behind the wave).
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_ruins_dummy", skyAccess = true)
    public static void dummyStubGoesWithBlockEntityInRuins(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<BlockPos> chests = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            BlockPos p = CENTER.west(8).south(i * 2 - 5).above(1);
            h.setBlock(p.below(), Blocks.OAK_PLANKS);
            h.setBlock(p, Blocks.CHEST);
            BlockPos abs = h.absolutePos(p);
            net.minecraft.nbt.CompoundTag stub = new net.minecraft.nbt.CompoundTag();
            stub.putInt("x", abs.getX());
            stub.putInt("y", abs.getY());
            stub.putInt("z", abs.getZ());
            stub.putString("id", "DUMMY");
            level.getChunkAt(abs).setBlockEntityNbt(stub);
            h.assertTrue(level.getBlockEntity(abs) != null && level.getChunkAt(abs).getBlockEntityNbt(abs) != null, "preparation: no chest with plug");
            chests.add(abs);
        }
        scarAll(h, detonation(h, CENTER, 0, 15, 0.025f), new ColumnScar.Budget(false));
        int ruined = 0;
        for (BlockPos abs : chests) {
            if (level.getBlockState(abs).is(Blocks.CHEST)) continue;
            ruined++;
            h.assertTrue(level.getChunkAt(abs).getBlockEntityNbt(abs) == null, "the plug remains with " + abs.toShortString() + " (" + level.getBlockState(abs) + ")");
        }
        h.assertTrue(ruined >= 3, "the wave barely touched the chests: removed " + ruined);
        h.succeed();
    }

    /**
     * POIs in ruins (bed, composter, cartography table, bell, lectern), both registered in world POI data and inserted
     * without it (like WorldEdit-built map structures): no PoiSection "never registered" errors; POI data after ruins
     * matches the blocks.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_ruins_poi", skyAccess = true)
    public static void poiInRuinsStayConsistent(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos row = CENTER.east(8);
        net.minecraft.world.level.block.state.BlockState[] poi = {Blocks.COMPOSTER.defaultBlockState(), Blocks.CARTOGRAPHY_TABLE.defaultBlockState(),
                Blocks.BELL.defaultBlockState(), Blocks.LECTERN.defaultBlockState(), Blocks.SMITHING_TABLE.defaultBlockState()};
        List<BlockPos> placed = new java.util.ArrayList<>();
        for (int i = 0; i < poi.length; i++) {
            // through the world (in data POI) - at height 1; past data POI - at height 3, on a support made of boards
            BlockPos registered = row.south(i * 2).above(1), bare = row.south(i * 2).east(2).above(3);
            h.setBlock(registered.below(), Blocks.OAK_PLANKS);
            h.setBlock(registered, poi[i]);
            for (int y = 1; y <= 2; y++) h.setBlock(bare.below(y), Blocks.OAK_PLANKS);
            BlockPos abs = h.absolutePos(bare);
            LevelChunk c = level.getChunkAt(abs);
            c.getSection(c.getSectionIndex(abs.getY())).setBlockState(abs.getX() & 15, abs.getY() & 15, abs.getZ() & 15, poi[i], false);
            placed.add(registered);
            placed.add(bare);
        }
        h.setBlock(row.west(2).above(1), Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.PART,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        placed.add(row.west(2).above(1));
        h.runAfterDelay(2, () -> {
            // additions of POI through the world are the task of the server - to this tick they are in the data
            for (BlockPos p : placed) {
                BlockPos abs = h.absolutePos(p);
                boolean inData = level.getPoiManager().getType(abs).isPresent();
                h.assertTrue(p.getY() - CENTER.getY() == 3 ? !inData : inData, "preparation: POI in " + p.toShortString() + " in data " + inData);
            }
            int[] errors = {0};
            var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(
                    net.minecraft.world.entity.ai.village.poi.PoiSection.class.getName());
            var counter = new org.apache.logging.log4j.core.appender.AbstractAppender("incoming-poi-errors", null, null, true,
                    org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
                @Override
                public void append(org.apache.logging.log4j.core.LogEvent e) {
                    if (e.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.ERROR)) errors[0]++;
                }
            };
            counter.start();
            logger.addAppender(counter);
            scarAll(h, detonation(h, CENTER, 0, 15, 0.025f), new ColumnScar.Budget(false));
            h.runAfterDelay(3, () -> {
                logger.removeAppender(counter);
                counter.stop();
                h.assertTrue(errors[0] == 0, "errors PoiSection: " + errors[0]);
                int ruined = 0;
                for (BlockPos p : placed) {
                    BlockPos abs = h.absolutePos(p);
                    var state = level.getBlockState(abs);
                    boolean should = net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(state).isPresent();
                    h.assertTrue(level.getPoiManager().getType(abs).isPresent() == should, "POI in " + p.toShortString() + " does not match " + state);
                    if (!should) ruined++;
                }
                h.assertTrue(ruined >= 3, "the wave almost didn't touch the place POI: removed " + ruined);
                h.succeed();
            });
        });
    }

    /**
     * Loot chest and bed in ruins: change across the world after replacement - no items on the ground, no block entity
     * in the air; a blow from conservation more powerful than the limit is undermined with the limit.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_ruins_be", skyAccess = true)
    public static void containersInRuinsLeaveNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos chest = CENTER.east(8), bed = CENTER.east(8).north(2);
        h.setBlock(chest, Blocks.CHEST);
        if (h.getBlockEntity(chest) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c) {
            c.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 64));
        }
        h.setBlock(bed, Blocks.RED_BED);
        scarAll(h, detonation(h, CENTER, 0, 15, 0.025f), new ColumnScar.Budget(false));
        h.assertBlockNotPresent(Blocks.CHEST, chest);
        h.assertTrue(level.getBlockEntity(h.absolutePos(chest)) == null || h.getBlockState(chest).hasBlockEntity(),
                "the block entity of the chest remained with " + h.getBlockState(chest));
        var items = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(h.absolutePos(CENTER)).inflate(40));
        h.assertTrue(items.isEmpty(), "items on the ground: " + items.size());
        // save with impact in 1 Mt
        var tag = NuclearEvents.ScheduledStrike.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,
                new NuclearEvents.ScheduledStrike(1, Vec3.ZERO, 1000, true, 0, 1, Vec3.ZERO, java.util.Optional.empty(), false)).getOrThrow();
        double kt = NuclearEvents.ScheduledStrike.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).getOrThrow().yieldKt();
        h.assertTrue(kt == ua.zentix.incoming.strike.Loadout.Nuke.MAX_YIELD, "power from storage is not limited: " + kt);
        h.succeed();
    }

    /** The plan, in places where something was changed after it, is not set: the ruins are built anew according to the chunk as is. */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_stale_plan", skyAccess = true)
    public static void stalePlanIsRebuilt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos glass = CENTER.west(8);
        h.setBlock(glass, Blocks.GLASS);
        Detonation d = detonation(h, CENTER, 0, 15, 0.025f);
        LevelChunk chunk = level.getChunkAt(h.absolutePos(glass));
        RuinPlan plan = RuinPlanner.plan(level, d, chunk);
        // the player replaced the glass after the plan
        h.setBlock(glass, Blocks.WHITE_STAINED_GLASS);
        ColumnScar.Budget budget = new ColumnScar.Budget(false);
        h.assertFalse(plan.apply(level, chunk, d, budget), "outdated plan set");
        h.assertBlockPresent(Blocks.WHITE_STAINED_GLASS, glass);
        h.assertTrue(RuinPlanner.plan(level, d, chunk).apply(level, chunk, d, budget), "no new plan has been set");
        h.assertBlockNotPresent(Blocks.WHITE_STAINED_GLASS, glass);
        h.succeed();
    }

    /**
     * blackout extinguishes the lamps directly in the section's palette - this does not make the plan obsolete, and the extinguished lamp after the ruins does not
     *  light up again (the plan writes only its places to the section).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_stale_plan", skyAccess = true)
    public static void blackoutKeepsPlanAndLampsDark(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos glass = CENTER.west(8), lamp = glass.north(), lantern = glass.south();
        h.setBlock(glass, Blocks.GLASS);
        h.setBlock(lamp, Blocks.REDSTONE_LAMP);
        h.setBlock(lantern, Blocks.LANTERN);
        Detonation d = detonation(h, CENTER, 0, 15, 0.025f);
        LevelChunk chunk = level.getChunkAt(h.absolutePos(glass));
        RuinPlan plan = RuinPlanner.plan(level, d, chunk);
        for (BlockPos p : List.of(lamp, lantern)) {
            BlockState dark = GridLights.unlit(h.getBlockState(p));
            h.assertTrue(dark != null, "not lamp network: " + h.getBlockState(p));
            h.setBlock(p, dark);
        }
        h.assertTrue(plan.apply(level, chunk, d, new ColumnScar.Budget(false)), "plan is outdated by blackout");
        h.assertBlockNotPresent(Blocks.GLASS, glass);
        h.assertBlockNotPresent(Blocks.REDSTONE_LAMP, lamp);
        h.assertBlockNotPresent(Blocks.LANTERN, lantern);
        h.succeed();
    }

    /**
     * Ruins in advance: while the ICBM is flying, the heavy zone chunks are getting ready; at the moment of detonation, their ruins rise according to plan, each
     *  chunk - no later than 2 tick after the front arrives at it (glass in 16 blocks, 15 kt in the air, scale 0.02),
     *  and preparation tickets are released. The detonation takes place and the appearance of preparation, and when the weather has changed (the ruins of chunks,
     * , uploaded later - by detonation, and they are the same). Counting clock: budget does not depend on the machine CI.
     */
    @GameTest(template = "range", timeoutTicks = 900, batch = "nuke_prep", skyAccess = true)
    public static void ruinsPreparedDuringFlightFallWithFront(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // zone-by-wave diagnostics without -Dincoming.nukeDiag=true is silent: no line “DIAG”, no stream of stack snapshots
        h.assertFalse(ua.zentix.incoming.nuclear.world.NukeDiag.ON, "diagnostics included in GameTest");
        // i/O queue diagnostics reads by reflection: fields IOWorker in place (names Mojang, as in the game)
        String io = ua.zentix.incoming.nuclear.world.NukeDiag.io(level);
        h.assertFalse(io.contains("unreadable"), "i/O diagnostics: " + io);
        java.util.List<String> diag = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Runnable unwatch = watchLog(line -> {
            if (line.startsWith("DIAG")) diag.add(line);
        });
        BlockPos glass = CENTER.west(16);
        h.setBlock(glass, Blocks.GLASS);
        double scale = IncomingConfig.SERVER.nukeEffectsScale.get();
        IncomingConfig.SERVER.nukeEffectsScale.set(0.02);
        NuclearWorld w = NuclearWorld.get(level);
        WorkClock clock = WorkClock.counting(1_000_000L);
        NuclearWorld.useClock(level.getServer(), clock);
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        events.schedule(new NuclearEvents.ScheduledStrike(events.nextId(), target, 15, true, now, now + 300, target, java.util.Optional.empty(), false));
        int[] plannedBefore = {-1};
        long[] glassGone = {-1}, tickAt = {0};
        h.onEachTick(() -> {
            String failure = null;
            if (!events.scheduled().isEmpty()) {
                plannedBefore[0] = w.plannedChunks();
                if (plannedBefore[0] > 0) {
                    // it started to rain during the flight: the explosion was still with the visibility that was used to prepare the ruins
                    Detonation f = NuclearWarhead.geometry(level, target, 15, true, IncomingConfig.SERVER.nukeEffectsScale.get().floatValue());
                    float other = (float) (f.visibility() == ThermalModel.VISIBILITY_RAIN ? ThermalModel.VISIBILITY_CLEAR : ThermalModel.VISIBILITY_RAIN);
                    Detonation rainy = new Detonation(f.id(), f.burst(), f.groundY(), f.yieldKt(), f.surface(), f.gameTime(), f.windDir(), f.windSpeed(),
                            other, f.seed(), f.scale(), f.fallout(), f.wave());
                    if (w.planned(rainy).visibility() != f.visibility()) failure = "detonation does not take the appearance of preparation";
                }
            }
            // after the explosion - at the pace of the game: preparation squares are released according to disk reads and background plans, and they go
            //  by the clock; without tempo 850 server tick GameTest ends before the squares have time to release
            else if (tickAt[0] == 0) tickAt[0] = System.nanoTime();
            else RuinBackgroundGameTests.gamePace(tickAt);
            if (glassGone[0] < 0 && !h.getBlockState(glass).is(Blocks.GLASS)) glassGone[0] = level.getGameTime(); // in place of glass there can be a fire
            Detonation d = events.detonations().stream().filter(x -> x.burst().distanceTo(target) < 20).findFirst().orElse(null);
            if (d != null && glassGone[0] >= 0) {
                BlockPos g = h.absolutePos(glass);
                ChunkPos c = new ChunkPos(g);
                double x = Math.max(c.getMinBlockX(), Math.min(d.burst().x, c.getMaxBlockX() + 1)), z = Math.max(c.getMinBlockZ(), Math.min(d.burst().z, c.getMaxBlockZ() + 1));
                double slant = Math.sqrt((x - d.burst().x) * (x - d.burst().x) + (z - d.burst().z) * (z - d.burst().z) + Math.pow(d.burst().y - d.groundY(), 2));
                long due = d.gameTime() + (long) d.arrivalTicks(slant);
                if (plannedBefore[0] <= 0) failure = "the ruins were not prepared during the flight";
                else if (w.ruinStats()[0] == 0) failure = "not a single chunk fell into place according to a ready-made plan";
                else if (glassGone[0] > due + 2) failure = "the ruins lagged behind the front: glass was broken through " + (glassGone[0] - due) + " tick after the wave arrives";
                else if (w.plannedChunks() > 0 || w.prepTiles() > 0) {
                    // we wait until preparation releases the ticket; didn’t let go by the deadline - failure with her score lower, not a timeout
                    if (level.getGameTime() <= now + 850) return;
                } else {
                    unwatch.run();
                    h.assertTrue(clock.maxUnitsPerTick() <= IncomingConfig.SERVER.workBudgetMs.get(), "per tick " + clock.maxUnitsPerTick() + " units");
                    h.assertTrue(diag.isEmpty(), "diagnostic lines without property: " + diag);
                    h.assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("incoming-nuke-diag")), "diagnostic flow without property");
                    IncomingConfig.SERVER.nukeEffectsScale.set(scale);
                    NuclearWorld.useClock(level.getServer(), new WorkClock());
                    NuclearStrikes.clear(level);
                    h.succeed();
                    return;
                }
            }
            if (failure != null || level.getGameTime() > now + 850) {
                unwatch.run();
                IncomingConfig.SERVER.nukeEffectsScale.set(scale);
                NuclearWorld.useClock(level.getServer(), new WorkClock());
                NuclearStrikes.clear(level);
                throw new net.minecraft.gametest.framework.GameTestAssertException(failure != null ? failure
                        : "the detonation did not go through to the end: ready " + plannedBefore[0] + ", according to plan " + w.ruinStats()[0] + ", left " + w.plannedChunks()
                        + ", squares " + w.prepTiles() + ", detonation " + (d != null) + ", glass " + glassGone[0]);
            }
        });
    }

    /** Mod log lines until the returned removal handler is called. */
    static Runnable watchLog(java.util.function.Consumer<String> lines) {
        org.apache.logging.log4j.core.LoggerContext ctx = (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        org.apache.logging.log4j.core.appender.AbstractAppender app = new org.apache.logging.log4j.core.appender.AbstractAppender(
                "incoming-gametest-" + System.nanoTime(), null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                lines.accept(event.getMessage().getFormattedMessage());
            }
        };
        app.start();
        ctx.getRootLogger().addAppender(app);
        return () -> {
            ctx.getRootLogger().removeAppender(app);
            app.stop();
        };
    }

    /** A forgotten explosion (the chunk loaded for the first time days later) burns, but does not set fire; fresh - sets fire. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_fires", skyAccess = true)
    public static void forgottenDetonationDoesNotIgnite(GameTestHelper h) {
        Detonation d = detonation(h, CENTER, 60, 15, 0.025f);
        scarAll(h, d, new ColumnScar.Budget(false));
        h.assertTrue(fires(h) == 0, "a forgotten explosion set fire to: " + fires(h));
        scarAll(h, d, new ColumnScar.Budget(true));
        h.assertTrue(fires(h) > 0, "a fresh explosion did not set fire - the check above does not mean anything");
        h.succeed();
    }

    private static int fires(GameTestHelper h) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(63, 16, 63))) {
            if (h.getBlockState(p).is(BlockTags.FIRE)) n++;
        }
        return n;
    }

    /**
     * Burning does not leave these block entities where there is now air: neither for a living block entity, nor for a deferred one.
     *  Deferred - for a chunk that has not yet ticked: after generation, the stub “DUMMY” (beds, bells, chests of
     *  villages, {@code WorldGenRegion.setBlock}), after loading - saved data. Replacing the block removes only
     *  live: otherwise the deferred data is saved, and when loading the chunk it writes “Tried to load a DUMMY block
     * entity … found air.” The contents of the chests do not spill out for either one or the other, and the unexpanded extraction of chests of the
     *  generation does not unfold at all (the researcher’s map in it looks for a structure in the server thread).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_block_entities", skyAccess = true)
    public static void scarLeavesNoOrphanBlockEntities(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos chest = CENTER.east(4), packedChest = CENTER.east(6), bell = CENTER.south(4), sign = CENTER.south(6);
        BlockPos lootChest = CENTER.west(4), packedLootBarrel = CENTER.west(6);
        h.setBlock(chest, Blocks.CHEST);
        h.setBlock(packedChest, Blocks.CHEST);
        h.setBlock(bell, Blocks.BELL);
        h.setBlock(sign, Blocks.OAK_SIGN);
        h.setBlock(lootChest, Blocks.CHEST);
        h.setBlock(packedLootBarrel, Blocks.BARREL);
        for (BlockPos p : List.of(chest, packedChest)) {
            if (h.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c) c.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
        }
        // unopened chest and generation barrel: the loot table (in the dungeon it is never empty) has not yet been expanded
        for (BlockPos p : List.of(lootChest, packedLootBarrel)) {
            if (h.getBlockEntity(p) instanceof net.minecraft.world.RandomizableContainer c) c.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON, 1L);
        }
        // chests and a barrel - like after loading a chunk that did not tick; bell and sign - as after generation
        for (BlockPos p : List.of(packedChest, packedLootBarrel)) pend(level, h.absolutePos(p), level.getBlockEntity(h.absolutePos(p)).saveWithFullMetadata(level.registryAccess()));
        for (BlockPos p : List.of(bell, sign)) {
            BlockPos abs = h.absolutePos(p);
            net.minecraft.nbt.CompoundTag dummy = new net.minecraft.nbt.CompoundTag();
            dummy.putInt("x", abs.getX());
            dummy.putInt("y", abs.getY());
            dummy.putInt("z", abs.getZ());
            dummy.putString("id", "DUMMY");
            pend(level, abs, dummy);
        }

        scarAll(h, detonation(h, CENTER, 0, 15, 0.025f), new ColumnScar.Budget(false)); // no fires: air in place of blocks

        for (BlockPos p : List.of(chest, packedChest, bell, sign, lootChest, packedLootBarrel)) h.assertTrue(h.getBlockState(p).isAir(), "not destroyed: " + h.getBlockState(p));
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(63, 0, 63));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                // what goes into saving the chunk (live and deferred) is only for blocks with a block entity
                for (BlockPos p : chunk.getBlockEntitiesPos()) {
                    h.assertTrue(chunk.getBlockState(p).hasBlockEntity(), "block entity data " + chunk.getBlockState(p) + " in " + p.toShortString());
                }
            }
        }
        h.assertEntityNotPresent(EntityType.ITEM);
        h.succeed();
    }

    /** The block entity in {@code pos} is deferred data {@code tag}, like a chunk that has not yet ticked. */
    private static void pend(ServerLevel level, BlockPos pos, net.minecraft.nbt.CompoundTag tag) {
        LevelChunk chunk = level.getChunkAt(pos);
        chunk.removeBlockEntity(pos);
        chunk.setBlockEntityNbt(tag);
    }

    /** Shadow: the fireball is not visible behind a wall (neither light nor fire), but in an open place it is visible. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_shadow", skyAccess = true)
    public static void wallCastsThermalShadow(GameTestHelper h) {
        for (int y = 0; y < 6; y++) {
            for (int z = -3; z <= 3; z++) h.setBlock(CENTER.offset(8, y, z), Blocks.STONE);
        }
        Vec3 fireball = Vec3.atCenterOf(h.absolutePos(CENTER.above(4)));
        Vec3 behind = Vec3.atCenterOf(h.absolutePos(CENTER.east(12)));
        Vec3 open = Vec3.atCenterOf(h.absolutePos(CENTER.west(12)));
        h.assertFalse(ThermalShadow.visible(h.getLevel(), fireball, behind), "the ball is visible behind the wall");
        h.assertTrue(ThermalShadow.visible(h.getLevel(), fireball, open), "in the open the ball is not visible");
        h.succeed();
    }

    /**
     * Ground blasting 1 Mt, bypassing the limit 15 kt (the blast was assembled in the test, not through {@code detonate}) - for the sake of a large
     *  bowl, 1 block = 15 m: bowl according to the profile of the model ±1 block from the natural soil of each column
     *  (the hillock in the bowl deepens from its height, the hill on the rampart is not cut off), there is a rampart around it; air blast does not give crater.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_crater", skyAccess = true)
    public static void groundBurstDigsCraterProfile(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Detonation d = detonation(h, CENTER, 0, 1000, 0.065f);
        double dry = d.blocks(CraterModel.radius(d.yieldKt(), CraterModel.Soil.DRY));
        // a hillock of soil in half the radius and a hill of stone on a rampart
        BlockPos bump = CENTER.north(Mth.floor(dry * 0.5)), hill = CENTER.south(Mth.floor(dry * 1.6));
        for (int y = 0; y < 3; y++) h.setBlock(bump.above(y), Blocks.DIRT);
        for (int y = 0; y < 6; y++) h.setBlock(hill.above(y), Blocks.STONE);
        CraterJob job = new CraterJob(d, 0);
        RandomSource random = RandomSource.create(3);
        h.succeedWhen(() -> {
            // chunks of the site are loaded, but the crater is on budget: we’ll dig here until it’s finished
            for (int i = 0; i < 5000 && job.step(level, random) == CraterJob.Step.PROGRESS; i++) {
            }
            h.assertTrue(job.step(level, random) == CraterJob.Step.DONE, "crater is still digging");
            CraterModel.Soil soil = job.soil();
            double radius = d.blocks(CraterModel.radius(d.yieldKt(), soil));
            for (double f : new double[]{0, 0.3}) {
                BlockPos p = h.absolutePos(CENTER.east(Mth.floor(radius * f)));
                assertSurface(h, d, soil, p, d.groundY(), "on " + f + " radius");
            }
            assertSurface(h, d, soil, h.absolutePos(bump), d.groundY() + 3, "on the hill");
            BlockPos hillTop = h.absolutePos(hill);
            h.assertTrue(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hillTop.getX(), hillTop.getZ()) >= hillTop.getY() + 6,
                    "the hill on the rampart is cut off");
            BlockPos rim = h.absolutePos(CENTER.east(Mth.floor(radius * 1.05)));
            h.assertTrue(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rim.getX(), rim.getZ()) >= d.groundY(), "no shaft behind the edge");
            h.assertFalse(CraterModel.formsCrater(Yield.optimalBurstHeight(15), 15), "an air blast at the optimal height digs a crater");
        });
    }

    private static void assertSurface(GameTestHelper h, Detonation d, CraterModel.Soil soil, BlockPos p, double ground, String where) {
        double r = d.metres(Math.hypot(p.getX() + 0.5 - d.burst().x, p.getZ() + 0.5 - d.burst().z));
        int expected = Mth.floor(ground - d.blocks(CraterModel.profileDepth(r, d.yieldKt(), soil)));
        int actual = h.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        h.assertTrue(Math.abs(actual - expected) <= 1, "crater profile " + where + ": height " + actual + ", waited " + expected);
    }

    /**
     * All the way: detonation 1 kt (1 block = 10 m) → queue by chunks → glass in 160 m is broken, the chunk is marked with the number
     *  detonation, the crater is dug, and the queue keeps the budget tick. The budget is checked on the counting clock
     * ({@link WorkClock#counting}: each unit of work is exactly 1 ms), and not on wall time: on shared machines
     * CI any column can be delayed due to neighbors on the machine, and the check would fail through no fault queues.
     * <p>
     * <p>
     * The deadline is not a speed test: the test ends as soon as the glass is broken. To the glass, the queue passes in the order of
     *  arrival of a wave of dozens of chunks along 256 columns, ~29 columns per tick - 100–200 tick, and how many chunks in the radius
     *  are loaded (neighboring sites, background generation), from launch to startup miscellaneous; the deadline at 200 tick would be
     *  butt. The glass is in a chunk, all of whose neighbors are inside the platform (x and z from 16 to 47 for any alignment according to
     *  chunks): the GameTest platform loads immediately, and the chunks beyond its edge are generated in the background, and a queue would be waiting for them.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_pipeline", skyAccess = true)
    public static void detonationRunsBudgetedQueue(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos glass = CENTER.west(16);
        h.setBlock(glass, Blocks.GLASS);
        NuclearWorld w = NuclearWorld.get(level);
        WorkClock clock = WorkClock.counting(1_000_000L);
        NuclearWorld.useClock(level.getServer(), clock);
        Detonation d = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 1, false, null, 0.1f);
        int budgetMs = IncomingConfig.SERVER.workBudgetMs.get();
        h.succeedWhen(() -> {
            // chunks at the edge of the busy world are waiting for neighbors (otherwise Sable would load them on every block) - they are in line
            h.assertTrue(w.craterJobs() == 0, "crater is still digging");
            h.assertBlockNotPresent(Blocks.GLASS, glass);
            int scar = level.getChunkAt(h.absolutePos(glass)).getData(ModAttachments.CHUNK_SCAR);
            h.assertTrue(scar >= d.id(), "chunk is not marked with detonation: " + scar + " < " + d.id());
            h.assertTrue(clock.maxUnitsPerTick() <= budgetMs, "per tick " + clock.maxUnitsPerTick() + " units by 1 ms with budget " + budgetMs + " ms");
            h.assertTrue(clock.ticksWorked() > 1, "all the work fit into one tick - the budget has not been verified");
            NuclearWorld.useClock(level.getServer(), new WorkClock());
            NuclearStrikes.clear(level);
        });
    }

    /**
     * The queue of ruins holds the budget even when all the inspected jobs are waiting: the background pool of ruins is occupied by tasks that are
     *  waiting for a latch, and each chunk that the wave has reached is waiting for a place in the pool (or reading the window) - it is inspected by every
     *  tick. The inspection is not a unit, but it goes on until the deadline has expired: per tick no more than the budget and one job in excess of
     *  have been inspected. The clock is real on its own time, where each reading of the clock costs 1 ms (for those who count, the inspection is free).
     * The clock is always started until the first recorded unit, and without checking the deadline for inspections, the entire
     *  queue would be inspected in a tick. Chunks in line - square 9×9 around the site, more budget.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_waits_budget", skyAccess = true)
    public static void waitingRuinsStayInBudget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos mid = new ChunkPos(h.absolutePos(CENTER));
        for (int dz = -4; dz <= 4; dz++) for (int dx = -4; dx <= 4; dx++) level.getChunk(mid.x + dx, mid.z + dz);
        level.getChunkSource().addRegionTicket(HOLD, mid, 4, mid);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        long[] time = {0};
        WorkClock clock = WorkClock.decaying(() -> time[0] += 1_000_000L, 1);
        NuclearWorld.useClock(level.getServer(), clock);
        StrikeGameTests.afterTest(h, () -> {
            release.countDown();
            level.getChunkSource().removeRegionTicket(HOLD, mid, 4, mid);
            NuclearWorld.useClock(level.getServer(), new WorkClock());
            NuclearStrikes.clear(level);
        });
        NuclearWorld w = NuclearWorld.get(level);
        long start = level.getGameTime();
        int[] maxInspected = {0};
        // the pool can grow (the number of threads is adjusted according to the server tick): occupy each tick. Inspected for tick -
        //  our own, with tick after detonation: the queue is the same for all server tests, and before this test there could have been someone else's tick in it
        //  on a real clock
        RuinWorkers.fillForTest(release, RuinWorkers.Priority.NEAR);
        h.onEachTick(() -> {
            RuinWorkers.fillForTest(release, RuinWorkers.Priority.NEAR);
            if (level.getGameTime() >= start + 2) maxInspected[0] = Math.max(maxInspected[0], w.scarInspectedLastTick());
        });
        NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 1, false, null, 0.1f);
        int budgetMs = IncomingConfig.SERVER.workBudgetMs.get();
        h.runAfterDelay(200, () -> {
            h.assertTrue(w.queuedChunks() > 2 * budgetMs, "in line " + w.queuedChunks() + " chunks - no more than two budgets, checking for nothing");
            h.assertTrue(maxInspected[0] > 0, "the line of ruins did not inspect anything - the inspection was about nothing");
            h.assertTrue(maxInspected[0] <= budgetMs + 1, "inspected by tick " + maxInspected[0] + " work on a budget " + budgetMs + " ms");
            h.succeed();
        });
    }

    /**
     * Light and penetrating radiation - not in the tick of the explosion, but under the budget, the neighbors are the first: immediately after the explosion,
     * 40 cows in 300 m (1 kt, 1 block = 10 m) are still intact, then the impulse passes through everyone in a few ticks, or one
     *  tick does not exceed the budget (counting clocks, as in {@link #detonationRunsBudgetedQueue}). 31 cal/cm² on an open
     *  place - 3 degree burn on the entire side to the ball: burn shock.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_pulse", skyAccess = true)
    public static void lightPulseRunsUnderBudget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<Cow> cows = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40;
            cows.add(h.spawn(EntityType.COW, CENTER.offset(Mth.floor(Math.cos(a) * 29), 0, Mth.floor(Math.sin(a) * 29))));
        }
        NuclearWorld w = NuclearWorld.get(level);
        WorkClock clock = WorkClock.counting(1_000_000L);
        NuclearWorld.useClock(level.getServer(), clock);
        NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 1, false, null, 0.1f);
        for (Cow cow : cows) h.assertTrue(cow.isAlive() && !cow.isOnFire() && cow.getHealth() == cow.getMaxHealth(), "the explosion touched the entity in its tick");
        h.assertTrue(w.pulseJobs() == 1, "the impulse is not put into operation");
        int budgetMs = IncomingConfig.SERVER.workBudgetMs.get();
        h.succeedWhen(() -> {
            h.assertTrue(w.pulseJobs() == 0, "the impulse is still going");
            long whole = cows.stream().filter(c -> c.isAlive() && !shocked(c)).count();
            h.assertTrue(whole == 0, "cows in 300 m without burn shock: " + whole);
            h.assertTrue(clock.maxUnitsPerTick() <= budgetMs, "per tick " + clock.maxUnitsPerTick() + " units by 1 ms with budget " + budgetMs + " ms");
            NuclearWorld.useClock(level.getServer(), new WorkClock());
            NuclearStrikes.clear(level);
        });
    }

    private static boolean shocked(LivingEntity e) {
        MobEffectInstance burns = e.getEffect(ModEffects.BURNS);
        return burns != null && burns.getAmplifier() == BurnsEffect.SHOCK;
    }

    /**
     * Restart in the middle of a wave: {@link NuclearWorld} is not saved, the front according to entities is started anew - and its countdown
     *  is from the front of the last tick, and not from the epicenter. 0.1 kt, 1 block = 20 m: through 2 tick after detonation of the front
     *  between 22 and 40 blocks; the cow in the 10 blocks has already been passed by him and does not beat a second time, the wave
     *  scares the cow in the 26 blocks (such a short wave cannot hurt her at ~1 psi - G&D, ch. 12).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_restart_front", skyAccess = true)
    public static void frontAfterRestartSkipsEntitiesInside(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Cow inside = h.spawn(EntityType.COW, CENTER.east(10));
        Cow ahead = h.spawn(EntityType.COW, CENTER.east(26));
        BlockPos g = h.absolutePos(CENTER);
        Detonation d = new Detonation(IDS.incrementAndGet(), Vec3.atBottomCenterOf(g), g.getY(), 0.1, true,
                level.getGameTime() - 2, 0, 0, 20_000, 7, 0.05f, false, Detonation.OLD_WAVE);
        h.assertTrue(d.frontRadius(1) > 10 && d.frontRadius(2) > 26 && d.frontRadius(1) < 26, "the front is not where the check awaits");
        NuclearEvents.get(level).add(d);
        // like after a restart: the world’s nuclear bursts are from a clean slate, the detonation is from saving
        level.removeData(ModAttachments.NUCLEAR_WORLD);
        h.succeedWhen(() -> {
            h.assertTrue(MobPanic.panicking(ahead), "the wave did not reach the cow ahead of the front");
            h.assertTrue(!MobPanic.panicking(inside) && inside.getHealth() == inside.getMaxHealth(), "the front hit the cow for the second time, which it had already passed");
            NuclearStrikes.clear(level);
        });
    }

    /**
     * Wave by craft Sable (boards 5×2×5 above the platform; 0.1 kt, 1 block = 20 m - from craft ~8 psi): an explosion at its
     *  point closest to the epicenter breaks the craft boards and does not touch the entity (their wave hits itself, due to pressure) -
     *  the object on the ground nearby is intact; a vanilla explosion would destroy it.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_aircraft", skyAccess = true)
    public static void blastBreaksAircraftNotEntities(GameTestHelper h) {
        aircraftBlast(h, true);
    }

    /** Without {@code block_damage}, an explosion on a craft does not break anything: neither the craft nor the glass next to it. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_aircraft_no_blocks", skyAccess = true)
    public static void aircraftBlastKeepsBlocksWithoutBlockDamage(GameTestHelper h) {
        aircraftBlast(h, false);
    }

    private static void aircraftBlast(GameTestHelper h, boolean blockDamage) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        BlockPos from = CENTER.offset(6, 5, -2), to = from.offset(4, 1, 4);
        BlockPos.betweenClosed(from, to).forEach(p -> h.setBlock(p, Blocks.OAK_PLANKS));
        BlockPos glass = CENTER.offset(8, 0, -5);
        h.setBlock(glass, Blocks.GLASS);
        ItemEntity item = h.spawnItem(Items.STONE, CENTER.offset(8, 0, 5));
        BlockPos a = h.absolutePos(from), b = h.absolutePos(to);
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
        Vec3 craft = Vec3.atCenterOf(h.absolutePos(from.offset(2, 0, 2)));
        SubLevelAccess[] sub = new SubLevelAccess[1];
        int[] planks = new int[1];
        boolean fires = IncomingConfig.SERVER.nukeFires.get();
        h.startSequence()
                .thenWaitUntil(() -> {
                    List<SubLevelAccess> near = SubLevels.near(level, craft, 8);
                    h.assertFalse(near.isEmpty(), "craft is not assembled");
                    sub[0] = near.getFirst();
                })
                .thenExecute(() -> {
                    planks[0] = aircraftPlanks(level, sub[0]);
                    h.assertTrue(planks[0] == 50, "in craft boards: " + planks[0]);
                    // fires from light would burn an object on the ground - only the wave is checked
                    IncomingConfig.SERVER.nukeFires.set(false);
                    IncomingConfig.SERVER.nukeBlockDamage.set(blockDamage);
                    NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 0.1, false, null, 0.05f);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    IncomingConfig.SERVER.nukeFires.set(fires);
                    IncomingConfig.SERVER.nukeBlockDamage.set(true);
                    NuclearStrikes.clear(level);
                    int left = aircraftPlanks(level, sub[0]);
                    if (blockDamage) {
                        h.assertTrue(left < planks[0], "the wave did not break craft: boards " + left);
                    } else {
                        h.assertTrue(left == planks[0], "without block_damage the wave broke craft: boards " + left);
                        h.assertBlockPresent(Blocks.GLASS, glass);
                    }
                    h.assertTrue(item.isAlive(), "explosion on craft hit an object on the ground");
                })
                .thenSucceed();
    }

    /** The boards in plot craft are around its center. */
    private static int aircraftPlanks(ServerLevel level, SubLevelAccess sub) {
        BlockPos c = BlockPos.containing(SubLevels.toPlot(sub, SubLevels.center(sub)));
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -6, -6), c.offset(6, 6, 6))) {
            if (level.getBlockState(p).is(Blocks.OAK_PLANKS)) n++;
        }
        return n;
    }

    static final TicketType<ChunkPos> HOLD = TicketType.create("incoming_test_hold", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * Stripes of untouched chunks in the zone: at the edge of visibility, the chunk drops below full loading and rises back,
     *  without unloading - {@code ChunkEvent.Load} does not arrive. Chunk C is fully loaded when detonated, but its
     *  neighbor is not there (waiting in line), then it goes down and rises again; The D chunk is already lowered during detonation (in the detonation snapshot
     *  it is not among the fully loaded ones) and rises after. Chunk E is the edge of a busy world: it is fully loaded
     *  all the time, its neighbors are never (like a seam between two player sites). All three must be
     *  destroyed and marked. Holders “below full” - ticket two chunks away from them (level 35: in memory, but not
     *  fully loaded).
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_stripes", skyAccess = true)
    public static void chunkDroppedBelowFullLoadStillScarred(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // at the pace of the game: C and D are raised back by background loading, and the server GameTest ticks without pauses - the period in ticks of
        //  ends before C becomes full again (“the glass in C is intact” a second after detonation)
        StrikeGameTests.gameSpeed(h);
        var chunks = level.getChunkSource();
        ChunkPos c = new ChunkPos(h.absolutePos(CENTER.east(80))), dPos = new ChunkPos(h.absolutePos(CENTER.west(80)));
        ChunkPos e = new ChunkPos(h.absolutePos(CENTER.north(80)));
        ChunkPos cAnchor = new ChunkPos(c.x + 2, c.z), dAnchor = new ChunkPos(dPos.x - 2, dPos.z);
        BlockPos[] glass = new BlockPos[3];
        Detonation[] det = new Detonation[1];
        chunks.addRegionTicket(HOLD, cAnchor, 0, cAnchor);
        chunks.addRegionTicket(HOLD, dAnchor, 0, dAnchor);
        chunks.addRegionTicket(HOLD, c, 0, c);
        chunks.addRegionTicket(HOLD, dPos, 0, dPos);
        chunks.addRegionTicket(HOLD, e, 0, e);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(c.x, c.z) != null && chunks.getChunkNow(dPos.x, dPos.z) != null
                        && chunks.getChunkNow(e.x, e.z) != null, "chunks are loading"))
                .thenExecute(() -> {
                    glass[0] = surface(level, c);
                    glass[1] = surface(level, dPos);
                    glass[2] = surface(level, e);
                    for (BlockPos g : glass) level.setBlock(g, Blocks.GLASS.defaultBlockState(), 3);
                    chunks.removeRegionTicket(HOLD, dPos, 0, dPos);
                })
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(dPos.x, dPos.z) == null, "D did not drop below full load"))
                .thenExecute(() -> {
                    // The detonation snapshot took C fully loaded; C has no fully loaded neighbours, so it waits.
                    // Remove the holder in the same tick: the chunk drops on the next world tick before the queue reaches it
                    // (the wave arrives several ticks later). A queue that reaches C while it is still full holds C and its
                    // neighbours itself (the edge of the loaded world), preventing it from dropping: with a 20-tick pause,
                    // the test would depend on whether the queue reached C in time (not always on CI).
                    det[0] = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 15, true, null, 0.1f);
                    chunks.removeRegionTicket(HOLD, c, 0, c);
                })
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(c.x, c.z) == null, "C did not drop below full load"))
                // longer than the queue repeat (40 tick): here C can fall out of it forever
                .thenIdle(60)
                .thenExecute(() -> {
                    chunks.addRegionTicket(HOLD, c, 1, c);
                    chunks.addRegionTicket(HOLD, dPos, 1, dPos);
                })
                .thenWaitUntil(() -> {
                    for (int i = 0; i < 3; i++) {
                        LevelChunk chunk = chunks.getChunkNow(glass[i].getX() >> 4, glass[i].getZ() >> 4);
                        String name = "CDE".substring(i, i + 1);
                        h.assertTrue(chunk != null, name + " didn't boot again");
                        h.assertFalse(level.getBlockState(glass[i]).is(Blocks.GLASS), "glass in " + name + " whole");
                        int scar = chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0);
                        h.assertTrue(scar >= det[0].id(), name + " not marked with detonation: " + scar);
                    }
                })
                .thenExecute(() -> {
                    for (ChunkPos p : new ChunkPos[]{cAnchor, dAnchor}) chunks.removeRegionTicket(HOLD, p, 0, p);
                    for (ChunkPos p : new ChunkPos[]{c, dPos}) chunks.removeRegionTicket(HOLD, p, 1, p);
                    chunks.removeRegionTicket(HOLD, e, 0, e);
                    NuclearStrikes.clear(level);
                })
                .thenSucceed();
    }

    /**
     * A chunk awaiting unloading is still in memory: the ticket is removed - vanilla immediately removes the holder from the visible map of chunks
     * ({@code processUnloads} → {@code pendingUnloads}), and unloads the chunk itself later, when it can be saved; ticket,
     *  returned during this time returns the same chunk without {@code ChunkEvent.Load}. The queue of ruins does not lose such a chunk:
     * , neither when setting up a detonation (chunk G - it is not among the visible ones), nor in work (chunk F - the work is not removed as
     *  from the unloaded one), otherwise the finished plan holds the square of the zone (“unplaced 2"). The window for waiting
     *  for unloading is set here deterministically: tick chunks without a time reserve ({@code ServerChunkCache.tick(() -> false,
     * false)}) transfers the removed holders to {@code pendingUnloads}, but does not unload them; the ruins queue is called in the same
     *  step, for F - with the clock ticking after the wave arrives. F and G - in 20 chunks from the site: closer ticket sites
     *  (FORCED, level 31, holds up to 13 chunks around) would leave them in memory below full loading, and not waiting for unloading.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_pending_unload", skyAccess = true)
    public static void chunkRevivedFromPendingUnloadStillScarred(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // at the pace of the game: the queue loads neighbors F and G with their tickets in the background (like the edge of E in chunkDroppedBelowFullLoadStillScarred)
        StrikeGameTests.gameSpeed(h);
        var chunks = level.getChunkSource();
        ChunkPos f = new ChunkPos(h.absolutePos(CENTER.east(320))), g = new ChunkPos(h.absolutePos(CENTER.west(320)));
        BlockPos[] glass = new BlockPos[2];
        Detonation[] det = new Detonation[1];
        chunks.addRegionTicket(HOLD, f, 0, f);
        chunks.addRegionTicket(HOLD, g, 0, g);
        h.startSequence()
                // and the generation of neighbors no longer holds them: the holder needed by the generation is not unloaded by vanilla
                .thenWaitUntil(() -> {
                    for (ChunkPos p : new ChunkPos[]{f, g}) {
                        var holder = chunks.chunkMap.getVisibleChunkIfPresent(p.toLong());
                        h.assertTrue(chunks.getChunkNow(p.x, p.z) != null && holder.getGenerationRefCount() == 0, p + " loading");
                    }
                })
                .thenExecute(() -> {
                    glass[0] = surface(level, f);
                    glass[1] = surface(level, g);
                    for (BlockPos p : glass) level.setBlock(p, Blocks.GLASS.defaultBlockState(), 3);
                    var scars = NuclearWorld.get(level).scars();
                    WorkClock clock = WorkClock.counting(1_000_000L);
                    // G is waiting to be unloaded when detonated: queuing should see it
                    toPendingUnload(h, chunks, g);
                    det[0] = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 15, true, null, 0.1f);
                    Detonation d = det[0];
                    double dx = f.getMiddleBlockX() + 0.5 - d.burst().x, dz = f.getMiddleBlockZ() + 0.5 - d.burst().z, dy = d.burst().y - d.groundY();
                    double toF = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    h.assertTrue(toF + 16 < d.ruinRadius(), "F outside the ruins radius: " + toF + " from " + d.ruinRadius());
                    long waveAtF = d.gameTime() + (long) d.arrivalTicks(toF);
                    clock.start(10_000_000_000L);
                    scars.work(level, level.getGameTime(), clock);
                    h.assertTrue(scars.queued(f.toLong()), "F is not in the ruins queue");
                    h.assertTrue(scars.queued(g.toLong()), "G, waiting to unload, was not included in the ruin queue");
                    // F is waiting to be unloaded when it is his turn: the work must remain
                    toPendingUnload(h, chunks, f);
                    clock.start(10_000_000_000L);
                    scars.work(level, waveAtF + 1, clock);
                    h.assertTrue(scars.queued(f.toLong()), "the work of F, waiting for unloading, has been removed");
                    // tickets are back - holders are returning from pendingUnloads with the same chunks, without ChunkEvent.Load
                    chunks.addRegionTicket(HOLD, f, 0, f);
                    chunks.addRegionTicket(HOLD, g, 0, g);
                    chunks.runDistanceManagerUpdates();
                    for (ChunkPos p : new ChunkPos[]{f, g}) {
                        h.assertTrue(chunks.chunkMap.getVisibleChunkIfPresent(p.toLong()) != null && !chunks.chunkMap.pendingUnloads.containsKey(p.toLong()),
                                p + " did not return from waiting for unloading");
                    }
                })
                .thenWaitUntil(() -> {
                    for (int i = 0; i < 2; i++) {
                        LevelChunk chunk = chunks.getChunkNow(glass[i].getX() >> 4, glass[i].getZ() >> 4);
                        String name = "FG".substring(i, i + 1);
                        h.assertTrue(chunk != null, name + " did not rise to full load");
                        h.assertFalse(level.getBlockState(glass[i]).is(Blocks.GLASS), "glass in " + name + " whole");
                        int scar = chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0);
                        h.assertTrue(scar >= det[0].id(), name + " not marked with detonation: " + scar);
                    }
                })
                .thenExecute(() -> {
                    for (ChunkPos p : new ChunkPos[]{f, g}) chunks.removeRegionTicket(HOLD, p, 0, p);
                    NuclearStrikes.clear(level);
                })
                .thenSucceed();
    }

    /** Remove the ticket chunk and bring its holder to wait for unloading: not in the visible map, but in memory (full chunk). */
    private static void toPendingUnload(GameTestHelper h, net.minecraft.server.level.ServerChunkCache chunks, ChunkPos p) {
        chunks.removeRegionTicket(HOLD, p, 0, p);
        // ticket levels, transfer of removed holders to pendingUnloads; without a time reserve, the unloading queue is not sorted,
        //  and no more than 200 holders are transferred per call (ChunkMap.processUnloads) - a removed ticket releases hundreds of
        //  around the chunk, and in what order, the coordinate hash decides: we call until it reaches it
        int ticks = 0;
        while (ticks < 16 && !chunks.chunkMap.pendingUnloads.containsKey(p.toLong())) {
            chunks.tick(() -> false, false);
            ticks++;
        }
        h.assertTrue(chunks.chunkMap.pendingUnloads.containsKey(p.toLong()), p + " did not wait for unloading for " + ticks + " tick chunks");
        // visible map - without it
        chunks.runDistanceManagerUpdates();
        h.assertTrue(chunks.chunkMap.getVisibleChunkIfPresent(p.toLong()) == null, p + " still in the visible map");
        h.assertTrue(chunks.chunkMap.pendingUnloads.get(p.toLong()) instanceof net.minecraft.server.level.ChunkHolder holder
                && holder.getChunkIfPresentUnchecked(net.minecraft.world.level.chunk.status.ChunkStatus.FULL) instanceof LevelChunk,
                p + " does not wait for unloading with a full chunk");
    }

    /** The top of the ground is in the middle of the chunk (the chunk is loaded). */
    static BlockPos surface(ServerLevel level, ChunkPos p) {
        int x = p.getMiddleBlockX(), z = p.getMiddleBlockZ();
        return new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
    }

    /**
     * Mobs get sick like players: 1000 Gr (brain form) - the effect of the disease immediately, death after 0,4 game hour (400
     *  ticks), and not at the time of the dose; the undead do not get sick; in the trail of precipitation, the mob gains a dose (in portions under the budget,
     * {@link MobFallout}). The patient is in a stone booth: the precipitation does not reach him, and the duration of the illness is not delayed by its dose.
     */
    @GameTest(template = "range", timeoutTicks = 1300, batch = "nuke_mob_radiation", skyAccess = true)
    public static void mobsGetRadiationSickness(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos booth = CENTER.east(10);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) if (dx != 0 || dz != 0 || dy == 2) h.setBlock(booth.offset(dx, dy, dz), Blocks.STONE);
            }
        }
        Cow sick = h.spawn(EntityType.COW, booth);
        Cow grazing = h.spawn(EntityType.COW, CENTER.west(3));
        var zombie = h.spawn(EntityType.ZOMBIE, CENTER.north(10));
        h.assertTrue(RadiationTicker.affectsMob(sick), "the cow is not irradiated");
        h.assertFalse(RadiationTicker.affectsMob(zombie), "the undead are irradiated");
        RadiationTicker.addDose(sick, 1000);
        h.assertTrue(sick.hasEffect(ModEffects.RADIATION_SICKNESS), "a cow with 1000 Gr does not have radiation sickness");
        // ground detonation with fallout two game hours earlier; the cow is at the epicenter - in the very wake
        BlockPos g = h.absolutePos(CENTER);
        Detonation d = new Detonation(IDS.incrementAndGet(), Vec3.atBottomCenterOf(g), g.getY(), 15, true,
                level.getGameTime() - 2000, 0, 5, 20_000, 7, 0.1f, true, Detonation.OLD_WAVE);
        MobFallout fallout = new MobFallout();
        WorkClock clock = WorkClock.counting(1_000_000L);
        long start = level.getGameTime();
        h.onEachTick(() -> {
            clock.start(IncomingConfig.SERVER.workBudgetMs.get() * 1_000_000L);
            fallout.work(level, List.of(d), clock);
            if (level.getGameTime() - start < 380) h.assertTrue(sick.isAlive(), "mob with 1000 Gr died prematurely");
        });
        h.succeedWhen(() -> {
            h.assertTrue(RadiationTicker.dose(grazing).doseGy() > 0, "in the trail of precipitation, the mob did not receive a dose");
            h.assertTrue(RadiationTicker.dose(zombie).doseGy() == 0, "the undead took a dose");
            h.assertFalse(sick.isAlive(), "mob with 1000 Gr is still alive");
        });
    }

    /**
     * The bypass of mobs, stretched by the budget longer than the period (the queue is occupied by destruction: here there is one unit of work
     *  per tick for hundreds of mobs), does not waste time: the dose increase for the bypass is the power of precipitation × all the time since the last
     *  snapshot. The walk time cut into two periods in a busy queue would under-dose the mobs.
     */
    @GameTest(template = "range", timeoutTicks = 1400, batch = "nuke_mob_fallout_starved", skyAccess = true)
    public static void starvedFalloutPassKeepsFullDose(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Cow grazing = h.spawn(EntityType.COW, CENTER.west(3));
        for (int i = 0; i < 2 * MobFallout.PERIOD + 100; i++) h.spawn(EntityType.SHEEP, CENTER.offset(-20 + i % 41, 0, 5 + i / 41));
        BlockPos g = h.absolutePos(CENTER);
        Detonation d = new Detonation(IDS.incrementAndGet(), Vec3.atBottomCenterOf(g), g.getY(), 15, true,
                level.getGameTime() - 2000, 0, 5, 20_000, 7, 0.1f, true, Detonation.OLD_WAVE);
        MobFallout fallout = new MobFallout();
        WorkClock clock = WorkClock.counting(1_000_000L);
        // when the cow received a dose (tick and increase): the entire bypass is visible from two adjacent increases
        List<long[]> gains = new java.util.ArrayList<>();
        double[] last = {0};
        h.onEachTick(() -> {
            clock.start(1_000_000L); // one unit of work per tick
            fallout.work(level, List.of(d), clock);
            double dose = RadiationTicker.dose(grazing).doseGy();
            if (dose > last[0]) gains.add(new long[]{level.getGameTime(), Double.doubleToLongBits(dose - last[0])});
            last[0] = dose;
        });
        h.succeedWhen(() -> {
            h.assertTrue(gains.size() >= 2, "rounds with dose: " + gains.size());
            for (int i = 1; i < gains.size(); i++) {
                long t = gains.get(i)[0], ticks = t - gains.get(i - 1)[0];
                h.assertTrue(ticks > 2 * MobFallout.PERIOD, "traversal shorter than two periods: " + ticks + " tick - check for nothing");
                double gain = Double.longBitsToDouble(gains.get(i)[1]);
                double expected = d.falloutRate(grazing.getX(), grazing.getZ(), t - d.gameTime())
                        * Shielding.fallout(level, RadiationTicker.middle(grazing)) * RadiationTicker.GY_PER_R * ticks / 1000.0;
                h.assertTrue(Math.abs(gain / expected - 1) < 0.05, "per round " + ticks + " tick dose " + gain + " Gr, we were waiting " + expected);
            }
        });
    }

    /**
     * Radiation of the first minute: under three blocks of stone is weakened by more than 100 times; behind the wall from the low explosion there remains a
     *  air-scattered part that comes from the sky (the trench does not save the whole thing).
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_shielding", skyAccess = true)
    public static void basementShieldsPromptRadiation(GameTestHelper h) {
        BlockPos cellar = CENTER.east(5);
        for (int i = 1; i <= 3; i++) h.setBlock(cellar.above(i), Blocks.STONE);
        Vec3 inside = Vec3.atCenterOf(h.absolutePos(cellar));
        Vec3 outside = Vec3.atCenterOf(h.absolutePos(CENTER.west(5)));
        Vec3 burst = inside.add(0, 50, 0);
        double open = Shielding.prompt(h.getLevel(), outside, outside.add(0, 50, 0));
        double roof = Shielding.prompt(h.getLevel(), inside, burst);
        h.assertTrue(open > 0.99, "there is a screen in an open place: " + open);
        h.assertTrue(roof < 0.01, "three blocks of stone were weakened only to " + roof);
        // a wall of two blocks to be undermined near the ground far in the south
        BlockPos trench = CENTER.west(12);
        for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy < 2; dy++) h.setBlock(trench.offset(dx, dy, 1), Blocks.STONE);
        Vec3 crouch = Vec3.atCenterOf(h.absolutePos(trench));
        double behind = Shielding.prompt(h.getLevel(), crouch, crouch.add(0, 0.2, 2000));
        h.assertTrue(behind > 0.1 && behind < 0.4, "behind the wall from a low explosion: " + behind);
        h.succeed();
    }

    /**
     * Precipitation lies on everything that is open to the sky, and shines through the roof and walls: in a stone house (walls and roof in a block) -
     *  less than a thousandth of the open space, near the wall of the house outside - noticeably less than in the field, but more than half.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_fallout_shielding", skyAccess = true)
    public static void houseShieldsFallout(GameTestHelper h) {
        BlockPos hut = CENTER.east(10);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    boolean shell = Math.abs(dx) == 3 || Math.abs(dz) == 3 || dy == 3;
                    if (shell) h.setBlock(hut.offset(dx, dy, dz), Blocks.STONE);
                }
            }
        }
        ServerLevel level = h.getLevel();
        double inside = Shielding.fallout(level, Vec3.atBottomCenterOf(h.absolutePos(hut)).add(0, 0.9, 0));
        double field = Shielding.fallout(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER.west(10))).add(0, 0.9, 0));
        double byWall = Shielding.fallout(level, Vec3.atBottomCenterOf(h.absolutePos(hut.west(4))).add(0, 0.9, 0));
        h.assertTrue(field > 0.99, "there is protection in the field: " + field);
        h.assertTrue(inside < 0.001, "left in the stone house " + inside);
        h.assertTrue(byWall > 0.5 && byWall < 0.95, "against the wall outside: " + byWall);
        h.succeed();
    }

    /** The explosion and the planned strike (at the location on the map - an explosion on the surface) survive the save, and the release cancels the strike. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_schedule", skyAccess = true)
    public static void scheduledStrikeSavesAndClears(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        h.assertTrue(NuclearStrikes.launch(level, target, true, 15, true, null), "remote launch failed");
        List<NuclearEvents.ScheduledStrike> scheduled = NuclearEvents.get(level).scheduled();
        h.assertTrue(scheduled.size() == 1, "scheduled strikes: " + scheduled.size());
        NuclearEvents.ScheduledStrike s = scheduled.getFirst();
        h.assertTrue(s.surface(), "hitting a location on the map is not marked as hitting a surface");
        var tag = NuclearEvents.ScheduledStrike.CODEC.encodeStart(NbtOps.INSTANCE, s).getOrThrow();
        h.assertTrue(NuclearEvents.ScheduledStrike.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow().equals(s), "the blow did not survive the save");
        Detonation d = detonation(h, CENTER, 300, 15, 0.1f);
        var dt = Detonation.CODEC.encodeStart(NbtOps.INSTANCE, d).getOrThrow();
        h.assertTrue(Detonation.CODEC.parse(NbtOps.INSTANCE, dt).getOrThrow().equals(d), "the explosion did not survive the preservation");
        // a forgotten explosion (precipitation has subsided) still destroys chunks loaded later; “Lights Out” forgets him too
        NuclearEvents events = NuclearEvents.get(level);
        Detonation old = new Detonation(d.id(), d.burst(), d.groundY(), d.yieldKt(), d.surface(), level.getGameTime() - NuclearEvents.FORGET_AFTER - 1,
                d.windDir(), d.windSpeed(), d.visibility(), d.seed(), d.scale(), d.fallout(), d.wave());
        events.add(old);
        events.prune(level.getGameTime());
        h.assertFalse(events.detonations().contains(old), "the old explosion is not forgotten");
        h.assertTrue(events.past().contains(old), "a forgotten explosion is not remembered for destruction");
        h.assertTrue(events.isPast(old.id()), "a forgotten explosion cannot be recognized by its number");
        NuclearStrikes.clear(level);
        h.assertTrue(NuclearEvents.get(level).scheduled().isEmpty(), "the all-clear did not cancel the blow");
        h.assertTrue(events.past().isEmpty(), "lights out haven't forgotten past explosions");
        h.succeed();
    }

    /**
     * The detonation site is loaded from launch, and not for 10 s to zero: in the next tick, the epicenter chunk already has a loading ticket
     *  (level 32 and below; without tick - neither ticket of the region, nor tick blocks), and the fresh place is ready before
     *  ends with sample - the server goes at the pace of the game so that the generation kept up just like in the game (ticket for 200 tick until
     *  detonation is in line behind the salvo areas, sample reaches zero, but there is no detonation). The place is only loading:
     *  while the sample is running, it does not have a ticket region and the blocks do not tick - and also
     * {@link #HELD} tick after the square 3 × 3 is ready, where the region with tick would already be ticking. The lights out release the place.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "nuke_ground", skyAccess = true)
    public static void nukeGroundLoadsFromLaunch(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER).offset(1500, 0, -1500));
        ChunkPos ground = new ChunkPos(BlockPos.containing(target));
        h.assertFalse(ua.zentix.incoming.util.Terrain.ready(level, ground.x, ground.z), "the explosion site is not fresh");
        h.assertTrue(NuclearStrikes.launchFrom(level, target, 15, true, null, 0, null), "launch failed");
        long detonate = NuclearEvents.get(level).scheduled().getFirst().detonateTime();
        java.util.UUID groundKey = new java.util.UUID(0L, NuclearEvents.get(level).scheduled().getFirst().id());
        int[] tick = {0}, readyAt = {-1}, aroundAt = {-1};
        h.onEachTick(() -> {
            tick[0]++;
            var holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ground.toLong());
            String failure = null;
            if (tick[0] == 2 && (holder == null || holder.getTicketLevel() > 32)) {
                failure = "the detonation site does not load from launch: level " + (holder == null ? "-" : holder.getTicketLevel());
            }
            boolean ready = ua.zentix.incoming.util.Terrain.ready(level, ground.x, ground.z);
            if (!ready && level.getGameTime() >= detonate) failure = "sample has run out, and the detonation site is not ready";
            // the place is just loading: no ticket region, no tick blocks
            if (!LifecycleGameTests.regionRadii(level, "incoming_nuclear_ground", groundKey).isEmpty()) failure = "at the site of the explosion ticket region";
            if (level.shouldTickBlocksAt(ground.toLong())) failure = "the explosion site is ticking in blocks";
            if (failure != null) {
                // the blow should not live to see other tests of this world
                NuclearStrikes.clear(level);
                throw new net.minecraft.gametest.framework.GameTestAssertException(failure);
            }
            if (!ready) return;
            if (readyAt[0] < 0) {
                Incoming.LOG.info("The detonation site is ready in {} tick after launch, sample - {}", tick[0], detonate - level.getGameTime() + tick[0]);
                readyAt[0] = tick[0];
            }
            // a region with tick would take the region's ticket when the entire square 3 × 3 is ready, and the center would tick in blocks:
            //  checking continues HELD tick after that
            if (!aroundReady(level, ground)) return;
            if (aroundAt[0] < 0) aroundAt[0] = tick[0];
            if (tick[0] - aroundAt[0] < HELD) return;
            NuclearStrikes.clear(level);
            h.assertTrue(NuclearEvents.get(level).scheduled().isEmpty(), "the all-clear did not cancel the blow");
            h.succeed();
        });
    }

    /** How many tick is the location of the detonation checked without a tick after the square 3 × 3 around it is ready. */
    private static final int HELD = 40;

    private static boolean aroundReady(ServerLevel level, ChunkPos centre) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) if (!ua.zentix.incoming.util.Terrain.ready(level, centre.x + dx, centre.z + dz)) return false;
        }
        return true;
    }

    /** ICBM starts at the launcher (30 blocks behind) and goes up; the strike is recorded in the timer. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_icbm", skyAccess = true)
    public static void icbmLiftsOffBehindLauncher(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 launcher = Vec3.atBottomCenterOf(h.absolutePos(CENTER.west(28)));
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER.west(28)).west(500));
        // looks west (yaw 90), towards the target - the missile launches in 30 blocks behind, to the east, on the site
        h.assertTrue(NuclearStrikes.launchFrom(level, target, 15, true, launcher, 90, null), "launch failed");
        h.assertTrue(NuclearEvents.get(level).scheduled().size() == 1, "hit not recorded");
        // the timer can be canceled immediately: a complete explosion is not needed in the test world, the rocket flies on its own
        NuclearStrikes.clear(level);
        List<IcbmEntity> icbms = level.getEntitiesOfClass(IcbmEntity.class, new net.minecraft.world.phys.AABB(launcher, launcher).inflate(64));
        h.assertTrue(icbms.size() == 1, "missiles at launcher: " + icbms.size());
        IcbmEntity icbm = icbms.getFirst();
        h.assertTrue(icbm.getX() > launcher.x + 25, "the rocket did not start behind: " + icbm.position());
        double startY = icbm.getY();
        h.succeedWhen(() -> h.assertTrue(icbm.isRemoved() || icbm.getY() > startY + 40, "the rocket does not gain altitude: " + icbm.getY()));
    }

    /**
     * A normal shutdown (who does not have the right to nuclear weapons) does not cancel nuclear strikes: the ICBM and its timer, the missile with a nuclear
     *  warhead on the launcher and outside the world remain, conventional missiles are removed. Nuclear shutdown removes everything.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_clear", skyAccess = true)
    public static void conventionalClearKeepsNuclearStrikes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 launcher = Vec3.atBottomCenterOf(h.absolutePos(CENTER.west(28)));
        Vec3 target = launcher.add(-500, 0, 0);
        h.assertTrue(NuclearStrikes.launchFrom(level, target, 15, true, launcher, 90, null), "launch ICBM failed");
        IcbmEntity icbm = level.getEntitiesOfClass(IcbmEntity.class, new net.minecraft.world.phys.AABB(launcher, launcher).inflate(64)).getFirst();
        Loadout.Nuke warhead = new Loadout.Nuke(15, true, true);
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(CENTER)).add(0, 1, 0);
        CruiseMissileEntity nuclearOnRail = onRail(level, rail, target, warhead);
        CruiseMissileEntity conventionalOnRail = onRail(level, rail.add(0, 0, 4), target, null);
        CruiseMissileEntity nuclearVirtual = virtual(level, target.add(0, 60, 2000), target, warhead);
        CruiseMissileEntity conventionalVirtual = virtual(level, target.add(0, 60, -2000), target, null);
        LauncherEntity missileLauncher = LauncherEntity.create(level, rail.add(6, -1, 0), 0, WeaponType.MISSILE, null, false);
        LauncherEntity droneLauncher = LauncherEntity.create(level, rail.add(-6, -1, 0), 0, WeaponType.DRONE, null, false);
        level.addFreshEntity(missileLauncher);
        level.addFreshEntity(droneLauncher);

        // who removed what - as a line in the log (otherwise the ICBM removed by another player’s “End Light” disappears from the log without a trace)
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable unwatch = watchLog(line -> {
            if (line.startsWith("Clear")) lines.add(line);
        });
        StrikeGameTests.afterTest(h, unwatch);
        int icbmId = NuclearEvents.get(level).scheduled().getFirst().id();

        ServerActions.clearAll(level.getServer(), false, "check");
        // numbers are not exact: the shutdown removes everything in the world, and it contains shells and the launcher of the previous batch
        h.assertTrue(lines.size() == 1 && lines.getFirst().startsWith("Clear — check: projectiles ") && lines.getFirst().contains(", launchers ")
                && !lines.getFirst().contains("ICBM"), "normal hang up line: " + lines);
        h.assertFalse(missileLauncher.isRemoved(), "the usual release removed the launcher from under a missile with a nuclear warhead");
        h.assertTrue(droneLauncher.isRemoved(), "normal hang up did not remove the empty launcher");
        h.assertTrue(!icbm.isRemoved() && NuclearEvents.get(level).scheduled().size() == 1, "normal curfew canceled by ICBM");
        h.assertFalse(nuclearOnRail.isRemoved(), "a normal shutdown removed a missile from a nuclear warhead");
        h.assertTrue(VirtualFlights.get(level).flights().contains(nuclearVirtual), "a normal shutdown removed the nuclear missile from the world");
        h.assertTrue(conventionalOnRail.isRemoved() && conventionalVirtual.isRemoved()
                && !VirtualFlights.get(level).flights().contains(conventionalVirtual), "a regular release did not remove conventional missiles");

        ServerActions.clearAll(level.getServer(), true, "check");
        h.assertTrue(lines.size() == 2 && lines.get(1).startsWith("Clear with nukes — check: projectiles ")
                && lines.get(1).contains("ICBM #" + icbmId + " (15 kt at "), "nuclear clear line: " + lines);
        h.assertTrue(icbm.isRemoved() && NuclearEvents.get(level).scheduled().isEmpty(), "nuclear shutdown did not cancel ICBM");
        h.assertTrue(nuclearOnRail.isRemoved() && nuclearVirtual.isRemoved() && VirtualFlights.get(level).flights().isEmpty(),
                "nuclear shutdown did not remove missiles from nuclear warhead");
        h.assertTrue(missileLauncher.isRemoved(), "nuclear shutdown did not remove the launcher");
        h.succeed();
    }

    /** Cruise missile on a rail: stands until ignited. */
    private static CruiseMissileEntity onRail(ServerLevel level, Vec3 rail, Vec3 target, @Nullable Loadout.Nuke warhead) {
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.placeOnLauncher(rail, 0, 40, 1000, 0, new Target.Point(target), target, null);
        m.setRoute(Route.direct());
        m.setNuclear(warhead);
        level.addFreshEntity(m);
        return m;
    }

    /** A cruise missile in flight out of the world. */
    private static CruiseMissileEntity virtual(ServerLevel level, Vec3 start, Vec3 target, @Nullable Loadout.Nuke warhead) {
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.launch(start, new Target.Point(target), target, null);
        m.setRoute(Route.direct());
        m.setNuclear(warhead);
        VirtualFlights.launch(level, m);
        return m;
    }

    /**
     * Wave on the bodies (15 kt near the ground, 1 block = 1 m, explosion to the west, near the bodies 8 psi): a cow is thrown into the field to the east; a cow
     *  with a wall in 2 blocks behind her is thrown against the wall - she flies towards the wall ~6 m/s (skull fracture: in half - with 5,5 m/s),
     *  she is seriously wounded - almost 80 % (in the field - somersaults, less than 30%), and she loses health no less than this fraction
     *  (how much more is decided by her randomness, {@code BlastInjury.lostShare}); there is no wave in a closed stone booth; in a
     *  house with a door to the east there is pressure, but there is no wind - it doesn’t blow. Covered by rubble - died or lost less than
     *  less than half of her health. The window in front of the cow wounds her with fragments even when the ruins had already knocked it out (the front found
     *  it intact); the carpet under your feet is not a wall behind your back, but a stone close to you is a wall; under the crown, behind the bush that
     *  hides the ball, there is a wind (a wave blows through the foliage). The memorized window and wall - until the body left them before the front arrived
     *  (window - 2 block, wall - half a block).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_blast_bodies", skyAccess = true)
    public static void blastHitsBodiesByPhysics(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos open = CENTER.north(12), walled = CENTER.north(4), booth = CENTER.south(4), hut = CENTER.south(14), rubble = CENTER.west(10);
        for (int dy = 0; dy <= 2; dy++) {
            for (int dz = -1; dz <= 1; dz++) h.setBlock(walled.offset(2, dy, dz), Blocks.STONE);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) if (dx != 0 || dz != 0 || dy == 2) h.setBlock(booth.offset(dx, dy, dz), Blocks.STONE);
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    boolean door = dx == 2 && dz == 0 && dy <= 1;
                    if (dy == 3 || wall && !door) h.setBlock(hut.offset(dx, dy, dz), Blocks.STONE);
                }
            }
        }
        Cow[] cows = new Cow[5];
        BlockPos[] at = {open, walled, booth, hut, rubble};
        for (int i = 0; i < cows.length; i++) {
            cows[i] = h.spawn(EntityType.COW, at[i]);
            cows[i].setNoAi(true);
        }
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(open));
        double lo = 10, hi = 20_000;
        for (int i = 0; i < 50; i++) {
            double mid = 0.5 * (lo + hi);
            if (blastFromWest(h, target, mid).psi(target) > 8) lo = mid;
            else hi = mid;
        }
        Detonation d = blastFromWest(h, target, hi);
        String psi = String.format(Locale.ROOT, " (%.1f psi)", d.psi(cows[1].position()));
        h.assertTrue(BlastBody.free(level, level.getChunkAt(cows[4].blockPosition())).contains(cows[4]), "the cow was already buried before the collapse");
        h.setBlock(rubble, Blocks.GRAVEL);
        h.setBlock(rubble.above(), Blocks.GRAVEL);
        h.assertTrue(BlastBody.buried(level, cows[4]), "the cow is not covered in gravel");
        BlastBody.Wound field = BlastBody.wound(level, d, cows[0], null), wall = BlastBody.wound(level, d, cows[1], null);
        h.assertTrue(field != null && field.injury() < 0.3, "a cow in a field was wounded as if hit by a wall" + psi + ": " + field);
        h.assertTrue(wall != null && wall.injury() > 0.75, "the cow was thrown weakly against the wall" + psi + ": " + wall);
        for (int i = 0; i < 4; i++) BlastBody.hit(level, d, cows[i], null, null);
        BlastBody.bury(level, d, cows[4], null);
        h.assertTrue(cows[0].getDeltaMovement().x > 0.2, "the cow in the field was not thrown to the east" + psi + ": " + cows[0].getDeltaMovement());
        h.assertTrue(!cows[1].isAlive() || cows[1].getHealth() <= (1 - Math.min(BlastInjury.CASUALTY_SHARE, wall.injury())) * cows[1].getMaxHealth() + 1e-3,
                "a cow thrown against a wall is almost unharmed" + psi + ": " + cows[1].getHealth());
        h.assertTrue(cows[2].getHealth() == cows[2].getMaxHealth() && cows[2].getDeltaMovement().lengthSqr() < 1e-6,
                "the wave reached the closed booth");
        h.assertTrue(Math.abs(cows[3].getDeltaMovement().x) < 1e-6, "in a house without a window, a cow was thrown by the wind to an explosion");
        h.assertTrue(!cows[4].isAlive() || cows[4].getHealth() <= 0.5f * cows[4].getMaxHealth(),
                "the buried cow is almost intact: " + cows[4].getHealth());
        aroundBodies(h, d);
        h.succeed();
    }

    /** Window, wall and crown near the body ({@link #blastHitsBodiesByPhysics}): east of the previous cows, explosion to the west. */
    private static void aroundBodies(GameTestHelper h, Detonation d) {
        ServerLevel level = h.getLevel();
        BlockPos glazed = CENTER.east(10), carpeted = CENTER.east(10).north(10), canopy = CENTER.east(10).south(10), backed = CENTER.east(20);
        for (int dy = 0; dy <= 2; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                h.setBlock(glazed.offset(-2, dy, dz), Blocks.GLASS);
                h.setBlock(backed.offset(1, dy, dz), Blocks.STONE);
            }
        }
        for (int dx = 0; dx <= 3; dx++) h.setBlock(carpeted.east(dx), Blocks.WHITE_CARPET);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) h.setBlock(canopy.offset(a, 2, b), leaves);
            h.setBlock(canopy.offset(-1, 0, a), leaves);
            h.setBlock(canopy.offset(-1, 1, a), leaves);
        }
        Cow behindGlass = still(h, Vec3.atBottomCenterOf(glazed)), onCarpet = still(h, Vec3.atBottomCenterOf(carpeted).add(0, 1 / 16.0, 0)),
                underTree = still(h, Vec3.atBottomCenterOf(canopy)), atWall = still(h, Vec3.atBottomCenterOf(backed));
        BlastBody.Before window = BlastBody.before(level, d, behindGlass);
        h.assertTrue(window.glass(), "glass in 2 blocks in front of the cow is not visible");
        for (int dy = 0; dy <= 2; dy++) {
            for (int dz = -1; dz <= 1; dz++) h.setBlock(glazed.offset(-2, dy, dz), Blocks.AIR);
        }
        BlastBody.Wound remembered = BlastBody.wound(level, d, behindGlass, window), now = BlastBody.wound(level, d, behindGlass, null);
        h.assertTrue(remembered != null && now != null && remembered.injury() > now.injury(),
                "a window broken by ruins, which was caught by the front, is not injured by shrapnel: " + remembered + " / " + now);
        // by the time the front arrived, the cow had moved away: in block 1,5 the window was still in front of her, in block 3 it was no longer there
        BlastBody.Wound walked = BlastBody.wound(level, d, behindGlass, window.drifted(window.at().add(0, 0, 1.5)));
        BlastBody.Wound away = BlastBody.wound(level, d, behindGlass, window.drifted(window.at().add(0, 0, 3)));
        h.assertTrue(walked != null && walked.injury() == remembered.injury(), "in 1,5 block from the remembered location the window is forgotten");
        h.assertTrue(away != null && away.injury() == now.injury(), "in 3 blocks from the remembered location the window is still remembered");
        // the wall is close behind your back: it still reflects half a block from the remembered place, but no longer reflects a block away
        BlastBody.Before wall = new BlastBody.Before(onCarpet.position(), true, false, 0);
        BlastBody.Wound against = BlastBody.wound(level, d, onCarpet, wall.drifted(onCarpet.position().add(0, 0, 0.4)));
        BlastBody.Wound off = BlastBody.wound(level, d, onCarpet, wall.drifted(onCarpet.position().add(0, 0, 1)));
        h.assertTrue(against != null && off != null && against.injury() > off.injury(),
                "the remembered wall behind my back did not increase the pressure on my lungs: " + against + " / " + off);
        h.assertFalse(BlastBody.before(level, d, onCarpet).backed(), "carpet underfoot - wall behind");
        h.assertTrue(BlastBody.before(level, d, atWall).backed(), "a stone close behind your back is not a wall");
        h.assertFalse(NuclearWarhead.sees(level, d, underTree), "the bush does not hide the ball - the check means nothing");
        BlastBody.Wound tree = BlastBody.wound(level, d, underTree, null);
        h.assertTrue(tree != null && tree.wind() && tree.throwSpeed() > 0, "there is no wind waves under the crown: " + tree);
    }

    private static Cow still(GameTestHelper h, Vec3 at) {
        Cow cow = h.spawn(EntityType.COW, at);
        cow.setNoAi(true);
        return cow;
    }

    /** 15 kt near the ground in {@code distance} blocks west of {@code target}, 1 block = 1 m. */
    private static Detonation blastFromWest(GameTestHelper h, Vec3 target, double distance) {
        return new Detonation(IDS.incrementAndGet(), new Vec3(target.x - distance, target.y, target.z), target.y, 15, true,
                h.getLevel().getGameTime(), 0, 0, 20_000, 7, 1, false, Detonation.OLD_WAVE);
    }
}
