package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The eye tiles under the real survey bound ({@link DustGround#SURVEY_NANOS}) with a column cost (review step 8's probes):
 * <ol>
 * <li>the coarse tile holds an unknown cell's height only once the survey has gone round the whole tile: held at the first,
 * time-bounded fill, every cell past the drawn chunks kept the ground next to the eye (15–31 m under the edge in a valley);</li>
 * <li>a jump of the eye (teleport, respawn, a new detonation after walking away) restarts the sweep at the eye: resumed
 * mid-sweep, the ground under the eye stayed unknown for 15–100 s.</li>
 * </ol>
 */
class DustGroundFirstFillProbeTest {
    private static double valley(double x, double z) {
        return 70 + 40 * (1 - Math.exp(-Math.pow(x / 150, 2)));
    }

    /** A column's cost (DustGround.SURVEY_NANOS's comment: 1.4-8 us a column, forests the dearest). */
    private static void spin(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() - until < 0) Thread.onSpinWait();
    }

    private static DustGround.Tile tile(String name) throws ReflectiveOperationException {
        var f = DustGround.class.getDeclaredField(name);
        f.setAccessible(true);
        return (DustGround.Tile) f.get(null);
    }

    /** A warm JIT, then both sweeps at the given position (0: the eye's cell first, as a fresh session). */
    private static void prepare(int next) throws ReflectiveOperationException {
        for (int i = 0; i < 300; i++) DustGround.update((x, z) -> 70.0, 0, 72.62, 0, 12, false, DustGround.SURVEY_NANOS);
        DustGround.reset();
        tile("FINE").next = next;
        tile("COARSE").next = next;
    }

    private static ua.zentix.incoming.client.far.Sightline.Heights drawn(double ex, long columnNanos) {
        // the client knows the drawn chunks (192 m, 12 chunks) around the eye
        return (x, z) -> {
            spin(columnNanos);
            return Math.hypot(x - ex, z) <= 192 ? Math.floor(valley(x, z)) : Double.NaN;
        };
    }

    @AfterEach
    void clear() {
        DustGround.reset();
    }

    @Test
    void farFloorContinuesTheKnownEdgeAfterABoundedFirstSweep() throws ReflectiveOperationException {
        prepare(0);
        var heights = drawn(0, 1_400);
        double eyeY = Math.floor(valley(0, 0)) + 2.62;
        // 600 ticks (30 s): the coarse tile's sweep covers the whole tile
        for (int i = 0; i < 600; i++) DustGround.update(heights, 0, eyeY, 0, 12, i == 599, DustGround.SURVEY_NANOS);
        String line = DustGround.describe();
        double past = DustGround.floor(230, 0, 70), far = DustGround.floor(600, 0, 70);
        System.out.printf(java.util.Locale.ROOT, "%s%nfloor at 180 m %.1f, 230 m %.1f, 600 m %.1f; ground at 192 m %.1f%n", line,
                DustGround.floor(180, 0, 70), past, far, Math.floor(valley(192, 0)));
        // past the known edge the floor continues the edge (within the relief of one coarse cell), not the ground near the eye
        assertEquals(Math.floor(valley(192, 0)), past, 3.0, "the floor just past the drawn chunks' edge");
        assertEquals(Math.floor(valley(192, 0)), far, 3.0, "the floor 600 m out on the same bearing");
    }

    @Test
    void afterATeleportTheEyesGroundIsKnownWithinSeconds() throws ReflectiveOperationException {
        // the session's tiles were on 2 km away (a previous burst, or the player before dying in this one): the sweep stood on
        // the ring of ORDER index 12 000 (about 1.6 km out on the coarse tile)
        prepare(12_000);
        DustGround.update((x, z) -> 70.0, -2000, 72.62, 0, 12, true, DustGround.SURVEY_NANOS);
        tile("FINE").next = 12_000;
        tile("COARSE").next = 12_000;
        // the eye arrives at the valley's slope (x = 120: ground 91), 2 km away: a far re-centre
        double ex = 120, eyeY = Math.floor(valley(ex, 0)) + 1.62;
        var heights = drawn(ex, 4_000);
        int ticks = 0;
        for (; ticks < 2400; ticks++) {
            DustGround.update(heights, ex, eyeY, 0, 12, false, DustGround.SURVEY_NANOS);
            if (Math.abs(DustGround.floor(ex, 0, 0) - Math.floor(valley(ex, 0))) < 0.5) break;
        }
        System.out.printf(java.util.Locale.ROOT, "the floor under the eye found after %d ticks (%.1f s); %s%n", ticks, ticks / 20.0,
                DustGround.describe());
        // the rule's promise: the fine tile knows the drawn chunks within 2.5-8 s, the cells nearest the eye first
        assertTrue(ticks <= 20, "ticks until the floor under the eye is the ground: " + ticks);
    }
}
