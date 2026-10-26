package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Layer time sample ({@link LayerTimes}): without it there are no marks; drain frame - upon request; Parts of the squares are drawn each completely and once. */
class LayerTimesTest {
    /** The test does not work - the passage timer does not call OpenGL at all (it is not in the test: the call would fail) and does not measure the frame. */
    @Test
    void timerCostsNothingWithoutTheProbe() {
        assertEquals(false, LayerTimes.on());
        GpuTimer timer = new GpuTimer();
        int pair = timer.begin();
        assertEquals(-1, pair);
        timer.end(pair);
        timer.second();
        assertEquals(0, timer.lastFrames);
    }

    /**
     * Drain frame (glFinish for each label, parts separately: 20–28 ms processor) - only upon request: F3 or the launch property
     * . The scripts and the stand keep the sample enabled by reading the line, and 25.10 in look_gate this frame once in 2 s was a jerk in
     *  their own metering.
     */
    @Test
    void drainedFrameOnlyWhenAsked() {
        long s = 1_000_000_000L;
        for (long now = 0; now < 60 * s; now += s / 60) assertEquals(false, LayerTimes.drains(false, false, 0, now));
        assertEquals(true, LayerTimes.drains(true, false, 0, 5 * s));
        assertEquals(true, LayerTimes.drains(false, true, 0, 5 * s));
        assertEquals(false, LayerTimes.drains(true, false, 5 * s, 6 * s));
        assertEquals(true, LayerTimes.drains(true, false, 5 * s, 5 * s + LayerTimes.DRAIN_EVERY_NS));
    }

    /**
     * Segments of parts in sorting order: each square of the frame is in exactly one segment of its part, segments of one part
     *  do not touch (adjacent ones are merged into one call). The sum of the parts individually is the entire frame, without gaps or repetitions.
     */
    @Test
    void partRunsCoverEveryQuadOnce() {
        Random random = new Random(7);
        int parts = FxQuads.Part.values().length;
        for (int n : new int[]{0, 1, 2, 17, 4000}) {
            byte[] part = new byte[n];
            int[] order = new int[n];
            for (int i = 0; i < n; i++) {
                // long series of one part and frequent changes - like billows in smoke and ribbons between them
                part[i] = (byte) (random.nextInt(3) == 0 ? random.nextInt(parts) : i > 0 ? part[i - 1] : 0);
                order[i] = i;
            }
            for (int i = n - 1; i > 0; i--) {
                int j = random.nextInt(i + 1), t = order[i];
                order[i] = order[j];
                order[j] = t;
            }
            int[][] runs = FxQuads.runs(part, order, n);
            int[] seen = new int[n];
            for (int p = 0; p < parts; p++) {
                int[] r = runs[p];
                for (int k = 0; k < r.length; k += 2) {
                    for (int i = r[k]; i < r[k] + r[k + 1]; i++) {
                        assertEquals(p, part[order[i]], "square in a segment of its part");
                        seen[i]++;
                    }
                    if (k > 0) assertNotEquals(r[k - 2] + r[k - 1], r[k], "adjacent segments of the part are merged");
                }
            }
            for (int i = 0; i < n; i++) assertEquals(1, seen[i], "square " + i + " drawn once");
        }
    }
}
