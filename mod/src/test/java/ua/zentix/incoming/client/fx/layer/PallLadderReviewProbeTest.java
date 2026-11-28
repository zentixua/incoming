package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probes for the pall ladder (PR #812, round 1). Each test runs the real {@link PallPass.Detail} in a closed loop
 * against a scene whose GPU parts follow the rung the way the GPU does, and asserts what the ladder must do.
 */
class PallLadderReviewProbeTest {
    private static final long MS = 1_000_000L;
    private static final double B = PallPass.Quality.MEDIUM.budget;

    /**
     * A scene's GPU parts at MEDIUM's rung (ms): rest, rays, near-volume medium, composites. {@code dustRead}: the composites'
     * part the four dust cuts take (0 without a dust pass this frame: dust off, or a pall with no dust source in view), the only
     * part {@link PallPass.Step#cutOnce} can save.
     */
    private record Scene(double rest, double rays, double near, double composite, double dustRead) {
        double at(int k) {
            PallPass.Step from = PallPass.LADDER[PallPass.Quality.MEDIUM.step()], s = PallPass.LADDER[k];
            double comp = composite - (s.cutOnce() ? dustRead * (1 - PallPass.COMPOSITE_ONCE) : 0);
            return rest + rays * s.cost() / from.cost() + near * s.volumeCost() / from.volumeCost() + comp;
        }

        double raysAt(int k) {
            PallPass.Step from = PallPass.LADDER[PallPass.Quality.MEDIUM.step()];
            return rays * PallPass.LADDER[k].cost() / from.cost();
        }

        double nearAt(int k) {
            PallPass.Step from = PallPass.LADDER[PallPass.Quality.MEDIUM.step()];
            return near * PallPass.LADDER[k].volumeCost() / from.volumeCost();
        }

        double compositeAt(int k) {
            return composite - (PallPass.LADDER[k].cutOnce() ? dustRead * (1 - PallPass.COMPOSITE_ONCE) : 0);
        }
    }

    /** 16 ms frames of the scene at the ladder's current rung, {@code spike} × every part; clock in {@code clock[0]}. */
    private static void run(PallPass.Detail d, Scene s, double spike, int frames, long[] clock) {
        for (int i = 0; i < frames; i++) {
            clock[0] += 16 * MS;
            int k = d.level;
            d.sample(spike * s.at(k), spike * s.raysAt(k), 0, 0, spike * s.nearAt(k), spike * s.compositeAt(k), d.generation, clock[0]);
        }
    }

    /** The finest rung at or below the setting whose true cost is within the ladder's own up-share of the budget. */
    private static int finestFitting(Scene s) {
        for (int k = PallPass.Quality.MEDIUM.step(); k < PallPass.LADDER.length; k++) {
            if (s.at(k) <= PallPass.Detail.UP_SHARE * B) return k;
        }
        return PallPass.LADDER.length - 1;
    }

    /**
     * A pall without a dust pass (dust OFF, or haze, mushroom and firestorm with no dust source in view): the composites read no
     * dust, so cutting the dust once saves nothing. The ladder's estimate still scales the whole composite part by
     * {@link PallPass#COMPOSITE_ONCE} across rung 4 ↔ 5: after one heavy moment it can never climb from rung 5 to rung 4 although
     * rung 4 fits, the class {@code PallDetailTest.fixedPassesDoNotKeepDetailDown} guards for the other fixed passes.
     */
    @Test
    void compositeWithoutDustDoesNotKeepTheRungDown() {
        Scene s = new Scene(1.5, 8, 2, 6, 0);
        int fits = finestFitting(s);
        assertEquals(4, fits, "the scene: rung 4 fits, rung 3 does not");
        PallPass.Detail d = new PallPass.Detail();
        d.setting(PallPass.Quality.MEDIUM);
        long[] clock = {0};
        // a heavy moment (the flash, a firestorm's peak): 2 s at 1.6 × every part
        run(d, s, 1.6, 125, clock);
        assertTrue(d.level > fits, "the heavy moment drops below rung 4: " + d.level);
        // then a minute of the steady scene
        run(d, s, 1.0, 60 * 62, clock);
        assertTrue(d.level <= fits, "rung " + d.level + " a minute later, true cost " + String.format(java.util.Locale.ROOT, "%.2f", s.at(d.level))
                + " ms; rung " + fits + " costs " + String.format(java.util.Locale.ROOT, "%.2f", s.at(fits)) + " ms of the 13.5 the ladder climbs at");
    }

    /** Control: the same scene with the composites counted as fixed (no composite part reported) climbs back to rung 4. */
    @Test
    void controlFixedCompositeClimbsBack() {
        Scene s = new Scene(1.5, 8, 2, 6, 0);
        PallPass.Detail d = new PallPass.Detail();
        d.setting(PallPass.Quality.MEDIUM);
        long[] clock = {0};
        for (int i = 0; i < 125 + 60 * 62; i++) {
            clock[0] += 16 * MS;
            int k = d.level;
            double spike = i < 125 ? 1.6 : 1.0;
            d.sample(spike * s.at(k), spike * s.raysAt(k), 0, 0, spike * s.nearAt(k), 0, d.generation, clock[0]);
        }
        assertEquals(finestFitting(s), d.level);
    }

    /**
     * The LOW setting's own rung (its picture, also every secondary view and pinned scenario / Flashback-export frames) now
     * evaluates a quarter of the near volume per frame and cuts the dust once. At the same e-fold time a cell's history then
     * averages fewer points: the steady-state variance of an exponential average with weight a is a/(2−a) of one point's.
     */
    @Test
    void lowSettingKeepsItsPicture() {
        PallPass.Step low = PallPass.LADDER[PallPass.Quality.LOW.step()];
        double a1 = PallPass.VOLUME_CURRENT, an = PallPass.volumeShare(low.volume());
        double noise = Math.sqrt((an / (2 - an)) / (a1 / (2 - a1)));
        System.out.printf(java.util.Locale.ROOT, "LOW rung %d: %s; near-volume noise ×%.2f (std) against every cell every frame%n",
                PallPass.Quality.LOW.step(), low.describe(), noise);
        assertEquals(1, low.volume(), "LOW's own picture: near-volume noise ×" + String.format(java.util.Locale.ROOT, "%.2f", noise));
        assertFalse(low.cutOnce(), "LOW's own picture: four dust cuts per pixel");
    }
}
