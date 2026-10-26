package ua.zentix.incoming.nuclear.model;

/**
 * Prompt radiation in the first minute (gamma and neutrons): D = K·Y·exp(−R/λ)/R², rem (≈ cGy in air: kerma received at
 * the body surface). R is slant range, m.
 *
 * <p>K and λ fitted to free-air kerma at Nagasaki (21 kt, height 503 m; RERF: gamma 7.8 Gy at 1 km from epicenter,
 * 0.89 at 1.5 km, 0.13 at 2 km, 0.021 at 2.5 km; neutrons add about 10 % at 1 km): model gives 8.6 / 1.06 / 0.145 / 0.022 Gy.
 * Hiroshima (16 kt, 600 m; thick-cased gun-type bomb emitted less gamma) — 3.9 Gy gamma at 1 km versus modeled 5.2.
 *
 * <p>Material attenuation uses mass along the path (kg/m²): first-minute gamma half-value layer ≈ 320 kg/m², nearly
 * material-independent (Glasstone & Dolan 1977, table 8.41: steel 1.5″, concrete 6″, earth 7.5″, water 13″, wood 23″);
 * fallout (lower energy) ≈ 130 kg/m² (ibid.: 0.7″, 2.2″, 3.3″, 4.8″, 8.8″).
 */
public final class PromptRadiationModel {
    /** Air attenuation length, m. */
    public static final double LAMBDA = 330;
    /** Coefficient, rem·m²/kt (Nagasaki, gamma and neutrons). */
    public static final double K = 1.52e9;
    /** Rem per gray. */
    public static final double REM_PER_GY = 100;
    /** First-minute radiation half-value layer, kg/m² (concrete — 14 cm, earth — 19 cm). */
    public static final double HALF_PROMPT = 320;
    /** Fallout gamma half-value layer, kg/m² (concrete — 5.6 cm, earth — 8 cm). */
    public static final double HALF_FALLOUT = 130;

    private PromptRadiationModel() {
    }

    public static double doseRem(double slantRangeM, double yieldKt) {
        double r = Math.max(slantRangeM, 1);
        return K * yieldKt * Math.exp(-r / LAMBDA) / (r * r);
    }

    /** Fraction transmitted through {@code massKgM2} of material with half-value layer {@code half}. */
    public static double transmission(double massKgM2, double half) {
        return Math.pow(0.5, massKgM2 / half);
    }
}
