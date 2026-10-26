package ua.zentix.incoming.client.fx;

import ua.zentix.incoming.client.far.Sight;

/**
 * On-screen explosion flash strength (0..1) as seen by the eye; no Minecraft, allowing a unit test of the curve.
 * <ul>
 *   <li>Point-source illuminance falls as 1/d² (inverse-square law), capped closer than {@code near}
 *       (fireball and what its light floods).</li>
 *   <li>Full-screen glare is a veil: light the bright source scatters in the eye. Under the Stiles–Holladay
 *       formula (CIE, disability glare), brightness is proportional to eye illuminance and falls with angle
 *       between gaze and source as 1/θ². Perceived brightness follows Stevens' power law with exponent 0.5 for a
 *       point source, so on-screen veil falls as near/d rather than 1/d².</li>
 *   <li>An explosion outside the field of view does not dazzle: only reflected light on surroundings remains ({@link #AMBIENT});
 *       behind an obstruction, only that contribution, weaker ({@link #OCCLUDED}).</li>
 *   <li>Closer than {@code near}, the explosion fills the view and its angle stops mattering.</li>
 *   <li>Strength smoothly reaches zero at {@code range}, without an edge step.</li>
 *   <li>Flash light is fireball light relative to surroundings ({@link #daylight}): by day it adds a few percent
 *       of sunlight and barely brightens the screen; in twilight/night, a flash.</li>
 * </ul>
 * The nuclear flash is separate ({@code client/nuclear/NukeFlash}): it is meant to dazzle.
 */
public final class FlashFalloff {
    /** Near strength, facing the explosion. */
    static final float PEAK = 0.85f;
    /** Strength share from a directly visible explosion behind the viewer: light reflected by surroundings. */
    static final float AMBIENT = 0.5f;
    /** Strength share from an explosion behind an obstruction. */
    static final float OCCLUDED = 0.35f;
    /**
     * Daytime screen-flash strength relative to full strength (darkness): almost absent by day. presentation: fireball world light is
     * computed by {@code CloudModel.emittedLight} and {@code HeatPass}; this number does not derive the old “4–11 % of sunlight”.
     */
    static final double DAYLIGHT = 0.08;

    private FlashFalloff() {}

    /**
     * Flash share at eye-local ambient light ({@code FarView#adaptation}: 1 under daytime sky, 0.17 at night, 0.12
     * in darkness): {@link #DAYLIGHT} by day; at twilight, inside caves/houses, light drops by orders of magnitude (eye adapts,
     * {@link Sight#adapted}), giving full flash strength.
     */
    public static float daylight(double ambient) {
        return (float) Math.min(1, DAYLIGHT * Sight.adapted(1, ambient));
    }

    /**
     * @param d       eye-to-explosion distance, blocks
     * @param near    full strength closer than this (fireball radius with margin), blocks
     * @param range   no flash beyond this, blocks
     * @param cosView cosine of angle between gaze and explosion direction
     * @param visible explosion directly visible
     */
    public static float strength(double d, double near, double range, double cosView, boolean visible) {
        if (d >= range) return 0;
        double distance = Math.min(1, near / Math.max(d, 1e-6));
        double r = d / range;
        double edge = 1 - r * r * r * r;
        double facing = Math.max(0, cosView);
        double view = visible ? AMBIENT + (1 - AMBIENT) * facing * facing : OCCLUDED;
        // up close the explosion fills the whole view with light in any gaze direction: angle stops mattering toward zero
        if (visible && d < near) view += (1 - view) * (1 - d / near);
        return (float) (PEAK * distance * edge * view);
    }
}
