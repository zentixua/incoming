package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.DustWallPass;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sight inside old dust belongs to the camera ({@link PallField#sightWeight}, {@code dustwall.glsl}: {@code DwSight}): from
 * outside the dust every ray reads the law through the transfer, so the blanket seen from a hill stays dark; a camera inside
 * sees about 200 m at one minute and 700 m by five, as before; walking out of the dust moves no far ray by a step.
 */
class DustSightTest {
    private static final int N = DustWallPass.NODES;

    private static Detonation burst(double kt) {
        return new Detonation(1, new Vec3(0, 64, 0), 64, kt, true, 0, 0, 0, 20_000, 7, 1f, false, 0.35f);
    }

    private record Wave(Detonation d, float[] table, double reach, double end) {
        static Wave of(double kt) {
            Detonation d = burst(kt);
            PallField f = new PallField(d);
            return new Wave(d, f.waveTableNow(), f.reach(), f.lifetimeEnd());
        }

        int node(double metres) {
            return (int) Math.round(d.blocks(metres) / reach * (N - 1));
        }

        double radius(int j) {
            return j / (double) (N - 1) * reach;
        }

        /** Model time when node j's dust is {@code age} s old. */
        double time(int j, double age) {
            return table[4 * j] + age;
        }

        /** Vertical drawn optical depth at node j, age s, for a camera with sight share {@code sight}. */
        double drawn(int j, double age, double sight) {
            double[] c = new double[2];
            PallField.drawnColumn(table, N, end, reach, time(j, age), radius(j), 0, 0, sight, c);
            return c[0];
        }

        double[] column(int j, double age) {
            double[] c = new double[2];
            PallField.drawnColumn(table, N, end, reach, time(j, age), radius(j), 0, 0, c);
            return c;
        }

        double weight(double r, double t, double h) {
            return PallField.sightWeight(table, N, end, reach, t, r, 0, h, 0);
        }
    }

    /**
     * Complaint (time.md §1.1): a minute after the front the blanket was drawn see-through from outside, 55 % of the light
     * straight down at 1.5 km from 15 kt. From outside the column now passes 9 % at one minute and 18 % at twenty (the law's
     * optical depth through the transfer over the drawn top), and seen slant (three times the vertical path) it stays opaque
     * through those twenty minutes.
     */
    @Test
    void fromOutsideTheBlanketFollowsTheLaw() {
        Wave w = Wave.of(15);
        int j = w.node(1564);
        double minute = Math.exp(-w.drawn(j, 60, 0)), twenty = Math.exp(-w.drawn(j, 1200, 0));
        assertTrue(minute > 0.07 && minute < 0.11, "straight down at one minute: " + minute);
        assertTrue(twenty > 0.16 && twenty < 0.20, "straight down at twenty minutes: " + twenty);
        for (double age = 60; age <= 1200; age += 30)
            assertTrue(Math.exp(-3 * w.drawn(j, age, 0)) < 0.01, "slant at " + age + " s: " + Math.exp(-3 * w.drawn(j, age, 0)));
        // the inside sight is what drew it see-through
        assertTrue(Math.exp(-w.drawn(j, 63, 1)) > 0.45, "the inside sight at one minute: " + Math.exp(-w.drawn(j, 63, 1)));
        // the outside reading is the table's law through the one transfer, at every age
        for (double age = 1; age < 3000; age *= 1.3)
            assertEquals(w.drawn(j, age, 0), w.column(j, age)[0], 1e-12, "outside is the plain reader at " + age + " s");
    }

    /** A camera under the layer's top, in an old enough column, takes the whole sight; above the top, before the front or beyond the dust, none. */
    @Test
    void theSightIsTheCamerasPlaceInTheDust() {
        Wave w = Wave.of(15);
        int j = w.node(1564);
        double r = w.radius(j), t = w.time(j, 120), top = w.column(j, 120)[1];
        assertTrue(w.weight(r, t, 2) > 0.99, "2 m over the floor at 2 min: " + w.weight(r, t, 2));
        assertTrue(w.weight(r, t, 0.5 * top) > 0.99, "half way up: " + w.weight(r, t, 0.5 * top));
        assertEquals(0, w.weight(r, t, 1.5 * top), "above the layer");
        assertEquals(0, w.weight(r, w.time(j, -1), 2), "before the front");
        assertEquals(0, w.weight(1.01 * w.reach, t, 2), "beyond the dust's reach");
    }

    /**
     * The view from inside keeps the presentation: about 200 m at one minute, 610–700 m by five (the eye's horizontal sight at
     * the column's mean extinction, to the sight's optical depth {@code SEE_TAU}), for 15 kt at 1.5 km and 1 Mt at 4.5 km.
     */
    @Test
    void insideTheSightIsAsBefore() {
        for (double[] at : new double[][] {{15, 1564}, {1000, 4500}}) {
            Wave w = Wave.of(at[0]);
            int j = w.node(at[1]);
            for (double[] want : new double[][] {{60, 200, 230}, {300, 600, 720}}) {
                double age = want[0], top = w.column(j, age)[1];
                double sigma = w.drawn(j, age, 1) / PallField.profileIntegral(top);
                double sight = w.d.metres(PallField.SEE_TAU / sigma);
                assertTrue(sight >= want[1] && sight <= want[2], at[0] + " kt at " + at[1] + " m, " + age + " s: sight " + sight + " m");
            }
        }
    }

    /**
     * Inside, the drawn column at the table's age nodes is the one main drew (the cap baked into the table: 1 Mt, measured at
     * nodes 18..29 of five radii), within 3 %. Between nodes the cap is now taken on the interpolated law, where the table
     * interpolated the capped column.
     */
    @Test
    void insideTheColumnIsMainsAtTheNodes() {
        double[][] main = {
                {3.1553, 1.3889, 1.7514, 2.6904, 3.5678, 2.4081, 1.2758, 0.2476, 0.0057, 0, 0, 0},
                {2.3173, 1.0087, 1.0720, 1.4165, 1.8749, 1.7080, 1.0637, 0.5939, 0.3773, 0.1458, 0.0089, 0.0002},
                {1.4029, 0.8588, 0.8056, 0.8982, 1.0540, 1.0040, 0.8460, 0.7111, 0.5915, 0.4893, 0.4136, 0.2296},
                {0.8801, 0.7474, 0.7527, 0.8544, 0.9750, 0.9533, 0.8724, 0.7908, 0.6320, 0.3615, 0.1575, 0.0471},
                {0.0574, 0.0612, 0.0737, 0.1046, 0.1472, 0.1520, 0.1268, 0.0968, 0.0654, 0.0370, 0.0162, 0.0048}};
        int[] nodes = {8, 16, 32, 64, 100};
        Wave w = Wave.of(1000);
        for (int i = 0; i < nodes.length; i++)
            for (int k = 18; k < 30; k++) {
                double age = PallField.lifetimeAge(k, w.end), now = w.drawn(nodes[i], age, 1), was = main[i][k - 18];
                assertEquals(was, now, 0.03 * was + 2e-4, "node " + nodes[i] + " at " + Math.round(age) + " s");
            }
    }

    /**
     * Walking up out of the dust, the drawn column of a fixed far point (3 km, ten minutes) goes from the inside sight's to the
     * law's without a step: no 5 cm rise moves it by more than 1 % of the whole change. Walking out of the dust's far edge
     * along the ground, the camera's share falls without a step too.
     */
    @Test
    void walkingOutOfTheDustMovesNoRayByAStep() {
        Wave w = Wave.of(15);
        int eye = w.node(1564), far = w.node(3000);
        double t = w.time(eye, 600), top = w.column(eye, 600)[1], r = w.radius(eye);
        double[] c = new double[2];
        java.util.function.DoubleUnaryOperator drawn = h -> {
            PallField.drawnColumn(w.table, N, w.end, w.reach, t, w.radius(far), 0, 0, w.weight(r, t, h), c);
            return c[0];
        };
        double inside = drawn.applyAsDouble(1), outside = drawn.applyAsDouble(2 * top), change = Math.abs(outside - inside);
        assertTrue(change > 0.5, "the sight changes the far column: " + inside + " → " + outside);
        double worst = 0, last = inside;
        for (double h = 1; h <= 2 * top; h += 0.05) {
            double now = drawn.applyAsDouble(h);
            worst = Math.max(worst, Math.abs(now - last));
            last = now;
        }
        assertTrue(worst < 0.01 * change, "largest step over 5 cm: " + worst + " of " + change);
        double step = 0, prev = w.weight(0.9 * w.reach, t, 2);
        for (double x = 0.9 * w.reach; x <= 1.02 * w.reach; x += 1) {
            double now = w.weight(x, t, 2);
            step = Math.max(step, Math.abs(now - prev));
            prev = now;
        }
        assertTrue(step < 0.01, "the share's largest step over 1 m along the ground: " + step);
        // as the front passes over a standing eye the share comes in over the head's first seconds
        double before = 0, worstT = 0;
        for (double age = -1; age <= 5; age += 0.05) {
            double now = w.weight(r, w.time(eye, age), 2);
            worstT = Math.max(worstT, Math.abs(now - before));
            before = now;
        }
        assertTrue(worstT < 0.06, "the share's largest step over a tick of the front's passage: " + worstT);
    }
}
