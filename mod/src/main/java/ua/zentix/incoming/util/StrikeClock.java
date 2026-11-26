package ua.zentix.incoming.util;

/**
 * A strike's two ages ({@code docs/design/time.md} §8.3, rules 1, 5, 7), pure arithmetic shared by the server and clients.
 * <ul>
 *     <li><b>Motion age M</b> ({@link #motion}): real time since the strike plus every jump counted in its dimension since
 *     ({@link WorldClock#counted}). What the strike put into the air lives on it.</li>
 *     <li><b>Strike clock S</b> ({@link #strike}): M during the show; after it, the world's rate joined by a rate blend,
 *     dS/dt = 1 + (k − 1)·u, u a smoothstep of r / {@link #RAMP_S} (presentation: no visible jump in fire or smoke), r the
 *     real seconds since the show's end and k the world's rate without jumps ({@link WorldClock#rateClock}), plus counted
 *     jumps. It never runs faster than k, never catches up the show, and S ≥ M always. What stays in place lives on it.</li>
 * </ul>
 * All from server anchors: the counted jumps at the strike, and at the show's end its real age, the clock without jumps
 * and the counted jumps; the clock without jumps once the blend is over ({@link #rateAtRamp}, so a later change of k
 * never moves the past). The show ends at its own length or at the first counted jump, whichever comes first
 * ({@link #end}).
 *
 * @param jumpsAtStrike counted jumps in the dimension at the strike, world-clock ticks
 * @param endAge        real ticks from the strike to the show's end; {@link #RUNNING} — the show runs
 * @param rateAtEnd     clock without jumps at the show's end, world-clock ticks
 * @param jumpsAtEnd    counted jumps at the show's end, world-clock ticks
 * @param rateAtRamp    clock without jumps {@link #RAMP_S} after the show's end; NaN — not reached or not recorded
 */
public record StrikeClock(double jumpsAtStrike, long endAge, double rateAtEnd, double jumpsAtEnd, double rateAtRamp) {
    /** Real seconds over which the strike clock joins the world's rate after the show (presentation). */
    public static final double RAMP_S = 60;
    /** {@link #RAMP_S} in game ticks. */
    public static final long RAMP_TICKS = (long) (RAMP_S * 20);
    /** {@link #endAge} of a show that still runs. */
    public static final long RUNNING = -1;
    private static final double S = WorldClock.SECONDS_PER_TICK;

    /** A strike's clock as the strike happens: counted jumps {@code jumps} so far, its show running. */
    public static StrikeClock start(double jumps) {
        return new StrikeClock(jumps, RUNNING, Double.NaN, Double.NaN, Double.NaN);
    }

    public boolean ended() {
        return endAge >= 0;
    }

    /** The show ended {@code age} real ticks after the strike, with clock without jumps {@code rate} and counted jumps {@code jumps}. */
    public StrikeClock end(long age, double rate, double jumps) {
        return new StrikeClock(jumpsAtStrike, Math.max(0, age), rate, jumps, Double.NaN);
    }

    /** The blend is due to be recorded at real age {@code age}: the show ended at least {@link #RAMP_TICKS} ago, not recorded. */
    public boolean rampDue(long age) {
        return ended() && Double.isNaN(rateAtRamp) && age - endAge >= RAMP_TICKS;
    }

    /**
     * The blend's end recorded at real age {@code age} with clock without jumps {@code rate}: exact on time; late (a save
     * from before the anchor), as if the world's rate had been even since the show's end.
     */
    public StrikeClock ramp(long age, double rate) {
        double since = Math.max(RAMP_TICKS, age - endAge);
        return new StrikeClock(jumpsAtStrike, endAge, rateAtEnd, jumpsAtEnd, rateAtEnd + (rate - rateAtEnd) * RAMP_TICKS / since);
    }

    /** Motion age M, seconds, at real age {@code ageTicks} (ticks since the strike) with counted jumps {@code jumps}. */
    public double motion(double ageTicks, double jumps) {
        return Math.max(0, ageTicks) / 20 + Math.max(0, jumps - jumpsAtStrike) * S;
    }

    /**
     * Strike clock S, seconds, at real age {@code ageTicks} with clock without jumps {@code rate} and counted jumps
     * {@code jumps} (all at that moment).
     */
    public double strike(double ageTicks, double rate, double jumps) {
        if (!ended() || ageTicks <= endAge) return motion(ageTicks, jumps);
        double atEnd = motion(endAge, jumpsAtEnd);
        double r = (ageTicks - endAge) / 20;
        double world = Math.max(r, (rate - rateAtEnd) * S);
        double ramp = Double.isNaN(rateAtRamp) ? Double.NaN : Math.max(RAMP_S, (rateAtRamp - rateAtEnd) * S);
        return atEnd + blend(r, world, ramp) + Math.max(0, jumps - jumpsAtEnd) * S;
    }

    /**
     * Strike-clock seconds since the show's end: ∫ 1 + (k − 1)·u dr with u = smoothstep(r / {@link #RAMP_S}), from the world
     * seconds {@code world} since the end (rate alone, at least {@code r}) and at the blend's end {@code atRamp} (NaN —
     * unknown: an even rate since the end). For a constant k: r + (k − 1)·∫u.
     */
    public static double blend(double r, double world, double atRamp) {
        if (r <= 0) return 0;
        world = Math.max(world, r);
        if (r <= RAMP_S) {
            // ∫0^r u = r·(x² − x³/2), x = r / RAMP_S: the world's share of each second grows smoothly from 0 to 1
            double x = r / RAMP_S;
            return r + (world - r) * (x * x - x * x * x / 2);
        }
        double w60 = Double.isNaN(atRamp) ? world * RAMP_S / r : atRamp;
        w60 = Math.min(Math.max(w60, RAMP_S), world);
        return RAMP_S + (w60 - RAMP_S) / 2 + (world - w60);
    }
}
