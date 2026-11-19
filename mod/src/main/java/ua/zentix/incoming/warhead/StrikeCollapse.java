package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
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
 * Impact work after the strike's explosions: snapshots of the chunks within {@link StrikeSupport#REACH} of a removal and
 * their neighbors, {@link #SHOTS_PER_UNIT} per unit; the solve on a ruin worker; then the fall in timed portions
 * ({@link #PORTION_NANOS}, at least one block): blocks changed since the snapshot stay, attachments and other
 * non-load-bearing blocks go first, load-bearing ones bottom-up (what falls never lands on a floor about to go). The
 * rubble share ({@link StrikeSupport#lands}) falls as vanilla falling blocks while the strike's falling limit has room
 * ({@link CraterFalls}), otherwise lands at once where it would come to rest, spread around its column by fall height
 * ({@link StrikeSupport#spread}); a container lands whole with its contents. Collapse dust and rumble: one {@link S2C.Collapse}
 * for the fallen material.
 * <p>
 * Cost per strike is bounded by what its blast reached (removal count, chunks within reach); a salvo adds jobs to the
 * impact lane's budget, never more per tick.
 */
final class StrikeCollapse implements UnitQueue.Job {
    /** Chunk snapshots per unit: a palette copy of every section and the heightmaps, ~0.1 ms each (est.). */
    static final int SHOTS_PER_UNIT = 8;
    /** Fall portion: continue while shorter, as the explosion's block portions ({@link StagedExplosion}). */
    static final long PORTION_NANOS = 5_000_000;
    /** Vanilla falling-block gravity and drag per tick ({@code FallingBlockEntity.tick}). */
    private static final double GRAVITY = 0.04, DRAG = 0.98;

    private final BlastArea area;
    private final List<StagedExplosion> after;
    private final Vec3 centre;
    @Nullable
    private StrikeSupport support;
    /** Falling positions in removal order, their states as solved and replacements. */
    @Nullable
    private LongArrayList order;
    private List<BlockState> was = List.of(), to = List.of();
    private LongArrayList fluid = new LongArrayList();
    private int next;
    /** Rubble that lands without a live falling block: source, state, container data. */
    private final List<Landing> landings = new ArrayList<>();
    private int landed;
    /** Tests: portion clock (null: impact lane clock). */
    @Nullable
    LongSupplier clock;
    /** Measurement: blocks dropped, falling entities, rubble placed at once, positions changed since the snapshot. */
    int dropped, live, placed, changed;

    private record Landing(BlockPos from, BlockState state, @Nullable CompoundTag data) {}

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

    @Override
    public boolean ready(ServerLevel level) {
        if (support != null && support.started() && !support.done()) return false;
        return area.ready(level);
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

    @Override
    public boolean step(ServerLevel level) {
        long t0 = System.nanoTime();
        int n;
        boolean more;
        if (support == null) {
            // explosions done: their removals are final
            if (area.removedAt().isEmpty() || !IncomingConfig.SERVER.blockDamage.get()) return false;
            support = new StrikeSupport(level, area.removedAt(), area.removedWas());
        }
        if (!support.started()) {
            n = support.snapshot(SHOTS_PER_UNIT);
            more = true;
        } else if (order == null) {
            if (!support.done()) return true;
            n = prepare(level);
            more = order != null && (next < order.size() || landed < landings.size());
        } else if (next < order.size()) {
            n = fall(level);
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
        if (support != null) support.cancel();
        area.release(level);
    }

    @Override
    public String describe() {
        return "Collapse at " + BlockPos.containing(centre).toShortString();
    }

    /** Solve arrived: removal order (non-load-bearing first, load-bearing bottom-up), dust and rumble. */
    private int prepare(ServerLevel level) {
        StrikeSupport.Result r;
        try {
            r = support.result();
        } catch (Exception e) {
            Incoming.LOG.warn("Collapse at {} failed: {}", BlockPos.containing(centre).toShortString(), e.toString());
            order = new LongArrayList();
            return 0;
        }
        int n = r.at().size();
        IntArrayList idx = new IntArrayList(n);
        boolean[] bearing = new boolean[n];
        for (int i = 0; i < n; i++) {
            idx.add(i);
            bearing[i] = StrikeSupport.bearing(r.was().get(i));
        }
        idx.sort((a, b) -> {
            if (bearing[a] != bearing[b]) return bearing[a] ? 1 : -1;
            return Integer.compare(BlockPos.getY(r.at().getLong(a)), BlockPos.getY(r.at().getLong(b)));
        });
        order = new LongArrayList(n);
        was = new ArrayList<>(n);
        to = new ArrayList<>(n);
        double sx = 0, sz = 0;
        int base = Integer.MAX_VALUE, mass = 0;
        java.util.Map<GroundMaterial, Integer> kinds = new java.util.EnumMap<>(GroundMaterial.class);
        for (int k = 0; k < n; k++) {
            int i = idx.getInt(k);
            long at = r.at().getLong(i);
            order.add(at);
            was.add(r.was().get(i));
            to.add(r.to().get(i));
            if (!bearing[i]) continue;
            mass++;
            sx += BlockPos.getX(at) + 0.5;
            sz += BlockPos.getZ(at) + 0.5;
            base = Math.min(base, BlockPos.getY(at));
            GroundMaterial m = GroundMaterial.of(r.was().get(i));
            if (m != null) kinds.merge(m, 1, Integer::sum);
        }
        fluid = r.fluidTicks();
        if (mass > 0) {
            GroundMaterial material = kinds.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey).orElse(GroundMaterial.BRICK);
            Vec3 at = new Vec3(sx / mass, base, sz / mass);
            // dust from the fallen material: a block of wall or floor stands for StrikeSupport.MATERIAL m³ of real material
            PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, IncomingConfig.SERVER.farRange.get(),
                    new S2C.Collapse(at, material.ordinal(), (float) (mass * StrikeSupport.MATERIAL)));
        }
        return n;
    }

    /** Timed portion of the fall. */
    private int fall(ServerLevel level) {
        LongSupplier watch = clock != null ? clock : WorkScheduler.impactClock(level.getServer()).sampler();
        long start = watch.getAsLong();
        int n = 0;
        CraterFalls falls = CraterFalls.get(level);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (next < order.size()) {
            int i = next++;
            BlockPos p = BlockPos.of(order.getLong(i));
            BlockState st = was.get(i);
            if (!Terrain.ready(level, p) || level.getBlockState(p) != st) {
                changed++;
                continue;
            }
            CompoundTag data = null;
            boolean lands = StrikeSupport.bearing(st) && StrikeSupport.lands(st, p.getX(), p.getY(), p.getZ());
            if (lands && StrikeSupport.container(st)) {
                BlockEntity be = level.getBlockEntity(p);
                if (be != null) data = be.saveWithoutMetadata(level.registryAccess());
            }
            ColumnScar.replace(level, p, st, to.get(i));
            dropped++;
            n++;
            if (lands) {
                BlockState rubble = StrikeSupport.rubble(st, p.getX(), p.getY(), p.getZ());
                if (data == null && to.get(i).isAir() && falls.room(level, p)) drop(level, p, rubble, m);
                else landings.add(new Landing(p, rubble, data));
            }
            if (watch.getAsLong() - start >= PORTION_NANOS) break;
        }
        if (next >= order.size()) {
            for (int i = 0; i < fluid.size(); i++) {
                BlockPos p = BlockPos.of(fluid.getLong(i));
                if (!Terrain.ready(level, p)) continue;
                FluidState f = level.getFluidState(p);
                if (!f.isEmpty()) level.scheduleTick(p, f.getType(), f.getType().getTickDelay(level));
            }
        }
        return n;
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
        LongSupplier watch = clock != null ? clock : WorkScheduler.impactClock(level.getServer()).sampler();
        long start = watch.getAsLong();
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (landed < landings.size()) {
            Landing l = landings.get(landed++);
            BlockPos from = l.from();
            if (Terrain.ready(level, from)) {
                int fall = from.getY() - rest(level, from.getX(), from.getY(), from.getZ(), m);
                double[] off = StrikeSupport.spread(from.getX(), from.getY(), from.getZ(), fall);
                int x = Mth.floor(from.getX() + 0.5 + off[0]), z = Mth.floor(from.getZ() + 0.5 + off[1]);
                // spread into a standing wall or an unready chunk: comes down in its own column
                if (!Terrain.ready(level, x >> 4, z >> 4) || !FallingBlock.isFree(level.getBlockState(m.set(x, from.getY(), z)))) {
                    x = from.getX();
                    z = from.getZ();
                }
                int y = rest(level, x, from.getY(), z, m) + 1;
                BlockPos at = new BlockPos(x, y, z);
                BlockState there = level.getBlockState(at);
                if (y < level.getMaxBuildHeight() && FallingBlock.isFree(there) && there.getFluidState().isEmpty()) {
                    ColumnScar.replace(level, at, there, l.state());
                    if (l.data() != null) {
                        BlockEntity be = level.getBlockEntity(at);
                        if (be != null) {
                            be.loadWithComponents(l.data(), level.registryAccess());
                            be.setChanged();
                        }
                    }
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
