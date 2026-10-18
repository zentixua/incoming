package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Слух после волны: энергия слышимого фронта, сдвиг порога, восстановление, перепонки, громкость при сдвиге. */
class HearingModelTest {
    private static final double PSI = 6895;

    /** A-коррекция МЭК 61672 (0 дБ на 1 кГц), доля амплитуды. */
    private static double aWeight(double f) {
        double f2 = f * f;
        double ra = 12194.0 * 12194 * f2 * f2 / ((f2 + 20.6 * 20.6) * Math.sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194));
        return ra * Math.pow(10, 2.0 / 20);
    }

    @Test
    void stepEnergyWithAWeightingIsThePeakMinusThirtyNineDecibels() {
        // скачок 1 Па: спектр 1/(2πf), энергия с коррекцией по обе стороны частот
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
        // 85 дБА за 8 часов — у фронта ~0,75 psi
        assertEquals(0, HearingModel.shift(HearingModel.exposure(0.7 * PSI)), 0);
        assertTrue(HearingModel.shift(HearingModel.exposure(0.8 * PSI)) > 0);
        // звуковой удар в 1 psi — слух почти не тронут
        assertTrue(HearingModel.shift(HearingModel.exposure(PSI)) < 10);
        assertEquals(50, HearingModel.shift(HearingModel.exposure(5 * PSI)), 1e-9);
        // далёкая волна в 0,3 psi (2 кт за 5 км) — ничего
        assertEquals(0, HearingModel.shift(HearingModel.exposure(0.3 * PSI)), 0);
    }

    @Test
    void shiftRecoversLinearlyInLogTime() {
        assertEquals(30, HearingModel.shiftAfter(30, 2 / 60.0), 1e-9);
        // от 2 минут до 16 часов — 2,68 декады: за декаду уходит 37 %
        assertEquals(30 * (1 - 1 / Math.log10(480)), HearingModel.shiftAfter(30, 20 / 60.0), 1e-9);
        assertEquals(0, HearingModel.shiftAfter(30, 16), 0);
        assertEquals(0, HearingModel.shiftAfter(40, 16), 0);
        assertTrue(HearingModel.shiftAfter(50, 16) > 0);
        assertEquals(32, HearingModel.recoveryHours(50), 1e-9);
        // сразу после волны — больше, чем через 2 минуты, но не без предела
        double now = HearingModel.shiftAfter(30, 0);
        assertTrue(now > 30 && now < 30 * 1.4, () -> "сразу " + now);
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

    @Test
    void normalDistribution() {
        assertEquals(0.5, HearingModel.normal(0), 1e-7);
        assertEquals(0.97725, HearingModel.normal(2), 1e-5);
        assertEquals(0.01, HearingModel.normal(-2.326), 1e-4);
    }
}
