package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChemicalFrontTest {
    /** Приход по закону: ∫dr/U(p(r)) мелким шагом напрямую. */
    private static double law(double rangeM, double kg, boolean surface) {
        int n = 200_000;
        double sum = 0, r0 = 1e-3;
        for (int i = 0; i < n; i++) {
            double a = r0 * Math.pow(rangeM / r0, (double) i / n), b = r0 * Math.pow(rangeM / r0, (i + 1.0) / n), m = Math.sqrt(a * b);
            sum += (b - a) / BlastModel.frontSpeed(BlastModel.overpressureKpa(m, kg, surface));
        }
        return sum + r0 / BlastModel.frontSpeed(BlastModel.overpressureKpa(r0 / 2, kg, surface));
    }

    /**
     * Фронт не обгоняет закон и не отстаёт от него: приход таблицы — интеграл 1/U по Кинни–Грэхему и Ренкину–Гюгонио (в
     * пределах 0,5 % и полутора миллисекунд), радиус — его обратная; фронт всегда не медленнее звука и вдали идёт со
     * скоростью звука.
     */
    @Test
    void frontFollowsTheLaw() {
        for (double kg : new double[]{3, 50, 450}) {
            for (boolean surface : new boolean[]{true, false}) {
                ChemicalFront f = ChemicalFront.of(kg, surface, 2500);
                for (double r : new double[]{5, 20, 50, 100, 300, 1000}) {
                    double law = law(r, kg, surface), got = f.arrivalSeconds(r);
                    assertEquals(law, got, Math.max(0.005 * law, 1.5e-3), kg + " кг, " + r + " м");
                    assertTrue(got <= r / BlastModel.SOUND_SPEED + 1e-9, "фронт медленнее звука: " + kg + " кг, " + r + " м");
                    assertEquals(r, f.radiusAt(got), 1e-6 * r + 1e-9);
                }
                // радиус во времени — тот же закон: фронт на радиусе закона, не впереди и не позади
                for (double r : new double[]{3, 10, 40, 150, 600}) {
                    double law = law(r, kg, surface);
                    assertEquals(r, f.radiusAt(law), 0.005 * r, kg + " кг, " + r + " м: радиус в миг закона");
                }
                double far = (f.radiusAt(8) - f.radiusAt(7)) / 1;
                assertEquals(BlastModel.SOUND_SPEED, far, 0.01 * BlastModel.SOUND_SPEED, kg + " кг: вдали — звук");
            }
        }
    }

    /** Толща сжатия за фронтом — метры у шахеда (t₊ × U): у 1 psi ~6 м фазы, у 0,2 psi ~6–7 м; оболочка тоньше радиуса. */
    @Test
    void shellIsThinAgainstTheRadius() {
        ChemicalFront f = ChemicalFront.of(50, true, 2500);
        for (double r : new double[]{20, 50, 100, 300}) {
            double l = ShockOptics.DECAY_SHARE * f.phaseLength(r);
            assertTrue(l > 0.5 && l < 0.15 * r, r + " м: толща " + l);
        }
    }
}
