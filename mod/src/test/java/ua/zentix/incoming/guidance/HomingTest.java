package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomingTest {
    private static double angle(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.normalize().dot(b.normalize())))));
    }

    /** Target from the side: turn exactly to the limit, in the plane of both directions, towards the target. */
    @Test
    void turnIsLimitedAndTowardsWant() {
        Vec3 dir = new Vec3(1, 0, 0), want = new Vec3(0, 0, 1);
        Vec3 got = Homing.turn(dir, want, 9);
        assertEquals(9, angle(dir, got), 1e-9);
        assertEquals(81, angle(got, want), 1e-9);
        assertEquals(0, got.y, 1e-12, "the rotation is out of plane");
        assertEquals(1, got.length(), 1e-12);
    }

    /** The target is within the turn - heading straight towards it. */
    @Test
    void smallTurnReachesWant() {
        Vec3 want = new Vec3(1, 0.1, 0).normalize();
        assertEquals(want, Homing.turn(new Vec3(1, 0, 0), want, 9));
    }

    /** The target is directly behind - turn through the top, at the vertical course - through +X; doesn't get stuck. */
    @Test
    void oppositeTurnsSomewhere() {
        Vec3 got = Homing.turn(new Vec3(1, 0, 0), new Vec3(-1, 0, 0), 9);
        assertEquals(9, angle(new Vec3(1, 0, 0), got), 1e-9);
        assertTrue(got.y > 0, got.toString());
        Vec3 up = Homing.turn(new Vec3(0, 1, 0), new Vec3(0, -1, 0), 9);
        assertEquals(9, angle(new Vec3(0, 1, 0), up), 1e-9);
        assertTrue(up.x > 0, up.toString());
    }

    /** Repeating turns brings the course to the target beyond the corner/tick limit. */
    @Test
    void repeatedTurnsConverge() {
        Vec3 dir = new Vec3(0, 1, 0), want = new Vec3(0.3, -0.2, -1).normalize();
        double start = angle(dir, want);
        int ticks = 0;
        while (!dir.equals(want)) {
            dir = Homing.turn(dir, want, 9);
            ticks++;
            assertTrue(ticks <= Math.ceil(start / 9), "for " + ticks + " tick course did not reach target");
        }
    }

    /**
     * A fast missile passes through the target within a tick: at the ends of the tick they are 5 blocks apart, and in the middle of the tick -
     *  they meet.
     */
    @Test
    void closestApproachInsideTick() {
        Vec3 a0 = new Vec3(0, 0, 0), a1 = new Vec3(10, 0, 0), b0 = new Vec3(5, 5, 0), b1 = new Vec3(5, -5, 0);
        double s = Homing.closest(a0, a1, b0, b1);
        assertEquals(0.5, s, 1e-12);
        assertEquals(0, Homing.distanceAt(a0, a1, b0, b1, s), 1e-12);
        assertTrue(a0.distanceTo(b0) > 6 && a1.distanceTo(b1) > 6);
    }

    /** Diverging or standing - closest at the beginning of the tick; approaching after the end tick - at the end. */
    @Test
    void closestIsClampedToTick() {
        Vec3 o = Vec3.ZERO;
        assertEquals(0, Homing.closest(o, new Vec3(-1, 0, 0), new Vec3(5, 0, 0), new Vec3(6, 0, 0)), 1e-12);
        assertEquals(0, Homing.closest(o, o, new Vec3(5, 0, 0), new Vec3(5, 0, 0)), 1e-12);
        assertEquals(1, Homing.closest(o, new Vec3(1, 0, 0), new Vec3(10, 0, 0), new Vec3(10, 0, 0)), 1e-12);
    }
}
