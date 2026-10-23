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
        return BodySway.tilt(1000 * BlastModel.kpa(psi), q(psi), 1, tPlus, BodySway.FORWARD, BodySway.BACK);
    }

    private static double peakDeg(double psi, double tPlus) {
        return Math.toDegrees(BodySway.peak(tilt(psi, tPlus), 0));
    }

    /** Положительная фаза на земле, где давление {@code psi}, у воздушного подрыва {@code kt} на лучшей высоте, с. */
    private static double phase(double psi, double kt) {
        double h = Yield.optimalBurstHeight(kt);
        return HobBlast.positivePhaseSeconds(HobBlast.rangeFor(psi, h, kt), h, kt);
    }

    /**
     * Далёкая волна (0,1 psi — ветер 1,7 м/с на секунды) тело почти не двигает: сам удар фронта (1 Н·с) клонит меньше чем
     * на полградуса.
     */
    @Test
    void farWaveLeavesTheBodyStill() {
        for (double tPlus : new double[]{0.3, 1, 2}) {
            assertTrue(peakDeg(0.1, tPlus) < 0.5, "0,1 psi, фаза " + tPlus + " с: " + peakDeg(0.1, tPlus));
        }
        assertEquals(0, tilt(0, 1).length);
        assertTrue(peakDeg(0.01, 1) < 0.05);
    }

    /**
     * 1 psi (ветер 16 м/с, Бофорт 7–8 «мешает идти», на фазу волны, и удар фронта — 11 Н·с) качает на градусы: у 1–2 кт
     * (фаза 0,3–0,6 с) спиной к волне человек стоит (до 8° вперёд), у 15 кт (1,3 с) — тоже, а лицом к ней (4,5° назад) —
     * отступает на шаг. С 2 psi (ветер 31 м/с, порыв урагана) — до границы и вперёд: человек шагает.
     */
    @Test
    void onePsiSwaysTwoPsiKnocksDown() {
        for (double tPlus : new double[]{0.3, 0.5, 0.6}) {
            double a = peakDeg(1, tPlus);
            assertTrue(a > 2 && a < Math.toDegrees(BodySway.FORWARD), "1 psi, фаза " + tPlus + " с: " + a);
        }
        double a15 = peakDeg(1, phase(1, 15));
        assertTrue(a15 > Math.toDegrees(BodySway.BACK) && a15 < Math.toDegrees(BodySway.FORWARD), "1 psi, 15 кт: " + a15);
        double face = Math.toDegrees(BodySway.peak(BodySway.tilt(1000 * BlastModel.kpa(1), q(1), 1, phase(1, 15), BodySway.BACK, BodySway.FORWARD), 0));
        assertEquals(Math.toDegrees(BodySway.BACK), face, 1e-3, "1 psi, 15 кт, лицом к волне");
        for (double tPlus : new double[]{0.5, 0.8, 1.5}) {
            // край — между отсчётами (шаг счёта 1 мс, отсчёт — 10 мс)
            assertEquals(Math.toDegrees(BodySway.FORWARD), peakDeg(2, tPlus), 1e-3, "2 psi, фаза " + tPlus + " с");
        }
    }

    /**
     * Фронт бьёт разом (жалоба 18.10: камера при приходе волны плавно клонилась): скорость наклона — сразу, в первые
     * 10 мс, а не нарастает с нуля за десятые доли секунды, как от одного ветра (ветер потом разгоняет тело дальше). Удар — по нагрузке при обтекании у
     * Glasstone и Dolan: у 1 psi (U = 350 м/с, отражённое 14,2 кПа, напор 0,17 кПа) — 0,6 м² × ((14,2 − 6,9 − 0,17)·0,3/350
     * + 6,9·0,65/350) ≈ 11,3 Н·с; вдоль земли — доля cos α, у напора вдоль земли — cos²α.
     */
    @Test
    void frontHitsAtOnce() {
        double p = 1000 * BlastModel.kpa(1);
        assertEquals(11.3, BodySway.jolt(p, q(1)), 0.2);
        assertEquals(BodySway.jolt(p, q(1)) * Math.sqrt(0.5), BodySway.jolt(p, q(1) / 2), 1e-9);
        assertEquals(0, BodySway.jolt(0, q(1)));
        // за стеной (половина лучей к взрыву открыта) — половина удара, доля вдоль земли та же
        double[] open = tilt(1, 0.5), half = BodySway.tilt(p, q(1), 0.5, 0.5, BodySway.FORWARD, BodySway.BACK);
        assertEquals(open[1] / 2, half[1], 1e-12);
        for (double psi : new double[]{0.3, 1, 3, 10}) {
            double[] t = tilt(psi, phase(psi, 15)), wind = BodySway.tilt(0, q(psi), 1, phase(psi, 15), BodySway.FORWARD, BodySway.BACK);
            // скорость удара — с первого отсчёта; ветер потом ещё разгоняет тело, но с места оно трогается не с нуля
            double hit = BodySway.jolt(1000 * BlastModel.kpa(psi), q(psi)) * BodySway.HEIGHT / BodySway.INERTIA / BodySway.RATE;
            assertTrue(t[1] >= 0.95 * hit && t[1] >= 0.25 * fastest(t), psi + " psi: удар не сразу, " + Math.toDegrees(t[1]) * BodySway.RATE + "°/с");
            // одним ветром наклон с места набирает скорость плавно — как было
            if (psi <= 3) assertTrue(wind[1] < 0.1 * fastest(wind), psi + " psi, ветер");
        }
    }

    /** Наибольшая скорость наклона по ветру между отсчётами, рад за отсчёт. */
    private static double fastest(double[] t) {
        double m = 0;
        for (int i = 1; i < t.length; i++) m = Math.max(m, t[i] - t[i - 1]);
        return m;
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
                double[] t = BodySway.tilt(1000 * BlastModel.kpa(psi), q(psi), 1, tPlus, e[0], e[1]);
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
        for (double psi = 0.2; peakDeg(psi, 0.8) < Math.toDegrees(BodySway.FORWARD) - 1e-3; psi += 0.1) {
            double a = peakDeg(psi, 0.8);
            assertTrue(a > prev, psi + " psi");
            prev = a;
        }
        prev = 0;
        for (double tPlus = 0.1; tPlus <= 2 && peakDeg(1, tPlus) < Math.toDegrees(BodySway.FORWARD) - 1e-3; tPlus += 0.1) {
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

    /** Наклон стоящего от наземного взрыва заряда {@code kg} на {@code m} метрах лицом к взрыву, °: тот же маятник, давление и фаза — Кинни–Грэхем. */
    private static double conventional(double kg, double m) {
        double pa = 1000 * BlastModel.surfaceOverpressureKpa(m, kg), q = 1000 * BlastModel.dynamicPressureKpa(pa / 1000);
        return Math.toDegrees(BodySway.peak(BodySway.tilt(pa, q, 1, BlastModel.chemicalPhaseSeconds(m, kg, true), BodySway.BACK, BodySway.FORWARD), 0));
    }

    /**
     * Обычный взрыв толкает одним резким ударом с приходом фронта (фаза — миллисекунды, ветер за ней почти ничего не
     * добавляет): шахед в 20 м сбивает с ног (наклон — до края устойчивости), в 300 м — десятые доли градуса, как
     * обычное покачивание стоящего.
     */
    @Test
    void shahedKnocksDownAtTwentyMetresAndBarelyTouchesAtThreeHundred() {
        assertEquals(Math.toDegrees(BodySway.BACK), conventional(50, 20), 1e-3);
        double far = conventional(50, 300);
        assertTrue(far < 0.5, "шахед в 300 м: " + far + "°");
        double pa = 1000 * BlastModel.surfaceOverpressureKpa(60, 50), q = 1000 * BlastModel.dynamicPressureKpa(pa / 1000);
        double[] t = BodySway.tilt(pa, q, 1, BlastModel.chemicalPhaseSeconds(60, 50, true), BodySway.FORWARD, BodySway.BACK);
        // удар — сразу: скорость наклона с первого отсчёта, ветер за фазой в 16 мс её почти не прибавляет
        assertTrue(t.length > 1 && t[1] > 0);
        double hitOnly = BodySway.jolt(pa, q, BlastModel.chemicalPhaseSeconds(60, 50, true)) * 0.83 / 66;
        assertEquals(hitOnly, t[1] * BodySway.RATE, 0.25 * hitOnly);
    }

    /** Напор по фазе — среднее за шаг из первообразной: совпадает с численным интегралом, и за фазой меняет знак. */
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
        // удар у долгой волны — прежний
        assertEquals(BodySway.jolt(6895, q(1)), BodySway.jolt(6895, q(1), 1e9), 1e-6);
    }

    /** Тело и голова у наземного взрыва {@code kg} на {@code m} метрах, спиной к взрыву (толкает вперёд). */
    private static BodySway.Motion motion(double kg, double m) {
        double pa = 1000 * BlastModel.surfaceOverpressureKpa(m, kg), q = 1000 * BlastModel.dynamicPressureKpa(pa / 1000);
        double tp = BlastModel.chemicalPhaseSeconds(m, kg, true);
        return BodySway.motion(pa, q, 1, tp, tp, BodySway.FORWARD, BodySway.BACK);
    }

    /**
     * Голова на шее (жалоба 23.10: «камера не качается»): плечи уходят с ударом, голова отстаёт — сперва откидывается
     * назад против толчка, потом качается около тела и затухает; камера (тело + шея) качается не один раз, и каждый
     * следующий размах меньше прошлого. Без скачков: шаг между отсчётами не больше скорости удара на шаг. Тело от
     * шеи не меняется ({@link BodySway#tilt} — то же тело).
     */
    @Test
    void headRocksOnTheNeckAndSettles() {
        for (double[] c : new double[][]{{3, 50}, {50, 50}, {50, 100}, {50, 300}, {450, 100}, {450, 300}}) {
            BodySway.Motion m = motion(c[0], c[1]);
            String what = c[0] + " кг, " + c[1] + " м";
            double[] body = m.body(), neck = m.neck(), head = new double[body.length];
            assertEquals(body.length, neck.length);
            for (int i = 0; i < head.length; i++) head[i] = body[i] + neck[i];
            double pa = 1000 * BlastModel.surfaceOverpressureKpa(c[1], c[0]), q = 1000 * BlastModel.dynamicPressureKpa(pa / 1000);
            double tp = BlastModel.chemicalPhaseSeconds(c[1], c[0], true);
            double[] tilt = BodySway.tilt(pa, q, 1, tp, BodySway.FORWARD, BodySway.BACK);
            for (int i = 0; i < Math.min(tilt.length, body.length); i++) assertEquals(tilt[i], body[i], 1e-15, what + ": тело");
            assertTrue(head[1] < 0, what + ": голова сперва отстаёт назад");
            // размахи камеры: экстремумы между переходами через ноль
            java.util.List<Double> swings = new java.util.ArrayList<>();
            double extreme = 0;
            for (int i = 1; i < head.length; i++) {
                if (Math.signum(head[i]) != Math.signum(head[i - 1]) && extreme != 0) {
                    swings.add(Math.abs(extreme));
                    extreme = 0;
                }
                if (Math.abs(head[i]) > Math.abs(extreme)) extreme = head[i];
            }
            assertTrue(swings.size() >= 3, what + ": качается, размахов " + swings.size());
            for (int k = 2; k < swings.size(); k++) assertTrue(swings.get(k) < swings.get(k - 1), what + ": затухает " + swings);
            assertTrue(Math.abs(body[body.length - 1]) < BodySway.STILL && Math.abs(neck[neck.length - 1]) < BodySway.STILL, what + ": успокоилась");
            double kick = Math.abs(head[1] - head[0]);
            for (int i = 2; i < head.length; i++) assertTrue(Math.abs(head[i] - head[i - 1]) <= 1.05 * kick, what + ": скачок на " + i);
            assertTrue(head.length <= BodySway.LONGEST * BodySway.RATE + 1);
        }
    }

    /**
     * Сколько качает: у шахеда (50 кг) в 100 м камера уходит на 1–4°, в 300 м — на десятые доли градуса; ближе и у
     * большего заряда — больше; качание кончается за 1–6 с (до сотой градуса).
     */
    @Test
    void headSwingGrowsWithTheBlast() {
        double near = head(motion(50, 50)), mid = head(motion(50, 100)), far = head(motion(50, 300)), big = head(motion(450, 100));
        assertTrue(mid > 1 && mid < 4, "шахед 100 м: " + mid);
        assertTrue(far > 0.2 && far < 1.5, "шахед 300 м: " + far);
        assertTrue(near > mid && mid > far && big > mid, near + " " + mid + " " + far + " " + big);
        for (double[] c : new double[][]{{50, 50}, {50, 300}}) {
            double seconds = motion(c[0], c[1]).body().length / (double) BodySway.RATE;
            assertTrue(seconds > 1 && seconds < 6, c[1] + " м: " + seconds + " с");
        }
    }

    private static double head(BodySway.Motion m) {
        double most = 0;
        for (int i = 0; i < m.body().length; i++) most = Math.max(most, Math.abs(m.body()[i] + m.neck()[i]));
        return Math.toDegrees(most);
    }
}
