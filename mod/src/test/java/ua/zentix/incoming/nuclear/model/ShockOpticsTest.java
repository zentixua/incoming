package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Свет сквозь фронт ударной волны ({@link ShockOptics}): тонкая оболочка против прямого интеграла, рост с давлением, край. */
class ShockOpticsTest {
    private static final double N0 = 2.77e-4;

    /** Путь одного пересечения прямым интегралом: ∫ δn·e^(−(R − r)/L) ds по половине хорды, r = √(b² + s²). */
    private static double integral(double b, double r, double l, double dn) {
        if (b >= r) return 0;
        double end = Math.sqrt(r * r - b * b);
        // оболочка — у конца хорды: шаги гуще там
        int n = 400_000;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double u0 = (double) i / n, u1 = (double) (i + 1) / n;
            double s0 = end * (1 - (1 - u0) * (1 - u0)), s1 = end * (1 - (1 - u1) * (1 - u1)), s = 0.5 * (s0 + s1);
            sum += dn * Math.exp(-(r - Math.sqrt(b * b + s * s)) / l) * (s1 - s0);
        }
        return sum;
    }

    /** Приближение тонкой оболочки: ошибка — порядка L/R (у r·dr взято R, у r² − b² отброшено (R − r)²). */
    @Test
    void thinShellPathIsTheIntegral() {
        for (double l : new double[]{5, 20, 100}) {
            double r = 2000, dn = 6e-5, top = dn * Math.sqrt(2 * r * l) * 0.5410442;
            for (double k : new double[]{0, 0.5, 0.9, 0.98, 0.99, 0.995, 0.999, 0.9999, 1}) {
                double b = k * r;
                assertEquals(integral(b, r, l, dn), ShockOptics.path(b, r, l, dn), (0.01 + 1.5 * l / r) * top, "L = " + l + ", b = " + b);
            }
        }
    }

    @Test
    void dawsonIsItsIntegral() {
        for (double x = 0; x <= 12; x += 0.05) {
            int n = 20_000;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                double t = (i + 0.5) / n * x;
                sum += Math.exp(t * t - x * x) * x / n;
            }
            assertEquals(sum, ShockOptics.dawson(x), 2e-5 * Math.max(sum, 1e-3), "x = " + x);
        }
        // за 10 — асимптота, без скачка на шве
        assertEquals(ShockOptics.dawson(10 - 1e-9), ShockOptics.dawson(10 + 1e-9), 1e-5 * ShockOptics.dawson(10));
    }

    @Test
    void densityJumpIsRankineHugoniot() {
        assertEquals(1.048, BlastModel.densityRatio(BlastModel.kpa(1)), 0.001);
        assertEquals(1.232, BlastModel.densityRatio(BlastModel.kpa(5)), 0.001);
        assertEquals(1.443, BlastModel.densityRatio(BlastModel.kpa(10)), 0.001);
        assertEquals(6, BlastModel.densityRatio(1e9), 1e-3);
        assertEquals(0, ShockOptics.indexJump(0, N0));
    }

    /** Сила преломления растёт с перепадом давления при той же геометрии, на любом месте у края и внутри. */
    @Test
    void bendGrowsWithPressure() {
        double r = 2000, l = 20;
        for (double w : new double[]{0.05, 2, 30}) {
            for (double k : new double[]{0.9, 0.99, 0.999, 0.99995}) {
                double was = 0;
                for (double kpa = 0.5; kpa < 5000; kpa *= 1.2) {
                    double bend = ShockOptics.bend(k * r, r, l, ShockOptics.indexJump(kpa, N0), w);
                    assertTrue(Math.abs(bend) >= was - 1e-15, "b = " + k * r + ", w = " + w + ", " + kpa + " кПа");
                    was = Math.abs(bend);
                }
                assertTrue(was > 0);
            }
        }
    }

    /** За фронтом (луч мимо сферы и пиксель целиком мимо) — ничего; у края — к середине. */
    @Test
    void nothingBeyondTheFront() {
        double r = 2000, l = 20, dn = ShockOptics.indexJump(BlastModel.kpa(5), N0);
        for (double w : new double[]{0.01, 1, 10}) {
            assertEquals(0, ShockOptics.bend(r + 0.5 * w, r, l, dn, w));
            assertEquals(0, ShockOptics.bend(r + 100, r, l, dn, w));
            assertTrue(ShockOptics.bend(r - 0.5 * w, r, l, dn, w) > 0);
        }
    }

    /** Оценка сверху {@link ShockOptics#most} не меньше отклонения любого пикселя (на два пересечения). */
    @Test
    void mostBoundsEveryPixel() {
        double r = 3000, pixel = 1e-3;
        for (double kpa : new double[]{2, 30, 300}) {
            double dn = ShockOptics.indexJump(kpa, N0);
            for (double l : new double[]{2, 40, 600}) {
                for (double near : new double[]{0.5, 20, 900}) {
                    double most = ShockOptics.most(r, dn, near, pixel), w = pixel * near;
                    for (double u = 0; u < 3 * l + 3 * w; u += Math.max(w, l) / 50) {
                        double bend = 2 * Math.abs(ShockOptics.bend(r - u, r, l, dn, w));
                        assertTrue(bend <= most * (1 + 1e-9), kpa + " кПа, L " + l + ", w " + w + ", u " + u + ": " + bend + " > " + most);
                    }
                }
            }
        }
    }

    /** Пример: 15 кт, фронт 5 psi в 2 км, глаз в 3 км, пиксель 1 мрад — кольцо в пиксели. */
    @Test
    void frontAtFivePsiIsSeenAsPixels() {
        double r = 2000, dn = ShockOptics.indexJump(BlastModel.kpa(5), N0), w = 1e-3 * Math.sqrt(3000 * 3000 - r * r);
        double px = 2 * ShockOptics.bend(r - 0.5 * w, r, 20, dn, w) / 1e-3;
        assertTrue(px > 2 && px < 10, "сдвиг у края " + px + " px");
    }
}
