package ua.zentix.incoming.warhead;

import net.minecraft.server.level.ServerLevel;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.util.Air;

/**
 * A conventional strike's show ({@code docs/design/time.md} §8.3, rule 5): it lasts until its cloud has stopped rising and
 * its own crater fuel has burnt out. The cloud's apex is the thermal law's π/N ({@code client.fx.smoke.CloudModel}: N the
 * Brunt–Väisälä frequency of the site's air, 1.7–5 min); the fuel's burn-out is its pool law ({@code FuelPool#seconds}:
 * Shahed 2.5 min, missile 16 min). What a strike harms that recovers (hearing, a district's power) recovers at real rate
 * until then ({@code util/RecoveryClock}).
 */
public final class StrikeShow {
    /** {@code S2C.Blast} without a show (a recording made before it, an explosion that is no strike). */
    public static final int NONE = -1;

    private StrikeShow() {}

    /**
     * Show length, seconds, for weapon {@code spec} in air of Brunt–Väisälä frequency {@code brunt} (s⁻¹); {@code fuel}: its
     * crater fuel burns (on the ground, fire allowed).
     */
    public static double seconds(WeaponSpec spec, double brunt, boolean fuel) {
        double apex = Math.PI / brunt;
        double burn = fuel && spec.fuelAtImpact() > 0 ? spec.pool().seconds() : 0;
        return Math.max(apex, burn);
    }

    /** Show length in game ticks for a strike in {@code level} now ({@link #seconds}). */
    public static int ticks(ServerLevel level, WeaponSpec spec, boolean fuel) {
        return (int) Math.ceil(seconds(spec, Air.of(level).brunt(), fuel) * 20);
    }
}
