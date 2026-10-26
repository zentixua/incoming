package ua.zentix.incoming.entity;

/**
 * Empty booster cleanup is not physical: 20 s after separation or 5 s after landing, whichever comes first; below
 * the world bottom, 3 s with the same smooth fade. Model and glow fade together over 2 s; the last second is
 * invisible so the removal packet and small server/client clock differences do not cut off light. Wall
 * temperature is unchanged by this presentation.
 */
public final class BoosterLifetime {
    public static final int MAX_AGE = 400;
    public static final int LIE_TICKS = 100;
    public static final int FADE_TICKS = 40;
    public static final int HIDDEN_TICKS = 20;

    private BoosterLifetime() {}

    /** Remaining lifetime on the server clock → shared visibility of mesh and hot nozzle. */
    public static float visibility(double remainingTicks) {
        double x = Math.clamp((remainingTicks - HIDDEN_TICKS) / FADE_TICKS, 0, 1);
        return (float) (x * x * (3 - 2 * x));
    }
}
