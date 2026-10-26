package ua.zentix.incoming.client.fx.plume;

/**
 * Condensed exhaust products make the jet luminous and trail visible: gas (CO₂, H₂O, N₂) barely emits or scatters visible
 * light. Values at 550 nm.
 * <ul>
 * <li>Mass extinction σ_m, m²/kg: τ = σ_m·∫ρ dl (Bouguer–Lambert–Beer). For a sphere of diameter d and density ρ_p,
 * σ_m = 3·Q/(2·ρ_p·d), Q — Mie extinction efficiency (~2 for particles larger than the wavelength).</li>
 * <li>Single-scattering albedo ω: fraction of extinguished light scattered rather than absorbed; smoke colour —
 * {@link #reflectance}.</li>
 * <li>Hot jet particle absorption (1 − ω for incandescent particles): determines flame emissivity ({@code FlameModel}).</li>
 * </ul>
 */
public enum Smoke {
    /**
     * Al₂O₃ from aluminized solid propellant: ω ≈ 0.99 (white); particles ~1.5 μm (est.) (solid-motor nozzle exit mean
     * size 1–4 μm), ρ 3 950, Q ≈ 2.2 → σ_m ≈ 550 m²/kg (range 300–1 000). Incandescent liquid alumina
     * absorbs more than cold alumina: (est.) 0.1.
     */
    ALUMINA(550, 0.99, 0.1),
    /**
     * Double-base (ballistite) propellant salts — stabilizers and flash suppressants (potassium and lead salts), submicron:
     * (est.) d ≈ 0.3 μm, ρ ~2 700, Q ~1 → σ_m ≈ 2 000 m²/kg; ω ≈ 0.95 (grey-white); hot — (est.) 0.1.
     */
    SALTS(2_000, 0.95, 0.1),
    /**
     * Soot: mass absorption 7.5 m²/g (Bond, Bergstrom 2006, at 550 nm) at ω ≈ 0.25 — extinction 10 000 m²/kg; hot soot
     * absorbs equally.
     */
    SOOT(10_000, 0.25, 0.75),
    /**
     * Contrail ice: young crystals ~2 μm (est.) (1–3 μm), ρ 917, Q ≈ 2 → σ_m ≈ 1 600 m²/kg; ω ≈ 0.999.
     */
    ICE(1_600, 0.999, 0);

    /** Forward-scattering asymmetry (Henyey–Greenstein): smoke and clouds 0.6–0.8, (est.) 0.7. */
    public static final double ASYMMETRY = 0.7;

    /** Mass extinction, m²/kg. */
    public final double extinction;
    /** Single-scattering albedo. */
    public final double albedo;
    /** Incandescent jet particle absorption fraction. */
    public final double hotAbsorption;

    Smoke(double extinction, double albedo, double hotAbsorption) {
        this.extinction = extinction;
        this.albedo = albedo;
        this.hotAbsorption = hotAbsorption;
    }

    /**
     * Thick layer reflectance with albedo ω (two-stream, forward scattering with asymmetry g): R = (1 − s)/(1 + s),
     * s = √((1 − ω)/(1 − ω·g)) — dense smoke brightness relative to a white wall under the same light (linear).
     * White Al₂O₃ smoke — ~0.69, salts — ~0.44, soot — ~0.02, ice — ~0.89.
     */
    public static double reflectance(double albedo) {
        double s = Math.sqrt((1 - albedo) / (1 - albedo * ASYMMETRY));
        return (1 - s) / (1 + s);
    }
}
