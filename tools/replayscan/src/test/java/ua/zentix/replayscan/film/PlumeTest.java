package ua.zentix.replayscan.film;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Impact smoke model based on Incoming rendering code: a particle path follows the mod tick; the Grad fireball
 * cloud and dust ring are where the 13.10 pass frames saw them (camera 13–18 blocks from impact after 3–5 s is in
 * smoke); the column lasts minutes and leans with the wind; a subject's own smoke does not obscure the view of it.
 */
class PlumeTest {
    private static final Vec3 AT = new Vec3(0, 64, 0);

    @Test
    void particleMovesLikeTheModTick() {
        // fireball cloud billow: velocity (0.2; 0.3; −0.1), drag 0.9, buoyancy 0.011, from the FxParticle tick
        double x = 0, y = 0, z = 0, vx = 0.2, vy = 0.3, vz = -0.1;
        for (int age = 1; age <= 300; age++) {
            vy += 0.011 * (0.15 + 0.85 * Math.exp(-age / 40.0));
            vx += Plume.WIND_X;
            vz += Plume.WIND_Z;
            x += vx;
            y += vy;
            z += vz;
            vx *= 0.9;
            vy *= 0.9;
            vz *= 0.9;
            if (age == 1 || age == 40 || age == 300) {
                Plume.Motion m = Plume.BALL;
                assertEquals(x, 0.2 * m.move(age) + Plume.WIND_X * m.wind(age), 1e-9, "x at " + age);
                assertEquals(y, 0.3 * m.move(age) + m.rise(age), 1e-9, "y at " + age);
                assertEquals(z, -0.1 * m.move(age) + Plume.WIND_Z * m.wind(age), 1e-9, "z at " + age);
            }
        }
    }

    @Test
    void gradSmokeFillsTheAirBesideTheHit() {
        // pass 03:53 (13.10): camera 13–24 blocks from Grads and 4–7 above them after 3–5 s; the frame is full of smoke. The
        // previous model (column radius 8) saw clear air there: fireball cloud wider than 10 blocks, dust ring 20 blocks along the ground
        Plume grad = new Plume(100, AT, 8, Plume.Look.ROCKET);
        for (int u : new int[]{60, 100}) {
            assertTrue(grad.clearance(AT.add(8, 8, 0), 100 + u) < 0, "ball cloud 8 blocks beside, " + u + " ticks after");
            assertTrue(grad.clearance(AT.add(0, 3, -17), 100 + u) < 0, "dust surge 17 blocks beside, " + u + " ticks after");
        }
        assertTrue(grad.clearance(AT.add(45, 10, 0), 160) > 3, "45 blocks away — clear");
        assertTrue(grad.clearance(AT.add(25, 16, 0), 160) > 0, "over the dust surge — clear");
        assertFalse(grad.alive(99));
    }

    @Test
    void columnStandsForMinutesAndLeansDownwind() {
        Plume grad = new Plume(0, AT, 8, Plume.Look.ROCKET);
        int u = 1200;
        // after a minute: the column has risen ~80 blocks; wind has carried its cap tens of blocks east and south
        double rise = 1 - Math.exp(-3.0 * u / 2400), drift = Plume.WIND_X * Plume.DRIFT * u;
        Vec3 top = AT.add(drift, 105 * rise, Plume.WIND_Z * Plume.DRIFT * u);
        assertTrue(drift > 50, "drift " + drift);
        assertTrue(grad.clearance(top, u) < 0, "in the cap downwind");
        assertTrue(grad.clearance(AT.add(-drift, 105 * rise, -Plume.WIND_Z * Plume.DRIFT * u), u) > 0, "upwind of the cap — clear");
        assertTrue(grad.blocks(top.add(-80, 0, 0), top.add(80, 0, 0), u), "a look through the cap");
        // column lifetime: 3000 ticks
        assertTrue(grad.alive(2999));
        assertEquals(Double.POSITIVE_INFINITY, grad.clearance(AT.add(0, 10, 0), 3001));
    }

    @Test
    void ownSmokeDoesNotHideTheHit() {
        Plume shahed = new Plume(100, AT, 12, Plume.Look.DRONE);
        Vec3 eye = AT.add(-60, 8, 0);
        assertFalse(shahed.blocks(eye, AT.add(0, 4, 0), 160), "looking at the hit itself");
        assertTrue(shahed.blocks(eye, AT.add(60, 8, 0), 160), "looking through it at something beyond");
        // projectile high above an earlier impact: that impact smoke is not its own; bomb charge below the collapse: it is its own
        assertFalse(shahed.own(AT.add(3, 100, 0)), "a shell high above");
        assertTrue(shahed.own(AT.add(3, -20, 0)), "a charge under the crater");
    }

    @Test
    void lookFromThePlanRow() {
        assertSame(Plume.Look.ROCKET, Plume.of(new double[]{1, 0, 64, 0, 8, 1}).look());
        assertSame(Plume.Look.MISSILE, Plume.of(new double[]{1, 0, 64, 0, 18, 3}).look());
        assertNull(Plume.of(new double[]{1, 0, 64, 0, 120, 0}).look(), "a nuke has no blast smoke");
        assertFalse(Plume.of(new double[]{1, 0, 64, 0, 120, 0}).alive(50));
        // old plans without a kind: use the fireball diameter
        assertSame(Plume.Look.ROCKET, Plume.of(new double[]{1, 0, 64, 0, 8}).look());
        assertSame(Plume.Look.DRONE, Plume.of(new double[]{1, 0, 64, 0}).look());
        assertSame(Plume.Look.BUNKER, Plume.of(new double[]{1, 0, 64, 0, 24}).look());
    }

    @Test
    void deepBombSmokeIsAColumnFromTheCrater() {
        // bomb below the breakout boundary: no fireball or dust-ring particles; cloud appears immediately
        Plume deep = Plume.of(new double[]{100, 0, 64, 0, 24, 5});
        assertSame(Plume.Look.BUNKER_DEEP, deep.look());
        assertEquals(12, deep.shapes(130).size(), "only the column puffs");
        assertTrue(deep.shapes(130).stream().noneMatch(Plume.Shape::cylinder), "no dust surge");
        assertTrue(deep.clearance(AT.add(0, 8, 0), 160) < 0, "above the crater");
        assertTrue(deep.alive(3100) && !deep.alive(3101));
        // own smoke is determined horizontally: plan impact is the buried charge, smoke is above the surface
        assertFalse(deep.blocks(AT.add(-60, 8, 0), new Vec3(0, 40, 0), 160));
    }

    @Test
    void puffBehindTheEyeHidesNothing() {
        // billow quad faces the camera through its center: center behind the eye means quad behind the camera
        double half = 10;
        assertEquals(1, Smoke.cover(0, 0, 0, 0, 0, 40, 1600, 0, 0, 3, half, 0, 0, 40, 0), 1e-9, "in front");
        assertEquals(0, Smoke.cover(0, 0, 0, 0, 0, 40, 1600, 0, 0, -3, half, 0, 0, 40, 0), 1e-9, "behind");
    }

    @Test
    void cylinderAndBallShapes() {
        Plume.Shape ball = new Plume.Shape(0, 70, 0, 5, 0, false, 1);
        assertEquals(-5, ball.distance(0, 70, 0), 1e-9);
        assertEquals(5, ball.distance(10, 70, 0), 1e-9);
        assertTrue(ball.crosses(new Vec3(-10, 72, 0), new Vec3(10, 72, 0)));
        assertFalse(ball.crosses(new Vec3(-10, 76, 0), new Vec3(10, 76, 0)));
        Plume.Shape disk = new Plume.Shape(0, 64, 0, 20, 70, true, 1);
        assertTrue(disk.distance(10, 66, 0) < 0, "inside");
        assertEquals(4, disk.distance(10, 74, 0), 1e-9, "above");
        assertEquals(5, disk.distance(25, 66, 0), 1e-9, "beside");
        assertTrue(disk.crosses(new Vec3(-40, 68, 5), new Vec3(40, 68, 5)), "level through");
        assertTrue(disk.crosses(new Vec3(-30, 90, 0), new Vec3(30, 60, 0)), "down through the top");
        assertFalse(disk.crosses(new Vec3(-30, 75, 0), new Vec3(30, 72, 0)), "over the top");
        assertFalse(disk.crosses(new Vec3(-40, 68, 25), new Vec3(40, 68, 25)), "beside");
    }
}
