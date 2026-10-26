package ua.zentix.incoming.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Projectile target area: chunks load (and generate) in background during approach, so entities tick by arrival
 * and the projectile (in-world or materializing from virtual flight) hits normally. Preload area, but start
 * ticking (mobs, block entities, spawning) only in final approach seconds ({@code TargetAreaHold}) and as ready
 * ({@link AreaLoader}). Each projectile has its own ticket (UUID key): same-point salvos do not remove each
 * other's tickets. Missile approach strips are shared ({@link #holdApproach}). Tickets are unsaved: after
 * restart, projectile reacquires its own.
 */
public final class FlightTickets {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_flight", Comparator.<UUID>naturalOrder());
    /**
     * Ticket level 33 − 4 = 29: entities tick in 5×5 chunks around target (±40 blocks), 9×9 loaded; projectile
     * appears before target, neighbors ready for explosion and debris.
     */
    public static final int DISTANCE = 4;
    /** Lancet target area ({@code LoiterEntity.targetArea}): level 27, entire orbit in ticking chunks. */
    public static final int LOITER_DISTANCE = 6;
    /**
     * Approach strip ({@link #approach}): level 33 − 3 = 30 areas, entities tick in 3×3 chunks around center
     * (vanilla allows them once 5×5 ready; 7×7 loaded).
     */
    public static final int APPROACH_DISTANCE = 3;
    /** Approach-strip center spacing, blocks: two chunks, so adjacent 3×3 squares overlap even diagonally. */
    private static final double APPROACH_STEP = 32;
    /**
     * Maximum world approach-strip areas: 4 strips of 256 blocks (8 areas). Neighboring areas overlap: ~150
     * loaded chunks per strip, ~600 total.
     */
    public static final int APPROACH_LIMIT = 32;
    /** Shared strip-area key for all projectiles; holders tracked in {@link StrikeWorld#approach}. */
    private static final UUID APPROACH_KEY = UUID.nameUUIDFromBytes("incoming:approach".getBytes(StandardCharsets.UTF_8));

    private FlightTickets() {}

    /**
     * Approach strip: {@link #APPROACH_DISTANCE} area centers along {@code length} blocks backward from target
     * over projectile path ({@code path}: target, waypoints last to nearest, then projectile), forming a
     * continuous 3-chunk-wide entity-ticking strip. Virtual projectile materializes at strip edge rather than
     * target area: approach is visible to a player at the target even with simulation distance below render
     * distance. Excludes target itself, held by target area.
     */
    public static List<ChunkPos> approach(List<Vec3> path, double length) {
        if (path.size() < 2) return List.of();
        ChunkPos target = new ChunkPos(BlockPos.containing(path.getFirst()));
        Set<ChunkPos> centres = new LinkedHashSet<>();
        double s = APPROACH_STEP, walked = 0;
        for (int i = 1; i < path.size() && s <= length; i++) {
            Vec3 a = path.get(i - 1), b = path.get(i);
            double dx = b.x - a.x, dz = b.z - a.z, d = Math.sqrt(dx * dx + dz * dz);
            // Centers on this path segment: every APPROACH_STEP along the path arc from target.
            for (; s <= length && s <= walked + d; s += APPROACH_STEP) {
                double t = (s - walked) / d;
                ChunkPos c = new ChunkPos(BlockPos.containing(a.x + dx * t, a.y, a.z + dz * t));
                if (!c.equals(target)) centres.add(c);
            }
            walked += d;
        }
        return List.copyOf(centres);
    }

    /**
     * Someone to see approach to {@code aim}: player in this world within server render distance and {@code leg}
     * horizontally. Otherwise no strip needed: projectile enters at target area like others.
     */
    public static boolean watched(ServerLevel level, Vec3 aim, double leg) {
        double r = level.getServer().getPlayerList().getViewDistance() * 16.0 + leg;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - aim.x, dz = p.getZ() - aim.z;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    /**
     * Acquire approach strip for {@code flight}. World strip areas are shared (one key): a salvo at one point
     * from one direction holds one strip, rather than one per projectile; release an area when no projectile
     * holds it. At most {@link #APPROACH_LIMIT} world strip areas: reject entire strip if it does not fit
     * (false); projectile enters at target area instead. Each strip area loads 7×7 chunks; in ungenerated terrain
     * this shares players' generation queue.
     */
    public static boolean holdApproach(ServerLevel level, List<ChunkPos> centres, UUID flight) {
        StrikeWorld world = StrikeWorld.get(level);
        Map<ChunkPos, Set<UUID>> refs = world.approach();
        long fresh = centres.stream().filter(c -> !refs.containsKey(c)).count();
        if (refs.size() + fresh > APPROACH_LIMIT) return false;
        for (ChunkPos c : centres) {
            Set<UUID> by = refs.computeIfAbsent(c, k -> new HashSet<>());
            if (by.isEmpty()) world.areas().hold(level, approachArea(c));
            by.add(flight);
        }
        return true;
    }

    /** Release {@code flight}'s approach strip: release areas nobody else holds. */
    public static void releaseApproach(ServerLevel level, List<ChunkPos> centres, UUID flight) {
        StrikeWorld world = StrikeWorld.get(level);
        Map<ChunkPos, Set<UUID>> refs = world.approach();
        for (ChunkPos c : centres) {
            Set<UUID> by = refs.get(c);
            if (by == null || !by.remove(flight) || !by.isEmpty()) continue;
            refs.remove(c);
            world.areas().release(level, approachArea(c));
        }
    }

    /** World approach-strip areas acquired (tests). */
    public static int approachAreas(ServerLevel level) {
        return StrikeWorld.get(level).areas().count(TYPE, APPROACH_KEY);
    }

    private static AreaLoader.Area approachArea(ChunkPos c) {
        return new AreaLoader.Area(TYPE, c, APPROACH_DISTANCE, APPROACH_KEY);
    }

    /** Ticking target areas held by {@code flight}, excluding approach strip (tests). */
    public static int ticking(ServerLevel level, UUID flight) {
        return StrikeWorld.get(level).areas().count(TYPE, flight, true);
    }

    /** Areas held by {@code flight}: target area plus approach-strip areas shared with others (tests). */
    public static int held(ServerLevel level, UUID flight) {
        StrikeWorld world = StrikeWorld.get(level);
        int approach = (int) world.approach().values().stream().filter(by -> by.contains(flight)).count();
        return world.areas().count(TYPE, flight) + approach;
    }

    /** {@code distance} is ticket level: default {@link #DISTANCE}; 6 entity-ticks a 9×9 square. */
    public static void hold(ServerLevel level, ChunkPos pos, int distance, UUID flight, boolean hold) {
        hold(level, pos, distance, flight, true, hold);
    }

    /**
     * Same; {@code ticks} false means loading only ({@link AreaLoader.Area#ticks}); ticking starts when
     * projectile acquires it as ticking ({@code TargetAreaHold}).
     */
    public static void hold(ServerLevel level, ChunkPos pos, int distance, UUID flight, boolean ticks, boolean hold) {
        AreaLoader.Area area = new AreaLoader.Area(TYPE, pos, distance, flight, ticks);
        if (hold) StrikeWorld.get(level).areas().hold(level, area);
        else StrikeWorld.get(level).areas().release(level, area);
    }
}
