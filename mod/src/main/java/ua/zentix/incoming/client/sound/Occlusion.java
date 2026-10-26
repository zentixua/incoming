package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;

/** Smoothed projectile sound-path openness to ear (1) or behind hill/house (0); computed once per tick for all its layers. */
final class Occlusion {
    /**
     * Fraction toward a new value per tick: projectile crosses behind a ridge and back over ~10 ticks (90 %; time
     * constant ~4.5 ticks), not with a click (presentation: edge Fresnel zone is tens of metres at hundreds of metres).
     */
    static final float SMOOTH = 0.2f;
    private float open = 1;
    private long tick = -1;

    /** Path from “retarded” {@code at} to ear open (1) or closed (0); smooth, without clicks. */
    float open(double now, Vec3 ear, Vec3 at) {
        long t = (long) now;
        if (t != tick) {
            tick = t;
            open += (SoundFilters.open(ear, at) - open) * SMOOTH;
        }
        return open;
    }
}
