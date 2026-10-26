package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** hearing after the wave: energy of the audible front, threshold shift, recovery, membranes, volume during shift. */
class HearingModelTest {
    private static final double PSI = 6895;

    /** A-IEC correction 61672 (0 dB at 1 kHz), amplitude fraction. */
    private static double aWeight(double f) {
        double f2 = f * f;
        double ra = 12194.0 * 12194 * f2 * f2 / ((f2 + 20.6 * 20.6) * Math.sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194));
        return ra * Math.pow(10, 2.0 / 20);
    }

    @Test
    void stepEnergyWithAWeightingIsThePeakMinusThirtyNineDecibels() {
        // jump 1 Pa: spectrum 1/(2πf), energy corrected on both sides of frequencies
        double sum = 0;
        for (double f = 0.1; f < 1e6; f *= 1.0005) {
            double df = f * 0.0005, x = aWeight(f) / (2 * Math.PI * f);
            sum += 2 * x * x * df;
        }
        assertEquals(10 * Math.log10(sum), HearingModel.STEP_A, 0.02);
        assertEquals(20 * Math.log10(PSI / 2e-5) + HearingModel.STEP_A, HearingModel.exposure(PSI), 1e-9);
    }

    @Test
    void shiftStartsAtTheDailyNoiseDoseAndReachesTraumaAtFivePsi() {
        assertEquals(129.6, HearingModel.ONSET_EXPOSURE, 0.05);
        // 85 dBA for 8 hours - at the front ~0,75 psi
        assertEquals(0, HearingModel.shift(HearingModel.exposure(0.7 * PSI)), 0);
        assertTrue(HearingModel.shift(HearingModel.exposure(0.8 * PSI)) > 0);
        // sonic boom in 1 psi - hearing almost untouched
        assertTrue(HearingModel.shift(HearingModel.exposure(PSI)) < 10);
        assertEquals(50, HearingModel.shift(HearingModel.exposure(5 * PSI)), 1e-9);
        // distant wave at 0,3 psi (2 kt per 5 km) - nothing
        assertEquals(0, HearingModel.shift(HearingModel.exposure(0.3 * PSI)), 0);
    }

    @Test
    void shiftRecoversLinearlyInLogTime() {
        assertEquals(30, HearingModel.shiftAfter(30, 2 / 60.0), 1e-9);
        // from 2 minutes to 16 hours - 2,68 decades: 37 % per decade
        assertEquals(30 * (1 - 1 / Math.log10(480)), HearingModel.shiftAfter(30, 20 / 60.0), 1e-9);
        assertEquals(0, HearingModel.shiftAfter(30, 16), 0);
        assertEquals(0, HearingModel.shiftAfter(40, 16), 0);
        assertTrue(HearingModel.shiftAfter(50, 16) > 0);
        assertEquals(32, HearingModel.recoveryHours(50), 1e-9);
        // immediately after the wave - more than 2 minutes, but not without a limit
        double now = HearingModel.shiftAfter(30, 0);
        assertTrue(now > 30 && now < 30 * 1.4, () -> "right away " + now);
        double prev = Double.MAX_VALUE;
        for (double h = 0; h < 40; h += 0.05) {
            double s = HearingModel.shiftAfter(45, h);
            assertTrue(s <= prev && s >= 0);
            prev = s;
        }
    }

    @Test
    void eardrumsRuptureLogNormally() {
        assertEquals(0.01, HearingModel.ruptureProbability(5 * BlastModel.KPA_PER_PSI), 2e-4);
        assertEquals(0.5, HearingModel.ruptureProbability(15 * BlastModel.KPA_PER_PSI), 1e-6);
        assertEquals(0.99, HearingModel.ruptureProbability(45 * BlastModel.KPA_PER_PSI), 2e-4);
        assertTrue(HearingModel.ruptureProbability(2 * BlastModel.KPA_PER_PSI) < 1e-4);
        assertEquals(0, HearingModel.ruptureProbability(0), 0);
    }

    @Test
    void tornEardrumsMuffleUntilTheyHeal() {
        assertEquals(25, HearingModel.rupturedLoss(false, 0), 1e-9);
        assertEquals(15, HearingModel.rupturedLoss(true, 27), 1e-9);
        assertEquals(12.5, HearingModel.rupturedLoss(false, 42), 1e-9);
        assertEquals(0, HearingModel.rupturedLoss(false, 56), 0);
    }

    @Test
    void loudSoundsLoseLessThanTheThresholdShift() {
        assertEquals(0, HearingModel.loudnessLoss(0), 0);
        assertEquals(30, HearingModel.loudnessLoss(50), 1e-9);
        for (double s = 1; s < 70; s += 1) assertTrue(HearingModel.loudnessLoss(s) < s);
        assertTrue(HearingModel.loudnessLoss(20) < 10);
    }

    /** The front energy, which shifts after 2 minutes, is {@code shift} dB. */
    private static double exposureFor(double shift) {
        double trauma = HearingModel.exposure(5 * PSI);
        return HearingModel.ONSET_EXPOSURE + shift / 50 * (trauma - HearingModel.ONSET_EXPOSURE);
    }

    /**
     * Waves within 2 minutes - one dose (energies add up: two equal - +3 dB of energy); later, each has its own straight line,
     * , and the weak late wave does not shorten the strong early one, and the strong late wave has its own, from the beginning.
     */
    @Test
    void severalWavesAddWithinTwoMinutesAndKeepTheLongestRecovery() {
        HearingModel.Ear ear = new HearingModel.Ear();
        double e = exposureFor(30);
        ear.blast(e, 0);
        ear.blast(e, 1 / 60.0);
        assertEquals(HearingModel.shift(e + 10 * Math.log10(2)), ear.shift(1 / 60.0 + HearingModel.FIRST_HOURS), 1e-9);
        assertTrue(HearingModel.shift(e + 10 * Math.log10(2)) > 38);

        // 40 dB and after half a minute 45 - one dose stronger 45: recovery longer than 45 (22,6 h)
        ear = new HearingModel.Ear();
        ear.blast(exposureFor(40), 0);
        ear.blast(exposureFor(45), 0.5 / 60);
        assertTrue(ear.shift(0.5 / 60 + HearingModel.recoveryHours(45)) > 0);

        // 60 dB, after 10 hours 15 - the shift goes along the straight line 60 dB to its end (64 hours)
        ear = new HearingModel.Ear();
        ear.blast(exposureFor(60), 0);
        ear.blast(exposureFor(15), 10);
        assertEquals(HearingModel.shiftAfter(60, 26), ear.shift(26), 1e-9);
        assertEquals(HearingModel.shiftAfter(60, 63), ear.shift(63), 1e-9);
        assertTrue(ear.shift(63) > 0);

        // 20 dB, after an hour 50 - from 50, from the beginning
        ear = new HearingModel.Ear();
        ear.blast(exposureFor(20), 0);
        ear.blast(exposureFor(50), 1);
        assertEquals(50, ear.shift(1 + HearingModel.FIRST_HOURS), 1e-9);
        assertEquals(0, ear.shift(1 + HearingModel.recoveryHours(50) + 0.01), 0);
        assertTrue(!ear.any());

        // two waves slightly quieter than the threshold next to each other - together higher
        ear = new HearingModel.Ear();
        ear.blast(HearingModel.ONSET_EXPOSURE - 1, 0);
        assertEquals(0, ear.shift(0), 0);
        ear.blast(HearingModel.ONSET_EXPOSURE - 1, 0.5 / 60);
        assertTrue(ear.shift(0.5 / 60 + HearingModel.FIRST_HOURS) > 0);
    }

    @Test
    void normalDistribution() {
        assertEquals(0.5, HearingModel.normal(0), 1e-7);
        assertEquals(0.97725, HearingModel.normal(2), 1e-5);
        assertEquals(0.01, HearingModel.normal(-2.326), 1e-4);
    }

    /** One law: for a long wave (nuclear, phase from 0,2 s) the energy of the entire wave is the energy of the jump, the current numbers are the nuke. */
    @Test
    void longWaveEnergyIsTheStepEnergy() {
        for (double pa : new double[]{200, 5000, 30_000}) {
            for (double t : new double[]{0.2, 0.6, 1.3}) {
                assertEquals(HearingModel.exposure(pa), HearingModel.exposure(pa, t), 0.02, pa + " Pa, phase " + t);
            }
        }
        // the integral A of the shock energy is the same constant
        assertEquals(HearingModel.STEP_A, HearingModel.exposure(1, 1e6) - 20 * Math.log10(1 / HearingModel.REFERENCE_PA), 0.01);
    }

    /**
     * An ordinary explosion: the phase is milliseconds, the entire wave is audible, and its energy is less than the energy of a jump of the same peak. The threshold is the same as the daily dose: for Shahed the shift begins at ~75 m (peak there ~168 dB, 0,8 psi), for “Lancet” - at ~35 m; Further
     *  there is no shift, closer - there is.
     */
    @Test
    void conventionalBlastShiftsHearingOnlyInsideTheThreshold() {
        for (double[] c : new double[][]{{50, 75}, {3, 33}, {450, 150}}) {
            double kg = c[0], onset = onset(kg);
            assertEquals(c[1], onset, 0.15 * c[1], kg + " kg: shift threshold");
            assertEquals(0, HearingModel.shift(exposureAt(kg, 1.1 * onset)), 1e-9, kg + " kg: there is no shift beyond the threshold");
            assertTrue(HearingModel.shift(exposureAt(kg, 0.8 * onset)) > 1, kg + " kg: closer to the threshold - shift");
            double pa = 1000 * BlastModel.surfaceOverpressureKpa(onset, kg);
            assertTrue(HearingModel.exposure(pa, BlastModel.chemicalPhaseSeconds(onset, kg, true)) < HearingModel.exposure(pa), "less jump");
            assertTrue(20 * Math.log10(pa / HearingModel.REFERENCE_PA) > 160, kg + " kg: peak on the threshold");
        }
    }

    /** As far as you can hear: continue with the charge; for the rocket - further than the previous 8 km packages; at this range the energy is the background of the night. */
    @Test
    void audibleRangeGrowsWithChargeAndEndsAtTheNightBackground() {
        double prev = 0;
        for (double kg : new double[]{3, 6.4, 50, 450}) {
            double r = HearingModel.audibleRangeM(kg);
            assertTrue(r > prev, kg + " kg: " + r);
            assertEquals(HearingModel.QUIET_NIGHT_A + 10 * Math.log10(HearingModel.INTEGRATION_S), HearingModel.blastExposureAt(kg, r), 0.05);
            prev = r;
        }
        assertTrue(HearingModel.audibleRangeM(450) > 8000);
        assertEquals(0, HearingModel.audibleRangeM(0));
    }

    private static double exposureAt(double kg, double m) {
        return HearingModel.exposure(1000 * BlastModel.surfaceOverpressureKpa(m, kg), BlastModel.chemicalPhaseSeconds(m, kg, true));
    }

    /** Where is the energy of the charge wave {@code kg} at the ear - the shift threshold, m. */
    private static double onset(double kg) {
        double lo = 1, hi = 10_000;
        for (int i = 0; i < 60; i++) {
            double mid = Math.sqrt(lo * hi);
            if (exposureAt(kg, mid) > HearingModel.ONSET_EXPOSURE) lo = mid;
            else hi = mid;
        }
        return lo;
    }
}
