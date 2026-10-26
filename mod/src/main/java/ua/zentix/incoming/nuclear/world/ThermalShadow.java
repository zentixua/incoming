package ua.zentix.incoming.nuclear.world;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.util.Terrain;

/**
 * Whether a point sees the fireball (DESIGN-nuke §3.4): 16 heightmap samples along the ray's horizontal projection —
 * terrain or building above the ray puts the point in shadow. One array read per sample, no {@code clip}; produces
 * “Hiroshima shadows” and surviving slopes behind hills.
 */
public final class ThermalShadow {
    private static final int SAMPLES = 16;

    private ThermalShadow() {}

    public static boolean visible(Level level, Vec3 fireball, Vec3 point) {
        return visible(fireball, point, (x, z) -> Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z));
    }

    /** The same with a supplied heightmap (first air above {@code MOTION_BLOCKING}): ruins see the pre-burst world. */
    static boolean visible(Vec3 fireball, Vec3 point, java.util.function.IntBinaryOperator height) {
        for (int i = 1; i <= SAMPLES; i++) {
            double f = i / (SAMPLES + 1.0);
            double x = point.x + (fireball.x - point.x) * f;
            double y = point.y + (fireball.y - point.y) * f;
            double z = point.z + (fireball.z - point.z) * f;
            if (height.applyAsInt(Mth.floor(x), Mth.floor(z)) > y + 0.5) return false;
        }
        return true;
    }
}
