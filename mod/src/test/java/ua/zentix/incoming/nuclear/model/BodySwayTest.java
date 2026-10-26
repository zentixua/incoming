package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodySwayTest {
    /** Velocity pressure behind the pressure front {@code psi}, Pa. */
    private static double q(double psi) {
        return 1000 * BlastModel.dynamicPressureKpa(BlastModel.kpa(psi));
    }

    /** Tilt with your back to the wave: with the wind - forward, back - towards the explosion. */
    private static double[] tilt(double psi, double tPlus) {
        return BodySway.tilt(1000 * BlastModel.kpa(psi), q(psi), 1, tPlus, BodySway.FORWARD, BodySway.BACK);
    }

    private static double peakDeg(double psi, double tPlus) {
        return Math.toDegrees(BodySway.peak(tilt(psi, tPlus), 0));
    }

    /** The positive phase on the ground, where the pressure is {@code psi}, at the air blast {@code kt} at the best altitude, p. */
    private static double phase(double psi, double kt) {
        double h = Yield.optimalBurstHeight(kt);
        return HobBlast.positivePhaseSeconds(HobBlast.rangeFor(psi, h, kt), h, kt);
    }

    /**
     * A distant wave (0,1 psi - wind 1,7 m/s for seconds) hardly moves the body: the front impact itself (1 N s) tilts less than
     *  by half a degree.
     */
    @Test
    void farWaveLeavesTheBodyStill() {
        for (double tPlus : new double[]{0.3, 1, 2}) {
            assertTrue(peakDeg(0.1, tPlus) < 0.5, "0,1 psi, phase " + tPlus + " from: " + peakDeg(0.1, tPlus));
        }
        assertEquals(0, tilt(0, 1).length);
        assertTrue(peakDeg(0.01, 1) < 0.05);
    }

    /**
     * 1 psi (wind 16 m/s, Beaufort 7–8 “prevents walking”, on the wave phase, and front impact - 11 N s) swings on Grad: y 1–2 kt
     *  (phase 0,3–0,6 s) the person stands with his back to the wave (up to 8° forward), at 15 kt (1,3 s) - also, but facing it (4,5° back) —
     *  takes a step back. From 2 psi (wind 31 m/s, hurricane gust) - to the border and forward: the man is walking.
     */
    @Test
    void onePsiSwaysTwoPsiKnocksDown() {
        for (double tPlus : new double[]{0.3, 0.5, 0.6}) {
            double a = peakDeg(1, tPlus);
            assertTrue(a > 2 && a < Math.toDegrees(BodySway.FORWARD), "1 psi, phase " + tPlus + " from: " + a);
        }
        double a15 = peakDeg(1, phase(1, 15));
        assertTrue(a15 > Math.toDegrees(BodySway.BACK) && a15 < Math.toDegrees(BodySway.FORWARD), "1 psi, 15 kt: " + a15);
        double face = Math.toDegrees(BodySway.peak(BodySway.tilt(1000 * BlastModel.kpa(1), q(1), 1, phase(1, 15), BodySway.BACK, BodySway.FORWARD), 0));
        assertEquals(Math.toDegrees(BodySway.BACK), face, 1e-3, "1 psi, 15 kt, facing the wave");
        for (double tPlus : new double[]{0.5, 0.8, 1.5}) {
            // edge - between sample (counting step 1 ms, sample - 10 ms)
            assertEquals(Math.toDegrees(BodySway.FORWARD), peakDeg(2, tPlus), 1e-3, "2 psi, phase " + tPlus + " s");
        }
    }

    /**
     * The front hits at once (complaint 18.10: the camera tilted smoothly when the wave arrived): the tilt speed is immediate, in the first
     * 10 ms, and does not increase from zero in tenths of a second, as from the wind alone (the wind then accelerates the body further). Impact - by load during flow around
     * Glasstone and Dolan: at 1 psi (U = 350 m/s, reflected 14,2 kPa, pressure 0,17 kPa) – 0,6 m² × ((14,2 − 6,9 − 0,17) 0,3/350
     * + 6,9·0,65/350) ≈ 11,3 N·s; along the ground - share cos α, at the pressure along the ground - cos²α.
     */
    @Test
    void frontHitsAtOnce() {
        double p = 1000 * BlastModel.kpa(1);
        assertEquals(11.3, BodySway.jolt(p, q(1)), 0.2);
        assertEquals(BodySway.jolt(p, q(1)) * Math.sqrt(0.5), BodySway.jolt(p, q(1) / 2), 1e-9);
        assertEquals(0, BodySway.jolt(0, q(1)));
        // behind the wall (half of the rays to the explosion are open) - half the impact, the share along the ground is the same
        double[] open = tilt(1, 0.5), half = BodySway.tilt(p, q(1), 0.5, 0.5, BodySway.FORWARD, BodySway.BACK);
        assertEquals(open[1] / 2, half[1], 1e-12);
        for (double psi : new double[]{0.3, 1, 3, 10}) {
            double[] t = tilt(psi, phase(psi, 15)), wind = BodySway.tilt(0, q(psi), 1, phase(psi, 15), BodySway.FORWARD, BodySway.BACK);
            // impact speed - from the first sample; the wind then accelerates the body, but it doesn’t start from scratch
            double hit = BodySway.jolt(1000 * BlastModel.kpa(psi), q(psi)) * BodySway.HEIGHT / BodySway.INERTIA / BodySway.RATE;
            assertTrue(t[1] >= 0.95 * hit && t[1] >= 0.25 * fastest(t), psi + " psi: the impact is not immediate, " + Math.toDegrees(t[1]) * BodySway.RATE + "°/s");
            // with one wind, the tilt from a standstill picks up speed smoothly - as it was
            if (psi <= 3) assertTrue(wind[1] < 0.1 * fastest(wind), psi + " psi, wind");
        }
    }

    /** The highest tilt speed downwind between sample, rad per sample. */
    private static double fastest(double[] t) {
        double m = 0;
        for (int i = 1; i < t.length; i++) m = Math.max(m, t[i] - t[i - 1]);
        return m;
    }

    /**
     * Beyond the boundary, a person walks: the tilt remains on it while the wind is stronger than equilibrium, and the body straightens, without
     *  flying over the vertical, while the wind blows in phase. Then it is driven back to the explosion only by the reverse wind of rarefaction
     *  (up to 13,5% of front pressure, pressure - 1,8%): for 2–3 psi - on Grad, for 5 psi and stronger - stronger, after the phase
     *  (back to the wave - back to the edge 4,5°). Facing the wave - the same with boundaries in reverse.
     */
    @Test
    void stepsAtTheEdge() {
        for (double[] e : new double[][]{{BodySway.FORWARD, BodySway.BACK}, {BodySway.BACK, BodySway.FORWARD}}) {
            for (double psi : new double[]{2, 3, 5, 10, 20}) {
                double tPlus = phase(psi, 15);
                double[] t = BodySway.tilt(1000 * BlastModel.kpa(psi), q(psi), 1, tPlus, e[0], e[1]);
                int held = 0, top = -1, low = 0;
                for (int i = 0; i < t.length; i++) {
                    if (t[i] == e[0]) {
                        held++;
                        if (top < 0) top = i;
                    }
                    if (t[i] < t[low]) low = i;
                    if (i < tPlus * BodySway.RATE) assertTrue(t[i] >= 0, psi + " psi: flew over the vertical in phase, " + i);
                }
                String what = psi + " psi, edge " + Math.toDegrees(e[0]) + "°";
                assertTrue(held > 0 && top < tPlus * BodySway.RATE, what + ": no edge");
                assertTrue(t[low] >= -e[1], what);
                if (psi <= 3) assertTrue(t[low] > -Math.toRadians(2), what + ": back " + Math.toDegrees(t[low]));
                else assertTrue(low > 1.5 * tPlus * BodySway.RATE, what + ": back before vacuum, " + low);
            }
        }
    }

    /** Stronger pressure and longer phase means greater slope (until it reaches the edge). */
    @Test
    void moreWindLeansMore() {
        double prev = 0;
        for (double psi = 0.2; peakDeg(psi, 0.8) < Math.toDegrees(BodySway.FORWARD) - 1e-3; psi += 0.1) {
            double a = peakDeg(psi, 0.8);
            assertTrue(a > prev, psi + " psi");
            prev = a;
        }
        prev = 0;
        for (double tPlus = 0.1; tPlus <= 2 && peakDeg(1, tPlus) < Math.toDegrees(BodySway.FORWARD) - 1e-3; tPlus += 0.1) {
            double a = peakDeg(1, tPlus);
            assertTrue(a > prev, "phase " + tPlus);
            prev = a;
        }
    }

    /**
     * The body straightens: the tilt is in the wind, towards the end it stands (less than {@link BodySway#STILL}), and no longer than
     * {@link BodySway#LONGEST}; no jumps between sample.
     */
    @Test
    void bodyStandsUpAgain() {
        for (double psi : new double[]{0.3, 1, 2, 5}) {
            double[] t = tilt(psi, 0.8);
            assertTrue(t.length > BodySway.RATE && t.length <= BodySway.LONGEST * BodySway.RATE + 1, psi + " psi: " + t.length);
            assertTrue(Math.abs(t[t.length - 1]) < BodySway.STILL, psi + " psi: not straightened");
            double most = 0;
            for (double v : t) most = Math.max(most, v);
            assertEquals(BodySway.peak(t, 0), most, 1e-12, psi + " psi: tilt not downwind");
            assertEquals(0, t[0], 1e-12);
        }
    }

    /** The inclination of the charge {@code kg} standing from the ground explosion at {@code m} meters facing the explosion, °: the same pendulum, pressure and phase - Kinney-Graham. */
    private static double conventional(double kg, double m) {
        double pa = 1000 * BlastModel.surfaceOverpressureKpa(m, kg), q = 1000 * BlastModel.dynamicPressureKpa(pa / 1000);
        return Math.toDegrees(BodySway.peak(BodySway.tilt(pa, q, 1, BlastModel.chemicalPhaseSeconds(m, kg, true), BodySway.BACK, BodySway.FORWARD), 0));
    }

    /**
     * An ordinary explosion pushes with one sharp blow with the arrival of the front (the phase is milliseconds, the wind behind it adds almost nothing): Shahed in 20 m knocks you down (tilt - to the edge of stability), in 300 m - tenths of a Grad, like
     *  the usual swaying of a standing person.
     */
    @Test
    void shahedKnocksDownAtTwentyMetresAndBarelyTouchesAtThreeHundred() {
        assertEquals(Math.toDegrees(BodySway.BACK), conventional(50, 20), 1e-3);
        double far = conventional(50, 300);
        assertTrue(far < 0.5, "shahed in 300 m: " + far + "°");
        double pa = 1000 * BlastModel.surfaceOverpressureKpa(60, 50), q = 1000 * BlastModel.dynamicPressureKpa(pa / 1000);
        double[] t = BodySway.tilt(pa, q, 1, BlastModel.chemicalPhaseSeconds(60, 50, true), BodySway.FORWARD, BodySway.BACK);
        // impact - immediately: the tilt speed from the first sample, the wind behind the phase in 16 ms almost does not add it
        assertTrue(t.length > 1 && t[1] > 0);
        double hitOnly = BodySway.jolt(pa, q, BlastModel.chemicalPhaseSeconds(60, 50, true)) * 0.83 / 66;
        assertEquals(hitOnly, t[1] * BodySway.RATE, 0.25 * hitOnly);
    }

    /** The pressure in a phase is the average per step from the antiderivative: it coincides with the numerical integral, and changes sign after the phase. */
    @Test
    void windShareIsTheMeanOfTheDynamicPressure() {
        for (double[] r : new double[][]{{0, 0.1}, {0.5, 1.5}, {1, 3}, {0, 10}}) {
            int n = 100_000;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                double x = r[0] + (i + 0.5) / n * (r[1] - r[0]), f = (1 - x) * Math.exp(-x);
                sum += f * Math.abs(f);
            }
            assertEquals(sum / n, BodySway.windShare(r[0], r[1]), 1e-7);
        }
        assertTrue(BodySway.windShare(1.2, 2) < 0);
        // the blow of the long wave is the same
        assertEquals(BodySway.jolt(6895, q(1)), BodySway.jolt(6895, q(1), 1e9), 1e-6);
    }

    /** The body and head are at the ground explosion {@code kg} at {@code m} meters, with its back to the explosion (pushes forward). */
    private static BodySway.Motion motion(double kg, double m) {
        double pa = 1000 * BlastModel.surfaceOverpressureKpa(m, kg), q = 1000 * BlastModel.dynamicPressureKpa(pa / 1000);
        double tp = BlastModel.chemicalPhaseSeconds(m, kg, true);
        return BodySway.motion(pa, q, 1, tp, tp, BodySway.FORWARD, BodySway.BACK);
    }

    /**
     * Head on the neck (complaint 23.10: “the camera doesn’t swing”): the shoulders move away with the blow, the head lags behind - first it leans
     *  back against the push, then it swings around the body and fades away; the camera (body + neck) swings more than once, and each
     *  next swing is smaller than the previous one. No jumps: the step between sample is no more than the impact speed per step. The body from
     *  to the neck does not change ({@link BodySway#tilt} is the same body).
     */
    @Test
    void headRocksOnTheNeckAndSettles() {
        for (double[] c : new double[][]{{3, 50}, {50, 50}, {50, 100}, {50, 300}, {450, 100}, {450, 300}}) {
            BodySway.Motion m = motion(c[0], c[1]);
            String what = c[0] + " kg, " + c[1] + " m";
            double[] body = m.body(), neck = m.neck(), head = new double[body.length];
            assertEquals(body.length, neck.length);
            for (int i = 0; i < head.length; i++) head[i] = body[i] + neck[i];
            double pa = 1000 * BlastModel.surfaceOverpressureKpa(c[1], c[0]), q = 1000 * BlastModel.dynamicPressureKpa(pa / 1000);
            double tp = BlastModel.chemicalPhaseSeconds(c[1], c[0], true);
            assertTrue(head[1] < 0, what + ": the head lags back first");
            // camera ranges: extremes between zero crossings
            java.util.List<Double> swings = new java.util.ArrayList<>();
            double extreme = 0;
            for (int i = 1; i < head.length; i++) {
                if (Math.signum(head[i]) != Math.signum(head[i - 1]) && extreme != 0) {
                    swings.add(Math.abs(extreme));
                    extreme = 0;
                }
                if (Math.abs(head[i]) > Math.abs(extreme)) extreme = head[i];
            }
            assertTrue(swings.size() >= 3, what + ": swings, swings " + swings.size());
            for (int k = 2; k < swings.size(); k++) assertTrue(swings.get(k) < swings.get(k - 1), what + ": fades out " + swings);
            assertTrue(Math.abs(body[body.length - 1]) < BodySway.STILL && Math.abs(neck[neck.length - 1]) < BodySway.STILL, what + ": calmed down");
            double kick = Math.abs(head[1] - head[0]);
            for (int i = 2; i < head.length; i++) assertTrue(Math.abs(head[i] - head[i - 1]) <= 1.05 * kick, what + ": jump to " + i);
            assertTrue(head.length <= BodySway.LONGEST * BodySway.RATE + 1);
        }
    }

    /**
     * How much does it shake: with Shahed (50 kg) in 100 m the camera goes to 1–4°, in 300 m - by tenths of Grad; closer and
     *  has a larger charge - more; the swing ends after 1–6 s (up to a hundredth Grad).
     */
    @Test
    void headSwingGrowsWithTheBlast() {
        double near = head(motion(50, 50)), mid = head(motion(50, 100)), far = head(motion(50, 300)), big = head(motion(450, 100));
        assertTrue(mid > 1 && mid < 4, "shahed 100 m: " + mid);
        assertTrue(far > 0.2 && far < 1.5, "shahed 300 m: " + far);
        assertTrue(near > mid && mid > far && big > mid, near + " " + mid + " " + far + " " + big);
        for (double[] c : new double[][]{{50, 50}, {50, 300}}) {
            double seconds = motion(c[0], c[1]).body().length / (double) BodySway.RATE;
            assertTrue(seconds > 1 && seconds < 6, c[1] + " m: " + seconds + " s");
        }
    }

    /**
     * The inclination of the body from the neck does not change: sample {@link BodySway#tilt} - the same as up to the head on the neck (the standard was removed from the code
     * main 9840901d: charge, m, row length there, sample 1, 10, 50, 100, 200); the row can be longer - until the head
     *  has calmed down, the body adds a sample less than {@link BodySway#STILL}.
     */
    @Test
    void neckLeavesTheBodyAsItWas() {
        double[][] main = {
                {50.0, 100.0, 416, 7.395679767067632E-4, 0.007188804133661276, 0.0221177882058665, 0.0012014023244044333, 0.0022245733355720915},
                {150.0, 50.0, 493, 0.002806985316968799, 0.02769623655308624, 0.0849411121087353, 0.004481699548655748, 0.008554702111216362},
                {3.0, 30.0, 417, 8.408738985636841E-4, 0.008135599252273383, 0.02504726656991267, 0.0013629278238277598, 0.002519189547521692}};
        int[] at = {1, 10, 50, 100, 200};
        for (double[] c : main) {
            double pa = 1000 * BlastModel.surfaceOverpressureKpa(c[1], c[0]), q = 1000 * BlastModel.dynamicPressureKpa(pa / 1000);
            double[] t = BodySway.tilt(pa, q, 1, BlastModel.chemicalPhaseSeconds(c[1], c[0], true), BodySway.FORWARD, BodySway.BACK);
            assertTrue(t.length >= c[2], c[0] + " kg: shorter than it was");
            for (int k = 0; k < at.length; k++) assertEquals(c[3 + k], t[at[k]], 1e-15, c[0] + " kg, " + c[1] + " m, sample " + at[k]);
            for (int i = (int) c[2]; i < t.length; i++) assertTrue(Math.abs(t[i]) < BodySway.STILL);
        }
    }

    /** The head at the explosion itself (50 kg in 10 m) is no further than the neck. */
    @Test
    void neckStaysInItsRange() {
        BodySway.Motion m = motion(50, 10);
        for (double v : m.neck()) assertTrue(Math.abs(v) < BodySway.NECK_RANGE, Math.toDegrees(v) + "°");
    }

    private static double head(BodySway.Motion m) {
        double most = 0;
        for (int i = 0; i < m.body().length; i++) most = Math.max(most, Math.abs(m.body()[i] + m.neck()[i]));
        return Math.toDegrees(most);
    }
}
