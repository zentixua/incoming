package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.StructuralResponse;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Authoritative coarse material released by nuclear structural failure. Cosmetic fragments own no
 * material. A record remains here until the same block state has actually been placed on a support;
 * an unloaded destination, collision or world-height limit cannot silently discard it.
 * Saved separately from the disposable planning cache, including block-entity contents.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = "incoming")
public final class RuinMatter extends SavedData {
    private static final String NAME = "incoming_ruin_matter";
    private static final double DT = 1.0 / 20;
    /** A work unit advances at most this many half-metre trajectory segments. */
    private static final int SEGMENTS = 8;
    private ArrayDeque<Piece> moving = new ArrayDeque<>();
    /** Outside build height: retained material, but no repeated world reads/ticket churn. */
    private record ReservoirKey(BlockState state, CompoundTag contents) {}
    private static final class Reservoir {
        long count;
        double volume, mass;
    }
    private final java.util.Map<ReservoirKey, Reservoir> stranded = new java.util.HashMap<>();
    private int strandedCount;
    private long movingBytes, strandedBytes, supportBacking;
    private int movingCapacity;

    /** Conservative owned representation, including save copies and collection growth slack. */
    long retainedBytes() {
        supportBacking = Math.max(supportBacking, 2048L + supportSources.size() * 4096L
                + supports.size() * 256L + uncertain.size() * 256L + originals.size() * 256L);
        movingCapacity = Math.max(movingCapacity, moving.size());
        return 1024L + movingBytes + strandedBytes + movingCapacity * 32L + supportBacking
                + materials.size() * 512L + (search == null ? 2048 : search.retainedBytes());
    }

    static long removalBytes(Piece piece) { return piece.retainedBytes() + 8192; }


    /** A terminal record has no trajectory to resume. Preserve state, cargo and totals only. */
    private void strand(Piece piece) {
        Reservoir value = stranded.computeIfAbsent(new ReservoirKey(piece.state, piece.contents), ignored -> {
            strandedBytes += 256L + (piece.contents == null ? 0 : 3L * piece.contents.sizeInBytes());
            return new Reservoir();
        });
        value.count++;
        value.volume += piece.volume;
        value.mass += piece.mass;
        strandedCount++;
    }
    private record SupportSeed(BlockPos source, BlockState state, int event) {}
    private ArrayDeque<SupportSeed> supportSources = new ArrayDeque<>();
    private long loadTick = Long.MIN_VALUE;
    private long released, deposited;
    /** Per block type: released, deposited and dispersed solid volume/mass. Moving owns the remainder. */
    private final java.util.Map<net.minecraft.world.level.block.Block, double[]> materials = new java.util.HashMap<>();

    private void account(Piece piece, int destination) {
        double[] balance = materials.computeIfAbsent(piece.state.getBlock(), ignored -> new double[6]);
        balance[destination] += piece.volume;
        balance[destination + 1] += piece.mass;
    }

    double[] balance(net.minecraft.world.level.block.Block block) {
        return materials.getOrDefault(block, new double[6]).clone();
    }

    private double releasedVolume, depositedVolume, releasedMass, depositedMass;
    private long dispersed;
    private double dispersedVolume, dispersedMass;
    private final it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap supports = new it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap();
    /** Only cold edges need to remember the removed face until their neighbour can be inspected. */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState[]> uncertain = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private record Original(BlockState state, int event) {}
    /** One pre-failure graph for all still-pending removals, including overlapping strikes.
     * First removal at a position wins until reconciliation drains; event IDs only attribute it. */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Original> originals = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private RuinSupport search;
    private int heldRadius;
    private int lookupId = -1, lookupIndex, lookupSize;
    private boolean lookupPast, lookupDone;
    private Detonation lookupValue;

    /** A seed never scans an unbounded detonation history in one work unit. A cached position
     * is checked against the live list, so clear/expiry cannot keep a removed event alive. */
    private Detonation event(ua.zentix.incoming.nuclear.NuclearEvents events, int id) {
        var list = lookupPast ? events.past() : events.detonations();
        int size = events.past().size() + events.detonations().size();
        boolean valid = lookupValue != null && lookupIndex < list.size() && list.get(lookupIndex) == lookupValue;
        if (lookupId != id || lookupDone && (!valid || lookupSize != size)) {
            lookupId = id; lookupIndex = 0; lookupPast = false; lookupDone = false; lookupValue = null;
        }
        if (lookupDone) return lookupValue;
        lookupSize = size;
        for (int i = 0; i < 16; i++) {
            list = lookupPast ? events.past() : events.detonations();
            if (lookupIndex >= list.size()) {
                if (lookupPast) { lookupDone = true; return null; }
                lookupPast = true; lookupIndex = 0; continue;
            }
            var value = list.get(lookupIndex);
            if (value.id() == id) { lookupValue = value; lookupDone = true; return value; }
            lookupIndex++;
        }
        return null;
    }


    @net.neoforged.bus.api.SubscribeEvent(receiveCanceled = true)
    public static void onNeighbour(net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            changed(level, event.getPos());
        }
    }

    /** Our batched replacements deliberately omit vanilla neighbour cascades, so notify the
     * continuation explicitly. Other ordinary edits arrive through the loader event above. */
    static void changed(ServerLevel level, BlockPos at) {
        RuinMatter ledger = level.getDataStorage().get(new Factory<>(RuinMatter::new, RuinMatter::load, null), NAME);
        if (ledger != null && ledger.search != null) ledger.search.changed(at);
    }

    BlockState original(ServerLevel level, BlockPos at) {
        Original removed = originals.get(at.asLong());
        return removed == null ? level.getBlockState(at) : removed.state;
    }

    private net.minecraft.world.level.ChunkPos held;

    void support(ServerLevel level, List<Piece> pieces, Detonation d) {
        for (Piece piece : pieces) if (Blast.props(piece.state).bearing()) {
            originals.putIfAbsent(piece.source.asLong(), new Original(piece.state, d.id()));
            supportSources.addLast(new SupportSeed(piece.source, piece.state, d.id()));
        }
        if (!pieces.isEmpty()) setDirty();
    }

    private void expandSupport(ServerLevel level, SupportSeed source) {
        for (var direction : net.minecraft.core.Direction.values()) {
            // A decoration above a platform cannot have carried that platform. Side contacts and
            // the upper face can carry a slab; the lower neighbour is not a support-loss seed.
            if (direction == net.minecraft.core.Direction.DOWN) continue;
            BlockPos next = source.source.relative(direction);
            long key = next.asLong();
            if (Terrain.ready(level, next.getX() >> 4, next.getZ() >> 4)) {
                if (!Blast.props(source.state).touches(direction, Blast.props(level.getBlockState(next)))) continue;
                supports.putIfAbsent(key, source.event);
                uncertain.remove(key);
            } else if (!supports.containsKey(key) || uncertain.containsKey(key)) {
                supports.putIfAbsent(key, source.event);
                uncertain.computeIfAbsent(key, ignored -> new BlockState[6])[direction.ordinal()] = source.state;
            }
        }
    }

    boolean ready(ServerLevel level, BlockPos at, boolean neighbours) {
        boolean loaded = neighbours ? NuclearTickets.aroundLoaded(level, at)
                : Terrain.ready(level, at.getX() >> 4, at.getZ() >> 4);
        return loaded || request(level, at, 1);
    }

    /** Both onRemove and onPlace may traverse a mod's block-entity network. */
    boolean readyToReplace(ServerLevel level, BlockPos at, BlockState old, BlockState placed) {
        int radius = Math.max(RuinPlan.callbackRadius(old), RuinPlan.callbackRadius(placed));
        if (radius == 0) return ready(level, at, true);
        return NuclearTickets.neighbourhoodLoaded(level, new net.minecraft.world.level.ChunkPos(at), radius)
                || request(level, at, radius);
    }

    private boolean request(ServerLevel level, BlockPos at, int radius) {
        if (loadTick == level.getGameTime() || held != null
                && !NuclearTickets.neighbourhoodLoaded(level, held, heldRadius)) return false;
        loadTick = level.getGameTime();
        if (held != null) NuclearTickets.holdMatter(level, held, false, heldRadius);
        held = new net.minecraft.world.level.ChunkPos(at);
        heldRadius = radius;
        NuclearTickets.holdMatter(level, held, true, radius);
        return false;
    }

    void unload(ServerLevel level) {
        if (held != null) NuclearTickets.holdMatter(level, held, false, heldRadius);
        held = null;
        search = null;
    }

    static final class Piece {
        final BlockState state;
        final CompoundTag contents;
        final BlockPos source;
        long id;
        int scorch, scorchEvent;
        long scorchTime;
        final boolean fine;
        boolean outside;
        double volume, mass;
        double x, y, z, vx, vy, vz;
        double tick, releasedTick, windX, windZ, drag, pulse;
        private final long retained;
        long retainedBytes() { return retained; }

        Piece(BlockPos source, BlockState state, CompoundTag contents, Vec3 velocity, long tick) {
            this.source = source.immutable();
            this.state = state;
            this.contents = contents;
            this.retained = 512L + (contents == null ? 0 : 3L * contents.sizeInBytes());
            Blast.Props material = Blast.props(state);
            fine = material.leaves()
                    || state.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS)
                    || state.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_PANES);
            volume = material.volume();
            mass = volume * material.density();
            x = source.getX() + 0.5;
            y = source.getY();
            z = source.getZ() + 0.5;
            vx = velocity.x;
            vy = velocity.y;
            vz = velocity.z;
            this.tick = releasedTick = tick;
        }
    }

    public static RuinMatter get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(RuinMatter::new, RuinMatter::load, null), NAME);
    }

    /** Capture before the plan clears block entities; publish only after successful source replacement. */
    static Piece capture(ServerLevel level, BlockPos at, BlockState state, Detonation d) {
        return capture(level, at, state, d, new RuinScorch(level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4)));
    }

    static Piece capture(ServerLevel level, BlockPos at, BlockState state, Detonation d, RuinScorch scorch) {
        BlockState sourceState = state;
        var props = Blast.props(state);
        if (props.air() || props.fluid() || props.fixed() || !props.bearing() && !props.leaves()) return null;
        CompoundTag contents = null;
        if (state.hasBlockEntity()) {
            var entity = level.getBlockEntity(at);
            if (entity != null) contents = entity.saveWithoutMetadata(level.registryAccess());
        }
        Vec3 p = Vec3.atCenterOf(at);
        // Material family density is an estimate, shared with structural response. A full block
        // remains a full block: no decorative substitution and no unexplained 88% loss.
        double mass = props.density() * props.volume();
        if (!(mass > 0)) return null;
        Vec3 away = new Vec3(p.x - d.burst().x, 0, p.z - d.burst().z).normalize();
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, false);
        if (props.log() && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS))
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS,
                    Math.abs(away.x) > Math.abs(away.z) ? net.minecraft.core.Direction.Axis.X : net.minecraft.core.Direction.Axis.Z);
        Piece piece = new Piece(at, state, contents, Vec3.ZERO, level.getGameTime());
        piece.scorch = scorch.capture(at, sourceState, state, away);
        piece.scorchEvent = d.id(); piece.scorchTime = d.gameTime();
        // Integrate drag while airborne: giving the entire pulse as an instantaneous kick would
        // move a short fall too far and ignore the reduction in relative air speed.
        piece.windX = away.x * d.blastWind(p);
        piece.windZ = away.z * d.blastWind(p);
        piece.drag = d.dynamicPressureKpa(p) * 1000 / mass; // Cd=1, exposed area=1 m² (est.).
        piece.pulse = d.positivePhaseSeconds(p);
        return piece;
    }

    void release(List<Piece> pieces) {
        if (pieces.isEmpty()) return;
        for (Piece piece : pieces) {
            // Ownership is assigned once. Replaying the same handoff cannot duplicate it; the ID
            // and original volume/mass travel through save/reload with the moving record.
            if (piece.id != 0) continue;
            piece.id = ++released;
            account(piece, 0);
            releasedVolume += piece.volume;
            releasedMass += piece.mass;
            if (piece.fine) {
                // Detached foliage and shattered glazing do not remain rigid metre-wide blocks.
                // Preserve their material in the dispersed reservoir; existing fragments show it.
                account(piece, 4);
                dispersed++;
                dispersedVolume += piece.volume;
                dispersedMass += piece.mass;
            } else {
                moving.addLast(piece);
                movingCapacity = Math.max(movingCapacity, moving.size());
                movingBytes += piece.retainedBytes();
            }
        }
        setDirty();
    }

    void resolved(it.unimi.dsi.fastutil.longs.LongSet positions) {
        for (var iterator = positions.iterator(); iterator.hasNext();) {
            long position = iterator.nextLong();
            supports.remove(position);
            uncertain.remove(position);
        }
    }

    int pendingSupport() { return supports.size() + supportSources.size(); }
    boolean validatingSupport() { return search != null && search.validating(); }
    boolean releasingSupport() { return search != null && search.releasing(); }

    boolean busy() { return !moving.isEmpty() || !supports.isEmpty() || !supportSources.isEmpty(); }

    public long released() { return released; }
    public long deposited() { return deposited; }
    public int pending() { return moving.size() + strandedCount; }
    public double releasedVolume() { return releasedVolume; }
    public double depositedVolume() { return depositedVolume; }

    /** Active material still travelling from a source volume, for diagnostics and phase-order checks. */
    public int pendingFrom(BlockPos minimum, BlockPos maximum) {
        int count = 0;
        for (Piece p : allPending()) if (p.source.getX() >= minimum.getX() && p.source.getX() <= maximum.getX()
                && p.source.getY() >= minimum.getY() && p.source.getY() <= maximum.getY()
                && p.source.getZ() >= minimum.getZ() && p.source.getZ() <= maximum.getZ()) count++;
        return count;
    }

    /** No world reads without getChunkNow readiness, and no whole-pile work in one tick unit. */
    void work(ServerLevel level, WorkClock clock) {
        int left = moving.size();
        boolean waiting = false;
        while ((left > 0 || !supportSources.isEmpty() || !supports.isEmpty() && !waiting) && clock.canStart()) {
            long began = clock.begin();
            for (int i = 0; i < 16 && !supportSources.isEmpty(); i++) expandSupport(level, supportSources.removeFirst());
            if (!supports.isEmpty() && !waiting) {
                long seed = supports.firstLongKey();
                int id = supports.get(seed);
                BlockState[] faces = uncertain.get(seed);
                if (faces != null) {
                    BlockPos at = BlockPos.of(seed);
                    if (!ready(level, at, false)) {
                        waiting = true;
                        clock.end(began);
                        continue;
                    }
                    Blast.Props neighbour = Blast.props(level.getBlockState(at));
                    boolean connected = false;
                    for (var direction : net.minecraft.core.Direction.values()) {
                        BlockState source = faces[direction.ordinal()];
                        if (source != null && Blast.props(source).touches(direction, neighbour)) connected = true;
                    }
                    uncertain.remove(seed);
                    if (!connected) {
                        supports.remove(seed);
                        clock.end(began);
                        setDirty();
                        continue;
                    }
                }
                var events = ua.zentix.incoming.nuclear.NuclearEvents.get(level);
                Detonation d = event(events, id);
                if (d != null) {
                    // A graph step adds at most sixteen cells and their six neighbours. Refusal
                    // keeps the saved seed/provenance intact; transport can still free capacity.
                    try (var reservation = RuinMemory.reserve(level, 32L << 10)) {
                        if (reservation == null) waiting = true;
                        else {
                            if (search == null) search = new RuinSupport(seed);
                            if (search.step(level, this, d)) {
                                supports.remove(seed);
                                search = null;
                            } else waiting = search.waiting;
                        }
                    }
                } else if (lookupDone) {
                    supports.remove(seed);
                    search = null;
                }
            }
            // A unit is a bounded batch, not a whole chunk and not a synthetic millisecond per
            // block on counting clocks. Support and moving material both make progress in it.
            for (int i = 0; i < 16 && left > 0 && !moving.isEmpty(); i++, left--) {
                Piece piece = moving.removeFirst();
                if (step(level, piece)) {
                    movingBytes -= piece.retainedBytes();
                    account(piece, 2);
                    deposited++;
                    depositedVolume += piece.volume;
                    depositedMass += piece.mass;
                } else if (piece.outside) {
                    movingBytes -= piece.retainedBytes();
                    strand(piece);
                }
                else moving.addLast(piece);
            }
            clock.end(began);
            setDirty();
        }
        if (supports.isEmpty() && supportSources.isEmpty() && !originals.isEmpty()) {
            // All affected components have been reconciled, including cold edges. A later failure
            // starts from that settled world; completed contact journals are not permanent history.
            originals.clear(); originals.trim();
            supports.trim(); uncertain.trim();
            supportBacking = 2048;
            setDirty();
        }
        if (moving.isEmpty() && movingCapacity > 0) { moving = new ArrayDeque<>(); movingCapacity = 0; }
        if (supports.isEmpty() && supportSources.isEmpty() && originals.isEmpty()) supportSources = new ArrayDeque<>();
        if (moving.isEmpty() && supports.isEmpty() && supportSources.isEmpty()) unload(level);
    }

    private boolean step(ServerLevel level, Piece p) {
        if (p.tick >= level.getGameTime()) return false;
        // A high velocity is sliced rather than tunnelling through a wall. Keep fractional elapsed
        // time, so multiple continuation units cannot simulate more time than has actually passed.
        for (int i = 0; i < SEGMENTS && p.tick < level.getGameTime(); i++) {
            double speed = Math.sqrt(p.vx * p.vx + p.vy * p.vy + p.vz * p.vz);
            double dt = Math.min(Math.min(DT, (level.getGameTime() - p.tick) * DT), 0.5 / Math.max(1, speed));
            double wind = Math.hypot(p.windX, p.windZ), rx = p.windX - p.vx, rz = p.windZ - p.vz;
            double age = (p.tick - p.releasedTick) * DT;
            double drag = p.pulse > 0 && wind > 0 ? p.drag * Math.max(0, 1 - age / p.pulse)
                    * Math.hypot(rx, rz) / (wind * wind) : 0;
            double ax = drag * rx, az = drag * rz;
            double acceleration = Math.sqrt(ax * ax + az * az + StructuralResponse.GRAVITY * StructuralResponse.GRAVITY);
            dt = Math.min(dt, 1 / (speed + Math.sqrt(speed * speed + acceleration)));
            if (drag > 0) dt = Math.min(dt, 0.25 / drag); // Resolve relative drag without overshooting the carrier flow.
            double nx = p.x + p.vx * dt + ax * dt * dt / 2,
                    ny = p.y + p.vy * dt - StructuralResponse.GRAVITY * dt * dt / 2,
                    nz = p.z + p.vz * dt + az * dt * dt / 2;
            BlockPos to = BlockPos.containing(nx, ny, nz);
            if (to.getY() < level.getMinBuildHeight() || to.getY() >= level.getMaxBuildHeight()) {
                p.outside = true;
                return false;
            }
            if (!ready(level, to, false)) return false;
            BlockState obstacle = level.getBlockState(to);
            if (!free(obstacle) || !obstacle.getCollisionShape(level, to).isEmpty()) {
                // Collision dissipates normal momentum. The remaining piece falls beside the face;
                // a horizontal surface may receive it only when the destination cell is empty.
                BlockPos here = BlockPos.containing(p.x, p.y, p.z);
                if (!ready(level, here, true)) return false;
                if (!level.getBlockState(here).getCollisionShape(level, here).isEmpty()) {
                    // Another piece settled in this cell since our preceding slice. Resolve the
                    // overlap upward, then let the same repose rule spill it onto the pile's side.
                    p.y = here.getY() + 1;
                    p.vx = p.vy = p.vz = 0;
                    return settle(level, p, here.above());
                }
                p.vx = 0;
                p.vz = 0;
                // A wall removes horizontal momentum, not elapsed time or gravity. Otherwise
                // each retry receives the same drag pulse and a piece sticks forever to the wall.
                BlockPos vertical = BlockPos.containing(p.x, ny, p.z);
                if (free(level.getBlockState(vertical)) && level.getBlockState(vertical).getCollisionShape(level, vertical).isEmpty()) {
                    p.y = ny;
                    p.vy -= StructuralResponse.GRAVITY * dt;
                } else if (ny < p.y) return settle(level, p, here);
                else p.vy = Math.min(0, p.vy);
                p.tick += dt / DT;
                return false;
            }
            p.x = nx;
            p.y = ny;
            p.z = nz;
            p.vx += ax * dt;
            p.vz += az * dt;
            p.vy -= StructuralResponse.GRAVITY * dt;
            p.tick += dt / DT;
        }
        return false;
    }

    private static boolean free(BlockState state) {
        Blast.Props props = Blast.props(state);
        return state.isAir() || props.fluid() || props.is(Blast.Props.REPLACEABLE)
                || !props.bearing() && !props.leaves() && !props.fixed() && !state.hasBlockEntity();
    }

    private boolean settle(ServerLevel level, Piece p, BlockPos at) {
        if (!ready(level, at, true) || at.getY() >= level.getMaxBuildHeight()) return false;
        BlockState old = level.getBlockState(at), below = level.getBlockState(at.below());
        if (!free(old)) {
            // Another owner occupied this cell during flight. Climb out of that occupied cell;
            // do not erase a protected block or another block entity's inventory.
            p.y = at.getY() + 1;
            p.vx = p.vy = p.vz = 0;
            p.tick = level.getGameTime();
            return false;
        }
        // An occupied non-replaceable cell is also a discrete placement obstruction, even if its
        // collision shape is empty. This is the same cell exclusion used above, not a retry loop.
        if (free(below) && below.getCollisionShape(level, at.below()).isEmpty()) return false;
        // A one-block step is the estimated repose condition of coarse block-sized debris. A loose
        // column spills to a lower adjacent cell instead of building an arbitrarily tall needle.
        var directions = net.minecraft.core.Direction.Plane.HORIZONTAL.stream().toArray(net.minecraft.core.Direction[]::new);
        int offset = Math.floorMod(Long.hashCode(p.source.asLong()), directions.length);
        for (int k = 0; k < directions.length; k++) {
            var direction = directions[(k + offset) % directions.length];
            BlockPos side = at.relative(direction);
            if (!ready(level, side, true)) return false;
            if (free(level.getBlockState(side)) && free(level.getBlockState(side.below()))) {
                p.x = side.getX() + 0.5;
                p.y = side.getY();
                p.z = side.getZ() + 0.5;
                p.vx = p.vy = p.vz = 0;
                return false;
            }
        }
        BlockState placed = p.state;
        if (placed.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))
            placed = placed.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,
                    old.getFluidState().is(net.minecraft.tags.FluidTags.WATER));
        if (!readyToReplace(level, at, old, placed)) return false;
        // Non-colliding decorations yield the destination, with their ordinary drops exactly once.
        // A block entity or protected block is never consumed by this displacement rule.
        boolean decoration = !old.isAir() && !Blast.props(old).fluid() && !old.canBeReplaced();
        ColumnScar.replace(level, at, old, placed);
        if (level.getBlockState(at) != placed) return false;
        if (decoration) net.minecraft.world.level.block.Block.dropResources(old, level, at);
        if (p.contents != null) {
            var entity = level.getBlockEntity(at);
            if (entity != null) {
                entity.loadWithComponents(p.contents, level.registryAccess());
                entity.setChanged();
            }
        }
        if (p.scorch != 0) ua.zentix.incoming.nuclear.flash.FlashSync.movedChars(level, at, p.scorchEvent, p.scorchTime, p.scorch, placed);
        DhUpdates.mark(level, new net.minecraft.world.level.ChunkPos(at), level.getGameTime());
        return true;
    }

    private Iterable<Piece> allPending() {
        return moving;
    }

    static RuinMatter load(CompoundTag tag, HolderLookup.Provider lookup) {
        RuinMatter result = new RuinMatter();
        result.released = tag.getLong("released");
        result.deposited = tag.getLong("deposited");
        result.releasedVolume = tag.getDouble("released_volume");
        result.depositedVolume = tag.getDouble("deposited_volume");
        result.releasedMass = tag.getDouble("released_mass");
        result.depositedMass = tag.getDouble("deposited_mass");
        result.dispersed = tag.getLong("dispersed");
        result.dispersedVolume = tag.getDouble("dispersed_volume");
        result.dispersedMass = tag.getDouble("dispersed_mass");
        ListTag materialTags = tag.getList("materials", Tag.TAG_COMPOUND);
        for (int i = 0; i < materialTags.size(); i++) {
            CompoundTag row = materialTags.getCompound(i);
            var id = net.minecraft.resources.ResourceLocation.parse(row.getString("block"));
            var block = lookup.lookupOrThrow(Registries.BLOCK).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.BLOCK, id)).value();
            double[] balance = new double[6];
            for (int k = 0; k < balance.length; k++) balance[k] = row.getDouble("v" + k);
            result.materials.put(block, balance);
        }
        ListTag originalTags = tag.getList("support_originals", Tag.TAG_COMPOUND);
        for (int i = 0; i < originalTags.size(); i++) {
            CompoundTag row = originalTags.getCompound(i);
            Original removed = new Original(NbtUtils.readBlockState(lookup.lookupOrThrow(Registries.BLOCK), row.getCompound("state")), row.getInt("event"));
            // Older per-event saves may repeat a position in unspecified map order. Event IDs
            // increase with registration; retain the earliest strike's contact when importing them.
            result.originals.merge(row.getLong("position"), removed, (a, b) -> a.event <= b.event ? a : b);
        }
        ListTag sourceTags = tag.getList("support_sources", Tag.TAG_COMPOUND);
        for (int i = 0; i < sourceTags.size(); i++) {
            CompoundTag n = sourceTags.getCompound(i);
            result.supportSources.addLast(new SupportSeed(BlockPos.of(n.getLong("position")),
                    NbtUtils.readBlockState(lookup.lookupOrThrow(Registries.BLOCK), n.getCompound("state")), n.getInt("event")));
        }
        long[] seeds = tag.getLongArray("supports");
        int[] detonations = tag.getIntArray("support_events");
        if (seeds.length != detonations.length) throw new IllegalStateException("Invalid support continuation");
        for (int i = 0; i < seeds.length; i++) result.supports.put(seeds[i], detonations[i]);
        ListTag cold = tag.getList("cold_edges", Tag.TAG_COMPOUND);
        for (int i = 0; i < cold.size(); i++) {
            CompoundTag edge = cold.getCompound(i);
            BlockState[] faces = new BlockState[6];
            for (int face = 0; face < faces.length; face++) if (edge.contains("face_" + face))
                faces[face] = NbtUtils.readBlockState(lookup.lookupOrThrow(Registries.BLOCK), edge.getCompound("face_" + face));
            result.uncertain.put(edge.getLong("position"), faces);
        }
        ListTag list = tag.getList("moving", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag n = list.getCompound(i);
            BlockState state = NbtUtils.readBlockState(lookup.lookupOrThrow(Registries.BLOCK), n.getCompound("state"));
            Piece p = new Piece(BlockPos.of(n.getLong("source")), state,
                    n.contains("contents") ? n.getCompound("contents") : null,
                    new Vec3(n.getDouble("vx"), n.getDouble("vy"), n.getDouble("vz")), n.getLong("tick"));
            p.x = n.getDouble("x"); p.y = n.getDouble("y"); p.z = n.getDouble("z");
            p.tick = n.getDouble("tick");
            p.releasedTick = n.getDouble("released_tick");
            p.windX = n.getDouble("wind_x"); p.windZ = n.getDouble("wind_z");
            p.drag = n.getDouble("drag"); p.pulse = n.getDouble("pulse");
            p.id = n.getLong("id");
            p.scorch = n.getInt("scorch"); p.scorchEvent = n.getInt("scorch_event"); p.scorchTime = n.getLong("scorch_time");
            p.volume = n.getDouble("volume");
            p.mass = n.getDouble("mass");
            p.outside = n.getBoolean("outside");
            if (p.outside) result.strand(p);
            else {
                result.moving.addLast(p);
                result.movingBytes += p.retainedBytes();
            }
        }
        ListTag reservoirs = tag.getList("stranded", Tag.TAG_COMPOUND);
        for (int i = 0; i < reservoirs.size(); i++) {
            CompoundTag row = reservoirs.getCompound(i);
            ReservoirKey key = new ReservoirKey(NbtUtils.readBlockState(lookup.lookupOrThrow(Registries.BLOCK), row.getCompound("state")),
                    row.contains("contents") ? row.getCompound("contents") : null);
            Reservoir value = result.stranded.computeIfAbsent(key, ignored -> {
                result.strandedBytes += 256L + (key.contents == null ? 0 : 3L * key.contents.sizeInBytes());
                return new Reservoir();
            });
            long count = row.getLong("count");
            if (count <= 0) throw new IllegalStateException("Invalid stranded material count");
            value.count += count;
            value.volume += row.getDouble("volume"); value.mass += row.getDouble("mass");
            result.strandedCount = Math.addExact(result.strandedCount, Math.toIntExact(count));
        }
        if (result.released != result.deposited + result.dispersed + result.pending())
            throw new IllegalStateException("Nuclear material ledger does not balance");
        double volume = result.depositedVolume + result.dispersedVolume;
        double mass = result.depositedMass + result.dispersedMass;
        for (Piece p : result.allPending()) { volume += p.volume; mass += p.mass; }
        for (Reservoir reservoir : result.stranded.values()) { volume += reservoir.volume; mass += reservoir.mass; }
        if (!Double.isFinite(volume) || !Double.isFinite(mass)
                || Math.abs(volume - result.releasedVolume) > 1e-9 * Math.max(1, volume)
                || Math.abs(mass - result.releasedMass) > 1e-9 * Math.max(1, mass))
            throw new IllegalStateException("Nuclear material volume/mass ledger does not balance");
        var pending = new java.util.HashMap<net.minecraft.world.level.block.Block, double[]>();
        for (Piece p : result.allPending()) {
            double[] values = pending.computeIfAbsent(p.state.getBlock(), ignored -> new double[2]);
            values[0] += p.volume; values[1] += p.mass;
        }
        for (var row : result.stranded.entrySet()) {
            double[] values = pending.computeIfAbsent(row.getKey().state.getBlock(), ignored -> new double[2]);
            values[0] += row.getValue().volume; values[1] += row.getValue().mass;
        }
        for (var row : result.materials.entrySet()) for (int k = 0; k < 2; k++) {
            double[] values = row.getValue();
            double accounted = values[2 + k] + values[4 + k] + pending.getOrDefault(row.getKey(), new double[2])[k];
            if (!Double.isFinite(accounted) || Math.abs(values[k] - accounted) > 1e-9 * Math.max(1, accounted))
                throw new IllegalStateException("Unbalanced nuclear material: " + row.getKey());
        }
        return result;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        ListTag materialTags = new ListTag();
        for (var entry : materials.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString());
            for (int k = 0; k < entry.getValue().length; k++) row.putDouble("v" + k, entry.getValue()[k]);
            materialTags.add(row);
        }
        tag.put("materials", materialTags);
        tag.putLong("released", released);
        tag.putLong("deposited", deposited);
        tag.putDouble("released_volume", releasedVolume);
        tag.putDouble("deposited_volume", depositedVolume);
        tag.putDouble("released_mass", releasedMass);
        tag.putDouble("deposited_mass", depositedMass);
        tag.putLong("dispersed", dispersed);
        tag.putDouble("dispersed_volume", dispersedVolume);
        tag.putDouble("dispersed_mass", dispersedMass);
        ListTag originalTags = new ListTag();
        for (var entry : originals.long2ObjectEntrySet()) {
            CompoundTag row = new CompoundTag();
            row.putInt("event", entry.getValue().event); row.putLong("position", entry.getLongKey());
            row.put("state", NbtUtils.writeBlockState(entry.getValue().state)); originalTags.add(row);
        }
        tag.put("support_originals", originalTags);
        ListTag sourceTags = new ListTag();
        for (SupportSeed source : supportSources) {
            CompoundTag n = new CompoundTag();
            n.putLong("position", source.source.asLong()); n.putInt("event", source.event);
            n.put("state", NbtUtils.writeBlockState(source.state)); sourceTags.add(n);
        }
        tag.put("support_sources", sourceTags);
        tag.putLongArray("supports", supports.keySet().toLongArray());
        tag.putIntArray("support_events", supports.values().toIntArray());
        ListTag cold = new ListTag();
        for (var entry : uncertain.long2ObjectEntrySet()) {
            CompoundTag edge = new CompoundTag();
            edge.putLong("position", entry.getLongKey());
            BlockState[] faces = entry.getValue();
            for (int face = 0; face < faces.length; face++) if (faces[face] != null)
                edge.put("face_" + face, NbtUtils.writeBlockState(faces[face]));
            cold.add(edge);
        }
        tag.put("cold_edges", cold);
        ListTag list = new ListTag();
        for (Piece p : allPending()) {
            CompoundTag n = new CompoundTag();
            n.putBoolean("outside", p.outside);
            n.putLong("source", p.source.asLong());
            n.putLong("id", p.id);
            n.putInt("scorch", p.scorch); n.putInt("scorch_event", p.scorchEvent); n.putLong("scorch_time", p.scorchTime);
            n.putDouble("volume", p.volume);
            n.putDouble("mass", p.mass);
            n.put("state", NbtUtils.writeBlockState(p.state));
            if (p.contents != null) n.put("contents", p.contents.copy());
            n.putDouble("x", p.x); n.putDouble("y", p.y); n.putDouble("z", p.z);
            n.putDouble("vx", p.vx); n.putDouble("vy", p.vy); n.putDouble("vz", p.vz);
            n.putDouble("tick", p.tick);
            n.putDouble("released_tick", p.releasedTick);
            n.putDouble("wind_x", p.windX); n.putDouble("wind_z", p.windZ);
            n.putDouble("drag", p.drag); n.putDouble("pulse", p.pulse);
            list.add(n);
        }
        tag.put("moving", list);
        ListTag reservoirs = new ListTag();
        for (var entry : stranded.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.put("state", NbtUtils.writeBlockState(entry.getKey().state));
            if (entry.getKey().contents != null) row.put("contents", entry.getKey().contents.copy());
            Reservoir value = entry.getValue();
            row.putLong("count", value.count);
            row.putDouble("volume", value.volume); row.putDouble("mass", value.mass);
            reservoirs.add(row);
        }
        tag.put("stranded", reservoirs);
        return tag;
    }
}
