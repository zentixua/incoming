package ua.zentix.incoming.entity;

import ua.zentix.incoming.strike.WeaponType;

/**
 * Mobile launcher rack ({@link LauncherEntity}), part of the weapon spec ({@code WeaponSpec#rack}): elevation,
 * cell count, launch rate, projectile wait and occupancy time, cell locations.
 *
 * @param elevation rail elevation, °
 * @param slots rack cells
 * @param spacing minimum launch interval from one launcher, ticks
 * @param minReady minimum ticks from order to ignition
 * @param busyTicks projectile cell occupancy after readiness (ignition and launch rail exit; MLRS tube reload)
 */
public enum LauncherRack {
    /** Shaheds stacked vertically (also catapults loitering munitions, which have their own {@link #LOITER} rack). */
    DRONE(15f, 5, 16, 12, 24),
    /** Two inclined cruise missile canisters. */
    MISSILE(40f, 2, 16, 12, 24),
    /** MLRS rack: 4 rows of 10 tubes, half-second sequence; a tube reloads for a minute after launch. */
    ROCKET(50f, LauncherEntity.ROCKET_COLUMNS * LauncherEntity.ROCKET_ROWS, 8, 10, 1200),
    /** Loitering catapult: three rails below, two above. */
    LOITER(20f, 5, 16, 12, 24);

    private final float elevation;
    private final int slots;
    private final int spacing;
    private final int minReady;
    private final int busyTicks;

    LauncherRack(float elevation, int slots, int spacing, int minReady, int busyTicks) {
        this.elevation = elevation;
        this.slots = slots;
        this.spacing = spacing;
        this.minReady = minReady;
        this.busyTicks = busyTicks;
    }

    /**
     * Rack for {@code weapon} (spec, {@code WeaponSpec#rack}); a weapon without a rack (B-2, ICBM: no such
     * launcher except from someone else's save) uses the Shahed rack.
     */
    public static LauncherRack of(WeaponType weapon) {
        LauncherRack rack = weapon.spec().rack();
        return rack != null ? rack : DRONE;
    }

    public float elevation() {
        return elevation;
    }

    public int slots() {
        return slots;
    }

    public int spacing() {
        return spacing;
    }

    public int minReady() {
        return minReady;
    }

    public int busyTicks() {
        return busyTicks;
    }

    /**
     * Rack cell in its axes from the elevation pivot (position from the mount, {@link LauncherMount}): {left, up,
     * forward}, blocks, projectile center on the rail.
     */
    double[] slotOffset(int slot) {
        return switch (this) {
            case MISSILE -> new double[]{slot == 0 ? 0.62 : -0.62, 0.64, 3.3};
            case ROCKET -> {
                // Sequence runs by rows left to right, top row first, as on a Grad.
                int col = slot % LauncherEntity.ROCKET_COLUMNS, row = LauncherEntity.ROCKET_ROWS - 1 - slot / LauncherEntity.ROCKET_COLUMNS;
                yield new double[]{(LauncherEntity.ROCKET_COLUMNS - 1) * LauncherEntity.TUBE_PITCH / 2 - col * LauncherEntity.TUBE_PITCH,
                        0.3 + row * LauncherEntity.TUBE_PITCH, LauncherEntity.TUBE_LENGTH / 2};
            }
            // Lancet, 1.65 m: tail on the trolley by the rear wall (0.1 m), nose at rail midpoint.
            case LOITER -> new double[]{LauncherEntity.loiterLeft(slot), LauncherEntity.loiterUp(slot), 0.93};
            // Shaheds stacked vertically: cell center 2 m from pivot (tail with booster at the rear wall).
            case DRONE -> new double[]{0, 0.45 + 0.8 * slot, 2.0};
        };
    }
}
