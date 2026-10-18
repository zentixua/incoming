package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EyeInjuryTest {
    private static final double V = ThermalModel.VISIBILITY_CLEAR;

    /** На дальности рис. 12.88 ослепление — те самые 10 с, днём и ночью, у любой мощности рисунка. */
    @Test
    void figureRangeGivesTenSeconds() {
        for (double y : new double[]{1, 15, 300, 10_000}) {
            for (double dark : new double[]{0, 0.5, 1}) {
                assertEquals(EyeInjury.EDGE_S, EyeInjury.dazzleSeconds(EyeInjury.edgeFluence(y, dark), y, dark), 1e-9, y + " кт, темнота " + dark);
            }
        }
    }

    /** Ближе — дольше, но не дольше, чем глаз приходит в себя после полного выцветания; без света — ничего. */
    @Test
    void longerCloserUpToFullBleach() {
        assertEquals(0, EyeInjury.dazzleSeconds(0, 15, 0));
        assertEquals(0, EyeInjury.dazzleSeconds(1e-9, 15, 0));
        double prev = 0;
        for (double r = 40_000; r >= 300; r *= 0.9) {
            double t = EyeInjury.dazzleSeconds(ThermalModel.fluenceCalPerCm2(r, 15, false, V), 15, 0);
            assertTrue(t >= prev, "на " + r + " м: " + t + " < " + prev);
            prev = t;
        }
        assertEquals(EyeInjury.FULL_DAY_S, prev, 1e-9);
        assertEquals(EyeInjury.FULL_NIGHT_S, EyeInjury.dazzleSeconds(1000, 15, 1), 1e-9);
    }

    /** 15 кт: днём в 3 км — меньше минуты, ночью в 10 км — минуты (глаз, привыкший к темноте, засвечивается сильнее). */
    @Test
    void fifteenKilotons() {
        double day = EyeInjury.dazzleSeconds(ThermalModel.fluenceCalPerCm2(3000, 15, false, V), 15, 0);
        assertTrue(day > 30 && day < EyeInjury.FULL_DAY_S, "днём в 3 км: " + day);
        double night = EyeInjury.dazzleSeconds(ThermalModel.fluenceCalPerCm2(10_000, 15, false, V), 15, 1);
        assertTrue(night > 300 && night < EyeInjury.FULL_NIGHT_S, "ночью в 10 км: " + night);
        double q = ThermalModel.fluenceCalPerCm2(10_000, 15, false, V);
        assertTrue(EyeInjury.dazzleSeconds(q, 15, 1) > EyeInjury.dazzleSeconds(q, 15, 0));
    }

    /** Ожог сетчатки — по рис. 12.88 (10 кт днём 18,5 мили от эпицентра при подрыве на 10 000 футов), ночью дальше, в дымке ближе. */
    @Test
    void retinaRangeFollowsFigure() {
        assertEquals(Math.hypot(18.5 * 1609.344, 3048), EyeInjury.retinaRange(10, 0, V), 1);
        assertEquals(Math.hypot(30.5 * 1609.344, 3048), EyeInjury.retinaRange(10, 1, V), 1);
        assertTrue(EyeInjury.retinaRange(15, 1, V) > EyeInjury.retinaRange(15, 0, V));
        assertEquals(EyeInjury.retinaRange(15, 0, V) / 2, EyeInjury.retinaRange(15, 0, V / 2), 1e-6);
    }

    /** Свет у глаза: днём 15, ночью под открытым небом 4 (небо темнее на 11) — ночь, темнее — тоже. */
    @Test
    void darknessByLight() {
        assertEquals(0, EyeInjury.darkness(15));
        assertEquals(1, EyeInjury.darkness(4));
        assertEquals(5 / 11.0, EyeInjury.darkness(10), 1e-12);
        assertEquals(1, EyeInjury.darkness(0));
        assertEquals(1, EyeInjury.darkness(-3));
        assertEquals(0, EyeInjury.darkness(20));
    }
}
