package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.DustWallPass;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sight inside old dust belongs to the camera ({@link PallField#sight}, {@code dustwall.glsl}: {@code DwSight}): from outside
 * the dust every ray reads the law through the transfer, so the blanket seen from a hill stays dark; a camera inside sees what
 * main drew, at every age (about 200 m at one minute, 700 m by five); walking out of the dust moves no far ray by a step.
 */
class DustSightTest {
    private static final int N = DustWallPass.NODES;

    private static Detonation burst(double kt) {
        return new Detonation(1, new Vec3(0, 64, 0), 64, kt, true, 0, 0, 0, 20_000, 7, 1f, false, 0.35f);
    }

    private record Wave(Detonation d, PallField f, float[] table, double reach, double end) {
        static Wave of(double kt) {
            Detonation d = burst(kt);
            PallField f = new PallField(d);
            return new Wave(d, f, f.waveTableNow(), f.reach(), f.lifetimeEnd());
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
            double[] c = new double[6];
            PallField.drawnColumn(table, N, end, reach, time(j, age), radius(j), 0, 0, 0, c);
            return c;
        }

        /** The camera's share at (r, 0) from ground zero, model time t, h blocks over the dust's floor. */
        double share(double r, double t, double h) {
            return f.sight(r, 0, h, t * 20 * d.scale());
        }
    }

    /**
     * Complaint (time.md §1.1): a minute after the front the blanket was drawn see-through from outside, 55 % of the light
     * straight down at 1.5 km from 15 kt. From outside the column passes 9 % at one minute and 18 % at twenty (the law's
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
    }

    /**
     * Inside, the drawn column is main's at every age through the haze level's fade window and after it, at five distances
     * and two yields, within 3 %: main's drawn vertical column for a camera inside (the cap baked into its table, read by its
     * {@code drawnColumn}), measured on origin/main 30f530d6d (review probe {@code InsideWindowProbeTest}).
     */
    @Test
    void insideTheColumnIsMainsAtEveryAge() {
        double[] ages = {10, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 66, 75, 90, 120, 180, 300, 600};
        double[][] at = {{15, 300}, {15, 782}, {15, 1564}, {15, 3000}, {1000, 4500}};
        double[][] main = {
                {21.0938, 12.0331, 10.4569, 9.1526, 7.6143, 6.0187, 4.5912, 3.4780, 2.9799, 2.5216, 2.0973, 1.7016, 0.9215, 0.9567,
                        1.0067, 1.3622, 1.9394, 1.7350, 0.2802},
                {6.0409, 4.2765, 3.9196, 3.6216, 3.1413, 2.5879, 2.0928, 1.7322, 1.6558, 1.5750, 1.4431, 1.0481, 0.5472, 0.5459,
                        0.5441, 0.6379, 0.8524, 0.8564, 0.4397},
                {3.1299, 2.8830, 2.7822, 2.6950, 2.4336, 2.0944, 1.7909, 1.5501, 1.4801, 1.3682, 1.1687, 0.8299, 0.5829, 0.5441,
                        0.4888, 0.4442, 0.4202, 0.3770, 0.2846},
                {1.6528, 1.6416, 1.6302, 1.6197, 1.5779, 1.5007, 1.3689, 1.1635, 1.0603, 0.9438, 0.8326, 0.7291, 0.6452, 0.5976,
                        0.5297, 0.4491, 0.3654, 0.2950, 0.2365},
                {5.4073, 4.6370, 4.4168, 4.2056, 3.9563, 3.3014, 2.7155, 2.1854, 1.8806, 1.7804, 1.6868, 1.5909, 1.2050, 0.9160,
                        0.9075, 0.9998, 1.2528, 1.2784, 0.8588}};
        Wave[] waves = {Wave.of(15), Wave.of(1000)};
        for (int i = 0; i < at.length; i++) {
            Wave w = waves[at[i][0] < 100 ? 0 : 1];
            int j = w.node(at[i][1]);
            for (int k = 0; k < ages.length; k++) {
                double now = w.drawn(j, ages[k], 1), was = main[i][k];
                assertEquals(was, now, 0.03 * was, at[i][0] + " kt at " + at[i][1] + " m, " + ages[k] + " s inside");
            }
        }
    }

    /** The inside sight's rows hold each node once: the interval k's upper node is interval k + 1's lower one. */
    @Test
    void insideRowsJoinAtTheNodes() {
        Wave w = Wave.of(15);
        for (int j = 0; j < N; j += 9)
            for (int k = 0; k < PallField.AGES - 2; k++) {
                int a = 4 * ((PallField.SIGHT_ROW + k) * N + j), b = 4 * ((PallField.SIGHT_ROW + k + 1) * N + j);
                assertEquals(w.table[a + 2], w.table[b], 0, "node " + (k + 1) + " value at " + j);
                assertEquals(w.table[a + 3], w.table[b + 1], 0, "node " + (k + 1) + " slope at " + j);
            }
        assertEquals(PallField.TABLE_ROWS * N * 4, w.table.length, "the table holds the inside sight's rows");
    }

    /** A camera under the layer's top, in an old enough column, takes the whole sight; above the top, before the front or beyond the dust, none. */
    @Test
    void theSightIsTheCamerasPlaceInTheDust() {
        Wave w = Wave.of(15);
        int j = w.node(1564);
        double r = w.radius(j), t = w.time(j, 120), top = w.column(j, 120)[1];
        assertTrue(w.share(r, t, 2) > 0.99, "2 m over the floor at 2 min: " + w.share(r, t, 2));
        assertTrue(w.share(r, t, 0.5 * top) > 0.99, "half way up: " + w.share(r, t, 0.5 * top));
        assertEquals(0, w.share(r, t, 6 * top), "far above the layer");
        assertEquals(0, w.share(r, w.time(j, -1), 2), "before the front");
        assertEquals(0, w.share(1.01 * w.reach, t, 2), "beyond the dust's reach");
    }

    /**
     * The share follows the haze level's own top ({@code dwHaze}: the envelope × (1 + swell + plume)), not the noise-free
     * one: an eye just above where the noise-free top's soft edge ends is inside a swell or plume standing a fifth higher and
     * keeps the sight; at the same height over a sag it is above the dust and reads the law.
     */
    @Test
    void anEyeInASwellKeepsTheSight() {
        Wave w = Wave.of(15);
        double worst = 1, best = 0;
        double[] c = new double[6];
        int swells = 0, sags = 0;
        for (double x = 1000; x < 2000; x += 10)
            for (double z = -500; z < 500; z += 10) {
                double r = Math.hypot(x, z);
                double t = w.time((int) Math.round(r / w.reach * (N - 1)), 300);
                PallField.drawnColumn(w.table, N, w.end, w.reach, t, x, z, 0, 0, c);
                double plain = c[1], swollen = w.f.hazeTop(x, z, c[2], plain, c[5], 0, false);
                double h = plain + 0.4 * (0.22 * plain + 4) + 1;
                if (swollen > 1.2 * plain) {
                    swells++;
                    worst = Math.min(worst, w.f.sight(x, z, h, t * 20));
                } else if (swollen < 0.9 * plain) {
                    sags++;
                    best = Math.max(best, w.f.sight(x, z, h, t * 20));
                }
            }
        assertTrue(swells > 10 && sags > 10, "swells " + swells + ", sags " + sags);
        assertTrue(worst > 0.25, "an eye over the noise-free top's soft edge inside a swell: share " + worst);
        assertEquals(0, best, "the same height over a sag: share " + best);
    }

    /**
     * The view from inside keeps the presentation: 125–230 m at one minute (the haze level's fade still ending, as main drew it),
     * 610–700 m by five (the eye's horizontal sight at
     * the column's mean extinction, to the sight's optical depth {@code SEE_TAU}), for 15 kt at 1.5 km and 1 Mt at 4.5 km.
     */
    @Test
    void insideTheSightIsAsBefore() {
        for (double[] at : new double[][] {{15, 1564}, {1000, 4500}}) {
            Wave w = Wave.of(at[0]);
            int j = w.node(at[1]);
            for (double[] want : new double[][] {{60, 120, 230}, {300, 600, 720}}) {
                double age = want[0], top = w.column(j, age)[1];
                double sigma = w.drawn(j, age, 1) / PallField.profileIntegral(top);
                double sight = w.d.metres(PallField.SEE_TAU / sigma);
                assertTrue(sight >= want[1] && sight <= want[2], at[0] + " kt at " + at[1] + " m, " + age + " s: sight " + sight + " m");
            }
        }
    }

    /**
     * Walking up out of the dust, the drawn column of a fixed far point (3 km, ten minutes) goes from the inside sight's to the
     * law's without a step: no 5 cm rise moves it by more than 1 % of the whole change. Walking out of the dust's far edge
     * along the ground and the front passing over a standing eye move the camera's share without a step too.
     */
    @Test
    void walkingOutOfTheDustMovesNoRayByAStep() {
        Wave w = Wave.of(15);
        int eye = w.node(1564), far = w.node(3000);
        double t = w.time(eye, 600), top = w.column(eye, 600)[1], r = w.radius(eye);
        double[] c = new double[2];
        java.util.function.DoubleUnaryOperator drawn = h -> {
            PallField.drawnColumn(w.table, N, w.end, w.reach, t, w.radius(far), 0, 0, w.share(r, t, h), c);
            return c[0];
        };
        double inside = drawn.applyAsDouble(1), outside = drawn.applyAsDouble(6 * top), change = Math.abs(outside - inside);
        assertTrue(change > 0.5, "the sight changes the far column: " + inside + " → " + outside);
        double worst = 0, last = inside;
        for (double h = 1; h <= 6 * top; h += 0.05) {
            double now = drawn.applyAsDouble(h);
            worst = Math.max(worst, Math.abs(now - last));
            last = now;
        }
        assertTrue(worst < 0.01 * change, "largest step over 5 cm: " + worst + " of " + change);
        double step = 0, prev = w.share(0.9 * w.reach, t, 2);
        for (double x = 0.9 * w.reach; x <= 1.02 * w.reach; x += 1) {
            double now = w.share(x, t, 2);
            step = Math.max(step, Math.abs(now - prev));
            prev = now;
        }
        assertTrue(step < 0.01, "the share's largest step over 1 m along the ground: " + step);
        // as the front passes over a standing eye the share comes in over the head's first seconds
        double before = 0, worstT = 0;
        for (double age = -1; age <= 5; age += 0.05) {
            double now = w.share(r, w.time(eye, age), 2);
            worstT = Math.max(worstT, Math.abs(now - before));
            before = now;
        }
        assertTrue(worstT < 0.06, "the share's largest step over a tick of the front's passage: " + worstT);
    }
}
