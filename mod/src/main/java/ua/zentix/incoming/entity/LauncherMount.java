package ua.zentix.incoming.entity;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Local;

/**
 * Launcher rack support ({@link Launcher}): location of its elevation pivot relative to the launcher. A weapon's
 * rack stays the same ({@link LauncherRack}); only the mount changes, determining cells, launch sector, and
 * visuals.
 */
public enum LauncherMount {
    /** Mobile launcher trailer: pivot 1.6 above ground and 2.2 behind trailer center. */
    TRAILER(1.6, 2.2),
    /** Fixed launcher: pivot on the turntable above the block, over its top center; position is bottom center. */
    PAD(1.25, 0);

    /** Elevation pivot above launcher position and behind it along heading, blocks. */
    public final double up, back;

    LauncherMount(double up, double back) {
        this.up = up;
        this.back = back;
    }

    /** Rack elevation pivot at launcher {@code pos} with heading {@code yaw}. */
    public Vec3 pivot(Vec3 pos, float yaw) {
        return Local.at(pos, yaw, 0, 0, up, -back);
    }

    /**
     * Projectile center on rail {@code slot} of a fully raised {@code weapon} rack at launcher {@code pos} with
     * heading {@code yaw}.
     */
    public Vec3 railPoint(Vec3 pos, float yaw, WeaponType weapon, int slot) {
        LauncherRack rack = LauncherRack.of(weapon);
        double[] at = rack.slotOffset(slot);
        return Local.at(pivot(pos, yaw), yaw, -rack.elevation(), at[0], at[1], at[2]);
    }
}
