package ua.zentix.incoming.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.target.Sight;
import ua.zentix.incoming.target.Target;

import java.util.Optional;
import java.util.UUID;

/**
 * Operator projectile camera ({@code client.cam.ProjectileCamera}): which owned projectile they view from and
 * where the gimballed camera points. Client sends this each tick while aboard ({@code net.C2S.CameraView}); view
 * older than {@link #FRESH} ticks means camera closed or switched to map. Direction arrives here rather than via
 * player rotation: while camera is off-player, vanilla client does not send rotation to server ({@code
 * LocalPlayer#sendPosition}).
 * <p>
 * Camera-guided projectile ({@link WeaponSpec.Tracking#CAMERA}) follows a sighted target ({@link Target.Sighted})
 * through this view only while operator holds it in frame ({@link #holds}). One view per player means one guided
 * projectile. The same view spots hostiles for operator's side ({@code target.Sightings}).
 *
 * @param projectile projectile viewed from
 * @param look camera direction, unit vector
 * @param tick game time when view arrived
 */
public record CameraLink(UUID projectile, Vec3 look, long tick) {
    /** View persists this many ticks after packet: client sends every tick; margin covers network delay. */
    private static final int FRESH = 10;
    /**
     * Frame is a cone around camera direction, half-angle in degrees. Onboard camera reduces client FOV by a
     * quarter: at normal 70°, frame is ±26° vertically and ±41° horizontally on 16:9; a 25° cone fits vertically
     * too.
     */
    private static final double FRAME_DEGREES = 25;
    private static final double FRAME_COS = Math.cos(Math.toRadians(FRAME_DEGREES));
    /**
     * Projectile camera cannot distinguish targets beyond this: same retargeting camera reach ({@link
     * ServerActions#retarget}).
     */
    public static final double REACH = 1024;

    /** View from {@code player}'s client: accepted if projectile belongs to them and is in their world. */
    public static void receive(ServerPlayer player, UUID projectile, float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        if (!(player.serverLevel().getEntity(projectile) instanceof StrikeProjectile p) || !player.getUUID().equals(p.ownerId())) return;
        player.setData(ModAttachments.CAMERA_LINK, Optional.of(new CameraLink(projectile, Vec3.directionFromRotation(pitch, yaw),
                player.serverLevel().getGameTime())));
    }

    /**
     * {@code player}'s view if currently aboard their own projectile: packet received within {@link #FRESH}
     * ticks.
     */
    public static Optional<CameraLink> current(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        return player.getData(ModAttachments.CAMERA_LINK).filter(v -> now - v.tick <= FRESH);
    }

    /** Point {@code at} is in this view's frame from projectile {@code eye} and within {@link #REACH}. */
    public boolean frames(Vec3 eye, Vec3 at) {
        Vec3 to = at.subtract(eye);
        double distance = to.length();
        return distance <= REACH && (distance <= 1e-6 || to.dot(look) >= distance * FRAME_COS);
    }

    /**
     * Operator of projectile {@code p} holds {@code subject} at {@code at} in this projectile camera's frame:
     * currently aboard, point in frame and within {@link #REACH}, their client receives it ({@link
     * Sight#within}), and camera sees it. Entity uses {@link Sight#sees(ServerPlayer, Vec3, Entity)} from camera;
     * craft ray may hit only that craft ({@link SubLevels#visibleFrom}); position uses {@link Sight#clear}.
     */
    public static boolean holds(ServerLevel level, StrikeProjectile p, Target subject, Vec3 at) {
        ServerPlayer operator = p.ownerPlayer();
        return operator != null && operator.serverLevel() == level && holds(operator, p, subject, at);
    }

    /**
     * Same with already found {@code operator} in projectile world (see {@link #holds(ServerLevel,
     * StrikeProjectile, Target, Vec3)}).
     */
    public static boolean holds(ServerPlayer operator, StrikeProjectile p, Target subject, Vec3 at) {
        ServerLevel level = operator.serverLevel();
        Optional<CameraLink> view = current(operator);
        Vec3 eye = p.getEyePosition();
        if (view.isEmpty() || !view.get().projectile.equals(p.getUUID()) || !view.get().frames(eye, at)) return false;
        if (!Sight.within(operator, at)) return false;
        return switch (subject) {
            case Target.OfEntity e -> {
                Entity entity = level.getEntity(e.uuid());
                yield entity != null && Sight.sees(operator, eye, entity);
            }
            case Target.OfSubLevel s -> SubLevels.visibleFrom(level, eye, s.plotPos());
            default -> Sight.clear(level, eye, at);
        };
    }
}
