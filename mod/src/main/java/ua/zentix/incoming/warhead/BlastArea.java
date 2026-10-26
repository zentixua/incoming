package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.ImpactCost;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Chunks reached by explosion (or entire explosion timeline): vanilla region ticket ({@link AreaLoader}, ticking
 * only with ready neighbors) keeps them fully loaded and loads unready ones in background. Needed because
 * detonating projectile releases its own/target-area tickets: neighbors immediately drop below “ready”, so
 * secondary detonation a few ticks later would read synchronously, waiting inside tick. Ticket unsaved; each area
 * has its own key, so neighboring salvo explosions do not release each other's tickets. Also crater-fall area
 * ({@link CraterFalls}).
 * <p>
 * Multiple holders possible: timeline and its queued impact work units ({@link StagedExplosion}, others), which
 * large salvos finish later than timeline. Last holder releases area ({@link #retain}).
 * <p>
 * Impact units record measurements here ({@link #record}); last holder logs summary: units, explosions, removed
 * blocks, elapsed ticks, longest unit, explosion ray breakdown.
 */
final class BlastArea {
    /** Slow blocks logged per strike. */
    static final int SLOW_BLOCKS_LOGGED = 5;
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_blast", Comparator.<UUID>naturalOrder());

    private final Vec3 centre;
    private final double reach;
    private final ChunkPos chunk;
    /** Ticket level 33 − distance: fully loaded square ±distance chunks around {@link #chunk}. */
    private final int distance;
    private final UUID key = UUID.randomUUID();
    /** Holders not yet releasing area. */
    private int holders = 1;

    /** Strike work summary: units, time, longest, completion by kind, explosion ray steps. */
    private long heldAt;
    private long lastUnitAt;
    private int units;
    private long nanos;
    private long maxUnit;
    private ImpactCost.Kind maxKind = ImpactCost.Kind.RAYS;
    private final int[] done = new int[ImpactCost.Kind.values().length];
    private final long[] rayStages = new long[ExplosionStage.values().length];
    private final StagedExplosion.RayCounts rayCounts = new StagedExplosion.RayCounts();
    /**
     * Unfinished area explosions with queue tick. Same-tick explosions (bunker-buster detonations) select
     * rays/damage entities against untouched world, removing blocks only once all do so ({@link #peersPicking});
     * otherwise later rays see removed blocks inconsistently and crater depends on server throughput.
     */
    private final List<Queued> explosions = new ArrayList<>();
    /** Slow strike blocks ({@link #slowBlock}): log first {@link #SLOW_BLOCKS_LOGGED}. */
    private int slowBlocks;

    private record Queued(StagedExplosion explosion, long tick) {}

    private BlastArea(Vec3 centre, double reach) {
        this.centre = centre;
        this.reach = reach;
        this.chunk = new ChunkPos(Mth.floor(centre.x) >> 4, Mth.floor(centre.z) >> 4);
        int west = chunk.x - (Mth.floor(centre.x - reach) >> 4);
        int east = (Mth.floor(centre.x + reach) >> 4) - chunk.x;
        int north = chunk.z - (Mth.floor(centre.z - reach) >> 4);
        int south = (Mth.floor(centre.z + reach) >> 4) - chunk.z;
        this.distance = Math.max(Math.max(west, east), Math.max(north, south));
    }

    /** Acquire area: everything within {@code reach} of {@code centre} loads/stays ready until {@link #release}. */
    static BlastArea hold(ServerLevel level, Vec3 centre, double reach) {
        BlastArea area = new BlastArea(centre, reach);
        area.heldAt = level.getGameTime();
        StrikeWorld.get(level).areas().hold(level, area.area());
        CraterFalls.get(level).open(level, area.key, centre, reach);
        return area;
    }

    /** Area explosion queued at {@code tick}. */
    void queued(StagedExplosion e, long tick) {
        explosions.removeIf(q -> q.explosion().done());
        explosions.add(new Queued(e, tick));
    }

    /**
     * Same-tick peer of {@code e} still selecting rays/damaging entities: {@code e}'s blocks wait. Exclude
     * explosions waiting entirely for others (fireball/secondary wait for main): they should see removed blocks.
     */
    boolean peersPicking(StagedExplosion e) {
        explosions.removeIf(q -> q.explosion().done());
        long tick = Long.MIN_VALUE;
        for (Queued q : explosions) if (q.explosion() == e) tick = q.tick();
        for (Queued q : explosions) {
            StagedExplosion p = q.explosion();
            if (p != e && q.tick() == tick && p.picking() && !p.waiting()) return true;
        }
        return false;
    }

    /** Another holder (unit may outlive acquirer): release with own {@link #release}. */
    BlastArea retain() {
        if (holders <= 0) throw new IllegalStateException("blast area already released");
        holders++;
        return this;
    }

    /**
     * Strike work {@code kind} took {@code took} ns, completed {@code count} (explosions, blocks, debris): add to
     * world-tick measurement ({@link ImpactCost}) and strike summary.
     */
    void record(ServerLevel level, ImpactCost.Kind kind, long took, int count) {
        record(level, StrikeWorld.get(level).impactCost(), kind, took, count);
    }

    void record(ServerLevel level, ImpactCost cost, ImpactCost.Kind kind, long took, int count) {
        cost.add(kind, took, count);
        units++;
        nanos += took;
        done[kind.ordinal()] += count;
        if (took > maxUnit) {
            maxUnit = took;
            maxKind = kind;
        }
        lastUnitAt = level.getGameTime();
    }

    /** Explosion stages per unit ({@link ExplosionStage}, ns). */
    void recordStages(ServerLevel level, long[] stages) {
        StrikeWorld.get(level).impactCost().addRayStages(stages);
        for (int i = 0; i < stages.length; i++) rayStages[i] += stages[i];
    }

    /**
     * Block removal exceeded {@link StagedExplosion#SLOW_BLOCK_NANOS}: log first {@link #SLOW_BLOCKS_LOGGED} per
     * strike, identifying block holding portion (host 16-block portion reaches 65 ms).
     */
    void slowBlock(ServerLevel level, BlockState state, BlockPos pos, long took) {
        if (slowBlocks++ >= SLOW_BLOCKS_LOGGED) return;
        Incoming.LOG.warn(String.format(Locale.ROOT, "Blast: removing block %s took %.1f ms at %d %d %d (%s, block entity: %s)",
                BuiltInRegistries.BLOCK.getKey(state.getBlock()), took / 1e6, pos.getX(), pos.getY(), pos.getZ(),
                level.dimension().location(), state.hasBlockEntity() ? "yes" : "no"));
    }

    /** Explosion ray counters for strike summary. */
    void recordCounts(StagedExplosion.RayCounts counts) {
        rayCounts.add(counts);
    }

    int slowBlocks() {
        return slowBlocks;
    }

    boolean ready(ServerLevel level) {
        return Terrain.readyAround(level, centre, reach);
    }

    /** Holder releases area; last removes ticket/closes falling. */
    void release(ServerLevel level) {
        if (holders <= 0 || --holders > 0) return;
        StrikeWorld.get(level).areas().release(level, area());
        CraterFalls.get(level).close(level, key);
        // Distant Horizons LOD: crater now, later falls when area stops falling.
        long now = level.getGameTime();
        DhUpdates.markArea(level, centre, reach, now + DhUpdates.SETTLE);
        DhUpdates.markArea(level, centre, reach, now + CraterFalls.GRACE);
        if (units > 0) {
            Incoming.LOG.info(summary(level.dimension().location().toString()));
            StrikeWorld.get(level).impactCost().strikeDone();
        }
    }

    /** Strike summary line (for {@code tools/logscan.py}). */
    String summary(String dimension) {
        StringBuilder rays = new StringBuilder();
        for (ExplosionStage st : ExplosionStage.values()) {
            if (rays.length() > 0) rays.append(", ");
            rays.append(st.label).append(' ').append(ms(rayStages[st.ordinal()]));
        }
        return String.format(Locale.ROOT,
                "Strike summary (%s) at %d %d %d: units %d, explosions %d, blocks removed %d, glass %d, debris %d, over %d ticks, total %s ms, "
                        + "longest unit %s ms (%s); explosion steps: %s ms; rays: steps %d, in air %d, cached %d, "
                        + "aircraft queries %d, vanilla explosions %d; blocks over 10 ms %d",
                dimension, Mth.floor(centre.x), Mth.floor(centre.y), Mth.floor(centre.z), units,
                done[ImpactCost.Kind.RAYS.ordinal()], done[ImpactCost.Kind.BLOCKS.ordinal()], done[ImpactCost.Kind.GLASS.ordinal()],
                done[ImpactCost.Kind.DEBRIS.ordinal()], lastUnitAt - heldAt + 1, ms(nanos), ms(maxUnit), maxKind.label(), rays,
                rayCounts.steps, rayCounts.airSteps, rayCounts.cached, rayCounts.craftQueries, rayCounts.vanilla, slowBlocks);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
    }

    private AreaLoader.Area area() {
        return new AreaLoader.Area(TYPE, chunk, distance, key);
    }

    Vec3 centre() {
        return centre;
    }
}
