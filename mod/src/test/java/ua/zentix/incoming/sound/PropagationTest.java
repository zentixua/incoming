package ua.zentix.incoming.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropagationTest {
    private static final Spectrum FLAT = Spectrum.of(0, 0, 0, 0, 0, 0, 0, 0);

    @Test
    void groundFollowsIsoTable3() {
        // далеко (середина пути — вся): 63 Гц — отражение +6 дБ при любой земле; луг на верхах — без поправки
        double q = 1 - 30 * (2 + 1.7) / 5000;
        assertEquals(-3 - 3 * q, Propagation.ground(0, 5000, 2, 1.7, 1), 1e-9);
        assertEquals(0, Propagation.ground(6, 5000, 2, 1.7, 1), 1e-3);
        // твёрдая земля (G = 0) на 500 Гц: −1,5 у источника и у уха и −3 посередине
        assertEquals(-3 - 3 * q, Propagation.ground(3, 5000, 2, 1.7, 0), 1e-9);
        // луг у уха на 1,7 м гасит 250 Гц (b′ = 1,5 + 8,6·e^(−0,09·h²))
        assertEquals(8.6 * Math.exp(-0.09 * 1.7 * 1.7), Propagation.ground(2, 5000, 50, 1.7, 1), 1e-6);
    }

    @Test
    void divergenceIsSpherical() {
        // 20·lg d + 11: вдвое дальше — на 6 дБ тише
        assertEquals(6.02, Propagation.divergence(200) - Propagation.divergence(100), 0.01);
        assertEquals(11, Propagation.divergence(1), 1e-12);
    }

    @Test
    void shadowAtDayMatchesFarBlasts() {
        // зона тени — тот же закон, что у далёких взрывов (Outdoor): с 1 км у источника на 2 м, к 4 км — вся глубина
        assertEquals(1000, Propagation.shadowFrom(2, 1.7), 1e-9);
        assertEquals(0, Propagation.shadow(2000, 900, 2, 1.7, 1), 1e-12);
        assertEquals(20, Propagation.shadow(2000, 4000, 2, 1.7, 1), 1e-9);
        assertEquals(10, Propagation.shadow(63, 4000, 2, 1.7, 1), 1e-9);
        assertEquals(0, Propagation.shadow(2000, 4000, 2, 1.7, 0), 1e-12);
    }

    @Test
    void louderSourceIsHeardFartherAndNightFartherThanDay() {
        double prev = 0;
        for (double lw = 90; lw <= 150; lw += 10) {
            double r = Propagation.range(lw, FLAT, 20, Propagation.Weather.NIGHT, 50_000);
            assertTrue(r > prev, lw + " дБ: " + r);
            prev = r;
            assertTrue(Propagation.range(lw, FLAT, 20, Propagation.Weather.NOON, 50_000) < r, "днём ближе: " + lw);
            assertTrue(Propagation.range(lw, FLAT, 20, new Propagation.Weather(0, 1, 0, 0), 50_000) < r, "в дождь ближе: " + lw);
        }
    }

    @Test
    void toneIsHeardBelowTheBackgroundLevel() {
        // вся мощность в октаве 500 Гц: на краю слышимости её A-уровень ниже A-уровня фона — фон маскирует её только
        // своей частью в той же октаве
        Spectrum tone = Spectrum.of(-60, -60, -60, 0, -60, -60, -60, -60);
        double r = Propagation.range(110, tone, 20, Propagation.Weather.NIGHT, 50_000);
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(110, tone, Propagation.Path.over(r * 0.99, 20, Propagation.Weather.NIGHT), bands);
        assertTrue(Propagation.margin(bands, Propagation.Weather.NIGHT) > 0);
        assertTrue(level < Propagation.background(Propagation.Weather.NIGHT) - 5, "A-уровень на краю " + level);
    }

    @Test
    void backgroundBandsSumToTheBackground() {
        double sum = 0;
        for (int b = 0; b < Propagation.BANDS; b++) sum += Math.pow(10, Propagation.background(b, Propagation.Weather.NIGHT) / 10);
        assertEquals(Propagation.NIGHT, 10 * Math.log10(sum), 1e-9);
    }
}
