package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Волна у земли по высоте подрыва: числа — Brode 1987 (ур. 62, подпрограмма PT), Glasstone &amp; Dolan 1977 (рис. 3.73,
 * 3.75, 3.76) и стандарт DNA (glasstone, MIT); сводка — design/nuke-film/sources-check.md, §1.
 */
class HobBlastTest {
    /** 15 кт на 592 м (как Хиросима: 240 м/кт^⅓). */
    private static final double W = 15, H = 592;

    /** Уравнение 62 до 2 psi: дальности по земле у 15 кт на 592 м (точно, ±1 %) и у 2 кт на 302 м (× ∛(2/15)). */
    @ParameterizedTest
    @CsvSource({"20, 358", "10, 998", "5, 1603", "2, 2833"})
    void brodeRanges(double psi, double metres) {
        assertEquals(metres, HobBlast.rangeFor(psi, H, W), metres * 0.01);
        double small = Math.cbrt(2 / 15.0);
        assertEquals(metres * small, HobBlast.rangeFor(psi, H * small, 2), metres * small * 0.01);
    }

    /** С кривыми Glasstone &amp; Dolan (рис. 3.73b, c, приведено к 15 кт) — в пределах 10 %; под подрывом у них ~30 psi. */
    @ParameterizedTest
    @CsvSource({"10, 1080", "5, 1620", "2, 2930", "1, 4550"})
    void matchesGlasstoneCurves(double psi, double metres) {
        assertEquals(metres, HobBlast.rangeFor(psi, H, W), metres * 0.1);
    }

    @Test
    void underTheBurst() {
        assertEquals(28.5, HobBlast.psi(0, H, W), 0.5);
    }

    /**
     * Дальняя зона — стандарт DNA: 0.3 psi у 15 кт на 592 м — на 10.4 км (подгонка Броуда дала бы 8.8 км и дальше ушла
     * бы в ноль). Дальше — слабая сферическая волна: давление спадает как 1/(r·√ln r) — быстрее 1/r, медленнее 1/r^1.5.
     */
    @Test
    void farFieldIsTheWeakShockStandard() {
        assertEquals(10_380, HobBlast.rangeFor(0.3, H, W), 200);
        double prev = 0;
        for (double g = 20_000; g < 200_000; g *= 1.3) {
            double p = HobBlast.psi(g, H, W);
            assertTrue(p > 0);
            if (prev > 0) {
                assertTrue(p * g < prev * g / 1.3, "быстрее 1/r: " + g);
                assertTrue(p * Math.pow(g, 1.5) > prev * Math.pow(g / 1.3, 1.5), "медленнее 1/r^1.5: " + g);
            }
            prev = p;
        }
    }

    /** Наземный: как в таблице 1.1 DESIGN-nuke (те же источники), в пределах 10 %. */
    @ParameterizedTest
    @CsvSource({
            "1, 20, 230", "1, 5, 440", "1, 3, 590", "1, 1, 1300", "1, 0.5, 2400",
            "15, 20, 570", "15, 5, 1090", "15, 3, 1450", "15, 1, 3200", "15, 0.5, 6000",
            "1000, 20, 2300", "1000, 5, 4400", "1000, 3, 5900", "1000, 1, 13000", "1000, 0.5, 24000",
    })
    void surfaceBurst(double yieldKt, double psi, double metres) {
        assertEquals(metres, HobBlast.rangeFor(psi, 0, yieldKt), metres * 0.1, () -> yieldKt + " кт, " + psi + " psi");
    }

    /** Давление падает с дальностью при любой высоте подрыва, и стык Броуда со стандартом DNA — без ступеньки. */
    @Test
    void fallsWithRangeAtEveryHeight() {
        for (double h : new double[]{0, 50, 150, 300, 592, 1000, 2000, 4000, 8000}) {
            double prev = Double.MAX_VALUE;
            for (double g = 1; g < 100_000; g *= 1.01) {
                double p = HobBlast.psi(g, h, W);
                double at = g;
                // у самой оси таблица по углу даёт рябь в стотысячные доли (точная формула спадает)
                assertTrue(p <= prev * (1 + 1e-6), () -> h + " м, " + at + " м");
                assertTrue(p >= prev * 0.97 || prev > 100, () -> "ступенька: " + h + " м, " + at + " м");
                prev = p;
            }
        }
    }

    /** Таблица — от точной формулы не дальше 0.5 % от 0.05 до 3000 psi. */
    @Test
    void tableMatchesFormula() {
        Random random = new Random(1);
        for (int n = 0; n < 20_000; n++) {
            double x = Math.exp(Math.log(0.003) + random.nextDouble() * Math.log(1e5)), y = switch (n % 4) {
                case 0 -> 0;
                case 1 -> random.nextDouble() * 0.2;
                case 2 -> random.nextDouble() * 3;
                default -> random.nextDouble() * 20;
            };
            double exact = HobBlast.exact(x, y);
            if (exact < 0.05 || exact > 3000) continue;
            // 1 кт: кфут/кт^⅓ в метры
            double table = HobBlast.psi(x * 304.8, y * 304.8, 1);
            assertEquals(exact, table, exact * 0.005, () -> x + ", " + y);
        }
    }

    /** Начало области Маха (Броуд, прил. B; G&amp;D рис. 3.73b — ~710 м). */
    @Test
    void machStem() {
        assertEquals(694, HobBlast.machStem(H, W), 5);
        assertEquals(354, HobBlast.machStem(302, 2), 5);
        assertEquals(0, HobBlast.machStem(0, W), 1e-9);
    }

    /** Приход фронта на землю (Броуд, PT): у 15 кт на 592 м; вдали фронт не медленнее звука. */
    @ParameterizedTest
    @CsvSource({"0, 0.79", "600, 1.40", "1000, 2.17", "2000, 4.64", "5000, 13.05", "10000, 27.5"})
    void arrival(double groundM, double seconds) {
        assertEquals(seconds, HobBlast.arrivalSeconds(groundM, H, W), seconds * 0.01);
    }

    @Test
    void frontNeverSlowerThanSound() {
        for (double h : new double[]{0, 592, 2000}) {
            for (double g = 100; g < 100_000; g *= 1.2) {
                double speed = (g * 1.2 - g) / (HobBlast.arrivalSeconds(g * 1.2, h, W) - HobBlast.arrivalSeconds(g, h, W));
                assertTrue(speed > 0.99 * BlastModel.SOUND_SPEED, h + " м, " + g + " м: " + speed);
            }
        }
    }

    /** Свободный воздух под подрывом приходит туда же, когда и на землю под ним. */
    @Test
    void freeAirMeetsTheGroundUnderTheBurst() {
        assertEquals(HobBlast.arrivalSeconds(0, H, W), HobBlast.freeAirArrivalSeconds(H, W), 0.01);
    }

    /** Положительная фаза на земле (Броуд, PT; G&amp;D рис. 3.76 — в пределах 15 %). */
    @ParameterizedTest
    @CsvSource({"0, 0.45", "1000, 0.74", "2000, 0.93", "10000, 1.15"})
    void positivePhase(double groundM, double seconds) {
        assertEquals(seconds, HobBlast.positivePhaseSeconds(groundM, H, W), 0.01);
    }

    /**
     * Напор вдоль земли (G&amp;D рис. 3.75, 15 кт на 592 м): под подрывом — ноль, 1 psi на 170 м, 2 psi на 450 м, ~4 psi у
     * начала области Маха; дальше — Ренкин–Гюгонио по давлению.
     */
    @Test
    void dynamicPressureAlongTheGround() {
        assertEquals(0, along(0), 1e-6);
        assertEquals(1, along(170), 0.25);
        assertEquals(2, along(450), 0.4);
        assertEquals(4, along(HobBlast.machStem(H, W)), 0.6);
        assertEquals(BlastModel.psi(BlastModel.dynamicPressureKpa(BlastModel.kpa(HobBlast.psi(1500, H, W)))), along(1500), 1e-9);
        assertEquals(1, HobBlast.alongGround(300, 0, W), 0);
    }

    private static double along(double groundM) {
        double q = BlastModel.dynamicPressureKpa(BlastModel.kpa(HobBlast.psi(groundM, H, W)));
        return BlastModel.psi(q) * HobBlast.alongGround(groundM, H, W);
    }
}
