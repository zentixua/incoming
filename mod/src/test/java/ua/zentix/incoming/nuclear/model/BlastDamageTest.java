package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ua.zentix.incoming.nuclear.world.BlockResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastDamageTest {
    /** Импульс Кинни–Грэхема против UFC 3-340-02 (рис. 2-7, свободный воздух): в пределах 20 %. */
    @ParameterizedTest
    @CsvSource({"1, 134", "10, 18"})
    void impulseMatchesUfc(double z, double scaledPas) {
        double i = BlastModel.chemicalImpulse(z, 1, false);
        assertEquals(scaledPas, i, 0.2 * scaledPas, "Z = " + z);
    }

    /** Импульс растёт как ∛W при той же приведённой дальности. */
    @Test
    void impulseScalesWithCubeRoot() {
        double w = 125;
        assertEquals(5 * BlastModel.chemicalImpulse(2, 1, false), BlastModel.chemicalImpulse(2 * 5, w, false), 1e-6);
    }

    /** Порог кладки (12 psi) и дерева (5 psi) у большого заряда — уровни A и B Джаррета (3,8 и 5,6), в пределах 5 %. */
    @Test
    void thresholdsAreJarrettLevels() {
        assertEquals(BlastDamage.JARRETT_A, BlastDamage.scaledRange(BlastModel.kpa(12)), 0.05 * BlastDamage.JARRETT_A);
        assertEquals(BlastDamage.JARRETT_B, BlastDamage.scaledRange(BlastModel.kpa(5)), 0.05 * BlastDamage.JARRETT_B);
    }

    /** Кривая давление–импульс даёт радиус Джаррета у зарядов оружия (3 кг — 2,4 т) с точностью таблицы. */
    @ParameterizedTest
    @CsvSource({"12, 3", "12, 50", "12, 150", "12, 2400", "5, 6.4", "5, 50", "5, 150", "2, 50", "0.8, 150"})
    void curveGivesJarrettRadius(double psi, double tnt) {
        double pc = BlastModel.kpa(psi);
        double lo = 0.05, hi = 1e4;
        for (int i = 0; i < 80; i++) {
            double m = Math.sqrt(lo * hi);
            if (BlastDamage.breaks(BlastModel.surfaceOverpressureKpa(m, tnt), BlastModel.chemicalImpulse(m, tnt, true), pc)) lo = m;
            else hi = m;
        }
        double jarrett = BlastDamage.breakRange(pc, tnt);
        assertEquals(jarrett, lo, 0.03 * jarrett, psi + " psi, " + tnt + " кг");
    }

    /** Радиусы паспорта: шахед сносит дерево в ~5 м, кладку в ~3,4 м; «Томагавк» — 11 и 7 м (Джаррет B и A). */
    @Test
    void passportRadii() {
        assertEquals(5.1, BlastDamage.breakRange(BlastModel.kpa(5), 50), 0.6);
        assertEquals(3.4, BlastDamage.breakRange(BlastModel.kpa(12), 50), 0.4);
        assertEquals(10.7, BlastDamage.breakRange(BlastModel.kpa(5), 150), 1.2);
        assertEquals(7.0, BlastDamage.breakRange(BlastModel.kpa(12), 150), 0.8);
    }

    /** У ядерной волны импульс на порядки выше кривой: ответ блока по давлению и импульсу тот же, что по одному давлению. */
    @Test
    void nuclearAnswerUnchanged() {
        double tnt = 0.5 * 15e6;
        for (float threshold : new float[]{0.8f, 1, 2, 4, 5, 8, 12, 15, 25}) {
            BlockResponse r = new BlockResponse(threshold, BlockResponse.Kind.BREAK);
            for (double range = 150; range < 6000; range *= 1.07) {
                double psi = BlastModel.psi(BlastModel.surfaceOverpressureKpa(range, tnt));
                double impulse = BlastModel.chemicalImpulse(range, tnt, true);
                for (int seed = 0; seed < 64; seed++) {
                    assertEquals(r.breaksAt(psi, seed), r.breaksAt(psi, impulse, seed), threshold + " psi на " + range + " м");
                }
            }
        }
    }

    /** Газы 10 кг в комнате 3×3×3: 2,25·(10/27)^0,72 МПа ≈ 1,1 МПа; без проёмов держатся, с окном 1 м² — ~0,13 с. */
    @Test
    void roomGasPressure() {
        assertEquals(1101, BlastDamage.quasiStaticKpa(10, 27), 5);
        assertTrue(Double.isInfinite(BlastDamage.ventSeconds(27, 0)));
        assertEquals(0.132, BlastDamage.ventSeconds(27, 1), 0.002);
        // газы шахеда в доме 5×8×5 ломают кладку стен целиком (давление выше порога, импульс — у кривой нет предела)
        double kpa = BlastDamage.quasiStaticKpa(50, 200);
        assertTrue(BlastDamage.breaks(kpa, kpa * 1000 * BlastDamage.ventSeconds(200, 8), BlastModel.kpa(12 * BlockResponse.JITTER_MAX)));
    }

    /** Сломанный блок у порога гасит волну, глубоко в зоне — почти не мешает. */
    @Test
    void brokenBlockPassesTheRest() {
        double pc = BlastModel.kpa(5);
        double edge = BlastDamage.breakRange(pc, 50);
        double near = BlastDamage.passes(BlastModel.surfaceOverpressureKpa(1, 50), BlastModel.chemicalImpulse(1, 50, true), pc);
        double far = BlastDamage.passes(BlastModel.surfaceOverpressureKpa(edge * 0.98, 50), BlastModel.chemicalImpulse(edge * 0.98, 50, true), pc);
        assertTrue(near > 0.5, "у заряда " + near);
        assertTrue(far < 0.1, "у края " + far);
        assertEquals(0, BlastDamage.passes(BlastModel.surfaceOverpressureKpa(edge * 1.2, 50), BlastModel.chemicalImpulse(edge * 1.2, 50, true), pc));
    }

    /** Воронка шахеда в сухом грунте — 2,4 м в диаметре, 0,6 м глубиной; шар топлива 30 кг — 18 м, 1,4 с, ~9 кал/см². */
    @Test
    void craterAndFuelFireball() {
        assertEquals(1.2, CraterModel.chargeRadius(50, CraterModel.Soil.DRY), 0.01);
        assertEquals(0.6, CraterModel.chargeDepth(50, CraterModel.Soil.DRY), 0.01);
        assertEquals(Math.PI / 4 * Math.pow(1.2, 3), CraterModel.chargeVolume(50, CraterModel.Soil.DRY), 0.02);
        assertEquals(18.0, FuelFireball.diameter(30), 0.1);
        assertEquals(1.40, FuelFireball.seconds(30), 0.01);
        assertEquals(9.2, FuelFireball.exposure(30), 0.2);
        assertEquals(0.46, ThermalModel.ignitionShare(FuelFireball.exposure(30)), 0.02);
        assertEquals(0, ThermalModel.ignitionShare(4.9));
    }
}
