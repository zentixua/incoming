package ua.zentix.incoming.entity;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.util.Local;

import java.util.List;

/**
 * A launcher's rack as it stands in the world: the projectile center on each cell's rail with the rack fully raised,
 * and the rails' common axis (heading {@code yaw}, {@code elevation} above the horizon, degrees). On the ground the axis
 * is the rack's own heading and elevation; on a craft it is the craft's pose applied to them, so a rolled or pitched
 * deck tilts the axis.
 */
public record Rails(List<Vec3> points, float yaw, float elevation) {
    public Rails {
        points = List.copyOf(points);
    }

    /** Projectile center on cell {@code slot}'s rail. */
    public Vec3 point(int slot) {
        return points.get(slot);
    }

    public int slots() {
        return points.size();
    }

    /** Unit vector along the rails, nose first. */
    public Vec3 direction() {
        return Local.offset(yaw, -elevation, 0, 0, 1);
    }

    /** A rack turned to a heading: where its rails would stand at world heading {@code yaw}. */
    @FunctionalInterface
    public interface Turned {
        Rails at(float yaw);
    }
}
