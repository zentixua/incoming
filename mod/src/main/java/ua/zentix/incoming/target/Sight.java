package ua.zentix.incoming.target;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import ua.zentix.incoming.util.Terrain;

/**
 * Server line of sight: what player sees with own eyes. Server does not know screen contents, so “sees” means
 * Minecraft's “entity sent to client” ({@code ChunkMap.TrackedEntity.updatePlayer}) plus clear eye-to-body line.
 * No check loads chunks: a segment through an unready chunk is unseen.
 * <p>
 * Obstacles block vision ({@link ClipContext.Block#VISUAL}): glass/bars transparent, leaves/walls/roofs opaque;
 * water/lava do not block. Sable aircraft blocks also obstruct: its vanilla {@code Level.clip} sees them.
 */
public final class Sight {
    private Sight() {}

    /**
     * {@code viewer} sees {@code target} with own eyes: {@link #sees(ServerPlayer, Vec3, Entity)} from eyes.
     */
    public static boolean sees(ServerPlayer viewer, Entity target) {
        return sees(viewer, viewer.getEyePosition(), target);
    }

    /**
     * {@code viewer} sees {@code target} from {@code eye}, own eyes or owned-projectile camera (client camera
     * image exists only while entity is sent): same world, alive, not invisible to viewer (invisibility potion
     * except team with {@code seeFriendlyInvisibles}), Minecraft sends it ({@link Entity#broadcastToPlayer}:
     * spectator unseen), within horizontal client reception range from player ({@link #range}), clear line from
     * {@code eye} to target eyes or body center ({@link #clear}).
     */
    public static boolean sees(ServerPlayer viewer, Vec3 eye, Entity target) {
        if (target == viewer || target.level() != viewer.level() || !target.isAlive()) return false;
        if (target.isInvisibleTo(viewer) || !target.broadcastToPlayer(viewer)) return false;
        if (!within(viewer, target.position(), range(viewer, target))) return false;
        ServerLevel level = viewer.serverLevel();
        return clear(level, eye, target.getEyePosition()) || clear(level, eye, target.getBoundingBox().getCenter());
    }

    /**
     * {@code at} within server entity-sending horizontal range for player ({@link #viewRange}). Caller checks
     * point world matches player world.
     */
    public static boolean within(ServerPlayer viewer, Vec3 at) {
        return within(viewer, at, viewRange(viewer));
    }

    /**
     * Horizontal client entity reception range, as Minecraft: type tracking range (maximum with passengers;
     * server {@code entity-broadcast-range-percentage} adjustment), capped at {@link #viewRange}.
     */
    public static double range(ServerPlayer viewer, Entity target) {
        int tracking = target.getType().clientTrackingRange() * 16;
        for (Entity passenger : target.getIndirectPassengers()) {
            tracking = Math.max(tracking, passenger.getType().clientTrackingRange() * 16);
        }
        return Math.min(viewer.server.getScaledTrackingDistance(tracking), viewRange(viewer));
    }

    /**
     * Player render distance, blocks: player setting capped by server; no entities sent beyond ({@code
     * ChunkMap.TrackedEntity.updatePlayer}).
     */
    public static int viewRange(ServerPlayer viewer) {
        int serverView = Mth.clamp(viewer.server.getPlayerList().getViewDistance(), 2, 32);
        return Mth.clamp(viewer.requestedViewDistance(), 2, serverView) * 16;
    }

    private static boolean within(ServerPlayer viewer, Vec3 at, double range) {
        double dx = at.x - viewer.getX(), dz = at.z - viewer.getZ();
        return dx * dx + dz * dz <= range * range;
    }

    /**
     * Unobstructed view from {@code from} to {@code to}. Segment entering unready chunk is “unseen”; do not load
     * chunks for sight ({@link Terrain#readyAlong}).
     */
    public static boolean clear(ServerLevel level, Vec3 from, Vec3 to) {
        if (!Terrain.readyAlong(level, from, to)) return false;
        return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
                .getType() == HitResult.Type.MISS;
    }
}
