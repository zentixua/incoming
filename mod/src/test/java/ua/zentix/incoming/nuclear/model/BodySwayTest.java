package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodySwayTest {
    /** Скоростной напор за фронтом давлением {@code psi}, Па. */
    private static double q(double psi) {
        return 1000 * BlastModel.dynamicPressureKpa(BlastModel.kpa(psi));
    }

    private static double peakDeg(double psi, double tPlus) {
        return Math.toDegrees(BodySway.peak(BodySway.tilt(q(psi), tPlus), 0));
    }

    /** Далёкая волна (0,1 psi — ветер 1,7 м/с на секунды) тело не двигает: меньше десятой градуса. */
    @Test
    void farWaveLeavesTheBodyStill() {
        for (double tPlus : new double[]{0.3, 1, 2}) {
            assertTrue(peakDeg(0.1, tPlus) < 0.1, "0,1 psi, фаза " + tPlus + " с: " + peakDeg(0.1, tPlus));
        }
        assertEquals(0, BodySway.tilt(0, 1).length);
        assertEquals(0, BodySway.tilt(q(0.01), 1).length);
    }

    /**
     * 1 psi (ветер 16 м/с, Бофорт 7–8 «мешает идти», на фазу волны) качает на градусы, но человек стоит: у 1–2 кт (фаза
     * 0,5–0,6 с) — в пределах устойчивости и назад (4,5°), у 15 кт (1,2 с) — только спиной к волне (8° вперёд), лицом к
     * ней он отступает на шаг. С 2 psi (ветер 31 м/с, порыв урагана) — за пределом и вперёд: человек шагает или падает.
     */
    @Test
    void onePsiSwaysTwoPsiKnocksDown() {
        for (double tPlus : new double[]{0.3, 0.5, 0.6}) {
            double a = peakDeg(1, tPlus);
            assertTrue(a > 0.5 && a < Math.toDegrees(BodySway.BACK), "1 psi, фаза " + tPlus + " с: " + a);
        }
        double a15 = peakDeg(1, 1.2);
        assertTrue(a15 > Math.toDegrees(BodySway.BACK) && a15 < Math.toDegrees(BodySway.FORWARD), "1 psi, фаза 1,2 с: " + a15);
        for (double tPlus : new double[]{0.5, 0.8, 1.5}) {
            double a = peakDeg(2, tPlus);
            assertTrue(a > Math.toDegrees(BodySway.FORWARD), "2 psi, фаза " + tPlus + " с: " + a);
        }
    }

    /** Сильнее напор и дольше фаза — больше наклон. */
    @Test
    void moreWindLeansMore() {
        double prev = 0;
        for (double psi = 0.2; psi <= 3; psi += 0.2) {
            double a = peakDeg(psi, 0.8);
            assertTrue(a > prev, psi + " psi");
            prev = a;
        }
        prev = 0;
        for (double tPlus = 0.1; tPlus <= 2; tPlus += 0.1) {
            double a = peakDeg(1, tPlus);
            assertTrue(a > prev, "фаза " + tPlus);
            prev = a;
        }
    }

    /**
     * Тело выпрямляется: наклон — по ветру, к концу стоит (меньше {@link BodySway#STILL}), и не дольше
     * {@link BodySway#LONGEST}; без скачков между отсчётами.
     */
    @Test
    void bodyStandsUpAgain() {
        for (double psi : new double[]{0.3, 1, 2, 5}) {
            double[] t = BodySway.tilt(q(psi), 0.8);
            assertTrue(t.length > BodySway.RATE && t.length <= BodySway.LONGEST * BodySway.RATE + 1, psi + " psi: " + t.length);
            assertTrue(Math.abs(t[t.length - 1]) < BodySway.STILL, psi + " psi: не выпрямилось");
            double most = 0;
            for (double v : t) most = Math.max(most, v);
            assertEquals(BodySway.peak(t, 0), most, 1e-12, psi + " psi: наклон не по ветру");
            assertEquals(0, t[0], 1e-12);
        }
    }
}
