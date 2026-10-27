package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A source's puffs merge only when they differ from its previous merge ({@code Draw.close}; LEAN 27.10: merging every
 * snapshot again took 0.5–0.7 ms of the raid frame). The reused result must be the merge of the current puffs.
 */
class SmokeMergeCacheTest {
    /** Overlapping blobs of one look: merging changes them. */
    static final class Cloud implements SmokeGrid.Source {
        final List<double[]> puffs = new ArrayList<>();
        final long seed;

        Cloud(long seed, double x, int count) {
            this.seed = seed;
            for (int i = 0; i < count; i++) puffs.add(new double[]{x + 0.3 * i, 20 + 0.2 * i, 100 + 0.1 * i, 4 + 0.1 * i, 3});
        }

        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            for (double[] p : puffs) out.blob(p[0], p[1], p[2], p[3], p[4], 0.5, 0.2, 0, 0.6f, 0.5f, 0.4f, CloudModel.T_AIR_K, 0);
            return true;
        }

        @Override
        public long seed() {
            return seed;
        }
    }

    /** One frame: every source's merged puffs, checked against merging its current puffs afresh. */
    private static void frame(SmokeConcealTest.Plan p, FarView view, List<Cloud> sources) throws ReflectiveOperationException {
        SmokeFillTest.Screen screen = new SmokeFillTest.Screen(view);
        for (Cloud c : sources) {
            SmokePuffs.sink(view, screen, c, 1000);
            int from = p.i("from");
            c.emit(1000, (SmokeGrid.Sink) p.draw);
            int holding = p.i("holding"), n = holding - from;
            double[] fresh = Arrays.copyOfRange((double[]) p.get("held"), from * SmokePuffs.FIELDS, holding * SmokePuffs.FIELDS);
            boolean[] gone = new boolean[n];
            SmokePuffs.merge(fresh, 0, n, gone, CloudModel.SOOT_K, SmokeGrid.DROP_K);
            p.call("close");
            double[] got = Arrays.copyOfRange((double[]) p.get("held"), from * SmokePuffs.FIELDS, holding * SmokePuffs.FIELDS);
            boolean[] merged = Arrays.copyOfRange((boolean[]) p.get("merged"), from, holding);
            assertArrayEquals(fresh, got, "merged puffs differ from merging the current puffs");
            assertArrayEquals(gone, merged);
            assertTrue(n > 1 && !Arrays.equals(gone, new boolean[n]), "the fixture must actually merge");
        }
        SmokePuffs.flush();
    }

    @Test
    void reusedMergeIsTheMergeOfTheCurrentPuffs() throws ReflectiveOperationException {
        SmokeConcealTest.Plan p = new SmokeConcealTest.Plan();
        FarView view = SmokeRaidTest.view(1);
        Map<?, ?> merges = (Map<?, ?>) p.get("merges");
        SmokePuffs.reset();
        try {
            Cloud a = new Cloud(1, 0, 6), b = new Cloud(2, 30, 5);
            frame(p, view, List.of(a, b));
            frame(p, view, List.of(a, b));
            assertEquals(2, SmokePuffs.lastReused(), "unchanged puffs take the previous merge");
            // another order: the frame's source numbers change, the merge input does not
            frame(p, view, List.of(b, a));
            assertEquals(2, SmokePuffs.lastReused(), "a new source order is not a new merge");
            // a new puff
            a.puffs.add(new double[]{1.7, 21, 100.5, 4.5, 3});
            frame(p, view, List.of(b, a));
            assertEquals(1, SmokePuffs.lastReused(), "a new puff merges again");
            // a refreshed snapshot: the cloud moved
            for (double[] q : b.puffs) q[0] += 0.5;
            frame(p, view, List.of(b, a));
            assertEquals(1, SmokePuffs.lastReused(), "moved puffs merge again");
            // an ended source leaves; another source with the same puffs is merged for itself
            frame(p, view, List.of(a));
            assertEquals(1, merges.size(), "the merge of a source not drawn is not kept");
            Cloud twin = new Cloud(1, 0, 6);
            twin.puffs.clear();
            twin.puffs.addAll(a.puffs);
            frame(p, view, List.of(twin, a));
            assertEquals(1, SmokePuffs.lastReused(), "a different source does not take another's merge");
        } finally {
            SmokePuffs.reset();
        }
    }

    /** The 100-strike raid drawn with merges reused gives the same quads as merging every source every frame. */
    @Test
    void raidFrameIsTheSameWithReusedMerges() throws ReflectiveOperationException {
        SmokeConcealTest.Plan p = new SmokeConcealTest.Plan();
        Map<?, ?> merges = (Map<?, ?>) p.get("merges");
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        for (int camera = 1; camera < 3; camera++) {
            FarView view = SmokeRaidTest.view(camera);
            double[][] reused = draw(raid, now, view, null), fresh = draw(raid, now, view, merges);
            assertEquals(fresh.length, reused.length);
            for (int k = 0; k < fresh.length; k++) assertArrayEquals(fresh[k], reused[k], "quad " + k);
        }
        SmokePuffs.reset();
    }

    /** Twelve frames in the budget; forget — merges dropped before every frame. Returns the last frame's quads. */
    private static double[][] draw(List<SmokeGrid.Source> raid, double now, FarView view, Map<?, ?> forget) {
        SmokePuffs.reset();
        FarFlightView.reset();
        SmokeFillTest.Screen s = new SmokeFillTest.Screen(view);
        int reused = 0;
        for (int frame = 0; frame < 12; frame++) {
            if (forget != null) forget.clear();
            s.clear();
            FarFlightView.budget();
            for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(view, s, b, now));
            SmokePuffs.flush();
            reused = Math.max(reused, SmokePuffs.lastReused());
        }
        assertTrue(forget != null ? reused == 0 : reused > 50, "reused merges: " + reused);
        return s.quads.toArray(new double[0][]);
    }
}
