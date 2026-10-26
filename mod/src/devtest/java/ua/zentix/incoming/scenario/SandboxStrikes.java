package ua.zentix.incoming.scenario;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.Warheads;

/** Server part {@code /fx real}: no flight, charge at a given point; not included in jar mod. */
public final class SandboxStrikes {
    private SandboxStrikes() {}

    public static void detonate(ServerLevel level, WeaponType weapon, Vec3 at) {
        if (weapon == WeaponType.NUKE) throw new IllegalArgumentException("the nuke - nuke <kt>");
        // The bomb has a charge and package - BunkerBlast, not SurfaceBlast (spec.blast() == NONE).
        // real sets the detonation point, and does not model penetration: the entrance and charge are here on the surface.
        if (weapon == WeaponType.BUNKER) Warheads.bunker(level, at, at, null, null);
        else Warheads.detonate(level, weapon, at, null, null);
    }
}
