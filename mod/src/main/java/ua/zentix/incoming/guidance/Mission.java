package ua.zentix.incoming.guidance;

import net.minecraft.nbt.CompoundTag;

/**
 * Projectile range in blocks rather than lifetime in ticks: flight ends when range is spent, not when time
 * expires. Consumption is actual distance traveled ({@link #spend}), so waiting on the launcher, waiting for the
 * target area, and stretched virtual MLRS flight inherently spend almost none; leg speed (slow climb, fast dive)
 * does not affect it: blocks are blocks. Area waiting has its own limit ({@code
 * StrikeProjectile.AREA_WAIT_LIMIT}). Pure code without the Minecraft world.
 * <p>
 * Range = planned path × {@link #PATH_MARGIN} + reserve. Reserve is in ticks at cruise speed ({@link
 * #RESERVE_TICKS}, {@link #LOST_RESERVE_TICKS}). A leg planned in ticks that exceeds cruise speed (Lancet dive)
 * converts to blocks at its own speed.
 */
public final class Mission {
    /** Planned path margin: route bends, repeated approach, popup. */
    public static final double PATH_MARGIN = 1.5;
    /** Reserve beyond flight plan, ticks at cruise speed. */
    public static final int RESERVE_TICKS = 600;
    /** Reserve after target loss beyond path to its last point, ticks at cruise speed ({@link #lose}). */
    public static final int LOST_RESERVE_TICKS = 200;

    /** Blocks still available to fly. */
    private double range;
    /** Range already reduced to flight toward lost target's last point ({@link #lose}). */
    private boolean lostCapped;

    private Mission(double range, boolean lostCapped) {
        this.range = range;
        this.lostCapped = lostCapped;
    }

    /** Range of {@code range} blocks without a plan (projectile with no assigned path: spec range). */
    public static Mission of(double range) {
        return new Mission(range, false);
    }

    /**
     * Flight plan: {@code path} blocks with {@link #PATH_MARGIN} plus {@link #RESERVE_TICKS} reserve at {@code
     * cruise} speed.
     */
    public static Mission plan(double path, double cruise) {
        return new Mission(path * PATH_MARGIN + RESERVE_TICKS * cruise, false);
    }

    /** Add a leg absent from the planned path (Lancet orbit and dive), blocks. */
    public Mission extend(double blocks) {
        range += blocks;
        return this;
    }

    /** Blocks still available to fly (≤ 0 means exhausted). */
    public double range() {
        return range;
    }

    /** Range exhausted: projectile never reached target. */
    public boolean exhausted() {
        return range <= 0;
    }

    /** Projectile traveled {@code moved} blocks. */
    public void spend(double moved) {
        range -= moved;
    }

    /**
     * Target moved {@code moved} blocks (walked, teleported): add range to cover this displacement with the same
     * margin as the plan. Displacement is already limited by the chase budget ({@code
     * TargetTracker.CHASE_BUDGET}): a continuously fleeing target cannot keep a projectile alive forever.
     */
    public void chase(double moved) {
        if (moved > 0) range += moved * PATH_MARGIN;
    }

    /** Retargeted: point displacement handled as pursuit ({@link #chase}), and the new target is not lost yet. */
    public void retarget(double moved) {
        chase(moved);
        lostCapped = false;
    }

    /**
     * Target lost: range covers only {@code path} to its last point (same margin as plan) plus {@link
     * #LOST_RESERVE_TICKS} at {@code cruise}, once. Range accumulated while chasing a fleeing target is no longer
     * needed: with it, Shaheds whose target died would circle the death point for minutes.
     *
     * @return range reduced now (false if already reduced earlier)
     */
    public boolean lose(double path, double cruise) {
        if (lostCapped) return false;
        lostCapped = true;
        range = Math.min(range, path * PATH_MARGIN + LOST_RESERVE_TICKS * cruise);
        return true;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("range", range);
        tag.putBoolean("lost_capped", lostCapped);
        return tag;
    }

    public static Mission load(CompoundTag tag) {
        return new Mission(tag.getDouble("range"), tag.getBoolean("lost_capped"));
    }

    /**
     * Projectile saved with lifetime (2.3.0 and earlier): remaining {@code ticksLeft} plus fractional chase ticks
     * {@code credit} mean flight at saved {@code cruise} speed (2.3.0 missile flew at 11.5 blocks/tick: remaining
     * path in blocks is independent of new speed). Preserve the “reduced after target loss” flag.
     */
    public static Mission fromLifetime(double ticksLeft, double credit, boolean lostCapped, double cruise) {
        return new Mission((ticksLeft + credit) * cruise, lostCapped);
    }
}
