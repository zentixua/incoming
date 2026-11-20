package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.compat.SubLevels;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The ruin support law ({@link Collapse}: what lost support falls) for a conventional strike. The strike's explosions
 * removed some blocks; every chunk within {@link Collapse#C} of a removal is solved in its own window, exactly as a
 * nuclear chunk is, over the world as it stood (live snapshots with the removals put back,
 * {@link RuinWindow#restoring}) and with the removals as the window chunks' fractures. Result: positions the law drops
 * (beyond the strike's own removals) in the order to drop them, their states, what lands as rubble, fluid beside them.
 * <p>
 * Server thread takes the snapshots in timed portions ({@link #snapshot}); the solve runs on the game's background
 * workers ({@code Util.backgroundExecutor}). At most {@link #MAX_SOLVES} collapses hold snapshots, a solve or an
 * unconsumed result at a time across the server ({@link #tryReserve}): a salvo's collapses wait for a slot instead of
 * piling up. Chunks not ready are missing from the window: solid mass
 * that supports, as at a nuclear window edge; no chunk is loaded. Sable plot-grid chunks are missing too (craft blocks
 * are not the world).
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
    /**
     * Collapses snapshotted or solving at once, server-wide: a strike's snapshots are tens of chunk copies (megabytes for
     * a tall city); a solve takes milliseconds, so a few slots keep a salvo's collapses moving without holding its
     * snapshots in memory (est.).
     */
    static final int MAX_SOLVES = 4;
    private static final AtomicInteger SOLVES = new AtomicInteger();

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<BlockState> removed;
    /** Chunks to solve ({@link ChunkPos#asLong}). */
    private final long[] solve;
    /** Chunks to snapshot (solve chunks and their neighbors) and their snapshots, filled by {@link #snapshot}. */
    private final long[] needed;
    private final Long2ObjectOpenHashMap<ChunkShot> shots = new Long2ObjectOpenHashMap<>();
    private int taken;
    /**
     * Holds one of {@link #MAX_SOLVES} from construction until the result is consumed ({@link #release}) or the collapse
     * is abandoned ({@link #cancel}): snapshots, a queued or running solve and its result all count. Released once.
     */
    private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
    /** Removals the solve used ({@link #start}): those the snapshot still showed empty. */
    private it.unimi.dsi.fastutil.longs.LongSet lost = it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET;
    @Nullable
    private CompletableFuture<Result> solving;

    /**
     * Take one of the server's {@link #MAX_SOLVES} slots for a new collapse: false — wait. The collapse constructed
     * after a true return owns it.
     */
    public static boolean tryReserve() {
        while (true) {
            int n = SOLVES.get();
            if (n >= MAX_SOLVES) return false;
            if (SOLVES.compareAndSet(n, n + 1)) return true;
        }
    }

    /** Free this collapse's slot: its result was consumed, or it is abandoned. Only the first call frees. */
    public void release() {
        if (released.compareAndSet(false, true)) SOLVES.decrementAndGet();
    }

    /**
     * Owns a slot taken by {@link #tryReserve}.
     *
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

    /** Removals the solve took as lost support ({@link BlockPos#asLong}), for the check before the fall. */
    public it.unimi.dsi.fastutil.longs.LongSet lostPositions() {
        return lost;
    }

    /**
     * Server thread: snapshot chunks until {@code budgetNanos} on {@code clock} passes (at
     * least one); once all are taken, start the solve.
     *
     * @return snapshots taken in this call
     */
    public int snapshot(LongSupplier clock, long budgetNanos) {
        long start = clock.getAsLong();
        int n = 0;
        while (taken < needed.length) {
            long c = needed[taken++];
            ChunkPos pos = new ChunkPos(c);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null || SubLevels.inPlotGrid(level, pos)) continue;
            shots.put(c, ChunkShot.take(chunk, null, null, false));
            n++;
            if (clock.getAsLong() - start >= budgetNanos) break;
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

    /**
     * Abandon (strike cancelled, server stopping): drop the snapshots and the slot; a solve already running ends into the
     * void (milliseconds over the limit at most).
     */
    public void cancel() {
        if (solving != null) solving.cancel(false);
        shots.clear();
        release();
    }

    private void start() {
        // a datapack reload between snapshot portions reset the property table: earlier snapshots' states are gone
        // from the table the worker would read; the strike leaves no collapse rather than a half-read one
        boolean fresh = true;
        for (ChunkShot s : shots.values()) fresh &= s.fresh();
        // a removal the snapshot no longer shows empty (a block put back, a support built in the hole since the blast)
        // is no loss of support
        Long2ObjectOpenHashMap<BlockState> lost = new Long2ObjectOpenHashMap<>(removed.size());
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        for (var e : removed.long2ObjectEntrySet()) {
            long at = e.getLongKey();
            int x = BlockPos.getX(at), y = BlockPos.getY(at), z = BlockPos.getZ(at);
            ChunkShot s = shots.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (s != null && y >= minY && y < maxY && Blast.props(s.raw(x & 15, y, z & 15)).bearing()) continue;
            lost.put(at, e.getValue());
            Blast.ensure(e.getValue());
        }
        Blast.PropsView view = Blast.publish();
        Long2ObjectOpenHashMap<ChunkShot> shots = new Long2ObjectOpenHashMap<>(this.shots);
        this.shots.clear();
        long[] solve = this.solve;
        this.lost = lost.keySet();
        if (!fresh || lost.isEmpty()) {
            solving = CompletableFuture.completedFuture(Result.EMPTY);
            return;
        }
        java.util.function.Supplier<Result> task = () -> {
            try {
                return solve(solve, shots, lost, view, minY, maxY);
            } finally {
                // this worker's traversal buffers (a tall window: megabytes) are not kept between strikes
                Collapse.releaseBuffers();
            }
        };
        try {
            solving = CompletableFuture.supplyAsync(task, net.minecraft.Util.backgroundExecutor());
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // workers shut down (server stopping)
            solving = CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Any thread: the law in each chunk's window; changes beyond the strike's own removals, ordered for the fall:
     * non-load-bearing first (attachments, leaves, water), load-bearing bottom-up.
     */
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
                at.add(p);
                was.add(w.get(BlockPos.getX(p) - w.x0, BlockPos.getY(p), BlockPos.getZ(p) - w.z0));
                to.add(e.getValue());
            }
            fluid.addAll(col.fluidTicks);
        }
        int n = at.size();
        Integer[] order = new Integer[n];
        boolean[] bearing = new boolean[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
            bearing[i] = view.get(was.get(i)).bearing();
        }
        java.util.Arrays.sort(order, (a, b) -> bearing[a] != bearing[b] ? (bearing[a] ? 1 : -1)
                : Integer.compare(BlockPos.getY(at.getLong(a)), BlockPos.getY(at.getLong(b))));
        long[] pos = new long[n];
        BlockState[] states = new BlockState[n], into = new BlockState[n], rubble = new BlockState[n];
        double sx = 0, sz = 0;
        int base = Integer.MAX_VALUE, mass = 0;
        Reference2IntOpenHashMap<BlockState> kinds = new Reference2IntOpenHashMap<>();
        for (int k = 0; k < n; k++) {
            int i = order[k];
            long p = at.getLong(i);
            BlockState st = was.get(i);
            pos[k] = p;
            states[k] = st;
            into[k] = to.get(i);
            if (!bearing[i]) continue;
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            Blast.Props props = view.get(st);
            // the same rubble share and hash as the nuclear landing (Collapse#land); not where water takes the place
            if (into[k].isAir() && props.rubble() && RuinPlanner.hash(x, y, z, 71) < Debris.SOLID_SHARE) rubble[k] = Collapse.rubbleOf(st, props, x, y, z);
            mass++;
            sx += x + 0.5;
            sz += z + 0.5;
            base = Math.min(base, y);
            kinds.addTo(st, 1);
        }
        BlockState common = null;
        int most = 0;
        for (var e : kinds.reference2IntEntrySet()) {
            if (e.getIntValue() > most) {
                most = e.getIntValue();
                common = e.getKey();
            }
        }
        return new Result(pos, states, into, rubble, fluid.toLongArray(), solved, mass,
                mass > 0 ? new net.minecraft.world.phys.Vec3(sx / mass, base, sz / mass) : null, common);
    }

    /**
     * Positions the law drops in fall order ({@link BlockPos#asLong}), their states as the world stood, what replaces them
     * (air, water beside water), what lands as rubble (null: turns to dust), fluid positions to tick, chunks solved,
     * load-bearing blocks fallen, the base of their mass (mean x/z, lowest y) and their most common state.
     */
    public record Result(long[] at, BlockState[] was, BlockState[] to, @Nullable BlockState[] rubble, long[] fluidTicks, int solved,
                         int mass, net.minecraft.world.phys.@Nullable Vec3 base, @Nullable BlockState common) {
        static final Result EMPTY = new Result(new long[0], new BlockState[0], new BlockState[0], new BlockState[0], new long[0], 0, 0, null, null);
    }

    /**
     * Horizontal landing offset of a block falling {@code fall} blocks, blocks: the collapse spread around its column
     * ({@link Debris#spread}), the same position grains as the nuclear landing; index 0 x, 1 z.
     */
    public static double[] spread(int x, int y, int z, double fall) {
        double s = Debris.spread(fall);
        return new double[]{RuinPlanner.grain(x, y, z, 61) * s, RuinPlanner.grain(x, y, z, 67) * s};
    }

    /**
     * Chunk readiness radius around a write that removes a non-vanilla block entity: its removal may follow chains (a
     * Create belt reads up to 20 blocks), as for nuclear ruins ({@code RuinPlan#neighbourRadius}).
     */
    public static final int MODDED_REACH = RuinPlanner.REACH;

    /** Load-bearing state (server thread): anything with collision but leaves and liquids ({@link Blast.Props#bearing}). */
    public static boolean bearing(BlockState st) {
        return Blast.props(st).bearing();
    }
}
