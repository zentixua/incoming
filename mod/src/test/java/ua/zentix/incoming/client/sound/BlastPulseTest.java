package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Удар слабой ударной волны у уха: один фронт, его крутизна по скачку, спад за положительную фазу, верха за холмом. */
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
        // 0,01 psi — 2,5 мс, 0,1 psi — 0,25 мс: обратно скачку
        assertEquals(2.47e-3, BlastPulse.riseSeconds(0.01 * PSI), 0.05e-3);
        assertEquals(10, BlastPulse.riseSeconds(0.01 * PSI) / BlastPulse.riseSeconds(0.1 * PSI), 1e-9);
    }

    @Test
    void oneFrontNoTailShock() {
        short[] pcm = BlastPulse.render(0.05 * PSI, 1.2, 1);
        int peakAt = argmax(pcm);
        assertEquals(Math.round(BlastPulse.PEAK * Short.MAX_VALUE), Math.abs(pcm[peakAt]), 1);
        assertTrue(pcm[peakAt] > 0, "сжатие, а не разрежение");
        assertTrue(peakAt < BlastPulse.RATE / 100, "фронт — в первые 10 мс");
        // после фронта ничего сравнимого: хвостового скачка через t+ нет
        int after = peakAt + BlastPulse.RATE / 20;
        for (int i = after; i < pcm.length; i++) assertTrue(Math.abs(pcm[i]) < 0.1 * Short.MAX_VALUE, () -> "второй удар");
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

    /** От 10 до 90 % пика перед ним, с. */
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
     * Обычный взрыв: давление и фаза у уха — Кинни–Грэхем (числа — та же формула, посчитанная отдельно), фаза —
     * миллисекунды, и дорожка несёт всю волну: сжатие, разрежение следом, всё в {@link BlastPulse#TAIL}. Срез ниже
     * 20 Гц (не слышно) укорачивает слышимое сжатие: у фазы 7 мс — вдвое, у 24 мс — вчетверо (у ядерной волны остаётся
     * один фронт). Импульс сбалансированного Фридлендера (p·t₊/e) — 0,6–0,7 импульса Кинни–Грэхема:
     * их фазы и импульсы подобраны порознь (отступление формы, у ядерной волны — та же форма).
     */
    @Test
    void conventionalPulseIsTheWholeKinneyGrahamWave() {
        double[][] cases = {
                // кг, м, Па, мс, импульс KG Па·с
                {3, 50, 3011, 7.18, 12.08}, {3, 1000, 147, 7.39, 0.60}, {450, 50, 25_778, 24.10, 338.5}, {450, 1000, 783, 39.19, 17.06}};
        for (double[] c : cases) {
            double pa = 1000 * BlastModel.surfaceOverpressureKpa(c[1], c[0]), t = BlastModel.chemicalPhaseSeconds(c[1], c[0], true);
            assertEquals(c[2], pa, 0.01 * c[2], c[0] + " кг в " + c[1] + " м: давление");
            assertEquals(c[3], 1000 * t, 0.01 * c[3], c[0] + " кг в " + c[1] + " м: фаза");
            double ratio = pa * t / Math.E / c[4];
            assertTrue(ratio > 0.6 && ratio < 0.75, c[0] + " кг в " + c[1] + " м: импульс Фридлендера / KG " + ratio);
            short[] pcm = BlastPulse.render(pa, t, 1);
            int peakAt = 0;
            for (int i = 0; i < pcm.length; i++) if (pcm[i] > pcm[peakAt]) peakAt = i;
            int cross = peakAt;
            while (cross < pcm.length && pcm[cross] > 0) cross++;
            double positive = (double) (cross - peakAt) / BlastPulse.RATE;
            assertTrue(positive > 0.1 * t && positive < 1.1 * t, c[0] + " кг в " + c[1] + " м: сжатие в дорожке " + positive);
            int min = cross;
            for (int i = cross; i < pcm.length; i++) if (pcm[i] < pcm[min]) min = i;
            assertTrue(pcm[min] < -0.05 * Short.MAX_VALUE, "разрежение слышно");
            // через пять фаз и звон среза 20 Гц (его постоянная — 8 мс) волны в дорожке почти нет
            int quiet = peakAt + (int) ((5 * t + 0.05) * BlastPulse.RATE);
            for (int i = quiet; i < pcm.length; i++) assertTrue(Math.abs(pcm[i]) < 0.05 * Short.MAX_VALUE, c[0] + " кг в " + c[1] + " м: хвост " + pcm[i] + " на " + i);
        }
    }
}
