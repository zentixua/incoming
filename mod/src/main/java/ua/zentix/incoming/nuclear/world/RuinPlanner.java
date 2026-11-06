package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.WorldNoise;
import ua.zentix.incoming.registry.ModBlocks;

import java.util.ArrayList;
import java.util.List;

/**
 * One chunk's ruins from one detonation, planned without world changes ({@link RuinPlan}): wave/light changes to each position
 * as chunk-section deltas; fires where they survive on ruins (DESIGN-nuke §3.2–3.4).
 * Destruction uses two physical processes without “structure or terrain” rules:
 * <ul>
 * <li>pressure differences break thin elements ({@link Blast}): walls, floors, windows, columns, tall narrow masses;
 * thicker mountains, cliffs, banks and ground stand;</li>
 * <li>what lost support falls ({@link Collapse}): stories without walls, roof on shattered glass, leaves without trunk,
 * water without bottom, attachments without support.</li>
 * </ul>
 * Then post-ruin ground (highest supporting block): rubble where structures collapsed (nothing inside
 * fireball), trunks toppled away from epicenter, wave-stripped turf, light: scorch, burned grass,
 * burned plants, melted snow, trinitite and boiling water inside fireball, scorched biomes ({@link ScorchedBiomes}). What happens
 * where follows damage curves with world noise ({@link Fragility}), not thresholds: ragged, patchy zone edges.
 * <p>
 * All computed from original chunk-window blocks (chunk plus 8 neighbors; neighbors already ruined use their old blocks),
 * randomness from coordinate hashes: same position plan in advance (during ICBM flight) and at detonation, regardless
 * of chunk order. Chunk and radius-2 neighbors fully loaded (neighbor fractures read their own neighbors).
 */
public final class RuinPlanner {
    /** Pressure stripping turf from half the positions (spread {@link #STRIP_BETA}). */
    static final double STRIP_PSI = 8;
    /** Ground light (cal/cm²) burning turf to bare soil at half the positions. */
    static final double BURN_Q = 15;
    /** Light leaving scorch, charred litter (podzol), at half the positions. */
    static final double CHAR_Q = 30;
    /** Light burning half the above-ground plants (grass, flowers, bushes, crops) and melting snow layers. */
    static final double PLANT_Q = 6;
    /** Damage-curve spread (lognormal): pressure more even, scorch patches widest. */
    static final double STRIP_BETA = 0.25, BURN_BETA = 0.35, CHAR_BETA = 0.45, PLANT_BETA = 0.4;
    /** Fraction of burned plants leaving dead bush. */
    static final double DEAD_BUSH = 0.08;
    /** World-noise layers: different consequences, different patches. */
    private static final int LAYER_BURN = 1, LAYER_CHAR = 2, LAYER_STRIP = 3, LAYER_PLANT = 4, LAYER_BIOME = 5;
    /** Local randomness: world noise (patches) plus position hash (grain); squared weights sum to 1, preserving spread 1. */
    private static final double SMOOTH = 0.8, GRAIN = 0.6;
    /**
     * Maximum blast-facing slope light relative to flat ground; slope-shadow edge penumbra, rad (fireball not point:
     * several degrees across from kilometers away), also minimum burst elevation above horizon used in calculation.
     */
    private static final double MAX_INCIDENCE = 2.5, LOW_SUN = 0.1;
    /** Ground slope from neighbors up to this many columns each side: terrain steps do not produce contour stripes. */
    private static final int SLOPE_REACH = 2;
    /**
     * Fraction of ruin radius at edge over which light trace fades: no plans beyond, so blast-facing slope
     * that light could still singe would otherwise end in a circular boundary.
     */
    private static final double EDGE_FADE = 0.2;
    /** Surrounding chunks that must be fully loaded for plan. */
    public static final int REACH = 2;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState[] STONE_RUBBLE = {Blocks.GRAVEL.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.TUFF.defaultBlockState()};
    private static final BlockState[] WOOD_RUBBLE = {Blocks.COARSE_DIRT.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
            Blocks.COARSE_DIRT.defaultBlockState(), Blocks.ROOTED_DIRT.defaultBlockState()};

    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState(), COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState(),
            PODZOL = Blocks.PODZOL.defaultBlockState(), DEAD_BUSH_STATE = Blocks.DEAD_BUSH.defaultBlockState(),
            WATER = Blocks.WATER.defaultBlockState(), FIRE = Blocks.FIRE.defaultBlockState(), SOUL_FIRE = Blocks.SOUL_FIRE.defaultBlockState();
    /** Ground marked “snowy” beneath snow ({@code snowy}): no snow above means no snow appearance. */
    private static final BlockState[] SNOWY_GROUND = {Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.PODZOL.defaultBlockState(),
            Blocks.MYCELIUM.defaultBlockState()};
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Fire faces toward flammable neighbors (like {@code FireBlock.getStateForPlacement}, no downward faces). */
    private static final Direction[] FIRE_FACE_DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};
    private static final net.minecraft.world.level.block.state.properties.BooleanProperty[] FIRE_FACES = {BlockStateProperties.NORTH,
            BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST, BlockStateProperties.UP};

    /** Property-table reset after which own states already present (server thread). */
    private static int ownStates = -1;

    /** States plan writes itself (rubble, fire, ground): properties published with snapshots. */
    static void ensureOwnStates() {
        if (ownStates == Blast.generation) return;
        ownStates = Blast.generation;
        for (BlockState st : STONE_RUBBLE) Blast.ensure(st);
        for (BlockState st : WOOD_RUBBLE) Blast.ensure(st);
        for (BlockState st : new BlockState[]{AIR, DIRT, COARSE_DIRT, PODZOL, DEAD_BUSH_STATE, WATER, ModBlocks.TRINITITE.get().defaultBlockState()}) Blast.ensure(st);
        for (BlockState st : SNOWY_GROUND) {
            Blast.ensure(st);
            Blast.ensure(st.setValue(BlockStateProperties.SNOWY, true));
        }
        for (BlockState st : Blocks.FIRE.getStateDefinition().getPossibleStates()) Blast.ensure(st);
        for (BlockState st : Blocks.SOUL_FIRE.getStateDefinition().getPossibleStates()) Blast.ensure(st);
    }

    private RuinPlanner() {}

    /** Position/salt hash as number in [0, 1). */
    static double hash(int x, int y, int z, int salt) {
        int h = Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z)) * 31 + salt);
        return (h >>> 8) / (double) (1 << 24);
    }

    /** Position hash as normal variable ~N(0, 1). */
    static double grain(int x, int y, int z, int salt) {
        return Fragility.inverse((hash(x, y, z, salt) * (1 << 24) + 0.5) / (1 << 24));
    }

    /** Column randomness for damage curve: world-noise {@code layer} plus hash grain, ~N(0, 1). */
    private static double chance(WorldNoise noise, int x, int z, int layer, int salt) {
        return SMOOTH * noise.at(x + 0.5, z + 0.5, layer) + GRAIN * grain(x, 0, z, salt);
    }

    /** Position damage curve for {@code effect}; compute noise only if consequence possible. */
    private static boolean struck(double effect, double median, double beta, WorldNoise noise, int x, int z, int layer, int salt) {
        return Fragility.possible(effect, median, beta) && Fragility.hit(effect, median, beta, chance(noise, x, z, layer, salt));
    }

    /** Chunk ruin plan (chunk plus radius-{@link #REACH} neighbors fully loaded, server thread). */
    public static RuinPlan plan(ServerLevel level, Detonation d, LevelChunk chunk) {
        return plan(level, NuclearWorld.get(level).ruins(level, d, level.getGameTime()), chunk);
    }

    /**
     * For tests: fresh chunk plan (without detonation cache), on server or background threads (server
     * thread awaits task). Matching plans through both paths: background solver reads no world.
     */
    public static RuinPlan planFresh(ServerLevel level, Detonation d, LevelChunk chunk, boolean background) {
        RuinContext ctx = new RuinContext(level, d);
        if (!background) return plan(level, ctx, chunk);
        if (!ctx.submit(level, chunk)) throw new IllegalStateException("ruin background threads busy");
        return await(level, ctx, chunk);
    }

    /**
     * For tests: chunk plan from disk-read snapshots ({@link DiskShots}), background solver; chunk and
     * neighbors serialized as world would ({@code ChunkSerializer.write}), parsed as outside-memory chunks;
     * window uses only these (in-memory chunk snapshot, {@link RuinContext#shot}, would replace disk snapshot).
     */
    public static RuinPlan planFromDisk(ServerLevel level, Detonation d, LevelChunk chunk) {
        return planFromDisk(level, d, chunk, java.util.function.UnaryOperator.identity());
    }

    /** Same with serialized data as actually stored ({@code onDisk}: e.g. upgraded 1.17-world chunk). */
    public static RuinPlan planFromDisk(ServerLevel level, Detonation d, LevelChunk chunk, java.util.function.UnaryOperator<net.minecraft.nbt.CompoundTag> onDisk) {
        RuinContext ctx = new RuinContext(level, d);
        ChunkPos c = chunk.getPos();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                LevelChunk n = level.getChunkSource().getChunkNow(c.x + dx, c.z + dz);
                if (n == null) throw new IllegalStateException("neighbour " + (c.x + dx) + ", " + (c.z + dz) + " not loaded");
                DiskShots.Read read = DiskShots.parse(level, new ChunkPos(c.x + dx, c.z + dz),
                        onDisk.apply(net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, n)));
                if (read.skip() != null) throw new IllegalStateException("chunk from disk not parsed: " + read.skip());
                ctx.putDisk(level, read);
            }
        }
        return fromCachedDisk(level, ctx, c);
    }

    /** Chunk plan from disk-snapshot window cached in {@code ctx} ({@link RuinContext#putDisk}), background solver. */
    private static RuinPlan fromCachedDisk(ServerLevel level, RuinContext ctx, ChunkPos c) {
        RuinContext.Grid g = ctx.cachedGridForTest(level, c);
        for (int dx = -RuinContext.Grid.R; dx <= RuinContext.Grid.R; dx++) {
            for (int dz = -RuinContext.Grid.R; dz <= RuinContext.Grid.R; dz++) {
                if (g.at(dx, dz) == null || !g.at(dx, dz).fromDisk()) throw new IllegalStateException("window of " + c + " not from disk at " + dx + ", " + dz);
            }
        }
        return finish(g, Collapse.solve(g));
    }

    /**
     * For tests: background plan for in-memory chunk as ruin queue builds it ({@link ScarQueue}): window uses in-memory
     * neighbor snapshots, disk for remainder ({@link RuinContext#requestWindow}); neighbor absent as whole disk chunk means solid
     * mass in window. {@code window}: [0] disk neighbors read, [1] those absent as whole disk chunks.
     */
    public static RuinPlan planWithWindow(ServerLevel level, Detonation d, LevelChunk chunk, int[] window) {
        RuinContext ctx = new RuinContext(level, d);
        long until = System.nanoTime() + 60_000_000_000L;
        while (ctx.requestWindow(level, chunk.getPos()) > 0) {
            if (System.nanoTime() > until) throw new IllegalStateException("window neighbours not read from disk within a minute");
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        window[0] = ctx.windowReads;
        window[1] = ctx.windowAbsent;
        if (!ctx.submit(level, chunk)) throw new IllegalStateException("ruin background threads busy");
        return await(level, ctx, chunk);
    }

    /**
     * For tests: flash-layer chunk plan as built by detonation ruins whose snapshots are gone (evicted,
     * retaken): discard all detonation snapshots/fractures; retake window from pre-flash world
     * ({@link RuinPlan#unflash}). {@code way}: 0 server thread, 1 background, 2 disk: chunk/neighbors
     * serialized as world would, parsed ({@link DiskShots}), window only from them (same solver as
     * background threads).
     */
    public static RuinPlan testPlanOverFlash(ServerLevel level, Detonation d, LevelChunk chunk, int way) {
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, level.getGameTime());
        ctx.dropShotsForTest();
        if (way == 0) return plan(level, ctx, chunk);
        if (way == 1) {
            if (!ctx.submit(level, chunk)) throw new IllegalStateException("ruin background threads busy");
            return await(level, ctx, chunk);
        }
        ChunkPos c = chunk.getPos();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                LevelChunk n = level.getChunkSource().getChunkNow(c.x + dx, c.z + dz);
                if (n == null) throw new IllegalStateException("neighbour " + (c.x + dx) + ", " + (c.z + dz) + " not loaded");
                DiskShots.Read read = DiskShots.parse(level, n.getPos(), net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, n));
                if (read.skip() != null) throw new IllegalStateException("chunk from disk not parsed: " + read.skip());
                ctx.putDisk(level, read);
            }
        }
        RuinPlan plan = fromCachedDisk(level, ctx, c);
        ctx.dropShotsForTest();
        return plan;
    }

    private static RuinPlan await(ServerLevel level, RuinContext ctx, LevelChunk chunk) {
        long until = System.nanoTime() + 60_000_000_000L;
        while (!ctx.done(chunk.getPos().toLong())) {
            if (System.nanoTime() > until) throw new IllegalStateException("background plan not ready within a minute");
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        RuinPlan plan = ctx.collect(level, chunk);
        if (plan == null) throw new IllegalStateException("background plan failed");
        return plan;
    }

    /**
     * Plan-cost measurement (tests): {collapse time, completion time}, ns, for chunk whose fractures already in detonation cache
     * ({@link #blastsReady}).
     */
    public static long[] timedParts(ServerLevel level, Detonation d, LevelChunk chunk) {
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, level.getGameTime());
        RuinContext.Grid g = ctx.grid(level, chunk.getPos(), false);
        long t0 = System.nanoTime();
        Collapse c = Collapse.solve(g);
        long t1 = System.nanoTime();
        finish(g, c);
        return new long[]{t1 - t0, System.nanoTime() - t1};
    }

    /**
     * Tests: ground trace without ruins ({@link #scorch}) for in-memory chunk, with same detonation shadow heightmaps as
     * {@link #plan(ServerLevel, Detonation, LevelChunk)}.
     */
    public static RuinPlan testScorch(ServerLevel level, Detonation d, LevelChunk chunk) {
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, level.getGameTime());
        RuinContext.Grid g = ctx.grid(level, chunk.getPos(), false);
        return scorch(ctx.scorchGrid(level, g.at(0, 0), Blast.SERVER));
    }

    /**
     * For tests: incident ground pressure, psi, where wave can break plant: reflection on exposed
     * face ({@link Blast#reflected}) reaches lowest plant threshold.
     */
    public static double testPlantBreakPsi() {
        double lo = 0, hi = BlockResponse.PLANT_PSI;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (Blast.reflected(mid) >= BlockResponse.PLANT_PSI * BlockResponse.JITTER_MIN) hi = mid;
            else lo = mid;
        }
        return lo;
    }

    /**
     * Whether chunk ready for one-unit plan: window fractures cached. Otherwise compute one fracture (the
     * work unit), plan next unit.
     */
    public static boolean blastsReady(ServerLevel level, Detonation d, LevelChunk chunk) {
        return blastsReady(level, NuclearWorld.get(level).ruins(level, d, level.getGameTime()), chunk);
    }

    static boolean blastsReady(ServerLevel level, RuinContext ctx, LevelChunk chunk) {
        return !IncomingConfig.SERVER.nukeBlockDamage.get() || ctx.blastsReady(level, chunk.getPos());
    }

    static RuinPlan plan(ServerLevel level, RuinContext ctx, LevelChunk chunk) {
        RuinContext.Grid g = ctx.grid(level, chunk.getPos(), false);
        RuinPlan plan = finish(g, Collapse.solve(g));
        ctx.planned(chunk.getPos());
        return plan;
    }

    /**
     * Plan from finished collapse (any thread): fires, rubble, turf, light, toppled trunks from chunk snapshot,
     * plus section deltas. Reads no world: snapshot blocks, window property table ({@link Blast.Props}),
     * light shadow from detonation heightmaps ({@link RuinContext.Grid#lit}).
     */
    static RuinPlan finish(RuinContext.Grid g, Collapse c) {
        Detonation d = g.ctx().d;
        ChunkShot shot = g.at(0, 0);
        Sections s = new Sections(shot, g.view());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (var e : c.changes.long2ObjectEntrySet()) {
            long at = e.getLongKey();
            s.set(BlockPos.getX(at) & 15, BlockPos.getY(at), BlockPos.getZ(at) & 15, e.getValue());
        }
        // wave-torn leaves ignite in light where wave weak and fire not blown out
        if (g.fires()) {
            for (int k = 0; k < c.leaves.size(); k++) {
                m.set(c.leaves.getLong(k));
                if (d.psi(Vec3.atCenterOf(m)) < 3 && g.lit(m) >= 10) s.fire(m.getX(), m.getY(), m.getZ(), 1.0, 1.0);
            }
        }
        double[] heat = land(g, s, c.ground, c.removed, c.wood, false);
        Blast own = c.blast();
        if (own != null) for (int i = 0; i < own.trees().size(); i++) lay(s, own.trees().get(i), own.treeLogs().get(i));
        // after everything deposited on ground (rubble, trunks)
        for (int column = 0; column < 256; column++) if (c.ground[column] != Integer.MIN_VALUE) snowy(s, column & 15, c.ground[column], column >> 4);
        RuinPlan plan = s.finish(g.ctx(), c.fluidTicks.toLongArray(), biomes(g.ctx().noise, c.ground, heat, shot.x << 4, shot.z << 4));
        // flash scorch on surviving faces: pre-wave world, applied with ruins
        if (plan != RuinPlan.EMPTY) plan.flash(FlashFaces.chars(g, s));
        return plan;
    }

    /**
     * Chunk ground trace without ruins (any thread): light/pressure like ruin plan ({@link #finish}) where
     * wave broke nothing, using one chunk snapshot, no window/neighbors. For Distant Horizons LODs beyond heavy
     * zone ({@link FarLods}): wave breaks glass there, but distant view shows ground, burned turf, scorch, scorched biomes. Chunk
     * loaded later gets full ruins, same ground if wave collapsed nothing.
     */
    static RuinPlan scorch(RuinContext.Grid g) {
        Sections s = new Sections(g.at(0, 0), g.view());
        return s.finish(g.ctx(), new long[0], groundPass(g, s, false));
    }

    /**
     * Chunk flash layer (any thread): light-only changes at flash, before wave ({@link FlashScorch}).
     * Like {@link #scorch} without pressure: no turf stripping (wave), all light-ignited fires (min(1, q/20), without
     * wave-blown fraction); fires wave will later extinguish (half from 2 psi, same hash roll as ruins)
     * listed in plan ({@link RuinPlan#blown}), extinguished by ruin replacement. Face scorch on blocks unchanged by light, using
     * chunk/in-memory-neighbor window (absent neighbor means exposed edge face). Where wave breaks nothing, ruins from
     * pre-flash world change same positions to same states (GameTest {@code flashScorchesBeforeTheWaveAndRuinsMatch}): wave
     * replacement adds only wave changes beyond layer.
     */
    static RuinPlan flash(RuinContext.Grid g) {
        Sections s = new Sections(g.at(0, 0), g.view());
        RuinPlan plan = s.finish(g.ctx(), new long[0], groundPass(g, s, true));
        if (plan != RuinPlan.EMPTY) plan.flash(FlashFaces.chars(g, s));
        return plan;
    }

    /** Chunk ground without ruins ({@link #scorch}, {@link #flash}): light, pressure (except {@code flash}), snow; biome tiers. */
    @Nullable
    private static ScorchedBiomes.Cells groundPass(RuinContext.Grid g, Sections s, boolean flash) {
        ChunkShot shot = s.shot;
        // bottom like collapse (Collapse.yb): 16 below lowest support (water supports); no ground under deep
        // water; collapse window includes neighbors, here only one chunk
        int low = Integer.MAX_VALUE;
        for (int column = 0; column < 256; column++) if (shot.solid[column] >= s.minY) low = Math.min(low, shot.solid[column]);
        int bottom = low == Integer.MAX_VALUE ? s.minY : Math.max(s.minY, low - 16);
        int[] ground = new int[256];
        for (int column = 0; column < 256; column++) ground[column] = groundOf(s, column & 15, column >> 4, bottom);
        double[] heat = land(g, s, ground, null, null, flash);
        for (int column = 0; column < 256; column++) if (ground[column] != Integer.MIN_VALUE) snowy(s, column & 15, ground[column], column >> 4);
        return biomes(g.ctx().noise, ground, heat, shot.x << 4, shot.z << 4);
    }

    /**
     * Ground column without ruins, like {@link Collapse} with no breakage: highest supporting block, not log, no lower than
     * {@code bottom} ({@code Integer.MIN_VALUE} absent). All-air sections skipped entirely.
     */
    private static int groundOf(Sections s, int lx, int lz, int bottom) {
        for (int i = s.sections - 1; i >= 0 && s.minY + (i << 4) + 15 >= bottom; i--) {
            if (!s.shot.mayHave(i, st -> !st.isAir())) continue;
            for (int y = s.minY + (i << 4) + 15; y >= Math.max(bottom, s.minY + (i << 4)); y--) {
                Blast.Props p = s.props(lx, y, lz);
                if (p.bearing() && !p.log()) return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Ground by columns (any thread): rubble from collapsed structures, stripped turf, light, scorch, burned grass,
     * burned plants, fires, trinitite and boiled water in fireball. {@code removed}, {@code wood} count
     * removed blocks above column and wooden subset (null none removed: {@link #scorch}). {@code flash} is light only, without
     * pressure ({@link #flash}): no turf stripping, all light-ignited fires, future wave-blown ones marked.
     *
     * @return slope-adjusted column ground light for biome tiers
     */
    private static double[] land(RuinContext.Grid g, Sections s, int[] groundOf, @Nullable int[] removed, @Nullable int[] wood, boolean flash) {
        Detonation d = g.ctx().d;
        WorldNoise noise = g.ctx().noise;
        boolean blockDamage = g.blockDamage() && !flash, fires = g.fires();
        int x0 = s.chunkX << 4, z0 = s.chunkZ << 4;
        double[] heat = new double[256];
        for (int column = 0; column < 256; column++) {
            int ground = groundOf[column];
            if (ground == Integer.MIN_VALUE) continue;
            int lx = column & 15, lz = column >> 4, x = x0 + lx, z = z0 + lz;
            double groundPsi = d.psi(new Vec3(x + 0.5, ground + 1, z + 0.5));
            Vec3 at = new Vec3(x + 0.5, ground + 0.5, z + 0.5);
            boolean inFireball = d.surface() && Math.hypot(at.x - d.burst().x, at.z - d.burst().z) < d.fireballRadius() * 0.8
                    && at.y > d.groundY() - d.fireballRadius();
            double lit = g.lit(new BlockPos(x, ground, z));
            double q = lit > 0 ? lit * incidence(groundOf, lx, lz, ground, x, z, d.burst()) * edge(x, z, d.burst(), g.ctx().ruinRadius) : 0;
            heat[column] = inFireball ? lit : q;
            // collapse leaves rubble at any pressure (house falls even below 5 psi); nothing in fireball
            if (blockDamage && !inFireball && removed != null && removed[column] >= 3) rubble(s, lx, ground, lz, x, z, removed[column], wood[column]);
            if (inFireball) {
                if (blockDamage) strip(s, lx, ground, lz, x, z);
                if (lit >= ThermalModel.IGNITE) fireball(s, lx, ground, lz);
                continue;
            }
            boolean stripped = ground(s, noise, lx, ground, lz, x, z, q, groundPsi, blockDamage);
            plants(s, noise, lx, ground, lz, x, z, q, stripped);
            if (fires && q >= ThermalModel.IGNITE) fire(s, lx, ground, lz, x, z, q, groundPsi, flash);
        }
        return heat;
    }

    /**
     * Scorched-biome tiers for chunk 4×4 cells ({@link ScorchedBiomes}): average ground light and world noise;
     * cells change from cell ground down {@link ScorchedBiomes#BELOW}. Cell ground is lower column quartile:
     * below street grass among houses, but single-column well/hole does not pull cell down. Null if none.
     */
    @Nullable
    private static ScorchedBiomes.Cells biomes(WorldNoise noise, int[] ground, double[] heat, int x0, int z0) {
        byte[] levels = new byte[16];
        int[] from = new int[16], tops = new int[16];
        boolean any = false;
        for (int qz = 0; qz < 4; qz++) {
            for (int qx = 0; qx < 4; qx++) {
                double sum = 0;
                int n = 0;
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int column = (qz << 2 | dz) << 4 | qx << 2 | dx;
                        if (ground[column] == Integer.MIN_VALUE) continue;
                        sum += heat[column];
                        tops[n++] = ground[column];
                    }
                }
                if (n == 0 || !Fragility.possible(sum / n, ScorchedBiomes.MEDIAN[0], ScorchedBiomes.BETA)) continue;
                int level = ScorchedBiomes.level(sum / n, noise.at(x0 + (qx << 2) + 2, z0 + (qz << 2) + 2, LAYER_BIOME));
                if (level == 0) continue;
                java.util.Arrays.sort(tops, 0, n);
                levels[qz << 2 | qx] = (byte) level;
                from[qz << 2 | qx] = tops[n / 4] - ScorchedBiomes.BELOW;
                any = true;
            }
        }
        return any ? new ScorchedBiomes.Cells(levels, from) : null;
    }

    /**
     * Ground-light factor relative to same flat ground: flux through area is cosine between
     * ray and normal (blast-facing slope burns more, opposite less, steeper than ray shadowed with penumbra of
     * {@link #LOW_SUN} rad: fireball not point); burn thresholds defined for flat ground. Normal from post-ruin chunk ground;
     * height difference capped at block per block (wall, rubble, trunk not slopes). Flat ground exactly 1 at any
     * burst height.
     */
    static double incidence(int[] ground, int lx, int lz, int h, int x, int z, Vec3 burst) {
        double gx = slope(ground, lx, lz, h, 1, 0), gz = slope(ground, lx, lz, h, 0, 1);
        double rx = burst.x - (x + 0.5), ry = burst.y - (h + 1), rz = burst.z - (z + 0.5);
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < 1e-6) return 1;
        rx /= len;
        ry /= len;
        rz /= len;
        double cos = (-gx * rx + ry - gz * rz) / Math.sqrt(gx * gx + 1 + gz * gz);
        return Math.min(MAX_INCIDENCE, Math.max(0, cos + LOW_SUN) / Math.max(ry + LOW_SUN, LOW_SUN));
    }

    /** Light trace at ruin edge: 1 to {@code 1 − EDGE_FADE} radius, then smoothly to 0 at edge. */
    static double edge(int x, int z, Vec3 burst, double ruinRadius) {
        double f = Mth.clamp((1 - Math.hypot(x + 0.5 - burst.x, z + 0.5 - burst.z) / ruinRadius) / EDGE_FADE, 0, 1);
        return f * f * (3 - 2 * f);
    }

    /**
     * Light ground-trace reach, horizontal blocks from epicenter ({@link #scorch}): beyond, even blast-facing slope
     * ({@link #MAX_INCIDENCE}) reaches neither plant nor lowest biome tier even in extreme curve tail
     * ({@link Fragility#possible}); capped at ruin radius.
     */
    static double scorchReach(Detonation d) {
        double lo = 0, hi = d.ruinRadius();
        for (int i = 0; i < 40; i++) {
            double r = (lo + hi) / 2;
            double q = d.fluence(new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z)) * MAX_INCIDENCE;
            if (Fragility.possible(q, PLANT_Q, PLANT_BETA) || Fragility.possible(q, ScorchedBiomes.MEDIAN[0], ScorchedBiomes.BETA)) lo = r;
            else hi = r;
        }
        return lo;
    }

    /**
     * Ground slope along axis ({@code dx, dz}): mean difference to chunk columns up to {@link #SLOPE_REACH} each side,
     * each capped at block per block.
     */
    private static double slope(int[] ground, int lx, int lz, int h, int dx, int dz) {
        double sum = 0;
        int n = 0;
        for (int k = 1; k <= SLOPE_REACH; k++) {
            int ax = lx + dx * k, az = lz + dz * k, bx = lx - dx * k, bz = lz - dz * k;
            if (ax < 16 && az < 16 && ground[az << 4 | ax] != Integer.MIN_VALUE) {
                sum += Mth.clamp(ground[az << 4 | ax] - h, -k, k) / (double) k;
                n++;
            }
            if (bx >= 0 && bz >= 0 && ground[bz << 4 | bx] != Integer.MIN_VALUE) {
                sum += Mth.clamp(h - ground[bz << 4 | bx], -k, k) / (double) k;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Rubble from collapsed structure: height from amount collapsed, material from original material. */
    private static void rubble(Sections s, int lx, int ground, int lz, int x, int z, int removed, int wood) {
        int height = Math.min(4, Math.max(1, Math.round(removed * 0.12f)));
        BlockState[] kind = wood * 2 > removed ? WOOD_RUBBLE : STONE_RUBBLE;
        for (int i = 1; i <= height; i++) {
            int y = ground + i;
            if (y >= s.maxY || !s.get(lx, y, lz).isAir()) break;
            s.set(lx, y, lz, kind[(int) (hash(x, y, z, 17) * kind.length)]);
        }
    }

    /** Dynamic pressure stripped turf/grass: bare soil. */
    private static void strip(Sections s, int lx, int ground, int lz, int x, int z) {
        Blast.Props p = s.props(lx, ground, lz);
        if (p.is(Blast.Props.GRASS) || p.is(Blast.Props.MOSS)) {
            s.set(lx, ground, lz, hash(x, ground, z, 3) < 1 / 3.0 ? DIRT : COARSE_DIRT);
        }
    }

    /** Inside fireball at ground: sand melts, water boils. */
    private static void fireball(Sections s, int lx, int ground, int lz) {
        if (s.props(lx, ground, lz).is(Blast.Props.SAND)) s.set(lx, ground, lz, ModBlocks.TRINITITE.get().defaultBlockState());
        for (int i = 1; i <= 3 && ground + i < s.maxY; i++) {
            if (!s.props(lx, ground + i, lz).is(Blast.Props.SOURCE)) break;
            s.set(lx, ground + i, lz, AIR);
        }
    }

    /**
     * Top ground block: pressure strips turf (bare soil even in shadow), light burns grass to soil, strongest
     * light leaves scorch patches; snow melts. Each uses damage curve with own world noise.
     *
     * @return turf stripped by pressure (and plants above with it)
     */
    private static boolean ground(Sections s, WorldNoise noise, int lx, int ground, int lz, int x, int z, double q, double psi, boolean blockDamage) {
        Blast.Props p = s.props(lx, ground, lz);
        if (p.is(Blast.Props.SNOW)) {
            if (struck(q, BURN_Q, BURN_BETA, noise, x, z, LAYER_BURN, 5)) s.set(lx, ground, lz, WATER);
            return false;
        }
        if (!p.is(Blast.Props.GRASS) && !p.is(Blast.Props.MOSS)) return false;
        if (blockDamage && struck(psi, STRIP_PSI, STRIP_BETA, noise, x, z, LAYER_STRIP, 3)) {
            strip(s, lx, ground, lz, x, z);
            return true;
        }
        // scorch only where turf already burned: patches inside burned ground, not speckles in green grass
        if (struck(q, BURN_Q, BURN_BETA, noise, x, z, LAYER_BURN, 5)) {
            s.set(lx, ground, lz, struck(q, CHAR_Q, CHAR_BETA, noise, x, z, LAYER_CHAR, 7) ? PODZOL
                    : hash(x, ground, z, 5) < 0.5 ? COARSE_DIRT : DIRT);
        }
        return false;
    }

    /**
     * Above ground: grass, flowers, bushes, crops ({@code nuke_plants}) burn (some leave dead bush) or leave with
     * stripped turf, including everything above (tall grass top, cane); snow layers melt. Randomness mostly position
     * grain rather than patches: grass burns in tufts.
     */
    private static void plants(Sections s, WorldNoise noise, int lx, int ground, int lz, int x, int z, double q, boolean stripped) {
        int y = ground + 1;
        if (y >= s.maxY) return;
        Blast.Props p = s.props(lx, y, lz);
        boolean plant = p.is(Blast.Props.PLANT), snow = p.is(Blast.Props.SNOW_LAYER);
        if (!plant && !snow) return;
        if (!stripped && !(Fragility.possible(q, PLANT_Q, PLANT_BETA)
                && Fragility.hit(q, PLANT_Q, PLANT_BETA, 0.5 * noise.at(x + 0.5, z + 0.5, LAYER_PLANT) + 0.87 * grain(x, y, z, 41)))) return;
        if (snow) {
            s.set(lx, y, lz, AIR);
            return;
        }
        // double grass/tall flowers, cane: top removed with bottom
        for (int up = y + 1; up < s.maxY && s.props(lx, up, lz).is(Blast.Props.PLANT); up++) s.set(lx, up, lz, AIR);
        Blast.Props under = s.props(lx, ground, lz);
        boolean bush = !stripped && hash(x, y, z, 43) < DEAD_BUSH && (under.is(Blast.Props.DIRT) || under.is(Blast.Props.SAND));
        s.set(lx, y, lz, bush ? DEAD_BUSH_STATE : AIR);
    }

    /**
     * Ground “snowy” ({@code snowy}: white turf sides) only with snow above, like {@code SnowyDirtBlock}:
     * snow layer wave-blown, flash-melted or covered by trunk means green sides; burned turf beneath surviving snow
     * white again. Only where plan changed ground or position above: rest of world unchanged.
     */
    private static void snowy(Sections s, int lx, int ground, int lz) {
        if (ground + 1 >= s.maxY || !s.touched(lx, ground, lz) && !s.touched(lx, ground + 1, lz)) return;
        BlockState under = s.get(lx, ground, lz);
        if (!under.hasProperty(BlockStateProperties.SNOWY)) return;
        boolean now = s.props(lx, ground + 1, lz).is(Blast.Props.SNOWY_TOP);
        if (under.getValue(BlockStateProperties.SNOWY) == now) return;
        for (BlockState st : SNOWY_GROUND) if (under.getBlock() == st.getBlock()) s.set(lx, ground, lz, st.setValue(BlockStateProperties.SNOWY, now));
    }

    /**
     * Ground fires where light ignited fuel (chance rises with light), not all extinguished by wave (from 2 psi
     * {@link #BLOWN_KEEP} remain). {@code flash} means flash layer: candidate by light probability, fraction surviving
     * wave separate.
     */
    private static void fire(Sections s, int lx, int ground, int lz, int x, int z, double q, double psi, boolean flash) {
        if (ground + 1 >= s.maxY) return;
        double lit = Math.min(1, q / ThermalModel.IGNITE_ALL), keep = psi >= 2 ? BLOWN_KEEP : 1;
        Blast.Props now = s.props(lx, ground, lz);
        if (s.get(lx, ground + 1, lz).isAir() && (now.is(Blast.Props.FLAMMABLE) || now.is(Blast.Props.DIRT))) {
            if (flash) s.fire(x, ground + 1, z, lit, keep);
            else s.fire(x, ground + 1, z, lit * keep, 1);
        }
    }

    /** Fire fraction surviving wave where ground pressure at least 2 psi. */
    static final double BLOWN_KEEP = 0.5;

    /** Toppled trunk: horizontal logs along surface away from epicenter. */
    private static void lay(Sections s, long[] tree, BlockState log) {
        BlockPos base = BlockPos.of(tree[0]);
        Direction dir = Direction.from3DDataValue((int) tree[2]);
        BlockState lying = log.hasProperty(BlockStateProperties.AXIS) ? log.setValue(BlockStateProperties.AXIS, dir.getAxis()) : log;
        for (int i = 0; i < tree[1]; i++) {
            BlockPos p = base.relative(dir, i);
            if (p.getX() >> 4 != s.chunkX || p.getZ() >> 4 != s.chunkZ) {
                // farther along trunk, neighboring chunk: logs placed through world during replacement
                s.outside.add(p.asLong());
                s.outsideState.add(lying);
                continue;
            }
            int lx = p.getX() & 15, lz = p.getZ() & 15;
            // first impassable block downward from trunk-base level (+8 for uphill slope)
            int y = Math.min(s.maxY - 1, base.getY() + 8);
            while (y > s.minY && s.props(lx, y, lz).is(Blast.Props.REPLACEABLE)) y--;
            if (y + 1 >= s.maxY || !s.props(lx, y + 1, lz).is(Blast.Props.REPLACEABLE)) break;
            s.set(lx, y + 1, lz, lying);
        }
    }

    /**
     * Plan chunk: original snapshot blocks, overlaid changes (position → state). Only delta enters plan
     * ({@link RuinPlan}); properties from window table, no block-code calls.
     */
    static final class Sections {
        final ChunkShot shot;
        final Blast.PropsView view;
        final int minY, maxY, sections, chunkX, chunkZ;
        /** Changes: section index {@code << 12} | position in section. */
        private final Int2ObjectOpenHashMap<BlockState> changes = new Int2ObjectOpenHashMap<>();
        /**
         * Fire candidates: position, probability and wave-surviving fraction (flash layer; ruins use 1,
         * their probability already post-wave).
         */
        final LongArrayList fires = new LongArrayList();
        final it.unimi.dsi.fastutil.doubles.DoubleArrayList fireChance = new it.unimi.dsi.fastutil.doubles.DoubleArrayList(),
                fireKeep = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        /** Toppled-tree logs in neighboring chunks: position (height from surface at replacement) and state. */
        final LongArrayList outside = new LongArrayList();
        final List<BlockState> outsideState = new ArrayList<>();
        /** Changed positions: section index << 12 | position in section (with duplicates). */
        final IntArrayList changed = new IntArrayList();

        Sections(ChunkShot shot, Blast.PropsView view) {
            this.shot = shot;
            this.view = view;
            this.minY = shot.minY;
            this.maxY = shot.maxY();
            this.sections = shot.sections;
            this.chunkX = shot.x;
            this.chunkZ = shot.z;
        }

        private int key(int lx, int y, int lz) {
            return ((y - minY) >> 4) << RuinPlan.SECTION_SHIFT | (y & 15) << 8 | lz << 4 | lx;
        }

        BlockState get(int lx, int y, int lz) {
            if (y < minY || y >= maxY) return AIR;
            BlockState now = changes.get(key(lx, y, lz));
            return now != null ? now : shot.raw(lx, y, lz);
        }

        Blast.Props props(int lx, int y, int lz) {
            return view.get(get(lx, y, lz));
        }

        /** Whether plan changed position. */
        boolean touched(int lx, int y, int lz) {
            return y >= minY && y < maxY && changes.containsKey(key(lx, y, lz));
        }

        void set(int lx, int y, int lz, BlockState state) {
            if (y < minY || y >= maxY) return;
            int k = key(lx, y, lz);
            changes.put(k, state);
            changed.add(k);
        }

        void fire(int x, int y, int z, double chance, double keep) {
            if (chance <= 0) return;
            fires.add(BlockPos.asLong(x, y, z));
            fireChance.add(chance);
            fireKeep.add(keep);
        }

        /** State at world coordinates after plan: air outside chunk (fire chooses shape from in-chunk neighbors). */
        private BlockState at(int x, int y, int z) {
            if (x >> 4 != chunkX || z >> 4 != chunkZ) return AIR;
            return get(x & 15, y, z & 15);
        }

        /**
         * Fire at plan position if survivable (like {@code FireBlock.canSurvive}); null if not. Fire type
         * like {@code BaseFireBlock.getState}: blue on blue-fire base, normal otherwise; faces toward flammable neighbors.
         */
        @Nullable
        private BlockState fireAt(int x, int y, int z) {
            if (!at(x, y, z).isAir()) return null;
            Blast.Props below = view.get(at(x, y - 1, z));
            BlockState fire;
            if (below.is(Blast.Props.SOUL_BASE)) {
                fire = SOUL_FIRE;
            } else if (!below.is(Blast.Props.FLAMMABLE) && !below.is(Blast.Props.STURDY_UP)) {
                fire = FIRE;
                for (int k = 0; k < FIRE_FACES.length; k++) {
                    Direction dir = FIRE_FACE_DIRS[k];
                    fire = fire.setValue(FIRE_FACES[k], view.get(at(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ())).is(Blast.Props.FLAMMABLE));
                }
            } else {
                fire = FIRE;
            }
            if (below.is(Blast.Props.STURDY_UP)) return fire;
            if (fire.getBlock() != Blocks.FIRE) return null;
            for (Direction dir : DIRECTIONS) {
                if (view.get(at(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ())).is(Blast.Props.FLAMMABLE)) return fire;
            }
            return null;
        }

        RuinPlan finish(RuinContext ctx, long[] fluidTicks, @Nullable ScorchedBiomes.Cells biomes) {
            // unique positions by section
            int[] cells = changed.toIntArray();
            java.util.Arrays.sort(cells);
            int n = 0;
            for (int k = 0; k < cells.length; k++) if (k == 0 || cells[k] != cells[k - 1]) cells[n++] = cells[k];
            // fires: roll from position hash (plan/detonation match), position where fire survives on ruins
            IntArrayList fireCells = new IntArrayList(), blown = new IntArrayList();
            List<BlockState> fireStates = new ArrayList<>();
            for (int k = 0; k < fires.size(); k++) {
                long f = fires.getLong(k);
                int fx = BlockPos.getX(f), fy = BlockPos.getY(f), fz = BlockPos.getZ(f);
                double roll = hash(fx, fy, fz, 29), chance = fireChance.getDouble(k);
                if (roll >= chance) continue;
                int i = (fy - minY) >> 4;
                if (i < 0 || i >= sections) continue;
                BlockState fire = fireAt(fx, fy, fz);
                if (fire == null) continue;
                int cell = i << RuinPlan.SECTION_SHIFT | (fy & 15) << 8 | (fz & 15) << 4 | fx & 15;
                fireCells.add(cell);
                fireStates.add(fire);
                // same roll as ruins (their probability chance · keep): wave extinguishes what ruins would not ignite
                if (roll >= chance * fireKeep.getDouble(k)) blown.add(cell);
            }
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            if (n == 0 && fireCells.isEmpty() && outside.isEmpty() && fluidTicks.length == 0) return RuinPlan.nothing(ctx, biomes);
            // column tops after ruins: removed/new blocks below change light beyond sky-source change
            int[] topChanged = new int[256];
            java.util.Arrays.fill(topChanged, Integer.MIN_VALUE);
            for (int k = 0; k < n; k++) {
                int c = cells[k], column = c & 0xFF;
                topChanged[column] = Math.max(topChanged[column], y(c));
            }
            int[] newTop = new int[256];
            for (int column = 0; column < 256; column++) {
                if (topChanged[column] == Integer.MIN_VALUE) continue;
                int lx = column & 15, lz = column >> 4;
                int y = Math.max(topChanged[column], shot.surface[column]);
                while (y >= minY && get(lx, y, lz).isAir()) y--;
                newTop[column] = y;
            }
            int[] heights = heights(topChanged);
            LongArrayList lightAt = new LongArrayList();
            Object2IntOpenHashMap<BlockState> palette = new Object2IntOpenHashMap<>();
            List<BlockState> states = new ArrayList<>();
            BlockState[] olds = new BlockState[n];
            long[] hashes = new long[sections];
            int kept = 0;
            for (int k = 0; k < n; k++) {
                int c = cells[k];
                int i = c >>> RuinPlan.SECTION_SHIFT, lx = c & 15, lz = (c >> 4) & 15, y = y(c), column = c & 0xFF;
                BlockState was = shot.raw(lx, y, lz), now = changes.get(c);
                if (now == null || was == now) continue;
                int idx = palette.computeIfAbsent(now, s -> {
                    states.add((BlockState) s);
                    return states.size() - 1;
                });
                if (idx >= RuinPlan.MAX_STATES) throw new IllegalStateException("Ruins of chunk " + pos + ": more than " + RuinPlan.MAX_STATES + " states");
                Blast.Props pw = view.get(was), pn = view.get(now);
                hashes[i] = RuinPlan.mixNormal(hashes[i], pw.normal());
                boolean covered = y < newTop[column];
                boolean light = y == topChanged[column] || pw.emission() > 0 || pn.emission() > 0
                        || covered && (pw.lightBlock() != pn.lightBlock() || pw.is(Blast.Props.SHAPE_LIGHT) || pn.is(Blast.Props.SHAPE_LIGHT));
                boolean slow = pw.is(Blast.Props.SLOW);
                if (light && !slow) lightAt.add(BlockPos.asLong((chunkX << 4) + lx, y, (chunkZ << 4) + lz));
                olds[kept] = was;
                cells[kept++] = c | idx << RuinPlan.STATE_SHIFT | (light ? RuinPlan.LIGHT : 0) | (slow ? RuinPlan.SLOW : 0);
            }
            int[] fireOut = new int[fireCells.size()];
            for (int k = 0; k < fireOut.length; k++) {
                BlockState fire = fireStates.get(k);
                int idx = palette.computeIfAbsent(fire, s -> {
                    states.add((BlockState) s);
                    return states.size() - 1;
                });
                if (idx >= RuinPlan.MAX_STATES) throw new IllegalStateException("Ruins of chunk " + pos + ": more than " + RuinPlan.MAX_STATES + " states");
                fireOut[k] = fireCells.getInt(k) | idx << RuinPlan.STATE_SHIFT;
            }
            if (kept == 0 && fireOut.length == 0 && outside.isEmpty() && fluidTicks.length == 0) return RuinPlan.nothing(ctx, biomes);
            RuinPlan plan = new RuinPlan(java.util.Arrays.copyOf(cells, kept), hashes, states.toArray(new BlockState[0]), java.util.Arrays.copyOf(olds, kept), fireOut,
                    heights, lightAt.toLongArray(), shot.prints.clone(), shot.normalPrints(view),
                    outside.isEmpty() ? null : outside, outside.isEmpty() ? null : outsideState, ctx, fluidTicks, biomes);
            if (!blown.isEmpty()) plan.blown(blown.toIntArray());
            return plan;
        }

        /**
         * Heightmaps and lower sky sources of changed columns after ruins (for {@link RuinPlan}): same rule as
         * {@code Heightmap.update} and {@code ChunkSkyLightSources}, using snapshot-overlaid changes once per column.
         * Above highest changed block column unchanged, so search starts there downward. “Before” values from snapshot (for
         * disk snapshot sky source computed here from blocks).
         */
        private int[] heights(int[] topChanged) {
            IntArrayList out = new IntArrayList();
            int skyMin = minY - 1;
            for (int column = 0; column < 256; column++) {
                int top = topChanged[column];
                if (top == Integer.MIN_VALUE) continue;
                int lx = column & 15, lz = column >> 4;
                for (int t = 0; t < RuinPlan.HEIGHTMAP_TYPES.length; t++) {
                    int old = shot.heights[t * 256 + column];
                    int y = Math.max(old, top + 1) - 1;
                    while (y >= minY && !props(lx, y, lz).opaque(t)) y--;
                    if (y + 1 != old) triple(out, column << 3 | t, old, y + 1);
                }
                // lower sky source: top of first occluded face from above; above top + 1 faces unchanged
                int old = shot.sky != null ? shot.sky[column] : oldSky(lx, lz);
                if (old > top + 1) continue;
                int source = skyMin;
                BlockState upper = get(lx, top + 1, lz);
                for (int y = top; y >= skyMin; y--) {
                    BlockState lower = get(lx, y, lz);
                    if (edgeOccluded(upper, lower)) {
                        source = y + 1;
                        break;
                    }
                    upper = lower;
                }
                if (source != old) triple(out, column << 3 | RuinPlan.SKY, old, source);
            }
            return out.toIntArray();
        }

        /** Column lower sky source from original snapshot blocks ({@code ChunkSkyLightSources.findLowestSourceY}). */
        private int oldSky(int lx, int lz) {
            BlockState upper = AIR;
            for (int y = maxY - 1; y >= minY; y--) {
                BlockState lower = shot.raw(lx, y, lz);
                if (edgeOccluded(upper, lower)) return y + 1;
                upper = lower;
            }
            return minY - 1;
        }

        private static void triple(IntArrayList out, int key, int old, int now) {
            out.add(key);
            out.add(old);
            out.add(now);
        }

        /** Like {@code ChunkSkyLightSources.isEdgeOccluded}: skylight blocked downward through face between blocks. */
        private boolean edgeOccluded(BlockState upper, BlockState lower) {
            Blast.Props below = view.get(lower);
            if (below.lightBlock() != 0) return true;
            return Shapes.faceShapeOccludes(view.get(upper).occludeDown(), below.occludeUp());
        }

        private int y(int c) {
            return minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15);
        }
    }
}
