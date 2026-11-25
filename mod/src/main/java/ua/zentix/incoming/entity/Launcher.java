package ua.zentix.incoming.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponType;

/**
 * Launcher: weapon rack ({@link LauncherRack}) on a mount ({@link LauncherMount}) with heading and per-cell
 * launch queue. Mobile is a trailer placed near the shooter ({@link LauncherEntity}); fixed is a player-placed
 * block ({@code launcher.FixedLauncherBlockEntity}). Launch places a projectile in either one's cell identically.
 */
public interface Launcher {
    /** The rack may turn if the new heading differs by more than this many degrees. */
    float TURN_THRESHOLD = 15;

    WeaponType weapon();

    /** Launcher position in the world: trailer ground center, or center of the block's bottom (moved with its craft). */
    Vec3 position();

    /** Rack heading in the world, °. */
    float yaw();

    LauncherMount mount();

    /** A moving frame carries the launcher (a fixed launcher on a craft): its rails move with it. */
    default boolean carried() {
        return false;
    }

    /** Tick when the rack started raising to elevation: placed or turned. */
    long deployedAt();

    /** Launch queue (server only). */
    LaunchQueue queue();

    /**
     * Turn the rack to {@code yaw} (MLRS, loitering munitions, fixed launcher at any target) if needed and
     * allowed ({@link #turnedYaw}): it lowers, turns, and raises again; launches wait for it to rise.
     */
    void turnTo(float yaw, long now);

    default LauncherRack rack() {
        return LauncherRack.of(weapon());
    }

    /** Rail elevation (spec): Shahed 15°, loitering catapult 20°, missiles 40°, MLRS tubes 50°. */
    default float elevation() {
        return rack().elevation();
    }

    /**
     * The rack's rails in the world, turned to world heading {@code yaw}: on its mount at {@link #position()}. A launcher
     * on a moving frame (a craft) places them by that frame's pose.
     */
    default Rails rails(float yaw) {
        return mount().rails(position(), yaw, weapon());
    }

    /** The rack's rails in the world at its current heading. */
    default Rails rails() {
        return rails(yaw());
    }

    /** Projectile center on {@code slot}'s rail with rack fully raised. */
    default Vec3 railPoint(int slot) {
        return rails().point(slot);
    }

    /** Ticks until rack finishes raising (0 means raised). */
    default int raisingTicks(long now) {
        return (int) Math.max(0, deployedAt() + LauncherEntity.DEPLOY_TICKS - now);
    }

    /** Current rack angle including raising (used for client rendering). */
    default float deployedElevation(long gameTime, float partialTick) {
        float t = Mth.clamp((gameTime - deployedAt() + partialTick) / LauncherEntity.DEPLOY_TICKS, 0, 1);
        return elevation() * t * t * (3 - 2 * t);
    }

    /**
     * Reserve a launch cell ({@link LaunchQueue#reserve}): no earlier than the rack is raised.
     *
     * @return [cell, ticks until ignition]
     */
    default int[] reserve(long now, int minReady, int busyTicks) {
        return queue().reserve(rack(), now, deployedAt() + LauncherEntity.DEPLOY_TICKS + 5, minReady, busyTicks);
    }

    /**
     * Rack heading after {@link #turnTo}{@code (yaw, now)}: unchanged if turning is unnecessary or the launcher
     * is not quiet.
     */
    default float turnedYaw(float yaw, long now) {
        return Math.abs(Mth.wrapDegrees(yaw - yaw())) <= TURN_THRESHOLD || !queue().silent(now) ? yaw() : yaw;
    }
}
