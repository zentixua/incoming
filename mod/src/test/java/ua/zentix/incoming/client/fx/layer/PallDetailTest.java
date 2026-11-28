package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pall details by time on the GPU: steps, budget, descent immediately, ascent with a reserve and not higher than the settings. */
class PallDetailTest {
    private static final long MS = 1_000_000L;
    /** Lowest rung: the LOW setting's picture with fewer steps per ray. */
    private static final int FLOOR = PallPass.LADDER.length - 1;
    /** The MEDIUM setting's budget, ms. */
    private static final double B = PallPass.Quality.MEDIUM.budget;
    /** The HIGH setting's budget, ms. */
    private static final double H = PallPass.Quality.HIGH.budget;

    /** Frames of 16 ms with one time on the GPU, all of it rays, {@code frames} pieces; clock in {@code clock[0]}. */
    private static void frames(PallPass.Detail d, double gpuMs, int frames, long[] clock) {
        frames(d, gpuMs, gpuMs, frames, clock);
    }

    /** Frames of 16 ms: {@code gpuMs} on the GPU, of which {@code rayMs} the ray march; clock in {@code clock[0]}. */
    private static void frames(PallPass.Detail d, double gpuMs, double rayMs, int frames, long[] clock) {
        for (int i = 0; i < frames; i++) {
            clock[0] += 16 * MS;
            d.sample(gpuMs, rayMs, 0, 0, d.generation, clock[0]);
        }
    }

    /**
     * Rungs from HIGH down, each at most half the price of the previous one; MEDIUM and LOW are among them; below LOW only the
     * steps per ray thin (fewer rays per frame drew a lattice, a coarser frame stairs); LOW costs what the former LOW (quarter
     * frame × 20 steps) did. The near volume interleaves with the rays and never thins its cells back.
     */
    @Test
    void ladderRunsFromHighToLow() {
        assertEquals(0, PallPass.Quality.HIGH.step());
        int medium = PallPass.Quality.MEDIUM.step(), low = PallPass.Quality.LOW.step();
        assertTrue(medium > 0 && medium < low && low < FLOOR);
        assertEquals(1, PallPass.LADDER[medium].volume(), "MEDIUM's own picture keeps every near-volume cell every frame");
        for (int i = low + 1; i <= FLOOR; i++) {
            PallPass.Step s = PallPass.LADDER[i], l = PallPass.LADDER[low];
            assertTrue(s.steps() >= 12 && s.steps() < l.steps() && s.interleave() == l.interleave() && s.volume() == l.volume()
                    && s.factor() == l.factor(), "below LOW only the steps thin: " + s.describe());
        }
        double old = 20 / 16.0, cost = PallPass.LADDER[low].cost();
        assertTrue(cost >= 0.9 * old && cost <= 1.15 * old, "LOW: " + cost);
        assertEquals(-1, PallPass.Quality.OFF.step());
        // never below half frame: coarser frames drew distant ridges as stairs of 8–16-pixel blocks (Artem's laptop 08.11)
        for (PallPass.Step step : PallPass.LADDER) assertEquals(2, step.factor(), "frame below half: " + step.describe());
        for (int i = 1; i < PallPass.LADDER.length; i++) {
            double ratio = PallPass.LADDER[i].cost() / PallPass.LADDER[i - 1].cost();
            assertTrue(ratio >= 0.5 && ratio < 0.9, "step " + i + ": " + ratio);
            assertTrue(PallPass.LADDER[i].steps() >= (i <= low ? 20 : 12) && PallPass.LADDER[i].interleave() <= 2, "below the floor");
            assertTrue(PallPass.LADDER[i].volume() >= PallPass.LADDER[i - 1].volume(), "near volume thins with the rung");
        }
    }

    /**
     * Each setting keeps its own rung at the GPU times measured on Artem's laptop (RTX 4060, 2560×1600, 150 kt firestorm in the
     * dust, 08.11, rung free: LOW up to 6.1 ms, MEDIUM up to 12.5, HIGH up to 19.2), also with a tenth more in passing windows; finer
     * settings get larger budgets.
     */
    @Test
    void settingsHoldTheirRungAtMeasuredTimes() {
        assertTrue(PallPass.Quality.LOW.budget < B && B < H);
        double[][] measured = {{6.1, PallPass.Quality.LOW.ordinal()}, {12.5, PallPass.Quality.MEDIUM.ordinal()}, {19.2, PallPass.Quality.HIGH.ordinal()}};
        for (double[] m : measured) {
            PallPass.Quality q = PallPass.Quality.values()[(int) m[1]];
            PallPass.Detail d = new PallPass.Detail();
            long[] clock = {0};
            d.setting(q);
            frames(d, m[0], 2000, clock);
            assertEquals(q.step(), d.level, q + " at " + m[0] + " ms");
            for (int k = 0; k < 20; k++) {
                frames(d, 1.1 * m[0], 40, clock);
                frames(d, m[0], 40, clock);
            }
            assertEquals(q.step(), d.level, q + " with passing windows at " + 1.1 * m[0] + " ms");
        }
    }

    /** Each interleave order visits every pixel of its n × n block once per n² frames. */
    @Test
    void interleaveOrderVisitsEveryBlockPixelOnce() {
        for (int n = 1; n <= PallPass.ORDER.length; n++) {
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
        for (int n = 1; n <= PallPass.ORDER.length; n++) {
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

    /** Longer than the budget - immediately down a notch, whatever fits; very long (software OpenGL) - to the floor, not lower. */
    @Test
    void overBudgetStepsDownButNotBelowTheFloor() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        d.setting(PallPass.Quality.MEDIUM);
        frames(d, 1.75 * B, 40, clock);
        assertTrue(d.level > PallPass.Quality.MEDIUM.step(), "1.75 budgets - lower");
        assertTrue(d.level < FLOOR, "1.75 budgets - not immediately down to the floor");
        frames(d, 150, 200, clock);
        assertEquals(FLOOR, d.level);
        frames(d, 150, 200, clock);
        assertEquals(FLOOR, d.level);
    }

    /** In the budget - the level is maintained; stock - up, but not immediately after descent, step by step and not higher than the setting. */
    @Test
    void roomStepsUpWithHysteresis() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        int medium = PallPass.Quality.MEDIUM.step();
        d.setting(PallPass.Quality.MEDIUM);
        frames(d, 0.9 * B, 200, clock);
        assertEquals(medium, d.level, "0.9 budget - in budget");
        frames(d, 3 * B, 40, clock);
        int down = d.level;
        assertTrue(down > medium);
        // the reserve is large, but after descent there is a pause
        frames(d, 0.1 * B, 60, clock);
        assertEquals(down, d.level, "does not rise immediately after descent");
        frames(d, 0.1 * B, 400, clock);
        assertTrue(d.level < down, "stock - up");
        frames(d, 0.1 * B, 4000, clock);
        assertEquals(medium, d.level, "no higher than setting");
        // at the lower stages 0.95 budget; a step higher in price will not fit into 0.7 budget - it stays
        d.setting(PallPass.Quality.HIGH);
        assertEquals(0, d.level, "new setting - immediately its level");
        frames(d, 1.5 * H, 40, clock);
        assertTrue(d.level > 0);
        frames(d, 0.95 * H, 100, clock);
        int level = d.level;
        frames(d, 0.95 * H, 2000, clock);
        assertEquals(level, d.level);
    }

    @Test
    void wallWorkChoosesTheFinestFittingRungInBothDirections() {
        PallPass.Detail d = new PallPass.Detail();
        d.setting(PallPass.Quality.HIGH);
        long now = 0;
        // 14 ms fixed, 8 ms pall rays, 6 ms wall march: rung 1 exceeds 24, rung 2 fits.
        while (now < 1200 * MS) d.sample(28, 8, 6, 0, d.generation, now += 16 * MS);
        assertEquals(2, d.level);
        // 13 ms fixed, 4 ms pall rays, 3 ms wall resolve. At rung 1 these become 13 + 5.14 + 4.69,
        // above the 21.6 ms promotion threshold. Treating the resolve as fixed would promote.
        while (now < 10000 * MS) d.sample(20, 4, 0, 3, d.generation, now += 16 * MS);
        assertEquals(2, d.level);
        // The same guard must account for the march's larger step cap as well as its extra pixels.
        while (now < 20000 * MS) d.sample(20, 4, 3, 0, d.generation, now += 16 * MS);
        assertEquals(2, d.level);
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
        d.setting(PallPass.Quality.HIGH);
        frames(d, 2 * H, 40, clock);
        assertTrue(d.level > high);
        int old = d.generation;
        assertTrue(d.pin(true));
        assertFalse(d.pin(true));
        assertEquals(high, d.level, "nailed - setting stage");
        frames(d, 150, 400, clock);
        assertEquals(high, d.level, "slow video output frames do not reduce detail");
        d.setting(PallPass.Quality.MEDIUM);
        assertEquals(PallPass.Quality.MEDIUM.step(), d.level, "new setting - its stage");
        frames(d, 150, 400, clock);
        assertEquals(PallPass.Quality.MEDIUM.step(), d.level);
        d.setting(PallPass.Quality.HIGH);
        assertTrue(d.pin(false));
        for (int i = 0; i < 100; i++) assertFalse(d.sample(150, 150, 0, 0, old, clock[0] += 16 * MS), "measurements before nailing do not count");
        assertEquals(high, d.level);
        frames(d, 2 * H, 40, clock);
        assertTrue(d.level > high, "released - again on budget");
    }

    /** pall has disappeared from the frame or the frame size has changed: pairs of marks taken before are not counted. */
    @Test
    void restartDropsPendingSamples() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        int medium = PallPass.Quality.MEDIUM.step();
        d.setting(PallPass.Quality.MEDIUM);
        frames(d, 1, 10, clock);
        int before = d.generation;
        d.restart();
        assertEquals(medium, d.level);
        for (int i = 0; i < 100; i++) assertFalse(d.sample(50, 50, 0, 0, before, clock[0] += 16 * MS));
        assertEquals(medium, d.level, "old couples don't count");
        frames(d, 50, 40, clock);
        assertTrue(d.level > medium, "new ones count");
    }

    /** The measurements of the previous stage are not counted, and the first frame of the new one is not counted either; without settings - nothing. */
    @Test
    void staleSamplesIgnored() {
        PallPass.Detail d = new PallPass.Detail();
        assertFalse(d.sample(100, 100, 0, 0, d.generation, 0));
        d.setting(PallPass.Quality.MEDIUM);
        int old = d.generation - 1;
        for (int i = 0; i < 100; i++) assertFalse(d.sample(100, 100, 0, 0, old, i * 16 * MS));
        assertEquals(PallPass.Quality.MEDIUM.step(), d.level);
    }

    /**
     * One window a hair over budget does not drop detail (laptop 08.11: HIGH fell two rungs on a window of 18.04 ms against 18
     * and stayed there for a minute); over budget for two windows in a row does.
     */
    @Test
    void oneWindowOverBudgetKeepsTheRung() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        d.setting(PallPass.Quality.HIGH);
        for (int k = 0; k < 20; k++) {
            frames(d, 1.02 * H, 40, clock);
            frames(d, 0.9 * H, 40, clock);
        }
        assertEquals(0, d.level, "single windows over budget");
        frames(d, 1.05 * H, 120, clock);
        assertTrue(d.level > 0, "two windows over budget");
    }

    /**
     * Only the rays scale with the rung: the near volume, sums, accumulation and composite stay. With most of the time outside the
     * rays, a lower rung saves little, so after a costly view passes the setting's rung comes back (laptop 08.11: MEDIUM sat at
     * the interleaved floor for 80 s at the same cost as its own rung, because the whole time was scaled by the rung).
     */
    @Test
    void fixedPassesDoNotKeepDetailDown() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        d.setting(PallPass.Quality.HIGH);
        frames(d, 1.4 * H, 0.6 * H, 40, clock);
        int down = d.level;
        assertTrue(down > 0, "costly view - lower");
        assertTrue(down < FLOOR, "rays alone fit a rung above the floor");
        // the view is back to the usual: rays a third of the time at the lower rung, the rest fixed
        frames(d, 0.75 * H, 0.25 * H, 6000, clock);
        assertEquals(0, d.level, "the setting's rung again");
    }

    /**
     * {@link GpuWatch} floor (#656): the last rung from then on, whatever the setting, the pin of the scenario or later fast
     * frames ask.
     */
    @Test
    void flooredDetailStaysAtTheLastRung() {
        PallPass.Detail d = new PallPass.Detail();
        d.setting(PallPass.Quality.HIGH);
        assertEquals(0, d.level);
        d.floor();
        assertEquals(FLOOR, d.level);
        long[] clock = {0};
        frames(d, 1, 400, clock);
        assertEquals(FLOOR, d.level, "fast frames do not raise a floored rung");
        d.pin(true);
        assertEquals(FLOOR, d.level, "pinning does not lift the floor");
        d.pin(false);
        d.setting(PallPass.Quality.MEDIUM);
        d.setting(PallPass.Quality.HIGH);
        assertEquals(FLOOR, d.level, "a new setting does not lift the floor");
    }

    /**
     * Artem's Zearth scene (28.11, MEDIUM, 2560×1600, Photon, eye in the haze): at the floor of the old ladder the volume cost
     * 21–29 ms against 15, of which the rays 8, the near volume 5.5. The near volume follows the rung, the floor thins the steps:
     * the estimates see both, so the ladder steps to a rung that fits instead of to the floor, and the floor itself fits.
     */
    @Test
    void nearVolumeAndStepsLetTheFloorFit() {
        // the parts at the old floor (rung 8: 22 steps, every near cell, four dust cuts): rays 8, near volume 5, composites 6.5,
        // the rest 2.8 (Zearth +150…+380 s)
        PallPass.Step old = new PallPass.Step(2, 22, 2, 6, 96, 1, false), floor = PallPass.LADDER[FLOOR];
        double rest = 2.8, rays = 8, near = 5, composite = 6.5;
        double atFloor = rest + rays * floor.cost() / old.cost() + near * floor.volumeCost() / old.volumeCost()
                + composite * floor.compositeCost() / old.compositeCost();
        assertTrue(atFloor <= B, "the floor fits MEDIUM's budget at Artem's scene: " + atFloor);
        // at MEDIUM's own rung the same scene: rays 28 (measured, rung 2) → the first fitting rung, not the floor
        PallPass.Detail d = new PallPass.Detail();
        d.setting(PallPass.Quality.MEDIUM);
        long now = 0;
        double r2 = 28;
        for (int i = 0; i < 40; i++) d.sample(rest + r2 + near + composite, r2, 0, 0, near, composite, d.generation, now += 16 * MS);
        int to = d.level;
        assertTrue(to > PallPass.Quality.MEDIUM.step() && to <= FLOOR, "down: " + to);
        PallPass.Step from = PallPass.LADDER[PallPass.Quality.MEDIUM.step()];
        java.util.function.IntToDoubleFunction at = k -> rest + r2 * PallPass.LADDER[k].cost() / from.cost()
                + near * PallPass.LADDER[k].volumeCost() / from.volumeCost() + composite * PallPass.LADDER[k].compositeCost() / from.compositeCost();
        assertTrue(at.applyAsDouble(to) <= B || to == FLOOR, "the chosen rung fits: " + at.applyAsDouble(to));
        assertTrue(at.applyAsDouble(to - 1) > B, "no finer rung fits: " + at.applyAsDouble(to - 1));
    }

    /** A near-volume cell that takes a point every n-th frame takes the share that keeps its e-fold time in frames. */
    @Test
    void interleavedNearVolumeKeepsItsTimeConstant() {
        for (int n : new int[] {1, 2, 4}) {
            double perFrame = 1 - Math.pow(1 - PallPass.volumeShare(n), 1.0 / n);
            assertEquals(PallPass.VOLUME_CURRENT, perFrame, 1e-6, "n " + n);
        }
    }

    /**
     * A game-mode switch (Artem's Zearth and Newisle sessions, 28.11: MEDIUM to the floor within 2–5 s of switching to
     * spectator) restarts the measurement: the renderer's rebuild frames of the next second are not the volume's cost.
     */
    @Test
    void gameModeSwitchDoesNotMeasureTheRebuild() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        d.setting(PallPass.Quality.MEDIUM);
        frames(d, 0.8 * B, 200, clock);
        int level = d.level;
        d.viewChanged(clock[0]);
        frames(d, 3 * B, 55, clock);
        assertEquals(level, d.level, "the rebuild's frames drop no rung");
        frames(d, 0.8 * B, 200, clock);
        assertEquals(level, d.level);
        d.viewChanged(clock[0]);
        frames(d, 0.8 * B, 70, clock);
        frames(d, 3 * B, 80, clock);
        assertTrue(d.level > level, "a real load after the quiet second still drops the rung");
    }

    /** World exit: the next world starts at its setting's rung (Artem's Newisle, 28.11, started at Zearth's floor). */
    @Test
    void worldExitStartsTheLadderAfresh() throws ReflectiveOperationException {
        java.lang.reflect.Field f = PallPass.class.getDeclaredField("DETAIL");
        f.setAccessible(true);
        PallPass.Detail d = (PallPass.Detail) f.get(null);
        d.setting(PallPass.Quality.MEDIUM);
        long[] clock = {0};
        frames(d, 5 * B, 200, clock);
        assertEquals(FLOOR, d.level);
        PallPass.leaveWorld();
        PallPass.Detail fresh = (PallPass.Detail) f.get(null);
        fresh.setting(PallPass.Quality.MEDIUM);
        assertEquals(PallPass.Quality.MEDIUM.step(), fresh.level, "the new world at the setting's rung");
    }
}
