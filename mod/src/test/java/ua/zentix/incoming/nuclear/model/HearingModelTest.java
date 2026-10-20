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

    /** Энергия фронта, у которой сдвиг через 2 минуты — {@code shift} дБ. */
    private static double exposureFor(double shift) {
        double trauma = HearingModel.exposure(5 * PSI);
        return HearingModel.ONSET_EXPOSURE + shift / 50 * (trauma - HearingModel.ONSET_EXPOSURE);
    }

    /**
     * Волны в пределах 2 минут — одна доза (энергии складываются: две равные — +3 дБ энергии); позже у каждой своя прямая,
     * и слабая поздняя волна не сокращает сильной ранней, а сильная поздняя — своя, с начала.
     */
    @Test
    void severalWavesAddWithinTwoMinutesAndKeepTheLongestRecovery() {
        HearingModel.Ear ear = new HearingModel.Ear();
        double e = exposureFor(30);
        ear.blast(e, 0);
        ear.blast(e, 1 / 60.0);
        assertEquals(HearingModel.shift(e + 10 * Math.log10(2)), ear.shift(1 / 60.0 + HearingModel.FIRST_HOURS), 1e-9);
        assertTrue(HearingModel.shift(e + 10 * Math.log10(2)) > 38);

        // 40 дБ и через полминуты 45 — одна доза сильнее 45: восстановление дольше, чем у 45 (22,6 ч)
        ear = new HearingModel.Ear();
        ear.blast(exposureFor(40), 0);
        ear.blast(exposureFor(45), 0.5 / 60);
        assertTrue(ear.shift(0.5 / 60 + HearingModel.recoveryHours(45)) > 0);

        // 60 дБ, через 10 часов 15 — сдвиг идёт по прямой 60 дБ до её конца (64 ч)
        ear = new HearingModel.Ear();
        ear.blast(exposureFor(60), 0);
        ear.blast(exposureFor(15), 10);
        assertEquals(HearingModel.shiftAfter(60, 26), ear.shift(26), 1e-9);
        assertEquals(HearingModel.shiftAfter(60, 63), ear.shift(63), 1e-9);
        assertTrue(ear.shift(63) > 0);

        // 20 дБ, через час 50 — с 50, с начала
        ear = new HearingModel.Ear();
        ear.blast(exposureFor(20), 0);
        ear.blast(exposureFor(50), 1);
        assertEquals(50, ear.shift(1 + HearingModel.FIRST_HOURS), 1e-9);
        assertEquals(0, ear.shift(1 + HearingModel.recoveryHours(50) + 0.01), 0);
        assertTrue(!ear.any());

        // две волны чуть тише порога рядом — вместе выше
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

    /** Один закон: у долгой волны (ядерная, фаза от 0,2 с) энергия всей волны — энергия скачка, нынешние числа ядерки. */
    @Test
    void longWaveEnergyIsTheStepEnergy() {
        for (double pa : new double[]{200, 5000, 30_000}) {
            for (double t : new double[]{0.2, 0.6, 1.3}) {
                assertEquals(HearingModel.exposure(pa), HearingModel.exposure(pa, t), 0.02, pa + " Па, фаза " + t);
            }
        }
        // интеграл A-энергии скачка — та самая постоянная
        assertEquals(HearingModel.STEP_A, HearingModel.exposure(1, 1e6) - 20 * Math.log10(1 / HearingModel.REFERENCE_PA), 0.01);
    }

    /**
     * Обычный взрыв: фаза — миллисекунды, слышна вся волна, и её энергия меньше энергии скачка того же пика. Порог тот
     * же — суточная доза: у шахеда сдвиг начинается в ~75 м (пик там ~168 дБ, 0,8 psi), у «Ланцета» — в ~35 м; дальше
     * сдвига нет, ближе — есть.
     */
    @Test
    void conventionalBlastShiftsHearingOnlyInsideTheThreshold() {
        for (double[] c : new double[][]{{50, 75}, {3, 33}, {450, 150}}) {
            double kg = c[0], onset = onset(kg);
            assertEquals(c[1], onset, 0.15 * c[1], kg + " кг: порог сдвига");
            assertEquals(0, HearingModel.shift(exposureAt(kg, 1.1 * onset)), 1e-9, kg + " кг: дальше порога сдвига нет");
            assertTrue(HearingModel.shift(exposureAt(kg, 0.8 * onset)) > 1, kg + " кг: ближе порога — сдвиг");
            double pa = 1000 * BlastModel.surfaceOverpressureKpa(onset, kg);
            assertTrue(HearingModel.exposure(pa, BlastModel.chemicalPhaseSeconds(onset, kg, true)) < HearingModel.exposure(pa), "меньше скачка");
            assertTrue(20 * Math.log10(pa / HearingModel.REFERENCE_PA) > 160, kg + " кг: пик на пороге");
        }
    }

    /** Докуда слышно: дальше с зарядом; у ракеты — дальше прежних 8 км пакетов; на этой дальности энергия — фон ночи. */
    @Test
    void audibleRangeGrowsWithChargeAndEndsAtTheNightBackground() {
        double prev = 0;
        for (double kg : new double[]{3, 6.4, 50, 450}) {
            double r = HearingModel.audibleRangeM(kg);
            assertTrue(r > prev, kg + " кг: " + r);
            assertEquals(HearingModel.QUIET_NIGHT_A + 10 * Math.log10(HearingModel.INTEGRATION_S), HearingModel.blastExposureAt(kg, r), 0.05);
            prev = r;
        }
        assertTrue(HearingModel.audibleRangeM(450) > 8000);
        assertEquals(0, HearingModel.audibleRangeM(0));
    }

    private static double exposureAt(double kg, double m) {
        return HearingModel.exposure(1000 * BlastModel.surfaceOverpressureKpa(m, kg), BlastModel.chemicalPhaseSeconds(m, kg, true));
    }

    /** Где энергия волны заряда {@code kg} у уха — порог сдвига, м. */
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
