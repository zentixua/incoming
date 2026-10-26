package ua.zentix.incoming.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.util.Terrain;

import java.util.Comparator;
import java.util.UUID;

/**
 * A salvo loads the area around its ordered launch site ({@link LaunchOrigin}) in the background
 * ({@link AreaLoader}, like a projectile's target area) and holds it while running. The launcher stands at the
 * actual site, where the projectile launches and accelerates in the loaded world. The launch is visible from
 * afar (plume, launch cloud, trail, {@link FarFlights} to {@code far_range}), so an unloaded site is no reason
 * to skip it. Without the area, the projectile would start outside the loaded world, already cruising (missile
 * at cruise height above the site, MLRS on descent), and nobody would see the launch. The area lives for
 * {@link #KEEP} ticks after its last renewal. The salvo renews it each tick and releases it if canceled or
 * failed.
 */
public final class LaunchPlaces {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_launch_place", Comparator.<UUID>naturalOrder());
    /** Area ticks after last renewal. */
    static final int KEEP = 100;
    /**
     * Salvo wait for launch-site area, ticks (30 s): fresh area ready in 0.7–4.6 s (target-area measurement,
     * {@code RocketEntity#preloadDistance}). On timeout, launch outside the loaded world without a launcher, as
     * before.
     */
    public static final int WAIT = 600;

    private LaunchPlaces() {}

    /**
     * Launch of {@code weapon} from {@code from} waits for area: site exists, weapon uses a launcher (B-2 uses
     * site only for approach heading, ICBM ignores it), and site is not ready ({@link #ready}).
     */
    public static boolean waits(ServerLevel level, WeaponType weapon, @Nullable LaunchOrigin from) {
        return from != null && fromPad(weapon) && !ready(level, from);
    }

    /** Weapon launches from a launcher at the site (has a rack): launch-site area has something to hold. */
    public static boolean fromPad(WeaponType weapon) {
        return weapon.spec().rack() != null;
    }

    /**
     * Site ready for launch: ordered site requires all chunks within {@link LaunchSite#PLACE_REACH} (launcher
     * search area); fixed launcher requires its chunk.
     */
    public static boolean ready(ServerLevel level, LaunchOrigin from) {
        return from instanceof LaunchOrigin.Fixed f ? Terrain.ready(level, f.pos()) : Terrain.readyAround(level, from.at(), LaunchSite.PLACE_REACH);
    }

    /** Acquire or renew salvo {@code key}'s {@code from} area. */
    public static void hold(ServerLevel level, LaunchOrigin from, UUID key) {
        AreaLoader areas = StrikeWorld.get(level).areas();
        AreaLoader.Area area = area(from, key);
        long until = level.getGameTime() + KEEP;
        if (areas.holds(area)) areas.renew(area, until);
        else areas.hold(level, area, until);
    }

    /** Whether salvo {@code key} holds its site area (tests). */
    public static boolean holds(ServerLevel level, LaunchOrigin from, UUID key) {
        return StrikeWorld.get(level).areas().holds(area(from, key));
    }

    /**
     * Area radius around site, chunks: all chunks within {@link LaunchSite#PLACE_REACH} for any site position in
     * its chunk (7 means 15×15). Entire area ticks as ready, like target area: launcher can stand anywhere in it.
     */
    static final int DISTANCE = Mth.ceil(LaunchSite.PLACE_REACH / 16);

    private static AreaLoader.Area area(LaunchOrigin from, UUID key) {
        return new AreaLoader.Area(TYPE, new ChunkPos(BlockPos.containing(from.at())), DISTANCE, key);
    }
}
