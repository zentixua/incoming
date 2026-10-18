package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudModelTest {
    /**
     * «Грейбл», 15 кт на 160 м: верх над землёй (Hawthorne 1979, красные линии рис. 2 Blakeley et al., сняты с графика
     * 300 dpi, ±0,1 км). Закон — одна экспонента: на 15-й секунде она ниже замера на 11 %, с 30-й — в пределах 6 %.
     */
    @Test
    void grableRise() {
        double hob = 159.7, y = 15;
        double[][] seen = {{15, 1730}, {30, 2650}, {45, 3440}, {60, 4140}, {90, 5270}, {120, 6150}, {180, 7530}, {240, 8410},
                {300, 9030}, {360, 9480}, {420, 9770}, {480, 9920}, {570, 9970}};
        for (double[] p : seen) {
            double top = CloudModel.top(p[0], hob, y, false);
            assertEquals(p[1], top, p[1] * (p[0] < 30 ? 0.12 : 0.06), () -> p[0] + " с: " + top);
        }
        double end = CloudModel.top(1e5, hob, y, false);
        assertEquals(10_000, end, 100, "верх после стабилизации");
        // низ шапки с 5-й по 6,5-ю минуту — 6,1–6,2 км
        assertEquals(6_150, CloudModel.capBottom(1e5, hob, y, false), 300, "низ шапки");
        assertEquals(6_090, CloudModel.capBottom(390, hob, y, false), 6_090 * 0.06, "низ шапки на 6,5-й минуте");
    }

    /**
     * «Уосп», 1 кт на 232 м: верх над землёй по тем же замерам — в пределах 5 % с 15-й секунды до 8-й минуты; через 8
     * минут он ещё растёт — за последнюю минуту на ~4 м/с (5,02 → 5,27 км), а низ шапки на 6,5-й минуте — 3,2 км.
     */
    @Test
    void waspStillRisingAtEightMinutes() {
        double hob = 232.3, y = 1;
        double[][] seen = {{15, 680}, {30, 1040}, {60, 1600}, {90, 2150}, {120, 2600}, {150, 3010}, {180, 3370}, {240, 3940},
                {300, 4380}, {360, 4740}, {420, 5020}, {480, 5270}};
        for (double[] p : seen) {
            double top = CloudModel.top(p[0], hob, y, false);
            assertEquals(p[1], top, p[1] * 0.05, () -> p[0] + " с: " + top);
        }
        double v = CloudModel.riseSpeed(480, hob, y, false);
        assertTrue(v > 2 && v < 6, "через 8 минут ещё растёт: " + v + " м/с");
        assertEquals(3_190, CloudModel.capBottom(390, hob, y, false), 3_190 * 0.08, "низ шапки");
        assertTrue(CloudModel.stabilizationSeconds(y) > 480, "к 8-й минуте не стабилизировалось");
    }

    /**
     * 1 Мт (Glasstone и Dolan, табл. 2.12, «грубые средние»): верх через 0,3; 0,7; 1,1; 2,5 и 3,8 минуты — 2, 4, 6, 10 и
     * 12 миль над точкой подрыва. Там подъём почти ровный и встаёт у тропопаузы — экспонента расходится с ним до 18 %.
     */
    @Test
    void megatonRise() {
        double mile = 1609.344;
        double[][] table = {{18, 2}, {42, 4}, {66, 6}, {150, 10}, {228, 12}};
        for (double[] p : table) {
            double top = CloudModel.top(p[0], 0, 1000, false), seen = p[1] * mile;
            assertEquals(seen, top, seen * 0.2, () -> p[0] + " с: " + top);
        }
        // в первую минуту — в среднем ~134 м/с (300 миль в час)
        assertEquals(134, (CloudModel.top(60, 0, 1000, false) - CloudModel.top(0, 0, 1000, false)) / 60, 134 * 0.2);
    }

    @Test
    void stabilizedTop() {
        // над точкой подрыва: «Грейбл» и «Уосп» за вычетом высоты подрыва, мегатонны — Glasstone и Dolan (рис. 2.16)
        assertEquals(9_840, CloudModel.stabilizedTop(15), 1);
        assertEquals(5_660, CloudModel.stabilizedTop(1), 1);
        double mt = CloudModel.stabilizedTop(1000);
        assertTrue(mt >= 20_000 && mt <= 22_000, "1 Мт: " + mt);
        double tenMt = CloudModel.stabilizedTop(10_000);
        assertTrue(tenMt >= 28_000 && tenMt <= 32_000, "10 Мт: " + tenMt);
        double prev = 0;
        for (double y = 0.01; y < 1e5; y *= 1.2) {
            double h = CloudModel.stabilizedTop(y);
            assertTrue(h > prev, y + " кт");
            prev = h;
        }
    }

    @Test
    void capRadius() {
        double r15 = CloudModel.capRadius(15);
        assertTrue(r15 >= 1500 && r15 <= 2500);
        assertEquals(10000, CloudModel.capRadius(1000), 1000);
    }

    /**
     * 15 кт — ~6 минут (к 7 минутам «Грейбл» в пределах 3 % от последнего верха), 1 Мт к 10 минутам — в пределах 2 %
     * («около 10 минут», Glasstone и Dolan, §2.15).
     */
    @Test
    void stabilizationTime() {
        double s15 = CloudModel.stabilizationSeconds(15);
        assertTrue(s15 >= 330 && s15 <= 450, "15 кт: " + s15);
        assertTrue(CloudModel.top(600, 0, 1000, false) > 0.98 * CloudModel.top(1e5, 0, 1000, false), "1 Мт к 10 минутам");
        for (double y = 0.01; y < 1e5; y *= 1.2) {
            double ts = CloudModel.stabilizationSeconds(y);
            assertTrue(ts >= 3 * 121 - 1e-9 && ts <= 3 * 227 + 1e-9, y + " кт: " + ts);
        }
    }

    @Test
    void cloudRisesToTop() {
        for (double y : new double[] {0.1, 1, 15, 100, 1000, 10_000}) {
            for (double hob : new double[] {0, 240 * Math.cbrt(y)}) {
                boolean surface = hob == 0;
                double ts = CloudModel.stabilizationSeconds(y), top = hob + CloudModel.stabilizedTop(y);
                // в миг подрыва — верх шара наибольшего размера
                assertEquals(hob + 1.3 * FireballModel.maxRadius(y, surface), CloudModel.top(0, hob, y, surface), 1e-9);
                assertEquals(top, CloudModel.top(ts, hob, y, surface), top * 0.06, () -> y + " кт");
                double prev = -1;
                for (double t = 0; t < 4 * ts; t += 5) {
                    double h = CloudModel.top(t, hob, y, surface);
                    assertTrue(h > prev, y + " кт, " + t + " с");
                    assertTrue(CloudModel.capBottom(t, hob, y, surface) < h);
                    prev = h;
                }
            }
        }
        assertEquals(0.6, CloudModel.capBottom(1e5, 0, 15, false) / CloudModel.top(1e5, 0, 15, false), 1e-6);
    }

    @Test
    void wilson() {
        assertFalse(CloudModel.wilsonCloud(0));
        assertTrue(CloudModel.wilsonCloud(0.4));
        assertTrue(CloudModel.wilsonCloud(1));
    }
}
