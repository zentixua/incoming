package ua.zentix.incoming.nuclear.model;

/** Conventional ground motion in SI units; independent of the nuclear ground-shock path and of Minecraft. */
public final class GroundShock {
    private GroundShock() {}

    /** GroundMaterial maps block materials to these homogeneous propagation media; enum order is not a wire id. */
    public enum Medium { LOOSE, SOIL, SATURATED, ROCK }

    /**
     * @param tntKg TNT-equivalent source energy assigned to ground, kg, before depth coupling (not air charge)
     * @param depthM burial below the local surface, m; zero means contact
     * @param medium homogeneous ground approximation selected at the source
     */
    public record Source(double tntKg, double depthM, Medium medium) {}

    /**
     * @param arrivalSeconds time since the source event, s, R/c (not rounded to ticks)
     * @param peakVelocityMps peak ground particle velocity, m/s, before perceptibility filtering
     * @param frequencyHz dominant ground vibration frequency, Hz (estimate)
     * @param perceptibility smooth 0..1 human-perception weight; not damage or a camera angle
     */
    public record Sample(double arrivalSeconds, double peakVelocityMps, double frequencyHz, double perceptibility) {}

    /** Ground motion at slant range in m. Implementation follows in the model commit; not wired by the API commit. */
    public static Sample sample(Source source, double rangeM) {
        throw new UnsupportedOperationException("SBM interface commit: implementation follows");
    }

    /** Depth coupling f, dimensionless; source mass is kg and depth is m. */
    public static double coupling(Source source) {
        throw new UnsupportedOperationException("SBM interface commit: implementation follows");
    }

    /** Propagation speed c in m/s; this is not peak ground particle velocity. */
    public static double waveSpeed(Medium medium) {
        throw new UnsupportedOperationException("SBM interface commit: implementation follows");
    }

    /** Signed ground velocity in m/s at elapsed seconds since source; zero before arrival, smooth onset and end. */
    public static double velocityMps(Sample sample, double elapsedSeconds) {
        throw new UnsupportedOperationException("SBM interface commit: implementation follows");
    }

    /** Range in m where peak ground velocity reaches the lower perception threshold; for packet recipients. */
    public static double feltRangeM(Source source) {
        throw new UnsupportedOperationException("SBM interface commit: implementation follows");
    }
}
