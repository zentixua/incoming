package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The impact of a weak shock wave at the ear: one front, its steepness along the jump, a decline beyond the positive phase, the top behind the hill. */
class BlastPulseTest {
    private static final double PSI = 6895;

    @Test
    void friedlanderCrossesZeroAtThePositivePhaseAndBalancesItsImpulse() {
        assertEquals(1, BlastPulse.friedlander(0, 1.2), 1e-12);
        assertEquals(0, BlastPulse.friedlander(1.2, 1.2), 1e-12);
        assertTrue(BlastPulse.friedlander(2.4, 1.2) < 0);
        double sum = 0, dt = 1e-4;
        for (double t = 0; t < 60; t += dt) sum += BlastPulse.friedlander(t, 1.2) * dt;
        assertEquals(0, sum, 1e-3);
    }

    @Test
    void weakerShockRisesSlower() {
        // 0,01 psi — 2,5 ms, 0,1 psi — 0,25 ms: back jump
        assertEquals(2.47e-3, BlastPulse.riseSeconds(0.01 * PSI), 0.05e-3);
        assertEquals(10, BlastPulse.riseSeconds(0.01 * PSI) / BlastPulse.riseSeconds(0.1 * PSI), 1e-9);
    }

    @Test
    void oneFrontNoTailShock() {
        short[] pcm = BlastPulse.render(0.05 * PSI, 1.2, 1);
        int peakAt = argmax(pcm);
        assertEquals(Math.round(BlastPulse.PEAK * Short.MAX_VALUE), Math.abs(pcm[peakAt]), 1);
        assertTrue(pcm[peakAt] > 0, "compression, not rarefaction");
        assertTrue(peakAt < BlastPulse.RATE / 100, "front - in the first 10 ms");
        // after the front there is nothing comparable: there is no tail jump through t+
        int after = peakAt + BlastPulse.RATE / 20;
        for (int i = after; i < pcm.length; i++) assertTrue(Math.abs(pcm[i]) < 0.1 * Short.MAX_VALUE, () -> "second strike");
        assertTrue(pcm.length <= BlastPulse.RATE * (BlastPulse.TAIL + 0.02));
        assertEquals(0, pcm[pcm.length - 1], 1);
    }

    @Test
    void rendersTheModelRiseTime() {
        for (double psi : new double[]{0.02, 0.05}) {
            double rise = rise(BlastPulse.render(psi * PSI, 1.2, 1));
            assertEquals(BlastPulse.riseSeconds(psi * PSI), rise, 0.2 * rise + 2.0 / BlastPulse.RATE, () -> psi + " psi");
        }
    }

    @Test
    void hillTakesTheHighsSoTheFrontIsDuller() {
        double open = rise(BlastPulse.render(0.2 * PSI, 1.2, 1)), hill = rise(BlastPulse.render(0.2 * PSI, 1.2, 0.05));
        assertTrue(hill > 5 * open, () -> open + " → " + hill);
    }

    /** From 10 to 90 % of the peak before it, p. */
    private static double rise(short[] pcm) {
        int peak = argmax(pcm);
        int lo = 0, hi = 0;
        for (int i = 0; i <= peak; i++) {
            if (lo == 0 && pcm[i] >= 0.1 * pcm[peak]) lo = i;
            if (hi == 0 && pcm[i] >= 0.9 * pcm[peak]) hi = i;
        }
        return (double) (hi - lo) / BlastPulse.RATE;
    }

    private static int argmax(short[] pcm) {
        int best = 0;
        for (int i = 1; i < pcm.length; i++) if (Math.abs(pcm[i]) > Math.abs(pcm[best])) best = i;
        return best;
    }

    /**
     * An ordinary explosion: the pressure and phase at the ear are Kinney-Graham (the numbers are the same formula, calculated separately), the phase is
     *  milliseconds, and the track carries the entire wave: compression, rarefaction followed, everything in {@link BlastPulse#TAIL}. The cut below
     * 20 Hz (not audible) shortens the audible compression: for the 7 ms phase - by half, for 24 ms - by four times (the nuclear wave remains
     *  with one front). Balanced Friedlander pulse (p·t₊/e) - 0,6–0,7 Kinney-Graham pulse:
     *  their phases and pulses are selected separately (shape deviation, nuclear wave has the same shape).
     */
    @Test
    void conventionalPulseIsTheWholeKinneyGrahamWave() {
        double[][] cases = {
                // kg, m, Pa, ms, impulse KG Pa s
                {3, 50, 3011, 7.18, 12.08}, {3, 1000, 147, 7.39, 0.60}, {450, 50, 25_778, 24.10, 338.5}, {450, 1000, 783, 39.19, 17.06}};
        for (double[] c : cases) {
            double pa = 1000 * BlastModel.surfaceOverpressureKpa(c[1], c[0]), t = BlastModel.chemicalPhaseSeconds(c[1], c[0], true);
            assertEquals(c[2], pa, 0.01 * c[2], c[0] + " kg in " + c[1] + " m: pressure");
            assertEquals(c[3], 1000 * t, 0.01 * c[3], c[0] + " kg in " + c[1] + " m: phase");
            double ratio = pa * t / Math.E / c[4];
            assertTrue(ratio > 0.6 && ratio < 0.75, c[0] + " kg in " + c[1] + " m: Friedlander impulse / KG " + ratio);
            short[] pcm = BlastPulse.render(pa, t, 1);
            int peakAt = 0;
            for (int i = 0; i < pcm.length; i++) if (pcm[i] > pcm[peakAt]) peakAt = i;
            int cross = peakAt;
            while (cross < pcm.length && pcm[cross] > 0) cross++;
            double positive = (double) (cross - peakAt) / BlastPulse.RATE;
            assertTrue(positive > 0.1 * t && positive < 1.1 * t, c[0] + " kg in " + c[1] + " m: compression in track " + positive);
            int min = cross;
            for (int i = cross; i < pcm.length; i++) if (pcm[i] < pcm[min]) min = i;
            assertTrue(pcm[min] < -0.05 * Short.MAX_VALUE, "vacuum can be heard");
            // after five phases and the cutoff ringing 20 Hz (its constant is 8 ms) there is almost no wave in the track
            int quiet = peakAt + (int) ((5 * t + 0.05) * BlastPulse.RATE);
            for (int i = quiet; i < pcm.length; i++) assertTrue(Math.abs(pcm[i]) < 0.05 * Short.MAX_VALUE, c[0] + " kg in " + c[1] + " m: tail " + pcm[i] + " on " + i);
        }
    }
}
