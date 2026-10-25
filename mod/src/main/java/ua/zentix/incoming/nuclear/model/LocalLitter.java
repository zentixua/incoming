package ua.zentix.incoming.nuclear.model;

/**
 * One short local release by a conventional front; after it, the caller applies ordinary Air wind and settling.
 * Dust quantity and entrainment threshold reuse Gaj &amp; Small (1991) in {@link DustModel}; loose-piece drag reuses
 * {@link BlastLitter}. A fixed-height turbulent kick is an estimate: the mean outward front is not an upward wind.
 */
public final class LocalLitter {
    /** Estimated vertical component of a loose-piece kick: 10% of outward motion, within the 0.02--0.12 u turbulent
     * mixing range discussed by Kuhl et al. (1993), also used in DustModel. Not a measured leaf-launch angle. */
    private static final double UPWARD_SHARE = 0.1;
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
        double along = Math.clamp(alongGround, 0, 1), phase = shock.positiveSeconds();
        double wind = shock.windMetresPerSecond() * Math.sqrt(along);
        double friction = BlastLitter.friction(shock.pressurePa() / 1000, along);
        // Air moves even below the entrainment threshold; keeping that travel independent of surface avoids a
        // spurious displacement jump when the first loose piece is released.
        double displacement = Math.max(0, wind * phase / Math.E);
        if (!(phase > 0 && terminalMps > 0 && friction > surface.threshold()))
            return new Lift(0, 0, 0, 0, displacement, Math.max(0, terminalMps));
        // The same excess-shear factor as DustModel.flux: a gradual fraction of loose pieces, not a binary burst.
        double share = 1 - Math.pow(surface.threshold() / friction, 2);
        double mass = Math.min(Math.max(0, surface.supply()), DustModel.soil(shock.pressurePa() / 1000, along, phase,
                surface.threshold(), DustModel.SALTATION_S));
        double velocity = BlastLitter.kick(wind, phase, terminalMps) * share;
        // Integral of u0 (1-x) exp(-x), x=0..1. This is metres, NOT velocity or seconds of continued wind.
        return new Lift(mass, share, velocity, UPWARD_SHARE * velocity, displacement, terminalMps);
    }

    /** Cumulative release at elapsed seconds SINCE DETONATION; zero before arrival, complete after t+. */
    public static Lift at(ShockAtBody shock, DustModel.Surface surface, double alongGround, double terminalMps,
                          double elapsedSeconds) {
        double age = elapsedSeconds - shock.arrivalSeconds();
        if (!(age > 0 && shock.positiveSeconds() > 0)) return empty(terminalMps);
        Lift full = lift(shock, surface, alongGround, terminalMps);
        double x = Math.min(1, age / shock.positiveSeconds());
        // DustModel's normalised positive-phase momentum integral; no second release after the front has passed.
        double released = DustModel.lifted(x);
        // Exact cumulative air travel integral. At x=1 it agrees with lift(), then stays fixed forever.
        double travel = full.airDisplacementM() * Math.E * x * Math.exp(-x);
        return new Lift(full.massKgPerM2() * released, full.releaseShare() * released,
                full.horizontalMps() * released, full.verticalMps() * released, travel, full.settlingMps());
    }

    private static Lift empty(double terminalMps) {
        return new Lift(0, 0, 0, 0, 0, Math.max(0, terminalMps));
    }
}
