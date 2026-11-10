package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.model.SicknessModel;
import ua.zentix.incoming.nuclear.world.NukeSeen;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.util.WorldClock;

import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.function.LongToDoubleFunction;

/**
 * Mob fallout (DESIGN-nuke §4). Players receive dose once a second; the loaded world has thousands of mobs, so every
 * {@link #PERIOD} ticks snapshot the level's mobs, then give each the dose since its own last account ({@link NukeSeen#falloutAt},
 * saved with the mob): local fallout rate over that time with wall and roof shielding ({@link Shielding#fallout}). A mob that was
 * not in memory for hours (its chunk unloaded, or first loaded after the detonation) receives the fallout of those hours when it
 * loads, as the decaying rate's integral, not the current rate times the gap, and its sickness runs from the hour that dose
 * passed 1 Gy ({@link #crossing}). Estimate: shielding is the one it has now. The
 * snapshot and each mob are work units under the shared nuclear work budget ({@link WorkClock}), as light and destruction are.
 * Clothing contamination and black rain are player-only.
 */
public final class MobFallout {
    /** Ticks between mob fallout doses (10 s — 0.2 hour under a vanilla day). */
    public static final int PERIOD = 200;
    /** Rate below this (R/h) is background: adds no dose, skip shielding (same for players). */
    static final double BACKGROUND = 0.01;
    /** Accounts up to this long, ticks, use the current rate (the pass period); longer ones integrate the decay. */
    private static final long SHORT = 2L * PERIOD;
    /** The same in detonation-clock seconds: two passes under a vanilla day (0.4 game hours). */
    private static final double SHORT_S = SHORT * 3.6;
    /** Logarithmic-grid steps of the long-account integral (decay t^-1.2, step at fallout arrival). */
    static final int INTEGRAL_STEPS = 64;

    @Nullable
    private List<? extends Mob> mobs;
    private int next;
    /** No previous snapshot: after world load or idle time without fallout. */
    private static final long NONE = Long.MIN_VALUE;
    /** Previous snapshot time: new pass dose spans the full time between snapshots. */
    private long lastSnapshot = NONE;
    /**
     * Start of the time this pass covers for a mob without its own account from a world saved before the accounts
     * ({@link NuclearEvents#lateFrom}): the previous snapshot, as before.
     */
    private long passStart;

    /** How much fits within the budget. */
    public void work(ServerLevel level, List<Detonation> detonations, WorkClock clock) {
        long now = level.getGameTime();
        if (mobs == null) {
            if (!enabled(detonations)) {
                lastSnapshot = NONE;
                return;
            }
            if ((lastSnapshot != NONE && now - lastSnapshot < PERIOD) || !clock.canStart()) return;
            long t0 = clock.begin();
            // snapshot, not the live map: a mob may die from dose and add loot to the entity map
            mobs = level.getEntities(EntityTypeTest.forClass(Mob.class), RadiationTicker::affectsMob);
            // a budget-stretched pass longer than the period (queue busy with destruction) loses no hours: dose covers all time
            // since the previous snapshot; first pass (after world load or idle without fallout) covers one period
            passStart = lastSnapshot == NONE ? now - PERIOD : lastSnapshot;
            lastSnapshot = now;
            next = 0;
            clock.end(t0);
        }
        while (next < mobs.size() && clock.canStart()) {
            long t0 = clock.begin();
            expose(level, detonations, mobs.get(next++), now);
            clock.end(t0);
        }
        if (next >= mobs.size()) mobs = null;
    }

    private void expose(ServerLevel level, List<Detonation> detonations, Mob mob, long now) {
        // snapshot older than a tick: the mob's chunk may have dropped below full loading, while shielding reads blocks
        if (!mob.isAlive() || !Terrain.ready(level, mob.blockPosition())) return;
        NukeSeen seen = mob.getExistingData(ModAttachments.NUKE_SEEN).orElse(NukeSeen.NONE);
        NuclearEvents events = NuclearEvents.get(level);
        int lateFrom = events.lateFrom();
        double clock = WorldClock.now(level);
        double roentgens = 0, spanHours = 0;
        long span = 0;
        long[] froms = new long[detonations.size()];
        double[] fromClocks = new double[froms.length];
        for (int i = 0; i < froms.length; i++) {
            Detonation d = detonations.get(i);
            // own account; none — since the detonation (it was there, unloaded or not), or this pass for an old world's mob
            long from;
            double fromClock;
            if (seen.falloutAt() >= 0 && seen.falloutAt() >= d.gameTime()) {
                from = seen.falloutAt();
                // an account saved before the world clock was kept: as if the day had run at vanilla speed since
                fromClock = Double.isNaN(seen.falloutClock()) ? clock - (now - from) : seen.falloutClock();
            } else if (seen.falloutAt() >= 0 || d.id() >= lateFrom) {
                from = d.gameTime();
                fromClock = events.burstClock(d, now, clock);
            } else {
                from = passStart;
                fromClock = clock - (now - from);
            }
            froms[i] = from;
            fromClocks[i] = Math.min(fromClock, clock);
            if (from >= now) continue;
            double s0 = events.seconds(d, from, fromClocks[i]), s1 = events.seconds(d, now, clock);
            roentgens += exposure(d, mob.getX(), mob.getZ(), s0, s1);
            span = Math.max(span, now - from);
            spanHours = Math.max(spanHours, (s1 - s0) / 3600);
        }
        mob.setData(ModAttachments.NUKE_SEEN, seen.withFallout(now, clock));
        // background rate over the span adds nothing (same as players): skip shielding rays
        if (roentgens < BACKGROUND * spanHours) return;
        double gyPerR = Shielding.fallout(level, RadiationTicker.middle(mob)) * RadiationTicker.GY_PER_R;
        double gy = roentgens * gyPerR, before = RadiationTicker.dose(mob).doseGy();
        long at = now;
        if (span > SHORT && before < SicknessModel.MIN_DOSE && before + gy >= SicknessModel.MIN_DOSE) {
            // a long account (the mob was unloaded for hours): the sickness clock starts when the dose really passed 1 Gy,
            // not on load, so a mob that would have died in those hours dies now
            // only detonations that gave this mob fallout in the account: each bisection step integrates them again.
            // The world clock between an account's start and now is taken as running evenly (estimate)
            int[] live = java.util.stream.IntStream.range(0, froms.length)
                    .filter(i -> froms[i] < now && detonations.get(i).hasFallout()).toArray();
            at = crossing(t -> {
                double r = 0;
                for (int i : live) {
                    Detonation d = detonations.get(i);
                    if (froms[i] < t) r += exposure(d, mob.getX(), mob.getZ(), events.seconds(d, froms[i], fromClocks[i]),
                            events.seconds(d, t, between(froms[i], fromClocks[i], now, clock, t)));
                }
                return before + r * gyPerR;
            }, now - span, now, SicknessModel.MIN_DOSE);
        }
        long first = now - span;
        double firstClock = clock;
        for (int i = 0; i < froms.length; i++) if (froms[i] == first) firstClock = Math.min(firstClock, fromClocks[i]);
        RadiationTicker.addDose(mob, (float) gy, (long) Math.floor(between(first, firstClock, now, clock, at)));
    }

    /** World clock at game time {@code t} between two readings, running evenly between them. */
    static double between(long t0, double clock0, long t1, double clock1, long t) {
        return t1 <= t0 ? clock1 : clock0 + (clock1 - clock0) * (double) (t - t0) / (t1 - t0);
    }

    /**
     * First tick in [{@code from}, {@code to}] at which the growing dose {@code doseAt} reaches {@code gy}: bisection, to a tick.
     * {@code to} if it never does.
     */
    static long crossing(LongToDoubleFunction doseAt, long from, long to, double gy) {
        if (doseAt.applyAsDouble(to) < gy) return to;
        long lo = from, hi = to;
        while (hi - lo > 1) {
            long mid = lo + (hi - lo) / 2;
            if (doseAt.applyAsDouble(mid) >= gy) hi = mid;
            else lo = mid;
        }
        return hi;
    }

    /**
     * Unshielded fallout exposure at (x, z) from {@code s0} to {@code s1} seconds after detonation {@code d} on its own clock
     * ({@code NukeClock}), R.
     */
    static double exposure(Detonation d, double x, double z, double s0, double s1) {
        if (s1 - s0 <= SHORT_S) return d.falloutRateAt(x, z, s1) * (s1 - s0) / 3600;
        return integral(s -> d.falloutRateAt(x, z, s), s0, s1) / 3600;
    }

    /**
     * ∫ rate dt over [{@code t0}, {@code t1}] seconds (rate × seconds): midpoint rule on a logarithmic grid of {@link #INTEGRAL_STEPS}
     * steps, which follows a power-law decay to a fraction of a percent; from the first second (no fallout before it).
     */
    static double integral(DoubleUnaryOperator rate, double t0, double t1) {
        double lo = Math.max(t0, 1);
        if (t1 <= lo) return 0;
        double q = Math.pow(t1 / lo, 1.0 / INTEGRAL_STEPS), sum = 0;
        for (int i = 0; i < INTEGRAL_STEPS; i++) {
            double hi = i == INTEGRAL_STEPS - 1 ? t1 : lo * q;
            sum += rate.applyAsDouble(Math.sqrt(lo * hi)) * (hi - lo);
            lo = hi;
        }
        return sum;
    }

    private static boolean enabled(List<Detonation> detonations) {
        return IncomingConfig.SERVER.nukeMobRadiation.get() && detonations.stream().anyMatch(Detonation::hasFallout);
    }

    /** Cancel: current pass interrupted. */
    public void clear() {
        mobs = null;
    }
}
