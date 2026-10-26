package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.util.Terrain;

import java.util.List;

/**
 * Mob fallout (DESIGN-nuke §4). Players receive dose once a second; the loaded world has thousands of mobs, so every
 * {@link #PERIOD} ticks snapshot the level's mobs, then give each elapsed-time dose from local fallout rate with wall
 * and roof shielding ({@link Shielding#fallout}). The snapshot and each mob are work units under the shared nuclear
 * work budget ({@link WorkClock}), as light and destruction are. Clothing contamination and black rain are player-only.
 */
public final class MobFallout {
    /** Ticks between mob fallout doses (10 s — 0.2 game hour). */
    public static final int PERIOD = 200;
    /** Rate below this (R/h) is background: adds no dose, skip shielding (same for players). */
    static final double BACKGROUND = 0.01;

    @Nullable
    private List<? extends Mob> mobs;
    private int next;
    /** No previous snapshot: after world load or idle time without fallout. */
    private static final long NONE = Long.MIN_VALUE;
    /** Previous snapshot time: new pass dose spans the full time between snapshots. */
    private long lastSnapshot = NONE;
    private double hours;

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
            hours = (lastSnapshot == NONE ? PERIOD : now - lastSnapshot) / 1000.0;
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
        double field = 0;
        for (Detonation d : detonations) field += d.falloutRate(mob.getX(), mob.getZ(), now - d.gameTime());
        if (field < BACKGROUND) return;
        double rate = field * Shielding.fallout(level, RadiationTicker.middle(mob));
        RadiationTicker.addDose(mob, (float) (rate * RadiationTicker.GY_PER_R * hours));
    }

    private static boolean enabled(List<Detonation> detonations) {
        return IncomingConfig.SERVER.nukeMobRadiation.get() && detonations.stream().anyMatch(Detonation::hasFallout);
    }

    /** Cancel: current pass interrupted. */
    public void clear() {
        mobs = null;
    }
}
