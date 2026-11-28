package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Projectile sound-path rays ({@link Occlusion}) share {@link Occlusion#RAYS} a tick at any salvo size: each ray walks
 * every block to the edge of the loaded world, and one a tick per projectile was 5 % of the render thread during a
 * nuclear front. Each projectile still casts in turn, and few projectiles cast every tick.
 */
class OcclusionTest {
    @Test
    void raysPerTickAreBoundedAndEveryProjectileCastsInTurn() {
        for (int n : new int[]{1, 3, Occlusion.RAYS, Occlusion.RAYS + 1, 30, 300}) {
            Occlusion.Budget budget = new Occlusion.Budget(Occlusion.RAYS);
            long[] last = new long[n];
            java.util.Arrays.fill(last, Long.MIN_VALUE / 2);
            long[] longest = new long[n];
            long[] first = new long[n];
            java.util.Arrays.fill(first, -1);
            for (long t = 0; t < 400; t++) {
                int cast = 0;
                for (int i = 0; i < n; i++) {
                    if (!budget.take(t, last[i])) continue;
                    if (first[i] < 0) first[i] = t;
                    else longest[i] = Math.max(longest[i], t - last[i]);
                    last[i] = t;
                    cast++;
                }
                assertTrue(cast <= Occlusion.RAYS, n + " projectiles: " + cast + " rays at tick " + t);
            }
            long turn = (n + Occlusion.RAYS - 1) / Occlusion.RAYS;
            for (int i = 0; i < n; i++) {
                assertTrue(first[i] >= 0 && first[i] <= turn + 1, n + " projectiles: #" + i + " first ray at " + first[i]);
                assertTrue(longest[i] <= turn + 1, n + " projectiles: #" + i + " waited " + longest[i] + " ticks");
                if (n <= Occlusion.RAYS) assertEquals(1, longest[i], "few projectiles ray every tick");
            }
        }
    }

    /** Between its rays a projectile's openness keeps moving toward its last ray, as with a ray every tick. */
    @Test
    void opennessSmoothsTowardTheLastRay() {
        List<Occlusion> salvo = new ArrayList<>();
        for (int i = 0; i < 40; i++) salvo.add(new Occlusion());
        float[] open = new float[salvo.size()];
        for (long t = 0; t < 200; t++)
            for (int i = 0; i < salvo.size(); i++) open[i] = salvo.get(i).open(t, () -> 0);
        for (float o : open) assertTrue(o < 1e-3, "behind the wall: " + o);
    }
}
