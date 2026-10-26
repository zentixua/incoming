package ua.zentix.incoming.nuclear.model;

/**
 * Acute radiation sickness by accumulated dose (Gy at body surface — instrument reading, Glasstone & Dolan 1977 §12.108)
 * and time after exposure (game hours, 1000 ticks). Course follows G&D table 12.108 and §12.113–12.122: prodrome
 * (nausea, vomiting), latent period, manifest illness; then recovery ({@link Stage#NONE}) or death ({@link Stage#FATAL}).
 *
 * <p>Whether an exposed subject dies is determined by their own random {@code fate} (0..1): death if
 * {@code fate < deathChance(dose)}. Curve is lognormal in untreated dose: LD50 ≈ 4.5 Gy at surface (≈ 3.2 Gy midbody:
 * midbody is 70 % of surface, G&D §12.108); spread fitted so almost nobody dies at 2 Gy, 80 % at 6 Gy, everyone at 10 Gy:
 * G&D's “0–80 %” at 2–6 Gy and “80–100 %” at 6–10 Gy.
 *
 * <p>Times are dose anchors (log dose/time interpolation), G&D table 12.108:
 * <ul>
 *   <li>vomiting onset: 3 h at 1 Gy, 2 h at 2, 1 h at 6, 30 min at 10, immediate at 50 (“delay time”);</li>
 *   <li>prodrome: “first day or two” (§12.115–12.116), shorter at high doses;</li>
 *   <li>latent period: “up to two weeks or more” at 1–2 Gy, “a few days to two weeks” at 2–10 Gy;
 *       gastrointestinal form (10–50 Gy) — days, cerebral form (from 50 Gy) — none;</li>
 *   <li>death: hematopoietic form (2–10 Gy) — “critical period 4–6 weeks”, gastrointestinal (10–50 Gy) — “5–14 days”,
 *       cerebral — “1–48 hours”: 36 h at 50 Gy (Kelley case, 1958: about 40 Gy, death after 35 h), faster with dose as
 *       D^−1.5 (estimate). Anchors: 6 weeks at 2 Gy, 30 days at 6, 12 at 10, 36 h at 50; form transitions between them
 *       (18 days at 8 Gy, 5 at 20, 2 at 40);</li>
 *   <li>survivor recovery: manifest illness ends after 5 weeks at 1 Gy, 8 at 2, 10 at 6 Gy and above
 *       (followed by months of weakness, omitted here).</li>
 * </ul>
 */
public final class SicknessModel {
    /** Sickness stage. */
    public enum Stage { NONE, PRODROMAL, LATENT, MANIFEST, FATAL }

    /** Untreated lethal dose for half of subjects, Gy at body surface. */
    public static final double LD50 = 4.5;
    /** Lognormal death-curve spread (σ of log dose). */
    public static final double LD_BETA = 0.34;
    /** No sickness below this (G&D: below 1 Gy, no symptoms). */
    public static final double MIN_DOSE = 1;

    private static final double DAY = 24;
    /** Anchor doses, Gy. */
    private static final double[] DOSE = {1, 2, 6, 10, 50};
    /** Prodrome onset, h. */
    private static final double[] ONSET = {3, 2, 1, 0.5, 0.1};
    /** Prodrome duration, h. */
    private static final double[] PRODROMAL = {24, 36, 24, 12, 2};
    /** End of latent period, h after exposure (at 50 Gy, immediately after prodrome). */
    private static final double[] LATENT_END = {21 * DAY, 18 * DAY, 12 * DAY, 5 * DAY, 2.1};
    /** Death of doomed subjects, h after exposure. */
    private static final double[] DEATH = {42 * DAY, 42 * DAY, 30 * DAY, 12 * DAY, 36};
    /** End of manifest illness in survivors, h after exposure. */
    private static final double[] RECOVERY = {35 * DAY, 56 * DAY, 70 * DAY, 70 * DAY, 70 * DAY};
    /** Above 50 Gy, death comes sooner: 36 h·(50/D)^1.5. */
    private static final double CNS_EXP = 1.5;

    /** Course at a dose: times after exposure, h. */
    public record Course(double onset, double latentFrom, double manifestFrom, double death, double recovery) {
    }

    private SicknessModel() {
    }

    /** Untreated death probability at dose {@code doseGy}: Φ(ln(D/LD50)/β); below 1 Gy — 0. */
    public static double deathChance(double doseGy) {
        if (doseGy < MIN_DOSE) return 0;
        return Fragility.probability(doseGy, LD50, LD_BETA);
    }

    /** Whether a subject with random {@code fate} (0..1) dies at {@code doseGy}. */
    public static boolean fated(double doseGy, double fate) {
        return fate < deathChance(doseGy);
    }

    /** Course at {@code doseGy}; null — below 1 Gy, no sickness. */
    public static Course course(double doseGy) {
        if (doseGy < MIN_DOSE) return null;
        double onset = at(ONSET, doseGy);
        double latentFrom = onset + at(PRODROMAL, doseGy);
        double death = doseGy > DOSE[DOSE.length - 1]
                ? DEATH[DEATH.length - 1] * Math.pow(DOSE[DOSE.length - 1] / doseGy, CNS_EXP)
                : at(DEATH, doseGy);
        // at high doses, everything compresses toward death: immediate vomiting, prodrome up to half the remaining time
        onset = Math.min(onset, death * 0.1);
        latentFrom = Math.min(latentFrom, death * 0.5);
        double manifestFrom = Math.max(latentFrom, Math.min(at(LATENT_END, doseGy), death * 0.5));
        return new Course(onset, latentFrom, manifestFrom, death, Math.max(manifestFrom, at(RECOVERY, doseGy)));
    }

    /** Stage at doseGy after hours game hours since exposure; {@code fated} — subject is doomed. */
    public static Stage stage(double doseGy, double hours, boolean fated) {
        Course c = course(doseGy);
        if (c == null || hours < c.onset) return Stage.NONE;
        if (hours < c.latentFrom) return Stage.PRODROMAL;
        if (hours < c.manifestFrom) return Stage.LATENT;
        if (fated) return hours < c.death ? Stage.MANIFEST : Stage.FATAL;
        return hours < c.recovery ? Stage.MANIFEST : Stage.NONE;
    }

    /** Game hours to prodrome onset; infinity for dose < 1 Gy. */
    public static double onsetHours(double doseGy) {
        Course c = course(doseGy);
        return c == null ? Double.POSITIVE_INFINITY : c.onset;
    }

    /** Maximum health reduction during manifest illness, units (2 = one heart). */
    public static double maxHealthPenalty(double doseGy) {
        if (doseGy >= 6) return 10;
        if (doseGy >= 2) return 4;
        return 0;
    }

    /** Hunger consumption multiplier during manifest illness. */
    public static double hungerMultiplier(double doseGy) {
        if (doseGy >= 2) return 2;
        if (doseGy >= 1) return 1.5;
        return 1;
    }

    /** No natural regeneration during manifest illness (≥ 2 Gy). */
    public static boolean noRegeneration(double doseGy) {
        return doseGy >= 2;
    }

    /** Weakness level during manifest illness (effect amplifier): −1 — none, 0 — I, 1 — II. */
    public static int weaknessAmplifier(double doseGy) {
        if (doseGy >= 6) return 1;
        if (doseGy >= 1) return 0;
        return -1;
    }

    /** Anchor table value at a dose: log dose/value interpolation, clamped to edge values beyond the table. */
    private static double at(double[] values, double doseGy) {
        if (doseGy <= DOSE[0]) return values[0];
        int last = DOSE.length - 1;
        if (doseGy >= DOSE[last]) return values[last];
        int i = 1;
        while (DOSE[i] < doseGy) i++;
        double t = Math.log(doseGy / DOSE[i - 1]) / Math.log(DOSE[i] / DOSE[i - 1]);
        return Math.exp(Math.log(values[i - 1]) + t * Math.log(values[i] / values[i - 1]));
    }
}
