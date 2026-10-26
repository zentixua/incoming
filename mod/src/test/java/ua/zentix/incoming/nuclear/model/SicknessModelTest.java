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

    /** G&D tab. 12.108: with 2 Gr, almost no one dies, with 2–6 Gr—up to 80%, with 6–10 Gr—80–100%; LD50 - 4,5 Gr. */
    @Test
    void deathChanceFollowsLd50() {
        assertTrue(SicknessModel.deathChance(2) < 0.01);
        assertEquals(0.5, SicknessModel.deathChance(SicknessModel.LD50), 1e-9);
        assertEquals(0.8, SicknessModel.deathChance(6), 0.02);
        assertTrue(SicknessModel.deathChance(10) > 0.98);
        double prev = 0;
        for (double d = 1; d < 20; d += 0.25) {
            assertTrue(SicknessModel.deathChance(d) >= prev, "the curve does not decrease");
            prev = SicknessModel.deathChance(d);
        }
    }

    /** It’s one and the same accident: with increasing dose, the irradiated person can become doomed, but not vice versa. */
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
        assertTrue(c.onset() > 2 && c.onset() < 3, "vomiting after 2–3 hours");
        assertEquals(Stage.NONE, SicknessModel.stage(d, 2, false));
        assertEquals(Stage.PRODROMAL, SicknessModel.stage(d, 3, false));
        assertEquals(Stage.LATENT, SicknessModel.stage(d, 2 * DAY, false));
        assertEquals(Stage.LATENT, SicknessModel.stage(d, 14 * DAY, false));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(d, 25 * DAY, false));
        assertEquals(Stage.NONE, SicknessModel.stage(d, 60 * DAY, false));
        assertEquals(1.5, SicknessModel.hungerMultiplier(d), 0);
    }

    /** Hematopoietic form: latent period - weeks, the doomed die after 4–6 weeks, the survivors recover. */
    @Test
    void haematopoieticWeeks() {
        double d = 4;
        assertTrue(SicknessModel.onsetHours(d) >= 1 && SicknessModel.onsetHours(d) <= 2);
        assertEquals(Stage.LATENT, SicknessModel.stage(d, 7 * DAY, true));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(d, 20 * DAY, true));
        double death = SicknessModel.course(d).death();
        assertTrue(death >= 4 * 7 * DAY && death <= 6 * 7 * DAY, "death in 4–6 weeks: " + death / DAY);
        assertEquals(Stage.FATAL, SicknessModel.stage(d, death + 1, true));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(d, death + 1, false));
        assertEquals(Stage.NONE, SicknessModel.stage(d, 70 * DAY, false));
        assertEquals(4, SicknessModel.maxHealthPenalty(d), 0);
        assertEquals(2, SicknessModel.hungerMultiplier(d), 0);
        assertTrue(SicknessModel.noRegeneration(d));
    }

    /** Intestinal form (10–50 Gy): death in days - 12 with 10 Gy, about two with 40 (shorter for the brain form). */
    @Test
    void gastrointestinalDays() {
        for (double d : new double[]{10, 20, 40}) {
            double death = SicknessModel.course(d).death();
            assertTrue(death >= 1.5 * DAY && death <= 14 * DAY, d + " Gr: " + death / DAY);
            assertEquals(Stage.FATAL, SicknessModel.stage(d, death, true));
        }
        assertEquals(10, SicknessModel.maxHealthPenalty(10), 0);
        assertEquals(1, SicknessModel.weaknessAmplifier(10));
    }

    /** Brain form: at 50 Gr - one and a half days, then faster; in large doses, all stages are compressed towards death in order. */
    @Test
    void veryHighDosesKillFast() {
        assertEquals(36, SicknessModel.course(50).death(), 1e-9);
        assertTrue(SicknessModel.course(1000).death() < 1);
        for (double d : new double[]{1, 2, 6, 10, 50, 200, 1000, 1e5}) {
            SicknessModel.Course c = SicknessModel.course(d);
            assertTrue(c.onset() <= c.latentFrom() && c.latentFrom() <= c.manifestFrom() && c.manifestFrom() < c.death(), d + " Gr: " + c);
            assertTrue(c.manifestFrom() <= c.recovery(), d + " Gr: " + c);
        }
        assertEquals(Stage.PRODROMAL, SicknessModel.stage(1000, 0.1, true));
        assertEquals(Stage.FATAL, SicknessModel.stage(1000, 0.5, true));
    }
}
