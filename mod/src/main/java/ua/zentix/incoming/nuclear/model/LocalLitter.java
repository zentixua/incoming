package ua.zentix.incoming.nuclear.model;

/** One short local release by a conventional front; after it, the caller applies ordinary Air wind and settling. */
public final class LocalLitter {
    private LocalLitter() {}

    /**
     * @param massKgPerM2 lofted surface dust per unit area, kg/m^2, bounded by Surface.supply
     * @param releaseShare smooth dimensionless 0..1 fraction of loose pieces released; caller supplies particle budget
     * @param horizontalMps outward release velocity, m/s, after the positive-phase drag impulse
     * @param verticalMps upward release velocity, m/s
     * @param airDisplacementM positive-phase air travel, m (integrated pulse, no continued blast wind)
     * @param settlingMps terminal fall speed in still air, m/s
     */
    public record Lift(double massKgPerM2, double releaseShare, double horizontalMps, double verticalMps,
                       double airDisplacementM, double settlingMps) {}

    /**
     * Total release over the positive phase. Surface is the actual source material; alongGround is dynamic-pressure
     * fraction 0..1 parallel to that surface; terminalMps is the piece's still-air fall speed, m/s.
     */
    public static Lift lift(ShockAtBody shock, DustModel.Surface surface, double alongGround, double terminalMps) {
        throw new UnsupportedOperationException("SBM interface commit: implementation follows");
    }

    /** Cumulative release at elapsed seconds SINCE DETONATION; zero before arrival, complete after t+. */
    public static Lift at(ShockAtBody shock, DustModel.Surface surface, double alongGround, double terminalMps,
                          double elapsedSeconds) {
        throw new UnsupportedOperationException("SBM interface commit: implementation follows");
    }
}
