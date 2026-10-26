package ua.zentix.incoming.defense;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.target.Sides;
import ua.zentix.incoming.util.LineOfSight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Radar: which mod projectiles are visible from a point and who owns them. Uses only the world's projectile list
 * ({@link StrikeWorld#projectiles}: inside and outside the world), without scanning the world, so it sees
 * loaded-chunk projectiles and virtual flights equally. Radar sees no players, mobs, or Create Aeronautics craft:
 * only mod projectiles.
 * <p>
 * Our own game model (not physical): a projectile is visible within radar range (closer for low signatures, per
 * {@link WeaponSpec.Signature}) if ground or structures do not block the antenna-to-target line ({@link
 * LineOfSight}: heightmaps from ready chunks only; unready chunks do not block it, and radar loads no chunks). A
 * terrain-hugging missile behind a hill is unseen until it emerges. The spec also decides whether a projectile is
 * worth a missile: an ICBM cannot be caught; a Grad is not worth one. Friendly means the same player or their
 * team ({@link Sides}); an ownerless projectile (console, command block) is hostile. Fire control is separate
 * ({@link FireControl}): a portable SAM can reuse the radar.
 */
public final class Radar {
    private Radar() {}

    /**
     * Radar screen target.
     *
     * @param projectile projectile (inside or outside the world)
     * @param distance distance to it, blocks
     * @param hostile hostile
     * @param engageable hostile, worth a missile, without a rider, and within engagement range
     */
    public record Track(StrikeProjectile projectile, double distance, boolean hostile, boolean engageable) {
        public UUID id() {
            return projectile.getUUID();
        }

        public Vec3 position() {
            return projectile.position();
        }
    }

    /**
     * Airborne projectile: not removed, not in a launcher cell or on its rail (radar cannot distinguish that from
     * ground), not underground.
     */
    public static boolean airborne(StrikeProjectile p) {
        if (p.isRemoved() || !p.isActive()) return false;
        FlightPhase ph = p.flightPhase();
        return !ph.onLauncher() && ph != FlightPhase.DRILL;
    }

    /**
     * What radar sees at {@code at}, nearest first: within range and not behind terrain.
     *
     * @param range radar range, blocks (closer for low signatures, per spec)
     * @param engageRange engagement range, blocks
     * @param owner radar owner (null means unowned: everyone is hostile)
     */
    public static List<Track> scan(ServerLevel level, Vec3 at, double range, double engageRange, @Nullable UUID owner) {
        MinecraftServer server = level.getServer();
        List<Track> out = new ArrayList<>();
        double r2 = range * range;
        for (StrikeProjectile p : DefenseWorld.get(level).projectiles(level)) {
            if (!airborne(p)) continue;
            double d2 = p.position().distanceToSqr(at);
            if (d2 > r2) continue;
            WeaponSpec.Signature sig = p.airframe().radar();
            double d = Math.sqrt(d2);
            if (d > range * sig.visibility()) continue;
            if (!LineOfSight.clear(level, at, p.position())) continue;
            boolean hostile = !Sides.friendly(server, owner, p.ownerId());
            // A projectile with a rider is not a target: the SAM missile does not pursue anything carrying someone.
            boolean engageable = hostile && sig.intercept() && !p.isVehicle() && d <= engageRange;
            out.add(new Track(p, d, hostile, engageable));
        }
        out.sort(Comparator.comparingDouble(Track::distance));
        return out;
    }
}
