package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;

/**
 * Smoothed projectile sound-path openness to ear (1) or behind hill/house (0); computed once per tick for all its layers.
 * <p>
 * Rays are shared by every audible projectile: at most {@link #RAYS} a tick ({@link Budget}). With more projectiles
 * each casts every ⌈n / {@link #RAYS}⌉ ticks and its openness keeps moving toward its last ray between them, so the cost
 * of a tick is bounded at any salvo size (each ray walks every block to the edge of the loaded world: 5 % of the render
 * thread during a nuclear front).
 */
final class Occlusion {
    /**
     * Fraction toward a new value per tick: projectile crosses behind a ridge and back over ~10 ticks (90 %; time
     * constant ~4.5 ticks), not with a click (presentation: edge Fresnel zone is tens of metres at hundreds of metres).
     */
    static final float SMOOTH = 0.2f;
    /** Sound-path rays per tick, all projectiles together. */
    static final int RAYS = 8;
    private static final Budget BUDGET = new Budget(RAYS);

    private float open = 1, target = 1;
    private long tick = -1, cast = Long.MIN_VALUE / 2;

    /** Path from “retarded” {@code at} to ear open (1) or closed (0); smooth, without clicks. */
    float open(double now, Vec3 ear, Vec3 at) {
        return open((long) now, () -> SoundFilters.open(ear, at));
    }

    interface Ray {
        float open();
    }

    float open(long t, Ray ray) {
        if (t != tick) {
            tick = t;
            if (BUDGET.take(t, cast)) {
                cast = t;
                target = ray.open();
            }
            open += (target - open) * SMOOTH;
        }
        return open;
    }

    /**
     * Rays of one tick: a projectile asks once a tick; it casts when the tick still has a ray and its own last ray is at
     * least ⌈asking / rays⌉ ticks old (asking: projectiles that asked the previous tick), so early askers do not take every
     * ray and each projectile casts in turn. One or few projectiles cast every tick.
     */
    static final class Budget {
        private final int rays;
        private long tick = Long.MIN_VALUE;
        private int left, asking, asked;

        Budget(int rays) {
            this.rays = rays;
        }

        boolean take(long t, long last) {
            if (t != tick) {
                asked = t == tick + 1 ? asking : 0;
                tick = t;
                asking = 0;
                left = rays;
            }
            asking++;
            long gap = Math.max(1, (asked + rays - 1) / rays);
            if (left <= 0 || t - last < gap) return false;
            left--;
            return true;
        }
    }
}
