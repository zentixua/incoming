package ua.zentix.incoming.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Воздух мира: класс устойчивости по солнцу, ветру и облачности, влажность по погоде, плотность и звук по ISO 2533. */
class AirTest {
    /** Полдень ясного дня — сутки игры с 6000-го тика: солнце в зените. */
    private static final long NOON = 6000;

    @Test
    void stabilityFollowsSunAndCloud() {
        Air day = Air.of(NOON, 1, 0, 0), night = Air.of(NOON + 12_000, -1, 0, 0), rain = Air.of(NOON, 1, 1, 0);
        assertTrue(day.stability() <= Stability.C, "ясный полдень неустойчив: " + day.stability());
        assertTrue(night.stability() >= Stability.D, "ясная ночь устойчива: " + night.stability());
        assertTrue(rain.stability() > day.stability(), "облачность тянет к нейтральному D");
        assertEquals(0.5, day.humidity(), 1e-12);
        assertEquals(1, rain.humidity(), 1e-12);
        assertTrue(night.brunt() >= day.brunt(), "ночь не менее устойчива (в сильный ветер — нейтрально)");
    }

    @Test
    void windGrowsWithHeight() {
        Air a = Air.of(NOON, 1, 0, 0);
        assertTrue(a.speed(Air.SEA + 100) > a.speed(Air.SEA + 10));
        assertEquals(a.wind().speed(), a.speed(Air.SEA + Wind.REFERENCE), 1e-9);
    }

    @Test
    void standardAtmosphere() {
        assertEquals(1.225, StandardAtmosphere.density(0), 0.001);
        assertEquals(340.3, StandardAtmosphere.soundSpeed(0), 0.1);
        assertEquals(295.1, StandardAtmosphere.soundSpeed(12_000), 0.1);
        assertEquals(0.0105, Stability.STANDARD_N, 0.0002);
    }
}
