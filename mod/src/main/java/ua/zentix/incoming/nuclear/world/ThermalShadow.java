package ua.zentix.incoming.nuclear.world;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.util.Terrain;

/**
 * How much of the fireball a point sees (DESIGN-nuke §3.4): the light source is the luminous fireball at its maximum
 * radius ({@link Detonation#fireballRadius}, Glasstone 2.12), not the burst point. A surface burst's ball is a
 * hemisphere 0.088·Y^0.4 km high on the ground (1 395 m for 1 Mt): a ray to its foot grazes the terrain, and any tree or
 * one-block bump between hid the whole pulse (1 Mt over the Silo map, 16.11.2026: 0.2–14 % of ground columns at
 * 0.5–6 km saw the burst point, 98–99 % to 3 km see the ball). Terrain samples along the horizontal projection of the ray
 * from the point to the ball's axis give the terrain line: the lowest height on the axis above which the ball is
 * visible. The visible share is the ball's vertical extent above that line ({@link #discShare}, the same law the
 * client uses for distant fireballs and puffs). Exposure is the free fluence times the share; one law for every caller.
 * Produces “Hiroshima shadows” and slopes behind hills that keep their grass.
 */
public final class ThermalShadow {
    /** Terrain samples on a ray in the live world (one heightmap read each, no {@code clip}). */
    private static final int SAMPLES = 16;
    /** Terrain line below every possible ball: nothing hides it. */
    static final double OPEN = Double.NEGATIVE_INFINITY;

    private ThermalShadow() {}

    /**
     * Visible share of a ball (centre {@code h} above its base ground, radius {@code r}) above the terrain line
     * {@code line} (height above the same ground; ≤ 0 open). The part below the ground does not count; smoothed, so
     * the ball goes behind a ridge gradually.
     */
    public static double discShare(double h, double r, double line) {
        if (line <= 0) return 1;
        double lo = Math.max(0, h - r), hi = h + r;
        if (hi <= lo) return line <= h ? 1 : 0;
        double v = Math.max(0, Math.min(1, (hi - Math.max(line, lo)) / (hi - lo)));
        return v * v * (3 - 2 * v);
    }

    /** Share of the detonation's fireball above terrain line {@code line} (absolute height on the ball axis). */
    public static double share(Detonation d, double line) {
        return line == OPEN ? 1 : discShare(d.burst().y - d.groundY(), Math.max(d.fireballRadius(), 1), line - d.groundY());
    }

    /**
     * Lowest height on the ball axis seen from {@code p} past terrain whose first air is {@code height} at horizontal
     * distance {@code s} from {@code p}, the axis at horizontal distance {@code reach}. A column's top hides a ray passing
     * less than half a block above its first air, as before.
     */
    public static double clearance(Vec3 p, double s, double reach, int height) {
        return p.y + (height - 0.5 - p.y) * reach / s;
    }

    /** Visible share of the fireball from {@code point} in the live world: ready-chunk heightmaps along the ray. */
    public static double share(Level level, Detonation d, Vec3 point) {
        return share(d, sparseLine(level, d, point));
    }

    /** Terrain line from {@code point} in the live world ({@link #SAMPLES} ready-chunk heightmap samples). */
    public static double sparseLine(Level level, Detonation d, Vec3 point) {
        return sparseLine(d, point, (x, z) -> Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z));
    }

    /**
     * Middle of the fireball's extent above the ground, on its axis: where a near block ray aims (a wall at the eye
     * hides the whole ball).
     */
    public static Vec3 aim(Detonation d) {
        double r = Math.max(d.fireballRadius(), 1), lo = Math.max(d.groundY(), d.burst().y - r), hi = d.burst().y + r;
        return new Vec3(d.burst().x, (lo + hi) / 2, d.burst().z);
    }

    /** Whether any part of the fireball is visible from {@code point} in the live world. */
    public static boolean visible(Level level, Detonation d, Vec3 point) {
        return share(level, d, point) > 0;
    }

    /** Terrain line from {@link #SAMPLES} heightmap samples spread evenly between the point and the ball axis. */
    static double sparseLine(Detonation d, Vec3 point, java.util.function.IntBinaryOperator height) {
        double dx = d.burst().x - point.x, dz = d.burst().z - point.z, reach = Math.sqrt(dx * dx + dz * dz);
        double line = OPEN;
        if (reach < 1e-6) return line;
        for (int i = 1; i <= SAMPLES; i++) {
            double f = i / (SAMPLES + 1.0);
            int h = height.applyAsInt(Mth.floor(point.x + dx * f), Mth.floor(point.z + dz * f));
            line = Math.max(line, clearance(point, f * reach, reach, h));
        }
        return line;
    }

    /**
     * Terrain line from heights known to a ruin plan: every block along the ray while {@code height} knows the column
     * ({@link Integer#MIN_VALUE} ends the walk — the plan's window edge). Terrain beyond is not an occluder: see
     * {@link RuinContext.Grid#shadowLine}.
     */
    static double windowLine(Detonation d, Vec3 point, java.util.function.IntBinaryOperator height) {
        double dx = d.burst().x - point.x, dz = d.burst().z - point.z, reach = Math.sqrt(dx * dx + dz * dz);
        double line = OPEN;
        if (reach < 1e-6) return line;
        double ux = dx / reach, uz = dz / reach;
        for (double s = 1; s < reach; s++) {
            int h = height.applyAsInt(Mth.floor(point.x + ux * s), Mth.floor(point.z + uz * s));
            if (h == Integer.MIN_VALUE) break;
            line = Math.max(line, clearance(point, s, reach, h));
        }
        return line;
    }
}
