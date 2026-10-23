package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Полоса снаряда залпа на маршруте ({@link Route#inLane}): выход в полосу, ровно параллельно пути, схождение к цели. */
class RouteLaneTest {
    private static final Vec3 ORIGIN = new Vec3(0, 0, 0);
    private static final double EXIT = 100, CONVERGE = 300;

    @Test
    void noOffsetKeepsRoute() {
        Route r = Route.operator(List.of(new Vec3(0, 0, 1000)), ORIGIN);
        assertSame(r, r.inLane(0, EXIT, CONVERGE, new Vec3(0, 64, 1500)));
    }

    /** Прямо на +z: точки на выходе (s = 100) и на схождении (r = 300), между ними — на полном сдвиге; цель на месте. */
    @Test
    void straightPathRunsParallelBetweenExitAndConvergence() {
        Vec3 target = new Vec3(0, 64, 1500);
        Route r = Route.operator(List.of(new Vec3(0, 0, 1000)), ORIGIN).inLane(10, EXIT, CONVERGE, target);
        List<Vec3> pts = r.points();
        assertEquals(3, pts.size(), "выход, точка оператора, схождение: " + pts);
        assertEquals(100, pts.get(0).z, 1e-6);
        assertEquals(1000, pts.get(1).z, 1e-6);
        assertEquals(1200, pts.get(2).z, 1e-6);
        // сторона — как side = +1 у Route.plan: на +z это −x
        for (Vec3 p : pts) assertEquals(-10, p.x, 1e-6, "не на полном сдвиге: " + pts);
        assertTrue(r.byOperator(), "маршрут оператора остался маршрутом оператора");
        // путь с полосой длиннее прямого лишь на сход и выход
        assertEquals(1500, r.remaining(ORIGIN, target), 1);
    }

    /** На повороте 90° точка — по биссектрисе: оба участка идут на сдвиге от пути. */
    @Test
    void cornerKeepsBothLegsInLane() {
        Vec3 corner = new Vec3(0, 0, 1000), target = new Vec3(1000, 64, 1000);
        Route r = Route.operator(List.of(corner), ORIGIN).inLane(10, EXIT, CONVERGE, target);
        Vec3 c = r.points().stream().filter(p -> Math.abs(p.z - 1000) < 20 && Math.abs(p.x) < 20).findFirst().orElseThrow();
        assertEquals(10, Math.abs(c.x), 1e-6, "от первого участка (x = 0): " + c);
        assertEquals(10, Math.abs(c.z - 1000), 1e-6, "от второго участка (z = 1000): " + c);
    }

    /**
     * Разворот почти назад (как петля пуска у стреляющего рядом с целью): две точки, каждая сбоку от своего участка, и на
     * обратном участке полоса — с той же стороны по ходу, а не переходит через путь.
     */
    @Test
    void hairpinKeepsLaneSideOnBothLegs() {
        Vec3 corner = new Vec3(0, 0, 1000), target = new Vec3(20, 64, 0);
        Route r = Route.operator(List.of(corner), ORIGIN).inLane(10, EXIT, CONVERGE, target);
        List<Vec3> near = r.points().stream().filter(p -> Math.abs(p.z - 1000) < 1).toList();
        assertEquals(2, near.size(), "у разворота — две точки: " + r.points());
        assertEquals(-10, near.get(0).x, 1e-6, "сбоку от первого участка (ход +z)");
        // обратный участок: ход от угла к цели, сдвиг по той же стороне (side = +1) — вправо от него на карте, +x
        double dx = target.x - corner.x, dz = target.z - corner.z, len = Math.hypot(dx, dz);
        for (Vec3 p : r.points().subList(r.points().indexOf(near.get(1)), r.points().size())) {
            double lateral = (p.x - corner.x) * (-dz / len) + (p.z - corner.z) * (dx / len);
            assertTrue(lateral > 0 && lateral <= 10 + 1e-6, "полоса обратного участка перешла на другую сторону: " + lateral + " в " + p);
        }
    }

    /** Точка ближе схождения к цели сдвинута не полностью: у цели сдвига нет. */
    @Test
    void lastPointNearTargetIsTapered() {
        Vec3 target = new Vec3(0, 64, 1150);
        Route r = Route.operator(List.of(new Vec3(0, 0, 1000)), ORIGIN).inLane(10, EXIT, CONVERGE, target);
        Vec3 last = r.points().getLast();
        assertEquals(1000, last.z, 1e-6, "после точки оператора новых точек нет: " + r.points());
        assertEquals(-10 * 150 / CONVERGE, last.x, 1e-6);
    }

    /** Точка ближе выхода к началу сдвинута не полностью: снаряд выходит в полосу с курса пуска. */
    @Test
    void firstPointNearStartIsTapered() {
        Vec3 target = new Vec3(0, 64, 2000);
        Route r = Route.operator(List.of(new Vec3(0, 0, 50), new Vec3(0, 0, 1000)), ORIGIN).inLane(10, EXIT, CONVERGE, target);
        assertEquals(-5, r.points().getFirst().x, 1e-6, "точка в 50 блоках от начала: " + r.points());
    }

    /** Прямой путь без точек (как у «Ланцета»): полоса — двумя точками; короче выхода и схождения — без точек. */
    @Test
    void directPathGetsLaneOnlyWhenLongEnough() {
        Route lane = new Route(List.of(), ORIGIN).inLane(-8, EXIT, CONVERGE, new Vec3(0, 64, 1000));
        assertEquals(2, lane.size(), "выход и схождение: " + lane.points());
        assertEquals(100, lane.points().get(0).z, 1e-6);
        assertEquals(700, lane.points().get(1).z, 1e-6);
        assertTrue(lane.points().stream().allMatch(p -> Math.abs(p.x - 8) < 1e-6), "не на полном сдвиге: " + lane.points());
        Route shortPath = new Route(List.of(), ORIGIN).inLane(-8, EXIT, CONVERGE, new Vec3(0, 64, 350));
        assertEquals(2, shortPath.size(), "выход и схождение перекрываются: " + shortPath.points());
        // перекрытие: сдвиг не доходит до полного ни в одной точке
        assertTrue(shortPath.points().stream().allMatch(p -> Math.abs(p.x) < 8 - 1e-6), "сдвиг полный на коротком пути: " + shortPath.points());
        assertEquals(0, new Route(List.of(), ORIGIN).inLane(-8, EXIT, CONVERGE, new Vec3(0, 64, 1.5)).size());
    }

    /** Маршрут пуска (петля в обход) в полосе: точки — на сдвиге от своих, длина почти та же. */
    @Test
    void loopRouteInLaneStaysNearPlan() {
        Vec3 target = new Vec3(0, 64, 400);
        Route plan = Route.plan(ORIGIN, target, new Vec3(0, 0, 1), 2100, 300, 1);
        Route lane = plan.inLane(32, EXIT, CONVERGE, target);
        assertEquals(plan.remaining(ORIGIN, target), lane.remaining(ORIGIN, target), 2 * 2 * 32, "длина пути");
        for (Vec3 p : plan.points()) {
            double nearest = lane.points().stream().mapToDouble(q -> Math.hypot(q.x - p.x, q.z - p.z)).min().orElseThrow();
            assertTrue(nearest <= 2 * 32 + 1e-6, "точка " + p + " без своей в полосе: " + lane.points());
        }
    }
}
