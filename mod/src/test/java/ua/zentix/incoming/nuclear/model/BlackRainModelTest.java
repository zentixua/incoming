package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackRainModelTest {
    /** Как в Хиросиме: 15 кт воздушный, пожары до 2 км, ветер 2,5 м/с на восток (+x). */
    private static final BlackRainModel HIROSHIMA = new BlackRainModel(15, 2000, 0, 2.5, 1);

    @Test
    void noFiresNoRain() {
        BlackRainModel dry = new BlackRainModel(15, 0, 0, 2.5, 1);
        for (double s = 0; s < 2000; s += 10) assertFalse(dry.raining(0, 0, s));
        assertEquals(0, dry.depositH1(0, 0, 1e6), 0);
        assertEquals(0, dry.endSeconds(), 0);
    }

    /** Над пожарами — через 20 минут после подрыва и полтора часа, на часах шторма — в 15 раз быстрее. */
    @Test
    void overTheFiresWhileTheyBurn() {
        double start = BlackRainModel.START_S / BlackRainModel.COMPRESSION;
        assertEquals(80, start, 1e-9);
        assertFalse(HIROSHIMA.raining(0, 0, start - 1));
        assertTrue(HIROSHIMA.raining(0, 0, start + 1));
        assertTrue(HIROSHIMA.raining(-1500, 0, start + 300));
        assertFalse(HIROSHIMA.raining(0, 0, start + BlackRainModel.durationSeconds() + 1));
    }

    /** Uda: сильный дождь — 19 × 11 км по ветру, слабый — до 29 км; против ветра за пожарами дождя нет. */
    @Test
    void streakDownwindLikeHiroshima() {
        double late = 1e9;
        assertTrue(HIROSHIMA.startSeconds(17000, 0) < late);
        assertTrue(HIROSHIMA.startSeconds(17000, 5000) < late, "полуширина ~5,5 км");
        assertFalse(HIROSHIMA.startSeconds(17000, 7000) < late);
        assertFalse(HIROSHIMA.startSeconds(25000, 0) < late);
        assertFalse(HIROSHIMA.startSeconds(-3000, 0) < late);
        // по ветру дождь приходит позже — на путь облака
        double near = HIROSHIMA.startSeconds(2000, 0), far = HIROSHIMA.startSeconds(11000, 0);
        assertEquals(9000 / 2.5 / BlackRainModel.COMPRESSION, far - near, 1e-6);
        assertTrue(HIROSHIMA.endSeconds() > far + BlackRainModel.durationSeconds());
    }

    /**
     * Вымытое на землю у воздушного подрыва — между Хиросимой (Кои–Такасу, 1–3 Р за всё время) и Нагасаки (Нисияма,
     * 20–40 Р): доза на открытом месте у зоны пожаров с начала дождя и навсегда, закон t^−1.2.
     */
    @Test
    void airBurstRainIsWeaklyRadioactive() {
        double start = HIROSHIMA.startSeconds(2500, 0);
        assertEquals(0, HIROSHIMA.depositH1(2500, 0, start - 1), 0);
        double h1 = HIROSHIMA.depositH1(2500, 0, start);
        double hours = start * 20 / 1000;
        double total = 5 * h1 * Math.pow(hours, -0.2);
        assertTrue(total > 1 && total < 40, "доза за всё время, Р: " + total);
        assertEquals(0, new BlackRainModel(15, 2000, 0, 2.5, 0).depositH1(2500, 0, start), 0, "у наземного всё — в следе");
        assertTrue(HIROSHIMA.depositH1(15000, 0, 1e6) < h1, "по ветру слабее");
    }
}
