package ua.zentix.incoming.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ветер у земли места ({@link LocalWind}): поле — без поправки, склон — не застройка, квартал — шероховатость и улица по
 * морфометрии Macdonald 1998, холм и низина — по Taylor, Lee 1984.
 */
class LocalWindTest {
    /** Квартал: дома 10×10 м высотой 12 м, улицы по 6 м, земля на 64. */
    static double town(int x, int z) {
        return Math.floorMod(x, 16) < 10 && Math.floorMod(z, 16) < 10 ? 76 : 64;
    }

    /** Ровное поле — поправки нет на всех высотах; земля — поле. */
    @Test
    void openFieldIsTheWorldWind() {
        LocalWind w = LocalWind.of((x, z) -> 70, 0, 0, 1, 0);
        assertEquals(70, w.ground(), 1e-9);
        assertEquals(0, w.canopy(), 1e-9);
        for (double z = 1; z <= 1000; z *= 2) assertEquals(1, w.factor(z), 1e-9, z + " м");
    }

    /** Ровный склон 20° — земля, а не постройки: застройки нет, склон без вершины ветер почти не меняет. */
    @Test
    void slopeIsGroundNotBuildings() {
        LocalWind w = LocalWind.of((x, z) -> 100 + Math.floor(0.36 * x), 0.5, 0.5, 0, 1);
        assertEquals(0, w.canopy(), 1e-9, "склон снят фильтром земли");
        assertEquals(100, w.ground(), 1.01);
        assertEquals(1, w.factor(2), 0.1);
    }

    /**
     * Квартал (λp = 0,39, λf ≈ 0,47): высота вытеснения и шероховатость — в разбросе замеров плотной застройки (d/H 0,5–0,8,
     * z₀/H 0,05–0,15; Grimmond, Oke 1999), у глаза в улице ветер в разы слабее поля, растёт с высотой и выше высоты
     * смешения (3H) — как в поле.
     */
    @Test
    void streetIsShelteredBelowTheRoofs() {
        // место — перекрёсток улиц
        LocalWind w = LocalWind.of(LocalWindTest::town, 13, 13, 1, 0);
        assertEquals(64, w.ground(), 1e-9);
        assertEquals(12, w.canopy(), 0.5);
        assertTrue(w.displacement() / w.canopy() > 0.5 && w.displacement() / w.canopy() < 0.8, "d/H " + w.displacement() / w.canopy());
        assertTrue(w.roughness() / w.canopy() > 0.05 && w.roughness() / w.canopy() < 0.15, "z₀/H " + w.roughness() / w.canopy());
        double eye = w.factor(1.6);
        assertTrue(eye > 0.03 && eye < 0.3, "у глаза " + eye);
        double last = 0;
        for (double z = 1; z <= 36; z += 1) {
            double f = w.factor(z);
            assertTrue(f >= last - 1e-9, z + " м: " + f + " < " + last);
            last = f;
        }
        assertEquals(1, w.factor(36), 1e-9);
        assertEquals(1, w.factor(100), 1e-9);
    }

    /** Взрыв на крыше дома: над крышей ветер как на её высоте над улицей, а не у земли. */
    @Test
    void roofIsMeasuredFromTheStreet() {
        LocalWind street = LocalWind.of(LocalWindTest::town, 13, 13, 1, 0), roof = LocalWind.of(LocalWindTest::town, 5, 5, 1, 0);
        assertEquals(12, roof.base(), 1e-9);
        assertTrue(roof.factor(2) > street.factor(2), "над крышей сильнее, чем в улице");
        assertEquals(street.overGround(14), roof.overGround(14), 0.1);
    }

    /**
     * Высоту меряют от источника, а не от верха колонки первой точки ячейки кэша (ревью R468: крыша и улица рядом делили
     * ячейку 4 м, и улица брала ветер крыши): то же место от улицы — ветер улицы, от крыши — ветер крыши.
     */
    @Test
    void baseIsTheSourceNotTheCell() {
        LocalWind street = LocalWind.of(LocalWindTest::town, 13, 13, 1, 0), roof = LocalWind.of(LocalWindTest::town, 9, 5, 1, 0);
        assertEquals(street.factor(2), roof.from(64).factor(2), 0.05);
        assertEquals(roof.factor(2), street.from(76).factor(2), 0.1);
        assertTrue(roof.from(64).factor(2) < 0.3 * roof.factor(2), "улица " + roof.from(64).factor(2) + ", крыша " + roof.factor(2));
        assertEquals(LocalWind.OPEN, LocalWind.OPEN.from(100));
    }

    /**
     * Холм высотой 30 м с полушириной 40 м: на вершине у земли ветер сильнее, к высоте разгон убывает; в такой же
     * впадине — слабее.
     */
    @Test
    void hillTopIsWindierAndHollowIsCalmer() {
        LocalWind top = LocalWind.of((x, z) -> 64 + Math.floor(30 * Math.exp(-Math.log(2) * (x * x + z * z) / (40.0 * 40))), 0, 0, 1, 0);
        double f2 = top.factor(2), f50 = top.factor(50);
        assertTrue(f2 > 1.3 && f2 < 1.9, "у земли на вершине " + f2);
        assertTrue(f50 < f2 && f50 > 1, "в 50 м " + f50);
        LocalWind hollow = LocalWind.of((x, z) -> 94 - Math.floor(30 * Math.exp(-Math.log(2) * (x * x + z * z) / (40.0 * 40))), 0, 0, 1, 0);
        assertTrue(hollow.factor(2) < 0.9 && hollow.factor(2) >= 0.5, "в низине " + hollow.factor(2));
    }

    /** Неизвестный рельеф (незагруженный район) — поле. */
    @Test
    void unknownIsOpen() {
        assertEquals(LocalWind.OPEN, LocalWind.of((x, z) -> Double.NaN, 0, 0, 1, 0));
    }
    /** Скользящий минимум и максимум — как прямой перебор окна, у краёв и с неизвестным. */
    @Test
    void runningWindowIsTheWindow() {
        java.util.Random rnd = new java.util.Random(7);
        for (int trial = 0; trial < 200; trial++) {
            int m = 1 + rnd.nextInt(60), w = rnd.nextInt(8);
            double[] v = new double[m], out = new double[m];
            for (int i = 0; i < m; i++) v[i] = rnd.nextInt(10) == 0 ? Double.NaN : rnd.nextInt(100);
            for (boolean min : new boolean[]{true, false}) {
                LocalWind.run(v, out, w, min);
                for (int i = 0; i < m; i++) {
                    double r = Double.NaN;
                    for (int p = Math.max(0, i - w); p <= Math.min(m - 1, i + w); p++) {
                        if (Double.isNaN(v[p])) continue;
                        if (Double.isNaN(r) || (min ? v[p] < r : v[p] > r)) r = v[p];
                    }
                    assertEquals(r, out[i], 0, "m " + m + " w " + w + " i " + i);
                }
            }
        }
    }
}
