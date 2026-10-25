package ua.zentix.incoming.nuclear.model;

/** Transient screen-response dose, not a clinical injury probability. No rendering, settings or Minecraft here. */
public final class ConcussionDose {
    private ConcussionDose() {}

    /**
     * Dimensionless dose 0..1 from the front at the head, HearingModel threshold shift in dB and peak absolute
     * head rotation (body + neck from BodySway) in radians. Below the pressure/impulse gate it is exactly zero.
     */
    public static double dose(ShockAtBody shock, double hearingShiftDb, double headRadians) {
        throw new UnsupportedOperationException("SBM interface commit: implementation follows");
    }

    /** Screen-response lifetime, s; estimate for presentation, not medical recovery time. Zero dose has zero life. */
    public static double durationSeconds(double dose) {
        throw new UnsupportedOperationException("SBM interface commit: implementation follows");
    }

    /** Dimensionless screen response 0..1 at seconds SINCE FRONT ARRIVAL; zero at negative time and after recovery. */
    public static double strength(double dose, double sinceArrivalSeconds) {
        throw new UnsupportedOperationException("SBM interface commit: implementation follows");
    }
}
