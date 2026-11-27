package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.model.FalloutModel;
import ua.zentix.incoming.nuclear.model.SicknessModel;
import ua.zentix.incoming.nuclear.world.NukeSeen;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.util.WorldClock;

import java.util.List;
import java.util.function.LongToDoubleFunction;

/**
 * Mob fallout (DESIGN-nuke §4). Players receive dose once a second; the loaded world has thousands of mobs, so every
 * {@link #PERIOD} ticks snapshot the level's mobs, then give each the dose since its own last account ({@link NukeSeen#falloutAt},
 * saved with the mob): local fallout rate over that time with wall and roof shielding ({@link Shielding#fallout}). A mob that was
 * not in memory for hours (its chunk unloaded, or first loaded after the detonation) receives the fallout of those hours when it
 * loads, as the decaying rate's integral over its ground clock from when it landed ({@link GroundFallout}), not the current rate
 * times the gap, and its sickness runs from the hour that dose passed 1 Gy ({@link #crossing}). Live detonations and ground
 * fields alike ({@link NuclearEvents#falloutSources}). Estimate: shielding, of walls and of what it wears ({@link WornShielding}), is the one
 * it has now. The
 * snapshot and each mob are work units under the shared nuclear work budget ({@link WorkClock}), as light and destruction are.
 * Clothing contamination and black rain are player-only.
 */
public final class MobFallout {
    /** Ticks between mob fallout doses (10 s — 0.2 hour under a vanilla day). */
    public static final int PERIOD = 200;
    /** Rate below this (R/h) is background: adds no dose, skip shielding (same for players). */
    static final double BACKGROUND = FalloutModel.FLOOR;
    /** Accounts longer than this, ticks, look for the tick the dose passed 1 Gy. */
    private static final long SHORT = 2L * PERIOD;

    @Nullable
    private List<? extends Mob> mobs;
    /** Each source's youngest ground age, ground clock at the pass start and reach for this pass, by detonation number. */
    private final java.util.Map<Integer, double[]> reaches = new java.util.HashMap<>();
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
            reaches.clear();
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
        double[] fromGrounds = new double[froms.length];
        GroundFallout.Point[] points = new GroundFallout.Point[froms.length];
        double[] doses = new double[froms.length];
        for (int i = 0; i < froms.length; i++) {
            Detonation d = detonations.get(i);
            // own account; none — since the detonation (it was there, unloaded or not), or this pass for an old world's mob
            long from;
            if (seen.falloutAt() >= 0 && seen.falloutAt() >= d.gameTime()) from = seen.falloutAt();
            else if (seen.falloutAt() >= 0 || d.id() >= lateFrom) from = d.gameTime();
            else from = passStart;
            froms[i] = from;
            if (from >= now) continue;
            double g0 = events.groundAt(d, level, from), g1 = events.ground(d, level);
            GroundFallout.Source s = new GroundFallout.Source(d, g1, g0, reach(events, level, d, g0, g1));
            points[i] = GroundFallout.at(events, level, s, mob.getX(), mob.getZ());
            fromGrounds[i] = g0;
            doses[i] = points[i].dose(g0, g1);
            roentgens += doses[i];
            span = Math.max(span, now - from);
            spanHours = Math.max(spanHours, (g1 - g0) / 3600);
        }
        mob.setData(ModAttachments.NUKE_SEEN, seen.withFallout(now, clock));
        // background rate over the span adds nothing (same as players): skip shielding rays
        if (roentgens < BACKGROUND * spanHours) return;
        // walls and roof, then what the mob wears; both as they are now (estimate for a long account)
        double gyPerR = Shielding.fallout(level, RadiationTicker.middle(mob)) * WornShielding.transmitted(mob) * RadiationTicker.GY_PER_R;
        double gy = roentgens * gyPerR, before = RadiationTicker.dose(mob).doseGy();
        long at = now;
        if (span > SHORT && before < SicknessModel.MIN_DOSE && before + gy >= SicknessModel.MIN_DOSE) {
            // a long account (the mob was unloaded for hours): the sickness clock starts when the dose really passed 1 Gy,
            // not on load, so a mob that would have died in those hours dies now
            // only detonations that gave this mob fallout in the account: each bisection step integrates them again,
            // each to its ground clock at that tick from its world-clock history
            int[] live = java.util.stream.IntStream.range(0, froms.length)
                    .filter(i -> froms[i] < now && points[i] != null && points[i] != GroundFallout.Point.NONE).toArray();
            at = crossing(t -> {
                double r = 0;
                for (int i : live) if (froms[i] < t) r += points[i].dose(fromGrounds[i], events.groundAt(detonations.get(i), level, t));
                return before + r * gyPerR;
            }, now - span, now, SicknessModel.MIN_DOSE);
        }
        // the dose's cause is the youngest detonation whose fallout reached the mob in this account; the sickness started as long
        // ago as that detonation's own strike clock has run since the crossing (the mob was out of the world meanwhile, its
        // body clock did not move)
        Detonation cause = null;
        for (int i = 0; i < froms.length; i++) {
            Detonation d = detonations.get(i);
            if (froms[i] < now && d.hasFallout() && doses[i] > 0 && (cause == null || d.id() > cause.id())) cause = d;
        }
        if (cause == null) return;
        double ago = at >= now ? 0 : (events.seconds(cause, level) - events.secondsAt(cause, level, at)) / WorldClock.SECONDS_PER_TICK;
        RadiationTicker.addDose(mob, (float) gy, cause, ago);
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
     * Reach of source {@code d} for an account from ground clock {@code g0} to {@code g1}, blocks ({@link GroundFallout#reach}):
     * one bound per pass for accounts since the previous snapshot; a longer account (a mob back after hours or months) its
     * own, wider one.
     */
    private double reach(NuclearEvents events, ServerLevel level, Detonation d, double g0, double g1) {
        double[] pass = reaches.computeIfAbsent(d.id(), k -> {
            double youngest = events.youngestGround(d, level), start = events.groundAt(d, level, passStart);
            return new double[]{youngest, start, GroundFallout.reach(d, youngest, g1 - start)};
        });
        return g0 >= pass[1] ? pass[2] : GroundFallout.reach(d, pass[0], g1 - g0);
    }

    private static boolean enabled(List<Detonation> detonations) {
        return IncomingConfig.SERVER.nukeMobRadiation.get() && detonations.stream().anyMatch(Detonation::hasFallout);
    }

    /** Cancel: current pass interrupted. */
    public void clear() {
        mobs = null;
        reaches.clear();
    }
}
