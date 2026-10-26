package ua.zentix.incoming.strike;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.guidance.Route;

import java.util.List;

/**
 * Operator route: designator-map points in flight order, horizontal only (projectile holds altitude). Empty means
 * launch builds path ({@link StrikeService}), detour and approach behind shooter. Waypoint weapons have spec
 * route reach ({@link WeaponSpec.Route#reach}): Shahed, cruise missile, Lancet. At most {@link #MAX} points;
 * server validates finite values, map range, path within weapon reach ({@code ServerActions.routeProblem}). Same
 * points in command ({@code /incoming salvo … via x z …}).
 */
public record Waypoints(List<Vec3> points) {
    /** Maximum route points. */
    public static final int MAX = 5;
    public static final Waypoints NONE = new Waypoints(List.of());

    /** Packet waypoint: x and z. */
    private static final StreamCodec<ByteBuf, Vec3> POINT = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::z, (x, z) -> new Vec3(x, 0, z));
    /** Packet refuses more than {@link #MAX} points: parse error, not a route. */
    public static final StreamCodec<ByteBuf, Waypoints> STREAM_CODEC = POINT.apply(ByteBufCodecs.list(MAX)).map(Waypoints::new, Waypoints::points);
    public static final Codec<Waypoints> CODEC = Vec3.CODEC.listOf().xmap(Waypoints::new, Waypoints::points);

    public Waypoints {
        points = points.stream().limit(MAX).map(p -> new Vec3(p.x, 0, p.z)).toList();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public int size() {
        return points.size();
    }

    /** Horizontal path from {@code from} through points to {@code to}, blocks. */
    public double length(Vec3 from, Vec3 to) {
        return route(from).remaining(from, to);
    }

    /** Weapon can fly this route from {@code from} to {@code to}: accepts waypoints and path within reach. */
    public boolean within(WeaponType weapon, Vec3 from, Vec3 to) {
        WeaponSpec.Route spec = weapon.spec().route();
        return spec.waypoints() && length(from, to) <= spec.reach();
    }

    /** Waypoint flight route; first leg starts at {@code origin} (launch point). */
    public Route route(Vec3 origin) {
        return Route.operator(points, origin);
    }

    /**
     * Distant approach origin (no launcher near shooter): {@code lead} blocks before first waypoint, on first leg
     * extension, approaching straight along route heading. Horizontal only; launch sets altitude.
     *
     * @param aim target point after last waypoint
     */
    public Vec3 afar(Vec3 aim, double lead) {
        Vec3 first = points.getFirst();
        Vec3 next = points.size() > 1 ? points.get(1) : new Vec3(aim.x, 0, aim.z);
        Vec3 along = next.subtract(first);
        // First waypoint directly above target: no first-leg heading, approach from north.
        Vec3 dir = along.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : along.normalize();
        return first.subtract(dir.scale(lead));
    }
}
