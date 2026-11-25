package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A source's puff merge through ground cells ({@code SmokePuffs.mergeIndexed}) is the pairwise merge ({@code mergeScan})
 * exactly, at a fraction of its pair tests. Breach dust as a 1 Mt wave crossed a city viewer (256 streaks of up to 12
 * billows, one source) merged pairwise on the render thread for 85–265 ms a second (passage-city run, 25.11).
 */
class SmokeMergeIndexTest {
    /** Breach dust streaks emitted through the real sink: segments of 12 billows downwind of scattered breaches. */
    static final class Streaks implements SmokeGrid.Source {
        final double[][] s;

        Streaks(long seed, int count) {
            SplittableRandom r = new SplittableRandom(seed);
            s = new double[count][];
            for (int i = 0; i < count; i++) {
                // breaches over a city block field around the viewer, wind mostly along +x, streaks 2–60 m long
                double x = r.nextDouble(-160, 160), z = r.nextDouble(-160, 160), y = 64 + r.nextDouble(0, 14);
                double len = r.nextDouble(4, 40), dir = r.nextDouble(-0.4, 0.4);
                s[i] = new double[]{x, y, z, len * Math.cos(dir), r.nextDouble(0, 2), len * Math.sin(dir), r.nextDouble(0.5, 9), r.nextDouble(0.05, 4)};
            }
        }

        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            for (double[] p : s)
                out.segment(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[6], 0, p[7], 0, 0.62f, 0.55f, 0.47f, CloudModel.T_AIR_K, 0);
            return true;
        }

        @Override
        public long seed() {
            return 0xD057L;
        }
    }

    /** One source's held puffs as {@code Draw} holds them before its merge. */
    private static double[] held(SmokeGrid.Source source) throws ReflectiveOperationException {
        SmokeConcealTest.Plan p = new SmokeConcealTest.Plan();
        SmokePuffs.reset();
        try {
            FarView view = SmokeRaidTest.view(1);
            SmokePuffs.sink(view, new SmokeFillTest.Screen(view), source, 1000);
            int from = p.i("from");
            source.emit(1000, (SmokeGrid.Sink) p.draw);
            int holding = p.i("holding");
            return Arrays.copyOfRange((double[]) p.get("held"), from * SmokePuffs.FIELDS, holding * SmokePuffs.FIELDS);
        } finally {
            SmokePuffs.reset();
        }
    }

    /** Both paths on copies: identical puffs and absorbed flags; returns {scan tests, indexed tests}. */
    private static long[] same(double[] puffs, double reach, String what) {
        int n = puffs.length / SmokePuffs.FIELDS;
        double[] a = puffs.clone(), b = puffs.clone();
        boolean[] ga = new boolean[n], gb = new boolean[n];
        long scan = SmokePuffs.mergeScan(a, 0, n, ga, CloudModel.SOOT_K, SmokeGrid.DROP_K, reach);
        long indexed = SmokePuffs.mergeIndexed(b, 0, n, gb, CloudModel.SOOT_K, SmokeGrid.DROP_K, reach);
        assertArrayEquals(ga, gb, what + ": absorbed puffs differ");
        assertArrayEquals(a, b, what + ": merged puffs differ");
        return new long[]{scan, indexed};
    }

    @Test
    void breachDustMergesTheSameAtAFractionOfTheTests() throws ReflectiveOperationException {
        double[] puffs = held(new Streaks(7, 256));
        int n = puffs.length / SmokePuffs.FIELDS;
        assertTrue(n >= 2000, "the fixture is the passage's size: " + n + " billows");
        long[] t = same(puffs, SmokePuffs.MERGE, "256 streaks");
        int absorbed = 0;
        double[] b = puffs.clone();
        boolean[] g = new boolean[n];
        SmokePuffs.mergeIndexed(b, 0, n, g, CloudModel.SOOT_K, SmokeGrid.DROP_K, SmokePuffs.MERGE);
        for (boolean x : g) if (x) absorbed++;
        assertTrue(absorbed > n / 10, "the fixture must actually merge: " + absorbed + " of " + n);
        assertTrue(t[1] * 20 < t[0], "indexed pair tests " + t[1] + " against the scan's " + t[0]);
        System.out.println("SmokeMergeIndexTest: " + n + " billows, " + absorbed + " absorbed, pair tests scan " + t[0] + ", indexed " + t[1]);
    }

    /** Random puffs: shared and different colours, looks and segments, wide and tiny σ, and values the rule cannot bound. */
    @Test
    void randomPuffsMergeTheSame() {
        SplittableRandom r = new SplittableRandom(20261125);
        for (int round = 0; round < 300; round++) {
            int n = SmokePuffs.INDEX_FROM + r.nextInt(400);
            double spread = r.nextBoolean() ? 20 : 400;
            double[] h = new double[n * SmokePuffs.FIELDS];
            for (int i = 0; i < n; i++) {
                int o = i * SmokePuffs.FIELDS;
                h[o] = 3000 + r.nextDouble(-spread, spread);
                h[o + 1] = 70 + r.nextDouble(-spread / 4, spread / 4);
                h[o + 2] = -2000 + r.nextDouble(-spread, spread);
                // σ over three decades, a few wider than the whole field
                h[o + 3] = Math.pow(10, r.nextDouble(-1, 1.6)) * (r.nextInt(40) == 0 ? 50 : 1);
                h[o + 4] = Math.pow(10, r.nextDouble(-1, 1.4));
                h[o + 5] = r.nextDouble(0, 2);
                h[o + 6] = r.nextDouble(0, 2);
                h[o + 7] = r.nextDouble(0, 0.5);
                int colour = r.nextInt(3);
                h[o + 8] = 0.5 + 0.1 * colour;
                h[o + 9] = 0.45;
                h[o + 10] = 0.4 - 0.05 * colour;
                h[o + 11] = r.nextInt(6) * SmokePuffs.SEGMENT_CLUBS + r.nextInt(SmokePuffs.SEGMENT_CLUBS);
                h[o + 12] = 0;
                h[o + 13] = r.nextDouble();
                h[o + 14] = r.nextInt(3) == 0 ? 1 : 0;
                h[o + 15] = r.nextInt(5) == 0 ? 1 : 0;
                h[o + 16] = r.nextDouble();
                h[o + 17] = r.nextDouble();
                switch (r.nextInt(60)) {
                    case 0 -> h[o + 3] = Double.NaN;
                    case 1 -> h[o + 3] = -r.nextDouble(0, 30);
                    case 2 -> h[o] = Double.NaN;
                    case 3 -> h[o + 4] = Double.POSITIVE_INFINITY;
                    case 4 -> h[o + 2] = Double.NEGATIVE_INFINITY;
                    default -> { }
                }
            }
            double reach = r.nextInt(4) == 0 ? r.nextDouble(0.2, 3) : SmokePuffs.MERGE;
            same(h, reach, "round " + round);
        }
    }
}
