package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BallisticsTest {
    /** 500 blocks at 50° and real g - about 10 s the flight; further - longer. */
    @Test
    void flightTimeMatchesBallistics() {
        int n = Ballistics.ticksFor(Vec3.ZERO, new Vec3(0, 0, 500), 50, 50);
        assertTrue(n > 180 && n < 240, "tick " + n);
        assertTrue(Ballistics.ticksFor(Vec3.ZERO, new Vec3(0, 0, 1000), 50, 50) > n);
    }

    /** Close or the target is much higher - no less than the minimum: the pipes rise steeper. */
    @Test
    void closeOrHighTargetStillArcs() {
        assertTrue(Ballistics.ticksFor(Vec3.ZERO, new Vec3(0, 0, 20), 50, 50) >= 50);
        assertTrue(Ballistics.ticksFor(Vec3.ZERO, new Vec3(0, 400, 100), 50, 50) >= 50);
    }

    /** Step by step (speed, then severity), the projectile arrives exactly at the target at tick N and coincides with the formula. */
    @Test
    void discreteParabolaLandsExactly() {
        Vec3 from = new Vec3(3, 70, -8), to = new Vec3(-120, 64, 390);
        int n = Ballistics.ticksFor(from, to, 50, 50);
        Vec3 v0 = Ballistics.launchVelocity(from, to, n);
        Vec3 p = from, v = v0;
        for (int k = 0; k < n; k++) {
            p = p.add(v);
            v = v.add(0, -Ballistics.GRAVITY, 0);
            assertEquals(0, p.distanceTo(Ballistics.at(from, v0, k + 1)), 1.0e-6);
        }
        assertEquals(0, p.distanceTo(to), 1.0e-6);
    }
}
