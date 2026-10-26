package ua.zentix.incoming.warhead;

/**
 * Bunker-buster body ground impact, without Minecraft: no explosion (delay fuze), impact energy only.
 */
public final class BunkerImpact {
    /** TNT-equivalent energy: 4.184 MJ/kg (definition). */
    static final double TNT_J = 4.184e6;
    /**
     * Impact energy fraction into air as pop/entry ejecta (estimate): penetrating impact spends almost all energy
     * crushing medium along path (Young's law); air gets percent-level, use 1%.
     */
    static final double AIR_SHARE = 0.01;
    /** Entry crater: cone radius/depth this many calibers (estimate from penetrating-body entry craters). */
    static final double ENTRY_CALIBRES = 2;

    private BunkerImpact() {}

    /** Impact energy of {@code mass} kg at {@code speed} m/s, kg TNT. */
    public static double equivalentTnt(double mass, double speed) {
        return 0.5 * mass * speed * speed / TNT_J;
    }

    /** Airborne fraction of {@code tnt} kg TNT impact energy, kg TNT. */
    public static double airTnt(double tnt) {
        return tnt * AIR_SHARE;
    }

    /** Ground ejected from entry crater for body {@code diameter} m, m³ (cone π/3·r²·h). */
    public static double entryVolume(double diameter) {
        double r = ENTRY_CALIBRES * diameter;
        return Math.PI / 3 * r * r * r;
    }
}
