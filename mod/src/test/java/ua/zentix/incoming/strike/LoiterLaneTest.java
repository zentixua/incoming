package ua.zentix.incoming.strike;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.flight.ProximityFuse;
import ua.zentix.incoming.guidance.Route;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * “Lancet” salvo strip ({@code StrikeService.loiterRoute}): from a catapult - only after the cocking gate, before it - along the
 *  verified launch sector; without a lane - the path is as it was.
 */
class LoiterLaneTest {
    private static final Vec3 RAIL = new Vec3(0, 70, 0);
    /** The catapult looks at +z: the gate is on its course in cocking range. */
    private static final Vec3 GATE = RAIL.add(0, 0, ProximityFuse.ARM_DISTANCE);

    @Test
    void noLaneKeepsOldPath() {
        assertNull(StrikeService.loiterRoute(Waypoints.NONE, RAIL, new Vec3(0, 64, 600), 0, GATE));
        Waypoints via = new Waypoints(List.of(new Vec3(0, 0, 400)));
        assertEquals(via.points(), StrikeService.loiterRoute(via, RAIL, new Vec3(0, 64, 600), 0, GATE).points());
    }

    /** From the catapult: the first point is the gate on the course, then the runway; there is no shift to the gate. */
    @Test
    void laneStartsAfterArmingGate() {
        Route r = StrikeService.loiterRoute(Waypoints.NONE, RAIL, new Vec3(0, 64, 800), 20, GATE);
        List<Vec3> pts = r.points();
        assertEquals(0, pts.getFirst().x, 1e-9, "the first point is the gate on the catapult course: " + pts);
        assertEquals(GATE.z, pts.getFirst().z, 1e-9);
        assertTrue(pts.size() >= 3 && pts.subList(1, pts.size()).stream().allMatch(p -> p.z > GATE.z && Math.abs(Math.abs(p.x) - 20) < 1e-6),
                "after the gate there is a strip: " + pts);
    }

    /** The target is closer to the gate or the direct path from it is shorter than the exit to the lane and convergence - without a lane. */
    @Test
    void shortPathsGetNoLane() {
        WeaponSpec.Lanes lanes = WeaponType.LOITER.spec().lanes();
        assertNull(StrikeService.loiterRoute(Waypoints.NONE, RAIL, new Vec3(0, 64, ProximityFuse.ARM_DISTANCE - 10), 20, GATE));
        double tooShort = GATE.z + lanes.exit() + lanes.converge() - 10;
        assertNull(StrikeService.loiterRoute(Waypoints.NONE, RAIL, new Vec3(0, 64, tooShort), 20, GATE));
        // from afar (without a catapult) - from the launch site
        assertNull(StrikeService.loiterRoute(Waypoints.NONE, RAIL, new Vec3(0, 64, lanes.exit() + lanes.converge() - 10), 20, null));
        assertTrue(StrikeService.loiterRoute(Waypoints.NONE, RAIL, new Vec3(0, 64, 600), 20, null).size() == 2);
    }
}
