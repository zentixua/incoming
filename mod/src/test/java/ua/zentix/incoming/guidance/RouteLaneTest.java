package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Salvo projectile strip on route ({@link Route#inLane}): entering the strip, exactly parallel to the path, convergence towards the target. */
class RouteLaneTest {
    private static final Vec3 ORIGIN = new Vec3(0, 0, 0);
    private static final double EXIT = 100, CONVERGE = 300;

    @Test
    void noOffsetKeepsRoute() {
        Route r = Route.operator(List.of(new Vec3(0, 0, 1000)), ORIGIN);
        assertSame(r, r.inLane(0, EXIT, CONVERGE, new Vec3(0, 64, 1500)));
    }

    /** Directly at +z: points at the exit (s = 100) and at the convergence (r = 300), between them - at full shift; the target is in place. */
    @Test
    void straightPathRunsParallelBetweenExitAndConvergence() {
        Vec3 target = new Vec3(0, 64, 1500);
        Route r = Route.operator(List.of(new Vec3(0, 0, 1000)), ORIGIN).inLane(10, EXIT, CONVERGE, target);
        List<Vec3> pts = r.points();
        assertEquals(3, pts.size(), "output, operator point, toe: " + pts);
        assertEquals(100, pts.get(0).z, 1e-6);
        assertEquals(1000, pts.get(1).z, 1e-6);
        assertEquals(1200, pts.get(2).z, 1e-6);
        // side - like side = +1 for Route.plan: on +z it is −x
        for (Vec3 p : pts) assertEquals(-10, p.x, 1e-6, "not at full shift: " + pts);
        assertTrue(r.byOperator(), "the operator's route remains the operator's route");
        // path with a lane exceeds the straight line only during entry and exit
        assertEquals(1500, r.remaining(ORIGIN, target), 1);
    }

    /** At the turn 90° the point is along a bisector: both sections are offset from the path. */
    @Test
    void cornerKeepsBothLegsInLane() {
        Vec3 corner = new Vec3(0, 0, 1000), target = new Vec3(1000, 64, 1000);
        Route r = Route.operator(List.of(corner), ORIGIN).inLane(10, EXIT, CONVERGE, target);
        Vec3 c = r.points().stream().filter(p -> Math.abs(p.z - 1000) < 20 && Math.abs(p.x) < 20).findFirst().orElseThrow();
        assertEquals(10, Math.abs(c.x), 1e-6, "from the first section (x = 0): " + c);
        assertEquals(10, Math.abs(c.z - 1000), 1e-6, "from the second section (z = 1000): " + c);
    }

    /**
     * A turn almost back (like a launch loop for a shooter next to the target): two points, each on the side of its own section, and on the
     *  opposite section the strip is on the same side along the path, and does not cross the path.
     */
    @Test
    void hairpinKeepsLaneSideOnBothLegs() {
        Vec3 corner = new Vec3(0, 0, 1000), target = new Vec3(20, 64, 0);
        Route r = Route.operator(List.of(corner), ORIGIN).inLane(10, EXIT, CONVERGE, target);
        List<Vec3> near = r.points().stream().filter(p -> Math.abs(p.z - 1000) < 1).toList();
        assertEquals(2, near.size(), "at the turn there are two points: " + r.points());
        assertEquals(-10, near.get(0).x, 1e-6, "on the side of the first section (stroke +z)");
        // return section: move from the corner to the target, shift along the same side (side = +1) - to the right of it on the map, +x
        double dx = target.x - corner.x, dz = target.z - corner.z, len = Math.hypot(dx, dz);
        for (Vec3 p : r.points().subList(r.points().indexOf(near.get(1)), r.points().size())) {
            double lateral = (p.x - corner.x) * (-dz / len) + (p.z - corner.z) * (dx / len);
            assertTrue(lateral > 0 && lateral <= 10 + 1e-6, "the return lane has crossed over to the other side: " + lateral + " in " + p);
        }
    }

    /** The point closer to the convergence to the target is not completely shifted: at the target there is no shift. */
    @Test
    void lastPointNearTargetIsTapered() {
        Vec3 target = new Vec3(0, 64, 1150);
        Route r = Route.operator(List.of(new Vec3(0, 0, 1000)), ORIGIN).inLane(10, EXIT, CONVERGE, target);
        Vec3 last = r.points().getLast();
        assertEquals(1000, last.z, 1e-6, "there are no new points after the operator dot: " + r.points());
        assertEquals(-10 * 150 / CONVERGE, last.x, 1e-6);
    }

    /** The point closer to the exit to the beginning is not completely shifted: the projectile enters the strip from the launch course. */
    @Test
    void firstPointNearStartIsTapered() {
        Vec3 target = new Vec3(0, 64, 2000);
        Route r = Route.operator(List.of(new Vec3(0, 0, 50), new Vec3(0, 0, 1000)), ORIGIN).inLane(10, EXIT, CONVERGE, target);
        assertEquals(-5, r.points().getFirst().x, 1e-6, "point in 50 blocks from the beginning: " + r.points());
    }

    /** Straight path without dots (like the Lancet): stripe - two dots; shorter than exit and convergence - without points. */
    @Test
    void directPathGetsLaneOnlyWhenLongEnough() {
        Route lane = new Route(List.of(), ORIGIN).inLane(-8, EXIT, CONVERGE, new Vec3(0, 64, 1000));
        assertEquals(2, lane.size(), "exit and toe: " + lane.points());
        assertEquals(100, lane.points().get(0).z, 1e-6);
        assertEquals(700, lane.points().get(1).z, 1e-6);
        assertTrue(lane.points().stream().allMatch(p -> Math.abs(p.x - 8) < 1e-6), "not at full shift: " + lane.points());
        Route shortPath = new Route(List.of(), ORIGIN).inLane(-8, EXIT, CONVERGE, new Vec3(0, 64, 350));
        assertEquals(2, shortPath.size(), "exit and toe overlap: " + shortPath.points());
        // overlap: the shift is not complete at any point
        assertTrue(shortPath.points().stream().allMatch(p -> Math.abs(p.x) < 8 - 1e-6), "full shift on a short path: " + shortPath.points());
        assertEquals(0, new Route(List.of(), ORIGIN).inLane(-8, EXIT, CONVERGE, new Vec3(0, 64, 1.5)).size());
    }

    /** Launch route (bypass loop) in the strip: points are shifted from their own, the length is almost the same. */
    @Test
    void loopRouteInLaneStaysNearPlan() {
        Vec3 target = new Vec3(0, 64, 400);
        Route plan = Route.plan(ORIGIN, target, new Vec3(0, 0, 1), 2100, 300, 1);
        Route lane = plan.inLane(32, EXIT, CONVERGE, target);
        assertEquals(plan.remaining(ORIGIN, target), lane.remaining(ORIGIN, target), 2 * 2 * 32, "path length");
        for (Vec3 p : plan.points()) {
            double nearest = lane.points().stream().mapToDouble(q -> Math.hypot(q.x - p.x, q.z - p.z)).min().orElseThrow();
            assertTrue(nearest <= 2 * 32 + 1e-6, "point " + p + " without yours in the strip: " + lane.points());
        }
    }
}
