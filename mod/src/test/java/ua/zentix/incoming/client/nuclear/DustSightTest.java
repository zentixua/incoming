package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.DustWallPass;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sight inside old dust belongs to the camera ({@link PallField#sight}, {@code dustwall.glsl}: {@code DwSight}): from outside
 * the dust every ray reads the law as drawn ({@link PallField#drawnColumn}: the haze level the law's held column, the young textured level its
 * transfer), so the blanket seen from a hill or from above stays opaque while the law's column is thick; a camera inside sees what
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
     * straight down at 1.5 km from 15 kt. From outside the column is the law's to what the screen shows (the haze level,
     * {@link PallField#hazeDepth}): it passes 1 % of the light straight down at one minute (law τ 14.0) and 1.7 % at twenty (τ 5.2),
     * and seen slant (three times the vertical path) it stays opaque through those twenty minutes.
     */
    @Test
    void fromOutsideTheBlanketFollowsTheLaw() {
        Wave w = Wave.of(15);
        int j = w.node(1564);
        double minute = Math.exp(-w.drawn(j, 60, 0)), twenty = Math.exp(-w.drawn(j, 1200, 0));
        assertTrue(minute < 0.011, "straight down at one minute: " + minute);
        assertTrue(twenty > 0.012 && twenty < 0.022, "straight down at twenty minutes: " + twenty);
        for (double age = 60; age <= 1200; age += 30)
            assertTrue(Math.exp(-3 * w.drawn(j, age, 0)) < 0.01, "slant at " + age + " s: " + Math.exp(-3 * w.drawn(j, age, 0)));
        // the inside sight is what drew it see-through
        assertTrue(Math.exp(-w.drawn(j, 63, 1)) > 0.45, "the inside sight at one minute: " + Math.exp(-w.drawn(j, 63, 1)));
    }

    /**
     * From 400 m above the layer, 2 km from a 15 kt surface burst, 70° down, at 70 s and two minutes, the ground stays hidden
     * under the blanket (the transfer alone draws the law's τ 6.7–9 as about 1.9: 15 % of the light). Seen from outside, the haze level draws the law's own depth up to the depth the screen shows (the march's exit, ln 100):
     * the column is that once the textured level has gone (60 s), the law within 1 % up to τ 3.3, and the 70° ray passes under
     * 1 % of the light while the law's column is over 6; the textured young level keeps the transfer.
     */
    @Test
    void fromAboveTheHazeIsTheLaw() {
        Wave w = Wave.of(15);
        int j = w.node(2000);
        double down = Math.sin(Math.toRadians(70));
        for (double age : new double[] {60, 70, 90, 120, 300, 600, 1200}) {
            double[] c = w.column(j, age);
            assertEquals(PallField.hazeDepth(c[3]), c[0], 1e-9 * c[3], "the haze level's column at " + age + " s");
            if (c[3] > 6) assertTrue(Math.exp(-c[0] / down) < 0.01, "70° down at " + age + " s: " + Math.exp(-c[0] / down));
        }
        assertTrue(w.column(j, 70)[3] > 6 && w.column(j, 120)[3] > 6, "the law's column at 70 s and 2 min: " + w.column(j, 70)[3]);
        for (double tau = 0.01; tau <= 3.3; tau += 0.01)
            assertEquals(tau, PallField.hazeDepth(tau), 0.01 * tau, "the haze level's column is the law's at τ " + tau);
        for (double tau = 2; tau < 400; tau *= 1.1)
            assertTrue(PallField.hazeDepth(tau) <= PallField.HAZE_SEEN && PallField.hazeDepth(tau * 1.1) >= PallField.hazeDepth(tau),
                    "held under the march's exit depth, never falling, at τ " + tau);
        // the young textured level: the law through the transfer (the matte face), and between them by the textured share
        double[] young = w.column(j, 10);
        assertEquals(PallField.transfer(young[3]) * (1 + 0.5 * Math.exp(-10 / 1.1)), young[0], 1e-9 * young[0], "at 10 s");
        double[] mid = w.column(j, 42);
        double f = PallField.textured(42);
        double haze = PallField.hazeDepth(mid[3]);
        assertEquals(haze + f * (PallField.transfer(mid[3]) - haze), mid[0], 1e-9 * mid[0], "at 42 s");
    }

    /**
     * Over hard ground (roads, roofs, rock: the table's paved share of the soil's dust) the column seen from above is the hold
     * of the law's column there, as over soil: 15 kt at 1, 1.5 and 2 km, 70 s and two minutes, eye 400 m up, 70° down, the drawn
     * transmission within ×1.5 of the law's, or under 1.5 % where the law's is under 1 % (the hold's most straight down;
     * review probe {@code DustAboveHardGroundProbeTest}).
     */
    @Test
    void hardGroundFromAboveFollowsTheLaw() {
        Wave w = Wave.of(15);
        double down = Math.sin(Math.toRadians(70));
        for (double m : new double[] {1000, 1500, 2000}) {
            int j = w.node(m);
            for (double age : new double[] {70, 120}) {
                double[] soil = new double[6], hard = new double[6];
                PallField.drawnColumn(w.table, N, w.end, w.reach, w.time(j, age), w.radius(j), 0, 0, 0, soil);
                PallField.drawnColumn(w.table, N, w.end, w.reach, w.time(j, age), w.radius(j), 0, 1, 0, hard);
                double law = soil[3] * w.table[4 * j + 1], tLaw = Math.exp(-law / down), tDrawn = Math.exp(-hard[0] / down);
                assertEquals(PallField.hazeDepth(law), hard[0], 1e-6 * law, m + " m, " + age + " s: the hold of the law's column");
                assertTrue(tDrawn < 0.015 && tLaw < 0.01 || tDrawn < 1.5 * tLaw && tDrawn > tLaw / 1.5,
                        m + " m, " + age + " s: drawn T " + tDrawn + ", the law's " + tLaw);
            }
        }
    }

    /**
     * At a shore (the ground water by half, no spray) the world's readers take the column the march draws: seen from outside the
     * hold of the mixed column, not half the soil's hold (review 2: 2.30 against the shader's 4.37 at law τ 10); a camera inside,
     * the transfer of the soil's share of its sight-mixed column.
     */
    @Test
    void aShoreIsTheMixedColumn() {
        Wave w = Wave.of(15);
        int j = w.node(1000);
        for (double age : new double[] {70, 300}) {
            double[] soil = new double[6], shore = new double[6], inside = new double[6], insideSoil = new double[6];
            PallField.drawnColumn(w.table, N, w.end, w.reach, w.time(j, age), w.radius(j), 0, 0, 0, soil);
            PallField.drawnColumn(w.table, N, w.end, w.reach, w.time(j, age), w.radius(j), 0, 0, 0, 0.5, shore);
            assertEquals(PallField.hazeDepth(0.5 * soil[3]), shore[0], 1e-6 * soil[3], age + " s, outside");
            PallField.drawnColumn(w.table, N, w.end, w.reach, w.time(j, age), w.radius(j), 0, 0, 1, 0.5, inside);
            PallField.drawnColumn(w.table, N, w.end, w.reach, w.time(j, age), w.radius(j), 0, 0, 1, insideSoil);
            assertTrue(inside[0] <= insideSoil[0] + 1e-9, age + " s, inside: the shore " + inside[0] + ", land " + insideSoil[0]);
        }
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
