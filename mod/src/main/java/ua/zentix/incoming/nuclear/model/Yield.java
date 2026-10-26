package ua.zentix.incoming.nuclear.model;

/**
 * Yield presets (kilotons) with translation key and optimal airburst height. No yields above 15 kt in the mod
 * ({@code Loadout.Nuke.MAX_YIELD}): heavy zone (ground pressure from 5 psi) grows as Y^⅔; for 15 kt at 592 m it reaches
 * 1.6 km — about 32 thousand chunks.
 */
public enum Yield {
    TACTICAL(1, "tactical"),
    HIROSHIMA(15, "hiroshima");

    private final double kt;
    private final String key;

    Yield(double kt, String name) {
        this.kt = kt;
        this.key = "incoming.yield." + name;
    }

    public double kt() {
        return kt;
    }

    /** Translation key, e.g. {@code incoming.yield.hiroshima}. */
    public String key() {
        return key;
    }

    /** Burst height maximizing 5 psi area, m: 240·Y^(1/3) (15 kt → ~590 m, 1 Mt → 2.4 km). */
    public static double optimalBurstHeight(double yieldKt) {
        return 240 * Math.cbrt(yieldKt);
    }
}
