package ua.zentix.incoming.warhead;

import net.minecraft.server.level.ServerLevel;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.util.Air;

/**
 * A conventional strike's show ({@code docs/design/time.md} §8.3, rule 5): it lasts until its cloud has stopped rising and
 * its own crater fuel has burnt out. The cloud's apex is the thermal law's π/N ({@code client.fx.smoke.CloudModel}: N the
 * Brunt–Väisälä frequency of the site's air, 1.7–5 min); the fuel's burn-out is its pool law ({@code FuelPool#seconds}:
 * Shahed 2.5 min, missile 16 min) when its crater fuel lights at all (water, claims or unready ground light none: the
 * apex alone). The blast announces the apex ({@code S2C.Blast#show}); a lit pool extends it ({@code S2C.FuelFire} on
 * clients, {@code Blackouts.extendShow} for outages). What a strike harms that recovers (hearing, a district's power)
 * recovers at real rate until then ({@code util/RecoveryClock}).
 */
public final class StrikeShow {
    /** {@code S2C.Blast} without a show (a recording made before it, an explosion that is no strike). */
    public static final int NONE = -1;

    private StrikeShow() {}

    /**
     * Show length, seconds, in air of Brunt–Väisälä frequency {@code brunt} (s⁻¹), with crater fuel burning out after
     * {@code fuelSeconds} (0 — none lit).
     */
    public static double seconds(double brunt, double fuelSeconds) {
        return Math.max(Math.PI / brunt, fuelSeconds);
    }

    /** The cloud's apex in {@code level} now, game ticks: the show of a strike whose fuel has not lit. */
    public static int apexTicks(ServerLevel level) {
        return ticks(seconds(Air.of(level).brunt(), 0));
    }

    /** The burn-out of {@code spec}'s crater fuel lit on {@code lit} cells, game ticks ({@code WeaponSpec.pool(lit)}). */
    public static int fuelTicks(WeaponSpec spec, int lit) {
        return lit <= 0 || !(spec.fuelAtImpact() > 0) ? 0 : ticks(spec.pool(lit).seconds());
    }

    private static int ticks(double seconds) {
        return (int) Math.ceil(seconds * 20);
    }
}
