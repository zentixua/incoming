package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pall details by time on the GPU: steps, budget, descent immediately, ascent with a reserve and not higher than the settings. */
class PallDetailTest {
    private static final long MS = 1_000_000L;
    /** Lowest rung (below the LOW setting: fewer rays per frame). */
    private static final int LOW = PallPass.LADDER.length - 1;

    /** Frames of 16 ms with one time on the GPU, {@code frames} pieces; watch - in {@code clock[0]}. */
    private static void frames(PallPass.Detail d, double gpuMs, int frames, long[] clock) {
        for (int i = 0; i < frames; i++) {
            clock[0] += 16 * MS;
            d.sample(gpuMs, d.generation, clock[0]);
        }
    }

    /**
     * Rungs from HIGH down, each at most half the price of the previous one; MEDIUM and LOW are among them, rungs below LOW
     * for a GPU it does not fit; LOW costs what the former LOW (quarter frame × 20 steps) did.
     */
    @Test
    void ladderRunsFromHighToLow() {
        assertEquals(0, PallPass.Quality.HIGH.step());
        int medium = PallPass.Quality.MEDIUM.step(), low = PallPass.Quality.LOW.step();
        assertTrue(medium > 0 && medium < low && low < LOW);
        double old = 20 / 16.0, cost = PallPass.LADDER[low].cost();
        assertTrue(cost >= 0.9 * old && cost <= 1.15 * old, "LOW: " + cost);
        assertEquals(-1, PallPass.Quality.OFF.step());
        for (int i = 1; i < PallPass.LADDER.length; i++) {
            double ratio = PallPass.LADDER[i].cost() / PallPass.LADDER[i - 1].cost();
            assertTrue(ratio >= 0.5 && ratio < 0.9, "step " + i + ": " + ratio);
            assertTrue(PallPass.LADDER[i].factor() <= 4 && PallPass.LADDER[i].steps() >= 20 && PallPass.LADDER[i].interleave() <= 4, "below the floor");
        }
    }

    /** Each interleave order visits every pixel of its n × n block once per n² frames. */
    @Test
    void interleaveOrderVisitsEveryBlockPixelOnce() {
        for (int n = 1; n <= 4; n++) {
            int[] order = PallPass.ORDER[n - 1];
            assertEquals(n * n, order.length);
            assertEquals(n * n, java.util.Arrays.stream(order).distinct().filter(p -> p >= 0 && p < order.length).count(), "n = " + n);
        }
    }

    /**
     * The march traces pixel cell·n + o of each block ({@code fx_pall}: {@code low}); the accumulation finds the block of every
     * pixel as round((px − o)/n), clamped to the cells, and takes the ray only where it lands on that pixel
     * ({@code fx_pall_resolve}: {@code own}, {@code traced}): over n² frames every pixel of a frame of any size takes exactly
     * one ray, also when the size is not a multiple of n.
     */
    @Test
    void interleavedRaysCoverEveryPixelOncePerCycle() {
        for (int n = 1; n <= 4; n++) {
            for (int size : new int[]{1, 7, 13, 64, 641}) {
                int cells = (size + n - 1) / n;
                int[] taken = new int[size];
                for (int frame = 0; frame < n * n; frame++) {
                    int phase = PallPass.ORDER[n - 1][frame], o = phase % n;
                    for (int px = 0; px < size; px++) {
                        int own = Math.max(0, Math.min(cells - 1, (int) Math.floor((px - o) / (double) n + 0.5)));
                        if (own * n + o == px) taken[px]++;
                    }
                }
                // one axis: offsets o repeat n times per cycle (the other axis takes the rest), so each pixel n times
                for (int px = 0; px < size; px++) assertEquals(n, taken[px], "n = " + n + ", size " + size + ", pixel " + px);
            }
        }
    }

    /** Longer than the budget - immediately down a notch, whatever fits; very long (software OpenGL) - up to LOW, but not lower. */
    @Test
    void overBudgetStepsDownButNotBelowLow() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        d.ceiling(PallPass.Quality.MEDIUM.step());
        frames(d, 3.5, 40, clock);
        assertTrue(d.level > PallPass.Quality.MEDIUM.step(), "3,5 ms with budget 2 - lower");
        assertTrue(d.level < LOW, "3,5 ms - not immediately until LOW");
        frames(d, 150, 200, clock);
        assertEquals(LOW, d.level);
        frames(d, 150, 200, clock);
        assertEquals(LOW, d.level);
    }

    /** In the budget - the level is maintained; stock - up, but not immediately after descent, step by step and not higher than the setting. */
    @Test
    void roomStepsUpWithHysteresis() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        int medium = PallPass.Quality.MEDIUM.step();
        d.ceiling(medium);
        frames(d, 1.8, 200, clock);
        assertEquals(medium, d.level, "1,8 ms - in budget");
        frames(d, 6, 40, clock);
        int down = d.level;
        assertTrue(down > medium);
        // the reserve is large, but after descent there is a pause
        frames(d, 0.2, 60, clock);
        assertEquals(down, d.level, "does not rise immediately after descent");
        frames(d, 0.2, 400, clock);
        assertTrue(d.level < down, "stock - up");
        frames(d, 0.2, 4000, clock);
        assertEquals(medium, d.level, "no higher than setting");
        // at the lower stages 1,9 ms; a step higher in price will not fit into the budget 0,7 - it’s worth it
        d.ceiling(PallPass.Quality.HIGH.step());
        assertEquals(0, d.level, "new setting - immediately its level");
        frames(d, 3, 40, clock);
        assertTrue(d.level > 0);
        frames(d, 1.9, 100, clock);
        int level = d.level;
        frames(d, 1.9, 2000, clock);
        assertEquals(level, d.level);
    }

    /**
     * Nailed (client script, video output): setting stage, no matter how long the frame lasts; lowered by the budget - immediately
     *  up to configuration. They released him - again according to the measurements, without those taken before.
     */
    @Test
    void pinnedStaysAtTheSetting() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        int high = PallPass.Quality.HIGH.step();
        d.ceiling(high);
        frames(d, 8, 40, clock);
        assertTrue(d.level > high);
        int old = d.generation;
        assertTrue(d.pin(true));
        assertFalse(d.pin(true));
        assertEquals(high, d.level, "nailed - setting stage");
        frames(d, 150, 400, clock);
        assertEquals(high, d.level, "slow video output frames do not reduce detail");
        d.ceiling(PallPass.Quality.MEDIUM.step());
        assertEquals(PallPass.Quality.MEDIUM.step(), d.level, "new setting - its stage");
        frames(d, 150, 400, clock);
        assertEquals(PallPass.Quality.MEDIUM.step(), d.level);
        d.ceiling(high);
        assertTrue(d.pin(false));
        for (int i = 0; i < 100; i++) assertFalse(d.sample(150, old, clock[0] += 16 * MS), "measurements before nailing do not count");
        assertEquals(high, d.level);
        frames(d, 8, 40, clock);
        assertTrue(d.level > high, "released - again on budget");
    }

    /** pall has disappeared from the frame or the frame size has changed: pairs of marks taken before are not counted. */
    @Test
    void restartDropsPendingSamples() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        int medium = PallPass.Quality.MEDIUM.step();
        d.ceiling(medium);
        frames(d, 1, 10, clock);
        int before = d.generation;
        d.restart();
        assertEquals(medium, d.level);
        for (int i = 0; i < 100; i++) assertFalse(d.sample(50, before, clock[0] += 16 * MS));
        assertEquals(medium, d.level, "old couples don't count");
        frames(d, 50, 40, clock);
        assertTrue(d.level > medium, "new ones count");
    }

    /** The measurements of the previous stage are not counted, and the first frame of the new one is not counted either; without settings - nothing. */
    @Test
    void staleSamplesIgnored() {
        PallPass.Detail d = new PallPass.Detail();
        assertFalse(d.sample(100, d.generation, 0));
        d.ceiling(PallPass.Quality.MEDIUM.step());
        int old = d.generation - 1;
        for (int i = 0; i < 100; i++) assertFalse(d.sample(100, old, i * 16 * MS));
        assertEquals(PallPass.Quality.MEDIUM.step(), d.level);
    }
}
