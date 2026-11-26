package ua.zentix.incoming.strike;

import com.mojang.serialization.Codec;
import ua.zentix.incoming.IncomingConfig;

/**
 * The world rules a conventional strike changes the world under, captured when its order is made ({@link Order}) and
 * carried by each of its rounds ({@link RoundRef#rules}) and by everything the impact leaves behind (blast area jobs,
 * debris), so a rule changed while the rounds are queued or flying reaches only orders made after the change
 * ({@code /incoming rule}). Work with no order behind it (SAM interceptors, tests) reads the rules live when it starts.
 *
 * @param blockDamage  {@code world.block_damage}: blasts remove blocks (craters, breaches, fragments, collapse need it)
 * @param shatterGlass {@code world.shatter_glass}: the wave and fragments break glass
 * @param fire         {@code world.fire}: blasts, fuel and burning debris start fires
 * @param debrisStay   {@code world.debris_stay}: clods, rubble and wreck parts stay as blocks
 * @param collapse     {@code world.collapse}: what a blast leaves without support falls
 * @param grid         {@code grid.enabled}: a blast near a substation cuts its power; nuclear blackout needs it too
 */
public record StrikeRules(boolean blockDamage, boolean shatterGlass, boolean fire, boolean debrisStay, boolean collapse, boolean grid) {
    /** Every rule on: the defaults, and the rules where the server config is not loaded (client, unit tests). */
    public static final StrikeRules ALL = new StrikeRules(true, true, true, true, true, true);
    /** Saved as {@link #bits}; bit positions are part of the save format: append only. */
    public static final Codec<StrikeRules> CODEC = Codec.INT.xmap(StrikeRules::of, StrikeRules::bits);

    private static final int BLOCK_DAMAGE = 1, SHATTER_GLASS = 2, FIRE = 4, DEBRIS_STAY = 8, COLLAPSE = 16, GRID = 32;

    /** The rules as the world's config holds them now. */
    public static StrikeRules live() {
        if (!IncomingConfig.SERVER_SPEC.isLoaded()) return ALL;
        IncomingConfig.Server c = IncomingConfig.SERVER;
        return new StrikeRules(c.blockDamage.get(), c.shatterGlass.get(), c.fire.get(), c.debrisStay.get(), c.collapse.get(), c.gridEnabled.get());
    }

    public int bits() {
        return (blockDamage ? BLOCK_DAMAGE : 0) | (shatterGlass ? SHATTER_GLASS : 0) | (fire ? FIRE : 0) | (debrisStay ? DEBRIS_STAY : 0)
                | (collapse ? COLLAPSE : 0) | (grid ? GRID : 0);
    }

    public static StrikeRules of(int bits) {
        return new StrikeRules((bits & BLOCK_DAMAGE) != 0, (bits & SHATTER_GLASS) != 0, (bits & FIRE) != 0, (bits & DEBRIS_STAY) != 0,
                (bits & COLLAPSE) != 0, (bits & GRID) != 0);
    }
}
