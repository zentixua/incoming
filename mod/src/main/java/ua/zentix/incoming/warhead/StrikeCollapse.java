package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.nuclear.world.StrikeSupport;
import ua.zentix.incoming.strike.ImpactCost;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.work.UnitQueue;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * What a conventional strike's blast left without support falls: the nuclear ruin support law ({@code Collapse}, via
 * {@link StrikeSupport}) over the blocks the strike's explosions removed ({@link BlastArea#removedAt}). A missile through
 * a tower's lower floors brings down what those floors held; a hole that every block above still spans stands.
 * <p>
 * Impact work after the strike's explosions: with one of the server's solve slots ({@link StrikeSupport#reserve}),
 * snapshots of the chunks within {@link StrikeSupport#REACH} of a removal and their neighbors in timed portions; the
 * solve on a background worker; then the fall (its own job, {@link Fall}, queued when the solve starts) in timed
 * portions ({@link #PORTION_NANOS}, at least one block): claim handlers may keep positions (one
 * {@code ExplosionEvent.Detonate} for the collapse, as for the blast); blocks changed since the snapshot stay; writes only
 * where the chunk and its neighbors are ready and never into the Sable plot grid; a container spills its contents
 * first. The rubble share falls as vanilla falling blocks while the strike's falling limit has room ({@link CraterFalls}),
 * otherwise lands at once where it would come to rest, spread around its column by fall height
 * ({@link StrikeSupport#spread}). Collapse dust and rumble: one {@link S2C.Collapse} for the fallen material.
 * <p>
 * Cost per strike is bounded by what its blast reached (removal count, chunks within reach); a salvo adds jobs to the
 * impact lane's budget, never more per tick.
 */
final class StrikeCollapse implements UnitQueue.Job {
    /** Snapshot, fall and landing portion: continue while shorter, as the explosion's block portions ({@link StagedExplosion}). */
    static final long PORTION_NANOS = 5_000_000;
    /** Vanilla falling-block gravity and drag per tick ({@code FallingBlockEntity.tick}). */
    private static final double GRAVITY = 0.04, DRAG = 0.98;

    private final BlastArea area;
    private final List<StagedExplosion> after;
    private final Vec3 centre;
    @Nullable
    private StrikeSupport support;
    /** Snapshots done and the solve started: {@link Fall} holds it now. */
    private boolean handedOver;
    @Nullable
    private StrikeSupport.Result result;
    /** Positions claim handlers kept ({@link #prepare}). */
    private LongOpenHashSet kept = new LongOpenHashSet();
    private int next, nextFluid;
    /** Rubble that lands without a live falling block: source and state. */
    private final List<Landing> landings = new ArrayList<>();
    private int landed;
    /** Tests: portion clock (null: impact lane clock). */
    @Nullable
    LongSupplier clock;
    /**
     * Measurement: blocks dropped, falling entities, rubble placed at once, positions changed since the snapshot or kept by
     * claims or unready chunks, chunks solved, fall portions and the largest one (blocks).
     */
    int dropped, live, placed, changed, solved, portions, largestPortion;
    /** Both jobs ended (tests). */
    boolean finished;

    private record Landing(BlockPos from, BlockState state) {}

    StrikeCollapse(BlastArea area, List<StagedExplosion> after, Vec3 centre) {
        this.area = area.retain();
        this.after = after;
        this.centre = centre;
    }

    /** Queue the collapse of a strike whose explosions are {@code after}, holding {@code area}. */
    static StrikeCollapse queue(ServerLevel level, BlastArea area, List<StagedExplosion> after, Vec3 centre) {
        StrikeCollapse job = new StrikeCollapse(area, after, centre);
        StrikeWorld.get(level).impacts().add(level, job);
        return job;
    }

    /** Area ready and a solve slot held (the explosions are done: their removals are final). */
    @Override
    public boolean ready(ServerLevel level) {
        if (!area.ready(level)) return false;
        if (support == null) {
            if (area.removedAt().isEmpty() || !IncomingConfig.SERVER.blockDamage.get()) return true;
            support = new StrikeSupport(level, area.removedAt(), area.removedWas());
        }
        return support.reserve();
    }

    @Override
    public boolean dynamicReadiness() {
        return true;
    }

    @Override
    public boolean blocked() {
        return Warheads.pending(after);
    }

    @Override
    public int unitKind() {
        return ImpactCost.Kind.GROUND.ordinal();
    }

    /**
     * Snapshot units; the last one starts the solve and hands over to {@link Fall}, a job queued then: its wait for the
     * solve counts from that moment ({@link UnitQueue#GIVE_UP_TICKS} from queueing), not from the strike.
     */
    @Override
    public boolean step(ServerLevel level) {
        if (support == null) return false;
        long t0 = System.nanoTime();
        int n = support.snapshot(watch(level), PORTION_NANOS);
        area.record(level, ImpactCost.Kind.GROUND, System.nanoTime() - t0, n);
        if (!support.started()) return true;
        StrikeWorld.get(level).impacts().add(level, new Fall());
        handedOver = true;
        return false;
    }

    @Override
    public void end(ServerLevel level) {
        if (!handedOver) {
            if (support != null) support.cancel();
            finished = true;
        }
        area.release(level);
    }

    @Override
    public String describe() {
        return "Collapse at " + BlockPos.containing(centre).toShortString();
    }

    private LongSupplier watch(ServerLevel level) {
        return clock != null ? clock : WorkScheduler.impactClock(level.getServer()).sampler();
    }

    /** The fall: waits for the solve, then removal and landing portions. Holds the strike area. */
    private final class Fall implements UnitQueue.Job {
        Fall() {
            area.retain();
        }

        @Override
        public boolean ready(ServerLevel level) {
            return support.done() && area.ready(level);
        }

        @Override
        public boolean dynamicReadiness() {
            return true;
        }

        @Override
        public int unitKind() {
            return ImpactCost.Kind.GROUND.ordinal();
        }

        @Override
        public boolean step(ServerLevel level) {
            long t0 = System.nanoTime();
            int n = 0;
            boolean more;
            if (result == null) {
                prepare(level);
                more = true;
            } else if (next < result.at().length) {
                n = fall(level);
                more = true;
            } else if (nextFluid < result.fluidTicks().length) {
                fluid(level);
                more = true;
            } else {
                n = land(level);
                more = landed < landings.size();
            }
            area.record(level, ImpactCost.Kind.GROUND, System.nanoTime() - t0, n);
            return more;
        }

        @Override
        public void end(ServerLevel level) {
            support.cancel();
            if (Incoming.LOG.isDebugEnabled()) {
                Incoming.LOG.debug("Collapse at {}: blast removed {}, chunks {}, solved {}, fell {} (falling {}, landed at once {}), stayed {}",
                        BlockPos.containing(centre).toShortString(), support.removedCount(), support.chunks(), solved, dropped, live, placed, changed);
            }
            area.release(level);
            finished = true;
        }

        @Override
        public String describe() {
            return "Collapse fall at " + BlockPos.containing(centre).toShortString();
        }
    }

    /**
     * Solve arrived (ordered on the worker): abandoned if a removal is filled again since the snapshot; claim handlers
     * keep their positions; dust and rumble.
     */
    private void prepare(ServerLevel level) {
        try {
            result = support.result();
        } catch (Exception e) {
            Incoming.LOG.warn("Collapse at {} failed: {}", BlockPos.containing(centre).toShortString(), e.toString());
            result = null;
        }
        if (result == null || result.at().length == 0) {
            result = emptyResult();
            return;
        }
        solved = result.solved();
        // a support built back into the hole while the solve ran: the plan no longer describes the world
        for (long p : support.removedPositions()) {
            BlockPos at = BlockPos.of(p);
            if (Terrain.ready(level, at) && StrikeSupport.bearing(level.getBlockState(at))) {
                result = emptyResult();
                return;
            }
        }
        // protection (claims) as for the blast itself: handlers filter the positions of an explosion's Detonate; a
        // zero-radius explosion without entities, so no handler reads it as a new blast (blackout, damage)
        Explosion e = new Explosion(level, null, centre.x, centre.y, centre.z, 0f, false, Explosion.BlockInteraction.DESTROY_WITH_DECAY);
        List<BlockPos> all = new ArrayList<>(result.at().length);
        for (long p : result.at()) all.add(BlockPos.of(p));
        e.getToBlow().addAll(all);
        EventHooks.onExplosionDetonate(level, e, new ArrayList<>(), 0);
        LongOpenHashSet allowed = new LongOpenHashSet(e.getToBlow().size());
        for (BlockPos p : e.getToBlow()) allowed.add(p.asLong());
        for (long p : result.at()) if (!allowed.contains(p)) kept.add(p);
        Vec3 base = result.base();
        if (base != null) {
            GroundMaterial m = result.common() == null ? null : GroundMaterial.of(result.common());
            // dust from the fallen material: a block of wall or floor stands for StrikeSupport.MATERIAL m³ of real material
            PacketDistributor.sendToPlayersNear(level, null, base.x, base.y, base.z, IncomingConfig.SERVER.farRange.get(),
                    new S2C.Collapse(base, (m == null ? GroundMaterial.BRICK : m).ordinal(), (float) (result.mass() * StrikeSupport.MATERIAL)));
        }
    }

    private static StrikeSupport.Result emptyResult() {
        return new StrikeSupport.Result(new long[0], new BlockState[0], new BlockState[0], new BlockState[0], new long[0], 0, 0, null, null);
    }

    /** Timed portion of the fall. */
    private int fall(ServerLevel level) {
        LongSupplier watch = watch(level);
        long start = watch.getAsLong();
        int n = 0;
        CraterFalls falls = CraterFalls.get(level);
        Long2BooleanOpenHashMap safe = new Long2BooleanOpenHashMap();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        long[] at = result.at();
        while (next < at.length) {
            int i = next++;
            BlockPos p = BlockPos.of(at[i]);
            BlockState st = result.was()[i];
            if (kept.contains(at[i]) || !writable(level, p.getX(), p.getZ(), safe) || level.getBlockState(p) != st) {
                changed++;
            } else {
                spill(level, p, st);
                ColumnScar.replace(level, p, st, result.to()[i]);
                dropped++;
                n++;
                BlockState rubble = result.rubble()[i];
                if (rubble != null) {
                    if (falls.room(level, p)) drop(level, p, rubble, m);
                    else landings.add(new Landing(p, rubble));
                }
            }
            if (watch.getAsLong() - start >= PORTION_NANOS) break;
        }
        if (n > 0) {
            portions++;
            largestPortion = Math.max(largestPortion, n);
        }
        return n;
    }

    /** Fluid beside what fell flows: scheduled ticks, in timed portions. */
    private void fluid(ServerLevel level) {
        LongSupplier watch = watch(level);
        long start = watch.getAsLong();
        long[] ticks = result.fluidTicks();
        while (nextFluid < ticks.length) {
            BlockPos p = BlockPos.of(ticks[nextFluid++]);
            if (Terrain.ready(level, p)) {
                FluidState f = level.getFluidState(p);
                if (!f.isEmpty()) level.scheduleTick(p, f.getType(), f.getType().getTickDelay(level));
            }
            if (watch.getAsLong() - start >= PORTION_NANOS) break;
        }
    }

    /**
     * A write at column {@code x, z} touches only loaded chunks: its chunk and all eight neighbors ready (a block change
     * reads its neighbors; Sable's change hook too), and not the Sable plot grid (craft blocks are not the world).
     */
    private static boolean writable(ServerLevel level, int x, int z, Long2BooleanOpenHashMap cache) {
        long c = ChunkPos.asLong(x >> 4, z >> 4);
        if (cache.containsKey(c)) return cache.get(c);
        boolean ok = Terrain.neighbourhoodReady(level, x >> 4, z >> 4) && !SubLevels.inPlotGrid(level, new ChunkPos(c));
        cache.put(c, ok);
        return ok;
    }

    /**
     * A container's contents come out where it stood before it goes (its block lands as rubble or dust like the rest);
     * an unopened loot chest keeps its table unrolled, as the crater's removal does ({@link ColumnScar#replace}).
     */
    private static void spill(ServerLevel level, BlockPos p, BlockState st) {
        if (!st.hasBlockEntity()) return;
        BlockEntity be = level.getBlockEntity(p);
        if (!(be instanceof Container c) || be instanceof RandomizableContainer r && r.getLootTable() != null) return;
        Containers.dropContents(level, p, c);
    }

    /**
     * A live falling block from {@code from} (already empty): moves sideways so that it comes down where the spread
     * around its column puts it ({@link StrikeSupport#spread}), lands as vanilla places a falling block.
     */
    private void drop(ServerLevel level, BlockPos from, BlockState rubble, BlockPos.MutableBlockPos m) {
        int fall = from.getY() - rest(level, from.getX(), from.getY(), from.getZ(), m);
        double[] off = StrikeSupport.spread(from.getX(), from.getY(), from.getZ(), fall);
        FallingBlockEntity e = FallingBlockEntity.fall(level, from, rubble);
        e.dropItem = false;
        double ticks = fallTicks(Math.max(1, fall));
        e.setDeltaMovement(off[0] / ticks, 0, off[1] / ticks);
        live++;
    }

    /**
     * Ticks a vanilla falling block takes to drop {@code h} blocks ({@link #GRAVITY}, {@link #DRAG}); horizontal speed
     * decays by the same drag, so a constant speed over this time is close enough for the spread (presentation).
     */
    static double fallTicks(double h) {
        double y = 0, v = 0;
        int t = 0;
        while (y < h && t < 200) {
            v = (v + GRAVITY) * DRAG;
            y += v;
            t++;
        }
        return Math.max(1, t);
    }

    /** Rubble without a live falling block, in timed portions, after every fall is out of the way. */
    private int land(ServerLevel level) {
        LongSupplier watch = watch(level);
        long start = watch.getAsLong();
        int n = 0;
        Long2BooleanOpenHashMap safe = new Long2BooleanOpenHashMap();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (landed < landings.size()) {
            Landing l = landings.get(landed++);
            BlockPos from = l.from();
            if (writable(level, from.getX(), from.getZ(), safe)) {
                int fall = from.getY() - rest(level, from.getX(), from.getY(), from.getZ(), m);
                double[] off = StrikeSupport.spread(from.getX(), from.getY(), from.getZ(), fall);
                int x = Mth.floor(from.getX() + 0.5 + off[0]), z = Mth.floor(from.getZ() + 0.5 + off[1]);
                // spread into a standing wall or a chunk not safe to write: comes down in its own column
                if (!writable(level, x, z, safe) || !FallingBlock.isFree(level.getBlockState(m.set(x, from.getY(), z)))) {
                    x = from.getX();
                    z = from.getZ();
                }
                int y = rest(level, x, from.getY(), z, m) + 1;
                BlockPos at = new BlockPos(x, y, z);
                BlockState there = level.getBlockState(at);
                // under water rubble sinks away, as in the nuclear pile (RuinPlanner#buries)
                if (y < level.getMaxBuildHeight() && FallingBlock.isFree(there) && there.getFluidState().isEmpty()) {
                    ColumnScar.replace(level, at, there, l.state());
                    placed++;
                    n++;
                }
            }
            if (watch.getAsLong() - start >= PORTION_NANOS) break;
        }
        return n;
    }

    /** Highest position below {@code y} in the column that a falling block would rest on ({@link FallingBlock#isFree} not). */
    private static int rest(ServerLevel level, int x, int y, int z, BlockPos.MutableBlockPos m) {
        int bottom = level.getMinBuildHeight();
        for (int k = y - 1; k >= bottom; k--) {
            if (!FallingBlock.isFree(level.getBlockState(m.set(x, k, z)))) return k;
        }
        return bottom - 1;
    }
}
