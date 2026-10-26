package ua.zentix.incoming.scenario;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.strike.VirtualFlights;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One hit script on the built-in server: what projectile was launched (UUID - new among projectiles in the world and outside it) and when
 *  it exploded (the first {@code ExplosionEvent.Start}, the source of damage of which is this projectile). The client's
 *  entity disappears without an explosion - when tracking is lost and when it goes off into flight outside the world ({@code VirtualFlights.park}: new
 *  entity with the same UUID), so the “impact” is only the explosion of its projectile.
 */
public final class StrikeWatch {
    @Nullable
    private volatile UUID projectile;
    private volatile int impactTick = -1;
    /** The server's world time is exploding: the client's clock lags behind the server and catches up with it in jerks. */
    private volatile long impactGameTime = -1;
    @Nullable
    private volatile Vec3 impactAt;

    /** UUID world shells: in and out of the busy world. */
    public static Set<UUID> projectiles(ServerLevel level) {
        Set<UUID> out = new HashSet<>();
        for (StrikeProjectile p : level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(StrikeProjectile.class), e -> true)) {
            out.add(p.getUUID());
        }
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) out.add(p.getUUID());
        return out;
    }

    /**
     * launch: the projectile is the only new UUID between the shots before and after the command. {@code false} - there is no new one or there are several of them
     *  (then the strike is not counted at all).
     */
    public boolean launched(Set<UUID> before, Set<UUID> after) {
        Set<UUID> fresh = new HashSet<>(after);
        fresh.removeAll(before);
        projectile = fresh.size() == 1 ? fresh.iterator().next() : null;
        return projectile != null;
    }

    /** An explosion whose source is the {@code by} projectile (null is not a projectile). {@code true} is the first impact of our projectile. */
    public boolean onBlast(@Nullable UUID by, Vec3 at, int tick, long gameTime) {
        UUID mine = projectile;
        if (mine == null || !mine.equals(by) || impactTick >= 0) return false;
        impactAt = at;
        impactGameTime = gameTime;
        impactTick = tick;
        return true;
    }

    @Nullable
    public UUID projectile() {
        return projectile;
    }

    /** tick of the strike (the clock transmitted by {@link #onBlast}), −1 - there was no strike yet. */
    public int impactTick() {
        return impactTick;
    }

    /** The time of the server's world is at strike, −1 - there has not been a strike yet. */
    public long impactGameTime() {
        return impactGameTime;
    }

    @Nullable
    public Vec3 impactAt() {
        return impactAt;
    }
}
