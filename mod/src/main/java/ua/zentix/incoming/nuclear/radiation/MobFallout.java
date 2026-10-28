package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.world.NukeSeen;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Terrain;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Mob fallout (DESIGN-nuke §4). Players receive dose once a second; the loaded world has thousands of mobs, so every
 * {@link #PERIOD} ticks snapshot the level's mobs, then give each the dose since its own last account ({@link NukeSeen#falloutAt},
 * saved with the mob): local fallout rate over that time with wall and roof shielding ({@link Shielding#fallout}). A mob that was
 * not in memory for hours (its chunk unloaded, or first loaded after the detonation) receives the fallout of those hours when it
 * loads, as the decaying rate's integral, not the current rate times the gap. Estimate: shielding is the one it has now. The
 * snapshot and each mob are work units under the shared nuclear work budget ({@link WorkClock}), as light and destruction are.
 * Clothing contamination and black rain are player-only.
 */
public final class MobFallout {
    /** Ticks between mob fallout doses (10 s — 0.2 game hour). */
    public static final int PERIOD = 200;
    /** Rate below this (R/h) is background: adds no dose, skip shielding (same for players). */
    static final double BACKGROUND = 0.01;
    /** Accounts up to this long, ticks, use the current rate (the pass period); longer ones integrate the decay. */
    private static final long SHORT = 2L * PERIOD;
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
        int lateFrom = NuclearEvents.get(level).lateFrom();
        double roentgens = 0;
        long span = 0;
        for (Detonation d : detonations) {
            // own account; none — since the detonation (it was there, unloaded or not), or this pass for an old world's mob
            long from = seen.falloutAt() >= 0 ? Math.max(seen.falloutAt(), d.gameTime()) : d.id() >= lateFrom ? d.gameTime() : passStart;
            if (from >= now) continue;
            roentgens += exposure(d, mob.getX(), mob.getZ(), from - d.gameTime(), now - d.gameTime());
            span = Math.max(span, now - from);
        }
        mob.setData(ModAttachments.NUKE_SEEN, seen.withFallout(now));
        // background rate over the span adds nothing (same as players): skip shielding rays
        if (roentgens < BACKGROUND * span / 1000.0) return;
        double gy = roentgens * Shielding.fallout(level, RadiationTicker.middle(mob)) * RadiationTicker.GY_PER_R;
        RadiationTicker.addDose(mob, (float) gy);
    }

    /** Unshielded fallout exposure at (x, z) from {@code t0} to {@code t1} ticks after detonation {@code d}, R. */
    static double exposure(Detonation d, double x, double z, long t0, long t1) {
        if (t1 - t0 <= SHORT) return d.falloutRate(x, z, t1) * (t1 - t0) / 1000.0;
        return integral(t -> d.falloutRate(x, z, (long) t), t0, t1) / 1000.0;
    }

    /**
     * ∫ rate dt over [{@code t0}, {@code t1}] ticks (rate × ticks): midpoint rule on a logarithmic grid of {@link #INTEGRAL_STEPS}
     * steps, which follows a power-law decay to a fraction of a percent; from tick 1 (no fallout in the first tick).
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
