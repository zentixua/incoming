package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;

/**
 * Point relative to the projectile.
 *
 * @param distance distance, blocks
 * @param horizontal horizontal distance, blocks
 * @param yaw heading toward the point, °
 * @param pitch point angle below the horizon, ° (> 0 means below)
 */
public record Bearing(double distance, double horizontal, float yaw, float pitch) {
    public static Bearing of(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        float[] a = FlightController.anglesTo(from, to);
        return new Bearing(from.distanceTo(to), Math.sqrt(dx * dx + dz * dz), a[0], a[1]);
    }
}
