package ua.zentix.incoming.warhead;

import ua.zentix.incoming.nuclear.model.CraterModel;

/**
 * Underground charge explosion, without Minecraft, shared by server (cavity, crushing, collapse, crater) and
 * client (cloud, sound). Scaled depth λ = d/W^⅓ (m/kg^⅓), radii proportional to W^⅓ (TM 5-855-1; Westine); W kg
 * TNT. Confined-blast laws are only roughly known: each value below explicitly an estimate with range.
 *
 * @param tnt charge, kg TNT
 * @param depth charge depth below overlying top, m
 */
public record BuriedBlast(double tnt, double depth) {
    /**
     * Cavity radius, ·W^⅓ m: 0.12–0.5 (between underground nuclear/chemical data, TM 5-855-1, broad estimate);
     * logarithmic midpoint 0.25: 2.4 t gives 3.4 m.
     */
    static final double CAVITY = 0.25;
    /** Crushing zone in cavity radii (review says “several radii”, estimate). */
    static final double CRUSH = 3;
    /**
     * Cracking/tunnel and cave collapse up to this ·W^⅓ m (review says “tens of meters”, estimate): 2.4 t gives
     * 27 m.
     */
    static final double FRACTURE = 2;
    /** Below this scaled depth, explosion ejects ground outward, forming crater (TM 5-855-1: ~1 m/kg^⅓). */
    static final double BREACH = 1;
    /**
     * Cavity roof collapses upward in chimney this many cavity radii (estimate from underground nuclear test
     * chimneys, 4–6 radii); near top causes surface subsidence.
     */
    static final double CHIMNEY = 5;

    /** Overburden density, kg/m³ (estimate: soil 1600–2000, rock 2600–2700). */
    static final double ROCK_DENSITY = 2400;

    /**
     * Roof collapse energy above cavity {@code volume} m³, kg TNT: cavity-volume rock falls chimney height
     * ({@link #CHIMNEY} cavity radii), m·g·h.
     */
    public static double collapseTnt(double volume) {
        double r = Math.cbrt(3 * volume / (4 * Math.PI));
        return volume * ROCK_DENSITY * 9.80665 * CHIMNEY * r / BunkerImpact.TNT_J;
    }

    /** W^⅓, kg^⅓. */
    public double cube() {
        return Math.cbrt(tnt);
    }

    /** Scaled depth λ, m/kg^⅓. */
    public double scaledDepth() {
        return depth / cube();
    }

    /** Explosion ejects ground outward (λ < {@value #BREACH}): ejecta crater/cloud like surface burst. */
    public boolean breaches() {
        return scaledDepth() < BREACH;
    }

    /** Cavity radius, m. */
    public double cavity() {
        return CAVITY * cube();
    }

    /** Crushing-zone radius, m. */
    public double crush() {
        return CRUSH * cavity();
    }

    /** Tunnel/cave roof collapse reach, m. */
    public double fracture() {
        return FRACTURE * cube();
    }

    /** Chimney height above cavity center, m. */
    public double chimney() {
        return CHIMNEY * cavity();
    }

    /** Roof collapse reaches top: surface subsidence (blast did not break out, but chimney reaches top). */
    public boolean subsides() {
        return !breaches() && depth <= chimney();
    }

    /**
     * Visible ejecta-crater radius in {@code soil}, m: at surface, surface-charge crater ({@link
     * CraterModel#chargeRadius}); initially wider with depth (more ground ejected), vanishing at breakout
     * boundary (λ = {@value #BREACH}), beyond which only contained cavity. Shape R₀·(1 + 2λ)·(1 − λ²): maximum
     * ~1.5 R₀ near λ ≈ 0.5 (shape estimate from burst-depth reviews, scaled-depth crater curves; values not
     * checked against primary source). 0 means no crater.
     */
    public double craterRadius(CraterModel.Soil soil) {
        if (!breaches()) return 0;
        double l = Math.max(0, scaledDepth()) / BREACH;
        return CraterModel.chargeRadius(tnt, soil) * (1 + 2 * l) * (1 - l * l);
    }

    /** Visible ejecta-crater depth, m: quarter diameter like surface-charge crater. */
    public double craterDepth(CraterModel.Soil soil) {
        return 0.5 * craterRadius(soil);
    }

    /** Ejecta-crater bowl volume (paraboloid π/2·r²·h), m³: ground ejected. */
    public double craterVolume(CraterModel.Soil soil) {
        double r = craterRadius(soil);
        return Math.PI / 2 * r * r * craterDepth(soil);
    }

    /**
     * Charge energy fraction entering air as wave ({@code hole} is bore cross-section, m²; 0 absent). Deep burst
     * vents only bore gases: bore share of cavity wall, hole / (4π·R²) (estimate: 2.4 t, 0.8 m bore gives 0.35%).
     * Breakout gives 1 − λ (full charge at surface, bore share at boundary; estimate).
     */
    public double airShare(double hole) {
        double r = cavity(), through = Math.min(1, hole / (4 * Math.PI * r * r));
        return breaches() ? Math.max(through, 1 - Math.max(0, scaledDepth()) / BREACH) : through;
    }

    /** Charge heard/seen above ground, kg TNT: {@link #airShare} of charge. */
    public double airCharge(double hole) {
        return tnt * airShare(hole);
    }
}
