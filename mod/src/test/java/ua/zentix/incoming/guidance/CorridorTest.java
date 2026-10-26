package ua.zentix.incoming.guidance;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.util.Relief;
import java.util.function.IntBinaryOperator;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Terrain lane over the path ({@link Corridor}): no column in the lane is skipped; climb is accounted for before distant columns. */
class CorridorTest {
    private static final double HALF_WIDTH = 1.5;

    /**
     * For random polylines, query every column whose center is within half-width of a segment (across it:
     * the lane has no rounded ends; the projectile does not need columns behind the path start).
     */
    @Test
    void asksEveryColumnInBand() {
        SplittableRandom r = new SplittableRandom(7);
        for (int run = 0; run < 500; run++) {
            int points = 1 + r.nextInt(5);
            double[] track = new double[2 * points];
            double x = r.nextDouble(-50, 50), z = r.nextDouble(-50, 50);
            for (int i = 0; i < points; i++) {
                track[2 * i] = x;
                track[2 * i + 1] = z;
                x += r.nextDouble(-30, 30);
                z += r.nextDouble(-30, 30);
            }
            Set<Long> asked = new HashSet<>();
            Corridor.highest(track, HALF_WIDTH, Corridor.Climb.NONE, (cx, cz) -> {
                asked.add(key(cx, cz));
                return 0;
            });
            for (int cx = -120; cx <= 120; cx++) {
                for (int cz = -120; cz <= 120; cz++) {
                    if (distance(track, cx + 0.5, cz + 0.5) <= HALF_WIDTH) {
                        assertTrue(asked.contains(key(cx, cz)), "column " + cx + " " + cz + " was not queried, path " + java.util.Arrays.toString(track));
                    }
                }
            }
        }
    }

    /** A narrow column in the lane contributes its own height, not its neighbors' heights. */
    @Test
    void thinColumnCounts() {
        double[] track = {0.3, 0, 0.3, 256};
        for (int at = 0; at < 256; at += 7) {
            int mz = at;
            double h = Corridor.highest(track, HALF_WIDTH, Corridor.Climb.NONE, (x, z) -> x == 1 && z == mz ? 104 : 64);
            assertEquals(104, h, "column at " + at);
        }
    }

    /** A column beyond the reaction distance contributes its height minus the climb before it; a nearby column contributes its full height. */
    @Test
    void farColumnLessClimb() {
        double[] track = {0.5, 0, 0.5, 200};
        Corridor.Climb climb = new Corridor.Climb(10, 0.1);
        double far = Corridor.highest(track, HALF_WIDTH, climb, (x, z) -> x == 0 && z == 100 ? 150 : 0);
        assertEquals(150 - 0.1 * (100.5 - 10), far, 1e-9);
        double near = Corridor.highest(track, HALF_WIDTH, climb, (x, z) -> x == 0 && z == 5 ? 150 : 0);
        assertEquals(150, near, 1e-9);
        // distance follows the polyline: on the second segment, it is measured from the start of the first
        double[] bent = {0.5, 0, 0.5, 50, 50.5, 50};
        double corner = Corridor.highest(bent, HALF_WIDTH, climb, (x, z) -> x == 30 && z == 49 ? 150 : 0);
        assertEquals(150 - 0.1 * (50 + 30 - 10), corner, 1e-9);
    }

    /** A single point gives a square around it. */
    @Test
    void pointIsSquare() {
        double h = Corridor.highest(new double[] {10.5, 10.5}, HALF_WIDTH, Corridor.Climb.NONE, (x, z) -> x == 10 && z == 11 ? 90 : 64);
        assertEquals(90, h);
    }

    /** Preserve every geometric visit: a bend or revisited segment gives the same column a different climb gain. */
    @Test
    void surveyReusesOnlyHeightsAndChunkViews() {
        double[][] tracks = {
                {0.3, 0, 0.3, 256},                         // thin pillar
                {-32.5, -32.5, 64.5, 64.5},                 // diagonal and negative chunks
                {0.5, 0, 0.5, 50, 50.5, 50},              // turn
                {0.5, 0, 0.5, 50, 50.5, 50, 0.5, 0},      // multiple segments and a revisited column
                {0.5, 0, 0.5, 50, 0.5, 0},                // return along the same band
                {-16, -16}, {0, 0, 0, 0, 16, 16}          // point and zero-length segment
        };
        IntBinaryOperator heights = (x, z) -> x == 1 && z == 100 ? 300 : 64 + Math.floorMod(x * 31 + z * 7, 90);
        for (double[] track : tracks) {
            for (Corridor.Climb climb : new Corridor.Climb[]{Corridor.Climb.NONE, new Corridor.Climb(10, 0.3)}) {
                Set<Long> columns = new HashSet<>(), chunks = new HashSet<>();
                int[] visits = {0}, reads = {0}, views = {0};
                double old = Corridor.highest(track, HALF_WIDTH, climb, (x, z) -> {
                    visits[0]++;
                    columns.add(key(x, z));
                    chunks.add(key(x >> 4, z >> 4));
                    return heights.applyAsInt(x, z);
                });
                Relief.Survey survey = new Relief.Survey((cx, cz) -> {
                    views[0]++;
                    return (x, z) -> {
                        assertEquals(cx, x >> 4);
                        assertEquals(cz, z >> 4);
                        reads[0]++;
                        return heights.applyAsInt(x, z);
                    };
                });
                int[] sampledVisits = {0};
                double batched = Corridor.highest(track, HALF_WIDTH, climb, (x, z) -> {
                    sampledVisits[0]++;
                    return survey.at(x, z);
                });
                assertEquals(old, batched, 0, java.util.Arrays.toString(track));
                assertEquals(visits[0], sampledVisits[0], "geometric visits");
                assertEquals(chunks.size(), views[0], "one view per unique chunk");
                assertEquals(columns.size(), reads[0], "one height read per unique column");
                assertTrue(reads[0] < visits[0], "the band must revisit columns");
            }
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Cross-track distance from a point to the nearest polyline segment onto which it projects; a single point uses a square. */
    private static double distance(double[] track, double px, double pz) {
        if (track.length == 2) return Math.max(Math.abs(px - track[0]), Math.abs(pz - track[1]));
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 3 < track.length; i += 2) {
            double ax = track[i], az = track[i + 1], dx = track[i + 2] - ax, dz = track[i + 3] - az;
            double t = ((px - ax) * dx + (pz - az) * dz) / Math.max(1e-12, dx * dx + dz * dz);
            if (t >= 0 && t <= 1) best = Math.min(best, Math.hypot(px - ax - t * dx, pz - az - t * dz));
        }
        return best;
    }
}
