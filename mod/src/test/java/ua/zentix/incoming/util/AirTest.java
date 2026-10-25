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
        // у земли (звук): ясно — опорные 70 %, в дождь насыщен, как и наверху
        assertEquals(Air.SURFACE_CLEAR, day.surfaceHumidity(), 1e-12);
        assertEquals(1, rain.surfaceHumidity(), 1e-12);
        assertEquals(Air.SURFACE_CLEAR + (1 - Air.SURFACE_CLEAR) / 2, Air.of(NOON, 1, 0.5, 0).surfaceHumidity(), 1e-12);
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

    /**
     * Шлагбаум картинки и звука ({@code ProbeScenario.air}, сценарий клиента) читает воздух мира отражением — класс пробы
     * один и в дереве 3.4.1, где воздуха нет. Переименование здесь сломало бы ему сравнение воздуха сцены с эталоном:
     * имена держит этот тест.
     */
    @Test
    void probeReadsTheAirByTheseNames() throws ReflectiveOperationException {
        Class<?> c = Class.forName("ua.zentix.incoming.util.Air");
        Object air = c.getMethod("of", long.class, double.class, double.class, double.class).invoke(null, 10_084L, -1.0, 0.0, 0.0);
        Object wind = c.getMethod("wind").invoke(air);
        double speed = (double) c.getMethod("speed", double.class).invoke(air, 73.0);
        double direction = (double) wind.getClass().getMethod("direction").invoke(wind);
        double stability = (double) c.getMethod("stability").invoke(air);
        assertEquals(Air.of(10_084L, -1, 0, 0).speed(73), speed, 1e-12);
        assertEquals(Wind.at(10_084L).direction(), direction, 1e-12);
        assertEquals(Air.of(10_084L, -1, 0, 0).stability(), stability, 1e-12);
    }
}
