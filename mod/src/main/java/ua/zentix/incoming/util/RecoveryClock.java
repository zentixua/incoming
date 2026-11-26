package ua.zentix.incoming.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Objects;

/**
 * The clock of a state with several causes ({@code docs/design/time.md} §8.3, rule 8): a body's hearing, burns and
 * sickness, a district's outage. Pure arithmetic shared by the server and clients.
 * <ul>
 *     <li>While any cause is in its show, the state recovers at real rate.</li>
 *     <li>After the last show ends, it recovers at the world's rate k ({@link WorldClock#rateClock}) joined by the strikes'
 *     rate blend ({@link StrikeClock#blend}: dR/dt = 1 + (k − 1)·u, u a smoothstep of the real seconds since that end over
 *     {@link StrikeClock#RAMP_S}).</li>
 *     <li>Every jump of its dimension counts, counted and unseen alike ({@link WorldClock#counted}, {@link WorldClock#unseen}):
 *     a sleep heals as a night does.</li>
 *     <li>The owner steps it about once a second while the state is in the world. A step after a gap longer than
 *     {@link #PRESENT_GAP} game ticks is an absence (a player offline, a mob in an unloaded chunk): nothing elapsed for
 *     the state. Bodies age only while present. A step in another dimension (whose clocks are its own) counts nothing
 *     either.</li>
 * </ul>
 * Causes: the youngest detonation that acted on the state ({@link #strike}; all detonations' shows last alike and a counted
 * jump ends all of a dimension's shows at once, so the youngest ends last; detonation ids are per dimension, so a step in
 * another dimension drops it and blends from there), and the latest end of a conventional strike's
 * show ({@link #until}, game time; a counted jump ends it). It also counts the event time ({@link #event}: real time plus
 * counted jumps, the motion age M of rule 1) for exposure windows such as the ear's 2-minute dose.
 * <p>
 * Values in world-clock ticks (1000 an hour). A new clock starts where its owner says: bodies and outages from before this
 * clock start at the world clock, so their saved times keep their meaning.
 */
public final class RecoveryClock {
    /** A gap between steps longer than this, game ticks, is an absence: nothing elapsed for the state. */
    public static final long PRESENT_GAP = 60;
    /** {@link #strike} without a detonation cause. */
    public static final int NO_STRIKE = -1;
    /** No time: no conventional show, no show ended yet, no step yet. */
    public static final long NONE = Long.MIN_VALUE;
    /** {@link Shows#ended} of a show still running. */
    public static final long RUNNING = Long.MAX_VALUE;

    /** Where detonation shows stand. */
    @FunctionalInterface
    public interface Shows {
        /**
         * Game time detonation {@code strike}'s show ended; {@link #RUNNING} — it runs; {@link #NONE} — not known (forgotten,
         * another dimension): long over.
         */
        long ended(int strike);

        Shows NONE_KNOWN = strike -> NONE;
    }

    private double clock, event;
    private int strike;
    private long until, endedAt;
    // stepping state, not saved: a load is an absence
    private long lastGame = NONE;
    private double lastRate, lastCounted, lastUnseen;
    private Object lastDim;
    /** Recovery per game tick over the last step (0 after an absence). */
    private double perTick;
    /** How far this clock has fallen behind the world clock since it was made (or loaded), world-clock ticks. */
    private double behind;
    /** The last step ran wholly at the world's rate: no show, the blend over. */
    private boolean settled;
    /** The last step counted nothing ({@link #fresh}). */
    private boolean fresh = true;
    /** A cause was in its show at the last step. */
    private boolean lastInShow;
    /** A gap between steps longer than this is an absence, game ticks. */
    private long gap = PRESENT_GAP;

    /** A body's clock starting at {@code clock}, world-clock ticks. */
    public RecoveryClock(double clock) {
        this(clock, clock, NO_STRIKE, NONE, NONE);
    }

    /**
     * A district's clock: a district never leaves the world, so a gap between steps is time that passed (a server busy
     * elsewhere), never an absence. Read as {@link #behind}.
     */
    public static RecoveryClock district() {
        RecoveryClock c = new RecoveryClock(0);
        c.gap = Long.MAX_VALUE;
        return c;
    }

    private RecoveryClock(double clock, double event, int strike, long until, long endedAt) {
        this.clock = clock;
        this.event = event;
        this.strike = strike;
        this.until = until;
        this.endedAt = endedAt;
    }

    public static final Codec<RecoveryClock> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("clock").forGetter(c -> c.clock),
            Codec.DOUBLE.optionalFieldOf("event", Double.NaN).forGetter(c -> c.event),
            Codec.INT.optionalFieldOf("strike", NO_STRIKE).forGetter(c -> c.strike),
            Codec.LONG.optionalFieldOf("until", NONE).forGetter(c -> c.until),
            Codec.LONG.optionalFieldOf("ended_at", NONE).forGetter(c -> c.endedAt)
    ).apply(i, (clock, event, strike, until, ended) -> new RecoveryClock(clock, Double.isNaN(event) ? clock : event, strike, until, ended)));

    /** Recovery time now, world-clock ticks. */
    public double clock() {
        return clock;
    }

    /** Event time now (real time plus counted jumps, M), world-clock ticks. */
    public double event() {
        return event;
    }

    /** Recovery over the last {@code ticks} game ticks at the last step's pace (0 right after an absence), world-clock ticks. */
    public double over(int ticks) {
        return perTick * ticks;
    }

    /**
     * World-clock ticks this clock has fallen behind the world clock (with every jump) since it was made: the world's rate
     * it did not take during shows and the blend.
     */
    public double behind() {
        return behind;
    }

    /** The last step ran wholly at the world's rate (no cause in its show, the blend over): it will not fall further behind. */
    public boolean settled() {
        return settled;
    }

    /** The last step counted nothing: the first, after a load, an absence or a dimension change. */
    public boolean fresh() {
        return fresh;
    }

    /** The youngest detonation cause; {@link #NO_STRIKE} — none. */
    public int strike() {
        return strike;
    }

    /** Latest end of a conventional cause's show, game time; {@link #NONE} — none. */
    public long until() {
        return until;
    }

    /** Detonation {@code id} acted on the state: the youngest detonation is its cause (ids grow with time). */
    public void strike(int id) {
        if (id >= 0 && id > strike) strike = id;
    }

    /** A conventional strike whose show ends at game time {@code end} acted on the state. */
    public void until(long end) {
        if (end > until) until = end;
    }

    /** The state's causes no longer hold it (an admin's restore): world rate from now, no blend. */
    public void release() {
        strike = NO_STRIKE;
        until = NONE;
        endedAt = NONE;
    }

    /**
     * Step to game time {@code game}, with the clock without jumps {@code rate} and the jumps of dimension {@code dim} so far
     * ({@code counted}, {@code unseen}; world-clock ticks), detonation shows read from {@code shows}.
     */
    public void step(long game, double rate, double counted, double unseen, Object dim, Shows shows) {
        boolean sameDim = Objects.equals(dim, lastDim);
        long dg = lastGame == NONE ? -1 : game - lastGame;
        if (dg == 0 && sameDim) return;
        // detonation ids are per dimension: a cause from the dimension left behind means nothing here (its show, if still
        // running, held the state until now; the blend from this moment)
        if (!sameDim && lastDim != null && strike != NO_STRIKE) {
            strike = NO_STRIKE;
            if (lastInShow) endedAt = Math.max(endedAt, game);
        }
        double dc = sameDim ? Math.max(0, counted - lastCounted) : 0, du = sameDim ? Math.max(0, unseen - lastUnseen) : 0;
        // a jump nobody in the dimension was awake for ends the shows there (rule 7); a detonation's own clock says so itself
        if (dc > 0 && until != NONE && until > game) until = game;
        boolean inShow = showing(game, shows);
        if (dg < 0 || dg > gap || !sameDim) {
            // first step, load, back after an absence, or another dimension's clocks (a portal; on a client, the first
            // clocks a server tells): nothing elapsed for the state, at most a second lost
            perTick = 0;
            settled = false;
            fresh = true;
        } else {
            fresh = false;
            double real = dg * WorldClock.REAL_RATE, world = Math.max(real, rate - lastRate);
            double share = inShow ? 0 : share(lastGame, game);
            double before = clock;
            clock += real + (world - real) * share + dc + du;
            event += real + dc;
            behind += (world - real) * (1 - share);
            perTick = (clock - before) / dg;
            settled = share >= 1 - 1e-9;
        }
        lastGame = game;
        lastRate = rate;
        lastCounted = counted;
        lastUnseen = unseen;
        lastDim = dim;
        lastInShow = inShow;
    }

    /** Whether a cause is in its show at {@code game}; records the latest show end seen ({@link #endedAt}). */
    private boolean showing(long game, Shows shows) {
        boolean running = false;
        if (strike != NO_STRIKE) {
            long e = shows.ended(strike);
            if (e == RUNNING) running = true;
            else if (e != NONE) endedAt = Math.max(endedAt, Math.min(e, game));
        }
        if (until != NONE) {
            if (until > game) running = true;
            else endedAt = Math.max(endedAt, until);
        }
        return running;
    }

    /** The world's share of the step from game time {@code g0} to {@code g1}: the blend's u averaged over the step. */
    private double share(long g0, long g1) {
        if (endedAt == NONE) return 1;
        double r0 = (g0 - endedAt) / 20.0, r1 = (g1 - endedAt) / 20.0;
        return r1 <= r0 ? 1 : (ramped(r1) - ramped(r0)) / (r1 - r0);
    }

    /** ∫₀ʳ u dr with u = smoothstep(r / {@link StrikeClock#RAMP_S}), seconds; 0 before the end. */
    static double ramped(double r) {
        if (r <= 0) return 0;
        double ramp = StrikeClock.RAMP_S;
        if (r >= ramp) return ramp / 2 + (r - ramp);
        double x = r / ramp;
        return r * (x * x - x * x * x / 2);
    }

    /** For tests: a copy as a save would read it back (stepping state dropped). */
    public RecoveryClock reloaded() {
        return new RecoveryClock(clock, event, strike, until, endedAt);
    }
}
