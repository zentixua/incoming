package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.compat.SubLevels;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * The ruin support law ({@link Collapse}: what lost support falls) for a conventional strike. The strike's explosions
 * removed some blocks; every chunk within {@link Collapse#C} of a removal is solved in its own window, exactly as a
 * nuclear chunk is, over the world as it stood (live snapshots with the removals put back,
 * {@link RuinWindow#restoring}) and with the removals as the window chunks' fractures. Result: positions the law drops
 * (beyond the strike's own removals), their states, and fluid beside them.
 * <p>
 * Server thread takes the snapshots in portions ({@link #snapshot}); the solve runs on the game's background workers
 * ({@code Util.backgroundExecutor}). Chunks not ready are missing from the window: solid mass that supports, as at a nuclear
 * window edge; no chunk is loaded. Sable plot-grid chunks are missing too (craft blocks are not the world).
 */
public final class StrikeSupport {
    /** Chunks within this distance of a removal can lose support: the exact band of a chunk's solve ({@link Collapse}). */
    public static final int REACH = Collapse.C;
    /**
     * Real material a fallen wall or floor block stands for, m³ per block: the share that lands as rubble
     * ({@link Debris#SOLID_SHARE}, est.: a real wall or slab is 0.15–0.3 m of the block's metre); the collapse dust is
     * raised from it.
     */
    public static final double MATERIAL = Debris.SOLID_SHARE;

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<BlockState> removed;
    /** Chunks to solve ({@link ChunkPos#asLong}). */
    private final long[] solve;
    /** Chunks to snapshot (solve chunks and their neighbors) and their snapshots, filled by {@link #snapshot}. */
    private final long[] needed;
    private final Long2ObjectOpenHashMap<ChunkShot> shots = new Long2ObjectOpenHashMap<>();
    private int taken;
    @Nullable
    private Future<Result> solving;

    /**
     * @param removedAt  positions the strike removed ({@link BlockPos#asLong}); a position removed twice keeps its first state
     * @param removedWas states before removal, parallel
     */
    public StrikeSupport(ServerLevel level, LongArrayList removedAt, List<BlockState> removedWas) {
        this.level = level;
        this.removed = new Long2ObjectOpenHashMap<>(removedAt.size());
        LongOpenHashSet chunks = new LongOpenHashSet();
        for (int i = 0; i < removedAt.size(); i++) {
            long at = removedAt.getLong(i);
            if (removed.putIfAbsent(at, removedWas.get(i)) != null) continue;
            int x = BlockPos.getX(at), z = BlockPos.getZ(at);
            for (int cx = (x - REACH) >> 4; cx <= (x + REACH) >> 4; cx++) {
                for (int cz = (z - REACH) >> 4; cz <= (z + REACH) >> 4; cz++) chunks.add(ChunkPos.asLong(cx, cz));
            }
        }
        this.solve = chunks.toLongArray();
        java.util.Arrays.sort(solve);
        LongOpenHashSet near = new LongOpenHashSet();
        for (long c : solve) {
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) near.add(ChunkPos.asLong(ChunkPos.getX(c) + dx, ChunkPos.getZ(c) + dz));
        }
        this.needed = near.toLongArray();
        java.util.Arrays.sort(needed);
    }

    /** Positions the strike removed. */
    public int removedCount() {
        return removed.size();
    }

    /** Chunks solved. */
    public int chunks() {
        return solve.length;
    }

    /**
     * Server thread: snapshot up to {@code max} more chunks; once all are taken, start the solve.
     *
     * @return snapshots taken in this call
     */
    public int snapshot(int max) {
        int n = 0;
        while (taken < needed.length && n < max) {
            long c = needed[taken++];
            ChunkPos pos = new ChunkPos(c);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null || SubLevels.inPlotGrid(level, pos)) continue;
            shots.put(c, ChunkShot.take(chunk, null, null, false));
            n++;
        }
        if (taken == needed.length && solving == null) start();
        return n;
    }

    /** All snapshots taken and the solve submitted. */
    public boolean started() {
        return solving != null;
    }

    /** Solve finished (or failed). */
    public boolean done() {
        return solving != null && solving.isDone();
    }

    /** Solve result (after {@link #done}); failure propagates. */
    public Result result() throws java.util.concurrent.ExecutionException, InterruptedException {
        if (solving == null) throw new IllegalStateException("collapse not started");
        return solving.get();
    }

    /** Abandon the solve (strike cancelled, server stopping). */
    public void cancel() {
        if (solving != null) solving.cancel(false);
    }

    private void start() {
        for (BlockState st : removed.values()) Blast.ensure(st);
        Blast.PropsView view = Blast.publish();
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        Long2ObjectOpenHashMap<ChunkShot> shots = this.shots;
        Long2ObjectOpenHashMap<BlockState> removed = this.removed;
        long[] solve = this.solve;
        // a few chunk windows, milliseconds: the game's background workers, not behind a nuke's ruin queue
        solving = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return solve(solve, shots, removed, view, minY, maxY);
            } finally {
                // this worker's traversal buffers (a tall window: megabytes) are not kept between strikes
                Collapse.releaseBuffers();
            }
        }, net.minecraft.Util.backgroundExecutor());
    }

    /** Any thread: the law in each chunk's window; changes beyond the strike's own removals. */
    static Result solve(long[] solve, Long2ObjectMap<ChunkShot> shots, Long2ObjectMap<BlockState> removed, Blast.PropsView view, int minY, int maxY) {
        // removals as fractures of their chunks (Blast keys: section, y, z, x; sorted)
        Long2ObjectOpenHashMap<IntArrayList> byChunk = new Long2ObjectOpenHashMap<>();
        for (long at : removed.keySet()) {
            int x = BlockPos.getX(at), y = BlockPos.getY(at), z = BlockPos.getZ(at);
            if (y < minY || y >= maxY) continue;
            byChunk.computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4), k -> new IntArrayList())
                    .add((y - minY) >> 4 << RuinPlan.SECTION_SHIFT | (y & 15) << 8 | (z & 15) << 4 | x & 15);
        }
        LongArrayList at = new LongArrayList();
        List<BlockState> was = new ArrayList<>(), to = new ArrayList<>();
        LongArrayList fluid = new LongArrayList();
        int solved = 0;
        for (long c : solve) {
            ChunkPos centre = new ChunkPos(c);
            if (shots.get(c) == null) continue;
            ChunkShot[] window = new ChunkShot[9];
            for (int k = 0; k < 9; k++) window[k] = shots.get(ChunkPos.asLong(centre.x + k % 3 - 1, centre.z + k / 3 - 1));
            RuinWindow w = RuinWindow.restoring(centre, minY, maxY, window, view, removed);
            Blast[] blasts = new Blast[9];
            boolean any = false;
            for (int k = 0; k < 9; k++) {
                if (window[k] == null) continue;
                IntArrayList keys = byChunk.get(ChunkPos.asLong(centre.x + k % 3 - 1, centre.z + k / 3 - 1));
                if (keys == null) continue;
                int[] sorted = keys.toIntArray();
                java.util.Arrays.sort(sorted);
                blasts[k] = new Blast(w.stamp(), minY, sorted, List.of(), List.of());
                any = true;
            }
            if (!any) continue;
            Collapse col = Collapse.solve(w, blasts);
            solved++;
            for (var e : col.changes.long2ObjectEntrySet()) {
                long p = e.getLongKey();
                if (removed.containsKey(p)) continue;
                int wx = BlockPos.getX(p) - w.x0, wz = BlockPos.getZ(p) - w.z0;
                at.add(p);
                was.add(w.get(wx, BlockPos.getY(p), wz));
                to.add(e.getValue());
            }
            fluid.addAll(col.fluidTicks);
        }
        return new Result(at, was, to, fluid, solved);
    }

    /**
     * Positions the law drops ({@link BlockPos#asLong}), their states as the world stood and what replaces them (air,
     * water beside water), fluid positions to tick, chunks actually solved.
     */
    public record Result(LongArrayList at, List<BlockState> was, List<BlockState> to, LongArrayList fluidTicks, int solved) {}

    // ---------------------------------------------------------------- landing (server thread)

    /** Load-bearing state (anything with collision but leaves and liquids, {@link Blast.Props#bearing}). */
    public static boolean bearing(BlockState st) {
        return Blast.props(st).bearing();
    }

    /**
     * Fallen block lands as rubble (the same share as nuclear collapse, {@link Debris#SOLID_SHARE}, of blocks that make
     * rubble, strength from 1; position hash as there). Containers always land, with their contents.
     */
    public static boolean lands(BlockState st, int x, int y, int z) {
        Blast.Props p = Blast.props(st);
        if (p.air() || p.fluid() || p.leaves() || !p.rubble()) return false;
        return p.is(Blast.Props.CONTAINER) || RuinPlanner.hash(x, y, z, 71) < Debris.SOLID_SHARE;
    }

    /** Container: lands whole in the pile with its contents. */
    public static boolean container(BlockState st) {
        return Blast.props(st).is(Blast.Props.CONTAINER);
    }

    /** What a fallen block lands as: full load-bearing blocks as themselves, others as rubble; a chest half as a lone chest. */
    public static BlockState rubble(BlockState st, int x, int y, int z) {
        Blast.Props p = Blast.props(st);
        if (p.is(Blast.Props.CONTAINER)) return Collapse.landedState(st);
        return Collapse.rubbleOf(st, p, x, y, z);
    }

    /**
     * Horizontal landing offset of a block falling {@code fall} blocks, blocks: the collapse spread around its column
     * ({@link Debris#spread}), the same position grains as the nuclear landing; index 0 x, 1 z.
     */
    public static double[] spread(int x, int y, int z, double fall) {
        double s = Debris.spread(fall);
        return new double[]{RuinPlanner.grain(x, y, z, 61) * s, RuinPlanner.grain(x, y, z, 67) * s};
    }
}
