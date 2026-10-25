package ua.zentix.incoming.nuclear.model;

/**
 * Transient screen-response dose, not a clinical injury probability. No rendering, settings or Minecraft here.
 * <p>
 * Human low-level blast data do not establish a universal safe pressure or a clinical pressure--impulse curve:
 * Carr et al. (2015),
 * https://pmc.ncbi.nlm.nih.gov/articles/PMC4360700/ (repeated occupational exposure). Repeated exposure is NOT
 * modelled here. Pressure and impulse gate the response together; auditory shift and measured head motion modify
 * its strength. All numerical dose thresholds below are estimates for presentation, not medical thresholds.
 */
public final class ConcussionDose {
    /** Estimated screen onset: 10 kPa AND 20 Pa s. Not the disputed 4 psi brain-injury/safety criterion. */
    public static final double PRESSURE_ONSET_PA = 10_000, IMPULSE_ONSET_PA_SECONDS = 20;
    /** Estimated unit contributions: 40 dB temporary threshold shift and 5 degrees peak head motion. */
    private static final double HEARING_SCALE_DB = 40, HEAD_SCALE_RADIANS = Math.toRadians(5);
    /** Estimated presentation timing only: 1--3 s recovery, 20 ms smooth onset after front arrival. */
    private static final double MIN_SECONDS = 1, EXTRA_SECONDS = 2, ATTACK_SECONDS = 0.02;
    private ConcussionDose() {}

    /**
     * Dimensionless dose 0..1 from the front at the head, HearingModel threshold shift in dB and peak absolute
     * head rotation (body + neck from BodySway) in radians. Below the pressure/impulse gate it is exactly zero.
     */
    public static double dose(ShockAtBody shock, double hearingShiftDb, double headRadians) {
        // Known backing-wall reflection contributes peak pressure; impulse remains the incident model's impulse,
        // since neither the record nor BlastBody specifies a reflected time history. No invented reflected impulse.
        double p = Math.log(Math.max(1, Math.max(shock.pressurePa(), shock.reflectedPressurePa()) / PRESSURE_ONSET_PA));
        double i = Math.log(Math.max(1, shock.impulsePaSeconds() / IMPULSE_ONSET_PA_SECONDS));
        if (!(p > 0 && i > 0)) return 0;
        double modifiers = 1 + Math.max(0, hearingShiftDb) / HEARING_SCALE_DB + Math.abs(headRadians) / HEAD_SCALE_RADIANS;
        return -Math.expm1(-p * i * modifiers);
    }

    /** Screen-response lifetime, s; estimate for presentation, not medical recovery time. Zero dose has zero life. */
    public static double durationSeconds(double dose) {
        return dose > 0 ? MIN_SECONDS + EXTRA_SECONDS * Math.min(1, dose) : 0;
    }

    /** Dimensionless screen response 0..1 at seconds SINCE FRONT ARRIVAL; zero at negative time and after recovery. */
    public static double strength(double dose, double sinceArrivalSeconds) {
        double duration = durationSeconds(dose), t = sinceArrivalSeconds;
        if (!(dose > 0 && t > 0 && t < duration)) return 0;
        double attack = smooth(t / ATTACK_SECONDS);
        double decay = 1 - smooth((t - ATTACK_SECONDS) / (duration - ATTACK_SECONDS));
        return Math.min(1, dose) * attack * decay;
    }

    private static double smooth(double x) {
        x = Math.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
