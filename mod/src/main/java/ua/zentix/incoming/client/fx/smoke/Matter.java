package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.client.fx.plume.Smoke;

/**
 * Condensed engine products ({@link Smoke}: Al₂O₃, salts, soot, ice) as smoke-grid substances. Grid has three:
 * soot (ω 0.25), dust (ω from ground colour), droplets (ω ≈ 1, no absorption). Product with extinction σ_m and
 * albedo ω becomes soot/droplet mixture with same raw optical depth σ_m·m and albedo: soot optical-depth share
 * (1 − ω)/(1 − ω_soot), rest droplets. Droplet asymmetry 0.85 (engine smoke 0.6–0.8, {@link Smoke#ASYMMETRY}):
 * they scatter slightly more forward — known difference, not tuning.
 */
public final class Matter {
    private Matter() {}

    /** out[0] soot, out[1] droplets, kg: same optical depth and albedo as kg of product smoke. */
    public static void of(Smoke smoke, double kg, double[] out) {
        double e = smoke.extinction * kg;
        double soot = Math.max(0, Math.min(e, e * (1 - smoke.albedo) / (1 - CloudModel.SOOT_W)));
        out[0] = soot / CloudModel.SOOT_K;
        out[1] = (e - soot) / CloudModel.DROP_K;
    }
}
