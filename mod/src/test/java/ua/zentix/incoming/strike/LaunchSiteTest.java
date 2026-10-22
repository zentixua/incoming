package ua.zentix.incoming.strike;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Место пусковой в районе места пуска из приказа ({@link LaunchSite#nearest}): расчёт ставит машину на ближайшую годную
 * площадку, а не пускает без пусковой. Места, где на Newisle пусковой не было (#394, кадры VXC4): берег (годное в 7
 * блоках, между кольцом прежних мест 13–38 блоков), озеро (суша в 50), лес на склоне (поляна в 72).
 */
class LaunchSiteTest {
    private static final int Y = 64;

    /** Земля по высоте без листвы и с листвой и по воде под ногами. */
    private record Field(IntBinaryOperator ground, IntBinaryOperator top, IntBinaryOperator water) implements LaunchSite.Ground {
        @Override
        public boolean ready(int x, int z) {
            return true;
        }

        @Override
        public int ground(int x, int z) {
            return ground.applyAsInt(x, z);
        }

        @Override
        public int top(int x, int z) {
            return top.applyAsInt(x, z);
        }

        @Override
        public boolean firm(int x, int y, int z) {
            return water.applyAsInt(x, z) == 0;
        }

        @Override
        public boolean dry(int x, int y, int z) {
            return water.applyAsInt(x, z) == 0;
        }

        @Override
        public int minY() {
            return -64;
        }
    }

    private static double dist(Vec3 site, int x, int z) {
        return Math.hypot(site.x - 0.5 - x, site.z - 0.5 - z);
    }

    @Test
    void areaGoesFromThePlaceOutwards() {
        int[][] area = LaunchSite.area(LaunchSite.PLACE_AREA, LaunchSite.PLACE_STEP);
        assertEquals(0, area[0][0]);
        assertEquals(0, area[0][1]);
        double prev = 0;
        for (int[] o : area) {
            double d = Math.hypot(o[0], o[1]);
            assertTrue(d >= prev && d <= LaunchSite.PLACE_AREA, "по дальности, в районе: " + d);
            prev = d;
        }
        double expected = Math.PI * Math.pow(LaunchSite.PLACE_AREA / LaunchSite.PLACE_STEP, 2);
        assertEquals(expected, area.length, expected * 0.02, "мест в круге");
        assertEquals(LaunchSite.PLACE_AREA + 9, LaunchSite.PLACE_REACH, 1e-9, "готово — район и зазор до соседней пусковой");
        assertTrue(LaunchPlaces.DISTANCE * 16 >= LaunchSite.PLACE_REACH, "район залпа накрывает место пусковой в любой точке чанка места");
    }

    /** Место в озере: суша в 50 блоках — пусковая на берегу, а не старт вне мира. */
    @Test
    void lakeSendsTheCrewToTheShore() {
        Field lake = new Field((x, z) -> Y, (x, z) -> Y, (x, z) -> Math.hypot(x, z) < 50 ? 1 : 0);
        Vec3 site = LaunchSite.nearest(lake, 0, 0, new LaunchSite.Budget(), s -> true);
        assertNotNull(site, "берег в районе");
        // весь квадрат 5×5 — на суше: середина не ближе 2 блоков от воды
        assertTrue(dist(site, 0, 0) >= 52 && dist(site, 0, 0) < 55, "ближайший берег: " + dist(site, 0, 0));
    }

    /** Лес: кроны и стволы всюду, поляна 8×8 в 72 блоках — пусковая на поляне. */
    @Test
    void forestSendsTheCrewToAClearing() {
        IntBinaryOperator clearing = (x, z) -> x >= 68 && x < 76 && z >= -4 && z < 4 ? 1 : 0;
        // стволы через 3 блока: верх без листвы скачет; кроны — на 8 над землёй
        Field forest = new Field((x, z) -> clearing.applyAsInt(x, z) == 1 || Math.floorMod(x, 3) != 0 || Math.floorMod(z, 3) != 0 ? Y : Y + 6,
                (x, z) -> clearing.applyAsInt(x, z) == 1 ? Y : Y + 8, (x, z) -> 0);
        Vec3 site = LaunchSite.nearest(forest, 0, 0, new LaunchSite.Budget(), s -> true);
        assertNotNull(site, "поляна в районе");
        assertTrue(site.x >= 70 && site.x <= 74 && Math.abs(site.z) <= 2, "на поляне: " + site);
    }

    /** Берег: годное в 7 блоках, между местами прежнего кольца (13–38 блоков) — его и берём. */
    @Test
    void shoreTakesTheNearestFlatSpot() {
        Field shore = new Field((x, z) -> x >= 5 ? Y : Y - 3 + Math.floorMod(x * 7 + z * 13, 5), (x, z) -> x >= 5 ? Y : Y + 2, (x, z) -> 0);
        Vec3 site = LaunchSite.nearest(shore, 0, 0, new LaunchSite.Budget(), s -> true);
        assertNotNull(site);
        assertTrue(dist(site, 0, 0) < 9, "ближайшее ровное: " + dist(site, 0, 0));
    }

    /**
     * Сектор закрыт — следующее место не ближе зазора к закрытому (его сектор упирается в те же деревья); место
     * сначала не годится, потом годится — берётся первое годное.
     */
    @Test
    void closedSectorClosesItsNeighbours() {
        Field flat = new Field((x, z) -> Y, (x, z) -> Y, (x, z) -> 0);
        List<Vec3> tried = new ArrayList<>();
        Vec3 site = LaunchSite.nearest(flat, 0, 0, new LaunchSite.Budget(), s -> {
            tried.add(s);
            return tried.size() == 3;
        });
        assertNotNull(site);
        assertEquals(3, tried.size());
        for (int i = 0; i < tried.size(); i++) {
            for (int j = 0; j < i; j++) assertTrue(tried.get(i).distanceTo(tried.get(j)) >= 9, "соседи закрытого: " + tried);
        }
    }

    /** Негде (море на весь район) — null, а цена — не больше места на проверку и не дороже предела. */
    @Test
    void openSeaHasNoSiteAndStaysInBudget() {
        Field sea = new Field((x, z) -> Y, (x, z) -> Y, (x, z) -> 1);
        LaunchSite.Budget budget = new LaunchSite.Budget();
        assertNull(LaunchSite.nearest(sea, 0, 0, budget, s -> true));
        assertTrue(budget.spent() <= 3 * LaunchSite.area(LaunchSite.PLACE_AREA, LaunchSite.PLACE_STEP).length && !budget.out(),
                "цена поиска: " + budget.spent());
        LaunchSite.Budget small = new LaunchSite.Budget(100);
        assertNull(LaunchSite.nearest(sea, 0, 0, small, s -> true));
        assertTrue(small.out(), "предел вышел — поиск встал");
    }
}
