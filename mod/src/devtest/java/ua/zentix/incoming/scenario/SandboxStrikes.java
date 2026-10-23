package ua.zentix.incoming.scenario;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.Warheads;

/** Серверная часть {@code /fx real}: без полёта, заряд в заданной точке; не входит в jar мода. */
public final class SandboxStrikes {
    private SandboxStrikes() {}

    public static void detonate(ServerLevel level, WeaponType weapon, Vec3 at) {
        if (weapon == WeaponType.NUKE) throw new IllegalArgumentException("ядерка — nuke <кт>");
        // У бомбы заряд и пакет — у BunkerBlast, не у SurfaceBlast (spec.blast() == NONE).
        // real задаёт точку подрыва, а не моделирует проникание: вход и заряд здесь на поверхности.
        if (weapon == WeaponType.BUNKER) Warheads.bunker(level, at, at, null, null);
        else Warheads.detonate(level, weapon, at, null, null);
    }
}
