package ua.zentix.incoming.entity.flight;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.StrikeProjectile;

import java.util.UUID;

/** Projectile arming and proximity fuze. */
public final class ProximityFuse {
    /** Fuze arms this far from the launcher (or on entering cruise). */
    public static final double ARM_DISTANCE = 96;

    private ProximityFuse() {}

    /** Fuze armed: projectile moved away from {@code launchPos} (null means unknown) or entered cruise. */
    public static boolean armed(FlightPhase phase, Vec3 pos, @Nullable Vec3 launchPos) {
        if (phase.ordinal() >= FlightPhase.CRUISE.ordinal()) return true;
        return launchPos == null || pos.distanceToSqr(launchPos) > ARM_DISTANCE * ARM_DISTANCE;
    }

    /**
     * Who the nose path {@code from → to} hits at {@code speed}: people near the trajectory and the target {@code
     * targetId} itself. Excludes {@code owner} (unless the target): flying over the launching player's head
     * should not kill them. Among hits, nearest to path start; null means nobody.
     */
    @Nullable
    public static Entity victim(ServerLevel level, Entity self, Vec3 from, Vec3 to, double speed, @Nullable UUID targetId,
                                @Nullable UUID owner) {
        double r = speed / 8 + 1.8;
        AABB sweep = new AABB(from, to).inflate(r + 1);
        Entity best = null;
        double bestT = Double.MAX_VALUE;
        for (Entity e : level.getEntities(self, sweep, e -> isTarget(e, targetId, owner))) {
            Vec3 c = e.getBoundingBox().getCenter();
            Vec3 on = closestOnSegment(from, to, c);
            double reach = r + e.getBbWidth() * 0.5;
            if (on.distanceToSqr(c) <= reach * reach) {
                double t = on.distanceToSqr(from);
                if (t < bestT) {
                    bestT = t;
                    best = e;
                }
            }
        }
        return best;
    }

    /**
     * Operator-selected target always triggers the fuze (except a spectator). Incidental people near the
     * trajectory follow vanilla's “who is noticed” rule: neither creative nor spectator ({@link
     * EntitySelector#NO_CREATIVE_OR_SPECTATOR}, used by vanilla mobs to choose whom to attack).
     */
    private static boolean isTarget(Entity e, @Nullable UUID targetId, @Nullable UUID owner) {
        if (!e.isAlive() || e.isSpectator() || e instanceof StrikeProjectile) return false;
        if (e.getUUID().equals(targetId)) return true;
        return e instanceof Player && !e.getUUID().equals(owner) && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(e);
    }

    /** Point on segment {@code a–b} nearest to {@code p}. */
    public static Vec3 closestOnSegment(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1.0e-9) return a;
        double t = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return a.add(ab.scale(t));
    }
}
