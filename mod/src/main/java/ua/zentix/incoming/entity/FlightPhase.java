package ua.zentix.incoming.entity;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Projectile flight phase, shared by all carriers and synchronized to the client: drives plume, smoke,
 * condensation, and sound selection (booster roar, engine spinup, dive whistle).
 * <p>
 * Typical path: Shahed and missile, READY → IGNITION → BOOST → CLIMB → CRUISE → (POP_UP) → TERMINAL; B-2, CRUISE
 * → EGRESS, its bomb, TERMINAL → DRILL; ICBM, IGNITION → BOOST.
 */
public enum FlightPhase {
    /** On the launcher, booster not burning yet. */
    READY,
    /** Booster ignited, projectile still on the rail: smoke cloud and flame at the launcher. */
    IGNITION,
    /** Booster burning: launch rail exit, acceleration, and climb. */
    BOOST,
    /**
     * Booster dropped; cruise engine (Shahed propeller, missile turbine) spools up, climbs, and turns onto
     * course.
     */
    CLIMB,
    /** Cruise flight along the route. */
    CRUISE,
    /** Popup before the dive (cruise missile). */
    POP_UP,
    /** Final leg: dive at the target. */
    TERMINAL,
    /** Bunker-buster bomb drilling into ground. */
    DRILL,
    /** Bomber departing the target after release. */
    EGRESS,
    /** Loitering above the target area: circles while waiting for a target. */
    LOITER;

    private static final FlightPhase[] VALUES = values();

    public static FlightPhase byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : CRUISE;
    }

    public static FlightPhase byName(String name) {
        for (FlightPhase p : VALUES) {
            if (p.getSerializedName().equals(name)) return p;
        }
        return CRUISE;
    }

    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Player-facing phase name (HUD, projectile camera, operator map). */
    public Component displayName() {
        return Component.translatable("incoming.phase." + getSerializedName());
    }

    /** Launch booster burning (flame, thick smoke, roar). */
    public boolean boosterLit() {
        return this == IGNITION || this == BOOST;
    }

    /** Still on the launcher. */
    public boolean onLauncher() {
        return this == READY || this == IGNITION;
    }

    /** Booster launch leg: block collisions ignored (launch rail exit through foliage). */
    public boolean launching() {
        return ordinal() <= BOOST.ordinal();
    }
}
