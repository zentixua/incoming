package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ua.zentix.incoming.nuclear.model.SicknessModel.Stage;

class SicknessModelTest {
    private static final double DAY = 24;

    @Test
    void belowOneGrayNothing() {
        assertEquals(Stage.NONE, SicknessModel.stage(0.9, 0, true));
        assertEquals(Stage.NONE, SicknessModel.stage(0.9, 100, true));
        assertEquals(Double.POSITIVE_INFINITY, SicknessModel.onsetHours(0.5));
        assertNull(SicknessModel.course(0.5));
        assertEquals(0, SicknessModel.deathChance(0.9), 0);
    }

    /** G&D табл. 12.108: при 2 Гр не умирает почти никто, при 2–6 Гр — до 80 %, при 6–10 Гр — 80–100 %; ЛД50 — 4,5 Гр. */
    @Test
    void deathChanceFollowsLd50() {
        assertTrue(SicknessModel.deathChance(2) < 0.01);
        assertEquals(0.5, SicknessModel.deathChance(SicknessModel.LD50), 1e-9);
        assertEquals(0.8, SicknessModel.deathChance(6), 0.02);
        assertTrue(SicknessModel.deathChance(10) > 0.98);
        double prev = 0;
        for (double d = 1; d < 20; d += 0.25) {
            assertTrue(SicknessModel.deathChance(d) >= prev, "кривая не убывает");
            prev = SicknessModel.deathChance(d);
        }
    }

    /** Одна и та же случайность: с ростом дозы облучённый может стать обречённым, но не наоборот. */
    @Test
    void fateIsAnIndividualThreshold() {
        double fate = 0.3;
        assertFalse(SicknessModel.fated(3, fate));
        assertTrue(SicknessModel.fated(6, fate));
        assertFalse(SicknessModel.fated(4.5, 0.51));
        assertTrue(SicknessModel.fated(4.5, 0.49));
    }

    @Test
    void mildRecovers() {
        double d = 1.5;
        SicknessModel.Course c = SicknessModel.course(d);
        assertTrue(c.onset() > 2 && c.onset() < 3, "рвота через 2–3 ч");
        assertEquals(Stage.NONE, SicknessModel.stage(d, 2, false));
        assertEquals(Stage.PRODROMAL, SicknessModel.stage(d, 3, false));
        assertEquals(Stage.LATENT, SicknessModel.stage(d, 2 * DAY, false));
        assertEquals(Stage.LATENT, SicknessModel.stage(d, 14 * DAY, false));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(d, 25 * DAY, false));
        assertEquals(Stage.NONE, SicknessModel.stage(d, 60 * DAY, false));
        assertEquals(1.5, SicknessModel.hungerMultiplier(d), 0);
    }

    /** Кроветворная форма: скрытый период — недели, обречённые умирают через 4–6 недель, выжившие поправляются. */
    @Test
    void haematopoieticWeeks() {
        double d = 4;
        assertTrue(SicknessModel.onsetHours(d) >= 1 && SicknessModel.onsetHours(d) <= 2);
        assertEquals(Stage.LATENT, SicknessModel.stage(d, 7 * DAY, true));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(d, 20 * DAY, true));
        double death = SicknessModel.course(d).death();
        assertTrue(death >= 4 * 7 * DAY && death <= 6 * 7 * DAY, "смерть через 4–6 недель: " + death / DAY);
        assertEquals(Stage.FATAL, SicknessModel.stage(d, death + 1, true));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(d, death + 1, false));
        assertEquals(Stage.NONE, SicknessModel.stage(d, 70 * DAY, false));
        assertEquals(4, SicknessModel.maxHealthPenalty(d), 0);
        assertEquals(2, SicknessModel.hungerMultiplier(d), 0);
        assertTrue(SicknessModel.noRegeneration(d));
    }

    /** Кишечная форма (10–50 Гр): смерть через 5–14 дней. */
    @Test
    void gastrointestinalDays() {
        for (double d : new double[]{10, 20, 40}) {
            double death = SicknessModel.course(d).death();
            assertTrue(death >= 1.5 * DAY && death <= 14 * DAY, d + " Гр: " + death / DAY);
            assertEquals(Stage.FATAL, SicknessModel.stage(d, death, true));
        }
        assertEquals(10, SicknessModel.maxHealthPenalty(10), 0);
        assertEquals(1, SicknessModel.weaknessAmplifier(10));
    }

    /** Мозговая форма: при 50 Гр — полтора дня, дальше быстрее; у больших доз все стадии сжимаются к смерти по порядку. */
    @Test
    void veryHighDosesKillFast() {
        assertEquals(36, SicknessModel.course(50).death(), 1e-9);
        assertTrue(SicknessModel.course(1000).death() < 1);
        for (double d : new double[]{1, 2, 6, 10, 50, 200, 1000, 1e5}) {
            SicknessModel.Course c = SicknessModel.course(d);
            assertTrue(c.onset() <= c.latentFrom() && c.latentFrom() <= c.manifestFrom() && c.manifestFrom() < c.death(), d + " Гр: " + c);
            assertTrue(c.manifestFrom() <= c.recovery(), d + " Гр: " + c);
        }
        assertEquals(Stage.PRODROMAL, SicknessModel.stage(1000, 0.1, true));
        assertEquals(Stage.FATAL, SicknessModel.stage(1000, 0.5, true));
    }
}
