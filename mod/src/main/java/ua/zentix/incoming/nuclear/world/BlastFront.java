package ua.zentix.incoming.nuclear.world;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModParticles;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Shock front on entities and craft (DESIGN-nuke §4): ring between previous and current radii hits
 * body ({@link BlastBody}: lungs, throw/impact, glass); after positive phase, slight reverse wind toward center
 * (only bodies reached by direct wind: absent in house without blast-facing window or bunker); bodies buried by chunk
 * ruins receive burial injury (also {@link BlastBody}). Chunk ruins appear one tick before front: nearby bodies remember window and wall
 * as front will find them ({@link #remember}).
 * <p>
 * Front position computed each tick (entity snapshot in circle takes fractions of a millisecond); hits on those
 * caught are queued under shared budget ({@link WorkClock}), nearest first. Hit means damage, damage means death,
 * loot and every pack mod's death handlers: near epicenter wave catches hundreds of mobs in one tick, costing
 * a hundred milliseconds at once (host: 84 ms on detonation tick). Queue waits until this detonation's light
 * pulse passes: light precedes wave, so wave-killed bodies already received burns and dose.
 * <p>
 * Create Aeronautics craft (DESIGN §3.6) receive vanilla explosion at craft point nearest epicenter:
 * Sable carries explosion rays through craft blocks, breaking hull and pushing body. Explosion also queued
 * under budget (no light needed, so no wait), breaking only craft blocks (ground handled by {@link ScarQueue}
 * by pressure), ignoring entities (wave hits them directly by pressure), honoring {@code block_damage}.
 */
final class BlastFront {
    /** Minimum pressure for wave hit on craft. */
    private static final double AIRCRAFT_PSI = 3;
    /**
     * Maximum explosion strength against craft (DESIGN §3.6). Near epicenter craft must break apart: strength 16 breaks
     * less than half as much of a 17×3×17 plank hull as 26 (97 blocks versus 224). Explosion breaks only craft
     * blocks, so cost is hull rays; one budget unit per craft.
     */
    private static final float MAX_AIRCRAFT_POWER = 60;

    /**
     * @param owner  launcher (credited for damage), null if unknown
     * @param buried burial: ruins buried body ({@link BlastBody#bury}), otherwise front hit
     * @param before pre-ruin window and wall, adjusted for body movement by front arrival; null if not remembered
     */
    private record Hit(Detonation d, LivingEntity entity, @Nullable UUID owner, boolean buried, BlastBody.@Nullable Before before) {}

    /** Craft explosion: nearest point to epicenter when front catches craft. */
    private record AircraftHit(Detonation d, Vec3 at, float power) {}

    /**
     * Radius already processed for entities, blocks: direct front and reverse wind. Unsaved: restart
     * (or new {@code BlastFront}) during wave starts from previous tick's front; those already inside
     * are not hit again.
     */
    private final Map<Integer, double[]> reached = new HashMap<>();
    /** Launcher by detonation ID while front travels. Unknown after restart: damage without owner. */
    private final Map<Integer, UUID> owners = new HashMap<>();
    /** Bodies' window and wall (detonation ID then entity ID) as front found them: before chunk ruins. */
    private final Map<Integer, Int2ObjectMap<BlastBody.Before>> before = new HashMap<>();
    /** Entities reached by this detonation's wind (IDs): only these receive reverse wind. */
    private final Map<Integer, IntSet> windy = new HashMap<>();
    /**
     * Entities already caught by this detonation's front (IDs): never hit twice; thrown body can overtake
     * slow front and end up ahead again.
     */
    private final Map<Integer, IntSet> struck = new HashMap<>();
    /** Queued front hits: detonation and entity ID ({@link #key}). */
    private final LongOpenHashSet queued = new LongOpenHashSet();
    /** Entities caught by front awaiting hit: by arrival tick, nearest first within tick. */
    private final ArrayDeque<Hit> hits = new ArrayDeque<>();
    /** Craft caught by front awaiting explosion: by arrival tick. */
    private final ArrayDeque<AircraftHit> aircraft = new ArrayDeque<>();

    /** Hits awaiting budget. */
    int pending() {
        return hits.size() + aircraft.size();
    }

    /** Front traveling or hits queued (common-budget NUCLEAR lane). */
    boolean busy() {
        return !reached.isEmpty() || !hits.isEmpty() || !aircraft.isEmpty();
    }

    /** Detonation: remember launcher. */
    void onDetonation(Detonation d, @Nullable UUID owner) {
        if (owner != null) owners.put(d.id(), owner);
    }

    /** This tick's front: queue caught entities/craft, apply reverse wind immediately (cheap). */
    void advance(ServerLevel level, List<Detonation> detonations, long now) {
        Set<Integer> active = new HashSet<>();
        for (Detonation d : detonations) {
            long since = now - d.gameTime();
            double reach = d.blastReach();
            if (since >= 0 && since <= d.arrivalTicks(reach) + d.positivePhaseTicks(reach) + 2) {
                advance(level, d, since);
                active.add(d.id());
            }
        }
        reached.keySet().retainAll(active);
        owners.keySet().retainAll(active);
        before.keySet().retainAll(active);
        windy.keySet().retainAll(active);
        struck.keySet().retainAll(active);
    }

    private void advance(ServerLevel level, Detonation d, long since) {
        double[] done = reached.computeIfAbsent(d.id(), k -> new double[]{d.frontRadius(since - 1), d.phaseEndRadius(since - 1)});
        double r = d.frontRadius(since);
        double back = d.phaseEndRadius(since);
        if (r <= done[0] && back <= done[1]) return;
        // snapshot list: hit can kill mob and add loot; never mutate live entity map during iteration
        double reach = Math.max(r, back);
        List<LivingEntity> ring = new ArrayList<>();
        IntSet hitBy = struck.computeIfAbsent(d.id(), k -> new IntOpenHashSet());
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(d.burst(), reach * 2, reach * 2, reach * 2))) {
            if (!living.isAlive() || living.isSpectator() || living.isPassenger()) continue;
            double dist = living.position().distanceTo(d.burst());
            if (dist >= done[0] && dist < r) {
                if (hitBy.add(living.getId())) ring.add(living);
            } else if (dist >= done[1] && dist < back) suck(level, d, living);
        }
        ring.sort(Comparator.comparingDouble(e -> e.distanceToSqr(d.burst())));
        Int2ObjectMap<BlastBody.Before> seen = before.get(d.id());
        for (LivingEntity e : ring) {
            // body movement from remembered window/wall position is measured at front arrival, not hit: hit awaits pulse and budget
            BlastBody.Before b = seen == null ? null : seen.remove(e.getId());
            hits.add(new Hit(d, e, owners.get(d.id()), false, b == null ? null : b.drifted(e.position())));
            queued.add(key(d, e));
        }
        sweepAircraft(level, d, done[0], r);
        done[0] = Math.max(done[0], r);
        done[1] = Math.max(done[1], back);
    }

    /**
     * Process queued hits while budget permits.
     *
     * @param lit light pulse already passed for this detonation ID
     */
    void work(ServerLevel level, WorkClock clock, IntPredicate lit) {
        // craft first: explosion where craft met front before it flies away
        while (!aircraft.isEmpty() && clock.canStart()) {
            long t0 = clock.begin();
            blast(level, aircraft.removeFirst());
            clock.end(t0);
        }
        while (!hits.isEmpty() && lit.test(hits.peekFirst().d().id()) && clock.canStart()) {
            Hit h = hits.removeFirst();
            if (!h.buried()) queued.remove(key(h.d(), h.entity()));
            // mob may have died, unloaded or boarded a boat while queued, same checks as snapshot
            LivingEntity e = h.entity();
            if (!e.isAlive() || e.isSpectator() || e.isPassenger()) continue;
            long t0 = clock.begin();
            Entity owner = h.owner() == null ? null : level.getPlayerByUUID(h.owner());
            if (h.buried()) BlastBody.bury(level, h.d(), e, owner);
            else if (!e.getData(ModAttachments.NUKE_SEEN).blasted(h.d().id())) {
                // once per body and detonation: loading it later brings no second wave, and a body loaded on the tick the front
                // crosses it and caught up first (LateExposure) is not hit again
                e.setData(ModAttachments.NUKE_SEEN, e.getData(ModAttachments.NUKE_SEEN).withBlast(h.d().id()));
                // ruins appeared while hit waited: remembered after front arrival
                Int2ObjectMap<BlastBody.Before> seen = before.get(h.d().id());
                BlastBody.Before late = h.before() != null || seen == null ? null : seen.remove(e.getId());
                BlastBody.Before b = h.before() != null ? h.before() : late == null ? null : late.drifted(e.position());
                if (BlastBody.hit(level, h.d(), e, owner, b)) windy.computeIfAbsent(h.d().id(), k -> new IntOpenHashSet()).add(e.getId());
            }
            clock.end(t0);
        }
    }

    /**
     * Chunk ruins about to appear: bodies in/near it (window out to {@link BlastBody#GLASS_REACH} may be in this
     * chunk), not yet reached by front or with queued hit, remember window and wall. First remembered world
     * is least disturbed.
     */
    void remember(ServerLevel level, Detonation d, LevelChunk chunk) {
        double[] done = reached.get(d.id());
        if (done == null) return;
        Int2ObjectMap<BlastBody.Before> seen = before.computeIfAbsent(d.id(), k -> new Int2ObjectOpenHashMap<>());
        int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
        double r = BlastBody.GLASS_REACH + 1;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(x - r, level.getMinBuildHeight(), z - r, x + 16 + r, level.getMaxBuildHeight(), z + 16 + r))) {
            if (!e.isAlive() || e.isSpectator() || e.isPassenger() || seen.containsKey(e.getId())) continue;
            // front already passed: hit applied or queued with old world; remembering it now is needed then
            if (e.position().distanceTo(d.burst()) < done[0] && !queued.contains(key(d, e))) continue;
            if (d.overpressureKpa(e.position()) < Detonation.HIT_KPA) continue;
            seen.put(e.getId(), BlastBody.before(level, d, e));
        }
    }

    /** Ruins buried body: queue burial injury after this tick's front hits. */
    void bury(Detonation d, LivingEntity e) {
        hits.add(new Hit(d, e, owners.get(d.id()), true, null));
    }

    /** Cancel. */
    void clear() {
        reached.clear();
        owners.clear();
        before.clear();
        windy.clear();
        struck.clear();
        dropHits();
    }

    /** Clear queued hits; keep front reach (otherwise everyone inside would be hit again). */
    void dropHits() {
        hits.clear();
        queued.clear();
        aircraft.clear();
    }

    private static long key(Detonation d, LivingEntity e) {
        return (long) d.id() << 32 | e.getId() & 0xFFFFFFFFL;
    }

    /**
     * Reverse wind: air returns toward epicenter, drawing bodies reached by direct wind. Hit still
     * queued (awaiting pulse and budget): wind based on sky above body.
     */
    private void suck(ServerLevel level, Detonation d, LivingEntity e) {
        double psi = d.psi(e.position());
        if (psi < 1) return;
        IntSet blown = windy.get(d.id());
        boolean wind = blown != null && blown.contains(e.getId())
                || queued.contains(key(d, e)) && BlastBody.windAt(level, e.getEyePosition());
        if (!wind) return;
        Vec3 to = new Vec3(d.burst().x - e.getX(), 0, d.burst().z - e.getZ());
        if (to.lengthSqr() < 1e-4) return;
        to = to.normalize().scale(Math.min(0.6, 0.08 * psi));
        e.push(to.x, 0.05, to.z);
        e.hurtMarked = true;
    }

    /** Queue craft whose nearest epicenter point is inside this tick's front ring and under at least 3 psi. */
    private void sweepAircraft(ServerLevel level, Detonation d, double from, double to) {
        if (to <= from) return;
        for (SubLevelAccess sub : SubLevels.near(level, d.burst(), to)) {
            AABB box = sub.boundingBox().toMojang();
            Vec3 nearest = new Vec3(Mth.clamp(d.burst().x, box.minX, box.maxX), Mth.clamp(d.burst().y, box.minY, box.maxY),
                    Mth.clamp(d.burst().z, box.minZ, box.maxZ));
            double dist = nearest.distanceTo(d.burst());
            if (dist < from || dist >= to) continue;
            double psi = d.psi(nearest);
            if (psi < AIRCRAFT_PSI) continue;
            aircraft.add(new AircraftHit(d, nearest, (float) Mth.clamp(6 + (psi - AIRCRAFT_PSI) * 1.15, 6, MAX_AIRCRAFT_POWER)));
        }
    }

    /**
     * Craft explosion. Without {@code block_damage}, no destruction ({@code NONE}): Sable still pushes craft
     * along explosion rays. Vanilla explosion rays read blocks: ready chunks only. Drops decay as for
     * warhead explosions ({@code BLOCK}: {@code blockExplosionDropDecay} rule).
     */
    private static void blast(ServerLevel level, AircraftHit a) {
        boolean blocks = IncomingConfig.SERVER.nukeBlockDamage.get();
        Warheads.whenReady(level, a.at(), Warheads.reach(a.power()), l -> l.explode(null,
                ModDamageTypes.source(l, ModDamageTypes.NUCLEAR_BLAST, null, null), new AircraftOnly(l),
                a.at().x, a.at().y, a.at().z, a.power(), false,
                blocks ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE,
                ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT));
    }

    /**
     * Craft explosion breaks only craft blocks (in Sable plots) and leaves entities untouched: no damage
     * or throw; wave already hits entities by pressure ({@link #hit}).
     */
    private static final class AircraftOnly extends ExplosionDamageCalculator {
        private final Level level;

        AircraftOnly(Level level) {
            this.level = level;
        }

        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter reader, BlockPos pos, BlockState state, float power) {
            return !state.isAir() && SubLevels.isInPlot(level, pos.getCenter());
        }

        @Override
        public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
            return false;
        }

        @Override
        public float getKnockbackMultiplier(Entity entity) {
            return 0;
        }
    }
}
