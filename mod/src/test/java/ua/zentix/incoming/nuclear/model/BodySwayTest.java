package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodySwayTest {
    /** Скоростной напор за фронтом давлением {@code psi}, Па. */
    private static double q(double psi) {
        return 1000 * BlastModel.dynamicPressureKpa(BlastModel.kpa(psi));
    }

    /** Наклон спиной к волне: по ветру — вперёд, назад — к взрыву. */
    private static double[] tilt(double psi, double tPlus) {
        return BodySway.tilt(q(psi), tPlus, BodySway.FORWARD, BodySway.BACK);
    }

    private static double peakDeg(double psi, double tPlus) {
        return Math.toDegrees(BodySway.peak(tilt(psi, tPlus), 0));
    }

    /** Положительная фаза на земле, где давление {@code psi}, у воздушного подрыва {@code kt} на лучшей высоте, с. */
    private static double phase(double psi, double kt) {
        double h = Yield.optimalBurstHeight(kt);
        return HobBlast.positivePhaseSeconds(HobBlast.rangeFor(psi, h, kt), h, kt);
    }

    /** Далёкая волна (0,1 psi — ветер 1,7 м/с на секунды) тело не двигает: меньше десятой градуса. */
    @Test
    void farWaveLeavesTheBodyStill() {
        for (double tPlus : new double[]{0.3, 1, 2}) {
            assertTrue(peakDeg(0.1, tPlus) < 0.1, "0,1 psi, фаза " + tPlus + " с: " + peakDeg(0.1, tPlus));
        }
        assertEquals(0, tilt(0, 1).length);
        assertEquals(0, tilt(0.01, 1).length);
    }

    /**
     * 1 psi (ветер 16 м/с, Бофорт 7–8 «мешает идти», на фазу волны) качает на градусы, но человек стоит: у 1–2 кт (фаза
     * 0,5–0,65 с) — в пределах устойчивости и назад (4,5°), у 15 кт (1,3 с) — только спиной к волне (8° вперёд), лицом к
     * ней он отступает на шаг. С 2 psi (ветер 31 м/с, порыв урагана) — до границы и вперёд: человек шагает.
     */
    @Test
    void onePsiSwaysTwoPsiKnocksDown() {
        for (double tPlus : new double[]{0.3, 0.5, 0.6}) {
            double a = peakDeg(1, tPlus);
            assertTrue(a > 0.5 && a < Math.toDegrees(BodySway.BACK), "1 psi, фаза " + tPlus + " с: " + a);
        }
        double a15 = peakDeg(1, phase(1, 15));
        assertTrue(a15 > Math.toDegrees(BodySway.BACK) && a15 < Math.toDegrees(BodySway.FORWARD), "1 psi, 15 кт: " + a15);
        for (double tPlus : new double[]{0.5, 0.8, 1.5}) {
            // край — между отсчётами (шаг счёта 1 мс, отсчёт — 10 мс)
            assertEquals(Math.toDegrees(BodySway.FORWARD), peakDeg(2, tPlus), 1e-3, "2 psi, фаза " + tPlus + " с");
        }
    }

    /**
     * За границей человек шагает: наклон стоит на ней, пока ветер сильнее равновесия, и тело выпрямляется, не
     * перелетая вертикаль, пока ветер дует по фазе. Назад к взрыву его клонит потом только обратный ветер разрежения
     * (до 13,5 % давления фронта, напор — 1,8 %): у 2–3 psi — на градусы, у 5 psi и сильнее — сильнее, уже после фазы
     * (спиной к волне — до края назад 4,5°). Лицом к волне — то же с границами наоборот.
     */
    @Test
    void stepsAtTheEdge() {
        for (double[] e : new double[][]{{BodySway.FORWARD, BodySway.BACK}, {BodySway.BACK, BodySway.FORWARD}}) {
            for (double psi : new double[]{2, 3, 5, 10, 20}) {
                double tPlus = phase(psi, 15);
                double[] t = BodySway.tilt(q(psi), tPlus, e[0], e[1]);
                int held = 0, top = -1, low = 0;
                for (int i = 0; i < t.length; i++) {
                    if (t[i] == e[0]) {
                        held++;
                        if (top < 0) top = i;
                    }
                    if (t[i] < t[low]) low = i;
                    if (i < tPlus * BodySway.RATE) assertTrue(t[i] >= 0, psi + " psi: перелетело вертикаль в фазе, " + i);
                }
                String what = psi + " psi, край " + Math.toDegrees(e[0]) + "°";
                assertTrue(held > 0 && top < tPlus * BodySway.RATE, what + ": края нет");
                assertTrue(t[low] >= -e[1], what);
                if (psi <= 3) assertTrue(t[low] > -Math.toRadians(2), what + ": назад " + Math.toDegrees(t[low]));
                else assertTrue(low > 1.5 * tPlus * BodySway.RATE, what + ": назад раньше разрежения, " + low);
            }
        }
    }

    /** Сильнее напор и дольше фаза — больше наклон (пока он не дошёл до края). */
    @Test
    void moreWindLeansMore() {
        double prev = 0;
        for (double psi = 0.2; peakDeg(psi, 0.8) < Math.toDegrees(BodySway.FORWARD); psi += 0.1) {
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
            double[] t = tilt(psi, 0.8);
            assertTrue(t.length > BodySway.RATE && t.length <= BodySway.LONGEST * BodySway.RATE + 1, psi + " psi: " + t.length);
            assertTrue(Math.abs(t[t.length - 1]) < BodySway.STILL, psi + " psi: не выпрямилось");
            double most = 0;
            for (double v : t) most = Math.max(most, v);
            assertEquals(BodySway.peak(t, 0), most, 1e-12, psi + " psi: наклон не по ветру");
            assertEquals(0, t[0], 1e-12);
        }
    }
}
