package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastLitterTest {
    /** Срыв — порог дёрна закона пыли: ниже порогового давления не срывает, выше — срывает; порог около 1 psi. */
    @Test
    void frontTearsTheCoverAboveOneThreshold() {
        double from = 0;
        for (double psi = 0.05; psi < 20; psi *= 1.05) {
            boolean tears = BlastLitter.tearsCover(BlastModel.kpa(psi), 1);
            if (tears && from == 0) from = psi;
            if (from > 0) assertTrue(tears, psi + " psi: срыв не монотонен");
        }
        assertTrue(from > 0.5 && from < 2, "порог " + from + " psi");
        assertFalse(BlastLitter.tearsCover(BlastModel.kpa(5), 0), "волна сверху вдоль земли не дует");
        assertEquals(DustModel.Surface.COVERED.threshold(), BlastLitter.friction(BlastModel.kpa(from), 1), 0.2);
    }

    /**
     * Бросок: у слабого ветра — интеграл сопротивления g/v_t²·∫u²dt = g/v_t²·U²t₊·(1 − e⁻²)/4 (в пределах процентов);
     * не быстрее ветра; растёт с ветром и фазой.
     */
    @Test
    void kickIsTheDragImpulse() {
        double vt = 1.7;
        for (double[] c : new double[][]{{2, 0.01}, {5, 0.005}}) {
            double small = 9.81 / (vt * vt) * c[0] * c[0] * c[1] * (1 - Math.exp(-2)) / 4;
            assertEquals(small, BlastLitter.kick(c[0], c[1], vt), 0.08 * small);
        }
        double last = 0;
        for (double u = 5; u < 300; u *= 1.5) {
            double v = BlastLitter.kick(u, 0.015, vt);
            assertTrue(v > last && v < u, u + " м/с: " + v);
            last = v;
        }
        assertTrue(BlastLitter.kick(50, 0.03, vt) > BlastLitter.kick(50, 0.01, vt));
    }
}
