package ua.zentix.incoming.nuclear.world;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Per-tick time budget for destruction queues. Checking only “time has not expired” is insufficient: an epicenter column
 * (dozens of blocks, neighbors, light) takes 3–5 ms, so starting just before deadline overruns budget by its full duration.
 * Start work only if recent longest-unit estimate says it will finish before deadline. Estimate is a decaying
 * maximum: cautious after a heavy column, more permissive again after roughly fifty units.
 * <p>
 * Time comes from clock source ({@link System#nanoTime} in game). {@link #counting} is a test clock: time
 * advances only through work, each unit costs the same, so queue decisions do not depend on machine speed.
 * <p>
 * Estimate decays per unit, so at one unit per tick (heavy unit, GC pause, external stall within
 * unit) it decays slowly: a 20 ms spike keeps blackout queue at one unit per tick for dozens of ticks.
 * Cheap, steady-unit queues use {@link #decaying}: estimate also fades each tick.
 */
public final class WorkClock {
    /** Estimate decay per work unit. */
    private static final double DECAY = 0.95;
    /** Tick time always available: clocks outside server tick (nuke preparation with own deadline) and tests. */
    private static final BooleanSupplier ALWAYS = () -> true;

    private final LongSupplier time;
    /** Counting-clock cost per work unit, ns; 0 for real clock. */
    private final long unitCost;
    /** Estimate decay factor per new tick (1 means no decay). */
    private final double tickDecay;
    /** Status: units last tick and longest unit in last 5–10 s (two 100-tick windows), ns. */
    private int unitsLastTick;
    private long largest, largestBefore;
    private int ticks;
    /** For tests: work time this tick and maximum per tick, ns. */
    private long usedThisTick, maxTickNanos;
    private long fakeNow;
    /** Counting-clock {@link #sampler} counter. */
    private long sampled;

    private long deadline;
    /** Deadline without spare tick time ({@link #start(long, BooleanSupplier, long)}): short but work progresses. */
    private long busyDeadline;
    /** Whether server has time left this tick ({@link #start(long, BooleanSupplier, long)}). */
    private BooleanSupplier spare = ALWAYS;
    /** Estimated next-unit duration, ns. */
    private double estimate;
    /** Estimates by unit kind ({@link #canStart(int)}), ns: cheap batch does not permit a risky expensive unit. */
    private double[] kindEstimate = new double[0];
    /** Some work already done this tick (always run one unit, otherwise an extremely long one would halt queue). */
    private boolean worked;
    private boolean lateStart;
    private int unitsThisTick, maxUnitsPerTick, ticksWorked, units;

    public WorkClock() {
        this(System::nanoTime, 0, 1);
    }

    private WorkClock(LongSupplier time, long unitCost, double tickDecay) {
        this.time = time == null ? () -> fakeNow : time;
        this.unitCost = unitCost;
        this.tickDecay = tickDecay;
    }

    /** Test clock: stationary without work; each unit advances it by {@code unitNanos}. */
    public static WorkClock counting(long unitNanos) {
        return new WorkClock(null, unitNanos, 1);
    }

    /** Real clock whose estimate decays by {@code tickDecay} each tick (0.5 halves it). */
    public static WorkClock decaying(double tickDecay) {
        return decaying(System::nanoTime, tickDecay);
    }

    /** Same with custom time source (tests). */
    public static WorkClock decaying(LongSupplier time, double tickDecay) {
        if (!(tickDecay > 0 && tickDecay <= 1)) throw new IllegalArgumentException("tickDecay " + tickDecay + " outside (0, 1]");
        return new WorkClock(time, 0, tickDecay);
    }

    /** New tick: deadline {@code budgetNanos} from now. Estimate carries across ticks. */
    public void start(long budgetNanos) {
        start(budgetNanos, ALWAYS, budgetNanos);
    }

    /**
     * New tick: deadline {@code budgetNanos} from now while server has tick time ({@code spare}: vanilla
     * {@code ServerTickEvent.hasTime}, used for vanilla chunk unloading); without it, only {@code busyNanos}.
     * No time when server is behind schedule: world tick over 50 ms, catch-up after stall, chunk
     * tasks between ticks consuming time before next tick. A host's salvo on such a server would add
     * the full 30 ms budget every tick (TPS 7–10); here a short deadline still lets queues progress: nuke
     * wave hits one mob per unit, so one unit per tick would stretch it over minutes.
     */
    public void start(long budgetNanos, BooleanSupplier spare, long busyNanos) {
        // counting clocks advance only through work: wall-clock spare tick time is irrelevant, otherwise flight scenarios
        // would differ between slow and fast CI machines
        this.spare = unitCost > 0 ? ALWAYS : spare;
        long now = time.getAsLong();
        deadline = now + budgetNanos;
        busyDeadline = now + Math.min(budgetNanos, busyNanos);
        worked = false;
        lateStart = false;
        unitsLastTick = unitsThisTick;
        unitsThisTick = 0;
        usedThisTick = 0;
        estimate *= tickDecay;
        for (int i = 0; i < kindEstimate.length; i++) kindEstimate[i] *= tickDecay;
        if (++ticks % 100 == 0) {
            largestBefore = largest;
            largest = 0;
        }
    }

    /** Another window in the same tick: keep expenditure, estimates and the single progress guarantee. */
    public void resume(long budgetNanos, BooleanSupplier spare, long busyNanos) {
        this.spare = unitCost > 0 ? ALWAYS : spare;
        long now = time.getAsLong();
        deadline = now + Math.max(0, budgetNanos);
        busyDeadline = now + Math.max(0, Math.min(budgetNanos, busyNanos));
    }

    /**
     * Start one new job in the late window. The scheduler grants this only if the early window did not overrun
     * its allowance and the common budget still has room: this, instead of an early overrun, is the last unit.
     */
    public void allowLateStart() {
        lateStart = true;
    }

    /** Deterministic scenarios use only the late window, independent of the host's wall clock. */
    public boolean isCounting() {
        return unitCost > 0;
    }

    /**
     * Whether another work unit fits before deadline (short deadline without spare tick time). First unit each tick
     * always runs even past deadline ({@link ua.zentix.incoming.work.WorkScheduler}: a lower
     * lane may receive no remainder of common budget).
     */
    public boolean canStart() {
        return fits((long) estimate);
    }

    /**
     * Same for unit of {@code kind} (0, 1, …), using that kind's estimate: in a mixed-cost lane (explosion
     * rays and block batches), shared estimate after cheap units would admit an expensive one just before deadline.
     */
    public boolean canStart(int kind) {
        return fits((long) kindEstimate(kind));
    }

    /**
     * Whether to start another unit with {@code estimate}. Without spare tick time, until short deadline
     * {@link #busyDeadline}, ignoring estimate: after one heavy unit (crater column 3–5 ms), estimate exceeds short deadline
     * and decays slowly, leaving lane at one unit per tick for dozens of ticks.
     */
    private boolean fits(long estimate) {
        if (!worked || lateStart) return true;
        long now = time.getAsLong();
        return spare.getAsBoolean() ? now + estimate < deadline : now < busyDeadline;
    }

    /**
     * Like {@link #canStart()}, but without spare tick time extend short deadline by {@code extraBusyNanos} (no later than
     * full deadline): work visible to player this tick (chunk ruins on screen) progresses even on a server
     * behind schedule. With spare time, same as {@link #canStart()}.
     */
    public boolean canStartVisible(long extraBusyNanos) {
        if (!worked) return true;
        long now = time.getAsLong();
        return spare.getAsBoolean() ? now + (long) estimate < deadline : now < visibleBusyDeadline(extraBusyNanos);
    }

    /** Like {@link #overdue()}, with {@link #canStartVisible} deadline. */
    public boolean overdueVisible(long extraBusyNanos) {
        return time.getAsLong() >= (spare.getAsBoolean() ? deadline : visibleBusyDeadline(extraBusyNanos));
    }

    private long visibleBusyDeadline(long extraBusyNanos) {
        return Math.min(deadline, busyDeadline + extraBusyNanos);
    }

    private double kindEstimate(int kind) {
        return kind < kindEstimate.length ? kindEstimate[kind] : 0;
    }

    /**
     * Clock inside unit (explosion block batch runs until deadline): same real clock; counting clock has its own
     * counter, each read advancing by unit cost (batch of “deadline / cost” steps), leaving queue clock unchanged.
     */
    public LongSupplier sampler() {
        return unitCost > 0 ? () -> sampled += unitCost : time;
    }

    /** Work-unit start: mark for {@link #end}. */
    public long begin() {
        return time.getAsLong();
    }

    /** Work unit started at {@code began} finished; returns duration, ns. */
    public long end(long began) {
        if (unitCost > 0) fakeNow += unitCost;
        long took = time.getAsLong() - began;
        record(took);
        return took;
    }

    /** Unit of {@code kind} started at {@code began} finished ({@link #canStart(int)}). */
    public long end(long began, int kind) {
        long took = end(began);
        if (kind >= kindEstimate.length) kindEstimate = java.util.Arrays.copyOf(kindEstimate, kind + 1);
        kindEstimate[kind] = Math.max(took, kindEstimate[kind] * DECAY);
        return took;
    }

    /**
     * Tick deadline passed. For non-unit work ({@link #spent}): {@link #canStart} always admits first unit,
     * so an all-waiting queue would otherwise be entirely scanned in one tick.
     */
    public boolean overdue() {
        return time.getAsLong() >= (spare.getAsBoolean() ? deadline : busyDeadline);
    }

    /**
     * Work started at {@code began} is not a unit (inspecting waiting work): time counts toward tick
     * ({@link #usedThisTickNanos}), not estimate or unit count. Counting clock does not advance: time there
     * advances only by units. Returns duration, ns.
     */
    public long spent(long began) {
        long took = time.getAsLong() - began;
        usedThisTick += took;
        maxTickNanos = Math.max(maxTickNanos, usedThisTick);
        return took;
    }

    /** Work unit took {@code nanos}. */
    public void record(long nanos) {
        lateStart = false;
        estimate = Math.max(nanos, estimate * DECAY);
        largest = Math.max(largest, nanos);
        usedThisTick += nanos;
        maxTickNanos = Math.max(maxTickNanos, usedThisTick);
        if (!worked) ticksWorked++;
        worked = true;
        maxUnitsPerTick = Math.max(maxUnitsPerTick, ++unitsThisTick);
        units++;
    }

    /** Status: units last tick. */
    public int unitsLastTick() {
        return unitsLastTick;
    }

    /** Status: next-unit estimate, ns. */
    public long estimateNanos() {
        return (long) estimate;
    }

    /** Status: longest unit in last 5–10 s (previous 100-tick window and current), ns. */
    public long largestRecentNanos() {
        return Math.max(largest, largestBefore);
    }

    /** Work time this tick, ns (on this clock: counting units × cost). */
    public long usedThisTickNanos() {
        return usedThisTick;
    }

    /** Whether work occurred this tick. */
    public boolean workedThisTick() {
        return worked;
    }

    /** Maximum work time in one tick, ns (for tests). */
    public long maxTickNanos() {
        return maxTickNanos;
    }

    /** Maximum units in one tick (for tests). */
    public int maxUnitsPerTick() {
        return maxUnitsPerTick;
    }

    /** Total work units (for tests). */
    public int units() {
        return units;
    }

    /** Number of ticks with work (for tests). */
    public int ticksWorked() {
        return ticksWorked;
    }
}
