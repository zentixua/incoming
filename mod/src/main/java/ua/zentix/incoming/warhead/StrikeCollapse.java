package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.nuclear.world.StrikeRemovals;
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
 * Impact work after the strike's explosions: with one of the server's solve slots ({@link StrikeSupport#tryReserve}),
 * snapshots of the chunks within {@link StrikeSupport#REACH} of a removal and their neighbors in timed portions; the
 * solve on a background worker; then the fall (its own job, {@link Fall}, queued when the solve starts) in timed
 * portions ({@link #PORTION_NANOS}, at least one block): claim handlers may keep positions
 * ({@code ExplosionEvent.Detonate} events attributed to the strike, {@link #OFFER_UNIT} positions each; a kept
 * load-bearing block cancels the fall); blocks changed since the snapshot stay; writes only
 * where the chunk and its neighbors are ready and never into the Sable plot grid; a block that holds items is removed
 * as the game removes a broken block, so its contents come out ({@link #remove}). The rubble share falls as vanilla falling blocks while the strike's falling limit has room ({@link CraterFalls}),
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

    /** Replans after the world changed under a plan ({@link #replan}): a support built while it was solved or fell. */
    static final int MAX_REPLANS = 2;

    private final BlastArea area;
    private final List<StagedExplosion> after;
    private final Vec3 centre;
    /** Positions taken as removed (the strike's; for a replan also what the earlier plan dropped) and their states before. */
    private final it.unimi.dsi.fastutil.longs.LongArrayList removedAt;
    private final List<BlockState> removedWas;
    /** Replans before this plan. */
    private final int replans;
    /** This collapse and its replans: measurement shared by the chain. */
    final Stats stats;
    /** What this plan dropped, for a replan ({@link #replan}). */
    private final it.unimi.dsi.fastutil.longs.LongArrayList droppedAt = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private final List<BlockState> droppedWas = new ArrayList<>();
    @Nullable
    private StrikeSupport support;
    /** Snapshots done and the solve started: {@link Fall} holds it now. */
    private boolean handedOver;
    @Nullable
    private StrikeSupport.Result result;
    /** Positions claim handlers kept ({@link #prepare}). */
    private LongOpenHashSet kept = new LongOpenHashSet();
    /** Preparation cursors: removals checked for a support built back, fall positions offered to claim handlers. */
    @Nullable
    private LongIterator holes;
    private int offered, peerWindow, peerSource, peerOffered;
    private boolean claimsChanged;
    private boolean prepared;
    private int next, nextFluid;
    private @Nullable Long2ObjectLinkedOpenHashMap<Dust> sharedDust;
    /** Rubble that lands without a live falling block: source and state. */
    private final List<Landing> landings = new ArrayList<>();
    private int landed;
    /** Tests: portion clock (null: impact lane clock). */
    @Nullable
    LongSupplier clock;
    /** Tests: runs once when the solve is in, before preparation (a world change between solve and fall). */
    @Nullable
    Runnable beforeFall;
    /**
     * Measurement of a collapse and its replans: blocks dropped, falling entities, rubble placed at once, positions changed
     * since the snapshot or kept by claims or unready chunks, chunks solved, fall portions and the largest one (blocks),
     * replans; every job of the chain ended (tests).
     */
    static final class Stats {
        int dropped, live, direct, placed, changed, solved, portions, largestPortion, replans;
        boolean finished;
        /** Jobs of the chain not yet ended. */
        private int open;
    }

    private record Landing(BlockPos from, BlockState state) {}

    /** Material this fall removed in one chunk. Other falls can consume candidates between portions. */
    private static final class Dust {
        private final Reference2IntOpenHashMap<BlockState> kinds = new Reference2IntOpenHashMap<>();
        private int blocks, bottom = Integer.MAX_VALUE, most;
        private double x, z;
        private @Nullable BlockState common;

        void add(BlockPos p, BlockState state) {
            blocks++;
            x += p.getX() + 0.5;
            z += p.getZ() + 0.5;
            bottom = Math.min(bottom, p.getY());
            int count = kinds.addTo(state, 1) + 1;
            if (count > most) {
                most = count;
                common = state;
            }
        }
    }

    StrikeCollapse(BlastArea area, List<StagedExplosion> after, Vec3 centre) {
        this(area, after, centre, area.removedAt(), area.removedWas(), 0, new Stats());
    }

    private StrikeCollapse(BlastArea area, List<StagedExplosion> after, Vec3 centre, it.unimi.dsi.fastutil.longs.LongArrayList removedAt,
                           List<BlockState> removedWas, int replans, Stats stats) {
        this.area = area.retain();
        this.after = after;
        this.centre = centre;
        this.removedAt = removedAt;
        this.removedWas = removedWas;
        this.replans = replans;
        this.stats = stats;
        stats.open++;
    }

    /** One job of the chain ended; the last sets {@link Stats#finished}. */
    private void closed() {
        if (--stats.open == 0) stats.finished = true;
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
        // the cheap slot test first: a backlog of waiting collapses costs no area readiness scans
        if (support == null && !removedAt.isEmpty() && !StrikeSupport.slotFree()) return false;
        if (!area.ready(level)) return false;
        if (support == null) {
            if (removedAt.isEmpty() || !area.rules().blockDamage()) return true;
            // the slot first: a waiting collapse holds nothing but its strike area
            if (!StrikeSupport.tryReserve()) return false;
            support = new StrikeSupport(level, removedAt, removedWas, area.collapseSource());
            // the fall's writes need the Sable origin chunk loaded (Fall#ready): requested now, loaded while this solves
            if (ua.zentix.incoming.compat.SableTerrain.present()) ua.zentix.incoming.nuclear.world.NuclearTickets.holdOrigin(level, true);
        }
        return true;
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
        return ImpactCost.Kind.COLLAPSE.ordinal();
    }

    /**
     * Snapshot units; the last one starts the solve and hands over to {@link Fall}, a job queued then: its wait for the
     * solve counts from that moment ({@link UnitQueue#GIVE_UP_TICKS} from queueing), not from the strike.
     */
    @Override
    public boolean step(ServerLevel level) {
        if (support == null) return false;
        long t0 = System.nanoTime();
        support.snapshot(watch(level), PORTION_NANOS);
        // counted: blocks fallen; a snapshot unit drops none
        area.record(level, ImpactCost.Kind.COLLAPSE, System.nanoTime() - t0, 0);
        if (!support.started()) return true;
        StrikeWorld.get(level).impacts().add(level, new Fall());
        handedOver = true;
        return false;
    }

    @Override
    public void end(ServerLevel level) {
        if (!handedOver) {
            if (support != null) support.cancel();
            closed();
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
            return support.done() && area.ready(level) && originReady(level);
        }

        @Override
        public boolean dynamicReadiness() {
            return true;
        }

        @Override
        public int unitKind() {
            return ImpactCost.Kind.COLLAPSE.ordinal();
        }

        @Override
        public boolean step(ServerLevel level) {
            long t0 = System.nanoTime();
            int n = 0;
            boolean more;
            if (!prepared) {
                prepare(level);
                more = true;
            } else if (next < result.at().length) {
                n = fall(level);
                more = true;
            } else if (sharedDust != null && !sharedDust.isEmpty()) {
                LongSupplier watch = watch(level);
                announceShared(level, watch, watch.getAsLong());
                more = true;
            } else if (nextFluid < result.fluidTicks().length) {
                fluid(level);
                more = true;
            } else {
                n = land(level);
                more = landed < landings.size();
            }
            area.record(level, ImpactCost.Kind.COLLAPSE, System.nanoTime() - t0, n);
            return more;
        }

        @Override
        public void end(ServerLevel level) {
            support.cancel();
            if (Incoming.LOG.isDebugEnabled()) {
                Incoming.LOG.debug("Collapse at {}: recorded removals with peers {}, chunks {}, solved {}, fell {} (falling {}, landed at once {}), stayed {}, replans {}",
                        BlockPos.containing(centre).toShortString(), support.recordedRemovals(), support.chunks(), stats.solved, stats.dropped,
                        stats.live, stats.placed, stats.changed, stats.replans);
            }
            area.release(level);
            closed();
        }

        @Override
        public String describe() {
            return "Collapse fall at " + BlockPos.containing(centre).toShortString();
        }
    }

    /** Fall positions per claim-handler event ({@link #prepare}): handlers visit each, as for a blast's own list. */
    static final int OFFER_UNIT = 1024;

    /**
     * Solve arrived (ordered on the worker), in timed portions: abandoned if a removal the solve used holds a
     * load-bearing block again; claim handlers keep their positions ({@link #OFFER_UNIT} a unit).
     */
    private void prepare(ServerLevel level) {
        if (result == null && beforeFall != null) {
            Runnable r = beforeFall;
            beforeFall = null;
            r.run();
        }
        if (result == null) {
            try {
                result = support.result();
            } catch (Exception e) {
                Incoming.LOG.warn("Collapse at {} failed: {}", BlockPos.containing(centre).toShortString(), e.toString());
                result = null;
            }
            if (result == null || result.at().length == 0) {
                result = emptyResult();
                prepared = true;
                support.releaseCapture();
                return;
            }
            stats.solved += result.solved();
            holes = result.lostPositions().iterator();
            if (sharedDust == null && !result.peerClaims().isEmpty()) sharedDust = new Long2ObjectLinkedOpenHashMap<>();
        }
        LongSupplier watch = watch(level);
        long start = watch.getAsLong();
        // a support built back into the hole while the solve ran: the plan no longer describes the world
        for (int i = 0; holes.hasNext(); i++) {
            BlockPos at = BlockPos.of(holes.nextLong());
            if (Terrain.ready(level, at) && StrikeSupport.bearing(level.getBlockState(at))) {
                // the solve decides again whether what it holds up still lacks support
                replan(level, refilled(at));
                prepared = true;
                return;
            }
            if ((i & 63) == 63 && watch.getAsLong() - start >= PORTION_NANOS) return;
        }
        long[] all = result.at();
        if (offered < all.length) {
            // protection (claims) as for the blast itself: handlers filter the positions of an explosion's Detonate; a
            // zero-radius explosion without entities, so no handler reads it as a new blast (blackout, damage)
            int end = Math.min(all.length, offered + OFFER_UNIT);
            StrikeRemovals.Source source = area.collapseSource();
            Explosion e = source != null ? source.explosion(level)
                    : after.getFirst().attributed(level, centre);
            BlockPos protectedBlock = protectedBearing(level, e, null, offered, end);
            if (protectedBlock != null) {
                Incoming.LOG.info("Collapse at {} kept standing: claim protection keeps load-bearing {}",
                        BlockPos.containing(centre).toShortString(), protectedBlock.toShortString());
                result = emptyResult();
                prepared = true;
                support.releaseCapture();
                return;
            }
            offered = end;
            if (offered < all.length || !result.peerClaims().isEmpty()) return;
        }
        if (peerWindow < result.peerClaims().size()) {
            StrikeSupport.PeerClaims peer = result.peerClaims().get(peerWindow);
            int end = Math.min(peer.indices().length, peerOffered + OFFER_UNIT);
            StrikeRemovals.Source source = peer.sources().get(peerSource);
            if (!support.excludedPeer(source)) {
                Explosion e = source.explosion(level);
                BlockPos protectedBlock = protectedBearing(level, e, peer.indices(), peerOffered, end);
                if (protectedBlock == null && end < peer.indices().length) {
                    peerOffered = end;
                    return;
                }
                if (protectedBlock != null) {
                    claimsChanged |= support.excludePeer(source);
                    Incoming.LOG.info("Collapse at {} excludes peer at {}: claim protection keeps load-bearing {}",
                            BlockPos.containing(centre).toShortString(), BlockPos.containing(e.center()).toShortString(),
                            protectedBlock.toShortString());
                }
            }
            peerOffered = 0;
            if (++peerSource < peer.sources().size()) return;
            peerSource = 0;
            if (++peerWindow < result.peerClaims().size()) return;
        }
        if (claimsChanged) {
            claimsChanged = false;
            offered = peerWindow = peerSource = peerOffered = 0;
            kept = new LongOpenHashSet();
            result = null;
            support.retryClaims();
            return;
        }
        prepared = true;
        support.releaseCapture();
        if (sharedDust == null) announce(level, result.base(), result.common(), result.mass());
    }

    /** Each local cloud starts over its chunk's fallen material. Packet fan-out shares the fall deadline. */
    private void announceShared(ServerLevel level, LongSupplier watch, long start) {
        while (!sharedDust.isEmpty()) {
            Dust dust = sharedDust.removeFirst();
            announce(level, new Vec3(dust.x / dust.blocks, dust.bottom, dust.z / dust.blocks), dust.common, dust.blocks);
            if (watch.getAsLong() - start >= PORTION_NANOS) return;
        }
    }

    private void announce(ServerLevel level, @Nullable Vec3 base, @Nullable BlockState common, int mass) {
        if (base != null) {
            GroundMaterial m = common == null ? null : GroundMaterial.of(common);
            // dust from the fallen material: a block of wall or floor stands for StrikeSupport.MATERIAL m³ of real material
            PacketDistributor.sendToPlayersNear(level, null, base.x, base.y, base.z, IncomingConfig.SERVER.farRange.get(),
                    new S2C.Collapse(base, (m == null ? GroundMaterial.BRICK : m).ordinal(), (float) (mass * StrikeSupport.MATERIAL)));
        }
    }

    /** Keep decorative blocks directly and return the first protected bearing block for a support-based retry. */
    private @Nullable BlockPos protectedBearing(ServerLevel level, Explosion e, @Nullable int[] indices, int from, int end) {
        long[] all = result.at();
        List<BlockPos> list = e.getToBlow();
        for (int i = from; i < end; i++) list.add(BlockPos.of(all[indices == null ? i : indices[i]]));
        EventHooks.onExplosionDetonate(level, e, new ArrayList<>(), 0);
        LongOpenHashSet allowed = new LongOpenHashSet(list.size());
        for (BlockPos p : list) allowed.add(p.asLong());
        for (int i = from; i < end; i++) {
            int index = indices == null ? i : indices[i];
            long at = all[index];
            if (allowed.contains(at)) continue;
            if (StrikeSupport.bearing(result.was()[index])) return BlockPos.of(at);
            kept.add(at);
        }
        return null;
    }

    private static StrikeSupport.Result emptyResult() {
        return StrikeSupport.Result.EMPTY;
    }

    /**
     * The world changed under this plan before it was done (a support built back into a hole or beside what falls, while
     * it was solved or fell): what is left of it is dropped, and a new plan over the strike's removals and what this one
     * dropped decides again from fresh snapshots, at most {@link #MAX_REPLANS} times (then the rest stays standing).
     */
    private void replan(ServerLevel level, String why) {
        result = emptyResult();
        next = 0;
        String at = BlockPos.containing(centre).toShortString();
        if (replans >= MAX_REPLANS) {
            Incoming.LOG.info("Collapse at {} stopped: {} after {} replans", at, why, replans);
            return;
        }
        Incoming.LOG.info("Collapse at {} replanned: {}", at, why);
        stats.replans++;
        it.unimi.dsi.fastutil.longs.LongArrayList removals = new it.unimi.dsi.fastutil.longs.LongArrayList(removedAt);
        List<BlockState> states = new ArrayList<>(removedWas);
        removals.addAll(droppedAt);
        states.addAll(droppedWas);
        StrikeCollapse again = new StrikeCollapse(area, after, centre, removals, states, replans + 1, stats);
        again.clock = clock;
        StrikeWorld.get(level).impacts().add(level, again);
    }

    /**
     * A load-bearing block now beside {@code p} where the solve saw none ({@link StrikeSupport.Result#watch}) or in one of
     * the holes it took as lost: the plan no longer describes what holds {@code p}. Its chunk and neighbours are ready
     * ({@link #writable}).
     */
    private @Nullable String stale(ServerLevel level, BlockPos p, BlockPos.MutableBlockPos m) {
        it.unimi.dsi.fastutil.longs.LongSet watch = result.watch(), lost = result.lostPositions();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            m.setWithOffset(p, d);
            long q = m.asLong();
            if (!watch.contains(q) && !lost.contains(q) || !StrikeSupport.bearing(level.getBlockState(m))) continue;
            return lost.contains(q) ? refilled(m) : "a support beside " + p.toShortString();
        }
        return null;
    }

    private String refilled(BlockPos at) {
        return (support.peerLoss(at.asLong()) ? "a peer removal refilled at " : "a removed block is back at ") + at.toShortString();
    }

    /** Sable reads block-entity shapes at the origin on a block change: the fall writes once that chunk is loaded. */
    private static boolean originReady(ServerLevel level) {
        return !ua.zentix.incoming.compat.SableTerrain.present() || level.getChunkSource().getChunkNow(0, 0) != null;
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
            String stale;
            if (kept.contains(at[i]) || !writable(level, p.getX(), p.getZ(), safe) || level.getBlockState(p) != st
                    || modded(st) && !Terrain.readyAround(level, Vec3.atCenterOf(p), 16 * StrikeSupport.MODDED_REACH)) {
                stats.changed++;
            } else if ((stale = stale(level, p, m)) != null) {
                replan(level, stale);
                break;
            } else {
                boolean item = remove(level, p, st, result.to()[i]);
                if (sharedDust != null && StrikeSupport.bearing(st)) {
                    long chunk = ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
                    sharedDust.computeIfAbsent(chunk, key -> new Dust()).add(p, st);
                }
                droppedAt.add(at[i]);
                droppedWas.add(st);
                StrikeRemovals.Source source = area.collapseSource();
                if (source != null) source.record(at[i], st);
                stats.dropped++;
                n++;
                BlockState rubble = item ? null : result.rubble()[i];
                if (rubble != null) {
                    if (falls.room(level, p)) drop(level, p, rubble, m);
                    else {
                        landings.add(new Landing(p, rubble));
                        stats.direct++;
                    }
                }
            }
            if (watch.getAsLong() - start >= PORTION_NANOS) break;
        }
        if (n > 0) {
            stats.portions++;
            stats.largestPortion = Math.max(stats.largestPortion, n);
        }
        if (next >= result.at().length && sharedDust != null && !sharedDust.isEmpty()
                && watch.getAsLong() - start < PORTION_NANOS) announceShared(level, watch, start);
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

    /** Sides an inventory may be reached from: the side-less view first ({@code null}), then each face. */
    private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /**
     * Remove a fallen block as the game removes a broken one, so nothing it holds is lost: a storage block entity
     * (a vanilla {@link Container}, or the loader's item-handler capability from any side) drops as a vanilla explosion
     * drops it ({@link Block#dropResources} with its block entity: a shulker box keeps its contents in the item), then the
     * block's own removal ({@code onRemove}) spills what it keeps inside: a chest's contents, a Create vault's or depot's,
     * a jukebox's disc. Nothing is cleared first (the nuclear ruins clear, {@link ColumnScar#replace}). An unopened loot
     * chest keeps its table unrolled (rolling it reads structures for maps, hundreds of ms).
     *
     * @return the block dropped as an item (it lands as no rubble then)
     */
    private static boolean remove(ServerLevel level, BlockPos p, BlockState st, BlockState to) {
        boolean item = false;
        if (st.hasBlockEntity()) {
            BlockEntity be = level.getBlockEntity(p);
            if (be != null && isStorage(level, p, st, be)) {
                Block.dropResources(st, level, p, be);
                item = true;
            }
            // Loot can be copied to the item as a component. Prevent onRemove from rolling it into loose items.
            if (be instanceof RandomizableContainer r && r.getLootTable() != null) r.setLootTable(null);
        }
        ColumnScar.replaceKeeping(level, p, st, to);
        return item;
    }

    /** Storage can be empty and still carry item components, such as a custom name. */
    private static boolean isStorage(ServerLevel level, BlockPos p, BlockState st, BlockEntity be) {
        if (be instanceof Container) return true;
        for (Direction side : SIDES) {
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, p, st, be, side) != null) return true;
        }
        return false;
    }

    /** A non-vanilla block entity: its removal may read far ({@link StrikeSupport#MODDED_REACH}). */
    private static boolean modded(BlockState st) {
        return st.hasBlockEntity() && !"minecraft".equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace());
    }

    /**
     * A live falling block from {@code from} (already empty): moves sideways so that it comes down where the spread
     * around its column puts it ({@link StrikeSupport#spread}), lands as vanilla places a falling block.
     */
    private void drop(ServerLevel level, BlockPos from, BlockState rubble, BlockPos.MutableBlockPos m) {
        int fall = from.getY() - rest(level, from.getX(), from.getY(), from.getZ(), m);
        double[] off = StrikeSupport.spread(from.getX(), from.getY(), from.getZ(), fall);
        FallingBlockEntity e = FallingBlockEntity.fall(level, from, rubble);
        CraterFalls.markRubble(e, area.rules().debrisStay());
        double ticks = fallTicks(Math.max(1, fall));
        e.setDeltaMovement(off[0] / ticks, 0, off[1] / ticks);
        stats.live++;
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
        if (!area.rules().debrisStay()) {
            landed = landings.size();
            return 0;
        }
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
                    stats.placed++;
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
