package ua.zentix.incoming.launcher;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Target;

/**
 * Fixed launcher mission ({@link FixedLauncherBlockEntity}): weapon, count, spread, target site, and waypoints.
 * Target is only a position ({@link Target.Point} or map site {@link Target.Ground}): launcher fires on a signal
 * at any later time and does not track a moving target; entities, craft, and sighted targets become their
 * positions at assignment.
 *
 * @param point impact position ({@link Target.Ground} stores assignment-time height; launch resolves it again)
 */
public record Mission(WeaponType weapon, int count, int spread, Target target, Vec3 point, Waypoints via) {
    public static final Codec<Mission> CODEC = RecordCodecBuilder.create(i -> i.group(
            WeaponType.CODEC.fieldOf("weapon").forGetter(Mission::weapon),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("count").forGetter(Mission::count),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("spread").forGetter(Mission::spread),
            Target.CODEC.fieldOf("target").forGetter(Mission::target),
            Vec3.CODEC.fieldOf("point").forGetter(Mission::point),
            Waypoints.CODEC.optionalFieldOf("via", Waypoints.NONE).forGetter(Mission::via)
    ).apply(i, Mission::new));

    public Mission {
        if (!(target instanceof Target.Point) && !(target instanceof Target.Ground)) target = new Target.Point(point);
        if (!weapon.spec().route().waypoints()) via = Waypoints.NONE;
    }

    /**
     * Weapons accepted by fixed launcher: those launched from a rack (spec, {@link WeaponSpec#rack}): Shahed,
     * Lancet, cruise missile, Grad. B-2 approaches from afar; ICBM has its own pad.
     */
    public static boolean accepts(WeaponType weapon) {
        return weapon.spec().rack() != null;
    }

    /** Rack heading for this mission: first waypoint, otherwise target. */
    public Vec3 heading() {
        return via.isEmpty() ? point : via.points().getFirst();
    }
}
