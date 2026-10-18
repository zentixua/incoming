package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireballModelTest {
    @Test
    void maxRadius() {
        assertEquals(207, FireballModel.maxRadius(15, false), 3);
        assertEquals(261, FireballModel.maxRadius(15, true), 3);
        assertEquals(1400, FireballModel.maxRadius(1000, true), 20);
    }

    @Test
    void doubleFlashTimes() {
        assertEquals(0.12, FireballModel.secondMaximumSeconds(15), 0.01);
        assertEquals(1.0, FireballModel.secondMaximumSeconds(1000), 0.02);
        assertEquals(0.0097, FireballModel.firstMinimumSeconds(15), 0.0005);
    }

    /**
     * Видимая температура: во втором максимуме — T₂, после — остывает к воздуху по хронике (15 кт — около 4 000 K через
     * секунду, около 1 950 K через девять); до него — Стефан–Больцман по яркости и радиусу: первый импульс горячее T₂,
     * в минимуме холоднее первого импульса, ко второму максимуму — без скачка.
     */
    @Test
    void temperatureFollowsTheFlashAndTheFootage() {
        double y = 15, tMin = FireballModel.firstMinimumSeconds(y), tMax = FireballModel.secondMaximumSeconds(y);
        assertEquals(FireballModel.PEAK_K, FireballModel.temperature(tMax, y), 1e-9);
        assertEquals(FireballModel.temperature(tMax * (1 - 1e-9), y), FireballModel.temperature(tMax * (1 + 1e-9), y), 0.01);
        double t1 = FireballModel.temperature(1, y), t9 = FireballModel.temperature(9, y);
        assertTrue(t1 > 3_600 && t1 < 4_400, "1 с: " + t1);
        assertTrue(t9 > 1_750 && t9 < 2_150, "9 с: " + t9);
        double first = 0;
        for (double t = 1e-6; t < tMin / 3; t *= 1.1) first = Math.max(first, FireballModel.temperature(t, y));
        assertTrue(first > FireballModel.PEAK_K, "первый импульс " + first);
        assertTrue(FireballModel.temperature(tMin, y) < first, "минимум " + FireballModel.temperature(tMin, y));
        assertEquals(FireballModel.AMBIENT_K, FireballModel.temperature(0, y), 0);
        double last = Double.MAX_VALUE;
        for (double t = tMax; t < 1e4; t *= 1.05) {
            double k = FireballModel.temperature(t, y);
            assertTrue(k < last && k > FireballModel.AMBIENT_K, t + " с: " + k);
            last = k;
        }
    }

    @Test
    void doubleFlashShape() {
        double y = 15;
        double tMin = FireballModel.firstMinimumSeconds(y), tMax = FireballModel.secondMaximumSeconds(y);
        double first = 0;
        for (double t = 1e-6; t < tMin / 3; t *= 1.1) first = Math.max(first, FireballModel.brightness(t, y));
        double min = FireballModel.brightness(tMin, y);
        double second = FireballModel.brightness(tMax, y);
        assertTrue(first > 0.9, "первый импульс " + first);
        assertTrue(min < 0.15, "минимум " + min);
        assertTrue(second > 0.7 && second < first, "второй максимум " + second);
        assertTrue(FireballModel.brightness(1000 * tMax, y) < 0.01);
        assertEquals(0, FireballModel.brightness(0, y), 0);
    }

    @Test
    void mostLightWithinTenMaxima() {
        double y = 15, tMax = FireballModel.secondMaximumSeconds(y);
        double within = integral(y, tMin(y), 10 * tMax), total = integral(y, tMin(y), 1e5 * tMax);
        assertEquals(0.8, within / total, 0.06);
    }

    @Test
    void radiusGrowsThenCools() {
        double y = 15, tMax = FireballModel.secondMaximumSeconds(y), max = FireballModel.maxRadius(y, false);
        assertEquals(max, FireballModel.radius(tMax, y, false), 1e-6);
        assertTrue(FireballModel.radius(tMax / 10, y, false) < max);
        assertTrue(FireballModel.radius(10, y, false) > max);
        assertTrue(FireballModel.radius(1e4, y, false) <= 1.3 * max + 1e-9);
    }

    /**
     * Шар всплывает, как верх облака (один закон): с высоты подрыва со скоростью верха — у 15 кт ~79 м/с, у 1 Мт ~139
     * (Glasstone и Dolan, табл. 2.12: в первую минуту в среднем ~134), — и тормозит: к стабилизации (3τ) — до двадцатой доли;
     * его верх не выше верха облака.
     */
    @Test
    void risesAndSlowsDown() {
        for (double y : new double[]{1, 15, 1000}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 240 * Math.cbrt(y);
                assertEquals(hob, FireballModel.centreHeight(0, hob, y, surface), 0);
                double v0 = FireballModel.centreHeight(1, hob, y, surface) - hob;
                assertEquals(FireballModel.riseSpeed(y, surface), v0, 0.01 * v0);
                assertEquals(CloudModel.riseSpeed(0, hob, y, surface), FireballModel.riseSpeed(y, surface), 1e-9);
                double stab = CloudModel.stabilizationSeconds(y);
                double late = FireballModel.centreHeight(stab, hob, y, surface) - FireballModel.centreHeight(stab - 1, hob, y, surface);
                assertTrue(late < v0 / 15, y + " кт");
                for (double t = 0.01; t < 1e4; t *= 1.1) {
                    double ballTop = FireballModel.centreHeight(t, hob, y, surface) + FireballModel.radius(t, y, surface);
                    assertTrue(ballTop <= CloudModel.top(t, hob, y, surface) + 1e-6, y + " кт, " + t + " с: шар выше облака");
                }
            }
        }
        double v15 = FireballModel.riseSpeed(15, false);
        assertTrue(v15 > 70 && v15 < 90, "15 кт: " + v15);
        assertEquals(134, FireballModel.riseSpeed(1000, false), 134 * 0.1);
    }

    private static double tMin(double y) {
        return FireballModel.firstMinimumSeconds(y);
    }

    /** Интеграл яркости по логарифмической сетке (без первого импульса — от минимума). */
    private static double integral(double y, double from, double to) {
        int n = 20000;
        double step = Math.log(to / from) / n, s = 0;
        for (int i = 0; i < n; i++) {
            double t = from * Math.exp(step * (i + 0.5));
            s += FireballModel.brightness(t, y) * t * step;
        }
        return s;
    }
}
