package ua.zentix.incoming.work;

import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.StrikeWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Shared heavy-work budget per server tick ({@code performance.work_ms_per_tick}). Each lane has its own
 * {@link WorkClock} and unit estimates; its allowance is bounded by its cap and what higher lanes left.
 * Ready impacts run before world ticks, reserving measured mandatory simulation ({@link TickReserve}).
 * Post uses the remaining budget and can start a newly queued impact. Both windows and all worlds share expenditure.
 * <p>
 * The budget is a ceiling, available only while vanilla's {@code hasTime} permits work. A server already behind
 * schedule keeps the {@link #BUSY_MS} fallback instead of adding the full budget to an overdue tick. Even without
 * an early window, a Post window with spare time ends at the absolute next-tick deadline. Sprint uses the full cap.
 * <p>
 * Each lane can start one unit to guarantee progress after a slow unit; the bound is its deadline plus that unit.
 * Worlds rotate each tick. Nuclear background workers ({@code RuinWorkers}) are outside the server-thread clock.
 */
public final class WorkScheduler {
    /** Work lanes in service order. */
    public enum Lane {
        /** Immediately visible impacts: explosions, glass, debris and bomb rubble ({@link StrikeWorld#impacts}). */
        IMPACT,
        /** Nuclear fronts, light, ruins and craters ({@link NuclearStrikes#work}) follow the wave. */
        NUCLEAR,
        /** Background blackout work ({@link Blackouts#work}). */
        GRID
    }

    /**
     * Busy-server allowance in milliseconds. Queues still progress (one mob per nuclear unit), but the three
     * lanes add at most 6 ms before their final units, plus up to 6 ms for visible ruins (ScarQueue.SEEN_BUSY_NANOS).
     */
    static final long BUSY_MS = 2;

    /** Impact share while lower lanes wait, so a blackout does not wait for the whole salvo. */
    static final double IMPACT_SHARE_WHEN_SHARED = 2.0 / 3.0;

    /**
     * Blackout cap, also reserved by the nuclear lane while grid work waits. Lamps incur additional lighting and
     * client section rebuild costs outside this clock. Their pace comes from grid.cascade_speed, not this budget.
     * At the default 30-ms budget this preserves the former 4-ms limit.
     */
    static final double GRID_SHARE = 2.0 / 15.0;

    /**
     * Nuclear preparation cap within its lane. An ICBM flight gives it at least 90 seconds; server-thread snapshots
     * and disk reads should not displace ongoing destruction. At 30 ms this preserves the former 8-ms limit.
     */
    public static final double PREP_SHARE = 4.0 / 15.0;

    /**
     * Measured lane time by server tick, indexed like vanilla tickTimesNanos. Nuclear worker tuning subtracts
     * this via {@link #ownNanos}. Every tick overwrites its slot; these measurements are not world state.
     */
    private static final long[] OWN = new long[100];
    /** Every 600 ticks: server time/max, lane time, early-window count, early/late impact time and unload-debt count. */
    private static final int REPORT_TICKS = 600;
    private static final long[] WINDOW = new long[10];

    private WorkScheduler() {}

    /** Lane time in nanoseconds for this MinecraftServer tick number. */
    public static long ownNanos(int tick) {
        return OWN[Math.floorMod(tick, OWN.length)];
    }

    /** One impact clock per server, attached to the overworld. */
    public static WorkClock impactClock(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.IMPACT_CLOCK);
    }

    /** Decay the estimate each tick so one slow explosion does not limit the queue to one unit indefinitely. */
    public static WorkClock newImpactClock() {
        return WorkClock.decaying(0.5);
    }

    /** Replace the impact clock for tests; restore {@link #newImpactClock} afterwards. */
    public static void useImpactClock(MinecraftServer server, WorkClock clock) {
        server.overworld().setData(ModAttachments.IMPACT_CLOCK, clock);
    }

    /** Common tick budget in nanoseconds. */
    public static long totalNanos() {
        return IncomingConfig.SERVER.workBudgetMs.get() * 1_000_000L;
    }

    /** Blackout cap within the common budget, in nanoseconds ({@link #GRID_SHARE}). */
    public static long gridCapNanos() {
        return (long) (totalNanos() * GRID_SHARE);
    }

    /** Nuclear preparation cap, in nanoseconds ({@link #PREP_SHARE}). */
    public static long prepCapNanos() {
        return (long) (totalNanos() * PREP_SHARE);
    }

    /** Impacts take the whole cap unless lower lanes wait; then use {@link #IMPACT_SHARE_WHEN_SHARED}. */
    static long impactBudget(long total, boolean lowerLanesPending) {
        return lowerLanesPending ? (long) (total * IMPACT_SHARE_WHEN_SHARED) : total;
    }

    /**
     * Nuclear work gets the remainder after impacts and the pending grid reserve. Without that reserve, its
     * blackout cascade would get only one unit per tick instead of its share during the wave.
     */
    static long nuclearBudget(long total, long usedAbove, long gridReserve) {
        return remaining(total, usedAbove + gridReserve, total);
    }

    /** Remaining common budget, bounded by this lane cap and zero. */
    static long remaining(long total, long usedAbove, long cap) {
        return Math.min(cap, Math.max(0, total - usedAbove));
    }

    /** The window must end at the absolute server deadline, even when no early window was granted. */
    static long untilTick(long cap, long now, long tickDeadline) {
        return Math.min(cap, Math.max(0, tickDeadline - now));
    }

    public static void onServerTickPre(ServerTickEvent.Pre e) {
        if (!impactClock(e.getServer()).isCounting()) timing(e.getServer()).begin(e.getServer());
    }

    /** Before the first world, after tickRateManager.tick has applied freeze/step for this tick. */
    public static void onLevelTickPre(LevelTickEvent.Pre e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        ImpactTiming timing = timing(server);
        if (!timing.active) return;
        timing.level(level);
        if (timing.earlyVisited) return;
        timing.earlyVisited = true;
        WorkClock impact = impactClock(server);
        if (!level.tickRateManager().runsNormally() || impact.isCounting()) return;
        // Lower lanes may become pending during world simulation, so leave their share even before they exist.
        long cap = impactBudget(totalNanos(), true);
        long budget = server.tickRateManager().isSprinting() ? cap
                : e.hasTime() ? timing.reserve.earlyNanos(cap, Util.getNanos(), server.getNextTickTime()) : 0;
        if (budget <= 0) return; // Keep the one-unit progress guarantee for Post on a genuinely busy tick.
        impact.start(budget, () -> server.tickRateManager().isSprinting() || e.hasTime(), 0);
        timing.earlyClock = impact;
        timing.earlyBudget = budget;
        workImpacts(levels(server), impact);
        timing.earlyUsed = impact.usedThisTickNanos();
    }

    private static ImpactTiming timing(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.IMPACT_TIMING);
    }

    private static List<ServerLevel> levels(MinecraftServer server) {
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);
        Collections.rotate(levels, -(server.getTickCount() % levels.size()));
        return levels;
    }

    private static void workImpacts(List<ServerLevel> levels, WorkClock impact) {
        for (ServerLevel level : levels) {
            if (level.tickRateManager().runsNormally() && level.hasData(ModAttachments.STRIKE_WORLD)) {
                StrikeWorld.get(level).workImpacts(level, impact);
            }
        }
    }

    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        List<ServerLevel> levels = levels(server);
        long total = totalNanos();
        BooleanSupplier spare = server.tickRateManager().isSprinting() ? () -> true : e::hasTime;

        WorkClock impact = impactClock(server);
        long busy = BUSY_MS * 1_000_000L;
        ImpactTiming timing = timing(server);
        long cap = impactBudget(total, Blackouts.pending(server) || NuclearStrikes.pending(server));
        if (timing.active && timing.earlyClock == impact) {
            // This is still the same tick: no second decay, budget or counters.
            long left = remaining(cap, impact.usedThisTickNanos(), cap);
            if (!server.tickRateManager().isSprinting()) {
                left = untilTick(left, Util.getNanos(), server.getNextTickTime());
            }
            // A short early window is not a debt tick: topping it up to BUSY_MS after autosave would overrun
            // the schedule by several units (e.g. 49 ms mandatory simulation). Only the final new unit may start.
            impact.resume(left, spare, 0);
            // If early work stayed inside its allowance, the permitted final unit can start a new hit in Post.
            if (timing.earlyUsed <= timing.earlyBudget && impact.usedThisTickNanos() < cap) impact.allowLateStart();
        } else {
            if (!impact.isCounting() && !server.tickRateManager().isSprinting() && spare.getAsBoolean()) {
                cap = untilTick(cap, Util.getNanos(), server.getNextTickTime());
            }
            impact.start(cap, spare, busy);
        }
        workImpacts(levels, impact);
        for (ServerLevel level : levels) {
            if (level.hasData(ModAttachments.STRIKE_WORLD)) StrikeWorld.get(level).endImpactTick(level);
        }

        WorkClock nuclear = NuclearWorld.clock(server);
        long gridCap = (long) (total * GRID_SHARE);
        nuclear.start(nuclearBudget(total, impact.usedThisTickNanos(), Blackouts.pending(server) ? gridCap : 0), spare, busy);
        NuclearStrikes.work(levels, nuclear);

        WorkClock grid = Blackouts.clock(server);
        grid.start(remaining(total, impact.usedThisTickNanos() + nuclear.usedThisTickNanos(), gridCap), spare, busy);
        Blackouts.work(server, levels, grid);

        OWN[Math.floorMod(server.getTickCount(), OWN.length)] = impact.usedThisTickNanos() + nuclear.usedThisTickNanos() + grid.usedThisTickNanos();
        report(server, impact.usedThisTickNanos(), nuclear.usedThisTickNanos(), grid.usedThisTickNanos(), timing);
    }

    /** Include other mods' normal/low-priority Post work in the next tick's mandatory reserve. */
    public static void onServerTickEnd(ServerTickEvent.Post e) {
        ImpactTiming timing = timing(e.getServer());
        if (timing.active) timing.finish(e.getServer(), ownNanos(e.getServer().getTickCount()));
    }

    /**
     * Every 600 ticks with work: previous server tick time, current lane time and the remainder. Early-window and
     * mandatory-unload percentages use all ticks in this report; early/late impact averages use the same denominator.
     */
    private static void report(MinecraftServer server, long impact, long nuclear, long grid, ImpactTiming timing) {
        long[] times = server.getTickTimesNanos();
        long last = times[Math.floorMod(server.getTickCount() - 1, times.length)];
        WINDOW[0]++;
        WINDOW[1] += last;
        WINDOW[2] = Math.max(WINDOW[2], last);
        WINDOW[3] += impact;
        WINDOW[4] += nuclear;
        WINDOW[5] += grid;
        long early = timing.active ? timing.earlyUsed : 0;
        if (timing.active && timing.earlyClock != null) WINDOW[6]++;
        WINDOW[7] += early;
        WINDOW[8] += Math.max(0, impact - early);
        if (timing.active && timing.hasUnloadDebt()) WINDOW[9]++;
        if (WINDOW[0] < REPORT_TICKS) return;
        long n = WINDOW[0];
        if (WINDOW[3] + WINDOW[4] + WINDOW[5] > 0) {
            ua.zentix.incoming.Incoming.LOG.info("Mod work over 30 s: server tick avg {} ms (max {}), of which mod lanes avg — "
                            + "impacts {}, nuke {}, blackout {} ms; the rest (vanilla, other mods) {} ms; "
                            + "impact early window {}% of ticks, early avg {} ms, late avg {} ms; mandatory unload debt {}% of ticks",
                    ms(WINDOW[1] / n), ms(WINDOW[2]), ms(WINDOW[3] / n), ms(WINDOW[4] / n), ms(WINDOW[5] / n),
                    ms(Math.max(0, WINDOW[1] - WINDOW[3] - WINDOW[4] - WINDOW[5]) / n),
                    percent(WINDOW[6], n), ms(WINDOW[7] / n), ms(WINDOW[8] / n), percent(WINDOW[9], n));
        }
        java.util.Arrays.fill(WINDOW, 0);
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1e6);
    }

    private static String percent(long ticks, long total) {
        return String.format(java.util.Locale.ROOT, "%.1f", ticks * 100.0 / total);
    }
}
